package forge.nova.online;

import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.Device;
import org.jupnp.model.meta.Service;
import org.jupnp.registry.Registry;
import org.jupnp.support.igd.PortMappingListener;
import org.jupnp.support.igd.callback.GetExternalIP;
import org.jupnp.support.model.PortMapping;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asks the router to forward the online port to this computer (UPnP), like classic Forge's
 * network lobby does. The mapping is removed again when the room closes or Nova exits.
 */
public final class PortMapper {
    public enum State { OFF, WORKING, OK, FAILED }

    private volatile State state = State.OFF;
    private volatile String detail;
    /** the router's own internet address (reported by the router; may differ from the public one behind CGNAT) */
    private volatile String routerIp;
    private UpnpService service;
    private int generation;
    private Thread shutdownHook;

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String routerIp() {
        return routerIp;
    }

    /** Starts mapping {@code port} (TCP) to {@code lanIp}; {@code onChange} runs when the outcome is known. */
    public synchronized void open(int port, String lanIp, Runnable onChange) {
        close();
        final int gen = ++generation;
        state = State.WORKING;
        detail = null;
        routerIp = null;
        Thread t = new Thread(() -> run(gen, port, lanIp, onChange), "Nova-UPnP");
        t.setDaemon(true);
        t.start();
    }

    private void run(int gen, int port, String lanIp, Runnable onChange) {
        UpnpService svc = null;
        boolean ok = false;
        String why = null;
        try {
            try {
                org.jupnp.util.SpecificationViolationReporter.disableReporting();
            } catch (Throwable ignored) {
                // older jupnp: nothing to silence
            }
            svc = new UpnpServiceImpl(new DefaultUpnpServiceConfiguration());
            svc.startup();
            final CompletableFuture<Boolean> done = new CompletableFuture<>();
            final String[] failure = new String[1];
            PortMapping pm = new PortMapping(port, lanIp, PortMapping.Protocol.TCP, "Forge Nova");
            PortMappingListener listener = new PortMappingListener(pm) {
                @Override
                public synchronized void deviceAdded(Registry registry, Device device) {
                    super.deviceAdded(registry, device);
                    Service<?, ?> connection = discoverConnectionService(device);
                    if (connection == null) {
                        return;
                    }
                    List<PortMapping> active = activePortMappings.get(connection);
                    if (active != null && !active.isEmpty()) {
                        done.complete(true);
                    }
                    registry.getUpnpService().getControlPoint().execute(new GetExternalIP(connection) {
                        @Override
                        protected void success(String externalIPAddress) {
                            routerIp = externalIPAddress;
                            onChange.run();
                        }

                        @Override
                        public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                            // the router's address is only a diagnostic
                        }
                    });
                }

                @Override
                protected void handleFailureMessage(String message) {
                    super.handleFailureMessage(message);
                    if (failure[0] == null) {
                        failure[0] = message;
                    } else if (message != null && message.startsWith("Reason:")) {
                        failure[0] = message.substring(7).trim();
                    }
                    // the reason follows in a second message; give it a moment
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                            // report now
                        }
                        done.complete(false);
                    }).start();
                }
            };
            svc.getRegistry().addListener(listener);
            svc.getControlPoint().search();
            try {
                ok = done.get(8, TimeUnit.SECONDS);
                if (!ok) {
                    why = "The router refused: " + (failure[0] == null ? "unknown reason" : failure[0]);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                why = "No router answered. UPnP may be switched off in the router settings.";
            }
        } catch (Throwable t) {
            why = "UPnP is not available: " + t;
        }
        synchronized (this) {
            if (gen != generation) {
                // closed or restarted meanwhile
                if (svc != null) shutdownQuietly(svc);
                return;
            }
            if (service != null) {
                shutdownQuietly(service);
            }
            service = svc;
            state = ok ? State.OK : State.FAILED;
            detail = why;
            if (ok && shutdownHook == null) {
                shutdownHook = new Thread(this::closeNow, "Nova-UPnP-cleanup");
                try {
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // already exiting
                }
            }
        }
        System.out.println("[Nova] UPnP port " + port + ": " + (ok ? "mapped" : why));
        onChange.run();
    }

    private static void shutdownQuietly(UpnpService svc) {
        try {
            svc.shutdown(); // removes the port mappings (PortMappingListener.beforeShutdown)
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Removes the mapping in the background. */
    public synchronized void close() {
        generation++;
        state = State.OFF;
        final UpnpService svc = service;
        service = null;
        if (svc != null) {
            Thread t = new Thread(() -> shutdownQuietly(svc), "Nova-UPnP-close");
            t.setDaemon(false); // let it finish removing the mapping even while Nova exits
            t.start();
        }
    }

    private void closeNow() {
        UpnpService svc;
        synchronized (this) {
            svc = service;
            service = null;
        }
        if (svc != null) {
            shutdownQuietly(svc);
        }
    }
}

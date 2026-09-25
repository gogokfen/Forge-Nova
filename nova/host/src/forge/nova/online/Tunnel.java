package forge.nova.online;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An internet link that needs no router setup: a Cloudflare "quick tunnel" (free, no account) that
 * forwards https://random-words.trycloudflare.com to the online port. Works behind carrier-grade NAT,
 * where port forwarding can't. Needs Cloudflare's cloudflared program; Nova does not download it.
 */
public final class Tunnel {
    public enum State { OFF, MISSING, STARTING, ON, ERROR }

    private static final Pattern URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    private final File novaDir;
    private volatile State state = State.OFF;
    private volatile String url;
    private volatile String error;
    private Process process;
    private Thread shutdownHook;

    public Tunnel(File novaDir) {
        this.novaDir = novaDir;
    }

    public State state() {
        return state;
    }

    public String url() {
        return url;
    }

    public String error() {
        return error;
    }

    /** cloudflared in nova/tools, on the PATH or in its default install folders; null if absent. */
    public File findExecutable() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = win ? "cloudflared.exe" : "cloudflared";
        List<File> candidates = new ArrayList<>();
        candidates.add(new File(new File(novaDir, "tools"), exe));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) candidates.add(new File(dir.trim(), exe));
            }
        }
        if (win) {
            String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
            candidates.add(new File(pf86 + "\\cloudflared\\cloudflared.exe"));
            candidates.add(new File(pf + "\\cloudflared\\cloudflared.exe"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                candidates.add(new File(local + "\\Microsoft\\WinGet\\Links\\cloudflared.exe"));
            }
        } else {
            candidates.add(new File("/usr/local/bin/cloudflared"));
            candidates.add(new File("/opt/homebrew/bin/cloudflared"));
            candidates.add(new File("/usr/bin/cloudflared"));
        }
        for (File f : candidates) {
            if (f.isFile() && f.canExecute()) {
                return f;
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return findExecutable() != null;
    }

    /** Starts the tunnel to the local {@code port}; {@code onChange} runs whenever the state changes. */
    public synchronized void start(int port, Runnable onChange) {
        stop();
        File exe = findExecutable();
        if (exe == null) {
            state = State.MISSING;
            onChange.run();
            return;
        }
        state = State.STARTING;
        url = null;
        error = null;
        final Process p;
        try {
            p = new ProcessBuilder(exe.getAbsolutePath(), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + port)
                    .redirectErrorStream(true).start();
        } catch (Exception e) {
            state = State.ERROR;
            error = "Could not start cloudflared: " + e.getMessage();
            onChange.run();
            return;
        }
        process = p;
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::stopNow, "Nova-tunnel-cleanup");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // already exiting
            }
        }
        onChange.run();
        Thread reader = new Thread(() -> {
            String lastError = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = URL.matcher(line);
                    if (m.find() && process == p && url == null) {
                        url = m.group();
                        state = State.ON;
                        System.out.println("[Nova] Internet link: " + url);
                        onChange.run();
                    } else if (line.contains(" ERR ") || line.contains("error=")) {
                        lastError = line.replaceFirst("^\\S+\\s+ERR\\s+", "").trim();
                    }
                }
            } catch (Exception ignored) {
                // process ended
            }
            synchronized (Tunnel.this) {
                if (process == p) {
                    process = null;
                    state = State.ERROR;
                    error = lastError != null ? lastError : "cloudflared stopped.";
                    url = null;
                }
            }
            onChange.run();
        }, "Nova-tunnel");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void stop() {
        Process p = process;
        process = null;
        url = null;
        error = null;
        if (state != State.MISSING) {
            state = State.OFF;
        }
        if (p != null) {
            p.destroy();
        }
    }

    private void stopNow() {
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }
}

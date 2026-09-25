package forge.nova;

import forge.gui.GuiBase;
import forge.nova.gui.Dialogs;
import forge.nova.gui.NovaAudio;
import forge.nova.gui.NovaEdt;
import forge.nova.gui.NovaGuiBase;
import forge.nova.net.ClientLink;
import forge.nova.net.NovaServer;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Forge Nova entry point: runs Forge's rules engine headless and serves the GPU client.
 *
 * <pre>
 *   java -cp nova/lib/nova-engine-patches.jar;nova/lib/nova-host.jar;forge-gui-desktop-...jar forge.nova.NovaMain
 *        [--port 7777] [--no-browser] [--browser PATH] [--forge-dir DIR] [--dev]
 * </pre>
 */
public final class NovaMain {
    private NovaMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // Forge relies on legacy merge sort semantics for some comparators (see forge.view.Main).
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");

        int port = 7777;
        boolean openBrowser = true;
        String browserOverride = null;
        String forgeDirArg = null;
        boolean dev = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--no-browser" -> openBrowser = false;
                case "--browser" -> browserOverride = args[++i];
                case "--forge-dir" -> forgeDirArg = args[++i];
                case "--dev" -> dev = true;
                default -> System.err.println("[Nova] ignoring unknown argument " + args[i]);
            }
        }

        File forgeDir = locateForgeDir(forgeDirArg);
        File novaDir = new File(forgeDir, "nova");
        File clientDir = new File(novaDir, "client");
        File resDir = new File(forgeDir, "res");
        System.out.println("[Nova] Forge directory: " + forgeDir.getAbsolutePath());

        // ---- Forge platform layer (must be installed before any Forge class reads ForgeConstants)
        NovaEdt edt = new NovaEdt();
        ClientLink link = new ClientLink();
        Dialogs dialogs = new Dialogs(link);
        SkinAssets skin = new SkinAssets(new File(resDir, "skins" + File.separator + "default"));
        NovaGuiBase guiBase = new NovaGuiBase(forgeDir.getAbsolutePath() + File.separator, edt, dialogs,
                skin.avatarCount(), skin.sleeveCount());
        GuiBase.setInterface(guiBase);
        NovaAudio audio = new NovaAudio(link, resDir);
        guiBase.setAudio(audio);

        NovaHost host = new NovaHost(link, dialogs, edt, guiBase, audio, skin, novaDir, dev);
        link.setListener(host);

        String token = dev ? "dev" : HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
        NovaServer server = new NovaServer(port, token, clientDir, resDir, link, host);
        server.start();
        host.setServer(server);
        String url = "http://127.0.0.1:" + server.getPort() + "/?token=" + token;
        System.out.println("[Nova] Serving on " + url);

        host.initAsync();

        Process browser = null;
        if (openBrowser) {
            browser = launchBrowser(url, browserOverride, novaDir);
        }

        // Lifetime: exit when the app window closes, or after the client has been gone for a while.
        final long started = System.currentTimeMillis();
        while (true) {
            Thread.sleep(1000);
            if (browser != null && !browser.isAlive()) {
                long lived = System.currentTimeMillis() - started;
                if (lived > 8000) {
                    System.out.println("[Nova] Window closed - shutting down.");
                    break;
                }
                browser = null; // handed off to an existing browser instance; rely on idle timeout
            }
            if (openBrowser && browser == null && host.wasEverConnected() && host.millisSinceClientSeen() > 180_000) {
                System.out.println("[Nova] No client for 3 minutes - shutting down.");
                break;
            }
        }
        host.shutdown(); // tells friends in an online room, closes the router port again
        server.stop();
        System.exit(0);
    }

    private static File locateForgeDir(String arg) {
        List<File> candidates = new ArrayList<>();
        if (arg != null) {
            candidates.add(new File(arg));
        }
        candidates.add(new File("."));
        candidates.add(new File(".."));
        try {
            File jar = new File(NovaMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // nova/lib/nova-host.jar -> forge root is two levels up
            File p = jar.getParentFile();
            for (int i = 0; i < 3 && p != null; i++, p = p.getParentFile()) {
                candidates.add(p);
            }
        } catch (Exception ignored) {
            // fall back to working directory candidates
        }
        for (File c : candidates) {
            File res = new File(c, "res" + File.separator + "cardsfolder");
            if (res.isDirectory()) {
                try {
                    return c.getCanonicalFile();
                } catch (Exception e) {
                    return c.getAbsoluteFile();
                }
            }
        }
        throw new IllegalStateException("Cannot find the Forge installation (a folder containing res/cardsfolder). Use --forge-dir.");
    }

    /**
     * Opens the client in a chromeless Chromium "app" window with its own profile, so the window
     * behaves like a desktop application and its lifetime can be tracked.
     */
    private static Process launchBrowser(String url, String override, File novaDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> exes = new ArrayList<>();
        if (override != null) {
            exes.add(override);
        }
        if (os.contains("win")) {
            String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
            String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
            exes.add(pf86 + "\\Microsoft\\Edge\\Application\\msedge.exe");
            exes.add(pf + "\\Microsoft\\Edge\\Application\\msedge.exe");
            exes.add(pf + "\\Google\\Chrome\\Application\\chrome.exe");
            exes.add(pf86 + "\\Google\\Chrome\\Application\\chrome.exe");
            exes.add(local + "\\Google\\Chrome\\Application\\chrome.exe");
            exes.add(pf + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            exes.add(local + "\\Chromium\\Application\\chrome.exe");
        } else if (os.contains("mac")) {
            exes.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            exes.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            exes.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            exes.add("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
        } else {
            for (String n : new String[]{"google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "brave-browser"}) {
                for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin").split(File.pathSeparator)) {
                    exes.add(dir + File.separator + n);
                }
            }
        }
        File profile = new File(System.getProperty("user.home"), ".forge-nova" + File.separator + "browser-profile");
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isEmpty()) {
            profile = new File(local, "Forge" + File.separator + "Nova" + File.separator + "browser-profile");
        }
        profile.mkdirs();
        for (String exe : exes) {
            if (exe == null || !new File(exe).isFile()) {
                continue;
            }
            try {
                List<String> cmd = new ArrayList<>(List.of(exe,
                        "--app=" + url,
                        "--user-data-dir=" + profile.getAbsolutePath(),
                        "--window-size=1600,940",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--disable-features=Translate,msEdgeSidebarV2",
                        "--autoplay-policy=no-user-gesture-required"));
                System.out.println("[Nova] Opening window with " + exe);
                return new ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (Exception e) {
                System.err.println("[Nova] could not start " + exe + ": " + e);
            }
        }
        // No Chromium found: use the default browser (WebGL2 works in Firefox/Safari too).
        System.out.println("[Nova] No Chromium-based browser found; opening the default browser.");
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            System.out.println("[Nova] Please open " + url + " in a browser.");
        }
        return null;
    }
}

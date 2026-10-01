package forge.nova.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.nova.util.JsonOut;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * Discord Rich Presence: shows in Discord what you are doing in Nova (the menu, the deck builder, or a match with its
 * format, your deck and commander, your life total and the turn). Discord needs an application id: the user creates
 * a free application (named what Discord should show, e.g. "Forge Nova") at discord.com/developers and pastes its id.
 * Settings live in Forge's preferences folder (nova-discord.json). One background thread owns the connection,
 * reconnects when Discord starts later, and sends at most one update every few seconds (Discord's rate limit).
 */
public final class DiscordPresence {
    private static final long MIN_UPDATE_MS = 5000;
    private static final long RETRY_MS = 20000;

    private final File settingsFile;
    private boolean enabled;
    private String appId = "";
    private boolean showLife = true;

    /** what is shown; the thread sends it when it changes */
    private String details = "In the main menu";
    private String state = "";
    private String largeImage = "";
    private String largeText = "";
    private long since = System.currentTimeMillis() / 1000;
    private long version = 1;

    private volatile String status = "off";
    private volatile String user = "";
    private volatile String error = "";
    private final Thread thread;
    private final Object lock = new Object();

    /** the running match, for life and turn updates */
    private String matchFormat = "";
    private String matchDeck = "";
    private boolean matchOnline;

    public DiscordPresence(File prefsDir) {
        this.settingsFile = new File(prefsDir, "nova-discord.json");
        load();
        thread = new Thread(this::run, "Nova-Discord");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------ settings

    private void load() {
        if (!settingsFile.isFile()) {
            return;
        }
        try (Reader r = Files.newBufferedReader(settingsFile.toPath(), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
            appId = o.has("appId") ? o.get("appId").getAsString().trim() : "";
            showLife = !o.has("showLife") || o.get("showLife").getAsBoolean();
        } catch (Exception e) {
            System.err.println("[Nova] cannot read " + settingsFile + ": " + e);
        }
    }

    private void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("appId", appId);
        o.addProperty("showLife", showLife);
        try {
            File dir = settingsFile.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.writeString(settingsFile.toPath(), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Nova] cannot save " + settingsFile + ": " + e);
        }
    }

    /** {enabled, appId, showLife, status: off|no-id|connecting|connected|no-discord|error, user, error} */
    public String stateJson() {
        synchronized (lock) {
            return new JsonOut(256).beginObj().put("enabled", enabled).put("appId", appId).put("showLife", showLife)
                    .put("status", enabled && appId.isEmpty() ? "no-id" : status).putOpt("user", user)
                    .putOpt("error", error).endObj().toString();
        }
    }

    /** Applies {enabled?, appId?, showLife?}; returns the new state. */
    public String configure(JsonObject req) {
        synchronized (lock) {
            if (req.has("appId")) {
                String id = req.get("appId").isJsonNull() ? "" : req.get("appId").getAsString().trim();
                if (!id.isEmpty() && !id.matches("\\d{15,25}")) {
                    throw new IllegalArgumentException("A Discord application id is a long number (17 to 20 digits), shown as \"Application ID\" on the application's page.");
                }
                if (!id.equals(appId)) {
                    appId = id;
                    error = "";
                    status = "off";
                }
            }
            if (req.has("enabled")) {
                enabled = req.get("enabled").getAsBoolean();
            }
            if (req.has("showLife")) {
                showLife = req.get("showLife").getAsBoolean();
            }
            version++;
            save();
            lock.notifyAll();
        }
        return stateJson();
    }

    // ------------------------------------------------------------------ what is shown

    private void show(String details, String state, String largeImage, String largeText, boolean restartClock) {
        synchronized (lock) {
            if (details.equals(this.details) && state.equals(this.state) && largeImage.equals(this.largeImage)
                    && largeText.equals(this.largeText) && !restartClock) {
                return;
            }
            this.details = details;
            this.state = state;
            this.largeImage = largeImage;
            this.largeText = largeText;
            if (restartClock) {
                since = System.currentTimeMillis() / 1000;
            }
            version++;
            lock.notifyAll();
        }
    }

    public void menu() {
        show("In the main menu", "", "", "", !details.startsWith("In the main menu"));
    }

    public void builder() {
        show("Building a deck", "", "", "", !details.equals("Building a deck"));
    }

    public void room(int players) {
        show("In an online room", players > 1 ? players + " players" : "Waiting for friends", "", "", !details.equals("In an online room"));
    }

    /**
     * A match started. {@code deck}: the local player's deck name (null when only watching), {@code commanders} its
     * commanders (their art becomes the picture).
     */
    public void matchStarted(String format, String deck, List<String> commanders, int players, boolean online) {
        String fmt = format == null || format.isBlank() ? "Constructed" : format;
        String cmd = commanders == null || commanders.isEmpty() ? "" : String.join(" & ", commanders);
        String image = commanders == null || commanders.isEmpty() ? "" : artUrl(commanders.get(0));
        synchronized (lock) {
            matchFormat = fmt;
            matchDeck = deck == null ? "" : deck;
            matchOnline = online;
        }
        String det = deck == null ? "Watching " + fmt : fmt + " · " + deck;
        show(det, players + "-player game" + (online ? " with friends" : ""), image, cmd.isEmpty() ? (deck == null ? "" : deck) : cmd, true);
    }

    /** Life and turn of the local player during a match (life < 0 when only watching). */
    public void matchState(int life, int turn, int playersLeft, boolean lost) {
        String deckPart;
        synchronized (lock) {
            deckPart = matchDeck.isEmpty() ? "Watching " + matchFormat : matchFormat + " · " + matchDeck;
        }
        StringBuilder st = new StringBuilder();
        if (lost) {
            st.append("Out of the game");
        } else if (life >= 0 && showLife) {
            st.append(life).append(" life");
        }
        if (turn > 0) {
            if (st.length() > 0) st.append(" · ");
            st.append("Turn ").append(turn);
        }
        if (playersLeft > 2 && !lost) {
            if (st.length() > 0) st.append(" · ");
            st.append(playersLeft).append(" players left");
        }
        String img, txt;
        synchronized (lock) {
            img = largeImage;
            txt = largeText;
        }
        show(deckPart, st.toString(), img, txt, false);
    }

    /** Scryfall's art crop of a card, by name (Discord fetches it through its image proxy). */
    private static String artUrl(String cardName) {
        return "https://api.scryfall.com/cards/named?exact=" + URLEncoder.encode(cardName, StandardCharsets.UTF_8)
                + "&format=image&version=art_crop";
    }

    // ------------------------------------------------------------------ the connection

    private void run() {
        DiscordIpc ipc = null;
        String ipcApp = "";
        long sentVersion = -1;
        long lastSend = 0;
        long nextTry = 0;
        String triedApp = "";
        while (true) {
            boolean on;
            String app;
            long ver;
            synchronized (lock) {
                on = enabled && !appId.isEmpty();
                app = appId;
                ver = version;
            }
            if (!app.equals(triedApp)) {
                nextTry = 0; // another application id: try it right away
                triedApp = app;
            }
            try {
                if (!on || (ipc != null && !app.equals(ipcApp))) {
                    if (ipc != null) {
                        clearActivity(ipc);
                        ipc.close();
                        ipc = null;
                        user = "";
                    }
                    if (!on) {
                        status = "off";
                        waitFor(ver, 0);
                        continue;
                    }
                }
                if (ipc == null) {
                    if (System.currentTimeMillis() < nextTry) {
                        waitFor(ver, nextTry - System.currentTimeMillis());
                        continue;
                    }
                    status = "connecting";
                    try {
                        ipc = DiscordIpc.open(app);
                    } catch (IOException e) {
                        error = e.getMessage() == null ? "" : e.getMessage();
                        status = "error";
                        nextTry = System.currentTimeMillis() + RETRY_MS * 3;
                        continue;
                    }
                    if (ipc == null) {
                        status = "no-discord";
                        nextTry = System.currentTimeMillis() + RETRY_MS;
                        continue;
                    }
                    ipcApp = app;
                    user = ipc.user();
                    error = "";
                    status = "connected";
                    sentVersion = -1;
                }
                // drain answers (and ping/close) without blocking
                DiscordIpc.Frame f;
                while ((f = ipc.poll()) != null) {
                    if (f.op() == DiscordIpc.OP_CLOSE) {
                        throw new IOException("Discord closed the connection");
                    }
                }
                if (ver != sentVersion) {
                    long wait = lastSend + MIN_UPDATE_MS - System.currentTimeMillis();
                    if (wait > 0) {
                        DiscordIpc.sleep(Math.min(wait, 500));
                        continue;
                    }
                    sendActivity(ipc);
                    sentVersion = ver;
                    lastSend = System.currentTimeMillis();
                }
                waitFor(ver, 1000); // wakes up for changes; polls the pipe now and then
            } catch (IOException | RuntimeException e) {
                if (ipc != null) {
                    ipc.close();
                    ipc = null;
                }
                user = "";
                status = "no-discord";
                nextTry = System.currentTimeMillis() + RETRY_MS;
            }
        }
    }

    /** Waits until the shown state or the settings change (or the time is up; 0: no limit). */
    private void waitFor(long seenVersion, long ms) {
        synchronized (lock) {
            if (version != seenVersion) {
                return;
            }
            try {
                lock.wait(ms <= 0 ? 0 : ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendActivity(DiscordIpc ipc) throws IOException {
        JsonObject activity = new JsonObject();
        synchronized (lock) {
            activity.addProperty("details", clip(details));
            if (!state.isEmpty()) activity.addProperty("state", clip(state));
            JsonObject ts = new JsonObject();
            ts.addProperty("start", since);
            activity.add("timestamps", ts);
            JsonObject assets = new JsonObject();
            if (!largeImage.isEmpty()) {
                assets.addProperty("large_image", largeImage);
                if (!largeText.isEmpty()) assets.addProperty("large_text", clip(largeText));
            }
            if (assets.size() > 0) activity.add("assets", assets);
            activity.addProperty("instance", false);
        }
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", activity);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        ipc.send(DiscordIpc.OP_FRAME, cmd);
    }

    private static void clearActivity(DiscordIpc ipc) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("pid", ProcessHandle.current().pid());
            JsonObject cmd = new JsonObject();
            cmd.addProperty("cmd", "SET_ACTIVITY");
            cmd.add("args", args);
            cmd.addProperty("nonce", UUID.randomUUID().toString());
            ipc.send(DiscordIpc.OP_FRAME, cmd);
        } catch (IOException ignored) {
            // Discord is gone anyway
        }
    }

    /** Discord's fields take 2 to 128 characters. */
    private static String clip(String s) {
        String t = s.length() > 128 ? s.substring(0, 127) + "…" : s;
        return t.length() < 2 ? t + "  " : t;
    }
}

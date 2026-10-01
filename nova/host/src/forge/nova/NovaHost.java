package forge.nova;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.CardDb;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameView;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.NextGameDecision;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.nova.discord.DiscordPresence;
import forge.nova.gui.Dialogs;
import forge.nova.gui.HouseRules;
import forge.nova.gui.NovaAudio;
import forge.nova.gui.NovaEdt;
import forge.nova.gui.NovaGuiBase;
import forge.nova.gui.NovaGuiGame;
import forge.nova.gui.NovaImageFetcher;
import forge.nova.moxfield.MoxfieldSync;
import forge.nova.net.ClientLink;
import forge.nova.net.NovaServer;
import forge.nova.online.OnlineRoom;
import forge.nova.online.Thumbnails;
import forge.nova.online.Tunnel;
import forge.nova.util.JsonOut;
import forge.player.PlayerControllerHuman;
import forge.util.BuildInfo;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wires the pieces together: HTTP API, lobby, online rooms, match lifecycle, assets.
 */
public final class NovaHost implements NovaServer.Handler, ClientLink.Listener, OnlineRoom.Host {
    private final ClientLink link;
    private final Dialogs dialogs;
    private final NovaEdt edt;
    private final NovaGuiBase guiBase;
    private final NovaAudio audio;
    private final SkinAssets skin;
    private final File novaDir;
    private final LobbyService lobby = new LobbyService();
    private final DeckService decks = new DeckService();
    /** created once Forge has loaded (its settings file lives in Forge's preferences folder) */
    private volatile MoxfieldSync moxfield;
    /** Discord Rich Presence; created once Forge has loaded, like moxfield */
    private volatile DiscordPresence presence;
    private final Achievements achievements = new Achievements();
    private final Tunnel tunnel;
    private final Thumbnails thumbs;
    /** friends' invite code; stays the same while Nova runs, so a reopened room keeps its links */
    private final String inviteCode = OnlineRoom.secret();
    private volatile NovaServer server;

    private volatile boolean ready;
    private volatile String loadError;
    private volatile MatchSession session;
    private volatile OnlineRoom room;
    private final AtomicLong lastClientSeen = new AtomicLong(System.currentTimeMillis());
    private volatile boolean everConnected;
    private final boolean dev;

    /**
     * A running match. {@code hostGui} is the local window's view (a player, or a spectator of an
     * AI-only match); {@code guis} holds every human's GUI; {@code room} is null for offline matches.
     */
    private record MatchSession(HostedMatch hm, NovaGuiGame hostGui, List<NovaGuiGame> guis, OnlineRoom room) {
        boolean isActive() {
            return hostGui != null && hostGui.isActive();
        }
    }

    public NovaHost(ClientLink link, Dialogs dialogs, NovaEdt edt, NovaGuiBase guiBase, NovaAudio audio, SkinAssets skin,
                    File novaDir, boolean dev) {
        this.link = link;
        this.dialogs = dialogs;
        this.edt = edt;
        this.guiBase = guiBase;
        this.audio = audio;
        this.skin = skin;
        this.novaDir = novaDir;
        this.dev = dev;
        this.tunnel = new Tunnel(novaDir);
        this.thumbs = new Thumbnails(new File(novaDir, "cache" + File.separator + "thumbs"));
        audio.setListeners(this::audioListeners);
        guiBase.setDialogRouter(this::routeDialogs);
    }

    public void setServer(NovaServer server) {
        this.server = server;
    }

    /** Loads the card database etc. (a few seconds); the client shows a loading screen meanwhile. */
    public void initAsync() {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                FModel.initialize(null, null);
                moxfield = new MoxfieldSync(novaDir, link::send);
                presence = new DiscordPresence(new File(ForgeConstants.USER_PREFS_DIR));
                ready = true;
                System.out.println("[Nova] Forge model ready in " + (System.currentTimeMillis() - t0) + " ms");
                link.send(helloJson());
                moxfield.autoSync(true); // Moxfield edits made since the last start show up in the lobby
            } catch (Throwable e) {
                e.printStackTrace();
                loadError = String.valueOf(e);
                link.send(helloJson());
            }
        }, "Nova-Init");
        t.setDaemon(true);
        t.start();
    }

    public boolean isReady() {
        return ready;
    }

    private String helloJson() {
        JsonOut o = new JsonOut(256).beginObj().put("t", "hello");
        o.put("ready", ready);
        o.putOpt("error", loadError);
        o.put("version", BuildInfo.getVersionString());
        o.put("proto", OnlineRoom.PROTOCOL);
        MatchSession s = session;
        o.put("inMatch", s != null && s.isActive());
        o.flag("room", room != null);
        if (ready) {
            ForgePreferences p = FModel.getPreferences();
            o.put("sounds", p.getPrefBoolean(ForgePreferences.FPref.UI_ENABLE_SOUNDS));
            o.put("music", p.getPrefBoolean(ForgePreferences.FPref.UI_ENABLE_MUSIC));
            o.put("volSounds", p.getPrefInt(ForgePreferences.FPref.UI_VOL_SOUNDS));
            o.put("volMusic", p.getPrefInt(ForgePreferences.FPref.UI_VOL_MUSIC));
            o.put("devMode", p.getPrefBoolean(ForgePreferences.FPref.DEV_MODE_ENABLED));
            // the free mulligan house rule needs Nova's engine patches (skipped after a Forge update until rebuilt)
            o.put("freeMullOk", HouseRules.freeMulliganAvailable());
        }
        o.endObj();
        return o.toString();
    }

    // ------------------------------------------------------------------ client link

    @Override
    public void onClientConnected() {
        everConnected = true;
        lastClientSeen.set(System.currentTimeMillis());
        link.send(helloJson());
        OnlineRoom r = room;
        if (r != null) {
            r.sendStateToHost();
        }
        MatchSession s = session;
        if (s != null && s.isActive()) {
            s.hostGui().resync();
            if (s.room() != null) {
                link.send(s.room().peersJson());
            }
        }
    }

    public long millisSinceClientSeen() {
        if (link.isConnected()) {
            lastClientSeen.set(System.currentTimeMillis());
            return 0;
        }
        return System.currentTimeMillis() - lastClientSeen.get();
    }

    public boolean wasEverConnected() {
        return everConnected;
    }

    @Override
    public void onClientMessage(JsonObject msg) {
        String t = msg.has("t") ? msg.get("t").getAsString() : "";
        switch (t) {
            case "reply" -> link.onReply(msg.get("id").getAsInt(), msg.get("v"));
            case "musicEnded" -> audio.onMusicEnded(msg.get("id").getAsInt());
            case "ping" -> link.send("{\"t\":\"pong\"}");
            case "leave" -> leaveMatch();
            case "concede" -> concedeGame();
            case "chat" -> {
                OnlineRoom r = room;
                if (r != null && msg.has("msg")) r.hostChat(msg.get("msg").getAsString());
            }
            default -> {
                MatchSession s = session;
                if (s != null && s.hostGui() != null) {
                    s.hostGui().handleAction(msg);
                    if ("next".equals(t) && s.room() != null) {
                        decideForAbsentFriends(s, msg);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP API

    @Override
    public NovaServer.Response api(String method, String path, Map<String, String> query, String body) {
        if (path.startsWith("room")) {
            if (!ready) return NovaServer.Response.error(503, "still loading");
            try {
                return roomApi(method, path, body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject());
            } catch (IllegalArgumentException e) {
                return NovaServer.Response.error(400, e.getMessage());
            }
        }
        switch (path) {
            case "status":
                return NovaServer.Response.json(helloJson());
            case "decks":
                if (!ready) return NovaServer.Response.error(503, "still loading");
                return NovaServer.Response.json(lobby.listDecks());
            case "cards", "cards/printings", "deck", "deck/save", "deck/delete", "deck/check":
                if (!ready) return NovaServer.Response.error(503, "still loading");
                try {
                    return deckApi(method, path, query, body);
                } catch (IllegalArgumentException e) {
                    return NovaServer.Response.error(400, e.getMessage());
                }
            case "moxfield", "moxfield/auto", "moxfield/settings", "moxfield/check", "moxfield/apply", "moxfield/link",
                 "moxfield/unlink", "moxfield/hide", "moxfield/open":
                if (!ready || moxfield == null) return NovaServer.Response.error(503, "still loading");
                return moxfieldApi(method, path, body);
            case "match/start":
                if (!"POST".equals(method)) return NovaServer.Response.error(405, "POST only");
                return startMatch(JsonParser.parseString(body).getAsJsonObject());
            case "achievements":
                if (!ready) return NovaServer.Response.error(503, "still loading");
                return NovaServer.Response.json(achievements.json());
            case "discord", "discord/screen", "discord/portal":
                if (!ready || presence == null) return NovaServer.Response.error(503, "still loading");
                return discordApi(method, path, body);
            case "prefs":
                if ("POST".equals(method)) {
                    return setPrefs(JsonParser.parseString(body).getAsJsonObject());
                }
                return NovaServer.Response.json(helloJson());
            case "dev/stats": {
                if (!dev) return NovaServer.Response.error(404, "dev mode only");
                MatchSession s = session;
                return NovaServer.Response.json(s == null || s.hostGui() == null ? "{}" : s.hostGui().getSync().statsJson());
            }
            case "dev/stress":
                if (!dev) return NovaServer.Response.error(404, "dev mode only");
                return devStress(body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject());
            case "quit":
                new Thread(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        // exit anyway
                    }
                    System.exit(0);
                }).start();
                return NovaServer.Response.json("{\"ok\":true}");
            default:
                return NovaServer.Response.error(404, "unknown endpoint " + path);
        }
    }

    /** Deck builder: card catalog, printings, deck files and Forge's deck checks. */
    private NovaServer.Response deckApi(String method, String path, Map<String, String> query, String body) {
        switch (path) {
            case "cards":
                return new NovaServer.Response(200, "application/json; charset=utf-8", decks.catalog(), false);
            case "cards/printings":
                return NovaServer.Response.json(decks.printings(query.getOrDefault("name", "")));
            case "deck":
                return NovaServer.Response.json(decks.loadDeck(query.get("src"), query.get("name")));
            default:
                break;
        }
        if (!"POST".equals(method)) {
            return NovaServer.Response.error(405, "POST only");
        }
        JsonObject req = JsonParser.parseString(body).getAsJsonObject();
        switch (path) {
            case "deck/save": {
                String saved = decks.saveDeck(req);
                return saved != null ? NovaServer.Response.json(saved)
                        : NovaServer.Response.error(409, "A deck with this name already exists.");
            }
            case "deck/delete":
                decks.deleteDeck(req.get("src").getAsString(), req.get("name").getAsString());
                return NovaServer.Response.json("{\"ok\":true}");
            case "deck/check":
                return NovaServer.Response.json(decks.checkDeck(req));
            default:
                return NovaServer.Response.error(404, "unknown endpoint " + path);
        }
    }

    /** Moxfield sync: the user's Moxfield decks next to Forge's copies, and syncing them. */
    private NovaServer.Response moxfieldApi(String method, String path, String body) {
        final MoxfieldSync mox = moxfield;
        if (!"moxfield".equals(path) && !"POST".equals(method)) {
            return NovaServer.Response.error(405, "POST only");
        }
        final JsonObject req = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        final java.util.function.Function<String, String> str = k -> req.has(k) && !req.get(k).isJsonNull() ? req.get(k).getAsString() : null;
        try {
            String json = switch (path) {
                case "moxfield" -> mox.stateJson();
                case "moxfield/auto" -> {
                    mox.autoSync(false); // the lobby is showing: catch up with Moxfield when it's been a while
                    yield mox.stateJson();
                }
                case "moxfield/settings" -> mox.settings(req);
                case "moxfield/check" -> mox.check(str.apply("user"), req.has("force") && req.get("force").getAsBoolean());
                case "moxfield/apply" -> {
                    List<String> ids = new ArrayList<>();
                    if (req.has("ids")) for (JsonElement e : req.getAsJsonArray("ids")) ids.add(e.getAsString());
                    yield mox.apply(ids);
                }
                case "moxfield/link" -> mox.addLinks(str.apply("text"));
                case "moxfield/unlink" -> mox.removeExtra(str.apply("id"));
                case "moxfield/hide" -> mox.hide(str.apply("id"));
                case "moxfield/open" -> {
                    // in the user's own browser, where they are logged in to Moxfield (the app window has its own profile)
                    String url = str.apply("url");
                    if (url == null || !url.matches("https://(www\\.)?moxfield\\.com/[A-Za-z0-9_\\-/]*")) {
                        throw new IllegalArgumentException("Not a Moxfield link.");
                    }
                    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                    new ProcessBuilder(os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                            : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url)).start();
                    yield "{\"ok\":true}";
                }
                default -> throw new IllegalArgumentException("unknown endpoint " + path);
            };
            return NovaServer.Response.json(json);
        } catch (IllegalArgumentException e) {
            return NovaServer.Response.error(400, e.getMessage());
        } catch (java.io.IOException e) {
            return NovaServer.Response.error(502, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NovaServer.Response.error(503, "interrupted");
        }
    }

    /**
     * Discord Rich Presence: its settings (GET / POST discord), what the client shows outside a match (discord/screen:
     * {screen: 'builder' | 'lobby'}), and Discord's page for creating the application id (discord/portal).
     */
    private NovaServer.Response discordApi(String method, String path, String body) {
        final DiscordPresence p = presence;
        final JsonObject req = body == null || body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        try {
            switch (path) {
                case "discord" -> {
                    return NovaServer.Response.json("POST".equals(method) ? p.configure(req) : p.stateJson());
                }
                case "discord/screen" -> {
                    MatchSession s = session;
                    if (s == null || !s.isActive()) {
                        if (req.has("screen") && "builder".equals(req.get("screen").getAsString())) p.builder();
                        else presenceIdle();
                    }
                    return NovaServer.Response.json("{\"ok\":true}");
                }
                default -> {
                    guiBase.browseToUrl("https://discord.com/developers/applications");
                    return NovaServer.Response.json("{\"ok\":true}");
                }
            }
        } catch (IllegalArgumentException e) {
            return NovaServer.Response.error(400, e.getMessage());
        }
    }

    /** Discord shows the menu, or the online room while one is open. */
    private void presenceIdle() {
        DiscordPresence p = presence;
        if (p == null) {
            return;
        }
        OnlineRoom r = room;
        if (r != null) p.room(r.seats().size());
        else p.menu();
    }

    private static String formatLabel(String format) {
        return switch (format == null ? "" : format) {
            case "commander" -> "Commander";
            case "brawl" -> "Brawl";
            case "oathbreaker" -> "Oathbreaker";
            case "tinyLeaders" -> "Tiny Leaders";
            default -> "Constructed";
        };
    }

    /** A match began: Discord shows the format, the host player's deck and commander; life and turn follow. */
    private void presenceForMatch(LobbyService.MatchSetup setup, NovaGuiGame hostGui, String format, boolean online) {
        DiscordPresence p = presence;
        if (p == null || hostGui == null) {
            return;
        }
        String deck = null;
        List<String> cmdrs = new ArrayList<>();
        for (Map.Entry<RegisteredPlayer, IGuiGame> e : setup.guis().entrySet()) {
            if (e.getValue() != hostGui) continue;
            Deck d = e.getKey().getDeck();
            if (d == null) continue;
            deck = d.getName();
            if (d.getCommanders() != null) {
                for (PaperCard pc : d.getCommanders()) cmdrs.add(pc.getName());
            }
        }
        p.matchStarted(formatLabel(format), deck, cmdrs, setup.players().size(), online);
        hostGui.setOnGameStateChanged(() -> presenceTick(hostGui));
    }

    /** Life, turn and players left, for Discord (Forge's threads; DiscordPresence sends it when it changed). */
    private void presenceTick(NovaGuiGame g) {
        DiscordPresence p = presence;
        GameView gv = g.getGameView();
        if (p == null || gv == null) {
            return;
        }
        PlayerView me = null;
        for (PlayerView pv : g.localPlayers()) {
            me = pv;
            break;
        }
        int alive = 0;
        for (PlayerView pv : gv.getPlayers()) {
            if (!pv.getHasLost()) alive++;
        }
        p.matchState(me == null ? -1 : me.getLife(), gv.getTurn(), alive, me != null && me.getHasLost());
    }

    private static boolean houseRule(JsonObject req, String rule) {
        if (!req.has("houseRules") || !req.get("houseRules").isJsonObject()) {
            return false;
        }
        JsonObject hr = req.getAsJsonObject("houseRules");
        return hr.has(rule) && hr.get(rule).getAsBoolean();
    }

    /** Floods every battlefield with permanents (Forge dev-mode code path, no triggers). */
    private NovaServer.Response devStress(JsonObject req) {
        MatchSession s = session;
        HostedMatch hm = s == null ? null : s.hm();
        final Game game = hm == null ? null : hm.getGame();
        if (game == null) {
            return NovaServer.Response.error(409, "no game running");
        }
        final int per = req.has("perPlayer") ? req.get("perPlayer").getAsInt() : 100;
        final List<String> names = new ArrayList<>();
        if (req.has("names")) {
            for (var e : req.getAsJsonArray("names")) names.add(e.getAsString());
        } else {
            names.addAll(List.of("Llanowar Elves", "Grizzly Bears", "Forest", "Savannah Lions", "Plains", "Sol Ring",
                    "Memnite", "Ornithopter", "Island", "Birds of Paradise", "Serra Angel", "Elvish Mystic",
                    "Suntail Hawk", "Mountain", "Swamp", "Goblin Guide", "Wall of Omens", "Darksteel Relic"));
        }
        final CardDb db = FModel.getMagicDb().getCommonCards();
        final int[] added = {0};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final Runnable inject = () -> {
            try {
                for (Player p : game.getPlayers()) {
                    for (int i = 0; i < per; i++) {
                        PaperCard pc = db.getUniqueByName(names.get(i % names.size()));
                        if (pc == null) continue;
                        try {
                            Card c = Card.fromPaperCard(pc, p);
                            c.setGameTimestamp(game.getNextTimestamp());
                            game.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
                            if (c.isCreature()) c.setSickness(false);
                            added[0]++;
                        } catch (RuntimeException ex) {
                            // some replacement effects expect a causing spell; skip that card
                        }
                    }
                }
            } finally {
                done.countDown();
            }
        };
        // Mutating zones is only safe on the game thread or while it is parked on human input
        // (this mirrors Forge's own dev-mode cheats).
        boolean humanWaiting = false;
        for (PlayerControllerHuman pch : hm.getHumanControllers()) {
            // spectator controllers always have an input pending while the AIs keep playing
            if (!(pch instanceof forge.gui.control.WatchLocalGame) && pch.getInputQueue().getInput() != null) {
                humanWaiting = true;
            }
        }
        if (humanWaiting) {
            game.getAction().invoke(inject);
        } else {
            game.subscribeToEvents(new OneShotPriorityHook(inject));
        }
        try {
            done.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return NovaServer.Response.json("{\"added\":" + added[0] + "}");
    }

    /** Runs a task once, on the game thread, the next time a player receives priority. */
    public static final class OneShotPriorityHook {
        private final Runnable task;
        private final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();

        OneShotPriorityHook(Runnable task) {
            this.task = task;
        }

        @com.google.common.eventbus.Subscribe
        public void onPriority(forge.game.event.GameEventPlayerPriority e) {
            if (fired.compareAndSet(false, true)) {
                task.run();
            }
        }
    }

    private NovaServer.Response setPrefs(JsonObject p) {
        if (!ready) return NovaServer.Response.error(503, "still loading");
        ForgePreferences prefs = FModel.getPreferences();
        if (p.has("sounds")) prefs.setPref(ForgePreferences.FPref.UI_ENABLE_SOUNDS, p.get("sounds").getAsBoolean());
        if (p.has("music")) prefs.setPref(ForgePreferences.FPref.UI_ENABLE_MUSIC, p.get("music").getAsBoolean());
        if (p.has("volSounds")) prefs.setPref(ForgePreferences.FPref.UI_VOL_SOUNDS, String.valueOf(p.get("volSounds").getAsInt()));
        if (p.has("volMusic")) prefs.setPref(ForgePreferences.FPref.UI_VOL_MUSIC, String.valueOf(p.get("volMusic").getAsInt()));
        if (p.has("devMode")) prefs.setPref(ForgePreferences.FPref.DEV_MODE_ENABLED, p.get("devMode").getAsBoolean());
        prefs.save();
        if (p.has("devMode")) {
            // the running match shows or hides its Dev tab
            MatchSession s = session;
            if (s != null && s.hostGui() != null && s.isActive()) {
                s.hostGui().sendDevState();
            }
        }
        if (p.has("music") || p.has("volMusic")) {
            // starts the background music if it was switched on while nothing was playing
            forge.sound.SoundSystem.instance.refreshVolume();
        }
        return NovaServer.Response.json(helloJson());
    }

    // ------------------------------------------------------------------ offline match

    private synchronized NovaServer.Response startMatch(JsonObject req) {
        if (!ready) {
            return NovaServer.Response.error(503, "Forge is still loading");
        }
        if (room != null) {
            return NovaServer.Response.error(409, "Close the online room first.");
        }
        MatchSession running = session;
        if (running != null && running.isActive() && !running.hm().isMatchOver()) {
            return NovaServer.Response.error(409, "A match is already in progress");
        }
        final NovaGuiGame gui = new NovaGuiGame(link, dialogs, edt, null);
        final LobbyService.MatchSetup setup;
        try {
            setup = lobby.buildMatch(req, gui);
        } catch (IllegalArgumentException e) {
            return NovaServer.Response.error(400, e.getMessage());
        }
        guiBase.setGuiGameFactory(() -> gui); // AI-only matches get this GUI as spectator view
        HouseRules.apply(houseRule(req, "freeMulligan"));
        presenceForMatch(setup, gui, req.has("format") ? req.get("format").getAsString() : "constructed", false);
        final HostedMatch hm = guiBase.hostMatch();
        final MatchSession s = new MatchSession(hm, gui, List.of(gui), null);
        hm.setOnMatchOver(() -> onMatchOver(s));
        session = s;
        final Throwable[] failure = new Throwable[1];
        edt.andWait(() -> {
            try {
                hm.startMatch(setup.rules(), setup.variants(), setup.players(), setup.guis(), null);
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            failure[0].printStackTrace();
            gui.dispose();
            if (session == s) session = null;
            return NovaServer.Response.error(500, "Could not start the match: " + failure[0]);
        }
        return NovaServer.Response.json("{\"ok\":true}");
    }

    /** Runs on the EDT; may fire late (or twice) for a match that was left, after a new one started. */
    private void onMatchOver(MatchSession s) {
        for (NovaGuiGame g : s.guis()) {
            g.dispose();
            if (g.getLink() != link) {
                g.getLink().cancelAllDialogs();
            }
        }
        if (session != s) {
            return;
        }
        session = null;
        link.cancelAllDialogs();
        presenceIdle();
        if (s.room() != null && !s.room().isClosed()) {
            s.room().onMatchEnded();
        } else {
            link.send("{\"t\":\"lobby\"}");
        }
    }

    /**
     * "Back to main menu" (offline) or "End match" (online, for everyone): ends the match now
     * instead of letting the others play the game out.
     */
    private void leaveMatch() {
        final MatchSession s = session;
        if (s == null) {
            OnlineRoom r = room;
            if (r != null) {
                r.sendStateToHost();
            } else {
                link.send("{\"t\":\"lobby\"}");
            }
            return;
        }
        for (NovaGuiGame g : s.guis()) {
            g.setLeaving();
            // the UI thread may itself be waiting for someone's answer: free it first
            if (s.room() != null) g.getLink().cancelAllDialogs();
        }
        edt.later(() -> {
            HostedMatch hm = s.hm();
            try {
                endGameNow(hm, true, s.room() == null ? null : s.hostGui());
                List<PlayerControllerHuman> humans = new ArrayList<>(hm.getHumanControllers());
                if (humans.isEmpty()) {
                    onMatchOver(s);
                } else {
                    // the same decision as "Back to lobby" on the game-over screen
                    humans.get(0).nextGameDecision(NextGameDecision.QUIT);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                onMatchOver(s);
            }
        });
    }

    /**
     * Concede in the options menu. Offline, the game ends at once and the usual game-over screen
     * follows; online only the host's player concedes and the others play on.
     */
    private void concedeGame() {
        final MatchSession s = session;
        if (s == null) {
            return;
        }
        if (s.room() != null) {
            concedeGui(s, s.hostGui());
            return;
        }
        edt.later(() -> {
            try {
                if (s.hm().getHumanControllers().stream().noneMatch(pch -> pch.getPlayer() != null)) {
                    return; // spectators have nothing to concede
                }
                endGameNow(s.hm(), false, null);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /**
     * Concedes for the local players (or, online, for {@code onlyFor}'s player) and ends the game
     * right away; in multiplayer Forge would otherwise keep the AIs playing (in the background, if
     * the match was left).
     *
     * @param abandon the match ends with this game (no follow-up game in best-of-N matches)
     */
    private void endGameNow(HostedMatch hm, boolean abandon, NovaGuiGame onlyFor) {
        final Game game = hm.getGame();
        if (game == null || game.isGameOver()) {
            return;
        }
        if (abandon) {
            // An AI-only match would otherwise start its next game: one win now decides the match.
            hm.getMatch().getRules().setGamesPerMatch(1);
        }
        for (PlayerControllerHuman pch : new ArrayList<>(hm.getHumanControllers())) {
            Player p = pch.getPlayer(); // null for the spectator of an AI-only match
            if (p != null && !p.hasLost() && (onlyFor == null || onlyFor.getOriginalGameControllers().contains(pch))) {
                pch.concede();
            }
        }
        if (!game.isGameOver()) {
            final List<PlayerControllerHuman> humans = new ArrayList<>(hm.getHumanControllers());
            game.getAction().invoke(() -> {
                game.setGameOver(forge.game.GameEndReason.AllHumansLost);
                // a game thread parked on someone's decision (online: often a friend's) must see the end
                for (PlayerControllerHuman pch : humans) {
                    pch.getInputQueue().onGameOver(true);
                }
            });
        }
        // a game thread waiting for a dialog answer has to notice the end, too
        MatchSession s = session;
        if (s != null) {
            for (NovaGuiGame g : s.guis()) g.getLink().cancelAllDialogs();
        }
        link.cancelAllDialogs();
    }

    /** One human concedes; the others play on (online multiplayer). */
    private void concedeGui(MatchSession s, NovaGuiGame gui) {
        if (gui == null) {
            return;
        }
        // a UI thread waiting for this player's answer would never get to the concession
        gui.getLink().cancelAllDialogs();
        edt.later(() -> {
            try {
                Game game = s.hm().getGame();
                if (game == null || game.isGameOver()) {
                    return;
                }
                for (IGameController c : new ArrayList<>(gui.getOriginalGameControllers())) {
                    if (c instanceof PlayerControllerHuman pch && pch.getPlayer() != null && !pch.getPlayer().hasLost()) {
                        pch.concede();
                        if (!game.isGameOver()) {
                            // a game waiting for this player's decision moves on
                            pch.getInputQueue().clearInputs();
                        }
                    }
                }
                gui.getLink().cancelAllDialogs();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /** The host's next-game decision also counts for friends who can't make it (disconnected, left, AI). */
    private void decideForAbsentFriends(MatchSession s, JsonObject msg) {
        final NextGameDecision d;
        try {
            d = NextGameDecision.valueOf(msg.get("decision").getAsString());
        } catch (RuntimeException e) {
            return;
        }
        edt.later(() -> {
            for (OnlineRoom.Seat seat : s.room().humanSeats()) {
                if (seat.kind() != OnlineRoom.Kind.FRIEND) continue;
                NovaGuiGame g = seat.gui();
                if (g == null || (seat.isConnected() && !seat.gone() && !seat.aiPlaying())) continue;
                for (IGameController c : new ArrayList<>(g.getOriginalGameControllers())) {
                    try {
                        c.nextGameDecision(d);
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ online rooms

    @Override
    public ClientLink localLink() {
        return link;
    }

    @Override
    public String hostName() {
        String pn = ready ? FModel.getPreferences().getPref(ForgePreferences.FPref.PLAYER_NAME) : null;
        return pn == null || pn.isBlank() ? "Host" : pn;
    }

    @Override
    public String deckList(boolean withUserDecks) {
        return lobby.guestDeckList(withUserDecks);
    }

    @Override
    public String deckInfo(JsonObject spec) {
        try {
            return LobbyService.deckInfo(spec);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String deckInfo(Deck deck) {
        return LobbyService.deckInfo(deck);
    }

    private NovaServer.Response roomApi(String method, String path, JsonObject req) {
        OnlineRoom r = room;
        if (path.equals("room")) {
            return NovaServer.Response.json("{\"room\":" + (r != null) + ",\"port\":" + (r != null ? r.port() : defaultOnlinePort()) + "}");
        }
        if (!"POST".equals(method)) {
            return NovaServer.Response.error(405, "POST only");
        }
        if (path.equals("room/open")) {
            return openRoom(req);
        }
        if (r == null) {
            return NovaServer.Response.error(409, "No room is open.");
        }
        switch (path) {
            case "room/settings" -> r.configure(req);
            case "room/seat/add" -> r.addSeat(req.has("kind") ? req.get("kind").getAsString() : "friend");
            case "room/seat/remove" -> r.removeSeat(req.get("id").getAsInt());
            case "room/seat" -> r.updateSeat(req);
            case "room/kick" -> r.kick(req.get("id").getAsInt());
            case "room/tunnel" -> r.setTunnel(req.get("on").getAsBoolean());
            case "room/upnp" -> r.setUpnp(req.get("on").getAsBoolean());
            case "room/name" -> {
                setHostName(req.get("name").getAsString());
                r.broadcastState();
            }
            case "room/start" -> {
                String err = r.start();
                if (err != null) return NovaServer.Response.error(409, err);
            }
            case "room/close" -> {
                MatchSession s = session;
                if (s != null && s.room() == r) {
                    leaveMatch();
                }
                r.close("The host closed the room.");
            }
            case "room/takeover", "room/concede" -> {
                OnlineRoom.Seat seat = r.seatById(req.get("id").getAsInt());
                MatchSession s = session;
                if (seat == null || seat.gui() == null || s == null || s.room() != r) {
                    return NovaServer.Response.error(409, "That player isn't in the game.");
                }
                if (path.equals("room/takeover")) aiTakeover(seat);
                else concede(seat);
            }
            default -> {
                return NovaServer.Response.error(404, "unknown endpoint " + path);
            }
        }
        return NovaServer.Response.json("{\"ok\":true}");
    }

    private void setHostName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > 20 || !n.matches("[\\p{L}\\p{N} ._'-]+")) {
            throw new IllegalArgumentException("Use 1 to 20 letters, digits or spaces for your name.");
        }
        if (n.toLowerCase(Locale.ROOT).contains("human")) {
            throw new IllegalArgumentException("Forge reserves the word \"human\" in player names.");
        }
        ForgePreferences prefs = FModel.getPreferences();
        prefs.setPref(ForgePreferences.FPref.PLAYER_NAME, n);
        prefs.save();
    }

    /**
     * Opens a room: {format, life, games, shareDecks, port, upnp, name, hostDeck:{src,name},
     * seats:[{kind:'friend'} | {kind:'ai', deck:{src,name}, profile}]}.
     */
    private synchronized NovaServer.Response openRoom(JsonObject req) {
        if (room != null) {
            return NovaServer.Response.json("{\"ok\":true}"); // already open: the client just shows it
        }
        MatchSession s = session;
        if (s != null && s.isActive()) {
            return NovaServer.Response.error(409, "Finish the current match first.");
        }
        if (req.has("name") && !req.get("name").getAsString().isBlank()) {
            setHostName(req.get("name").getAsString());
        }
        int port = req.has("port") ? req.get("port").getAsInt() : defaultOnlinePort();
        if (port < 1024 || port > 65535) {
            return NovaServer.Response.error(400, "Choose a port between 1024 and 65535.");
        }
        OnlineRoom r = new OnlineRoom(this, server, inviteCode, port, tunnel,
                guiBase.getAvatarCount(), guiBase.getSleevesCount());
        int bound;
        try {
            bound = server.startOnline(port, r);
        } catch (Exception e) {
            return NovaServer.Response.error(409, "Port " + port + " can't be opened (" + e.getMessage()
                    + "). It may be in use by another program; try another port.");
        }
        if (bound != port) {
            server.stopOnline();
            return NovaServer.Response.error(409, "Port " + port + " is in use.");
        }
        try {
            JsonObject cfg = new JsonObject();
            for (String k : new String[]{"format", "life", "games", "shareDecks", "freeMulligan"}) {
                if (req.has(k)) cfg.add(k, req.get(k));
            }
            r.configure(cfg);
            if (req.has("hostDeck")) {
                JsonObject hs = new JsonObject();
                hs.addProperty("id", r.hostSeat().id);
                hs.add("deck", req.get("hostDeck"));
                try {
                    r.updateSeat(hs);
                } catch (IllegalArgumentException ignored) {
                    // the host picks a deck in the room
                }
            }
            if (req.has("seats")) {
                for (JsonElement el : req.getAsJsonArray("seats")) {
                    JsonObject seat = el.getAsJsonObject();
                    String kind = seat.has("kind") ? seat.get("kind").getAsString() : "friend";
                    int id = r.addSeat(kind);
                    if ("ai".equals(kind) && (seat.has("deck") || seat.has("profile"))) {
                        JsonObject upd = seat.deepCopy();
                        upd.remove("kind");
                        upd.addProperty("id", id);
                        try {
                            r.updateSeat(upd);
                        } catch (IllegalArgumentException ignored) {
                            // keep the default deck
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            server.stopOnline();
            throw e;
        }
        room = r;
        try {
            ForgeNetPreferences np = FModel.getNetPreferences();
            np.setPref(ForgeNetPreferences.FNetPref.NET_PORT, String.valueOf(port));
            np.save();
        } catch (RuntimeException ignored) {
            // the port preference is a convenience
        }
        r.startNetworking(!req.has("upnp") || req.get("upnp").getAsBoolean());
        r.systemChat("Room opened. Send your friends an invite link.");
        r.broadcastState();
        presenceIdle();
        return NovaServer.Response.json("{\"ok\":true}");
    }

    /** Forge's own network port preference (classic Forge's lobby uses it too), 36743 by default. */
    public int defaultOnlinePort() {
        try {
            int p = FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
            return p >= 1024 && p <= 65535 ? p : 36743;
        } catch (RuntimeException e) {
            return 36743;
        }
    }

    @Override
    public void onRoomClosed(OnlineRoom r) {
        synchronized (this) {
            if (room == r) {
                room = null;
            }
        }
        link.send("{\"t\":\"roomClosed\",\"host\":true}");
        presenceIdle();
    }

    @Override
    public synchronized String startOnlineMatch(OnlineRoom r, List<OnlineRoom.SeatPlan> plans) {
        if (room != r) {
            return "This room is closed.";
        }
        MatchSession running = session;
        if (running != null && running.isActive()) {
            return "A match is already in progress.";
        }
        final List<NovaGuiGame> guis = new ArrayList<>();
        final NovaGuiGame[] hostGui = new NovaGuiGame[1];
        final LobbyService.MatchSetup setup;
        try {
            setup = lobby.buildOnlineMatch(r.format(), r.games(), r.life(), plans, sp -> {
                boolean isHost = sp.kind() == OnlineRoom.Kind.HOST;
                NovaGuiGame g = isHost
                        ? new NovaGuiGame(link, dialogs, edt, null, true)
                        : new NovaGuiGame(sp.link(), new Dialogs(sp.link()), edt, null, false);
                g.setNetGame();
                if (isHost) {
                    hostGui[0] = g;
                    guis.add(0, g);
                } else {
                    guis.add(g);
                }
                r.bindGui(sp.seat(), g);
                return g;
            });
        } catch (IllegalArgumentException e) {
            unbind(r);
            return e.getMessage();
        }
        HouseRules.apply(r.freeMulligan());
        presenceForMatch(setup, hostGui[0], r.format(), true);
        final HostedMatch hm = guiBase.hostMatch();
        final MatchSession s = new MatchSession(hm, hostGui[0], List.copyOf(guis), r);
        guiBase.setGuiGameFactory(() -> hostGui[0]);
        hm.setOnMatchOver(() -> onMatchOver(s));
        session = s;
        final Throwable[] failure = new Throwable[1];
        edt.andWait(() -> {
            try {
                hm.startMatch(setup.rules(), setup.variants(), setup.players(), setup.guis(), null);
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            failure[0].printStackTrace();
            for (NovaGuiGame g : guis) g.dispose();
            if (session == s) session = null;
            unbind(r);
            return "Could not start the match: " + failure[0];
        }
        link.send(r.peersJson());
        return null;
    }

    private static void unbind(OnlineRoom r) {
        for (OnlineRoom.Seat seat : r.seats()) r.bindGui(seat, null);
    }

    @Override
    public void concede(OnlineRoom.Seat seat) {
        MatchSession s = session;
        NovaGuiGame g = seat.gui();
        if (s != null && g != null && s.guis().contains(g)) {
            concedeGui(s, g);
        }
    }

    /** The AI finishes the game for a friend (like classic Forge does when a network player drops out). */
    @Override
    public void aiTakeover(OnlineRoom.Seat seat) {
        final MatchSession s = session;
        final NovaGuiGame g = seat.gui();
        if (s == null || g == null || !s.guis().contains(g) || seat.aiPlaying()) {
            return;
        }
        // the UI thread may be waiting for this friend's answer (e.g. a mana choice): free it first
        g.getLink().cancelAllDialogs();
        edt.later(() -> {
            try {
                Game game = s.hm().getGame();
                if (game == null || game.isGameOver()) {
                    return;
                }
                for (IGameController c : new ArrayList<>(g.getOriginalGameControllers())) {
                    if (c instanceof PlayerControllerHuman pch && pch.getPlayer() != null && !pch.getPlayer().hasLost()) {
                        Player p = pch.getPlayer();
                        LobbyPlayerAi ai = new LobbyPlayerAi(p.getName(), null);
                        ai.setAiProfile("Default");
                        p.dangerouslySetController(new PlayerControllerAi(game, p, ai));
                        pch.getInputQueue().clearInputs();
                    }
                }
                g.getLink().cancelAllDialogs();
                s.room().markAiPlaying(seat);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    @Override
    public void musicEnded(int id) {
        audio.onMusicEnded(id);
    }

    /** Everyone who hears the match's sounds: the local window, plus the friends of an online match. */
    private List<ClientLink> audioListeners() {
        MatchSession s = session;
        if (s != null && s.room() != null) {
            return s.room().matchLinks();
        }
        return List.of(link);
    }

    /**
     * Engine questions asked through Forge's static dialog helpers don't say whose they are; online,
     * they go to the player who has priority (the one paying a cost or deciding), else to the host.
     */
    private Dialogs routeDialogs() {
        MatchSession s = session;
        if (s == null || s.room() == null) {
            return null;
        }
        try {
            Game game = s.hm().getGame();
            Player pp = game == null ? null : game.getPhaseHandler().getPriorityPlayer();
            if (pp == null) {
                return null;
            }
            int id = pp.getId();
            for (NovaGuiGame g : s.guis()) {
                for (PlayerView pv : g.localPlayers()) {
                    if (pv.getId() == id) {
                        return g.getDialogs();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall back to the host
        }
        return null;
    }

    // ------------------------------------------------------------------ assets

    @Override
    public NovaServer.Response asset(String path, Map<String, String> query) {
        try {
            if (path.equals("/img")) {
                String key = query.get("key");
                if (key == null || key.isEmpty() || !ready) {
                    return notFound();
                }
                boolean fetch = !"0".equals(query.get("fetch"));
                File f = fetch ? guiBase.getNovaImageFetcher().getOrFetch(key, 25) : NovaImageFetcher.lookup(key);
                if (f == null || !isImageInCache(f)) {
                    return notFound();
                }
                return imageResponse(f);
            }
            if (path.equals("/achv")) {
                File f = ready ? achievements.image(query.get("key")) : null;
                return f == null ? notFound() : new NovaServer.Response(200, "image/png", Files.readAllBytes(f.toPath()), true);
            }
            if (path.startsWith("/avatar/")) {
                byte[] png = skin.avatarPng(parseIndex(path.substring(8)));
                return png == null ? notFound() : new NovaServer.Response(200, "image/png", png, true);
            }
            if (path.startsWith("/sleeve/")) {
                byte[] png = skin.sleevePng(parseIndex(path.substring(8)));
                return png == null ? notFound() : new NovaServer.Response(200, "image/png", png, true);
            }
        } catch (Exception e) {
            System.err.println("[Nova] asset error " + path + ": " + e);
        }
        return notFound();
    }

    /** Card images for friends: card/token/icon keys only, optionally board-sized ({@code w=256}). */
    @Override
    public NovaServer.Response guestImage(Map<String, String> query) {
        try {
            String key = query.get("key");
            if (key == null || key.length() > 300 || !ready || key.contains("..")
                    || !(key.startsWith("c:") || key.startsWith("t:") || key.startsWith("i:"))) {
                return notFound();
            }
            File f = guiBase.getNovaImageFetcher().getOrFetch(key, 25);
            if (f == null || !isImageInCache(f)) {
                return notFound();
            }
            if (String.valueOf(Thumbnails.W).equals(query.get("w"))) {
                byte[] small = thumbs.get(f);
                if (small != null) {
                    return new NovaServer.Response(200, "image/jpeg", small, true);
                }
            }
            return imageResponse(f);
        } catch (Exception e) {
            System.err.println("[Nova] guest image error: " + e);
            return notFound();
        }
    }

    private static NovaServer.Response imageResponse(File f) throws java.io.IOException {
        String n = f.getName().toLowerCase(Locale.ROOT);
        String type = n.endsWith(".png") ? "image/png" : "image/jpeg";
        return new NovaServer.Response(200, type, Files.readAllBytes(f.toPath()), true);
    }

    /** Image keys map to file names; only pictures inside Forge's image cache may ever be served. */
    private static boolean isImageInCache(File f) {
        try {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) {
                return false;
            }
            String path = f.getCanonicalPath();
            String cache = new File(ForgeConstants.CACHE_DIR).getCanonicalPath() + File.separator;
            return path.startsWith(cache);
        } catch (Exception e) {
            return false;
        }
    }

    private static int parseIndex(String s) {
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static NovaServer.Response notFound() {
        return new NovaServer.Response(404, "text/plain", new byte[0], false);
    }

    /** Closes an open room (Nova is quitting). */
    public void shutdown() {
        OnlineRoom r = room;
        if (r != null) {
            r.close("The host quit Forge Nova.");
        }
        tunnel.stop();
    }
}

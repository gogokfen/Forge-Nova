package forge.nova.online;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.nova.gui.NovaGuiGame;
import forge.nova.net.ClientLink;
import forge.nova.net.NovaServer;
import forge.nova.util.JsonOut;
import io.netty.channel.Channel;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An online room: the host's seat, friends' seats and AI seats, chat, deck choices and the invite.
 *
 * Friends connect through {@link NovaServer}'s online listener with the room's invite code. Each
 * friend's seat has its own {@link ClientLink}, so during the match the engine's prompts, dialogs
 * and state reach exactly that friend (with that player's view of hidden information), and a
 * friend who reloads the page or loses the connection gets the same seat back.
 */
public final class OnlineRoom implements NovaServer.GuestHandler {
    /** bumped whenever client and host messages change incompatibly */
    public static final int PROTOCOL = 1;
    public static final int MAX_PLAYERS = 8;
    private static final int MAX_NAME = 20;
    private static final int MAX_CHAT = 300;
    private static final int CHAT_HISTORY = 80;
    /** an empty-handed friend who dropped out of the lobby loses the seat after this long */
    private static final long SEAT_HOLD_MS = 120_000;

    /** What the room needs from the rest of the host. */
    public interface Host {
        ClientLink localLink();

        /** the host player's name as the others see it */
        String hostName();

        /** {user:[...], builtin:[...]} in the lobby's format; user decks only when shared */
        String deckList(boolean withUserDecks);

        /** summary of a deck from the host's lists ({@code spec}: src + name); null if not found */
        String deckInfo(JsonObject spec);

        /** summary of an imported deck */
        String deckInfo(Deck deck);

        NovaServer.Response guestImage(Map<String, String> query);

        /** @return null when the match started, else the reason it could not */
        String startOnlineMatch(OnlineRoom room, List<SeatPlan> plans);

        /** a friend conceded (or left) during the match */
        void concede(Seat seat);

        /** the host lets the AI play for a friend who can't continue */
        void aiTakeover(Seat seat);

        void musicEnded(int id);

        /** the room was closed (listener, port mapping and tunnel are already shut down) */
        void onRoomClosed(OnlineRoom room);
    }

    public enum Kind { HOST, FRIEND, AI }

    /** One seat at the table. */
    public static final class Seat {
        public final int id;
        Kind kind;
        /** friend's name (null: open seat); AI: optional custom name */
        String name;
        String key;
        ClientLink link;
        boolean ready;
        long disconnectedAt;
        /** a friend's imported deck */
        Deck deck;
        /** a deck from the host's lists or a generated one: {src, name} */
        JsonObject spec;
        String deckJson;
        List<String> candidates = List.of();
        String problem;
        String profile = "";
        final int avatar;
        final int sleeve;
        // during a match
        volatile NovaGuiGame gui;
        volatile boolean aiPlaying;
        volatile boolean gone;
        // chat flood control
        long chatWindow;
        int chatCount;

        Seat(int id, Kind kind, int avatar, int sleeve) {
            this.id = id;
            this.kind = kind;
            this.avatar = avatar;
            this.sleeve = sleeve;
        }

        public Kind kind() {
            return kind;
        }

        public ClientLink link() {
            return link;
        }

        public NovaGuiGame gui() {
            return gui;
        }

        public String name() {
            return name;
        }

        public boolean isConnected() {
            return link != null && link.isConnected();
        }

        public boolean gone() {
            return gone;
        }

        public boolean aiPlaying() {
            return aiPlaying;
        }
    }

    /** A seat as the match is built from it. */
    public record SeatPlan(Seat seat, Kind kind, String name, Deck deck, JsonObject spec, String profile,
                           ClientLink link, int avatar, int sleeve) {
    }

    private static final class Conn {
        final Channel ch;
        volatile Seat seat;

        Conn(Channel ch) {
            this.ch = ch;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String secret() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private final Host host;
    private final NovaServer server;
    private final String code;
    private final int port;
    private final PortMapper upnp = new PortMapper();
    private final Tunnel tunnel;
    private final int avatarCount;
    private final int sleeveCount;

    private final List<Seat> seats = new ArrayList<>();
    private int nextSeatId = 1;
    private final Map<Channel, Conn> conns = new ConcurrentHashMap<>();
    private final Deque<String> chat = new ArrayDeque<>();
    private int chatSeq;

    private String format = "commander";
    private Integer life;
    private int games = 1;
    private boolean shareDecks = true;
    private volatile boolean inGame;
    private volatile boolean starting;
    private volatile boolean closed;

    private volatile String publicIp;
    private volatile boolean publicIpDone;
    private final List<NetInfo.Address> addresses;
    private final String lanIp;

    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Nova-Room");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService deckWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Nova-Room-Decks");
        t.setDaemon(true);
        return t;
    });

    public OnlineRoom(Host host, NovaServer server, String code, int port, Tunnel tunnel, int avatarCount, int sleeveCount) {
        this.host = host;
        this.server = server;
        this.code = code;
        this.port = port;
        this.tunnel = tunnel;
        this.avatarCount = Math.max(1, avatarCount);
        this.sleeveCount = Math.max(1, sleeveCount);
        this.addresses = NetInfo.localAddresses();
        this.lanIp = NetInfo.routableAddress();
        seats.add(new Seat(nextSeatId++, Kind.HOST, -1, -1));
        janitor.scheduleWithFixedDelay(this::housekeeping, 5, 5, TimeUnit.SECONDS);
    }

    // =================================================================== setup (host)

    /** Applies the host's settings: {format, life, games, shareDecks}. */
    public void configure(JsonObject cfg) {
        synchronized (this) {
            if (cfg.has("format")) {
                String f = cfg.get("format").getAsString();
                if (!List.of("commander", "constructed", "brawl", "oathbreaker", "tinyLeaders").contains(f)) {
                    throw new IllegalArgumentException("Unknown format " + f);
                }
                if (inGame && !f.equals(format)) {
                    throw new IllegalArgumentException("The format can't change during a game.");
                }
                format = f;
            }
            if (cfg.has("life")) {
                JsonElement l = cfg.get("life");
                life = l == null || l.isJsonNull() ? null : Math.max(1, Math.min(9999, l.getAsInt()));
            }
            if (cfg.has("games")) {
                int g = cfg.get("games").getAsInt();
                games = g == 3 || g == 5 ? g : 1;
            }
            if (cfg.has("shareDecks")) {
                shareDecks = cfg.get("shareDecks").getAsBoolean();
            }
            revalidateDecks();
        }
        broadcastState();
    }

    /** Adds a seat ("friend" or "ai"); returns its id. */
    public int addSeat(String kind) {
        int id;
        synchronized (this) {
            if (seats.size() >= MAX_PLAYERS) {
                throw new IllegalArgumentException("Forge supports at most " + MAX_PLAYERS + " players.");
            }
            Seat s = new Seat(nextSeatId++, "ai".equals(kind) ? Kind.AI : Kind.FRIEND,
                    RANDOM.nextInt(avatarCount), RANDOM.nextInt(sleeveCount));
            if (s.kind == Kind.AI) {
                s.spec = defaultAiSpec();
                s.deckJson = host.deckInfo(s.spec);
            }
            seats.add(s);
            id = s.id;
        }
        broadcastState();
        return id;
    }

    private JsonObject defaultAiSpec() {
        JsonObject spec = new JsonObject();
        spec.addProperty("src", "gen");
        spec.addProperty("name", DeckImport.isCommanderFormat(format) ? "randomCommanderPrecon" : "randomColors");
        return spec;
    }

    public void removeSeat(int id) {
        Seat removed;
        synchronized (this) {
            requireLobby();
            removed = find(id);
            if (removed == null || removed.kind == Kind.HOST) {
                throw new IllegalArgumentException("That seat can't be removed.");
            }
            seats.remove(removed);
        }
        if (removed.link != null) {
            removed.link.send(new JsonOut(96).beginObj().put("t", "roomClosed").put("reason", "The host removed your seat.").endObj().toString());
            removed.link.disconnect(NovaServer.CLOSE_KICKED, "seat removed");
        }
        broadcastState();
    }

    /**
     * Changes a seat: {id, kind?: friend|ai, deck?: {src,name}, profile?, name?}. Turning an occupied
     * friend seat into an AI seat sends that friend away.
     */
    public void updateSeat(JsonObject req) {
        Seat kicked = null;
        synchronized (this) {
            Seat s = find(req.get("id").getAsInt());
            if (s == null) {
                throw new IllegalArgumentException("No such seat.");
            }
            if (req.has("kind") && s.kind != Kind.HOST) {
                requireLobby();
                Kind k = "ai".equals(req.get("kind").getAsString()) ? Kind.AI : Kind.FRIEND;
                if (k != s.kind) {
                    if (s.kind == Kind.FRIEND && s.name != null) {
                        kicked = copyForKick(s);
                    }
                    s.kind = k;
                    clearOccupant(s);
                    s.spec = k == Kind.AI ? defaultAiSpec() : null;
                    s.deckJson = s.spec == null ? null : host.deckInfo(s.spec);
                    s.profile = "";
                }
            }
            if (req.has("deck") && s.kind != Kind.FRIEND) {
                requireLobby();
                JsonObject spec = req.getAsJsonObject("deck");
                String info = host.deckInfo(spec);
                if (info == null) {
                    throw new IllegalArgumentException("Deck not found.");
                }
                s.spec = spec;
                s.deckJson = info;
                revalidate(s);
            }
            if (req.has("profile") && s.kind == Kind.AI) {
                s.profile = req.get("profile").getAsString();
            }
        }
        if (kicked != null && kicked.link != null) {
            kicked.link.send(new JsonOut(96).beginObj().put("t", "roomClosed").put("reason", "The host gave your seat to an AI player.").endObj().toString());
            kicked.link.disconnect(NovaServer.CLOSE_KICKED, "seat changed");
        }
        broadcastState();
    }

    private static Seat copyForKick(Seat s) {
        Seat c = new Seat(-1, s.kind, 0, 0);
        c.link = s.link;
        c.name = s.name;
        return c;
    }

    /** Sends a friend away (their seat stays open for someone else). */
    public void kick(int id) {
        Seat gone;
        synchronized (this) {
            requireLobby();
            Seat s = find(id);
            if (s == null || s.kind != Kind.FRIEND || s.name == null) {
                return;
            }
            gone = copyForKick(s);
            clearOccupant(s);
        }
        systemChat(gone.name + " was removed from the room.");
        if (gone.link != null) {
            gone.link.send(new JsonOut(96).beginObj().put("t", "roomClosed").put("reason", "The host removed you from the room.").endObj().toString());
            gone.link.disconnect(NovaServer.CLOSE_KICKED, "kicked");
        }
        broadcastState();
    }

    private void clearOccupant(Seat s) {
        s.name = s.kind == Kind.AI ? s.name : null;
        s.key = null;
        s.link = null;
        s.ready = false;
        s.deck = null;
        s.spec = null;
        s.deckJson = null;
        s.candidates = List.of();
        s.problem = null;
        s.disconnectedAt = 0;
        if (s.kind == Kind.FRIEND) {
            s.name = null;
        }
        for (Conn c : conns.values()) {
            if (c.seat == s) c.seat = null;
        }
    }

    private void requireLobby() {
        if (inGame || starting) {
            throw new IllegalArgumentException("Not while a game is running.");
        }
    }

    private Seat find(int id) {
        for (Seat s : seats) {
            if (s.id == id) return s;
        }
        return null;
    }

    public synchronized Seat hostSeat() {
        return seats.get(0);
    }

    public synchronized List<Seat> seats() {
        return new ArrayList<>(seats);
    }

    public String code() {
        return code;
    }

    public int port() {
        return port;
    }

    public synchronized String format() {
        return format;
    }

    public synchronized int games() {
        return games;
    }

    /** starting life for everyone, null: the format's default */
    public synchronized Integer life() {
        return life;
    }

    public boolean isInGame() {
        return inGame;
    }

    public boolean isClosed() {
        return closed;
    }

    // =================================================================== network (invite)

    /** Starts the public-address lookup and (optionally) the router port mapping. */
    public void startNetworking(boolean useUpnp) {
        Thread t = new Thread(() -> {
            publicIp = NetInfo.publicIp();
            publicIpDone = true;
            broadcastState();
        }, "Nova-PublicIp");
        t.setDaemon(true);
        t.start();
        if (useUpnp && lanIp != null && !"localhost".equals(lanIp)) {
            upnp.open(port, lanIp, this::broadcastState);
        }
    }

    public void setTunnel(boolean on) {
        if (on) {
            tunnel.start(port, this::broadcastState);
        } else {
            tunnel.stop();
            broadcastState();
        }
    }

    public void setUpnp(boolean on) {
        if (on) {
            upnp.open(port, lanIp, this::broadcastState);
        } else {
            upnp.close();
        }
        broadcastState();
    }

    private String link(String base) {
        return base + "/#join=" + code;
    }

    private String inviteJson() {
        JsonOut o = new JsonOut(1024).beginObj();
        o.put("port", port).put("code", code);
        o.beginArr("links");
        if (tunnel.state() == Tunnel.State.ON && tunnel.url() != null) {
            o.beginObj().put("kind", "tunnel").put("label", "Internet (Cloudflare link)").put("url", link(tunnel.url()))
                    .put("note", "Works from anywhere, no router setup needed.").endObj();
        }
        String pub = publicIp;
        boolean cgnat = NetInfo.isPrivateAddress(upnp.routerIp()) || (upnp.routerIp() != null && pub != null && !pub.equals(upnp.routerIp()));
        if (pub != null) {
            String note = switch (upnp.state()) {
                case OK -> cgnat
                        ? "Your router is behind another router of your internet provider, so this link probably won't work. Use a Cloudflare link or a VPN instead."
                        : "Your router forwards port " + port + " to this computer automatically (UPnP).";
                case WORKING -> "Asking your router to open port " + port + "…";
                case FAILED -> "Needs port " + port + " (TCP) forwarded to " + lanIp + " in your router settings. "
                        + (upnp.detail() == null ? "" : "(Automatic setup failed: " + upnp.detail() + ")");
                default -> "Needs port " + port + " (TCP) forwarded to " + lanIp + " in your router settings.";
            };
            o.beginObj().put("kind", "public").put("label", "Internet").put("url", link("http://" + pub + ":" + port))
                    .put("note", note).flag("warn", upnp.state() != PortMapper.State.OK || cgnat).endObj();
        }
        for (NetInfo.Address a : addresses) {
            o.beginObj().put("kind", a.kind()).put("label", a.label()).put("url", link("http://" + a.ip() + ":" + port))
                    .put("note", a.kind().equals("vpn") ? "For friends in the same " + a.label() + " network." : "For friends on your Wi-Fi / home network.")
                    .endObj();
        }
        o.endArr();
        o.put("upnp", upnp.state().name().toLowerCase(Locale.ROOT));
        o.putOpt("upnpDetail", upnp.detail());
        o.putOpt("publicIp", pub);
        o.flag("publicIpDone", publicIpDone);
        o.flag("cgnat", cgnat);
        o.putOpt("lanIp", lanIp);
        o.beginObj("tunnel").put("state", tunnel.state().name().toLowerCase(Locale.ROOT)).putOpt("url", tunnel.url())
                .putOpt("error", tunnel.error()).flag("available", tunnel.state() != Tunnel.State.MISSING && tunnel.isAvailable()).endObj();
        o.endObj();
        return o.toString();
    }

    // =================================================================== state for the clients

    private boolean connected(Seat s) {
        return switch (s.kind) {
            case HOST -> host.localLink().isConnected();
            case FRIEND -> s.isConnected();
            case AI -> true;
        };
    }

    private String displayName(Seat s) {
        return switch (s.kind) {
            case HOST -> host.hostName();
            case FRIEND -> s.name;
            case AI -> s.name;
        };
    }

    private synchronized String stateJson(Seat viewer, boolean forHost) {
        JsonOut o = new JsonOut(2048).beginObj().put("t", "room");
        o.put("proto", PROTOCOL);
        o.put("state", inGame || starting ? "game" : "lobby");
        o.flag("isHost", forHost);
        o.put("you", viewer == null ? -1 : viewer.id);
        o.put("hostName", host.hostName());
        o.put("format", format).put("games", games);
        if (life != null) o.put("life", life);
        o.flag("shareDecks", shareDecks);
        o.beginArr("seats");
        for (Seat s : seats) {
            o.beginObj().put("id", s.id).put("kind", s.kind.name().toLowerCase(Locale.ROOT));
            o.putOpt("name", displayName(s));
            o.flag("open", s.kind == Kind.FRIEND && s.name == null);
            o.flag("conn", connected(s));
            if (s.kind == Kind.FRIEND && s.link != null && s.link.rtt() >= 0) o.put("rtt", s.link.rtt());
            o.flag("ready", s.kind != Kind.FRIEND || s.ready);
            o.flag("ai", s.aiPlaying);
            o.flag("gone", s.gone);
            if (s.kind == Kind.AI) o.put("profile", s.profile == null ? "" : s.profile);
            if (s.deckJson != null) o.rawPut("deck", s.deckJson);
            o.putOpt("problem", s.problem);
            if (s.spec != null && (forHost || s == viewer)) o.rawPut("spec", s.spec.toString());
            if (s == viewer && !s.candidates.isEmpty()) {
                o.beginArr("candidates");
                for (String c : s.candidates) o.val(c);
                o.endArr();
            }
            o.endObj();
        }
        o.endArr();
        if (forHost) {
            o.rawPut("invite", inviteJson());
            o.putOpt("why", startProblem());
        }
        o.endObj();
        return o.toString();
    }

    /**
     * Sends every participant their view of the room. Built and sent under the room's lock, so a
     * state from before a match started can never arrive after the match's first messages.
     */
    public synchronized void broadcastState() {
        if (closed) {
            return;
        }
        ClientLink hl = host.localLink();
        hl.send(stateJson(hostSeat(), true));
        for (Seat s : seats) {
            if (s.kind == Kind.FRIEND && s.link != null) {
                s.link.send(stateJson(s, false));
            }
        }
    }

    /** The host's window (re)connected. */
    public synchronized void sendStateToHost() {
        ClientLink hl = host.localLink();
        hl.send(stateJson(hostSeat(), true));
        for (String c : chatHistory()) hl.send(c);
    }

    private synchronized List<String> chatHistory() {
        return new ArrayList<>(chat);
    }

    // =================================================================== chat

    /** Posts a chat line to everyone (ids let a reconnecting client skip lines it already shows). */
    private void postChat(String from, int seat, String msg) {
        String json;
        synchronized (this) {
            JsonOut o = new JsonOut(160 + msg.length()).beginObj().put("t", "chat").put("id", ++chatSeq);
            if (from == null) {
                o.put("sys", true);
            } else {
                o.put("from", from).put("seat", seat);
            }
            json = o.put("msg", msg).put("ts", System.currentTimeMillis()).endObj().toString();
            chat.addLast(json);
            while (chat.size() > CHAT_HISTORY) chat.removeFirst();
        }
        host.localLink().send(json);
        for (Seat s : seats()) {
            if (s.kind == Kind.FRIEND && s.link != null) s.link.send(json);
        }
    }

    public void systemChat(String msg) {
        postChat(null, 0, msg);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(cp -> cp >= 0x20 && cp != 0x7f && !(cp >= 0x200b && cp <= 0x200f) && !(cp >= 0x202a && cp <= 0x202e))
                .forEach(b::appendCodePoint);
        String r = b.toString().trim().replaceAll("\\s+", " ");
        return r.length() > max ? r.substring(0, max) : r;
    }

    private void chatFrom(Seat s, String name, String text) {
        String msg = clean(text, MAX_CHAT);
        if (msg.isEmpty()) return;
        if (s != null) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (now - s.chatWindow > 5000) {
                    s.chatWindow = now;
                    s.chatCount = 0;
                }
                if (++s.chatCount > 6) return; // flood
            }
        }
        postChat(name, s == null ? 0 : s.id, msg);
    }

    public void hostChat(String text) {
        chatFrom(hostSeat(), host.hostName(), text);
    }

    // =================================================================== decks

    private void revalidateDecks() {
        for (Seat s : seats) revalidate(s);
    }

    /** Re-checks a seat's deck against the current format. */
    private void revalidate(Seat s) {
        if (s.deck != null) {
            s.problem = DeckImport.problem(s.deck, format);
            s.deckJson = host.deckInfo(s.deck);
            if (DeckImport.isCommanderFormat(format) && !DeckImport.hasCommander(s.deck) && s.candidates.isEmpty()) {
                s.candidates = candidatesOf(s.deck);
            }
        } else if (s.spec != null) {
            String src = s.spec.has("src") ? s.spec.get("src").getAsString() : "gen";
            s.problem = null;
            if ("gen".equals(src)) {
                // generated decks follow the format
                String n = s.spec.get("name").getAsString();
                boolean cmdrGen = n.startsWith("randomCommander");
                if (!"randomUser".equals(n) && cmdrGen != DeckImport.isCommanderFormat(format)) {
                    if (s.kind == Kind.FRIEND) {
                        s.spec = null;
                        s.deckJson = null;
                        s.ready = false;
                    } else {
                        s.spec = defaultAiSpec();
                        s.deckJson = host.deckInfo(s.spec);
                    }
                }
            } else if (s.deckJson != null && DeckImport.isCommanderFormat(format) && !s.deckJson.contains("\"cmdrs\"")) {
                s.problem = "This deck has no commander.";
            }
        } else {
            s.problem = null;
        }
        if (s.problem != null && s.kind == Kind.FRIEND && s.problem.startsWith("No commander")) {
            s.ready = false;
        }
    }

    private List<String> candidatesOf(Deck d) {
        try {
            return DeckImport.candidates(d, format);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** A friend chose a deck: {mode: text|host|gen, text?, name, src?}. Runs on the deck worker. */
    private void chooseDeck(Seat s, JsonObject m) {
        String mode = m.has("mode") ? m.get("mode").getAsString() : "";
        JsonOut reply = new JsonOut(512).beginObj().put("t", "deckResult");
        try {
            synchronized (this) {
                requireLobby();
            }
            switch (mode) {
                case "text" -> {
                    String name = clean(m.has("name") ? m.get("name").getAsString() : "", 60);
                    DeckImport.Result r = DeckImport.parse(m.get("text").getAsString(), name, format);
                    if (r.deck() == null) {
                        throw new IllegalArgumentException("No cards were recognized in that list.");
                    }
                    if (m.has("check") && m.get("check").getAsBoolean()) {
                        // only read it (a friend adding decks to their list): the seat keeps its deck
                        reply.put("ok", true).put("check", true).rawPut("deck", host.deckInfo(r.deck()));
                        reply.putOpt("problem", DeckImport.problem(r.deck(), format));
                        reply.beginArr("unknown");
                        for (String u : r.unknown()) reply.val(u);
                        reply.endArr();
                        reply.putOpt("ref", m.has("ref") ? m.get("ref").getAsString() : null);
                        reply.endObj();
                        if (s.link != null) s.link.send(reply.toString());
                        return;
                    }
                    synchronized (this) {
                        s.deck = r.deck();
                        s.spec = null;
                        s.candidates = r.candidates();
                        s.ready = false;
                        revalidate(s);
                        reply.put("ok", true).rawPut("deck", s.deckJson).putOpt("problem", s.problem);
                    }
                    reply.beginArr("unknown");
                    for (String u : r.unknown()) reply.val(u);
                    reply.endArr();
                    reply.beginArr("candidates");
                    for (String c : r.candidates()) reply.val(c);
                    reply.endArr();
                }
                case "host", "gen" -> {
                    JsonObject spec = new JsonObject();
                    String src = "gen".equals(mode) ? "gen" : m.get("src").getAsString();
                    boolean builtin = src.equals("cmdPrecon") || src.equals("precon") || src.equals("gen");
                    if (!builtin && !shareDecks) {
                        throw new IllegalArgumentException("The host doesn't share their decks.");
                    }
                    spec.addProperty("src", src);
                    spec.addProperty("name", m.get("name").getAsString());
                    String info = host.deckInfo(spec);
                    if (info == null) {
                        throw new IllegalArgumentException("Deck not found.");
                    }
                    synchronized (this) {
                        s.deck = null;
                        s.spec = spec;
                        s.deckJson = info;
                        s.candidates = List.of();
                        s.ready = false;
                        revalidate(s);
                        reply.put("ok", true).rawPut("deck", s.deckJson).putOpt("problem", s.problem);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown deck choice.");
            }
        } catch (IllegalArgumentException e) {
            reply.put("ok", false).put("error", e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            reply.put("ok", false).put("error", "That deck could not be read: " + e.getMessage());
        }
        reply.putOpt("ref", m.has("ref") ? m.get("ref").getAsString() : null);
        reply.endObj();
        if (s.link != null) s.link.send(reply.toString());
        broadcastState();
    }

    private void chooseCommander(Seat s, JsonObject m) {
        List<String> names = new ArrayList<>();
        if (m.has("names")) {
            for (JsonElement e : m.getAsJsonArray("names")) names.add(e.getAsString());
        }
        synchronized (this) {
            if (s.deck == null || names.isEmpty() || names.size() > 2 || inGame) {
                return;
            }
            // put back earlier choices first
            forge.deck.CardPool cmd = s.deck.get(forge.deck.DeckSection.Commander);
            if (cmd != null && !cmd.isEmpty()) {
                s.deck.getMain().addAll(cmd);
                cmd.clear();
            }
            DeckImport.chooseCommanders(s.deck, names);
            s.candidates = List.of();
            s.ready = false;
            revalidate(s);
        }
        broadcastState();
    }

    // =================================================================== guests (network threads)

    @Override
    public boolean acceptsRoom(String c) {
        return !closed && c != null && c.equals(code);
    }

    @Override
    public NovaServer.Response guestImage(Map<String, String> query) {
        return host.guestImage(query);
    }

    @Override
    public void onGuestOpen(Channel ch, Map<String, String> query) {
        if (closed) {
            ch.close();
            return;
        }
        if (conns.size() >= 40) {
            ch.close(); // a handful of friends never need this many connections
            return;
        }
        Conn conn = new Conn(ch);
        conns.put(ch, conn);
        String v = query.get("v");
        if (v != null && !v.equals(String.valueOf(PROTOCOL))) {
            reject(ch, NovaServer.CLOSE_VERSION, "Your Forge Nova version doesn't match the host's. Open the invite link in a web browser instead.");
            return;
        }
        String key = query.get("key");
        Seat seat = null;
        if (key != null && !key.isEmpty()) {
            synchronized (this) {
                for (Seat s : seats) {
                    if (s.kind == Kind.FRIEND && key.equals(s.key)) {
                        seat = s;
                    }
                }
            }
        }
        if (seat != null) {
            conn.seat = seat;
            seat.disconnectedAt = 0;
            seat.link.attach(ch); // -> onSeatConnected
            return;
        }
        // not seated yet: show what the room is about, the client then asks for a seat
        ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(previewJson()));
    }

    private synchronized String previewJson() {
        int open = 0;
        for (Seat s : seats) {
            if (s.kind == Kind.FRIEND && s.name == null) open++;
        }
        return new JsonOut(256).beginObj().put("t", "roomInfo").put("proto", PROTOCOL).put("hostName", host.hostName())
                .put("format", format).put("players", seats.size()).put("open", open).flag("inGame", inGame || starting)
                .endObj().toString();
    }

    private void reject(Channel ch, int code, String msg) {
        ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(
                new JsonOut(160).beginObj().put("t", "joinError").put("msg", msg).endObj().toString()));
        ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.CloseWebSocketFrame(code, "rejected"))
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private static void sendTo(Channel ch, String json) {
        ch.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(json));
    }

    /** A friend asks for a seat: {t:'join', name}. */
    private void join(Conn conn, JsonObject m) {
        String name = clean(m.has("name") ? m.get("name").getAsString() : "", MAX_NAME);
        String err = null;
        Seat seat = null;
        synchronized (this) {
            if (name.isEmpty()) {
                err = "Please enter a name.";
            } else if (name.toLowerCase(Locale.ROOT).contains("human")) {
                err = "Forge reserves the word \"human\" in player names. Please pick another name.";
            } else if (inGame || starting) {
                err = "A game is in progress. You can join when it's over.";
            } else {
                for (Seat s : seats) {
                    String other = displayName(s);
                    if (other != null && other.equalsIgnoreCase(name)) {
                        err = "Someone called " + other + " is already here. Please pick another name.";
                    }
                }
                if (err == null) {
                    for (Seat s : seats) {
                        if (s.kind == Kind.FRIEND && s.name == null) {
                            seat = s;
                            break;
                        }
                    }
                    if (seat == null) {
                        err = "The room is full.";
                    }
                }
            }
            if (seat != null) {
                seat.name = name;
                seat.key = secret();
                seat.ready = false;
                seat.disconnectedAt = 0;
                final Seat s = seat;
                seat.link = new ClientLink("friend:" + name);
                seat.link.setListener(() -> onSeatConnected(s));
                conn.seat = seat;
            }
        }
        if (err != null) {
            sendTo(conn.ch, new JsonOut(160).beginObj().put("t", "joinError").put("msg", err).endObj().toString());
            return;
        }
        systemChat(name + " joined.");
        seat.link.attach(conn.ch);
    }

    /** A friend's browser (re)connected to their seat. */
    private void onSeatConnected(Seat s) {
        ClientLink l = s.link;
        if (l == null) {
            return;
        }
        l.send(new JsonOut(160).beginObj().put("t", "welcome").put("key", s.key).put("seat", s.id).put("proto", PROTOCOL)
                .put("name", s.name).endObj().toString());
        for (String c : chatHistory()) l.send(c);
        broadcastState();
        NovaGuiGame g = s.gui;
        if (g != null && !g.isDisposed() && g.isActive()) {
            g.resync();
        }
        if (inGame) {
            systemChat(s.name + " is back.");
            sendPeers();
        }
    }

    @Override
    public void onGuestMessage(Channel ch, JsonObject m) {
        Conn conn = conns.get(ch);
        if (conn == null || closed) {
            return;
        }
        String t = m.has("t") ? m.get("t").getAsString() : "";
        Seat s = conn.seat;
        if (s != null && s.link != null) {
            s.link.touch();
        }
        if ("ping".equals(t)) {
            if (s != null && s.link != null && m.has("rtt")) {
                s.link.setRtt(Math.max(0, Math.min(99999, m.get("rtt").getAsInt())));
            }
            sendTo(ch, "{\"t\":\"pong\"" + (m.has("ts") ? ",\"ts\":" + m.get("ts").getAsLong() : "") + "}");
            return;
        }
        if (s == null || s.link == null || s.link.channel() != ch) {
            if ("join".equals(t)) {
                join(conn, m);
            }
            return;
        }
        switch (t) {
            case "chat" -> chatFrom(s, s.name, m.has("msg") ? m.get("msg").getAsString() : "");
            case "deck" -> deckWorker.execute(() -> chooseDeck(s, m));
            case "commander" -> chooseCommander(s, m);
            case "ready" -> setReady(s, m.has("on") && m.get("on").getAsBoolean());
            case "hostDecks" -> deckWorker.execute(() -> s.link.send("{\"t\":\"hostDecks\",\"shared\":" + shareDecks
                    + ",\"data\":" + host.deckList(shareDecks) + "}"));
            case "leave" -> leave(s);
            case "reply" -> s.link.onReply(m.get("id").getAsInt(), m.get("v"));
            case "musicEnded" -> host.musicEnded(m.get("id").getAsInt());
            case "concede" -> {
                if (s.gui != null) host.concede(s);
            }
            default -> {
                NovaGuiGame g = s.gui;
                if (g != null && !s.aiPlaying && !s.gone) {
                    g.handleAction(m);
                }
            }
        }
    }

    private void setReady(Seat s, boolean on) {
        String why = null;
        synchronized (this) {
            if (inGame) return;
            if (on && s.deckJson == null) why = "Choose a deck first.";
            else if (on && s.problem != null && s.problem.startsWith("No commander")) why = "Choose your commander first.";
            else s.ready = on;
        }
        if (why != null) {
            s.link.send(new JsonOut(96).beginObj().put("t", "toast").put("msg", why).put("level", "error").endObj().toString());
        }
        broadcastState();
    }

    /** A friend leaves the room (during a match: concedes and watches no more). */
    private void leave(Seat s) {
        String name = s.name;
        ClientLink l = s.link;
        boolean match;
        synchronized (this) {
            match = inGame && s.gui != null;
            if (match) {
                s.gone = true;
            } else {
                clearOccupant(s);
            }
        }
        if (match) {
            host.concede(s);
        }
        systemChat(name + " left.");
        if (l != null) {
            l.disconnect(NovaServer.CLOSE_REJECTED, "left");
        }
        broadcastState();
    }

    @Override
    public void onGuestClose(Channel ch) {
        Conn conn = conns.remove(ch);
        if (conn == null) {
            return;
        }
        Seat s = conn.seat;
        if (s == null || s.link == null) {
            return;
        }
        if (s.link.detach(ch)) {
            s.disconnectedAt = System.currentTimeMillis();
            if (!closed && s.name != null && !s.gone) {
                systemChat(s.name + (inGame ? " lost the connection. Waiting for them to come back…" : " disconnected."));
            }
            broadcastState();
            if (inGame) sendPeers();
        }
    }

    /** Frees lobby seats of friends who never came back; refreshes connection info. */
    private void housekeeping() {
        if (closed) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            List<String> left = new ArrayList<>();
            synchronized (this) {
                if (!inGame && !starting) {
                    for (Seat s : seats) {
                        if (s.kind == Kind.FRIEND && s.name != null && !s.isConnected() && s.disconnectedAt > 0
                                && now - s.disconnectedAt > SEAT_HOLD_MS) {
                            left.add(s.name);
                            clearOccupant(s);
                        }
                    }
                }
            }
            for (String n : left) systemChat(n + "'s seat is open again.");
            if (inGame) {
                sendPeers();
            } else {
                broadcastState(); // latency figures
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    /** Connection status of the players in the match, for their boards. */
    public void sendPeers() {
        String json = peersJson();
        for (ClientLink l : matchLinks()) l.send(json);
    }

    // =================================================================== match

    /** Why the host can't start yet (null: ready to go). */
    private synchronized String startProblem() {
        if (inGame || starting) return "A game is running.";
        if (seats.size() < 2) return "Add at least one more player.";
        Seat h = seats.get(0);
        if (h.spec == null) return "Choose your deck.";
        if (h.problem != null) return "Your deck: " + h.problem;
        int open = 0;
        for (Seat s : seats) {
            if (s.kind == Kind.FRIEND) {
                if (s.name == null) {
                    open++;
                } else if (!s.isConnected()) {
                    return s.name + " is disconnected.";
                } else if (s.deckJson == null) {
                    return "Waiting for " + s.name + " to choose a deck.";
                } else if (!s.ready) {
                    return "Waiting for " + s.name + " to be ready.";
                }
            } else if (s.kind == Kind.AI && s.problem != null) {
                return "AI deck: " + s.problem;
            }
        }
        if (open > 0) {
            return open == 1 ? "Waiting for a friend to join (or make the open seat an AI)."
                    : "Waiting for " + open + " friends to join (or make open seats AI players).";
        }
        return null;
    }

    /** Starts the match; returns null or the reason it did not start. */
    public String start() {
        List<SeatPlan> plans = new ArrayList<>();
        synchronized (this) {
            String why = startProblem();
            if (why != null) {
                return why;
            }
            for (Seat s : seats) {
                plans.add(new SeatPlan(s, s.kind, displayName(s), s.deck, s.spec, s.profile,
                        s.kind == Kind.HOST ? host.localLink() : s.link, s.avatar, s.sleeve));
                s.gone = false;
                s.aiPlaying = false;
            }
            starting = true;
        }
        String err;
        try {
            err = host.startOnlineMatch(this, plans);
        } catch (RuntimeException e) {
            e.printStackTrace();
            err = "Could not start the match: " + e;
        }
        synchronized (this) {
            starting = false;
            inGame = err == null;
        }
        if (err == null) {
            systemChat("The game begins. Good luck!");
        }
        broadcastState();
        return err;
    }

    /** Called by the host while starting: the GUI of each human seat. */
    public void bindGui(Seat s, NovaGuiGame gui) {
        s.gui = gui;
    }

    /** The match is over (called on the UI thread); everyone returns to the room. */
    public void onMatchEnded() {
        synchronized (this) {
            inGame = false;
            for (Seat s : seats) {
                s.gui = null;
                s.aiPlaying = false;
                if (s.kind == Kind.FRIEND) {
                    s.ready = false;
                    if (s.gone) {
                        s.gone = false;
                        clearOccupant(s);
                    }
                }
            }
        }
        broadcastState();
    }

    /** The host made the AI play for a friend. */
    public void markAiPlaying(Seat s) {
        s.aiPlaying = true;
        systemChat("The AI plays for " + s.name + " now.");
        broadcastState();
        sendPeers();
    }

    public Seat seatById(int id) {
        synchronized (this) {
            return find(id);
        }
    }

    /** The human seats of the running match, host first. */
    public List<Seat> humanSeats() {
        List<Seat> out = new ArrayList<>();
        for (Seat s : seats()) {
            if (s.gui != null) out.add(s);
        }
        return out;
    }

    /** Connection status of the humans in the match, for the board: {t:'peers', list:[...]}. */
    public String peersJson() {
        JsonOut o = new JsonOut(512).beginObj().put("t", "peers").beginArr("list");
        for (Seat s : humanSeats()) {
            NovaGuiGame g = s.gui;
            int pid = -1;
            try {
                for (var pv : g.localPlayers()) {
                    pid = pv.getId();
                    break;
                }
            } catch (RuntimeException ignored) {
                // between games
            }
            o.beginObj().put("seat", s.id).put("pid", pid).putOpt("name", displayName(s))
                    .flag("host", s.kind == Kind.HOST).flag("conn", connected(s)).flag("ai", s.aiPlaying).flag("gone", s.gone);
            if (s.kind == Kind.FRIEND && s.link != null && s.link.rtt() >= 0) o.put("rtt", s.link.rtt());
            o.endObj();
        }
        o.endArr().endObj();
        return o.toString();
    }

    /** Everyone who takes part in the match (for sounds and status updates). */
    public List<ClientLink> matchLinks() {
        List<ClientLink> out = new ArrayList<>();
        out.add(host.localLink());
        for (Seat s : seats()) {
            if (s.kind == Kind.FRIEND && s.link != null && s.gui != null) out.add(s.link);
        }
        return out;
    }

    // =================================================================== closing

    /** Closes the room: friends are told why and disconnected, the port is closed again. */
    public void close(String reason) {
        List<Seat> all;
        synchronized (this) {
            if (closed) return;
            closed = true;
            all = new ArrayList<>(seats);
        }
        String msg = new JsonOut(128).beginObj().put("t", "roomClosed").put("reason", reason).endObj().toString();
        for (Seat s : all) {
            if (s.kind == Kind.FRIEND && s.link != null) {
                s.link.send(msg);
                s.link.cancelAllDialogs();
                s.link.disconnect(NovaServer.CLOSE_ROOM_CLOSED, "room closed");
            }
        }
        for (Conn c : new ArrayList<>(conns.values())) {
            if (c.ch.isActive()) {
                sendTo(c.ch, msg);
                c.ch.close();
            }
        }
        conns.clear();
        server.stopOnline();
        upnp.close();
        tunnel.stop();
        janitor.shutdownNow();
        deckWorker.shutdownNow();
        host.onRoomClosed(this);
    }
}

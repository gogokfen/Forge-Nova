package forge.nova.moxfield;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.nova.DeckService;
import forge.nova.LobbyService;
import forge.nova.util.JsonOut;
import forge.util.FileSection;
import forge.util.storage.IStorage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps Forge's deck files in step with a Moxfield account: decks are edited on Moxfield, Forge gets copies.
 *
 * <ul>
 * <li>The user's decks come from Moxfield's search, <b>including the ones Moxfield marks as not legal</b> (a
 * Commander deck that isn't exactly 100 cards...), which a plain search leaves out. Unlisted decks aren't listed
 * anywhere, so they are added once by link; private decks can't be read without logging in.</li>
 * <li>A synced deck file carries its Moxfield link in Forge's own "Source URL" field, so the link survives
 * renames. Fingerprints of what the last sync wrote tell a change on Moxfield (safe to apply, also automatically)
 * from edits made in Forge (never overwritten without asking).</li>
 * <li>Deck details are cached per deck and downloaded again only when Moxfield lists a newer edit, so a check
 * where nothing changed costs one request.</li>
 * </ul>
 */
public final class MoxfieldSync {
    /** the deck folders a sync writes to (the lobby's format ids) */
    private static final String[] FOLDERS = {"commander", "constructed", "brawl", "oathbreaker", "tinyLeaders"};
    private static final Pattern DECK_URL = Pattern.compile("moxfield\\.com/decks/([A-Za-z0-9_-]{6,64})");
    private static final Pattern DECK_ID = Pattern.compile("[A-Za-z0-9_-]{6,64}");
    /** decks edited this recently are downloaded again even when the search still lists the older edit */
    private static final long RECENT_MS = 15 * 60_000L;
    /** how often returning to the lobby checks Moxfield again (when automatic sync is on) */
    private static final long AUTO_EVERY_MS = 10 * 60_000L;
    /** Moxfield boards and the keys they are cached under, in the order a deck shows them */
    private static final String[][] BOARDS = {{"commanders", "commander"}, {"signatureSpells", "signature"},
            {"mainboard", "main"}, {"sideboard", "side"}, {"companions", "companion"}, {"attractions", "attractions"},
            {"contraptions", "contraptions"}, {"planes", "planes"}, {"schemes", "schemes"}};
    /** Forge sections a sync always writes; the extra ones only when the Moxfield deck has cards there */
    private static final DeckSection[] ALWAYS = {DeckSection.Commander, DeckSection.Main, DeckSection.Sideboard};
    private static final DeckSection[] EXTRA = {DeckSection.Attractions, DeckSection.Contraptions, DeckSection.Planes, DeckSection.Schemes};

    private final MoxfieldClient api = new MoxfieldClient();
    private final File stateFile;
    private final File cacheDir;
    /** messages for the app window (progress, results of automatic syncs) */
    private final Consumer<String> push;
    /** one check or sync at a time */
    private final ReentrantLock running = new ReentrantLock();

    // ---- saved settings and sync records (guarded by this)
    private String user = "";
    private boolean auto = true;
    private final Set<String> extra = new LinkedHashSet<>();
    private final Set<String> hidden = new HashSet<>();
    private final Map<String, Link> links = new HashMap<>();
    private long lastCheck;

    // ---- the last check (guarded by this)
    private List<MoxDeck> fetched;
    private String fetchedFor;
    private String report;
    private int pending;
    private boolean autoRunning;
    /** why the last automatic sync failed (cleared by the next successful check) */
    private String autoError;

    /** What the last sync of a Moxfield deck wrote. */
    private static final class Link {
        String src;
        String name;
        /** the Moxfield deck's name back then (a new name means it was renamed there) */
        String mname;
        /** fingerprints of Moxfield's cards back then and of the file written */
        String fpMox;
        String fpForge;
        long at;
    }

    /** One card line of a Moxfield deck. */
    record Card(String name, String set, String cn, int qty) {
    }

    /** The parts of a Moxfield deck Forge needs. */
    static final class MoxDeck {
        String id;
        String name;
        String format;
        String updated;
        /** the edit time Moxfield's search listed when this copy was downloaded */
        String listed;
        String visibility;
        String author;
        boolean extra;
        /** why the deck couldn't be downloaded (no copy at all) or why this copy may be old */
        String error;
        final Map<String, List<Card>> boards = new LinkedHashMap<>();
    }

    /**
     * A deck in Forge's folders: {@code key} is its name in {@code folder}, {@code prefix} the subfolders
     * ("" or "Old decks/"), as in Nova's deck paths.
     */
    private record Local(String src, String prefix, String key, IStorage<Deck> folder, Deck deck, String moxId) {
        String path() {
            return prefix + key;
        }
    }

    /** A Moxfield deck compared with Forge's copy. */
    private static final class Item {
        MoxDeck mox;
        /** Moxfield's cards as a Forge deck; null when the deck couldn't be downloaded */
        Deck deck;
        final List<String> unknown = new ArrayList<>();
        final List<String> demoted = new ArrayList<>();
        Local local;
        String status;
        /** where a new deck goes */
        String targetSrc;
        String targetName;
        /** the new Forge name when the deck was renamed on Moxfield */
        String rename;
        String fpMox;
        final List<String> diffJson = new ArrayList<>();
        int added;
        int removed;
        int printings;
    }

    /** What happened to one deck in a sync. */
    private record Result(String id, String name, String src, String path, boolean created, String error) {
    }

    public MoxfieldSync(File novaDir, Consumer<String> push) {
        this.stateFile = new File(ForgeConstants.USER_PREFS_DIR, "nova-moxfield.json");
        this.cacheDir = new File(novaDir, "cache" + File.separator + "moxfield");
        this.push = push;
        load();
    }

    // ================================================================== public API (the host's HTTP handlers)

    /** Settings and the last check, for the Moxfield window and the lobby's badge. */
    public synchronized String stateJson() {
        JsonOut o = new JsonOut(report == null ? 512 : report.length() + 512).beginObj();
        o.put("user", user).put("auto", auto).put("lastCheck", lastCheck).put("pending", pending);
        o.flag("running", running.isLocked()).putOpt("error", autoError);
        o.beginArr("extra");
        for (String id : extra) o.val(id);
        o.endArr();
        if (report != null) o.rawPut("report", report);
        return o.endObj().toString();
    }

    /** Changes the user name and/or automatic sync. */
    public String settings(JsonObject req) {
        synchronized (this) {
            if (req.has("user") && !req.get("user").isJsonNull()) {
                setUser(req.get("user").getAsString());
            }
            if (req.has("auto") && !req.get("auto").isJsonNull()) {
                auto = req.get("auto").getAsBoolean();
            }
            save();
        }
        return stateJson();
    }

    /**
     * Downloads the deck list (and the decks that changed) and compares it with Forge's decks.
     *
     * @param userArg a new user name, or null to keep the saved one
     * @param force   download every deck again instead of trusting the cache
     */
    public String check(String userArg, boolean force) throws IOException, InterruptedException {
        running.lock();
        try {
            if (userArg != null) {
                synchronized (this) {
                    setUser(userArg);
                    save();
                }
            }
            fetchNow(force);
            compareAndReport();
            return stateJson();
        } finally {
            running.unlock();
        }
    }

    /** Writes the given Moxfield decks into Forge's folders; returns what happened and the new state. */
    public String apply(Collection<String> ids) throws IOException, InterruptedException {
        running.lock();
        try {
            boolean stale;
            synchronized (this) {
                stale = fetched == null || !Objects.equals(fetchedFor, user);
            }
            if (stale) {
                fetchNow(false);
            }
            List<Result> results = write(new HashSet<>(ids), false);
            compareAndReport();
            JsonOut o = new JsonOut(1024).beginObj();
            writeResults(o, results);
            return o.rawPut("state", stateJson()).endObj().toString();
        } finally {
            running.unlock();
        }
    }

    /** Adds decks by link (unlisted decks, or anyone's public deck). */
    public String addLinks(String text) throws IOException, InterruptedException {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = DECK_URL.matcher(text == null ? "" : text);
        while (m.find()) ids.add(m.group(1));
        if (ids.isEmpty() && text != null && DECK_ID.matcher(text.trim()).matches()) ids.add(text.trim());
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No Moxfield deck links found. They look like https://moxfield.com/decks/…");
        }
        running.lock();
        try {
            List<String> missing = new ArrayList<>();
            for (String id : ids) {
                JsonObject j = api.deck(id);
                if (j == null) {
                    missing.add(id);
                    continue;
                }
                MoxDeck d = parse(j);
                d.listed = d.updated;
                writeCache(d);
                synchronized (this) {
                    extra.add(id);
                    hidden.remove(id);
                }
            }
            synchronized (this) {
                save();
            }
            if (missing.size() == ids.size()) {
                throw new IllegalArgumentException(missing.size() == 1
                        ? "That deck can't be read: it doesn't exist or is private. Set it to Unlisted on Moxfield and try again."
                        : "None of those decks can be read: they don't exist or are private.");
            }
            fetchNow(false);
            compareAndReport();
            JsonOut o = new JsonOut(1024).beginObj().put("added", ids.size() - missing.size());
            o.beginArr("missing");
            for (String id : missing) o.val(id);
            return o.endArr().rawPut("state", stateJson()).endObj().toString();
        } finally {
            running.unlock();
        }
    }

    /** Stops following a deck that was added by link (Forge's copy stays). */
    public String removeExtra(String id) {
        synchronized (this) {
            extra.remove(id);
            hidden.add(id); // don't list Forge's copy as "no longer on Moxfield"
            links.remove(id);
            save();
            if (fetched != null) {
                List<MoxDeck> keep = new ArrayList<>(fetched);
                keep.removeIf(d -> d.extra && d.id.equals(id));
                fetched = keep;
            }
        }
        refreshReport();
        return stateJson();
    }

    /** Stops listing a Forge deck whose Moxfield deck is gone. */
    public String hide(String id) {
        synchronized (this) {
            hidden.add(id);
            links.remove(id);
            save();
        }
        refreshReport();
        return stateJson();
    }

    /**
     * Checks Moxfield and applies the safe changes (new decks, decks changed only on Moxfield) in the background,
     * when automatic sync is on and a check is due. The outcome is pushed to the app window.
     */
    public void autoSync(boolean startup) {
        synchronized (this) {
            if (!auto || user.isEmpty() || autoRunning || running.isLocked()) return;
            if (!startup && System.currentTimeMillis() - lastCheck < AUTO_EVERY_MS) return;
            autoRunning = true;
        }
        Thread t = new Thread(() -> {
            try {
                runAuto();
            } finally {
                synchronized (this) {
                    autoRunning = false;
                }
            }
        }, "Nova-Moxfield");
        t.setDaemon(true);
        t.start();
    }

    private void runAuto() {
        JsonOut o = new JsonOut(1024).beginObj().put("t", "moxfield").put("auto", true);
        try {
            running.lock();
            try {
                fetchNow(false);
                List<Result> results = write(null, true);
                compareAndReport();
                writeResults(o, results);
            } finally {
                running.unlock();
            }
        } catch (IOException | RuntimeException e) {
            o.put("error", message(e));
            synchronized (this) {
                autoError = "Automatic sync failed: " + message(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            o.put("pending", pending);
        }
        push.accept(o.endObj().toString());
    }

    // ================================================================== settings file

    private void setUser(String u) {
        String v = u == null ? "" : u.trim();
        // a pasted profile link works too
        Matcher m = Pattern.compile("moxfield\\.com/users/([^/?#\\s]+)").matcher(v);
        if (m.find()) v = m.group(1);
        if (v.startsWith("@")) v = v.substring(1);
        if (!v.equals(user)) {
            user = v;
            fetched = null;
            fetchedFor = null;
            report = null;
            pending = 0;
        }
    }

    private synchronized void load() {
        if (!stateFile.isFile()) return;
        try (BufferedReader r = Files.newBufferedReader(stateFile.toPath(), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            user = Objects.requireNonNullElse(MoxfieldClient.str(o, "user"), "");
            auto = !o.has("auto") || o.get("auto").getAsBoolean();
            lastCheck = o.has("lastCheck") ? o.get("lastCheck").getAsLong() : 0;
            if (o.has("extra")) for (JsonElement e : o.getAsJsonArray("extra")) extra.add(e.getAsString());
            if (o.has("hidden")) for (JsonElement e : o.getAsJsonArray("hidden")) hidden.add(e.getAsString());
            if (o.has("links")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("links").entrySet()) {
                    JsonObject l = e.getValue().getAsJsonObject();
                    Link k = new Link();
                    k.src = MoxfieldClient.str(l, "src");
                    k.name = MoxfieldClient.str(l, "name");
                    k.mname = MoxfieldClient.str(l, "mname");
                    k.fpMox = MoxfieldClient.str(l, "fpMox");
                    k.fpForge = MoxfieldClient.str(l, "fpForge");
                    k.at = l.has("at") ? l.get("at").getAsLong() : 0;
                    links.put(e.getKey(), k);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Nova] Moxfield sync settings unreadable (" + e + "); starting fresh");
        }
    }

    private synchronized void save() {
        JsonOut o = new JsonOut(4096).beginObj();
        o.put("v", 1).put("user", user).put("auto", auto).put("lastCheck", lastCheck);
        o.beginArr("extra");
        for (String id : extra) o.val(id);
        o.endArr().beginArr("hidden");
        for (String id : hidden) o.val(id);
        o.endArr().beginObj("links");
        for (Map.Entry<String, Link> e : new TreeMap<>(links).entrySet()) {
            Link k = e.getValue();
            o.beginObj(e.getKey()).put("src", k.src).put("name", k.name).put("mname", k.mname)
                    .put("fpMox", k.fpMox).put("fpForge", k.fpForge).put("at", k.at).endObj();
        }
        o.endObj().endObj();
        try {
            Files.createDirectories(stateFile.getParentFile().toPath());
            File tmp = new File(stateFile.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Nova] Could not save the Moxfield sync settings: " + e);
        }
    }

    // ================================================================== downloading

    /** A deck to download: listed on the user's profile, or added by link. */
    private record Want(String id, MoxfieldClient.Listed listed, MoxDeck cached) {
    }

    private void fetchNow(boolean force) throws IOException, InterruptedException {
        String u;
        List<String> extras;
        synchronized (this) {
            u = user;
            extras = new ArrayList<>(extra);
        }
        if (u.isEmpty() && extras.isEmpty()) {
            throw new IllegalArgumentException("Enter your Moxfield user name first.");
        }
        List<MoxDeck> decks = fetch(u, extras, force);
        synchronized (this) {
            if (!Objects.equals(user, u)) return; // the name changed meanwhile: this list is someone else's
            fetched = decks;
            fetchedFor = u;
            lastCheck = System.currentTimeMillis();
            autoError = null;
            save();
        }
    }

    private List<MoxDeck> fetch(String u, List<String> extras, boolean force) throws IOException, InterruptedException {
        progress(0, 0);
        List<MoxfieldClient.Listed> listed = u.isEmpty() ? List.of() : api.listDecks(u);
        if (!u.isEmpty() && listed.isEmpty() && !api.userExists(u)) {
            throw new IllegalArgumentException("Moxfield has no user named \"" + u + "\". Use the name from your profile link (moxfield.com/users/NAME).");
        }
        List<String> order = new ArrayList<>();
        Map<String, MoxDeck> got = new HashMap<>();
        List<Want> wants = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (MoxfieldClient.Listed l : listed) {
            order.add(l.id());
            MoxDeck cached = readCache(l.id());
            boolean fresh = !force && cached != null && Objects.equals(cached.listed, l.updated()) && !recent(l.updated(), now);
            if (fresh) {
                got.put(l.id(), cached);
            } else {
                wants.add(new Want(l.id(), l, cached));
            }
        }
        Set<String> listedIds = new HashSet<>(order);
        for (String id : extras) {
            if (listedIds.contains(id)) continue; // it's public now: the listing covers it
            order.add(id);
            wants.add(new Want(id, null, readCache(id)));
        }
        if (!wants.isEmpty()) {
            progress(0, wants.size());
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, wants.size()), r -> {
                Thread t = new Thread(r, "Nova-Moxfield-download");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<MoxDeck>> futures = new ArrayList<>();
                for (Want w : wants) futures.add(pool.submit(() -> download(w)));
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        MoxDeck d = futures.get(i).get();
                        if (d != null) got.put(wants.get(i).id(), d);
                    } catch (ExecutionException e) {
                        got.put(wants.get(i).id(), stub(wants.get(i), message(e.getCause())));
                    }
                    progress(i + 1, wants.size());
                }
            } finally {
                pool.shutdownNow();
            }
        }
        Set<String> extraSet = new HashSet<>(extras);
        List<MoxDeck> out = new ArrayList<>();
        for (String id : order) {
            MoxDeck d = got.get(id);
            if (d == null) continue;
            d.extra = extraSet.contains(id) && !listedIds.contains(id);
            out.add(d);
        }
        return out;
    }

    private static boolean recent(String iso, long now) {
        try {
            return iso != null && now - Instant.parse(iso).toEpochMilli() < RECENT_MS;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private MoxDeck download(Want w) throws InterruptedException {
        try {
            JsonObject j = api.deck(w.id());
            if (j == null) {
                // gone since the listing (or, for a deck added by link, deleted or made private)
                return w.listed() != null ? null : stub(w, "Not found on Moxfield: the deck was deleted or made private.");
            }
            MoxDeck d = parse(j);
            d.id = w.id();
            d.listed = w.listed() != null ? w.listed().updated() : d.updated;
            writeCache(d);
            return d;
        } catch (IOException e) {
            if (w.cached() != null) {
                w.cached().error = "Showing the copy from " + (w.cached().updated == null ? "an earlier check" : w.cached().updated.substring(0, 10))
                        + ": " + message(e);
                return w.cached();
            }
            return stub(w, message(e));
        }
    }

    /** A deck that couldn't be downloaded, shown with its error. */
    private static MoxDeck stub(Want w, String error) {
        MoxDeck d = w.cached() != null ? w.cached() : new MoxDeck();
        d.id = w.id();
        if (d.name == null) d.name = w.listed() != null && w.listed().name() != null ? w.listed().name() : w.id();
        if (d.format == null && w.listed() != null) d.format = w.listed().format();
        d.error = error;
        return d;
    }

    private void progress(int done, int total) {
        push.accept("{\"t\":\"moxProgress\",\"done\":" + done + ",\"total\":" + total + "}");
    }

    static String message(Throwable e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.isBlank() ? String.valueOf(e) : m;
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** The boards Forge needs from Moxfield's deck JSON (maybeboard and tokens are left out). */
    static MoxDeck parse(JsonObject j) {
        MoxDeck d = new MoxDeck();
        d.id = MoxfieldClient.str(j, "publicId");
        d.name = MoxfieldClient.str(j, "name");
        d.format = MoxfieldClient.str(j, "format");
        d.updated = MoxfieldClient.str(j, "lastUpdatedAtUtc");
        d.visibility = MoxfieldClient.str(j, "visibility");
        d.author = MoxfieldClient.str(obj(j, "createdByUser"), "userName");
        JsonObject boards = obj(j, "boards");
        for (String[] b : BOARDS) {
            JsonObject cards = obj(obj(boards, b[0]), "cards");
            if (cards == null) continue;
            List<Card> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : cards.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject entry = e.getValue().getAsJsonObject();
                JsonObject card = obj(entry, "card");
                String name = MoxfieldClient.str(card, "name");
                int qty;
                try {
                    qty = entry.get("quantity").getAsInt();
                } catch (RuntimeException ex) {
                    qty = 1;
                }
                if (name != null && qty > 0) {
                    list.add(new Card(name, nz(MoxfieldClient.str(card, "set")), nz(MoxfieldClient.str(card, "cn")), qty));
                }
            }
            list.sort((a, c) -> a.name().compareToIgnoreCase(c.name()));
            if (!list.isEmpty()) d.boards.put(b[1], list);
        }
        return d;
    }

    private File cacheFile(String id) {
        return DECK_ID.matcher(id).matches() ? new File(cacheDir, id + ".json") : null;
    }

    private void writeCache(MoxDeck d) {
        File f = cacheFile(d.id);
        if (f == null) return;
        JsonOut o = new JsonOut(16 * 1024).beginObj().put("v", 1).put("id", d.id).put("name", nz(d.name))
                .put("format", nz(d.format)).put("updated", nz(d.updated)).put("listed", nz(d.listed))
                .put("visibility", nz(d.visibility)).put("author", nz(d.author));
        o.beginObj("boards");
        for (Map.Entry<String, List<Card>> b : d.boards.entrySet()) {
            o.beginArr(b.getKey());
            for (Card c : b.getValue()) o.beginArr().val(c.name()).val(c.set()).val(c.cn()).val(c.qty()).endArr();
            o.endArr();
        }
        o.endObj().endObj();
        try {
            Files.createDirectories(cacheDir.toPath());
            Files.writeString(f.toPath(), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Nova] Moxfield cache: " + e);
        }
    }

    private MoxDeck readCache(String id) {
        File f = cacheFile(id);
        if (f == null || !f.isFile()) return null;
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            MoxDeck d = new MoxDeck();
            d.id = id;
            d.name = MoxfieldClient.str(o, "name");
            d.format = MoxfieldClient.str(o, "format");
            d.updated = emptyToNull(MoxfieldClient.str(o, "updated"));
            d.listed = emptyToNull(MoxfieldClient.str(o, "listed"));
            d.visibility = MoxfieldClient.str(o, "visibility");
            d.author = emptyToNull(MoxfieldClient.str(o, "author"));
            JsonObject boards = obj(o, "boards");
            if (boards != null) {
                for (Map.Entry<String, JsonElement> b : boards.entrySet()) {
                    List<Card> list = new ArrayList<>();
                    for (JsonElement e : b.getValue().getAsJsonArray()) {
                        JsonArray c = e.getAsJsonArray();
                        list.add(new Card(c.get(0).getAsString(), c.get(1).getAsString(), c.get(2).getAsString(), c.get(3).getAsInt()));
                    }
                    d.boards.put(b.getKey(), list);
                }
            }
            return d;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable: download it again
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ================================================================== Moxfield cards -> Forge cards

    /** Finds the Forge printing of a Moxfield card; remembers answers across the decks of one check. */
    private static final class Resolver {
        private final CardDb cards = StaticData.instance().getCommonCards();
        private final CardDb variants = StaticData.instance().getVariantCards();
        private final Map<String, CardEdition> bySetCode = new HashMap<>();
        private final Map<String, PaperCard> memo = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        Resolver() {
            // Moxfield uses Scryfall's set codes; Forge's own codes differ for some sets
            for (CardEdition ed : StaticData.instance().getEditions().getOrderedEditions()) {
                String sc = ed.getScryfallCode();
                if (sc != null && !sc.isEmpty()) bySetCode.putIfAbsent(sc.toLowerCase(Locale.ROOT), ed);
            }
        }

        private CardEdition edition(String set) {
            if (set == null || set.isEmpty()) return null;
            CardEdition ed = bySetCode.get(set.toLowerCase(Locale.ROOT));
            if (ed == null) ed = StaticData.instance().getCardEdition(set.toUpperCase(Locale.ROOT));
            return ed == null || ed == CardEdition.UNKNOWN ? null : ed;
        }

        PaperCard resolve(Card c) {
            String key = c.name() + '|' + c.set() + '|' + c.cn();
            PaperCard pc = memo.get(key);
            if (pc == null && !missing.contains(key)) {
                pc = find(c);
                if (pc == null) missing.add(key);
                else memo.put(key, pc);
            }
            return pc;
        }

        private PaperCard find(Card c) {
            for (String n : names(c.name())) {
                for (CardDb db : new CardDb[]{cards, variants}) {
                    if (db == null || db.getAllCards(n).isEmpty()) continue;
                    CardEdition ed = edition(c.set());
                    PaperCard pc = null;
                    if (ed != null) {
                        if (!c.cn().isEmpty()) pc = db.getCardFromSet(n, ed, -1, c.cn(), false);
                        if (pc == null) pc = db.getCardFromSet(n, ed, -1, null, false);
                    }
                    if (pc == null) pc = db.getCard(n);
                    if (pc != null) return pc;
                }
            }
            return null;
        }

        /**
         * Moxfield names double-faced, adventure and flip cards "Front // Back"; Forge knows them by the front
         * (split cards like "Fire // Ice" keep the full name in both).
         */
        private static List<String> names(String name) {
            List<String> out = new ArrayList<>(4);
            out.add(name);
            // Scryfall spells a few names with look-alike characters ("Ratonhnhaké꞉ton" has U+A789, Forge a colon)
            String plain = name.replace('꞉', ':').replace('’', '\'');
            if (!plain.equals(name)) out.add(plain);
            int split = plain.indexOf(" // ");
            if (split > 0) out.add(plain.substring(0, split).trim());
            if (plain.startsWith("A-")) out.add(plain.substring(2)); // an Alchemy rebalance: its paper original
            return out;
        }
    }

    /**
     * Moxfield's cards as a Forge deck, exactly as Forge reads it back from a deck file. Cards Forge doesn't know
     * go to {@code unknown}; commanders Forge can't use (the uncommon creature leading a Pauper Commander deck)
     * go to {@code demoted}: Forge keeps those in the main deck.
     */
    private static Deck toForge(MoxDeck m, Resolver res, List<String> unknown, List<String> demoted) {
        Deck d = new Deck(forgeName(m.name));
        String[][] map = {{"commander", "Commander"}, {"signature", "Commander"}, {"main", "Main"}, {"side", "Sideboard"},
                {"companion", "Sideboard"}, {"attractions", "Attractions"}, {"contraptions", "Contraptions"},
                {"planes", "Planes"}, {"schemes", "Schemes"}};
        for (String[] k : map) {
            List<Card> list = m.boards.get(k[0]);
            if (list == null || list.isEmpty()) continue;
            CardPool pool = d.getOrCreate(DeckSection.valueOf(k[1]));
            for (Card c : list) {
                PaperCard pc = res.resolve(c);
                if (pc == null) {
                    if (!unknown.contains(c.name())) unknown.add(c.name());
                } else {
                    pool.add(pc, c.qty());
                }
            }
        }
        Deck read = asForgeReadsIt(d);
        CardPool before = d.get(DeckSection.Commander), after = read.get(DeckSection.Commander);
        if (before != null) {
            for (Map.Entry<PaperCard, Integer> e : before) {
                if (after == null || after.countByName(e.getKey().getName()) == 0) demoted.add(e.getKey().getName());
            }
        }
        return read;
    }

    /**
     * A deck as Forge reads it from its file: loading moves cards a section can't hold to the one that can, so
     * comparing (and writing) this form keeps later checks from seeing changes nobody made.
     */
    private static Deck asForgeReadsIt(Deck d) {
        List<String> lines = new ArrayList<>();
        lines.add("[metadata]");
        lines.add("Name=" + d.getName());
        for (Map.Entry<DeckSection, CardPool> s : d) {
            if (s.getValue().isEmpty()) continue;
            lines.add("[" + s.getKey().name() + "]");
            for (Map.Entry<PaperCard, Integer> e : s.getValue()) {
                lines.add(e.getValue() + " " + CardDb.CardRequest.compose(e.getKey()));
            }
        }
        try {
            Deck back = DeckSerializer.fromSections(FileSection.parseSections(lines));
            if (back != null) {
                back.iterator(); // loads the sections now (Forge does it on first use)
                return back;
            }
        } catch (RuntimeException e) {
            System.err.println("[Nova] Moxfield sync: " + d.getName() + " can't be read back: " + e);
        }
        return d;
    }

    /**
     * A Forge deck name for a Moxfield deck name: '/' separates folders in Nova's deck paths, so partner decks
     * named "Tana // Kydele" become "Tana + Kydele".
     */
    static String forgeName(String moxName) {
        String n = moxName == null ? "" : moxName.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s*//\\s*", " + ")
                .replace('/', '-').replace('\\', '-').replaceAll(" {2,}", " ").trim();
        return n.isEmpty() ? "Moxfield deck" : n;
    }

    /** The Forge folder a new deck in this Moxfield format goes to. */
    static String folderFor(String format, boolean hasCommander) {
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (f) {
            case "brawl", "standardbrawl", "historicbrawl" -> "brawl";
            case "oathbreaker" -> "oathbreaker";
            case "tinyleaders" -> "tinyLeaders";
            case "commander", "duel", "duelcommander", "pauperedh", "paupercommander", "predh", "commanderprecons", "cedh" -> "commander";
            default -> hasCommander ? "commander" : "constructed";
        };
    }

    private static String norm(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Fingerprint of a deck's cards (every section, every printing). */
    static String fingerprint(Deck d) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<DeckSection, CardPool> s : d) {
            for (Map.Entry<PaperCard, Integer> e : s.getValue()) {
                lines.add(s.getKey().name() + '|' + e.getValue() + '|' + CardDb.CardRequest.compose(e.getKey()));
            }
        }
        Collections.sort(lines);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(lines.hashCode());
        }
    }

    // ================================================================== Forge's decks

    private static String idOf(String sourceUrl) {
        if (sourceUrl == null) return null;
        Matcher m = DECK_URL.matcher(sourceUrl);
        return m.find() ? m.group(1) : null;
    }

    static String deckUrl(String id) {
        return "https://moxfield.com/decks/" + id;
    }

    /** Every deck in Forge's deck folders (caller holds {@link DeckService#FILES}). */
    private static List<Local> scan() {
        List<Local> out = new ArrayList<>();
        for (String src : FOLDERS) {
            try {
                walk(DeckService.storage(src), src, "", out);
            } catch (RuntimeException e) {
                System.err.println("[Nova] Moxfield sync: can't read the " + src + " decks: " + e);
            }
        }
        return out;
    }

    private static void walk(IStorage<Deck> st, String src, String prefix, List<Local> out) {
        if (st == null) return;
        for (String name : st.getItemNames()) {
            Deck d = st.get(name);
            if (d != null) out.add(new Local(src, prefix, name, st, d, idOf(d.getSourceUrl())));
        }
        IStorage<IStorage<Deck>> folders = st.getFolders();
        if (folders != null) {
            for (String f : folders.getItemNames()) walk(folders.get(f), src, prefix + f + "/", out);
        }
    }

    // ================================================================== comparing

    private List<Item> compare(List<MoxDeck> decks, List<Local> locals, Resolver res) {
        Map<String, Link> linkCopy;
        synchronized (this) {
            linkCopy = new HashMap<>(links);
        }
        List<Item> items = new ArrayList<>();
        for (MoxDeck m : decks) {
            Item it = new Item();
            it.mox = m;
            boolean readable = m.error == null || !m.boards.isEmpty();
            if (readable) {
                it.deck = toForge(m, res, it.unknown, it.demoted);
                it.fpMox = fingerprint(it.deck);
            }
            items.add(it);
        }
        // Forge's copy of each Moxfield deck: the one carrying its link, then the one the last sync wrote, then
        // one of the same name (a deck copied over before Nova synced it)
        Set<Local> taken = new HashSet<>();
        for (Item it : items) {
            List<Local> tagged = locals.stream().filter(l -> it.mox.id.equals(l.moxId()) && !taken.contains(l)).toList();
            if (tagged.isEmpty()) continue;
            Link k = linkCopy.get(it.mox.id);
            Local pick = tagged.get(0);
            for (Local l : tagged) {
                if (k != null && l.src().equals(k.src) && l.path().equals(k.name)) pick = l;
            }
            it.local = pick;
            taken.add(pick);
        }
        for (Item it : items) {
            Link k = linkCopy.get(it.mox.id);
            if (it.local != null || k == null) continue;
            for (Local l : locals) {
                if (!taken.contains(l) && l.src().equals(k.src) && l.path().equals(k.name) && (l.moxId() == null || l.moxId().equals(it.mox.id))) {
                    it.local = l;
                    taken.add(l);
                    break;
                }
            }
        }
        for (Item it : items) {
            if (it.local != null) continue;
            // Forge itself turns '/' into '_' in deck names ("A // B" is "A __ B" there); Nova writes "A + B"
            Set<String> want = new HashSet<>(List.of(norm(forgeName(it.mox.name)), norm(nz(it.mox.name).replace('/', '_'))));
            String home = folderFor(it.mox.format, it.deck != null && it.deck.has(DeckSection.Commander) && !it.deck.get(DeckSection.Commander).isEmpty());
            Local best = null;
            for (Local l : locals) {
                if (taken.contains(l) || l.moxId() != null || !want.contains(norm(l.key()))) continue;
                if (best == null || (l.src().equals(home) && !best.src().equals(home))) best = l;
            }
            if (best != null) {
                it.local = best;
                taken.add(best);
            }
        }
        for (Item it : items) classify(it, linkCopy.get(it.mox.id));
        return items;
    }

    private static void classify(Item it, Link k) {
        MoxDeck m = it.mox;
        if (it.deck == null) {
            it.status = "error";
            return;
        }
        boolean hasCmd = it.deck.has(DeckSection.Commander) && !it.deck.get(DeckSection.Commander).isEmpty();
        if (it.local == null) {
            it.status = "new";
            it.targetSrc = folderFor(m.format, hasCmd);
            it.targetName = forgeName(m.name);
            diff(null, it.deck, it);
            return;
        }
        Local l = it.local;
        it.targetSrc = l.src();
        it.targetName = l.path();
        // renamed on Moxfield since the last sync: follow it
        String newName = forgeName(m.name);
        if (k != null && k.mname != null && !k.mname.equals(m.name) && !newName.equals(l.key())) {
            it.rename = newName;
        }
        boolean same = diff(l.deck(), it.deck, it);
        if (same && it.rename == null) {
            it.status = "same";
        } else if (k == null || k.fpForge == null) {
            it.status = same ? "changed" : "different"; // never synced by Nova: Forge's copy came from elsewhere
        } else {
            boolean editedHere = !k.fpForge.equals(fingerprint(l.deck()));
            boolean changedThere = !Objects.equals(k.fpMox, it.fpMox) || it.rename != null;
            it.status = !editedHere ? "changed" : !changedThere ? "edited" : "conflict";
        }
    }

    /**
     * Card differences from Forge's copy ({@code from}, null for a new deck) to Moxfield's; fills the item's
     * change list. Returns true when the cards are identical.
     */
    private static boolean diff(Deck from, Deck to, Item it) {
        List<DeckSection> secs = new ArrayList<>(List.of(ALWAYS));
        for (DeckSection s : EXTRA) {
            if (to.has(s) && !to.get(s).isEmpty()) secs.add(s);
        }
        boolean same = true;
        for (DeckSection s : secs) {
            CardPool a = from == null ? null : from.get(s);
            CardPool b = to.get(s);
            Map<String, Integer> ca = counts(a), cb = counts(b);
            Map<String, List<String>> pa = prints(a), pb = prints(b);
            JsonOut o = new JsonOut(512).beginObj().put("sec", secKey(s));
            int n = 0;
            o.beginArr("add");
            for (Map.Entry<String, Integer> e : cb.entrySet()) {
                if (!ca.containsKey(e.getKey())) {
                    o.beginArr().val(e.getKey()).val(e.getValue()).endArr();
                    it.added += e.getValue();
                    n++;
                }
            }
            o.endArr().beginArr("rem");
            for (Map.Entry<String, Integer> e : ca.entrySet()) {
                if (!cb.containsKey(e.getKey())) {
                    o.beginArr().val(e.getKey()).val(e.getValue()).endArr();
                    it.removed += e.getValue();
                    n++;
                }
            }
            o.endArr().beginArr("qty");
            for (Map.Entry<String, Integer> e : cb.entrySet()) {
                Integer was = ca.get(e.getKey());
                if (was != null && !was.equals(e.getValue())) {
                    o.beginArr().val(e.getKey()).val(was).val(e.getValue()).endArr();
                    if (e.getValue() > was) it.added += e.getValue() - was;
                    else it.removed += was - e.getValue();
                    n++;
                }
            }
            o.endArr().beginArr("print");
            for (Map.Entry<String, Integer> e : cb.entrySet()) {
                Integer was = ca.get(e.getKey());
                if (was != null && was.equals(e.getValue()) && !pa.get(e.getKey()).equals(pb.get(e.getKey()))) {
                    o.beginArr().val(e.getKey()).val(sets(a, e.getKey())).val(sets(b, e.getKey())).endArr();
                    it.printings++;
                    n++;
                }
            }
            o.endArr().endObj();
            if (n > 0) {
                same = false;
                if (from != null) it.diffJson.add(o.toString());
            }
        }
        return same;
    }

    private static String secKey(DeckSection s) {
        return switch (s) {
            case Main -> "main";
            case Sideboard -> "side";
            case Commander -> "commander";
            default -> s.name().toLowerCase(Locale.ROOT);
        };
    }

    private static Map<String, Integer> counts(CardPool p) {
        Map<String, Integer> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (p != null) {
            for (Map.Entry<PaperCard, Integer> e : p) m.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        return m;
    }

    private static Map<String, List<String>> prints(CardPool p) {
        Map<String, List<String>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (p != null) {
            for (Map.Entry<PaperCard, Integer> e : p) {
                m.computeIfAbsent(e.getKey().getName(), k -> new ArrayList<>()).add(e.getValue() + "x" + CardDb.CardRequest.compose(e.getKey()));
            }
        }
        for (List<String> l : m.values()) Collections.sort(l);
        return m;
    }

    /** "C21" or "C21 263, SLD 1011": the printings of a card in a pool. */
    private static String sets(CardPool p, String name) {
        Set<String> out = new LinkedHashSet<>();
        if (p != null) {
            for (Map.Entry<PaperCard, Integer> e : p) {
                PaperCard pc = e.getKey();
                if (!pc.getName().equalsIgnoreCase(name)) continue;
                String cn = pc.getCollectorNumber();
                out.add(pc.getEdition() + (cn == null || cn.isEmpty() || "N.A.".equals(cn) ? "" : " " + cn));
            }
        }
        return String.join(", ", out);
    }

    // ================================================================== writing

    /**
     * Writes Moxfield decks into Forge's folders: {@code ids}, or (for an automatic sync, ids == null) every
     * deck that is new or changed only on Moxfield.
     */
    private List<Result> write(Set<String> ids, boolean safeOnly) {
        List<MoxDeck> decks;
        synchronized (this) {
            decks = fetched == null ? List.of() : new ArrayList<>(fetched);
        }
        List<Result> results = new ArrayList<>();
        Resolver res = new Resolver();
        synchronized (DeckService.FILES) {
            List<Item> items = compare(decks, scan(), res);
            for (Item it : items) {
                if (it.deck == null || "same".equals(it.status)) continue;
                boolean wanted = ids != null ? ids.contains(it.mox.id)
                        : safeOnly && ("new".equals(it.status) || "changed".equals(it.status));
                if (!wanted) continue;
                try {
                    results.add(writeDeck(it));
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    results.add(new Result(it.mox.id, forgeName(it.mox.name), it.targetSrc, it.targetName, it.local == null, message(e)));
                }
            }
        }
        synchronized (this) {
            save();
        }
        return results;
    }

    private Result writeDeck(Item it) {
        final String id = it.mox.id;
        final IStorage<Deck> folder;
        final String prefix;
        final String oldLeaf;
        final String leaf;
        final Deck d;
        if (it.local == null) {
            folder = DeckService.storage(it.targetSrc);
            prefix = "";
            oldLeaf = null;
            leaf = uniqueName(folder, it.targetName, null);
            d = new Deck(it.deck, leaf);
        } else {
            Local l = it.local;
            folder = l.folder();
            prefix = l.prefix();
            oldLeaf = l.key();
            leaf = it.rename != null ? uniqueName(folder, it.rename, oldLeaf) : oldLeaf;
            // keeps what Moxfield doesn't know about: notes, tags, AI hints, sleeves, planes and schemes...
            d = new Deck(l.deck(), leaf);
            for (DeckSection s : ALWAYS) d.putSection(s, copy(it.deck.get(s)));
            for (DeckSection s : EXTRA) {
                if (it.deck.has(s) && !it.deck.get(s).isEmpty()) d.putSection(s, copy(it.deck.get(s)));
            }
        }
        d.setSourceUrl(deckUrl(id));
        // a rename that only changes letter case must drop the old file first: Windows sees one file
        boolean caseOnly = oldLeaf != null && !oldLeaf.equals(leaf) && oldLeaf.equalsIgnoreCase(leaf);
        if (caseOnly) folder.delete(oldLeaf);
        folder.add(d);
        if (oldLeaf != null && !oldLeaf.equals(leaf) && !caseOnly) folder.delete(oldLeaf);
        File dir = new File(folder.getFullPath());
        File written = new File(dir, d.getBestFileName() + ".dck");
        removeLegacyFiles(dir, oldLeaf == null ? List.of(leaf) : List.of(leaf, oldLeaf), written);

        Link k = new Link();
        k.src = it.local == null ? it.targetSrc : it.local.src();
        k.name = prefix + leaf;
        k.mname = it.mox.name;
        k.fpMox = it.fpMox;
        k.fpForge = fingerprintOfFile(written, d);
        k.at = System.currentTimeMillis();
        synchronized (this) {
            links.put(id, k);
            hidden.remove(id);
        }
        return new Result(id, leaf, k.src, k.name, it.local == null, null);
    }

    private static CardPool copy(CardPool p) {
        return p == null ? new CardPool() : new CardPool(p);
    }

    /** The fingerprint of a deck as Forge reads it back from its file (what later checks compare with). */
    private static String fingerprintOfFile(File f, Deck fallback) {
        try {
            Deck back = f.isFile() ? DeckSerializer.fromFile(f) : null;
            return fingerprint(back != null ? back : fallback);
        } catch (RuntimeException e) {
            return fingerprint(fallback);
        }
    }

    /** {@code want}, or "want (2)"... when another deck of the folder has that name or file name. */
    private static String uniqueName(IStorage<Deck> folder, String want, String self) {
        Set<String> names = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (String n : folder.getItemNames()) {
            if (n.equals(self)) continue;
            names.add(n.toLowerCase(Locale.ROOT));
            files.add(new Deck(n).getBestFileName().toLowerCase(Locale.ROOT));
        }
        String n = want;
        for (int i = 2; names.contains(n.toLowerCase(Locale.ROOT)) || files.contains(new Deck(n).getBestFileName().toLowerCase(Locale.ROOT)); i++) {
            n = want + " (" + i + ")";
        }
        return n;
    }

    /**
     * The older Moxfield sync script named deck files differently from Forge ("A_ B.dck" for the deck "A: B",
     * where Forge writes "A B.dck"). Once Forge's own file is written, such a leftover would load as a second deck.
     */
    private static void removeLegacyFiles(File dir, List<String> names, File keep) {
        for (String name : names) {
            String legacy = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + ".dck";
            if (legacy.equalsIgnoreCase(keep.getName())) continue;
            File f = new File(dir, legacy);
            String inFile = f.isFile() ? deckNameIn(f) : null;
            // Forge reads a '/' in a deck name as '_'
            if (inFile != null && name.equals(inFile.replace('/', '_')) && !f.delete()) {
                System.err.println("[Nova] Could not remove the old deck file " + f);
            }
        }
    }

    /** The deck name in a .dck file's [metadata] section. */
    private static String deckNameIn(File f) {
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line;
            boolean meta = false;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    meta = t.equalsIgnoreCase("[metadata]");
                } else if (meta && t.regionMatches(true, 0, "Name=", 0, 5)) {
                    return t.substring(5).trim();
                }
            }
        } catch (IOException | RuntimeException e) {
            // not a deck we can read: leave it alone
        }
        return null;
    }

    // ================================================================== reports

    private void refreshReport() {
        running.lock();
        try {
            compareAndReport();
        } finally {
            running.unlock();
        }
    }

    /** Compares the last download with Forge's decks and keeps the report. */
    private void compareAndReport() {
        List<MoxDeck> decks;
        String u;
        synchronized (this) {
            if (fetched == null) {
                report = null;
                pending = 0;
                return;
            }
            decks = new ArrayList<>(fetched);
            u = user;
        }
        Resolver res = new Resolver();
        JsonOut o = new JsonOut(64 * 1024).beginObj();
        int waiting = 0;
        synchronized (DeckService.FILES) {
            List<Local> locals = scan();
            List<Item> items = compare(decks, locals, res);
            rememberSame(items);
            o.put("user", u).put("checked", System.currentTimeMillis());
            o.beginArr("decks");
            Set<String> ids = new HashSet<>();
            for (Item it : items) {
                ids.add(it.mox.id);
                writeItem(o, it, u);
                if (Set.of("new", "changed", "different", "conflict").contains(it.status)) waiting++;
            }
            o.endArr();
            // Forge decks that came from Moxfield decks which aren't there anymore
            Set<String> hide;
            synchronized (this) {
                hide = new HashSet<>(hidden);
            }
            o.beginArr("gone");
            if (!u.isEmpty()) {
                for (Local l : locals) {
                    if (l.moxId() == null || ids.contains(l.moxId()) || hide.contains(l.moxId())) continue;
                    o.beginObj().put("id", l.moxId()).put("src", l.src()).put("name", l.path()).put("url", deckUrl(l.moxId())).endObj();
                }
            }
            o.endArr();
        }
        String json = o.endObj().toString();
        synchronized (this) {
            report = json;
            pending = waiting;
            save();
        }
    }

    /** Decks that are identical on both sides count as synced from now on (no file is written). */
    private void rememberSame(List<Item> items) {
        synchronized (this) {
            for (Item it : items) {
                if (!"same".equals(it.status) || it.local == null) continue;
                Link k = links.get(it.mox.id);
                String fpForge = fingerprint(it.local.deck());
                if (k != null && it.local.src().equals(k.src) && it.local.path().equals(k.name) && fpForge.equals(k.fpForge)
                        && Objects.equals(k.fpMox, it.fpMox) && Objects.equals(k.mname, it.mox.name)) {
                    continue;
                }
                if (k == null) {
                    k = new Link();
                    k.at = System.currentTimeMillis();
                    links.put(it.mox.id, k);
                }
                k.src = it.local.src();
                k.name = it.local.path();
                k.mname = it.mox.name;
                k.fpMox = it.fpMox;
                k.fpForge = fpForge;
            }
        }
    }

    private static void writeItem(JsonOut o, Item it, String user) {
        MoxDeck m = it.mox;
        o.beginObj().put("id", m.id).put("name", nz(m.name)).put("format", nz(m.format)).put("url", deckUrl(m.id));
        o.putOpt("updated", m.updated).putOpt("vis", m.visibility).put("status", it.status).flag("extra", m.extra);
        if (m.author != null && !m.author.equalsIgnoreCase(user)) o.put("author", m.author);
        o.putOpt("error", m.error);
        if (it.local != null) o.beginObj("forge").put("src", it.local.src()).put("name", it.local.path()).endObj();
        if (it.targetSrc != null) o.beginObj("target").put("src", it.targetSrc).put("name", it.targetName).endObj();
        o.putOpt("rename", it.rename);
        if (it.deck != null) {
            o.rawPut("info", LobbyService.deckInfo(it.deck));
            int main = it.deck.getMain() == null ? 0 : it.deck.getMain().countAll();
            CardPool cmd = it.deck.get(DeckSection.Commander);
            o.put("cards", main + (cmd == null ? 0 : cmd.countAll()));
        }
        o.putNz("add", it.added).putNz("rem", it.removed).putNz("print", it.printings);
        o.beginArr("diff");
        for (String s : it.diffJson) o.raw(s);
        o.endArr();
        if (!it.unknown.isEmpty()) {
            o.beginArr("unknown");
            for (String u : it.unknown) o.val(u);
            o.endArr();
        }
        if (!it.demoted.isEmpty()) {
            o.beginArr("demoted");
            for (String u : it.demoted) o.val(u);
            o.endArr();
        }
        o.endObj();
    }

    private static void writeResults(JsonOut o, List<Result> results) {
        o.beginArr("results");
        for (Result r : results) {
            o.beginObj().put("id", r.id()).put("name", r.name()).put("src", r.src()).put("path", r.path())
                    .flag("created", r.created()).putOpt("error", r.error()).endObj();
        }
        o.endArr();
    }
}

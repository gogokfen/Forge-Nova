package forge.nova;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ICardFace;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.game.GameFormat;
import forge.gamemodes.quest.QuestController;
import forge.item.PaperCard;
import forge.item.PreconDeck;
import forge.model.FModel;
import forge.nova.sync.CardJson;
import forge.nova.util.JsonOut;
import forge.util.storage.IStorage;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The deck builder's backend: the card catalog for the browser, deck files in the same folders as
 * classic Forge (so both programs share them), and Forge's own deck rules for validation.
 */
public final class DeckService {
    /** formats whose legality is reported per card; their order defines the catalog's legality bits */
    static final String[] FORMATS = {"Standard", "Pioneer", "Modern", "Legacy", "Vintage", "Pauper", "Premodern",
            "Commander", "Brawl", "Oathbreaker", "Tiny Leaders"};
    /** of those, the ones checked with Forge's GameFormat card lists (the rest are deck formats) */
    private static final int GAME_FORMATS = 7;

    private volatile byte[] catalog;

    // ------------------------------------------------------------------ catalog

    /** Every card a deck can contain, one entry per card name. Built on first use (a few seconds). */
    public byte[] catalog() {
        byte[] c = catalog;
        if (c == null) {
            synchronized (this) {
                c = catalog;
                if (c == null) {
                    long t0 = System.currentTimeMillis();
                    c = buildCatalog().getBytes(StandardCharsets.UTF_8);
                    catalog = c;
                    System.out.println("[Nova] Card catalog: " + (c.length / 1024) + " KB in " + (System.currentTimeMillis() - t0) + " ms");
                }
            }
        }
        return c;
    }

    private static List<Predicate<PaperCard>> legalityTests() {
        List<Predicate<PaperCard>> tests = new ArrayList<>();
        for (int i = 0; i < GAME_FORMATS; i++) {
            GameFormat gf = FModel.getFormats().getFormat(FORMATS[i]);
            tests.add(gf == null ? pc -> false : gf.getFilterRules());
        }
        tests.add(DeckFormat.Commander::isLegalCard);
        tests.add(DeckFormat.Brawl::isLegalCard);
        tests.add(DeckFormat.Oathbreaker::isLegalCard);
        tests.add(DeckFormat.TinyLeaders::isLegalCard);
        return tests;
    }

    /** Same bits as the client's TF constants (plus a few deck-building ones). */
    static int typeFlags(CardType t) {
        int f = 0;
        if (t.isCreature()) f |= 1;
        if (t.isLand()) f |= 2;
        if (t.isPlaneswalker()) f |= 4;
        if (t.isArtifact()) f |= 8;
        if (t.isEnchantment()) f |= 16;
        if (t.isInstant()) f |= 32;
        if (t.isSorcery()) f |= 64;
        if (t.isBattle()) f |= 128;
        if (t.isBasicLand()) f |= 256;
        if (t.isLegendary()) f |= 1024;
        if (t.isSnow()) f |= 2048;
        if (t.isKindred()) f |= 4096;
        return f;
    }

    private static String pt(ICardFace f) {
        if (f == null || f.getPower() == null || f.getPower().isEmpty() || f.getToughness() == null) {
            return "";
        }
        return f.getPower() + "/" + f.getToughness();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private String buildCatalog() {
        final StaticData data = FModel.getMagicDb();
        final CardDb db = data.getCommonCards();
        final List<Predicate<PaperCard>> legal = legalityTests();
        final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
        final Map<String, Integer> setIndex = new LinkedHashMap<>();

        List<PaperCard> unique = new ArrayList<>(db.getUniqueCards());
        unique.sort(Comparator.comparing(PaperCard::getName, String.CASE_INSENSITIVE_ORDER));

        JsonOut cards = new JsonOut(16 * 1024 * 1024);
        cards.beginArr();
        for (PaperCard any : unique) {
            final CardRules r = any.getRules();
            if (r == null || r.isVariant() || r.isUnsupported()) {
                continue;
            }
            final String name = r.getName();
            PaperCard def = db.getCard(name);
            if (def == null) {
                def = any;
            }
            // printings (for set filters and the printing picker)
            Set<Integer> sets = new LinkedHashSet<>();
            for (PaperCard p : db.getAllCards(name)) {
                sets.add(setIndex.computeIfAbsent(p.getEdition(), k -> setIndex.size()));
            }
            int lg = 0;
            for (int i = 0; i < legal.size(); i++) {
                try {
                    if (legal.get(i).test(def)) lg |= 1 << i;
                } catch (RuntimeException ignored) {
                    // a format that cannot judge this card treats it as not legal
                }
            }
            int cmd = 0;
            if (DeckFormat.Commander.isLegalCommander(r)) cmd |= 1;
            if (r.canBePartnerCommander()) cmd |= 2;
            if (r.canBeBackground()) cmd |= 4;
            if (DeckFormat.Brawl.isLegalCommander(r)) cmd |= 8;
            if (DeckFormat.Oathbreaker.isLegalCommander(r)) cmd |= 16;
            if (r.canBeSignatureSpell()) cmd |= 32;
            if (DeckFormat.TinyLeaders.isLegalCommander(r)) cmd |= 64;
            int ai = (r.getAiHints() != null && r.getAiHints().getRemAIDecks() ? 1 : 0)
                    | (r.getAiHints() != null && r.getAiHints().getRemRandomDecks() ? 2 : 0);

            ICardFace main = r.getMainPart();
            ICardFace other = r.getOtherPart();
            cards.beginArr();
            cards.val(name);
            cards.val(CardJson.cost(r.getManaCost()));
            cards.val(r.getManaCost() == null ? 0 : r.getManaCost().getCMC());
            cards.val(r.getColor() == null ? 0 : r.getColor().getColor());
            cards.val(r.getColorIdentity() == null ? 0 : r.getColorIdentity().getColor());
            cards.val(r.getType().toString());
            cards.val(typeFlags(r.getType()));
            cards.val(nz(r.getOracleText()));
            cards.val(pt(main));
            cards.val(main == null ? "" : nz(main.getInitialLoyalty()) + (main.getDefense() == null || main.getDefense().isEmpty() ? "" : "D" + main.getDefense()));
            if (other != null && r.getName().indexOf("//") < 0) {
                // back face / adventure / flip side: [name, type, text, cost, P/T]
                cards.beginArr().val(other.getName()).val(other.getType() == null ? "" : other.getType().toString())
                        .val(nz(other.getOracleText())).val(CardJson.cost(other.getManaCost())).val(pt(other)).endArr();
            } else {
                cards.nul();
            }
            cards.val(def.getRarity() == null ? "?" : def.getRarity().toString());
            cards.val(def.getEdition());
            cards.val(nz(def.getImageKey(false)));
            cards.beginArr();
            for (Integer s : sets) cards.val(s);
            cards.endArr();
            cards.val(lg).val(cmd).val(ai);
            cards.endArr();
        }
        cards.endArr();

        JsonOut o = new JsonOut(cards.length() + 128 * 1024);
        o.beginObj().put("v", 1);
        o.beginArr("formats");
        for (String f : FORMATS) o.val(f);
        o.endArr();
        o.beginArr("sets");
        for (String code : setIndex.keySet()) {
            CardEdition ed = data.getCardEdition(code);
            Date date = ed == null ? null : ed.getDate();
            o.beginArr().val(code).val(ed == null ? code : ed.getName()).val(date == null ? "" : day.format(date))
                    .val(ed == null || ed.getType() == null ? "" : ed.getType().name()).endArr();
        }
        o.endArr();
        o.beginArr("fields");
        for (String f : new String[]{"n", "mc", "cmc", "c", "ci", "t", "tf", "o", "pt", "loy", "b", "r", "s", "img", "ss", "lg", "cmd", "ai"}) o.val(f);
        o.endArr();
        o.rawPut("cards", cards.toString());
        o.endObj();
        return o.toString();
    }

    /** All printings of a card, newest first. */
    public String printings(String name) {
        final StaticData data = FModel.getMagicDb();
        final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
        List<PaperCard> all = new ArrayList<>(data.getCommonCards().getAllCards(name));
        all.sort(Comparator.comparing((PaperCard p) -> {
            CardEdition ed = data.getCardEdition(p.getEdition());
            return ed == null || ed.getDate() == null ? new Date(0) : ed.getDate();
        }).reversed().thenComparingInt(PaperCard::getArtIndex));
        JsonOut o = new JsonOut(4096).beginArr();
        for (PaperCard p : all) {
            CardEdition ed = data.getCardEdition(p.getEdition());
            o.beginObj().put("set", p.getEdition()).put("setName", ed == null ? p.getEdition() : ed.getName())
                    .put("date", ed == null || ed.getDate() == null ? "" : day.format(ed.getDate()))
                    .put("art", p.getArtIndex()).putOpt("cn", p.getCollectorNumber())
                    .put("r", p.getRarity() == null ? "?" : p.getRarity().toString())
                    .putOpt("img", p.getImageKey(false)).endObj();
        }
        o.endArr();
        return o.toString();
    }

    // ------------------------------------------------------------------ deck files

    /**
     * Guards Forge's user deck storages: the deck builder, the lobby's lists and the Moxfield sync (which may
     * write in the background) use them from different threads.
     */
    public static final Object FILES = new Object();

    /** User deck folders by the lobby's format ids. */
    public static IStorage<Deck> storage(String src) {
        return switch (src == null ? "" : src) {
            case "constructed" -> FModel.getDecks().getConstructed();
            case "commander" -> FModel.getDecks().getCommander();
            case "brawl" -> FModel.getDecks().getBrawl();
            case "oathbreaker" -> FModel.getDecks().getOathbreaker();
            case "tinyLeaders" -> FModel.getDecks().getTinyLeaders();
            default -> throw new IllegalArgumentException("Unknown deck folder: " + src);
        };
    }

    static DeckFormat deckFormat(String src) {
        return switch (src == null ? "" : src) {
            case "commander" -> DeckFormat.Commander;
            case "brawl" -> DeckFormat.Brawl;
            case "oathbreaker" -> DeckFormat.Oathbreaker;
            case "tinyLeaders" -> DeckFormat.TinyLeaders;
            default -> DeckFormat.Constructed;
        };
    }

    /** "folder/sub/name" -> the storage of the folder part. */
    private static IStorage<Deck> folderOf(IStorage<Deck> root, String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return root;
        }
        IStorage<Deck> folder = root.tryGetFolder(path.substring(0, slash));
        if (folder == null) {
            throw new IllegalArgumentException("Deck folder not found: " + path.substring(0, slash));
        }
        return folder;
    }

    private static String leaf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static Deck findDeck(String src, String path) {
        if ("cmdPrecon".equals(src)) {
            return folderOf(FModel.getDecks().getCommanderPrecons(), path).get(leaf(path));
        }
        if ("precon".equals(src)) {
            PreconDeck pd = QuestController.getPrecons().get(path);
            return pd == null ? null : pd.getDeck();
        }
        return folderOf(storage(src), path).get(leaf(path));
    }

    public String loadDeck(String src, String path) {
        Deck d;
        synchronized (FILES) {
            d = path == null ? null : findDeck(src, path);
        }
        if (d == null) {
            throw new IllegalArgumentException("Deck not found: " + path);
        }
        JsonOut o = new JsonOut(8192).beginObj();
        o.put("src", src).put("name", path).putOpt("comment", d.getComment()).putOpt("source", d.getSourceUrl());
        o.beginObj("sections");
        writeSection(o, "commander", d.get(DeckSection.Commander));
        writeSection(o, "main", d.get(DeckSection.Main));
        writeSection(o, "side", d.get(DeckSection.Sideboard));
        o.endObj();
        o.endObj();
        return o.toString();
    }

    private static void writeSection(JsonOut o, String key, CardPool pool) {
        o.beginArr(key);
        if (pool != null) {
            for (Map.Entry<PaperCard, Integer> e : pool) {
                PaperCard pc = e.getKey();
                o.beginArr().val(pc.getName()).val(pc.getEdition()).val(pc.getArtIndex()).val(e.getValue())
                        .val(pc.getImageKey(false)).endArr();
            }
        }
        o.endArr();
    }

    static PaperCard resolve(String name, String set, int art) {
        CardDb db = FModel.getMagicDb().getCommonCards();
        PaperCard pc = null;
        if (set != null && !set.isEmpty()) {
            pc = art > 0 ? db.getCard(name, set, art) : null;
            if (pc == null) pc = db.getCard(name, set);
        }
        if (pc == null) pc = db.getCard(name);
        return pc;
    }

    /** Fills Main / Sideboard / Commander from the client's {main:[[name,set,art,count],...], ...}. */
    private static void fillSections(Deck deck, JsonObject sections, List<String> unknown) {
        String[][] keys = {{"main", "Main"}, {"side", "Sideboard"}, {"commander", "Commander"}};
        for (String[] k : keys) {
            JsonElement arr = sections == null ? null : sections.get(k[0]);
            if (arr == null || !arr.isJsonArray()) continue;
            CardPool pool = deck.getOrCreate(DeckSection.valueOf(k[1]));
            for (JsonElement el : arr.getAsJsonArray()) {
                JsonArray e = el.getAsJsonArray();
                String name = e.get(0).getAsString();
                String set = e.size() > 1 && !e.get(1).isJsonNull() ? e.get(1).getAsString() : "";
                int art = e.size() > 2 && !e.get(2).isJsonNull() ? e.get(2).getAsInt() : 0;
                int count = e.size() > 3 ? e.get(3).getAsInt() : 1;
                PaperCard pc = resolve(name, set, art);
                if (pc == null) {
                    unknown.add(name);
                } else if (count > 0) {
                    pool.add(pc, count);
                }
            }
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Saves a deck file; renaming or changing the format moves the file. Returns null on a name conflict. */
    public String saveDeck(JsonObject req) {
        synchronized (FILES) {
            return saveDeckLocked(req);
        }
    }

    private String saveDeckLocked(JsonObject req) {
        final String src = str(req, "src");
        final String path = str(req, "name") == null ? "" : str(req, "name").trim();
        final String oldSrc = str(req, "oldSrc");
        final String oldPath = str(req, "oldName");
        final boolean overwrite = req.has("overwrite") && req.get("overwrite").getAsBoolean();
        final String name = leaf(path);
        if (name.isBlank()) {
            throw new IllegalArgumentException("The deck needs a name.");
        }
        if (name.matches(".*[\\\\:*?\"<>|].*")) {
            throw new IllegalArgumentException("Deck names can't contain \\ : * ? \" < > |");
        }
        final IStorage<Deck> folder = folderOf(storage(src), path);
        final boolean sameDeck = src.equals(oldSrc) && path.equals(oldPath);
        if (!sameDeck && folder.contains(name) && !overwrite) {
            return null;
        }
        List<String> unknown = new ArrayList<>();
        Deck deck = new Deck(name);
        fillSections(deck, req.getAsJsonObject("sections"), unknown);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown cards: " + String.join(", ", unknown));
        }
        String comment = str(req, "comment");
        deck.setComment(comment == null || comment.isBlank() ? null : comment);
        // keep sections this editor doesn't show (planes, schemes, avatars...)
        Deck previous = null;
        if (oldSrc != null && oldPath != null) {
            try {
                previous = findDeck(oldSrc, oldPath);
            } catch (IllegalArgumentException ignored) {
                // the old deck is gone; nothing to carry over
            }
        }
        if (previous != null) {
            for (Map.Entry<DeckSection, CardPool> s : previous) {
                DeckSection sec = s.getKey();
                if (sec != DeckSection.Main && sec != DeckSection.Sideboard && sec != DeckSection.Commander && !s.getValue().isEmpty()) {
                    deck.putSection(sec, new CardPool(s.getValue()));
                }
            }
            // and Forge's metadata the builder doesn't edit (Source URL holds a synced deck's Moxfield link)
            deck.setSourceUrl(previous.getSourceUrl());
            deck.getTags().addAll(previous.getTags());
            if (!previous.getAiHints().isEmpty()) {
                deck.setAiHints(String.join(" | ", previous.getAiHints()));
            }
            deck.setSleeveArtKey(previous.getSleeveArtKey());
            deck.setSleeveArtOffset(previous.getSleeveArtOffset());
        }
        boolean moving = previous != null && !sameDeck && isUserFolder(oldSrc);
        // A rename that only changes letter case must drop the old file first: Windows sees one file.
        if (moving && src.equals(oldSrc) && folderOf(storage(oldSrc), oldPath) == folder && leaf(oldPath).equalsIgnoreCase(name)) {
            folder.delete(leaf(oldPath));
            moving = false;
        }
        folder.add(deck);
        if (moving) {
            folderOf(storage(oldSrc), oldPath).delete(leaf(oldPath));
        }
        return new JsonOut(128).beginObj().put("ok", true).put("src", src).put("name", path).endObj().toString();
    }

    private static boolean isUserFolder(String src) {
        return switch (src == null ? "" : src) {
            case "constructed", "commander", "brawl", "oathbreaker", "tinyLeaders" -> true;
            default -> false;
        };
    }

    public void deleteDeck(String src, String path) {
        synchronized (FILES) {
            IStorage<Deck> folder = folderOf(storage(src), path);
            if (!folder.contains(leaf(path))) {
                throw new IllegalArgumentException("Deck not found: " + path);
            }
            folder.delete(leaf(path));
        }
    }

    /** Forge's verdict on a deck: the first rules problem (or none) and the formats it is legal in. */
    public String checkDeck(JsonObject req) {
        final String src = str(req, "src");
        List<String> unknown = new ArrayList<>();
        Deck deck = new Deck("check");
        fillSections(deck, req.getAsJsonObject("sections"), unknown);
        JsonOut o = new JsonOut(512).beginObj();
        String problem;
        try {
            problem = deckFormat(src).getDeckConformanceProblem(deck);
        } catch (RuntimeException e) {
            problem = "could not be checked (" + e.getMessage() + ")";
        }
        o.putOpt("problem", problem);
        o.beginArr("legal");
        for (int i = 0; i < GAME_FORMATS; i++) {
            GameFormat gf = FModel.getFormats().getFormat(FORMATS[i]);
            try {
                if (gf != null && !deck.getMain().isEmpty() && gf.isDeckLegal(deck)) o.val(FORMATS[i]);
            } catch (RuntimeException ignored) {
                // not legal as far as we can tell
            }
        }
        o.endArr();
        o.beginArr("unknown");
        for (String u : unknown) o.val(u);
        o.endArr();
        o.endObj();
        return o.toString();
    }
}

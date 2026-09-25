package forge.nova;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.ai.AiProfileUtil;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.quest.QuestController;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.item.PreconDeck;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.nova.online.OnlineRoom;
import forge.nova.util.JsonOut;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.util.MyRandom;
import forge.util.storage.IStorage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Deck browsing and match setup for the browser lobby. Uses the same deck folders,
 * preferences and AI profiles as classic Forge.
 */
public final class LobbyService {
    private String staticDecksJson; // precons don't change while running

    // ------------------------------------------------------------------ deck listing

    private static int colorMask(Deck d) {
        int mask = 0;
        List<PaperCard> cmdrs = d.getCommanders();
        if (cmdrs != null && !cmdrs.isEmpty()) {
            for (PaperCard pc : cmdrs) {
                mask |= pc.getRules().getColorIdentity().getColor();
            }
            return mask;
        }
        CardPool main = d.getMain();
        if (main != null) {
            for (Map.Entry<PaperCard, Integer> e : main) {
                PaperCard pc = e.getKey();
                if (!pc.getRules().getType().isLand()) {
                    mask |= pc.getRules().getColor().getColor();
                }
            }
        }
        return mask;
    }

    private static String colorString(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & 1) != 0) sb.append('W');
        if ((mask & 2) != 0) sb.append('U');
        if ((mask & 4) != 0) sb.append('B');
        if ((mask & 8) != 0) sb.append('R');
        if ((mask & 16) != 0) sb.append('G');
        return sb.toString();
    }

    private static void writeDeck(JsonOut o, String src, String id, Deck d) {
        o.beginObj();
        o.put("src", src);
        o.put("name", id);
        try {
            o.put("colors", colorString(colorMask(d)));
            List<PaperCard> cmdrs = d.getCommanders();
            if (cmdrs != null && !cmdrs.isEmpty()) {
                o.beginArr("cmdrs");
                for (PaperCard pc : cmdrs) {
                    o.beginObj().put("n", pc.getName()).put("img", pc.getImageKey(false)).endObj();
                }
                o.endArr();
            }
            CardPool main = d.getMain();
            o.put("count", main == null ? 0 : main.countAll());
            // A representative non-land card for decks without a commander.
            if ((cmdrs == null || cmdrs.isEmpty()) && main != null) {
                PaperCard best = null;
                for (Map.Entry<PaperCard, Integer> e : main) {
                    PaperCard pc = e.getKey();
                    if (pc.getRules().getType().isLand()) continue;
                    if (best == null || pc.getRules().getManaCost().getCMC() > best.getRules().getManaCost().getCMC()) {
                        best = pc;
                    }
                }
                if (best != null) {
                    o.put("art", best.getImageKey(false));
                }
            }
        } catch (Exception e) {
            // deck metadata is best-effort
        }
        o.endObj();
    }

    private static void writeStorage(JsonOut o, String src, IStorage<Deck> storage, String prefix) {
        if (storage == null) return;
        for (String name : storage.getItemNames()) {
            Deck d = storage.get(name);
            if (d != null) {
                writeDeck(o, src, prefix + name, d);
            }
        }
        IStorage<IStorage<Deck>> folders = storage.getFolders();
        if (folders != null) {
            for (String f : folders.getItemNames()) {
                writeStorage(o, src, folders.get(f), prefix + f + "/");
            }
        }
    }

    private static Deck findInStorage(IStorage<Deck> storage, String name) {
        if (storage == null || name == null) return null;
        int slash = name.indexOf('/');
        if (slash > 0) {
            IStorage<IStorage<Deck>> folders = storage.getFolders();
            IStorage<Deck> sub = folders == null ? null : folders.get(name.substring(0, slash));
            return findInStorage(sub, name.substring(slash + 1));
        }
        return storage.get(name);
    }

    /** User decks are re-read every time (they may be edited in classic Forge meanwhile). */
    public synchronized String listDecks() {
        JsonOut o = new JsonOut(64 * 1024);
        o.beginObj();
        o.beginArr("user");
        writeStorage(o, "constructed", FModel.getDecks().getConstructed(), "");
        writeStorage(o, "commander", FModel.getDecks().getCommander(), "");
        writeStorage(o, "brawl", FModel.getDecks().getBrawl(), "");
        writeStorage(o, "oathbreaker", FModel.getDecks().getOathbreaker(), "");
        writeStorage(o, "tinyLeaders", FModel.getDecks().getTinyLeaders(), "");
        o.endArr();
        if (staticDecksJson == null) {
            JsonOut s = new JsonOut(256 * 1024);
            s.beginArr();
            writeStorage(s, "cmdPrecon", FModel.getDecks().getCommanderPrecons(), "");
            try {
                IStorage<PreconDeck> precons = QuestController.getPrecons();
                for (String name : precons.getItemNames()) {
                    PreconDeck pd = precons.get(name);
                    if (pd != null && pd.getDeck() != null) {
                        writeDeck(s, "precon", name, pd.getDeck());
                    }
                }
            } catch (Exception e) {
                System.err.println("[Nova] precon decks unavailable: " + e);
            }
            s.endArr();
            staticDecksJson = s.toString();
        }
        o.rawPut("builtin", staticDecksJson);
        o.beginArr("aiProfiles");
        for (String p : AiProfileUtil.getProfilesDisplayList()) {
            o.val(p);
        }
        o.endArr();
        o.put("playerName", FModel.getPreferences().getPref(ForgePreferences.FPref.PLAYER_NAME));
        o.endObj();
        return o.toString();
    }

    /** Labels of the generated deck choices (same as the lobby's). */
    private static final Map<String, String> GENERATED = Map.of(
            "randomCommander", "Random generated commander deck",
            "randomCommanderPrecon", "Random commander precon",
            "randomUser", "Random pick from the host's decks",
            "randomColors", "Random 2-color deck",
            "theme", "Random theme deck",
            "randomPrecon", "Random preconstructed deck");

    /**
     * Summary of a deck choice ({src, name}) as the lobby lists it: colors, commanders, card count.
     * Generated decks only get a label. Null when the deck doesn't exist.
     */
    public static String deckInfo(JsonObject spec) {
        String src = spec.has("src") ? spec.get("src").getAsString() : "gen";
        String name = spec.has("name") ? spec.get("name").getAsString() : "";
        if ("gen".equals(src)) {
            String label = GENERATED.get(name);
            return label == null ? null : new JsonOut(128).beginObj().put("src", "gen").put("name", name).put("label", label).endObj().toString();
        }
        Deck d = findDeck(src, name);
        if (d == null) {
            return null;
        }
        JsonOut o = new JsonOut(512);
        writeDeck(o, src, name, d);
        return o.toString();
    }

    /** Summary of an imported deck. */
    public static String deckInfo(Deck d) {
        JsonOut o = new JsonOut(512);
        writeDeck(o, "import", d.getName(), d);
        return o.toString();
    }

    /** A deck of the host's lists (not copied); null for generated or unknown decks. */
    private static Deck findDeck(String src, String name) {
        return switch (src) {
            case "constructed" -> findInStorage(FModel.getDecks().getConstructed(), name);
            case "commander" -> findInStorage(FModel.getDecks().getCommander(), name);
            case "brawl" -> findInStorage(FModel.getDecks().getBrawl(), name);
            case "oathbreaker" -> findInStorage(FModel.getDecks().getOathbreaker(), name);
            case "tinyLeaders" -> findInStorage(FModel.getDecks().getTinyLeaders(), name);
            case "cmdPrecon" -> findInStorage(FModel.getDecks().getCommanderPrecons(), name);
            case "precon" -> {
                PreconDeck pd = QuestController.getPrecons().get(name);
                yield pd == null ? null : pd.getDeck();
            }
            default -> null;
        };
    }

    /** The deck list for a friend's deck picker: precons always, the host's own decks only when shared. */
    public synchronized String guestDeckList(boolean withUserDecks) {
        String all = listDecks();
        if (withUserDecks) {
            return all;
        }
        JsonObject o = com.google.gson.JsonParser.parseString(all).getAsJsonObject();
        o.add("user", new JsonArray());
        return o.toString();
    }

    // ------------------------------------------------------------------ deck resolution

    private static Deck resolveDeck(JsonObject spec, boolean commanderFormat) {
        String src = spec.has("src") ? spec.get("src").getAsString() : "gen";
        String name = spec.has("name") ? spec.get("name").getAsString() : "";
        Deck d = switch (src) {
            case "constructed" -> findInStorage(FModel.getDecks().getConstructed(), name);
            case "commander" -> findInStorage(FModel.getDecks().getCommander(), name);
            case "brawl" -> findInStorage(FModel.getDecks().getBrawl(), name);
            case "oathbreaker" -> findInStorage(FModel.getDecks().getOathbreaker(), name);
            case "tinyLeaders" -> findInStorage(FModel.getDecks().getTinyLeaders(), name);
            case "cmdPrecon" -> findInStorage(FModel.getDecks().getCommanderPrecons(), name);
            case "precon" -> {
                PreconDeck pd = QuestController.getPrecons().get(name);
                yield pd == null ? null : pd.getDeck();
            }
            default -> generate(name, commanderFormat);
        };
        return d == null ? null : new Deck(d); // copy: the engine mutates decks during sideboarding
    }

    private static Deck generate(String kind, boolean commanderFormat) {
        switch (kind) {
            case "randomCommanderPrecon":
                return DeckgenUtil.getRandomCommanderPreconDeck();
            case "randomCommander":
                return DeckgenUtil.getCommanderDeck();
            case "theme":
                return DeckgenUtil.getRandomThemeDeck();
            case "randomPrecon":
                return DeckgenUtil.getRandomPreconDeck();
            case "randomUser": {
                List<Deck> all = new ArrayList<>();
                IStorage<Deck> st = commanderFormat ? FModel.getDecks().getCommander() : FModel.getDecks().getConstructed();
                for (String n : st.getItemNames()) all.add(st.get(n));
                if (!all.isEmpty()) {
                    return all.get(MyRandom.getRandom().nextInt(all.size()));
                }
                return commanderFormat ? DeckgenUtil.getCommanderDeck() : DeckgenUtil.getRandomColorDeck(true);
            }
            case "randomColors":
            default:
                return commanderFormat ? DeckgenUtil.getCommanderDeck() : DeckgenUtil.getRandomColorDeck(true);
        }
    }

    // ------------------------------------------------------------------ match setup

    /** Mirrors HostedMatch.getDefaultRules (private there). */
    private static GameRules defaultRules(GameType gameType, int gamesPerMatch) {
        ForgePreferences prefs = FModel.getPreferences();
        GameRules r = new GameRules(gameType);
        r.setPlayForAnte(prefs.getPrefBoolean(ForgePreferences.FPref.UI_ANTE));
        r.setMatchAnteRarity(prefs.getPrefBoolean(ForgePreferences.FPref.UI_ANTE_MATCH_RARITY));
        r.setAnteIncludeBasicLands(prefs.getPrefBoolean(ForgePreferences.FPref.UI_ANTE_INCLUDE_BASIC_LANDS));
        r.setManaBurn(prefs.getPrefBoolean(ForgePreferences.FPref.LEGACY_MANABURN));
        r.setOrderCombatants(prefs.getPrefBoolean(ForgePreferences.FPref.LEGACY_ORDER_COMBATANTS));
        r.setUseGrayText(prefs.getPrefBoolean(ForgePreferences.FPref.UI_GRAY_INACTIVE_TEXT));
        r.setGamesPerMatch(gamesPerMatch > 0 ? gamesPerMatch : prefs.getPrefInt(ForgePreferences.FPref.UI_MATCHES_PER_GAME));
        r.setAllowCheatShuffle(prefs.getPrefBoolean(ForgePreferences.FPref.UI_ENABLE_AI_CHEATS));
        switch (AiProfileUtil.getAISideboardingMode()) {
            case Off -> {
                r.setAISideboardingEnabled(false);
                r.setSideboardForAI(false);
            }
            case AI -> {
                r.setAISideboardingEnabled(true);
                r.setSideboardForAI(false);
            }
            case HumanForAI -> {
                r.setAISideboardingEnabled(true);
                r.setSideboardForAI(true);
            }
        }
        return r;
    }

    public record MatchSetup(GameRules rules, Set<GameType> variants, List<RegisteredPlayer> players,
                             Map<RegisteredPlayer, IGuiGame> guis) {
    }

    /**
     * Builds the players of a match. {@code gui} is attached to the human player (if any).
     *
     * @throws IllegalArgumentException with a user-facing message when a deck cannot be found
     */
    public MatchSetup buildMatch(JsonObject req, IGuiGame gui) {
        String format = req.has("format") ? req.get("format").getAsString() : "constructed";
        Set<GameType> variants = EnumSet.noneOf(GameType.class);
        switch (format) {
            case "commander" -> variants.add(GameType.Commander);
            case "brawl" -> variants.add(GameType.Brawl);
            case "oathbreaker" -> variants.add(GameType.Oathbreaker);
            case "tinyLeaders" -> variants.add(GameType.TinyLeaders);
            default -> { }
        }
        boolean commanderFormat = !variants.isEmpty();
        int games = req.has("games") ? req.get("games").getAsInt() : 1;
        // same for everyone; without it each player gets the format's default (Forge's own rules)
        int life = req.has("life") && !req.get("life").isJsonNull() ? req.get("life").getAsInt() : 0;
        if (req.has("life") && (life < 1 || life > 9999)) {
            throw new IllegalArgumentException("Starting life must be between 1 and 9999.");
        }
        JsonArray slots = req.getAsJsonArray("players");
        if (slots == null || slots.size() < 2) {
            throw new IllegalArgumentException("A match needs at least two players.");
        }
        if (slots.size() > 8) {
            throw new IllegalArgumentException("Forge supports at most 8 players.");
        }
        String[] avatarPrefs = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_AVATARS).split(",");
        int avatarCount = GuiBase.getInterface().getAvatarCount();

        List<RegisteredPlayer> players = new ArrayList<>();
        Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        boolean humanSeen = false;
        int idx = 0;
        for (JsonElement el : slots) {
            JsonObject slot = el.getAsJsonObject();
            boolean human = "human".equals(slot.get("type").getAsString()) && !humanSeen;
            Deck deck = resolveDeck(slot.getAsJsonObject("deck"), commanderFormat);
            if (deck == null) {
                throw new IllegalArgumentException("Deck not found for player " + (idx + 1) + ".");
            }
            if (commanderFormat && (deck.getCommanders() == null || deck.getCommanders().isEmpty())) {
                throw new IllegalArgumentException("\"" + deck.getName() + "\" has no commander, so it can't be used in " + format + ".");
            }
            RegisteredPlayer rp = commanderFormat
                    ? RegisteredPlayer.forVariants(slots.size(), variants, deck, null, false, null, null)
                    : new RegisteredPlayer(deck);
            rp.setTeamNumber(idx);
            if (life > 0) {
                rp.setStartingLife(life);
            }
            LobbyPlayer lp;
            if (human) {
                humanSeen = true;
                lp = GamePlayerUtil.getGuiPlayer();
            } else {
                String name = slot.has("name") && !slot.get("name").getAsString().isBlank()
                        ? slot.get("name").getAsString()
                        : aiName(deck, idx);
                String profile = slot.has("profile") ? slot.get("profile").getAsString() : "";
                int avatar;
                try {
                    avatar = idx < avatarPrefs.length ? Integer.parseInt(avatarPrefs[idx].trim()) : MyRandom.getRandom().nextInt(avatarCount);
                } catch (NumberFormatException e) {
                    avatar = MyRandom.getRandom().nextInt(avatarCount);
                }
                lp = GamePlayerUtil.createAiPlayer(name, avatar, MyRandom.getRandom().nextInt(Math.max(1, GuiBase.getInterface().getSleevesCount())), null, profile);
            }
            rp.setPlayer(lp);
            players.add(rp);
            if (human && gui != null) {
                guis.put(rp, gui);
            }
            idx++;
        }
        return new MatchSetup(defaultRules(GameType.Constructed, games), variants, players, guis);
    }

    private static Set<GameType> variantsOf(String format) {
        Set<GameType> variants = EnumSet.noneOf(GameType.class);
        switch (format == null ? "" : format) {
            case "commander" -> variants.add(GameType.Commander);
            case "brawl" -> variants.add(GameType.Brawl);
            case "oathbreaker" -> variants.add(GameType.Oathbreaker);
            case "tinyLeaders" -> variants.add(GameType.TinyLeaders);
            default -> { }
        }
        return variants;
    }

    /** The host's own player: Forge's GUI player (named after the Forge preference), or "Host". */
    private static LobbyPlayer hostPlayer() {
        String pn = FModel.getPreferences().getPref(ForgePreferences.FPref.PLAYER_NAME);
        return pn == null || pn.isBlank() ? new LobbyPlayerHuman("Host") : GamePlayerUtil.getGuiPlayer();
    }

    /**
     * Builds an online match from the room's seats: the host and each friend get their own GUI
     * ({@code guiFor}), AI seats are set up like the lobby's.
     *
     * @throws IllegalArgumentException with a user-facing message when a deck is unusable
     */
    public MatchSetup buildOnlineMatch(String format, int games, Integer life, List<OnlineRoom.SeatPlan> plans,
                                       Function<OnlineRoom.SeatPlan, IGuiGame> guiFor) {
        Set<GameType> variants = variantsOf(format);
        boolean commanderFormat = !variants.isEmpty();
        if (plans.size() < 2 || plans.size() > 8) {
            throw new IllegalArgumentException("A match needs 2 to 8 players.");
        }
        List<RegisteredPlayer> players = new ArrayList<>();
        Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        int idx = 0;
        for (OnlineRoom.SeatPlan sp : plans) {
            Deck deck = sp.deck() != null ? new Deck(sp.deck()) : sp.spec() == null ? null : resolveDeck(sp.spec(), commanderFormat);
            String who = sp.kind() == OnlineRoom.Kind.AI ? "AI player " + (idx + 1) : sp.name();
            if (deck == null) {
                throw new IllegalArgumentException("Deck not found for " + who + ".");
            }
            if (commanderFormat && (deck.getCommanders() == null || deck.getCommanders().isEmpty())) {
                throw new IllegalArgumentException(who + "'s deck \"" + deck.getName() + "\" has no commander.");
            }
            RegisteredPlayer rp = commanderFormat
                    ? RegisteredPlayer.forVariants(plans.size(), variants, deck, null, false, null, null)
                    : new RegisteredPlayer(deck);
            rp.setTeamNumber(idx);
            if (life != null && life > 0) {
                rp.setStartingLife(life);
            }
            LobbyPlayer lp = switch (sp.kind()) {
                case HOST -> hostPlayer();
                case FRIEND -> new LobbyPlayerHuman(sp.name(), sp.avatar(), sp.sleeve());
                case AI -> GamePlayerUtil.createAiPlayer(sp.name() == null || sp.name().isBlank() ? aiName(deck, idx) : sp.name(),
                        MyRandom.getRandom().nextInt(Math.max(1, GuiBase.getInterface().getAvatarCount())),
                        MyRandom.getRandom().nextInt(Math.max(1, GuiBase.getInterface().getSleevesCount())), null,
                        sp.profile() == null ? "" : sp.profile());
            };
            rp.setPlayer(lp);
            players.add(rp);
            if (sp.kind() != OnlineRoom.Kind.AI) {
                guis.put(rp, guiFor.apply(sp));
            }
            idx++;
        }
        return new MatchSetup(defaultRules(GameType.Constructed, games), variants, players, guis);
    }

    private static String aiName(Deck deck, int idx) {
        List<PaperCard> cmdrs = deck.getCommanders();
        String base = cmdrs != null && !cmdrs.isEmpty() ? cmdrs.get(0).getName() : deck.getName();
        if (base == null || base.isBlank()) {
            base = "AI";
        }
        int comma = base.indexOf(',');
        if (comma > 0) {
            base = base.substring(0, comma); // "Atraxa, Praetors' Voice" -> "Atraxa"
        }
        if (base.length() > 22) {
            base = base.substring(0, 22).trim();
        }
        return base + " (AI " + idx + ")";
    }
}

package forge.nova.sync;

import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.nova.net.ClientLink;
import forge.nova.util.JsonOut;
import forge.util.collect.FCollectionView;

import com.google.common.collect.Multiset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Streams the game state to the browser as per-object diffs.
 *
 * Every flush re-serialises all visible objects (cheap: a few ms even with hundreds of
 * permanents) and compares each object's JSON with what the client already has; only
 * changed objects are sent. Engine notifications merely mark the state dirty, and flushes
 * are coalesced, so an AI doing 1000 things per second costs at most ~50 flushes per second.
 */
public final class StateSync {
    /** zones whose contents are synced card-by-card */
    private static final ZoneType[] SYNCED_ZONES = {
            ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
            ZoneType.Ante, ZoneType.PlanarDeck, ZoneType.SchemeDeck, ZoneType.Junkyard
    };
    /** hidden zones: a count, plus the cards the local player may look at right now */
    private static final ZoneType[] COUNTED_ZONES = {
            ZoneType.Library, ZoneType.Sideboard, ZoneType.AttractionDeck, ZoneType.ContraptionDeck
    };
    private static final long FLUSH_DELAY_MS = 20;

    public interface ViewPolicy {
        Collection<PlayerView> localPlayers();

        boolean mayView(CardView c);

        boolean mayFlip(CardView c);

        /** Lets the GUI add fields to a player's JSON (e.g. phase stops, custom avatar). */
        void writeExtraPlayerInfo(PlayerView p, JsonOut out);
    }

    private final ClientLink link;
    private final ViewPolicy policy;
    private volatile GameView game;

    private final Map<Integer, String> sentCards = new HashMap<>();
    private final Map<Integer, String> sentPlayers = new HashMap<>();
    private String sentGame = "";
    private String sentStack = "";
    private String sentCombat = "";
    private int sentLog = 0;
    private boolean needFull = true;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Nova-Sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    private final JsonOut cardBuf = new JsonOut(1024);

    // --- statistics (for the dev/benchmark API)
    private volatile long flushCount;
    private volatile long lastFlushNanos;
    private volatile long totalFlushNanos;
    private volatile int lastCardCount;
    private volatile int lastMessageBytes;
    private volatile long totalBytes;

    /** Snapshot of synchronisation statistics as JSON. */
    public String statsJson() {
        return new JsonOut(256).beginObj()
                .put("flushes", flushCount)
                .put("lastFlushMs", lastFlushNanos / 1e6)
                .put("avgFlushMs", flushCount == 0 ? 0 : totalFlushNanos / 1e6 / flushCount)
                .put("cards", lastCardCount)
                .put("lastMessageBytes", lastMessageBytes)
                .put("totalBytes", totalBytes)
                .endObj().toString();
    }

    public StateSync(ClientLink link, ViewPolicy policy) {
        this.link = link;
        this.policy = policy;
    }

    public void setGame(GameView gv) {
        if (scheduler.isShutdown()) {
            return;
        }
        this.game = gv;
        invalidate();
    }

    public GameView getGame() {
        return game;
    }

    /** Forget what the client has; next flush sends everything (new client or new game). */
    public synchronized void invalidate() {
        needFull = true;
        sentCards.clear();
        sentPlayers.clear();
        sentGame = "";
        sentStack = "";
        sentCombat = "";
        sentLog = 0;
    }

    /** Coalesced asynchronous flush. */
    public void requestFlush() {
        if (scheduler.isShutdown()) {
            return; // match over: late engine notifications are ignored
        }
        if (scheduled.compareAndSet(false, true)) {
            try {
                scheduler.schedule(() -> {
                    scheduled.set(false);
                    try {
                        flush();
                    } catch (Throwable t) {
                        // Views are mutated concurrently by the game thread; just try again shortly.
                        requestFlush();
                    }
                }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shutDownMeanwhile) {
                scheduled.set(false);
            }
        }
    }

    /** Synchronous flush (before prompts/dialogs, so the client sees the state they refer to). */
    public void flushNow() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                flush();
                return;
            } catch (Throwable t) {
                // concurrent modification from the game thread - retry
            }
        }
        requestFlush();
    }

    private static <T> List<T> snapshot(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        if (it == null) {
            return out;
        }
        if (it instanceof Collection<?> c) {
            Object[] arr = c.toArray();
            for (Object o : arr) {
                if (o != null) {
                    @SuppressWarnings("unchecked") T t = (T) o;
                    out.add(t);
                }
            }
            return out;
        }
        for (T t : it) {
            if (t != null) out.add(t);
        }
        return out;
    }

    private synchronized void flush() {
        final GameView gv = game;
        if (gv == null || !link.isConnected()) {
            return;
        }
        final long t0 = System.nanoTime();
        final boolean full = needFull;
        final Collection<PlayerView> locals = policy.localPlayers();
        final boolean spectating = locals.isEmpty();
        final Iterable<PlayerView> viewers = spectating ? null : locals;
        final List<PlayerView> players = snapshot(gv.getPlayers());

        // ---- collect cards to sync
        final Map<Integer, CardView> cards = new LinkedHashMap<>();
        final Map<Integer, String> playerJson = new LinkedHashMap<>();
        final JsonOut po = new JsonOut(1024);
        for (PlayerView p : players) {
            po.reset();
            po.beginObj();
            po.put("id", p.getId());
            po.put("n", p.getName());
            po.flag("ai", p.isAI());
            po.put("life", p.getLife());
            po.flag("lost", p.getHasLost());
            po.flag("pri", p.getHasPriority());
            po.flag("xt", p.getIsExtraTurn());
            po.put("av", p.getAvatarIndex());
            po.put("sl", p.getSleeveIndex());
            po.putOpt("avk", p.getAvatarCardImageKey());
            po.flag("local", locals.contains(p));
            Multiset<CounterType> counters = p.getCounters();
            if (counters != null && !counters.isEmpty()) {
                po.beginObj("cnt");
                for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                    po.put(e.getElement().getName(), e.getCount());
                }
                po.endObj();
            }
            // mana pool W U B R G C
            po.beginArr("mana");
            po.val(p.getMana((byte) 1)).val(p.getMana((byte) 2)).val(p.getMana((byte) 4))
              .val(p.getMana((byte) 8)).val(p.getMana((byte) 16)).val(p.getMana((byte) 0));
            po.endArr();
            po.put("maxHand", p.hasUnlimitedHandSize() ? -1 : p.getMaxHandSize());
            po.put("lands", p.getNumLandThisTurn());

            po.beginObj("z");
            for (ZoneType zt : SYNCED_ZONES) {
                List<CardView> zc = snapshot(p.getCards(zt));
                if (zc.isEmpty()) continue;
                po.beginArr(zt.name());
                int hidden = 0;
                for (CardView c : zc) {
                    boolean publicZone = zt != ZoneType.Hand;
                    if (publicZone || policy.mayView(c)) {
                        po.val(c.getId());
                        cards.put(c.getId(), c);
                    } else {
                        hidden++;
                    }
                }
                po.endArr();
                if (hidden > 0) {
                    po.put(zt.name() + "#", hidden);
                }
            }
            int libTop = -1;
            for (ZoneType zt : COUNTED_ZONES) {
                List<CardView> zc = snapshot(p.getCards(zt));
                if (zc.isEmpty()) continue;
                // Visible cards are the top card under "play with the top card revealed" effects, or the
                // whole library while the engine lets the player search it. Spectators may view every
                // card, so they get counts only (the board never shows a library's contents).
                int hidden = zc.size();
                if (!spectating) {
                    boolean listed = false;
                    for (int i = 0; i < zc.size(); i++) {
                        CardView c = zc.get(i);
                        if (!policy.mayView(c)) continue;
                        if (!listed) {
                            po.beginArr(zt.name());
                            listed = true;
                        }
                        po.val(c.getId());
                        cards.put(c.getId(), c);
                        hidden--;
                        if (i == 0 && zt == ZoneType.Library) libTop = c.getId();
                    }
                    if (listed) po.endArr();
                }
                if (hidden > 0) {
                    po.put(zt.name() + "#", hidden);
                }
            }
            po.endObj();
            if (libTop >= 0) {
                po.put("libTop", libTop);
            }

            // commander damage received from each commander in the game
            boolean any = false;
            for (PlayerView other : players) {
                for (CardView cmdr : snapshot(other.getCommanders())) {
                    int dmg = p.getCommanderDamage(cmdr);
                    if (dmg > 0) {
                        if (!any) {
                            po.beginObj("cmdDmg");
                            any = true;
                        }
                        po.put(String.valueOf(cmdr.getId()), dmg);
                    }
                }
            }
            if (any) po.endObj();
            List<CardView> cmdrs = snapshot(p.getCommanders());
            if (!cmdrs.isEmpty()) {
                po.beginArr("cmdrs");
                for (CardView c : cmdrs) {
                    po.val(c.getId());
                }
                po.endArr();
            }
            policy.writeExtraPlayerInfo(p, po);
            po.putOpt("det", safeDetails(p));
            po.endObj();
            playerJson.put(p.getId(), po.toString());
        }

        // stack source cards and revealed cards are part of the board too
        final List<StackItemView> stack = snapshot(gv.getStack());
        for (StackItemView si : stack) {
            CardView src = si.getSourceCard();
            if (src != null && !cards.containsKey(src.getId())) {
                cards.put(src.getId(), src);
            }
        }
        final List<CardView> revealed = snapshot(gv.getRevealedCollection());
        for (CardView c : revealed) {
            cards.putIfAbsent(c.getId(), c);
        }
        final CombatView combat = gv.getCombat();

        // ---- build message
        final JsonOut out = new JsonOut(16 * 1024);
        out.beginObj().put("t", "state").put("full", full);
        boolean changed = full;

        String gameJson = gameJson(gv, players, revealed);
        if (!gameJson.equals(sentGame)) {
            out.rawPut("game", gameJson);
            sentGame = gameJson;
            changed = true;
        }

        // players
        boolean opened = false;
        for (Map.Entry<Integer, String> e : playerJson.entrySet()) {
            if (!e.getValue().equals(sentPlayers.get(e.getKey()))) {
                if (!opened) {
                    out.beginArr("players");
                    opened = true;
                }
                out.raw(e.getValue());
                sentPlayers.put(e.getKey(), e.getValue());
            }
        }
        if (opened) {
            out.endArr();
            changed = true;
        }

        // cards
        opened = false;
        for (CardView c : cards.values()) {
            cardBuf.reset();
            boolean canView = policy.mayView(c);
            CardJson.write(cardBuf, c, viewers, canView, canView && policy.mayFlip(c));
            String js = cardBuf.toString();
            if (!js.equals(sentCards.get(c.getId()))) {
                if (!opened) {
                    out.beginArr("cards");
                    opened = true;
                }
                out.raw(js);
                sentCards.put(c.getId(), js);
            }
        }
        if (opened) {
            out.endArr();
            changed = true;
        }
        // removed cards (moved to hidden zones, ceased to exist...)
        Set<Integer> gone = new HashSet<>(sentCards.keySet());
        gone.removeAll(cards.keySet());
        if (!gone.isEmpty()) {
            out.beginArr("gone");
            for (Integer id : gone) {
                out.val(id);
                sentCards.remove(id);
            }
            out.endArr();
            changed = true;
        }

        String stackJson = stackJson(stack);
        if (!stackJson.equals(sentStack)) {
            out.rawPut("stack", stackJson);
            sentStack = stackJson;
            changed = true;
        }
        String combatJson = combatJson(combat);
        if (!combatJson.equals(sentCombat)) {
            out.rawPut("combat", combatJson);
            sentCombat = combatJson;
            changed = true;
        }

        // log (append-only)
        if (gv.getGameLog() != null) {
            List<GameLogEntry> all = gv.getGameLog().getAllEntries();
            if (all.size() > sentLog) {
                int from = full ? Math.max(0, all.size() - 400) : sentLog;
                out.beginArr("log");
                for (int i = from; i < all.size(); i++) {
                    GameLogEntry le = all.get(i);
                    out.beginObj().put("ty", le.type().name()).put("m", le.message()).endObj();
                }
                out.endArr();
                sentLog = all.size();
                changed = true;
            }
        }
        out.endObj();

        needFull = false;
        lastCardCount = cards.size();
        if (changed) {
            String msg = out.toString();
            link.send(msg);
            lastMessageBytes = msg.length();
            totalBytes += msg.length();
        }
        long dt = System.nanoTime() - t0;
        lastFlushNanos = dt;
        totalFlushNanos += dt;
        flushCount++;
    }

    private static String safeDetails(PlayerView p) {
        try {
            return p.getDetails();
        } catch (Exception e) {
            return "";
        }
    }

    private String gameJson(GameView gv, List<PlayerView> players, List<CardView> revealed) {
        JsonOut o = new JsonOut(256);
        o.beginObj();
        o.put("id", gv.getId());
        o.put("turn", gv.getTurn());
        o.putOpt("phase", gv.getPhase() == null ? null : gv.getPhase().name());
        PlayerView ap = gv.getPlayerTurn();
        if (ap != null) o.put("ap", ap.getId());
        for (PlayerView p : players) {
            if (p.getHasPriority()) {
                o.put("pp", p.getId());
                break;
            }
        }
        o.putOpt("title", gv.getTitle());
        o.putOpt("type", gv.getGameType() == null ? null : gv.getGameType().name());
        o.flag("cmdr", gv.isCommander());
        o.putNz("storm", gv.getStormCount());
        o.flag("over", gv.isGameOver());
        o.flag("mOver", gv.isMatchOver());
        o.flag("mull", gv.isMulligan());
        o.put("poisonToLose", gv.getPoisonCountersToLose());
        o.put("gamesInMatch", gv.getNumGamesInMatch());
        o.put("gamesPlayed", gv.getNumPlayedGamesInMatch());
        if (gv.isGameOver()) {
            o.putOpt("winner", gv.getWinningPlayerName());
        }
        if (!revealed.isEmpty()) {
            o.beginArr("rev");
            for (CardView c : revealed) {
                o.val(c.getId());
            }
            o.endArr();
        }
        String deps = gv.getDependencies();
        o.putOpt("deps", deps);
        o.endObj();
        return o.toString();
    }

    private static String stackJson(List<StackItemView> stack) {
        JsonOut o = new JsonOut(256);
        o.beginArr();
        for (StackItemView si : stack) {
            o.beginObj();
            o.put("id", si.getId());
            CardView src = si.getSourceCard();
            if (src != null) o.put("src", src.getId());
            o.putOpt("txt", si.getText());
            PlayerView act = si.getActivatingPlayer();
            if (act != null) o.put("act", act.getId());
            o.flag("ab", si.isAbility());
            o.flag("tr", si.isTrigger());
            o.flag("opt", si.isOptionalTrigger());
            List<Integer> tc = new ArrayList<>();
            List<Integer> tp = new ArrayList<>();
            for (StackItemView cur = si; cur != null; cur = cur.getSubInstance()) {
                FCollectionView<CardView> cs = cur.getTargetCards();
                if (cs != null) for (CardView c : snapshot(cs)) tc.add(c.getId());
                FCollectionView<PlayerView> ps = cur.getTargetPlayers();
                if (ps != null) for (PlayerView p : snapshot(ps)) tp.add(p.getId());
            }
            if (!tc.isEmpty()) {
                o.beginArr("tc");
                for (Integer i : tc) o.val(i);
                o.endArr();
            }
            if (!tp.isEmpty()) {
                o.beginArr("tp");
                for (Integer i : tp) o.val(i);
                o.endArr();
            }
            o.putOpt("key", si.getKey());
            o.endObj();
        }
        o.endArr();
        return o.toString();
    }

    private static String combatJson(CombatView combat) {
        JsonOut o = new JsonOut(128);
        o.beginArr();
        if (combat != null) {
            for (CardView a : snapshot(combat.getAttackers())) {
                o.beginObj();
                o.put("a", a.getId());
                GameEntityView def = combat.getDefender(a);
                if (def instanceof PlayerView dp) {
                    o.put("dp", dp.getId());
                } else if (def instanceof CardView dc) {
                    o.put("dc", dc.getId());
                }
                List<CardView> blockers = snapshot(combat.getBlockers(a));
                if (blockers.isEmpty()) {
                    blockers = snapshot(combat.getPlannedBlockers(a));
                }
                if (!blockers.isEmpty()) {
                    o.beginArr("b");
                    for (CardView b : blockers) o.val(b.getId());
                    o.endArr();
                }
                o.endObj();
            }
        }
        o.endArr();
        return o.toString();
    }

    public void shutdown() {
        game = null;
        scheduler.shutdownNow();
    }

    /** Utility used by dialogs to decide how to render an object. */
    public static Predicate<CardView> always() {
        return c -> true;
    }
}

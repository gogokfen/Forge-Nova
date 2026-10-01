package forge.nova.gui;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameOutcome;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.YieldMarker;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IDevModeCheats;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.nova.net.ClientLink;
import forge.nova.sync.CardDetails;
import forge.nova.sync.StateSync;
import forge.nova.util.JsonOut;
import forge.player.NovaControllerAccess;
import forge.player.PlayerControllerHuman;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import forge.util.Localizer;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The match screen, implemented as a bridge to the browser.
 *
 * Forge's engine calls these IGuiGame methods exactly as it calls the Swing CMatchUI; instead of
 * repainting Swing components we stream state diffs and prompts to the WebGL client, and feed the
 * client's clicks back into the engine through the standard IGameController interface.
 */
public final class NovaGuiGame extends AbstractGuiGame implements StateSync.ViewPolicy {
    private static final PhaseType[] PHASES = PhaseType.values();

    private final ClientLink link;
    private final Dialogs dialogs;
    private final NovaEdt edt;
    private final StateSync sync;
    private final Runnable onGameEndedCallback;
    /** the host's own window: phase stops are Forge preferences; a friend's stops live only in this match */
    private final boolean persistPrefs;

    private volatile List<PlayerView> sortedPlayers = Collections.emptyList();
    /** phases at which the UI stops (Forge: "enabled phase label"), per player id */
    private final Map<Integer, EnumSet<PhaseType>> phaseStops = new ConcurrentHashMap<>();
    private final Map<String, String> avatarImages = new ConcurrentHashMap<>();

    // --- prompt / selection state (re-sent after reconnects)
    private volatile String promptMsg = "";
    private volatile int promptCard = -1;
    private volatile int promptPlayer = -1;
    private volatile String b1Label = "", b2Label = "";
    private volatile boolean b1On, b2On, focus1;
    private volatile String inputType = "";
    private final Set<CardView> selectable = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile int selMin, selMax;
    private final Set<GameEntityView> highlighted = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<CardView, Integer> weak = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Integer, SpellAbilityView> abilityMenu = new ConcurrentHashMap<>();
    private volatile String abilityMenuJson;
    /** the ability the player last picked to play (see getInteger: "choose X" for it) */
    private volatile SpellAbility pickedAbility;
    private volatile String gameOverJson;
    /** this player's option: mark the cards they can play whenever they get priority */
    private volatile boolean highlightPlayable = true;
    private final AtomicBoolean playablePending = new AtomicBoolean();
    private Observer logObserver;
    /** the player chose "Back to main menu": the match is being torn down, so no game-over screen */
    private volatile boolean leaving;
    /** a finished or abandoned match keeps calling into its GUI for a while; it must not reach the client */
    private volatile boolean disposed;
    /** scry/surveil without Forge's card displays: the top order chosen in the window, for Forge's second question */
    private volatile List<CardView> pendingTopOrder;
    /** the free mulligan house rule: its note is on the mulligan prompt; this player has taken it this game */
    private volatile boolean freeMullNote, freeMullTaken;
    /** the host's own window feeds Discord's "now playing" (life, turn) */
    private volatile Runnable onGameStateChanged;

    public NovaGuiGame(ClientLink link, Dialogs dialogs, NovaEdt edt, Runnable onGameEnded) {
        this(link, dialogs, edt, onGameEnded, true);
    }

    public NovaGuiGame(ClientLink link, Dialogs dialogs, NovaEdt edt, Runnable onGameEnded, boolean persistPrefs) {
        this.link = link;
        this.dialogs = dialogs;
        this.edt = edt;
        this.onGameEndedCallback = onGameEnded;
        this.persistPrefs = persistPrefs;
        this.sync = new StateSync(link, this);
    }

    public ClientLink getLink() {
        return link;
    }

    public Dialogs getDialogs() {
        return dialogs;
    }

    /** Called (on Forge's threads) when life totals or the turn change. */
    public void setOnGameStateChanged(Runnable r) {
        this.onGameStateChanged = r;
    }

    private void gameStateChanged() {
        Runnable r = onGameStateChanged;
        if (r != null && !disposed) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // presence is best-effort
            }
        }
    }

    /** The game behind the view (null between games). */
    public Game currentGame() {
        GameView gv = getGameView();
        return gv == null ? null : gv.getGame();
    }

    // =================================================================== ViewPolicy (for StateSync)

    @Override
    public Collection<PlayerView> localPlayers() {
        try {
            return new ArrayList<>(getLocalPlayers());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public boolean mayView(CardView c) {
        try {
            return super.mayView(c);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean mayFlip(CardView c) {
        try {
            return super.mayFlip(c);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void writeExtraPlayerInfo(PlayerView p, JsonOut out) {
        EnumSet<PhaseType> stops = phaseStops.get(p.getId());
        if (stops != null) {
            int mask = 0;
            for (PhaseType ph : stops) {
                mask |= 1 << ph.ordinal();
            }
            out.put("stops", mask);
        }
        String img = avatarImages.get(p.getLobbyPlayerName());
        out.putOpt("avImg", img);
        PlayerView master = p.getMindSlaveMaster();
        if (master != null) {
            out.put("ctrlBy", master.getId());
        }
    }

    // =================================================================== connection / lifecycle

    /** A (re)connected browser needs the full picture. Called on a network thread. */
    public void resync() {
        GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        sync.invalidate();
        sendMatchInfo();
        sync.flushNow();
        sendPrompt();
        sendSelection();
        String menu = abilityMenuJson;
        if (menu != null) {
            send(menu);
        }
        String over = gameOverJson;
        if (over != null) {
            send(over);
        }
        refreshYieldUi(getCurrentPlayer());
        sendDevState();
    }

    public boolean isActive() {
        return getGameView() != null;
    }

    public void setLeaving() {
        leaving = true;
    }

    private void send(String json) {
        if (!disposed) {
            link.send(json);
        }
    }

    private void sendMatchInfo() {
        GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        JsonOut o = new JsonOut(256);
        o.beginObj().put("t", "match");
        o.put("gameId", gv.getId());
        o.putOpt("title", gv.getTitle());
        o.beginArr("order");
        for (PlayerView p : sortedPlayers) {
            o.val(p.getId());
        }
        o.endArr();
        o.beginArr("local");
        for (PlayerView p : localPlayers()) {
            o.val(p.getId());
        }
        o.endArr();
        o.flag("spectator", localPlayers().isEmpty());
        o.flag("online", isNetGame());
        o.endObj();
        send(o.toString());
    }

    private void sendPrompt() {
        JsonOut o = new JsonOut(512);
        o.beginObj().put("t", "prompt");
        o.put("msg", promptMsg == null ? "" : promptMsg);
        if (promptCard >= 0) o.put("card", promptCard);
        if (promptPlayer >= 0) o.put("player", promptPlayer);
        o.beginObj("b1").put("l", b1Label == null ? "" : b1Label).put("on", b1On).endObj();
        o.beginObj("b2").put("l", b2Label == null ? "" : b2Label).put("on", b2On).endObj();
        o.put("focus1", focus1);
        o.putOpt("input", inputType);
        o.endObj();
        send(o.toString());
    }

    private void sendSelection() {
        JsonOut o = new JsonOut(256);
        o.beginObj().put("t", "sel");
        o.beginArr("ids");
        synchronized (selectable) {
            for (CardView c : selectable) o.val(c.getId());
        }
        o.endArr();
        o.put("min", selMin).put("max", selMax);
        List<Integer> hiCards = new ArrayList<>();
        List<Integer> hiPlayers = new ArrayList<>();
        synchronized (highlighted) {
            for (GameEntityView ge : highlighted) {
                if (ge instanceof PlayerView p) hiPlayers.add(p.getId());
                else if (ge instanceof CardView c) hiCards.add(c.getId());
            }
        }
        o.beginArr("hiC");
        for (Integer i : hiCards) o.val(i);
        o.endArr();
        o.beginArr("hiP");
        for (Integer i : hiPlayers) o.val(i);
        o.endArr();
        o.beginObj("weak");
        synchronized (weak) {
            for (Map.Entry<CardView, Integer> e : weak.entrySet()) {
                o.put(String.valueOf(e.getKey().getId()), e.getValue());
            }
        }
        o.endObj();
        o.endObj();
        send(o.toString());
    }

    // =================================================================== game view

    @Override
    public void setGameView(GameView gameView0) {
        super.setGameView(gameView0);
        GameView gv = getGameView();
        sync.setGame(gv);
        if (gv != null) {
            gameOverJson = null;
            sync.requestFlush();
        }
    }

    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {
        GameView gv = getGameView();
        FCollectionView<PlayerView> players = gv.getPlayers();
        List<PlayerView> order = new ArrayList<>();
        for (PlayerView p : players) {
            order.add(p);
        }
        // local player first, the rest in turn order (Forge's "rows" multiplayer layout)
        if (myPlayers != null && !myPlayers.isEmpty()) {
            int idx = order.indexOf(myPlayers.get(0));
            if (idx > 0) {
                Collections.rotate(order, -idx);
            }
        }
        sortedPlayers = order;
        if (!persistPrefs) {
            // Forge's achievements are the host's; a friend's game must not count for them
            // (achievements skip controllers that have created their dev-mode helper)
            for (IGameController gc : getOriginalGameControllers()) {
                if (gc instanceof PlayerControllerHuman pch) {
                    pch.cheat();
                }
            }
        }
        initPhaseStops();
        gameOverJson = null;
        abilityMenuJson = null;
        pickedAbility = null;
        pendingTopOrder = null;
        freeMullNote = freeMullTaken = false;
        promptMsg = "";
        promptCard = -1;
        b1Label = b2Label = "";
        b1On = b2On = false;
        selectable.clear();
        highlighted.clear();
        weak.clear();

        dialogs.bindGame(this, this::mayView, localPlayers().isEmpty() ? null : localPlayers(), sync::flushNow);
        if (gv.getGameLog() != null) {
            if (logObserver != null) {
                gv.getGameLog().deleteObserver(logObserver);
            }
            logObserver = (o, arg) -> sync.requestFlush();
            gv.getGameLog().addObserver(logObserver);
        }
        sync.setGame(gv);
        sendMatchInfo();
        sync.flushNow();
        sendPrompt();
        sendSelection();
        sendDevState();
        gameStateChanged();
    }

    private void initPhaseStops() {
        ForgePreferences prefs = FModel.getPreferences();
        phaseStops.clear();
        for (PlayerView p : sortedPlayers) {
            ForgePreferences.FPref[] keys = isLocalPlayer(p) ? ForgePreferences.FPref.PHASES_HUMAN : ForgePreferences.FPref.PHASES_AI;
            EnumSet<PhaseType> set = EnumSet.noneOf(PhaseType.class);
            for (int i = 1; i < PHASES.length && i - 1 < keys.length; i++) {
                // a friend gets Forge's default stops, not the host's personal ones
                boolean on = persistPrefs ? prefs.getPrefBoolean(keys[i - 1]) : Boolean.parseBoolean(keys[i - 1].getDefault());
                if (on) {
                    set.add(PHASES[i]);
                }
            }
            phaseStops.put(p.getId(), set);
        }
    }

    private void savePhaseStops() {
        if (!persistPrefs) {
            return;
        }
        ForgePreferences prefs = FModel.getPreferences();
        for (PlayerView p : sortedPlayers) {
            EnumSet<PhaseType> set = phaseStops.get(p.getId());
            if (set == null) continue;
            ForgePreferences.FPref[] keys = isLocalPlayer(p) ? ForgePreferences.FPref.PHASES_HUMAN : ForgePreferences.FPref.PHASES_AI;
            for (int i = 1; i < PHASES.length && i - 1 < keys.length; i++) {
                prefs.setPref(keys[i - 1], set.contains(PHASES[i]));
            }
        }
        prefs.save();
    }

    @Override
    protected void updateCurrentPlayer(PlayerView player) {
        sync.requestFlush();
    }

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
        link.cancelAllDialogs();
        abilityMenuJson = null;
        if (getGameView() != null && getGameView().getGameLog() != null && logObserver != null) {
            getGameView().getGameLog().deleteObserver(logObserver);
        }
    }

    @Override
    public void finishGame() {
        if (leaving || disposed) {
            return;
        }
        sync.flushNow();
        GameView gv = getGameView();
        JsonOut o = new JsonOut(512);
        o.beginObj().put("t", "gameOver");
        if (gv != null) {
            o.put("matchOver", gv.isMatchOver());
            o.putOpt("winner", gv.getWinningPlayerName());
            o.put("gamesPlayed", gv.getNumPlayedGamesInMatch());
            o.put("gamesInMatch", gv.getNumGamesInMatch());
            GameOutcome outcome = gv.getOutcome();
            if (outcome != null) {
                o.put("draw", outcome.isDraw());
                o.put("turns", outcome.getLastTurnNumber());
                o.beginArr("lines");
                for (String s : outcome.getOutcomeStrings()) o.val(s);
                o.endArr();
            }
            o.beginArr("score");
            for (PlayerView p : sortedPlayers) {
                o.beginObj().put("id", p.getId()).put("n", p.getName());
                try {
                    LobbyPlayer lp = gv.getGame() == null ? null : gv.getGame().getPlayer(p).getLobbyPlayer();
                    if (lp != null) {
                        o.put("won", gv.getGamesWonBy(lp));
                        o.flag("winner", gv.isWinner(lp));
                    }
                } catch (Exception ignored) {
                    // player may have left the game object graph
                }
                o.endObj();
            }
            o.endArr();
            o.flag("spectator", localPlayers().isEmpty());
            o.flag("online", isNetGame());
        }
        o.endObj();
        gameOverJson = o.toString();
        send(gameOverJson);
        if (onGameEndedCallback != null) {
            onGameEndedCallback.run();
        }
    }

    // =================================================================== incremental updates

    @Override public void showCombat() { sync.requestFlush(); }
    @Override public void updatePhase(boolean saveState) { sync.requestFlush(); }
    @Override public void updateTurn(PlayerView player) { sync.requestFlush(); gameStateChanged(); }
    @Override public void updatePlayerControl() { sendMatchInfo(); sync.requestFlush(); }
    @Override public void updateStack() { sync.requestFlush(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { sync.requestFlush(); }
    @Override public void updateCards(Iterable<CardView> cards) { sync.requestFlush(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { sync.requestFlush(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { sync.requestFlush(); gameStateChanged(); }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { sync.requestFlush(); }
    @Override public void refreshField() { sync.requestFlush(); }
    @Override public void refreshCardDetails(Iterable<CardView> cards) { sync.requestFlush(); }
    @Override public void updateDependencies() { sync.requestFlush(); }
    @Override public void showManaPool(PlayerView player) { }
    @Override public void hideManaPool(PlayerView player) { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public GameState getGamestate() { return null; }

    @Override
    public void updateRevealedCards(TrackableCollection<CardView> collection) {
        super.updateRevealedCards(collection);
        sync.requestFlush();
    }

    @Override
    public void updateDayTime(String daytime) {
        super.updateDayTime(daytime);
        send("{\"t\":\"daytime\",\"v\":" + (daytime == null ? "null" : "\"" + daytime + "\"") + "}");
    }

    @Override
    public void setPanelSelection(CardView card) {
        if (card != null) {
            send("{\"t\":\"focusCard\",\"id\":" + card.getId() + "}");
        }
    }

    @Override
    public void setCard(CardView card) {
        if (card != null) {
            send("{\"t\":\"focusCard\",\"id\":" + card.getId() + "}");
        }
    }

    @Override
    public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) {
        if (player != null && ihi != null && ihi.getIconImageKey() != null) {
            avatarImages.put(player.getName(), ihi.getIconImageKey());
        }
    }

    // =================================================================== prompt & buttons

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        cancelWaitingTimer();
        setPrompt(playerView, message, card);
    }

    private void setPrompt(PlayerView playerView, String message, CardView card) {
        message = withHouseRuleNote(message);
        promptMsg = message == null ? "" : message;
        promptCard = card == null ? -1 : card.getId();
        promptPlayer = playerView == null ? -1 : playerView.getId();
        inputType = currentInputType();
        sync.flushNow();
        sendPrompt();
    }

    /**
     * The free mulligan house rule (see MulliganService in the engine patches): on the keep / mulligan question, says
     * when this hand's mulligan would be free (no lands or seven lands, the first time this game).
     */
    private String withHouseRuleNote(String message) {
        freeMullNote = false;
        if (message == null || freeMullTaken || !HouseRules.freeMulliganActive() || !"InputConfirmMulligan".equals(currentInputType())) {
            return message;
        }
        try {
            PlayerControllerHuman pch = humanController();
            Player p = pch == null ? null : pch.getPlayer();
            if (p == null) {
                return message;
            }
            int cards = 0, lands = 0;
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                cards++;
                if (c.isLand()) lands++;
            }
            if (cards == 0 || (lands != 0 && lands < 7)) {
                return message;
            }
            freeMullNote = true;
            return message + "\n\nHouse rule: this hand has " + (lands == 0 ? "no lands" : "seven lands")
                    + ", so a mulligan now is free (once per game).";
        } catch (RuntimeException e) {
            return message;
        }
    }

    /** Updates of the "waiting for another player (12s)" prompt, which must not stop its own timer. */
    @Override
    public void showPromptMessageNoCancel(PlayerView playerView, String message) {
        setPrompt(playerView, message, null);
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        b1Label = label1;
        b2Label = label2;
        b1On = enable1;
        b2On = enable2;
        this.focus1 = focus1;
        inputType = currentInputType();
        sendPrompt();
        if ("InputPassPriority".equals(inputType)) {
            schedulePlayable();
        }
    }

    // =================================================================== playable cards

    private PlayerControllerHuman humanController() {
        try {
            return getGameController() instanceof PlayerControllerHuman pch ? pch : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A priority prompt is shown: mark the cards the player can play now (after the prompt went out, on the UI
     * thread like the clicks, while the game thread waits for this player).
     */
    private void schedulePlayable() {
        if (highlightPlayable && !disposed && playablePending.compareAndSet(false, true)) {
            edt.later(() -> {
                playablePending.set(false);
                refreshPlayable();
            });
        }
    }

    private void refreshPlayable() {
        final PlayerControllerHuman pch = humanController();
        if (!highlightPlayable || disposed || pch == null || pch.getPlayer() == null) {
            return;
        }
        final Object input = pch.getInputQueue().getInput();
        if (!(input instanceof InputPassPriority)) {
            return;
        }
        // with Forge's own "actionable highlights" preference on, Forge marks these cards itself
        if (pch.getYieldController().getBoolPref(ForgePreferences.FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS)) {
            return;
        }
        Player p = pch.getPlayer();
        if (p.getGame() == null || p.getGame().isGameOver()) {
            return;
        }
        Set<CardView> cards = Playable.collect(p, edt::hasUserActions);
        if (cards == null) {
            schedulePlayable(); // a click came first; try again after it, if this prompt is still up
            return;
        }
        if (pch.getInputQueue().getInput() == input && highlightPlayable) {
            setWeaklySelectable(cards);
        }
    }

    /** The client's display options that the host acts on. */
    private void applyUiPrefs(JsonObject m) {
        if (m.has("playable")) {
            final boolean on = m.get("playable").getAsBoolean();
            if (on == highlightPlayable) {
                return;
            }
            highlightPlayable = on;
            edt.later(() -> {
                PlayerControllerHuman pch = humanController();
                if (pch == null || pch.getPlayer() == null) {
                    return;
                }
                if (on) {
                    refreshPlayable();
                } else if (!pch.getYieldController().getBoolPref(ForgePreferences.FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS)) {
                    clearWeaklySelectable();
                }
            });
        }
    }

    // =================================================================== developer mode

    /**
     * Classic Forge's "Dev Mode" tab: with Forge's developer mode preference on, in offline matches that have a
     * player of ours (online, friends would play against the host's cheats).
     */
    public boolean devAllowed() {
        if (disposed || isNetGame()) {
            return false;
        }
        try {
            if (!FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.DEV_MODE_ENABLED)) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        PlayerControllerHuman pch = humanController();
        return pch != null && pch.getPlayer() != null;
    }

    /** Tells the client whether to show the Dev tab, and the state of its two switches. */
    public void sendDevState() {
        JsonOut o = new JsonOut(128).beginObj().put("t", "dev");
        boolean on = devAllowed();
        o.put("on", on);
        if (on) {
            try {
                IGameController gc = getGameController();
                if (gc != null) {
                    o.put("lands", gc.canPlayUnlimitedLands()).put("viewAll", gc.mayLookAtAllCards());
                }
                GameView gv = getGameView();
                o.flag("planar", gv != null && gv.getGame() != null && gv.getGame().getRules().hasAppliedVariant(GameType.Planechase));
            } catch (RuntimeException ignored) {
                // switches unknown
            }
        }
        o.endObj();
        send(o.toString());
    }

    /** One of the Dev tab's buttons (runs on the UI thread, as classic Forge runs them on Swing's). */
    private void devAction(IGameController gc, String action) {
        if (gc == null || !devAllowed()) {
            return;
        }
        IDevModeCheats cheat = gc.cheat();
        switch (action) {
            case "lands" -> cheat.setCanPlayUnlimitedLands(!gc.canPlayUnlimitedLands());
            case "viewAll" -> cheat.setViewAllCards(!gc.mayLookAtAllCards());
            case "mana" -> cheat.generateMana();
            case "tutor" -> cheat.tutorForCard();
            case "cast" -> cheat.castASpell();
            case "toHand" -> cheat.addCardToHand();
            case "toLibrary" -> cheat.addCardToLibrary();
            case "toGraveyard" -> cheat.addCardToGraveyard();
            case "toExile" -> cheat.addCardToExile();
            case "toBattlefield" -> cheat.addCardToBattlefield();
            case "token" -> cheat.addTokenToBattlefield();
            case "remove" -> cheat.removeCardsFromGame();
            case "repeat" -> cheat.repeatLastAddition();
            case "exileHand" -> cheat.exileCardsFromHand();
            case "exilePlay" -> cheat.exileCardsFromBattlefield();
            case "life" -> cheat.setPlayerLife();
            case "win" -> cheat.winGame();
            case "addCounters" -> cheat.addCountersToPermanent();
            case "subCounters" -> cheat.removeCountersFromPermanent();
            case "tap" -> cheat.tapPermanents();
            case "untap" -> cheat.untapPermanents();
            case "planarRoll" -> cheat.riggedPlanarRoll();
            case "planeswalk" -> cheat.planeswalkTo();
            case "askAI" -> cheat.askAI(false);
            case "askSimAI" -> cheat.askAI(true);
            case "loadState" -> cheat.setupGameState();
            case "saveState" -> cheat.dumpGameState();
            default -> {
                return;
            }
        }
        sendDevState();
        sync.requestFlush();
        // the cards that can be played may have changed (mana, lands, cards added...); most cheats finish on the
        // game thread a moment later
        CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute(() -> {
            if ("InputPassPriority".equals(currentInputType())) {
                schedulePlayable();
            }
        });
    }

    private String currentInputType() {
        try {
            IGameController gc = getGameController();
            if (gc instanceof PlayerControllerHuman pch && pch.getInputQueue().getInput() != null) {
                // Forge often uses anonymous subclasses (e.g. the discard at cleanup): report the named class
                Class<?> c = pch.getInputQueue().getInput().getClass();
                while (c != null && c.getSimpleName().isEmpty()) {
                    c = c.getSuperclass();
                }
                return c == null ? "" : c.getSimpleName();
            }
        } catch (Exception ignored) {
            // no input
        }
        return "";
    }

    @Override
    public void flashIncorrectAction() {
        send("{\"t\":\"flash\"}");
    }

    @Override
    public void alertUser() {
        send("{\"t\":\"alert\"}");
    }

    // =================================================================== selection

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) {
        super.setSelectables(cards, min, max);
        for (CardView c : cards) {
            selectable.add(c);
        }
        selMin = min;
        selMax = max;
        sendSelection();
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        selectable.clear();
        selMin = selMax = 0;
        sendSelection();
    }

    @Override
    public void setHighlighted(Iterable<GameEntityView> entities, boolean b) {
        super.setHighlighted(entities, b);
        for (GameEntityView ge : entities) {
            if (b) highlighted.add(ge);
            else highlighted.remove(ge);
        }
        sendSelection();
    }

    @Override
    public void setWeaklySelectable(Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        synchronized (weak) {
            weak.clear();
            for (CardView c : cards) {
                weak.merge(c, 1, Integer::sum);
            }
        }
        sendSelection();
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        weak.clear();
        sendSelection();
    }

    // =================================================================== zones

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) {
        List<PlayerZoneUpdate> shown = new ArrayList<>();
        JsonOut o = new JsonOut(128).beginObj().put("t", "showZones").beginArr("zones");
        for (PlayerZoneUpdate u : zonesToUpdate) {
            for (ZoneType z : u.getZones()) {
                if (z == ZoneType.Battlefield || (z == ZoneType.Hand && u.getPlayer().equals(controller))) {
                    continue;
                }
                o.beginObj().put("p", u.getPlayer().getId()).put("z", z.name()).endObj();
            }
            shown.add(u);
        }
        o.endArr().endObj();
        sync.flushNow();
        send(o.toString());
        return shown;
    }

    @Override
    public void hideZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) {
        if (zonesToUpdate == null) return;
        JsonOut o = new JsonOut(128).beginObj().put("t", "hideZones").beginArr("zones");
        for (PlayerZoneUpdate u : zonesToUpdate) {
            for (ZoneType z : u.getZones()) {
                o.beginObj().put("p", u.getPlayer().getId()).put("z", z.name()).endObj();
            }
        }
        o.endArr().endObj();
        send(o.toString());
    }

    @Override
    public PlayerZoneUpdates openZones(PlayerView controller, Collection<ZoneType> zones, Map<PlayerView, Object> playersWithTargetables, boolean backupLastZones) {
        PlayerZoneUpdates updates = new PlayerZoneUpdates();
        for (PlayerView view : playersWithTargetables.keySet()) {
            for (ZoneType zone : zones) {
                if (zone != ZoneType.Battlefield && zone != ZoneType.Hand && zone != ZoneType.Stack) {
                    updates.add(new PlayerZoneUpdate(view, zone));
                }
            }
        }
        tempShowZones(controller, updates);
        return updates;
    }

    @Override
    public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) {
        hideZones(playerView, playerZoneUpdates);
    }

    // =================================================================== phases

    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        if (phase == PhaseType.UNTAP) {
            return false; // no label for untap in Forge either
        }
        PlayerView master = playerTurn.getMindSlaveMaster();
        boolean skipped = true;
        if (master != null) {
            skipped = !stopsAt(master, phase);
        }
        return skipped && !stopsAt(playerTurn, phase);
    }

    private boolean stopsAt(PlayerView p, PhaseType phase) {
        EnumSet<PhaseType> set = phaseStops.get(p.getId());
        return set == null || set.contains(phase);
    }

    @Override
    public void refreshYieldUi(PlayerView player) {
        PlayerView local = getCurrentPlayer();
        IGameController gc = local == null ? null : getGameController(local);
        YieldMarker m = gc == null ? null : gc.getYieldController().getAutoPassUntilMarker();
        JsonOut o = new JsonOut(96).beginObj().put("t", "yield");
        if (m != null) {
            o.beginObj("marker").put("p", m.getPhaseOwner().getId()).put("ph", m.getPhase().name()).endObj();
        }
        o.endObj();
        send(o.toString());
    }

    // =================================================================== abilities

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        SpellAbilityView chosen = chooseAbilityToPlay(hostCard, abilities, triggerEvent);
        rememberPicked(chosen);
        return chosen;
    }

    /** The ability the player picked (Forge maps the view back to it); a "choose X" question may be about it. */
    private void rememberPicked(SpellAbilityView view) {
        if (view == null) {
            return;
        }
        try {
            pickedAbility = NovaControllerAccess.abilityOf(humanController(), view);
        } catch (RuntimeException e) {
            pickedAbility = null;
        }
    }

    private SpellAbilityView chooseAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) {
            return null;
        }
        if (triggerEvent == null) {
            if (abilities.size() == 1) {
                return abilities.get(0);
            }
            return oneOrNone(Localizer.getInstance().getMessage("lblChooseAbilityToPlay"), abilities);
        }
        if (abilities.size() == 1 && !abilities.get(0).promptIfOnlyPossibleAbility()) {
            return abilities.get(0).canPlay() ? abilities.get(0) : null;
        }
        // Non-blocking popup menu, exactly like the Swing client: the choice comes back later
        // through IGameController.selectAbility().
        abilityMenu.clear();
        JsonOut o = new JsonOut(512).beginObj().put("t", "abilities").put("card", hostCard.getId());
        o.put("x", triggerEvent.getX()).put("y", triggerEvent.getY());
        o.beginArr("items");
        for (SpellAbilityView sa : abilities) {
            abilityMenu.put(sa.getId(), sa);
            o.beginObj().put("id", sa.getId()).put("label", sa.toString()).put("on", sa.canPlay()).endObj();
        }
        o.endArr().endObj();
        abilityMenuJson = o.toString();
        send(abilityMenuJson);
        return null;
    }

    // =================================================================== blocking dialogs

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        if (damage <= 0) {
            return Collections.emptyMap();
        }
        CardView first = blockers.get(0);
        boolean deathtouch = attacker.getCurrentState() != null && attacker.getCurrentState().hasDeathtouch();
        if (!overrideOrder && !deathtouch && first.getLethalDamage() >= damage) {
            return ImmutableMap.of(first, damage);
        }
        int[] lethal = new int[blockers.size()];
        for (int i = 0; i < blockers.size(); i++) {
            lethal[i] = deathtouch ? 1 : Math.max(0, blockers.get(i).getLethalDamage());
        }
        return dialogs.assignCombatDamage(attacker, blockers, damage, defender, overrideOrder, maySkip, lethal, deathtouch);
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount,
                                                    boolean atLeastOne, String amountLabel) {
        if (amount <= 0) {
            return Collections.emptyMap();
        }
        return dialogs.assignAmount(effectSource, target, amount, atLeastOne, amountLabel);
    }

    @Override
    public void message(String message, String title) {
        Game game = currentGame();
        if (message != null && message.contains("Attack declaration invalid")) {
            // Forge doesn't say why; the attack is still declared, so the reasons can be worked out
            String why = null;
            try {
                why = AttackExplainer.explain(game);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
            dialogs.message(why != null ? why : message, "This attack isn't allowed", false);
            return;
        }
        DiceRolls dice = null;
        try {
            dice = DiceRolls.parse(message, game);
        } catch (RuntimeException ignored) {
            // a plain message
        }
        dialogs.message(message, title, false, dice);
    }

    @Override
    public void showErrorDialog(String message, String title) {
        dialogs.message(message, title, true);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        int r = dialogs.option(message, title, List.of(yesButtonText, noButtonText), defaultYes ? 0 : 1, null);
        return r == 0;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return dialogs.option(message, title, options, defaultOption, null);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        return dialogs.input(message, title, initialInput, inputOptions, isNumeric);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        List<String> opts = options == null || options.isEmpty()
                ? List.of(Localizer.getInstance().getMessage("lblYes"), Localizer.getInstance().getMessage("lblNo"))
                : options;
        int r = dialogs.option(question, c == null ? "" : c.toString(), opts, defaultIsYes ? 0 : 1, c);
        return r == 0;
    }

    /**
     * Forge's number questions ("Choose X for Fireball", "How many times...", "How many?"): one small window with
     * − / + instead of Forge's list of 0-9 and "Other...". For X of a mana cost it also tells the most the player's
     * mana can pay (the Max button), as MTG Arena does.
     */
    @Override
    public Integer getInteger(String message, int min, int max, int cutoff) {
        return askNumber(message, min, max);
    }

    @Override
    public Integer getInteger(String message, int min, int max, boolean sortDesc) {
        return askNumber(message, min, max);
    }

    private Integer askNumber(String message, int min, int max) {
        if (max <= min) {
            return min; // nothing to choose (Forge doesn't ask either)
        }
        int afford = -1;
        String cost = null;
        PlayerControllerHuman pch = humanController();
        Player p = pch == null ? null : pch.getPlayer();
        try {
            SpellAbility sa = p == null ? null : AffordableX.abilityFor(message, p, pickedAbility);
            if (sa != null) {
                cost = AffordableX.manaCost(sa);
                afford = AffordableX.of(sa, p, max);
            }
        } catch (RuntimeException e) {
            // no Max hint
        }
        return dialogs.number(message, min, max, afford, cost);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        return dialogs.choose(message, min, max, choices, selected, display == null ? null : display::apply, null);
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                             List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                             boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> scried = scryStep(title, sourceChoices, destChoices);
        if (scried != null) {
            return new IGuiGame.OrderResult<>(scried, false);
        }
        Dialogs.Ordered<T> r = dialogs.order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices,
                referenceCard, sideboardingMode, showRememberCheckbox);
        return new IGuiGame.OrderResult<>(r.items(), r.remember());
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return dialogs.sideboard(sideboard.toFlatList(), main.toFlatList(), message);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        if (delayedReveal != null) {
            reveal(delayedReveal.getMessagePrefix(), delayedReveal.getCards());
        }
        return isOptional ? oneOrNone(title, optionList) : one(title, optionList);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        if (delayedReveal != null) {
            reveal(delayedReveal.getMessagePrefix(), delayedReveal.getCards());
        }
        @SuppressWarnings("unchecked")
        List<GameEntityView> options = (List<GameEntityView>) optionList;
        return order(title, Localizer.getInstance().getMessage("lblSelected"), optionList.size() - max, optionList.size() - min,
                options, null, null, false);
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        for (CardView c : cards) all.add(c);
        List<CardView> movable = new ArrayList<>();
        for (CardView c : manipulable) movable.add(c);
        int n = movable.size();
        // Scry with Forge's "select from card displays" on: Forge passes the whole library, the top n movable. Only
        // those n are shown; the answer is turned back into the library order Forge reads it from (arrangeForMove:
        // the movable cards at the start go on top, those at the end, read from the end, go to the bottom)
        if (toTop && toBottom && !toAnywhere && n > 0 && n <= all.size()
                && new HashSet<>(all.subList(0, n)).equals(new HashSet<>(movable))
                && Localizer.getInstance().getMessage("lblMoveCardstoToporBbottomofLibrary").equals(title)) {
            Dialogs.Arranged r = dialogs.scry("Scry " + n, "bottom", new ArrayList<>(all.subList(0, n)));
            if (r == null) {
                return all;
            }
            List<CardView> out = new ArrayList<>(r.top());
            List<CardView> rest = all.subList(n, all.size());
            if (rest.isEmpty()) {
                out.addAll(r.other()); // all of the library: it is simply this order, top first
            } else {
                out.addAll(rest);
                List<CardView> bottom = new ArrayList<>(r.other());
                Collections.reverse(bottom);
                out.addAll(bottom);
            }
            return out;
        }
        return dialogs.arrange(title, all, movable, toTop, toBottom, toAnywhere);
    }

    /**
     * Scry and surveil without Forge's card displays come as two questions: which cards go to the bottom (or the
     * graveyard), then the order of the rest on top. The scry window answers both: the first question returns its
     * bottom (graveyard) cards and keeps its top order for the second. Null: not one of these questions.
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> scryStep(String title, List<T> source, List<T> dest) {
        if (title == null || source == null || source.isEmpty() || (dest != null && !dest.isEmpty())) {
            return null;
        }
        for (T t : source) {
            if (!(t instanceof CardView)) {
                return null;
            }
        }
        List<CardView> cards = (List<CardView>) source;
        Localizer loc = Localizer.getInstance();
        String other = title.equals(loc.getMessage("lblSelectCardsToBeOutOnTheBottomOfYourLibrary")) ? "bottom"
                : title.equals(loc.getMessage("lblSelectCardsToBePutIntoTheGraveyard")) ? "graveyard" : null;
        if (other != null) {
            Dialogs.Arranged r = dialogs.scry((other.equals("bottom") ? "Scry " : "Surveil ") + cards.size(), other, new ArrayList<>(cards));
            if (r == null) {
                pendingTopOrder = null;
                return new ArrayList<>(); // everything stays on top
            }
            pendingTopOrder = r.top().size() > 1 ? new ArrayList<>(r.top()) : null;
            return (List<T>) new ArrayList<>(r.other());
        }
        if (title.equals(loc.getMessage("lblArrangeCardsToBePutOnTopOfYourLibrary"))) {
            List<CardView> remembered = pendingTopOrder;
            pendingTopOrder = null;
            if (remembered != null && remembered.size() == cards.size() && new HashSet<>(remembered).equals(new HashSet<>(cards))) {
                return (List<T>) new ArrayList<>(remembered);
            }
        }
        return null;
    }

    // =================================================================== browser -> engine

    private static final class Trigger implements ITriggerEvent {
        private final int button, x, y;

        Trigger(int button, int x, int y) {
            this.button = button;
            this.x = x;
            this.y = y;
        }

        @Override public int getButton() { return button; }
        @Override public int getX() { return x; }
        @Override public int getY() { return y; }
    }

    private CardView findCard(int id) {
        GameView gv = getGameView();
        if (gv == null) return null;
        Tracker t = gv.getTracker();
        CardView c = t == null ? null : t.getObj(TrackableTypes.CardViewType, id);
        if (c != null) return c;
        for (PlayerView p : gv.getPlayers()) {
            for (ZoneType z : ZoneType.values()) {
                FCollectionView<CardView> zc = p.getCards(z);
                if (zc == null) continue;
                for (CardView cv : zc) {
                    if (cv.getId() == id) return cv;
                }
            }
        }
        return null;
    }

    /** The hovered card's details for the side panel (see CardDetails); read concurrently with the game thread. */
    private void sendCardText(int id, boolean alt) {
        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            try {
                json = CardDetails.json(getGameView(), findCard(id), id, alt, this::mayView, this::mayFlip);
            } catch (RuntimeException e) {
                // the game changed the card meanwhile: try again
            }
        }
        send(json != null ? json : "{\"t\":\"cardText\",\"id\":" + id + ",\"alt\":" + alt + "}");
    }

    /** "Cards left": the player's own cards whose place they can't see (their library, mostly); see LibraryLeft. */
    private void sendLibraryLeft(int playerId) {
        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            try {
                json = LibraryLeft.json(currentGame(), findPlayer(playerId), localPlayers(), this::mayView);
            } catch (RuntimeException e) {
                // the game changed meanwhile: try again
            }
        }
        send(json != null ? json : "{\"t\":\"libraryLeft\",\"pid\":" + playerId + ",\"error\":true}");
    }

    private PlayerView findPlayer(int id) {
        GameView gv = getGameView();
        if (gv == null) return null;
        for (PlayerView p : gv.getPlayers()) {
            if (p.getId() == id) return p;
        }
        return null;
    }

    private static int intOf(JsonObject m, String k, int def) {
        JsonElement e = m.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    /** Executes a client action on the UI thread, just like a mouse click in Swing. */
    public void handleAction(JsonObject m) {
        final String type = m.has("t") ? m.get("t").getAsString() : "";
        // read-only or display-only requests are answered right away instead of waiting behind the clicks
        if ("cardText".equals(type)) {
            sendCardText(intOf(m, "id", -1), m.has("alt") && m.get("alt").getAsBoolean());
            return;
        }
        if ("uiPrefs".equals(type)) {
            applyUiPrefs(m);
            return;
        }
        if ("libraryLeft".equals(type)) {
            sendLibraryLeft(intOf(m, "id", -1));
            return;
        }
        edt.userAction(() -> {
            try {
                doAction(m);
            } catch (Throwable t) {
                System.err.println("[Nova] action failed: " + m);
                t.printStackTrace();
            }
        });
    }

    private void doAction(JsonObject m) {
        final String type = m.get("t").getAsString();
        final IGameController gc = getGameController();
        switch (type) {
            case "ok" -> { if (gc != null) gc.selectButtonOk(); }
            case "cancel" -> {
                if (freeMullNote && "InputConfirmMulligan".equals(currentInputType())) {
                    freeMullTaken = true; // the engine counts it as the free one (same rule, same hand)
                    freeMullNote = false;
                }
                if (gc != null) gc.selectButtonCancel();
            }
            case "card" -> {
                CardView c = findCard(intOf(m, "id", -1));
                if (c == null || gc == null) return;
                List<CardView> others = null;
                if (m.has("others")) {
                    others = new ArrayList<>();
                    for (JsonElement e : m.getAsJsonArray("others")) {
                        CardView o = findCard(e.getAsInt());
                        if (o != null) others.add(o);
                    }
                }
                abilityMenuJson = null;
                boolean handled = gc.selectCard(c, others, new Trigger(intOf(m, "btn", 1), intOf(m, "x", 0), intOf(m, "y", 0)));
                if (!handled && intOf(m, "btn", 1) == 1) {
                    send("{\"t\":\"cardRejected\",\"id\":" + c.getId() + "}");
                }
            }
            case "cards" -> {
                // Apply the same click to a whole group of identical permanents (shift-click on a pile).
                if (gc == null) return;
                for (JsonElement e : m.getAsJsonArray("ids")) {
                    CardView c = findCard(e.getAsInt());
                    if (c != null) gc.selectCard(c, null, new Trigger(1, 0, 0));
                }
            }
            case "player" -> {
                PlayerView p = findPlayer(intOf(m, "id", -1));
                if (p != null && gc != null) gc.selectPlayer(p, new Trigger(intOf(m, "btn", 1), 0, 0));
            }
            case "ability" -> {
                SpellAbilityView sa = abilityMenu.get(intOf(m, "id", -1));
                abilityMenuJson = null;
                rememberPicked(sa);
                if (sa != null && gc != null) gc.selectAbility(sa);
            }
            case "abilityCancel" -> abilityMenuJson = null;
            case "mana" -> { if (gc != null) gc.useMana((byte) intOf(m, "c", 0)); }
            case "attackAll" -> { if (gc != null) gc.alphaStrike(); }
            case "undo" -> { if (gc != null) gc.undoLastAction(); }
            case "passTurn" -> {
                if (gc instanceof PlayerControllerHuman pch) pch.autoPassUntilEndOfTurn();
                if (gc != null) gc.selectButtonOk();
            }
            case "cancelAutoPass" -> { if (gc instanceof PlayerControllerHuman pch) pch.autoPassCancel(); }
            case "stop" -> {
                PlayerView p = findPlayer(intOf(m, "player", -1));
                if (p == null) return;
                PhaseType ph = PhaseType.valueOf(m.get("phase").getAsString());
                EnumSet<PhaseType> set = phaseStops.computeIfAbsent(p.getId(), k -> EnumSet.noneOf(PhaseType.class));
                if (m.get("on").getAsBoolean()) set.add(ph);
                else set.remove(ph);
                savePhaseStops();
                sync.requestFlush();
            }
            case "yieldMarker" -> {
                PlayerView p = findPlayer(intOf(m, "player", -1));
                if (p == null) return;
                PhaseType ph = PhaseType.valueOf(m.get("phase").getAsString());
                handleYieldMarkerToggle(p, ph, () -> {
                    phaseStops.computeIfAbsent(p.getId(), k -> EnumSet.noneOf(PhaseType.class)).add(ph);
                    sync.requestFlush();
                });
            }
            case "autoYield" -> {
                if (gc != null && m.has("key")) {
                    gc.setShouldAutoYield(m.get("key").getAsString(), m.get("on").getAsBoolean(), false);
                }
            }
            case "reorderHand" -> {
                CardView c = findCard(intOf(m, "id", -1));
                if (c != null && gc != null) gc.reorderHand(c, intOf(m, "index", 0));
            }
            case "dev" -> devAction(gc, m.has("a") ? m.get("a").getAsString() : "");
            case "next" -> {
                NextGameDecision d = NextGameDecision.valueOf(m.get("decision").getAsString());
                gameOverJson = null;
                Collection<IGameController> ctrls = getOriginalGameControllers();
                if (ctrls.isEmpty() && gc != null) {
                    gc.nextGameDecision(d);
                } else {
                    for (IGameController c : ctrls) {
                        c.nextGameDecision(d);
                    }
                }
            }
            default -> System.err.println("[Nova] unknown action " + type);
        }
    }

    /** Stops streaming; used when the match is over or left. */
    public void dispose() {
        disposed = true;
        pickedAbility = null;
        cancelWaitingTimer();
        sync.shutdown();
        dialogs.unbindGame(this);
    }

    public boolean isDisposed() {
        return disposed;
    }

    /** For the spectator / debugging API. */
    public StateSync getSync() {
        return sync;
    }

    public List<PlayerView> getSortedPlayers() {
        return sortedPlayers;
    }
}

package forge.nova.gui;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameOutcome;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.match.YieldMarker;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.nova.net.ClientLink;
import forge.nova.sync.StateSync;
import forge.nova.util.JsonOut;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private volatile String gameOverJson;
    private Observer logObserver;
    /** the player chose "Back to main menu": the match is being torn down, so no game-over screen */
    private volatile boolean leaving;
    /** a finished or abandoned match keeps calling into its GUI for a while; it must not reach the client */
    private volatile boolean disposed;

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
    @Override public void updateTurn(PlayerView player) { sync.requestFlush(); }
    @Override public void updatePlayerControl() { sendMatchInfo(); sync.requestFlush(); }
    @Override public void updateStack() { sync.requestFlush(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { sync.requestFlush(); }
    @Override public void updateCards(Iterable<CardView> cards) { sync.requestFlush(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { sync.requestFlush(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { sync.requestFlush(); }
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
        promptMsg = message == null ? "" : message;
        promptCard = card == null ? -1 : card.getId();
        promptPlayer = playerView == null ? -1 : playerView.getId();
        inputType = currentInputType();
        sync.flushNow();
        sendPrompt();
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
        dialogs.message(message, title, false);
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

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        return dialogs.choose(message, min, max, choices, selected, display == null ? null : display::apply, null);
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                             List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                             boolean sideboardingMode, boolean showRememberCheckbox) {
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
        return dialogs.arrange(title, all, movable, toTop, toBottom, toAnywhere);
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
        edt.later(() -> {
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
            case "cancel" -> { if (gc != null) gc.selectButtonCancel(); }
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

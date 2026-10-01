import com.google.common.eventbus.Subscribe;
import forge.GuiDesktop;
import forge.card.CardDb;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Forge Nova benchmark / equivalence harness: one seeded AI-only game, like Forge's "sim" mode, but
 *  - the AI decision time limit is configurable (-timeout, default 1000 s, so no decision is ever cut short and
 *    two engine builds given the same seed must play exactly the same game),
 *  - optionally puts extra permanents from each library onto the battlefield at a given turn (big-board test),
 *  - optionally stages a Scute Swarm pile-up: at the start of a turn the active player gets N Scute Swarms,
 *    six Forests and a Forest in hand, so its land drop puts N landfall triggers on the stack, each of which
 *    copies a Scute Swarm while every player gets priority in between (huge stack + huge board test),
 *  - reports where the time went (per turn and per phase type) after the game log.
 *
 *   java -cp [nova-engine-patches.jar;]forge-gui-desktop-...jar;nova/bench/classes NovaSim
 *        -d "Deck A.dck" "Deck B.dck" ... [-f Commander] [-s seed] [-timeout 1000] [-clock 1800]
 *        [-inject turn:count] [-scute count:turn] [-maxturns N] [-q]
 */
public final class NovaSim {
    public static void main(String[] args) throws Exception {
        List<String> decks = new ArrayList<>();
        String format = "Commander";
        long seed = 1;
        int aiTimeout = 1000, clock = 1800, injectTurn = -1, injectCount = 0, maxTurns = Integer.MAX_VALUE;
        int scuteCount = 0, scuteTurn = -1;
        boolean quiet = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-d" -> {
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) decks.add(args[++i]);
                }
                case "-f" -> format = args[++i];
                case "-s" -> seed = Long.parseLong(args[++i]);
                case "-timeout" -> aiTimeout = Integer.parseInt(args[++i]);
                case "-clock" -> clock = Integer.parseInt(args[++i]);
                case "-maxturns" -> maxTurns = Integer.parseInt(args[++i]);
                case "-inject" -> {
                    String[] p = args[++i].split(":");
                    injectTurn = Integer.parseInt(p[0]);
                    injectCount = Integer.parseInt(p[1]);
                }
                case "-scute" -> {
                    String[] p = args[++i].split(":");
                    scuteCount = Integer.parseInt(p[0]);
                    scuteTurn = Integer.parseInt(p[1]);
                }
                case "-q" -> quiet = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        MyRandom.setRandom(new Random(seed));

        GameType type = GameType.valueOf(format);
        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        rules.setSimTimeout(clock);
        List<RegisteredPlayer> players = new ArrayList<>();
        int n = 1;
        for (String dn : decks) {
            String dir = type == GameType.Commander ? ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR;
            Deck d = DeckSerializer.fromFile(new File(dir + dn));
            if (d == null) {
                throw new IllegalArgumentException("deck not found: " + dir + dn);
            }
            RegisteredPlayer rp = type == GameType.Commander ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
            rp.setPlayer(GamePlayerUtil.createAiPlayer("Ai(" + n + ")-" + d.getName(), n - 1, ""));
            players.add(rp);
            n++;
        }
        Match match = new Match(rules, players, "NovaSim");
        final Game game = match.createGame();
        game.setNoGUIUser();
        game.AI_TIMEOUT = aiTimeout;

        final long t0 = System.nanoTime();
        final Timing timing = new Timing(game, t0, injectTurn, injectCount, scuteCount, scuteTurn, maxTurns);
        game.subscribeToEvents(timing);

        Thread runner = new Thread(() -> {
            try {
                match.startGame(game);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, "NovaSim game");
        runner.setDaemon(true);
        runner.start();
        runner.join(clock * 1000L);
        boolean timedOut = runner.isAlive();
        long total = System.nanoTime() - t0;
        timing.close(System.nanoTime());
        if (timedOut || !game.isGameOver()) {
            game.setGameOver(GameEndReason.Draw);
        }

        if (!quiet) {
            List<GameLogEntry> log = new ArrayList<>(game.getGameLog().getLogEntries(null));
            Collections.reverse(log);
            for (GameLogEntry l : log) {
                System.out.println(l);
            }
        }
        System.out.println();
        System.out.printf("[NovaSim] result: %s after turn %d%s%n",
                game.getOutcome() == null ? "?" : (game.getOutcome().isDraw() ? "draw" : game.getOutcome().getWinningLobbyPlayer().getName() + " won"),
                game.getPhaseHandler().getTurn(), timedOut ? " (CLOCK LIMIT)" : "");
        System.out.printf("[NovaSim] total %.2f s%n", total / 1e9);
        timing.report();
        System.out.flush();
        System.exit(0);
    }

    /** Wall time per turn and per phase type (the AI's thinking time lands in the phase it thinks in). */
    public static final class Timing {
        final Game game;
        final long t0;
        final int injectTurn, injectCount, scuteCount, scuteTurn, maxTurns;
        final Map<PhaseType, Long> perPhase = new EnumMap<>(PhaseType.class);
        final List<long[]> perTurn = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        PhaseType curPhase;
        long phaseStart, turnStart;
        int curTurn;

        Timing(Game game, long t0, int injectTurn, int injectCount, int scuteCount, int scuteTurn, int maxTurns) {
            this.game = game;
            this.t0 = t0;
            this.injectTurn = injectTurn;
            this.injectCount = injectCount;
            this.scuteCount = scuteCount;
            this.scuteTurn = scuteTurn;
            this.maxTurns = maxTurns;
        }

        void endPhase(long now) {
            if (curPhase != null) {
                perPhase.merge(curPhase, now - phaseStart, Long::sum);
                if (curTurn == scuteTurn || curTurn == scuteTurn + 1) {
                    notes.add(String.format("turn %d %s took %.2f s (battlefield %d)", curTurn, curPhase, (now - phaseStart) / 1e9,
                            game.getCardsIn(ZoneType.Battlefield).size()));
                }
            }
        }

        void close(long now) {
            endPhase(now);
            curPhase = null;
            if (curTurn > 0) {
                perTurn.add(new long[]{curTurn, now - turnStart});
            }
        }

        @Subscribe
        public void onPhase(GameEventTurnPhase e) {
            long now = System.nanoTime();
            endPhase(now);
            curPhase = e.phase();
            phaseStart = now;
        }

        @Subscribe
        public void onTurn(GameEventTurnBegan e) {
            long now = System.nanoTime();
            if (curTurn > 0) {
                perTurn.add(new long[]{curTurn, now - turnStart});
            }
            curTurn = e.turnNumber();
            turnStart = now;
            if (curTurn > maxTurns && !game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw); // the game loop stops at the next check
                return;
            }
            if (curTurn == injectTurn) {
                inject();
            }
            if (curTurn == scuteTurn) {
                scute();
            }
        }

        /** Puts the top injectCount permanent cards of each library onto the battlefield (deterministic). */
        void inject() {
            int added = 0;
            for (Player p : game.getPlayers()) {
                List<Card> lib = new ArrayList<>(p.getCardsIn(ZoneType.Library));
                int got = 0;
                for (Card c : lib) {
                    if (got >= injectCount) {
                        break;
                    }
                    if (!c.isPermanent() || c.isLand() && got % 3 != 0) {
                        continue;
                    }
                    game.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
                    c.setSickness(false);
                    got++;
                    added++;
                }
            }
            System.out.println("[NovaSim] injected " + added + " permanents at turn " + curTurn);
        }

        /** The active player gets scuteCount Scute Swarms, six Forests in play and a Forest in hand. */
        void scute() {
            long s = System.nanoTime();
            Player p = game.getPhaseHandler().getPlayerTurn();
            CardDb db = FModel.getMagicDb().getCommonCards();
            for (int i = 0; i < 6; i++) {
                Card land = Card.fromPaperCard(db.getCard("Forest"), p);
                land.setGameTimestamp(game.getNextTimestamp());
                game.getAction().moveTo(ZoneType.Battlefield, land, null, AbilityKey.newMap());
            }
            for (int i = 0; i < scuteCount; i++) {
                Card c = Card.fromPaperCard(db.getCard("Scute Swarm"), p);
                c.setGameTimestamp(game.getNextTimestamp());
                game.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
                c.setSickness(false);
            }
            Card inHand = Card.fromPaperCard(db.getCard("Forest"), p);
            game.getAction().moveTo(ZoneType.Hand, inHand, null, AbilityKey.newMap());
            String msg = String.format("[NovaSim] turn %d: %s got %d Scute Swarms, 6 Forests and a Forest in hand (setup %.2f s, at %.2f s)",
                    curTurn, p.getName(), scuteCount, (System.nanoTime() - s) / 1e9, (System.nanoTime() - t0) / 1e9);
            System.out.println(msg);
            notes.add(msg);
        }

        void report() {
            long sum = 0;
            for (long v : perPhase.values()) sum += v;
            System.out.printf("[NovaSim] phases total %.2f s%n", sum / 1e9);
            for (Map.Entry<PhaseType, Long> e : perPhase.entrySet()) {
                System.out.printf("[NovaSim] phase %-22s %8.2f s%n", e.getKey(), e.getValue() / 1e9);
            }
            StringBuilder sb = new StringBuilder("[NovaSim] turns:");
            for (long[] t : perTurn) {
                sb.append(String.format(" %d=%.1f", t[0], t[1] / 1e9));
            }
            System.out.println(sb);
            for (String note : notes) {
                System.out.println("[NovaSim] " + note.replace("[NovaSim] ", ""));
            }
        }
    }
}

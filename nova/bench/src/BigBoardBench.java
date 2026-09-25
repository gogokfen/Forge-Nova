import com.google.common.eventbus.Subscribe;
import forge.GuiDesktop;
import forge.card.CardDb;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Forge Nova "big board" AI benchmark.
 *
 * Starts a real AI-vs-AI game, puts N creatures (a varied mix) onto every battlefield at the start
 * of turn 2, then measures how long each following turn takes. This is the situation where
 * Forge's AI used to take minutes per turn (attack/block evaluation over large boards).
 *
 *   java -cp [nova-engine-patches.jar;]forge-gui-desktop-...jar;nova/bench/classes BigBoardBench 60 4 2
 *   args: creaturesPerPlayer turnsToMeasure players
 */
public final class BigBoardBench {
    static final String[] CREATURES = {"Grizzly Bears", "Hill Giant", "Serra Angel", "Giant Spider", "Llanowar Elves",
            "Wall of Omens", "Savannah Lions", "Craw Wurm", "Suntail Hawk", "Centaur Courser", "Sentinel Spider",
            "Air Elemental", "Trained Armodon", "Fugitive Wizard", "Coral Merfolk"};

    public static void main(String[] args) throws Exception {
        final int perPlayer = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        final int turns = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        final int players = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        MyRandom.setRandom(new Random(42));

        CardDb db = FModel.getMagicDb().getCommonCards();
        List<RegisteredPlayer> regs = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            Deck d = new Deck("Bench " + i);
            d.getOrCreate(DeckSection.Main).add(db.getCard("Forest"), 24);
            for (String n : CREATURES) {
                d.getOrCreate(DeckSection.Main).add(db.getCard(n), 3);
            }
            RegisteredPlayer rp = new RegisteredPlayer(d);
            rp.setPlayer(GamePlayerUtil.createAiPlayer("AI " + (i + 1), i));
            regs.add(rp);
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, regs, "BigBoard");
        final Game game = match.createGame();
        game.setNoGUIUser();

        final long[] turnStart = new long[64];
        final int[] turnSeen = {0};
        final boolean[] injected = {false};
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void onTurn(GameEventTurnBegan e) {
                int t = game.getPhaseHandler().getTurn();
                long now = System.nanoTime();
                if (t == 2 && !injected[0]) {
                    injected[0] = true;
                    int added = 0;
                    for (Player p : game.getPlayers()) {
                        for (int i = 0; i < perPlayer; i++) {
                            PaperCard pc = db.getCard(CREATURES[i % CREATURES.length]);
                            Card c = Card.fromPaperCard(pc, p);
                            c.setGameTimestamp(game.getNextTimestamp());
                            game.getAction().moveTo(ZoneType.Battlefield, c, null, AbilityKey.newMap());
                            c.setSickness(false);
                            added++;
                        }
                    }
                    System.out.println("[bench] put " + added + " creatures onto the battlefield");
                }
                if (t >= 2 && t < turnStart.length) {
                    turnStart[t] = now;
                    if (t > 2) {
                        double s = (now - turnStart[t - 1]) / 1e9;
                        System.out.printf("[bench] turn %d took %.2f s%n", t - 1, s);
                    }
                    turnSeen[0] = t;
                }
                if (t >= 2 + turns) {
                    double total = (now - turnStart[2]) / 1e9;
                    System.out.printf("[bench] TOTAL %d turns with %d creatures/player: %.2f s%n", turns, perPlayer, total);
                    System.out.flush();
                    System.exit(0);
                }
            }
        });
        long t0 = System.nanoTime();
        match.startGame(game);
        System.out.printf("[bench] game ended early after %.1f s (turn %d)%n", (System.nanoTime() - t0) / 1e9, turnSeen[0]);
        game.setGameOver(GameEndReason.Draw);
        System.exit(0);
    }
}

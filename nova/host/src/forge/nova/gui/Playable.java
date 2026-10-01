package forge.nova.gui;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.nova.sync.StateSync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The cards a player could play right now: spells and lands in hand, commanders and other cards castable
 * from outside the hand, activated abilities on the battlefield.
 *
 * The same test as Forge's "actionable highlights" ({@code forge.ai.AvailableActions}): the rules engine's list of
 * playable abilities (timing, land drops, "may play" effects), a cost Forge's AI believes the player can pay, and
 * legal targets. Nova runs it only when a priority prompt is actually shown, where Forge would run it every time
 * the player receives priority, and without Forge's fallback that marks every card once the time budget ran out.
 *
 * Must run while the game thread waits for this player's input (Forge does it on its UI thread too).
 */
final class Playable {
    private Playable() {
    }

    /**
     * @param giveWay true when a player's action waits (a click): the test stops and returns null, so it never
     *                delays anyone; the caller may try again afterwards. (Not any queued UI work: the test itself
     *                fires game events that queue some.)
     */
    static Set<CardView> collect(Player player, BooleanSupplier giveWay) {
        final Set<CardView> out = new LinkedHashSet<>();
        final long t0 = System.nanoTime();
        final long deadline = t0 + budgetMs(player) * 1_000_000L;
        final boolean[] stopped = {false};
        // Trying out payments briefly changes the game (mana leaves the pool and comes back, the player gets
        // an AI controller): the clients get the state from before and after, never the moments in between.
        StateSync.holdAll();
        try {
            // the AI's cost helpers expect an AI controller
            player.runWithController(() -> stopped[0] =
                    !scan(player, byManaValue(player.getCardsIn(ZoneType.Hand)), out, deadline, giveWay)
                    || !scan(player, byManaValue(player.getCardsIn(ZoneType.Flashback)), out, deadline, giveWay)
                    || !scan(player, player.getCardsIn(ZoneType.Battlefield), out, deadline, giveWay),
                    new PlayerControllerAi(player.getGame(), player, player.getOriginalLobbyPlayer()));
        } finally {
            StateSync.resumeAll();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms > 300) {
            System.out.println("[Nova] finding playable cards took " + ms + " ms" + (stopped[0] ? " (stopped)" : ""));
        }
        return stopped[0] ? null : out;
    }

    /** @return false when it had to give way */
    private static boolean scan(Player player, Iterable<Card> cards, Set<CardView> out, long deadline, BooleanSupplier giveWay) {
        for (Card c : cards) {
            if (giveWay.getAsBoolean()) {
                return false;
            }
            if (System.nanoTime() > deadline) {
                return true; // out of time: the cards found so far
            }
            CardView cv = c.getView();
            if (!out.contains(cv) && hasPlayable(c, player)) {
                out.add(cv);
            }
        }
        return true;
    }

    private static boolean hasPlayable(Card card, Player player) {
        try {
            for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
                if (sa.isManaAbility()) {
                    continue;
                }
                if (sa.getPayCosts() != null && sa.getPayCosts().hasManaCost()
                        && !ComputerUtilMana.canPayManaCost(sa, player, 0, false)) {
                    continue;
                }
                if (ComputerUtilAbility.isFullyTargetable(sa)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // a card script the AI helpers can't evaluate: leave it unmarked
        }
        return false;
    }

    /** cheap cards first, so they are marked even if the budget runs out */
    private static List<Card> byManaValue(Iterable<Card> cards) {
        List<Card> list = new ArrayList<>();
        for (Card c : cards) {
            list.add(c);
        }
        list.sort(Comparator.comparingInt(Card::getCMC));
        return list;
    }

    /** Forge's budget for the same test: its preference, else 50 ms per card (0.1 to 1.5 s). */
    private static long budgetMs(Player p) {
        try {
            int pref = Integer.parseInt(FModel.getPreferences().getPref(ForgePreferences.FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS));
            if (pref > 0) {
                return pref;
            }
        } catch (RuntimeException ignored) {
            // default budget
        }
        int n = p.getCardsIn(ZoneType.Hand).size() + p.getCardsIn(ZoneType.Battlefield).size();
        return Math.min(1500, Math.max(100, 50L * n));
    }
}

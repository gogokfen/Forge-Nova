package forge.nova.gui;

import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.nova.sync.StateSync;
import forge.util.Localizer;

/**
 * The most X a player's mana can pay for right now, for the Max button of the "choose X" window (as in MTG Arena).
 *
 * Forge asks for X before any cost is paid, with only a message ("Choose X for Fireball") and the rules' limits
 * (usually none). The ability is found from that message: the spell being cast (already moved to the stack but not
 * on it yet), or the ability the player just picked. Its cost is then tried with growing X the way Forge's AI and
 * its Auto pay button pay mana (untapped lands, mana abilities, floating mana, cost reductions).
 *
 * Runs on the game thread, which is waiting for the answer.
 */
final class AffordableX {
    /** a larger X is never tried */
    private static final int CAP = 999;
    private static final long BUDGET_MS = 600;

    private AffordableX() {
    }

    /** The ability a "choose X" question is about, or null. {@code picked}: the ability the player last picked. */
    static SpellAbility abilityFor(String message, Player player, SpellAbility picked) {
        if (message == null || player == null) {
            return null;
        }
        Game game = player.getGame();
        for (Card c : game.getCardsIn(ZoneType.Stack)) {
            SpellAbility sa = c.getCastSA();
            if (sa != null && sa.getActivatingPlayer() == player && asksX(message, sa)
                    && game.getStack().getInstanceMatchingSpellAbilityID(sa) == null) {
                return sa;
            }
        }
        return picked != null && picked.getActivatingPlayer() == player && asksX(message, picked) ? picked : null;
    }

    /** Forge's question for X of this ability ({@code PlayerControllerHuman.announceRequirements}) */
    private static boolean asksX(String message, SpellAbility sa) {
        Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        String title = sa.getParamOrDefault("XAnnounceTitle", "X");
        return message.equals(Localizer.getInstance().getMessage("lblChooseAnnounceForCard", title, host.getTranslatedName()));
    }

    /** "{X}{R}": the ability's mana cost, when X is part of it; else null. */
    static String manaCost(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null || !cost.hasManaCost() || cost.getCostMana().getAmountOfX() <= 0) {
            return null;
        }
        return cost.getTotalMana().getSimpleString();
    }

    /**
     * The most X (0 to {@code max}) the player can pay for {@code sa} now, or -1 when its X isn't paid with mana or
     * the AI's helpers can't tell.
     */
    static int of(SpellAbility sa, Player player, int max) {
        if (manaCost(sa) == null) {
            return -1;
        }
        final int hi = Math.min(max, CAP);
        if (hi <= 0) {
            return 0;
        }
        final int[] best = {-1};
        final long t0 = System.nanoTime();
        final long deadline = t0 + BUDGET_MS * 1_000_000L;
        // trying out payments briefly changes the game (see Playable): the clients get the state from before and
        // after, never the moments in between
        StateSync.holdAll();
        try {
            // the AI's cost helpers expect an AI controller (Forge's Auto pay button does the same)
            player.runWithController(() -> best[0] = search(sa, player, hi, deadline),
                    new PlayerControllerAi(player.getGame(), player, player.getOriginalLobbyPlayer()));
        } catch (RuntimeException e) {
            return -1; // a card script the AI's helpers can't evaluate: no Max
        } finally {
            StateSync.resumeAll();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms > 300) {
            System.out.println("[Nova] finding the most X took " + ms + " ms");
        }
        return best[0];
    }

    /** Doubles X until the cost can't be paid, then halves the gap (out of time: the most found so far). */
    private static int search(SpellAbility sa, Player p, int hi, long deadline) {
        if (!canPay(sa, p, 1)) {
            return 0;
        }
        int good = 1, bad = hi + 1;
        while (good < hi) {
            if (System.nanoTime() > deadline) {
                return good;
            }
            int probe = Math.min(hi, good * 2);
            if (canPay(sa, p, probe)) {
                good = probe;
            } else {
                bad = probe;
                break;
            }
        }
        while (bad - good > 1) {
            if (System.nanoTime() > deadline) {
                return good;
            }
            int mid = (good + bad) >>> 1;
            if (canPay(sa, p, mid)) {
                good = mid;
            } else {
                bad = mid;
            }
        }
        return good;
    }

    private static boolean canPay(SpellAbility sa, Player p, int x) {
        return ComputerUtilMana.canPayManaCost(sa, p, x, false);
    }
}

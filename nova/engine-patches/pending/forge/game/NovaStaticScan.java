package forge.game;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.TraitEpoch;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import forge.util.Visitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge Nova engine patch: the cards GameAction.checkStaticAbilities has to visit when it collects the continuous
 * static abilities of the whole game (every library, hand, graveyard and sideboard card, for every layer pass).
 *
 * For a visited card c, Forge takes co = preList.get(c) (c itself unless an equal card is in preList) and adds
 * co's Continuous static abilities that pass zonesCheck, co if its static command list is not empty, and co's
 * hidden Continuous statics that pass zonesCheck. A card that has no Continuous static ability, an empty static
 * command list and no possible hidden statics (no counter-keyword statics, not suspected), and has no equal card
 * in preList, adds nothing, and visiting it has no other effect (getStaticAbilities creates nothing; checkMode is
 * a set lookup). This index lists the positions of the other cards in forEachCardInGame(visitor, true) order;
 * {@link #scan} visits them, the positions of cards equal to a preList card, and each player's inbound tokens
 * (live: they are not zoned), in that order.
 *
 * Rebuilt when {@link TraitEpoch#global()} changes (zone contents/order; any zoned card's abilities, states,
 * static commands, counter statics or suspect status bump it), when the players change, or for another game.
 * With -Dnova.verifyCaches=true GameAction compares every result with Forge's full visit.
 */
final class NovaStaticScan {
    private static final int[] NONE = new int[0];

    private static final class Index {
        final Game game;
        final long epoch;
        final Player[] players;
        final Card[] cards;
        final int[] inboundAt;
        /** ascending positions of the cards whose visit may add something */
        final int[] visit;
        /** card (equal by id and class) -> its positions, for preList substitution */
        final Map<Card, int[]> positions;

        Index(Game game, long epoch, Player[] players, Card[] cards, int[] inboundAt, int[] visit, Map<Card, int[]> positions) {
            this.game = game;
            this.epoch = epoch;
            this.players = players;
            this.cards = cards;
            this.inboundAt = inboundAt;
            this.visit = visit;
            this.positions = positions;
        }
    }

    private static volatile Index last;

    private NovaStaticScan() {
    }

    /** Same visits as {@code game.forEachCardInGame(visitor, true)}, minus the visits that cannot add anything. */
    static void scan(Game game, CardCollectionView preList, Visitor<Card> visitor) {
        Index ix = index(game);
        int[] pos = ix.visit;
        if (!preList.isEmpty()) {
            pos = withPreList(ix, preList);
        }
        Card[] cards = ix.cards;
        int k = 0;
        for (int p = 0; p < ix.players.length; p++) {
            int end = ix.inboundAt[p];
            for (; k < pos.length && pos[k] < end; k++) {
                if (!visitor.visit(cards[pos[k]])) {
                    return;
                }
            }
            if (!visitor.visitAll(ix.players[p].getInboundTokens())) {
                return;
            }
        }
        for (; k < pos.length; k++) {
            if (!visitor.visit(cards[pos[k]])) {
                return;
            }
        }
    }

    /** ix.visit plus the positions of the cards equal to a preList card, ascending, without duplicates */
    private static int[] withPreList(Index ix, CardCollectionView preList) {
        int[] extra = NONE;
        for (Card pc : preList) {
            int[] at = ix.positions.get(pc);
            if (at != null) {
                int[] merged = Arrays.copyOf(extra, extra.length + at.length);
                System.arraycopy(at, 0, merged, extra.length, at.length);
                extra = merged;
            }
        }
        if (extra.length == 0) {
            return ix.visit;
        }
        int[] all = Arrays.copyOf(ix.visit, ix.visit.length + extra.length);
        System.arraycopy(extra, 0, all, ix.visit.length, extra.length);
        Arrays.sort(all);
        int n = 0;
        for (int i = 0; i < all.length; i++) {
            if (n == 0 || all[i] != all[n - 1]) {
                all[n++] = all[i];
            }
        }
        return Arrays.copyOf(all, n);
    }

    private static Index index(Game game) {
        long ep = TraitEpoch.global();
        Index ix = last;
        if (ix == null || ix.game != game || ix.epoch != ep || !samePlayers(ix.players, game)) {
            ix = build(game, ep);
            if (TraitEpoch.global() == ep) {
                last = ix;
            }
        }
        return ix;
    }

    private static boolean samePlayers(Player[] players, Game game) {
        int i = 0;
        for (Player p : game.getPlayers()) {
            if (i >= players.length || players[i] != p) {
                return false;
            }
            i++;
        }
        return i == players.length;
    }

    private static Index build(Game game, long ep) {
        List<Player> pl = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            pl.add(p);
        }
        Player[] players = pl.toArray(new Player[0]);
        // the visiting order of Game.forEachCardInGame(visitor, true), inbound tokens excluded
        List<Card> cards = new ArrayList<>();
        int[] inboundAt = new int[players.length];
        for (int p = 0; p < players.length; p++) {
            Player player = players[p];
            addAll(cards, player.getZone(ZoneType.Graveyard).getCards());
            addAll(cards, player.getZone(ZoneType.Hand).getCards());
            addAll(cards, player.getZone(ZoneType.Library).getCards());
            addAll(cards, player.getZone(ZoneType.Battlefield).getCards(false));
            addAll(cards, player.getZone(ZoneType.Exile).getCards());
            addAll(cards, player.getCardsIn(ZoneType.PART_OF_COMMAND_ZONE));
            addAll(cards, player.getZone(ZoneType.Sideboard).getCards());
            inboundAt[p] = cards.size();
        }
        addAll(cards, game.getStackZone().getCards());

        int n = cards.size();
        int[] visit = new int[n];
        int v = 0;
        Map<Card, int[]> positions = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            Card c = cards.get(i);
            int[] at = positions.get(c);
            if (at == null) {
                positions.put(c, new int[]{i});
            } else {
                int[] more = Arrays.copyOf(at, at.length + 1);
                more[at.length] = i;
                positions.put(c, more);
            }
            if (mayAdd(c)) {
                visit[v++] = i;
            }
        }
        return new Index(game, ep, players, cards.toArray(new Card[0]), inboundAt, Arrays.copyOf(visit, v), positions);
    }

    /** false only if visiting c (with no preList stand-in) cannot add anything */
    private static boolean mayAdd(Card c) {
        if (!c.getStaticCommandList().isEmpty() || c.novaMayHaveHiddenStatics()) {
            return true;
        }
        for (StaticAbility st : c.getStaticAbilities()) {
            if (st.checkMode(StaticAbilityMode.Continuous)) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(List<Card> to, Iterable<Card> from) {
        for (Card c : from) {
            to.add(c);
        }
    }
}

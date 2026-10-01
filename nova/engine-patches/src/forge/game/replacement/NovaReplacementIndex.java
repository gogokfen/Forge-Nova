package forge.game.replacement;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.TraitEpoch;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.Visitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge Nova engine patch: which cards ReplacementHandler.getReplacementList has to look at for one event.
 *
 * Forge answers "can this permanent untap / be tapped?", "would this combat damage be prevented?" and every
 * other replacement question by visiting every card in the game (all libraries, hands, graveyards...) and
 * testing each of its replacement effects. For an event other than Moved, visiting a card has an effect only if
 *  - one of its replacement effects has that event as its mode (every other effect fails modeCheck, which is a
 *    plain comparison, after tests without side effects), or
 *  - getReplacementEffects() may create effects on first use: shield/stun/finality counter effects
 *    (Card.hasCounterReplacementEffects) and Adventure/Omen effects (CardState.novaHasAdventureOrOmen).
 * This index lists, per event, the positions of those cards in Forge's visiting order, and
 * {@link #scan} visits exactly those cards, in that order, stopping where Forge stops. The cards' own
 * replacement lists are read at build time without side effects (the cached base list, as used by
 * getReplacementEffects(false)).
 *
 * Rebuilt when {@link TraitEpoch#global()} changes (zone contents/order, any zoned card's abilities, states or
 * types), when {@link TraitEpoch#replacementExtras()} changes (shield/stun/finality counters), when the players
 * in the game change, or for another game. Tokens waiting to enter (Player.getInboundTokens) are not zoned, so
 * they are always visited live, at their place in the order. With -Dnova.verifyCaches=true ReplacementHandler
 * compares every indexed result with Forge's full scan.
 */
final class NovaReplacementIndex {
    private static final int MODES = ReplacementType.values().length;
    private static final int[] NONE = new int[0];

    private static final class Index {
        final Game game;
        final long epoch;
        final long extras;
        final Player[] players;
        /** the cards forEachCardInGame(visitor, false) visits, in order, minus each player's inbound tokens */
        final Card[] cards;
        /** inboundAt[p] = how many entries of cards come before player p's inbound tokens */
        final int[] inboundAt;
        /** byMode[mode] = ascending positions in cards of the cards that may matter for that event */
        final int[][] byMode;

        Index(Game game, long epoch, long extras, Player[] players, Card[] cards, int[] inboundAt, int[][] byMode) {
            this.game = game;
            this.epoch = epoch;
            this.extras = extras;
            this.players = players;
            this.cards = cards;
            this.inboundAt = inboundAt;
            this.byMode = byMode;
        }
    }

    private static volatile Index last;

    private NovaReplacementIndex() {
    }

    /**
     * Same visits as {@code game.forEachCardInGame(visitor, false)} restricted to the cards that may matter for
     * {@code event} (which must not be Moved).
     */
    static void scan(Game game, ReplacementType event, Visitor<Card> visitor) {
        Index ix = index(game);
        int[] pos = ix.byMode[event.ordinal()];
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

    private static Index index(Game game) {
        long ep = TraitEpoch.global();
        long ex = TraitEpoch.replacementExtras();
        Index ix = last;
        if (ix == null || ix.game != game || ix.epoch != ep || ix.extras != ex || !samePlayers(ix.players, game)) {
            ix = build(game, ep, ex);
            if (TraitEpoch.global() == ep && TraitEpoch.replacementExtras() == ex) {
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

    private static Index build(Game game, long ep, long ex) {
        List<Player> pl = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            pl.add(p);
        }
        Player[] players = pl.toArray(new Player[0]);
        // the visiting order of Game.forEachCardInGame(visitor, false)
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
            inboundAt[p] = cards.size();
        }
        addAll(cards, game.getStackZone().getCards());

        int[] count = new int[MODES];
        int[][] pos = new int[MODES][];
        boolean[] modes = new boolean[MODES];
        int n = cards.size();
        List<int[]> perCard = new ArrayList<>(n); // per card: the modes it matters for (null = all)
        for (int i = 0; i < n; i++) {
            Card c = cards.get(i);
            if (c.hasCounterReplacementEffects() || c.getCurrentState().novaHasAdventureOrOmen()) {
                perCard.add(null); // visiting it may create replacement effects: visit it for every event
                for (int m = 0; m < MODES; m++) {
                    count[m]++;
                }
                continue;
            }
            java.util.Arrays.fill(modes, false);
            int distinct = 0;
            for (ReplacementEffect re : c.getCurrentState().getReplacementEffects(false)) {
                ReplacementType mode = re.getMode();
                if (mode != null && !modes[mode.ordinal()]) {
                    modes[mode.ordinal()] = true;
                    distinct++;
                }
            }
            if (distinct == 0) {
                perCard.add(NONE);
                continue;
            }
            int[] ms = new int[distinct];
            int j = 0;
            for (int m = 0; m < MODES; m++) {
                if (modes[m]) {
                    ms[j++] = m;
                    count[m]++;
                }
            }
            perCard.add(ms);
        }
        for (int m = 0; m < MODES; m++) {
            pos[m] = count[m] == 0 ? NONE : new int[count[m]];
            count[m] = 0;
        }
        for (int i = 0; i < n; i++) {
            int[] ms = perCard.get(i);
            if (ms == null) {
                for (int m = 0; m < MODES; m++) {
                    pos[m][count[m]++] = i;
                }
            } else {
                for (int m : ms) {
                    pos[m][count[m]++] = i;
                }
            }
        }
        return new Index(game, ep, ex, players, cards.toArray(new Card[0]), inboundAt, pos);
    }

    private static void addAll(List<Card> to, Iterable<Card> from) {
        for (Card c : from) {
            to.add(c);
        }
    }
}

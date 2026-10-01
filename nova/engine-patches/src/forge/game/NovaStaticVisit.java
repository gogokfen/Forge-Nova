package forge.game;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Visitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge Nova engine patch: the card walk of GameAction.checkStaticAbilities, without the cards whose visit cannot
 * add anything.
 *
 * Every static-ability pass visits every card in the game ({@code game.forEachCardInGame(visitor, true)}) to
 * collect the statics that are in effect; a Commander game runs hundreds of thousands of passes (the AI runs two
 * for each castable spell it considers while a static grants abilities to spells), and most visited cards sit in
 * libraries, graveyards and exile without any static ability. Visiting card {@code cx} adds something only if
 * {@code co = preList.get(cx)} has a static ability, a static command or hidden statics (see
 * GameAction.checkStaticAbilities); for a card of a zone that holds no card equal to a preList card, co is cx.
 *
 * For graveyards, hands, libraries, exile and sideboards this keeps, per zone, the zone's cards that have static
 * abilities, static commands or possibly hidden statics, in list order, and visits only those while the zone's
 * version ({@link Zone#novaVersion}) is unchanged. The version changes whenever the zone's list changes and
 * whenever a card whose zone is this zone bumps its trait epoch (static abilities, static commands and hidden
 * statics only change with such a bump). A zone listing a card whose zone is another one is never summarized.
 * Battlefields, command zones, inbound tokens and the stack are visited in full, as are zones holding a card equal
 * to a preList card. The visiting order is Forge's.
 */
public final class NovaStaticVisit {
    private static final class Summary {
        final long version;
        final Card[] cards;

        Summary(long version, Card[] cards) {
            this.version = version;
            this.cards = cards;
        }
    }

    private static final Card[] NONE = new Card[0];

    private NovaStaticVisit() {
    }

    /** Same visits as {@code game.forEachCardInGame(visitor, true)} for GameAction's collecting visitor. */
    static void visit(Game game, CardCollectionView preList, Visitor<Card> visitor) {
        for (Player player : game.getPlayers()) {
            zone(player.getZone(ZoneType.Graveyard), preList, visitor);
            zone(player.getZone(ZoneType.Hand), preList, visitor);
            zone(player.getZone(ZoneType.Library), preList, visitor);
            visitor.visitAll(player.getZone(ZoneType.Battlefield).getCards(false));
            zone(player.getZone(ZoneType.Exile), preList, visitor);
            visitor.visitAll(player.getCardsIn(ZoneType.PART_OF_COMMAND_ZONE));
            zone(player.getZone(ZoneType.Sideboard), preList, visitor);
            visitor.visitAll(player.getInboundTokens());
        }
        visitor.visitAll(game.getStackZone().getCards());
    }

    /** visitor.visitAll(z.getCards()), skipping cards whose visit adds nothing. */
    private static void zone(Zone z, CardCollectionView preList, Visitor<Card> visitor) {
        if (!preList.isEmpty()) {
            for (Card p : preList) {
                if (z.contains(p)) {
                    visitor.visitAll(z.getCards());
                    return;
                }
            }
        }
        final long ver = z.novaVersion();
        Object o = z.novaStaticSummary();
        if (o instanceof Summary s && s.version == ver) {
            for (Card c : s.cards) {
                visitor.visit(c);
            }
            return;
        }
        List<Card> relevant = new ArrayList<>();
        boolean consistent = true;
        for (Card c : z.getCards()) {
            if (!c.getStaticAbilities().isEmpty() || !c.getStaticCommandList().isEmpty() || c.novaMayHaveHiddenStatics()) {
                relevant.add(c);
            }
            if (c.getZone() != z) {
                consistent = false;
            }
            visitor.visit(c);
        }
        if (consistent && z.novaVersion() == ver) {
            z.novaSetStaticSummary(new Summary(ver, relevant.isEmpty() ? NONE : relevant.toArray(NONE)));
        }
    }
}

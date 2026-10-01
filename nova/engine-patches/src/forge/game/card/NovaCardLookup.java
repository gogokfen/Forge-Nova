package forge.game.card;

import java.util.Collections;
import java.util.HashMap;

/**
 * Forge Nova engine patch: {@code CardCollectionView.get(card)} answered from a hash map instead of a linear
 * search. FCollection.get returns the first element equal to the card (same id and class), or the card itself
 * when there is none; Forge calls it for every card in the game against the battlefield's last-known-state
 * list when checking replacement effects for a card entering the battlefield, which made each token entering
 * a big board cost (board size)^2 comparisons. The map is built once per collection (first occurrence wins,
 * like the linear search) and used only for one scan, during which the collection is not modified.
 * With -Dnova.verifyCaches=true every answer is compared with the linear search.
 */
public final class NovaCardLookup {
    private CardCollectionView coll;
    private HashMap<Card, Card> map;

    /** Same result as {@code coll.get(c)}. */
    public Card get(CardCollectionView coll, Card c) {
        if (c == null || coll.size() <= 16 || TraitEpoch.DISABLED) {
            return coll.get(c);
        }
        if (coll != this.coll) {
            HashMap<Card, Card> m = new HashMap<>(coll.size() * 2);
            for (Card x : coll) {
                m.putIfAbsent(x, x);
            }
            this.coll = coll;
            this.map = m;
        }
        Card found = this.map.get(c);
        Card result = found == null ? c : found;
        if (TraitEpoch.VERIFY) {
            Card fresh = coll.get(c);
            if (fresh != result) {
                TraitEpoch.mismatch("cardLookup", c, Collections.singletonList(result), Collections.singletonList(fresh));
            }
        }
        return result;
    }
}

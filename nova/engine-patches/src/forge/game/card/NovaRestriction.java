package forge.game.card;

import forge.card.CardType;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge Nova engine patch: a card restriction string ("Creature.YouCtrl+nonToken", "!Card.Self", ...)
 * split up once instead of on every {@link Card#isValid(String, forge.game.player.Player, Card, forge.game.CardTraitBase)}
 * call. Forge evaluates restrictions tens of millions of times per Commander game (every static ability
 * checks its "Affected" restriction against every card in its zones on every rules pass), and used to
 * split the string with regular expressions and look up the card type names each time.
 *
 * Everything here is a pure function of the restriction string: the same splits Forge performs
 * ({@code split("\\.", 2)}, then {@code split("\\+")}) and the same type-name lookups
 * {@code CardType.hasStringType} performs after its per-card subtype test.
 */
final class NovaRestriction {
    static final int SPELL = 1, PERMANENT = 2, EFFECT = 3, EMBLEM = 4, BOON = 5, ANY = 6, CARD = 7, TYPE = 8;

    /** leading '!' on the type part: the whole result is inverted (Forge's "testFailed") */
    final boolean negated;
    final int kind;
    /** the type part without '!', for kind == TYPE */
    final String type;
    /** CardType.CoreType.getEnum(StringUtils.capitalize(type)), as hasStringType computes it */
    final CardType.CoreType core;
    /** CardType.Supertype.getEnum(StringUtils.capitalize(type)), consulted by hasStringType when core == null */
    final CardType.Supertype sup;
    /** the '+'-separated properties after the first '.', or null when there is no '.' */
    final String[] props;

    private static final ConcurrentHashMap<String, NovaRestriction> CACHE = new ConcurrentHashMap<>();

    static NovaRestriction of(String restriction) {
        NovaRestriction r = CACHE.get(restriction);
        if (r == null) {
            r = new NovaRestriction(restriction);
            if (CACHE.size() > 50000) {
                CACHE.clear(); // restrictions built at runtime (e.g. "powerLE3") are unbounded in theory
            }
            CACHE.put(restriction, r);
        }
        return r;
    }

    private NovaRestriction(String restriction) {
        String[] incR = restriction.split("\\.", 2);
        boolean neg = false;
        if (incR[0].startsWith("!")) {
            neg = true;
            incR[0] = incR[0].substring(1);
        }
        this.negated = neg;
        String t = incR[0];
        switch (t) {
            case "Spell" -> this.kind = SPELL;
            case "Permanent" -> this.kind = PERMANENT;
            case "Effect" -> this.kind = EFFECT;
            case "Emblem" -> this.kind = EMBLEM;
            case "Boon" -> this.kind = BOON;
            case "card", "Card" -> this.kind = CARD;
            case "Any" -> this.kind = ANY;
            default -> this.kind = TYPE;
        }
        this.type = t;
        if (this.kind == TYPE && !t.isEmpty()) {
            String cap = StringUtils.capitalize(t);
            this.core = CardType.CoreType.getEnum(cap);
            this.sup = this.core == null ? CardType.Supertype.getEnum(cap) : null;
        } else {
            this.core = null;
            this.sup = null;
        }
        this.props = incR.length > 1 ? incR[1].split("\\+") : null;
    }
}

package forge.game.card;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge Nova engine patch: which "Affected" restrictions keep their answer for every card from the COLOR layer
 * (5) to the end of a static-ability pass (see forge.game.staticability.NovaLayerMemo).
 *
 * A restriction qualifies when every comma-separated part has a type part (a card type or subtype, "Card",
 * "Permanent", "Spell", "Any", "Effect", "Emblem", "Boon", possibly negated) and only properties from
 * {@link #STABLE} or of the form {@link #COUNTERS}, possibly negated. In {@link Card#isValid} the type part reads the card's current state name,
 * zone, immutability and type (types change only in the TYPE layer, 4); the properties are answered by
 * NovaProps.fast from phasing, the change-zone LKI's controller (CONTROL layer, 2), owner, token status, commander
 * status, the source's remembered objects and card identity; the counters form reads the card's counters. None of
 * these change in layers 5 to 8.
 */
public final class NovaLayerStable {
    /** exact property strings NovaProps.fast answers from layer-stable data */
    private static final Set<String> STABLE = Set.of("YouCtrl", "YouDontCtrl", "OppCtrl", "YouOwn", "token", "Self",
            "Other", "IsCommander", "IsRemembered");

    /**
     * "counters_GE12_CHARGE": CardProperty compares card.getCounters(type) with AbilityUtils.calculateAmount(source,
     * "12", sa), which for digits is the number itself (0 without a source); counters never change inside a pass.
     */
    private static final java.util.regex.Pattern COUNTERS = java.util.regex.Pattern.compile("counters_(LT|LE|EQ|NE|GE|GT)[0-9]+_[A-Za-z0-9]+");

    /**
     * "CastSa Spell.ManaFromTreasure": CardProperty (after 246 string tests) takes the card's cast SpellAbility and
     * SpellAbility.isValid checks root.isSpell(), then SpellAbilityProperty (after 62 string tests) counts the paying
     * mana whose source card passes the type restriction ("Treasure"): the recorded payment and the sources' types,
     * neither of which changes in layers 5 to 8 (ChainWalk, 2026-09-26). No "_" (that form computes an amount).
     */
    private static final java.util.regex.Pattern CAST_SA_MANA_FROM = java.util.regex.Pattern.compile("CastSa Spell[.]ManaFrom[A-Z][A-Za-z]*");

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private NovaLayerStable() {
    }

    public static boolean isStable(String affected) {
        if (affected == null) {
            return false;
        }
        Boolean b = CACHE.get(affected);
        if (b == null) {
            b = compute(affected);
            if (CACHE.size() > 20000) {
                CACHE.clear();
            }
            CACHE.put(affected, b);
        }
        return b;
    }

    private static boolean compute(String affected) {
        for (String part : affected.split(",")) {
            NovaRestriction r = NovaRestriction.of(part);
            if (r.kind == NovaRestriction.TYPE && r.type.isEmpty()) {
                return false;
            }
            if (r.props != null) {
                for (String p : r.props) {
                    String q = p.startsWith("!") ? p.substring(1) : p;
                    if (!STABLE.contains(q) && !COUNTERS.matcher(q).matches() && !CAST_SA_MANA_FROM.matcher(q).matches()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}

package forge.game.staticability;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.TraitEpoch;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge Nova engine patch: static abilities of all cards in the static-ability source zones,
 * grouped by mode, in exactly the order Forge's own scans visit them
 * ({@code for card in game.getCardsIn(STATIC_ABILITIES_SOURCE_ZONES) for stAb in card.getStaticAbilities()}).
 *
 * Rebuilt whenever {@link TraitEpoch#global()} changes (any zone membership/order change or any
 * card ability change), so hot rules checks look at the one or two relevant abilities instead
 * of scanning every permanent on a crowded battlefield. With -Dnova.verifyCaches=true every lookup
 * is compared against a fresh scan.
 */
public final class StaticAbilityIndex {
    /** Statics of one mode plus the card each was found on (parallel lists). */
    public static final class Entries {
        public final List<StaticAbility> statics = new ArrayList<>();
        public final List<Card> hosts = new ArrayList<>();
        static final Entries EMPTY = new Entries();
    }

    private static final class Holder {
        final Game game;
        final long epoch;
        final int players;
        final Map<StaticAbilityMode, Entries> byMode;
        final Set<Card> cards;
        final java.util.IdentityHashMap<Card, Long> cardEpochs;

        Holder(Game game, long epoch, Map<StaticAbilityMode, Entries> byMode, Set<Card> cards, java.util.IdentityHashMap<Card, Long> cardEpochs) {
            this.game = game;
            this.epoch = epoch;
            this.players = game.getPlayers().size(); // a player leaving the game shrinks getCardsIn()
            this.byMode = byMode;
            this.cards = cards;
            this.cardEpochs = cardEpochs;
        }
    }

    private static volatile Holder last;

    private StaticAbilityIndex() {
    }

    private static Holder holder(Game game) {
        long ep = TraitEpoch.global();
        Holder h = last;
        if (h == null || h.game != game || h.epoch != ep || h.players != game.getPlayers().size() || TraitEpoch.DISABLED) {
            h = build(game, ep);
            last = h;
        } else if (TraitEpoch.VERIFY) {
            Holder fresh = build(game, ep);
            if (TraitEpoch.global() != ep) {
                // another thread (e.g. an AI evaluation that outlived its time limit) changed the game
                // while we were rebuilding: the comparison would be meaningless
                return h;
            }
            for (StaticAbilityMode m : StaticAbilityMode.values()) {
                Entries a = h.byMode.getOrDefault(m, Entries.EMPTY), b = fresh.byMode.getOrDefault(m, Entries.EMPTY);
                if (!TraitEpoch.sameElements(a.statics, b.statics) || !TraitEpoch.sameElements(a.hosts, b.hosts)) {
                    TraitEpoch.mismatch("staticIndex:" + m, game, a.statics, b.statics);
                    describe(h, fresh);
                }
            }
        }
        return h;
    }

    /** Entries (statics + host cards) having {@code mode}, in Forge's scan order. Never null. */
    public static Entries entries(Game game, StaticAbilityMode mode) {
        if (!StaticAbilityModeRegistry.mayExist(mode)) {
            return Entries.EMPTY; // no static ability of this mode exists anywhere
        }
        Entries e = holder(game).byMode.get(mode);
        return e == null ? Entries.EMPTY : e;
    }

    /** Static abilities having {@code mode}, in Forge's scan order. Never null. */
    public static List<StaticAbility> forMode(Game game, StaticAbilityMode mode) {
        if (!StaticAbilityModeRegistry.mayExist(mode)) {
            return Collections.emptyList(); // no static ability of this mode exists anywhere
        }
        Entries e = holder(game).byMode.get(mode);
        return e == null ? Collections.emptyList() : e.statics;
    }

    /** Same membership test as {@code game.getCardsIn(STATIC_ABILITIES_SOURCE_ZONES).contains(card)}. */
    public static boolean containsCard(Game game, Card card) {
        return holder(game).cards.contains(card);
    }

    private static Holder build(Game game, long ep) {
        Map<StaticAbilityMode, Entries> m = new EnumMap<>(StaticAbilityMode.class);
        Set<Card> cards = new HashSet<>();
        java.util.IdentityHashMap<Card, Long> epochs = new java.util.IdentityHashMap<>();
        for (Card c : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            cards.add(c);
            epochs.put(c, c.getTraitEpoch());
            for (StaticAbility st : c.getStaticAbilities()) {
                if (st.getMode() == null) {
                    continue;
                }
                for (StaticAbilityMode mode : st.getMode()) {
                    Entries e = m.computeIfAbsent(mode, k -> new Entries());
                    e.statics.add(st);
                    e.hosts.add(c);
                }
            }
        }
        return new Holder(game, ep, m, cards, epochs);
    }

    /** Verification diagnostics: explain how the stale index differs from a fresh scan. */
    private static void describe(Holder cached, Holder fresh) {
        for (Card c : fresh.cardEpochs.keySet()) {
            Long before = cached.cardEpochs.get(c);
            if (before == null) {
                System.err.println("   + card not in cached index: " + c + " zone=" + c.getZone() + " phasedOut=" + c.isPhasedOut() + " epoch=" + c.getTraitEpoch());
            } else if (before != c.getTraitEpoch()) {
                System.err.println("   ~ card epoch changed " + before + "->" + c.getTraitEpoch() + ": " + c);
            }
        }
        for (Card c : cached.cardEpochs.keySet()) {
            if (!fresh.cardEpochs.containsKey(c)) {
                System.err.println("   - card gone from static zones: " + c + " zone=" + c.getZone() + " phasedOut=" + c.isPhasedOut());
            }
        }
    }
}

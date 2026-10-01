package forge.game.staticability;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;

import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * Forge Nova engine patch: remembers StaticAbilityContinuous.getAffectedCards answers within one layer of one
 * GameAction.checkStaticAbilities pass.
 *
 * Inside a layer, Forge asks for the same static's affected cards over and over: once per dependency trial of
 * every other static in the layer (findStaticAbilityToApply tries each pair), again for the real application,
 * and again in the next step. A pass processes the layers in order and, within a layer, only applies (and
 * removes, for the trials) that layer's changes: card types change only in the TYPE layer (4), controllers only in
 * the CONTROL layer (2), and no layer moves cards between zones, phases them or changes tokens, owners, commanders
 * or remembered objects. So from the COLOR layer (5) on, a restriction that tests only those things (see
 * {@link forge.game.card.NovaRestriction#isStableAfterTypeLayer}) has the same answer for every card for the rest
 * of the layer, and the candidate cards (zone contents, the pass's preList) are the same too.
 *
 * The answers stay valid from the COLOR layer to the end of the pass (layers 5 to 8), so they are kept across those
 * layers. {@link #passBegin}/{@link #passEnd} bracket each pass and {@link #begin}/{@link #end} each layer; answers
 * are only used and stored inside a layer. A nested pass (started from inside a layer) ends the outer pass's memo:
 * nothing remembered before it is used after it. Per thread: the AI may run passes on its own thread.
 * With -Dnova.verifyCaches=true every remembered answer is compared with a fresh computation.
 */
public final class NovaLayerMemo {
    private static final class State {
        long token; // 0 = nothing remembered
        long tokenPass; // the pass the token belongs to
        long pass; // changes whenever a pass starts or ends (so a nested pass ends the outer pass's memo)
        boolean inLayer; // between begin and end (answers are used and stored only then)
        long next = 1;
        final IdentityHashMap<StaticAbility, Entry> map = new IdentityHashMap<>();
        final HashMap<ValidKey, ValidEntry> valid = new HashMap<>();
    }

    /** CardLists.getValidCards arguments that decide each card's answer (the restriction must be layer-stable). */
    private static final class ValidKey {
        final String restriction;
        final Player sourceController;
        final Card source;

        ValidKey(String restriction, Player sourceController, Card source) {
            this.restriction = restriction;
            this.sourceController = sourceController;
            this.source = source;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ValidKey k && k.restriction.equals(this.restriction) && k.sourceController == this.sourceController && k.source == this.source;
        }

        @Override
        public int hashCode() {
            return this.restriction.hashCode() * 31 + System.identityHashCode(this.sourceController) * 17 + System.identityHashCode(this.source);
        }
    }

    private static final class ValidEntry {
        final long token;
        final Card[] input;
        final boolean[] mask;

        ValidEntry(long token, Card[] input, boolean[] mask) {
            this.token = token;
            this.input = input;
            this.mask = mask;
        }
    }

    private static final class Entry {
        final long token;
        final CardCollectionView preList;
        final Card[] cards;

        Entry(long token, CardCollectionView preList, Card[] cards) {
            this.token = token;
            this.preList = preList;
            this.cards = cards;
        }
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private NovaLayerMemo() {
    }

    /** checkStaticAbilities starts a pass (the layers follow). */
    public static void passBegin() {
        State s = STATE.get();
        s.pass = s.next++;
        s.token = 0L;
        s.inLayer = false;
        s.map.clear();
        s.valid.clear();
    }

    /** checkStaticAbilities ends a pass (normally or not). */
    public static void passEnd() {
        passBegin();
    }

    /**
     * checkStaticAbilities starts processing {@code layer}. From COLOR on, the answers remembered in the previous
     * layers of the same pass stay valid (the data they depend on changes only in the layers before COLOR).
     */
    public static void begin(StaticAbilityLayer layer) {
        State s = STATE.get();
        if (layer.ordinal() < StaticAbilityLayer.COLOR.ordinal()) {
            s.token = 0L;
            s.map.clear();
            s.valid.clear();
        } else if (s.token == 0L || s.tokenPass != s.pass) {
            s.token = s.next++;
            s.tokenPass = s.pass;
            s.map.clear();
            s.valid.clear();
        }
        s.inLayer = true;
    }

    /** checkStaticAbilities is done with the layer. */
    public static void end() {
        STATE.get().inLayer = false;
    }

    /** The remembered affected cards (before the ignore-effect removal), or null. */
    static Card[] get(StaticAbility stAb, CardCollectionView preList) {
        State s = STATE.get();
        if (s.token == 0L || !s.inLayer) {
            return null;
        }
        Entry e = s.map.get(stAb);
        return e != null && e.token == s.token && e.preList == preList ? e.cards : null;
    }

    static void put(StaticAbility stAb, CardCollectionView preList, Card[] cards) {
        State s = STATE.get();
        if (s.token != 0L && s.inLayer) {
            s.map.put(stAb, new Entry(s.token, preList, cards));
        }
    }

    public static boolean active() {
        State s = STATE.get();
        return s.token != 0L && s.inLayer;
    }

    /**
     * For CardLists: which cards of {@code input} passed the (layer-stable) restriction the last time it was
     * evaluated in this layer with the same source and controller over the same cards (same objects, same order),
     * or null.
     */
    public static boolean[] validMask(String restriction, Player sourceController, Card source, Card[] input) {
        State s = STATE.get();
        if (s.token == 0L || !s.inLayer) {
            return null;
        }
        ValidEntry e = s.valid.get(new ValidKey(restriction, sourceController, source));
        if (e == null || e.token != s.token || e.input.length != input.length) {
            return null;
        }
        for (int i = 0; i < input.length; i++) {
            if (e.input[i] != input[i]) {
                return null;
            }
        }
        return e.mask;
    }

    public static void putValidMask(String restriction, Player sourceController, Card source, Card[] input, boolean[] mask) {
        State s = STATE.get();
        if (s.token != 0L && s.inLayer) {
            s.valid.put(new ValidKey(restriction, sourceController, source), new ValidEntry(s.token, input, mask));
        }
    }
}

package forge.game.staticability;

import forge.game.card.TraitEpoch;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Forge Nova engine patch: the static ability modes that exist at all.
 *
 * Every StaticAbility gets its modes from {@link StaticAbilityMode#setValueOf} when it is created (copies
 * reuse the same modes), and that method reports them here. So while a mode has never been reported, no
 * card anywhere can have a static ability of that mode, and every "is there any static ability of mode X
 * that applies?" scan over the whole board has a known answer without scanning. Most modes (turn order
 * reversed, can't gain life, disable triggers...) never occur in a game, yet Forge scans for them in its
 * hottest loops. A mode that has appeared once stays "seen" (the full scan runs as before).
 *
 * With -Dnova.verifyCaches=true, {@link StaticAbility#checkMode} reports any static ability whose mode was
 * not registered (it would mean a mode was set some other way and the shortcut could be wrong).
 */
public final class StaticAbilityModeRegistry {
    private static volatile EnumSet<StaticAbilityMode> seen = EnumSet.noneOf(StaticAbilityMode.class);

    private StaticAbilityModeRegistry() {
    }

    static void note(Set<StaticAbilityMode> modes) {
        if (seen.containsAll(modes)) {
            return;
        }
        synchronized (StaticAbilityModeRegistry.class) {
            EnumSet<StaticAbilityMode> next = EnumSet.copyOf(seen);
            next.addAll(modes);
            seen = next;
        }
    }

    /** False only when no static ability with this mode was ever created, so none can exist. */
    public static boolean mayExist(StaticAbilityMode mode) {
        return TraitEpoch.DISABLED || seen.contains(mode);
    }

    public static boolean mayExist(StaticAbilityMode a, StaticAbilityMode b) {
        return mayExist(a) || mayExist(b);
    }

    public static boolean mayExist(StaticAbilityMode a, StaticAbilityMode b, StaticAbilityMode c) {
        return mayExist(a) || mayExist(b) || mayExist(c);
    }

    static void verifySeen(StaticAbilityMode mode, StaticAbility stAb) {
        if (!seen.contains(mode)) {
            TraitEpoch.mismatch("staticModeRegistry:" + mode, stAb.getHostCard(), Collections.emptyList(), Collections.singletonList(stAb));
        }
    }
}

package forge.player;

import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

import java.util.Map;

/**
 * Nova's window into {@link PlayerControllerHuman}: the ability behind the view the player picked. Forge keeps that
 * map in a protected field, which only its own package can read.
 */
public final class NovaControllerAccess {
    private NovaControllerAccess() {
    }

    /** The ability of {@code view} from the player's last list of abilities to choose from, or null. */
    public static SpellAbility abilityOf(PlayerControllerHuman pch, SpellAbilityView view) {
        if (pch == null || view == null) {
            return null;
        }
        Map<SpellAbilityView, SpellAbility> cache = pch.spellViewCache;
        return cache == null ? null : cache.get(view);
    }
}

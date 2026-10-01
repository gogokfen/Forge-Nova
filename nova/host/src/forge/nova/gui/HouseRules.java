package forge.nova.gui;

/**
 * Nova's house rules. The engine side lives in the engine patches (MulliganService reads the system property), so a
 * rule only works while those patches are loaded: the launcher skips them after a Forge update until Nova is rebuilt.
 */
public final class HouseRules {
    /** "true": the first hand a player sends back with no lands or seven lands is a free mulligan (once per game) */
    public static final String FREE_MULLIGAN = "nova.houseRule.freeMulligan";

    private static volatile Boolean patched;

    private HouseRules() {
    }

    /** Sets the rules for the match about to start (one match runs at a time). */
    public static void apply(boolean freeMulligan) {
        System.setProperty(FREE_MULLIGAN, String.valueOf(freeMulligan && freeMulliganAvailable()));
    }

    /** The engine patch that implements the free mulligan is loaded. */
    public static boolean freeMulliganAvailable() {
        Boolean p = patched;
        if (p == null) {
            try {
                Class.forName("forge.game.mulligan.MulliganService").getDeclaredMethod("novaFreeMulligan", forge.game.player.Player.class);
                p = true;
            } catch (ReflectiveOperationException | LinkageError e) {
                p = false;
            }
            patched = p;
        }
        return p;
    }

    /** The free mulligan applies in the current match. */
    public static boolean freeMulliganActive() {
        return Boolean.getBoolean(FREE_MULLIGAN);
    }
}

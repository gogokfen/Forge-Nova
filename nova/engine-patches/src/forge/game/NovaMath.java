package forge.game;

/**
 * Forge Nova: int arithmetic that stops at the int limits instead of wrapping around. Forge keeps power, toughness,
 * counters, damage and life in ints; a power doubled past 2,147,483,647 used to come out negative or exactly 0 (and
 * dealt no damage), two huge attackers' damage added up to a negative number, and lifelink from a huge creature made
 * its controller's life negative. Below the limits these give exactly the same results as plain + - *.
 */
public final class NovaMath {
    private NovaMath() {
    }

    public static int sat(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : v < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) v;
    }

    /** Also the Integer::sum of Map.merge calls. */
    public static int add(int a, int b) {
        return sat((long) a + b);
    }

    public static int sub(int a, int b) {
        return sat((long) a - b);
    }

    public static int mul(int a, int b) {
        return sat((long) a * b);
    }
}

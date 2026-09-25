package forge.game.card;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forge Nova engine patch: version counters that make ability lookups cacheable.
 *
 * Forge used to rebuild a card's static-ability / replacement-effect lists on every call
 * (allocating collections and re-applying every trait-changing effect). The AI asks for these
 * lists millions of times per decision on big boards, which made large Commander games crawl.
 *
 * Every mutation that can change a card's abilities bumps that card's epoch and the global
 * epoch; caches are keyed by these numbers, so a stale cache can never be returned as long as
 * all mutation points bump (verified by running with -Dnova.verifyCaches=true, which recomputes
 * every cached answer the original way and reports any difference).
 */
public final class TraitEpoch {
    private static final AtomicLong GLOBAL = new AtomicLong(1);

    /** -Dnova.verifyCaches=true: compare every cache hit with a fresh computation. */
    public static final boolean VERIFY = Boolean.getBoolean("nova.verifyCaches");
    /** -Dnova.disableCaches=true: behave exactly like upstream Forge (for A/B benchmarking). */
    public static final boolean DISABLED = Boolean.getBoolean("nova.disableCaches");

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    public static final AtomicLong HITS = new AtomicLong();
    public static final AtomicLong MISSES = new AtomicLong();

    private TraitEpoch() {
    }

    public static long global() {
        return GLOBAL.get();
    }

    public static void bumpGlobal() {
        GLOBAL.incrementAndGet();
    }

    /** Reports a cache inconsistency once per call site (verification mode only). */
    public static void mismatch(String what, Object host, List<?> cached, List<?> fresh) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        String site = what + "@" + (st.length > 2 ? st[2] : "?");
        if (REPORTED.add(site)) {
            System.err.println("[Nova] CACHE MISMATCH " + what + " for " + host);
            System.err.println("   cached: " + cached);
            System.err.println("   fresh : " + fresh);
            new Throwable("cache mismatch").printStackTrace();
        }
    }

    /** Element-wise identity comparison of two ability lists (order matters). */
    public static boolean sameElements(Iterable<?> a, Iterable<?> b) {
        java.util.Iterator<?> ia = a.iterator(), ib = b.iterator();
        while (ia.hasNext() && ib.hasNext()) {
            if (ia.next() != ib.next()) {
                return false;
            }
        }
        return !ia.hasNext() && !ib.hasNext();
    }

    public static List<Object> toList(Iterable<?> it) {
        List<Object> l = new java.util.ArrayList<>();
        for (Object o : it) {
            l.add(o);
        }
        return l;
    }
}

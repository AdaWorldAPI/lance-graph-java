package com.adaworldapi.bricks;

import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.Diagnostics;
import com.adaworldapi.lancegraph.NativeRuntime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * The falsifier suite for the mask-first, fail-closed authorization consumer example.
 *
 * <p>Mirrors {@code TradesParityTest}'s shape (main + {@link #run}, a transcribed SplitMix64
 * generator with its own provenance comment, {@code Diagnostics.crossings()} as the laziness
 * instrument) but proves a different, sharper thesis: <strong>a query never reads a row before it
 * is authorized</strong>, and that "never" is checked three separate ways — by value (two roles
 * see two different, correctly-related answers), by control flow (an unauthorized terminal throws
 * before doing any work), and by crossing count (composing {@code .authorize(...)} costs the
 * membrane nothing; only the terminal does, and its cost does not grow with role complexity).
 *
 * <ol>
 *   <li><strong>Two-sided role discrimination</strong> — {@code Role.GLOBAL}, {@code
 *       Role.EU_ONLY}, and {@code Role.DENY_ALL} produce three different, independently
 *       predicted counts over the identical base query, agreeing with a pure-Java recomputation
 *       that never touches {@code BricksQuery} or the native library.
 *   <li><strong>Fail-closed, both directions</strong> — a terminal without {@code authorize(...)}
 *       throws {@code UnauthorizedQueryException} (checked on all three terminals: {@code
 *       count()}, {@code sum()}, {@code sumBy()}); the identical chain with {@code authorize(...)}
 *       present does not throw and returns the predicted answer, in either composition order.
 *   <li><strong>Compose-not-execute</strong> — {@code Diagnostics.crossings()} does not move while
 *       {@code .where(...)}/{@code .authorize(...)} are composed, only when a terminal runs — the
 *       strongest checkable form of "nothing crosses until the fully-clamped plan does."
 *   <li><strong>{@code sumBy} parity</strong> — every one of its 16 returned per-region sums
 *       agrees with an independent pure-Java recomputation, and the 16 sums are internally
 *       consistent with the grand total.
 *   <li><strong>Aggregate-only egress</strong> — {@code BricksQuery}'s public surface returns only
 *       {@code BricksQuery}, {@code long}, or {@code Map}; {@code Orders} is a schema, never an
 *       entity, exactly as {@code Trade} is proven to be in {@code TradesParityTest}.
 * </ol>
 */
public final class BricksAuthTest {

    private BricksAuthTest() {}

    public static void main(String[] args) {
        System.out.println("BricksAuthTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("BricksAuthTest"));
        }
        Checks c = new Checks("BricksAuthTest");
        run(c);
        System.exit(c.report());
    }

    /**
     * Same seed as {@code TradesParityTest} — chosen there to match {@code
     * NativePattern.DEFAULT_SEED} (docs/abi.md's normative fixture seed), reused here so this
     * file's numbers are cross-checkable against the rest of the corpus.
     */
    private static final long SEED = 0xABCDL;

    /**
     * SplitMix64, transcribed from the normative fixture generator. Provenance: identical
     * constant-for-constant, shift-for-shift transcription already checked against the published
     * reference vector in {@code FixtureParityTest} and re-verified in {@code TradesParityTest}.
     * Duplicated (not shared) because the original is {@code private} to a different compile unit
     * — see {@code TradesParityTest}'s own copy for the same reasoning, applied here to the Bricks
     * consumer module.
     */
    private static final class SplitMix64 {
        private long state;

        SplitMix64(long seed) {
            this.state = seed;   // no warm-up draws
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    /**
     * {@code (region, revenue)} per row, recomputed independently in pure Java from the fixture
     * generator's published shape: two SplitMix64 draws per row, the first then the second, with
     * {@code region = (draw1 >>> 33) & 0xF} and {@code revenue = ((draw2 >>> 40) & 0x1FF) - 150}
     * — {@code Orders.REGION} binds the same lane-1 U32 tag {@code Trade.VENUE} does, and {@code
     * Orders.REVENUE} the same lane-2 I32 value {@code Trade.PRICE} does, so the derivation is
     * identical, renamed to match this schema's domain.
     *
     * <p>Named {@code Fixture} rather than {@code Orders} deliberately: this file also needs the
     * unqualified name {@code Orders} to resolve to the real schema class ({@code Orders.REGION},
     * {@code Orders.EU}, {@code Orders.REVENUE}) throughout, and a nested type sharing that simple
     * name would shadow it within this compilation unit — the same reason {@code TradesParityTest}
     * names its own recomputation record {@code Trades}, not {@code Trade}.
     */
    private record Fixture(int[] regions, int[] revenues) {}

    private static Fixture generate(int nRows, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        int[] regions = new int[nRows];
        int[] revenues = new int[nRows];
        for (int i = 0; i < nRows; i++) {
            long draw1 = rng.next();   // FIRST draw of the row
            long draw2 = rng.next();   // SECOND draw of the row
            regions[i] = (int) ((draw1 >>> 33) & 0xF);
            revenues[i] = (int) (((draw2 >>> 40) & 0x1FF) - 150);
        }
        return new Fixture(regions, revenues);
    }

    public static void run(Checks c) {
        checkRoleDiscrimination(c);
        checkFailClosed(c);
        checkComposeNotExecute(c);
        checkSumByParity(c);
        checkAggregateOnlyEgress(c);
    }

    // ------------------------------------------------------------------
    // 1. Two-sided role discrimination
    // ------------------------------------------------------------------

    private static void checkRoleDiscrimination(Checks c) {
        c.section("two-sided role discrimination at n = 64,000");

        final int rows = 64_000;
        Fixture expected = generate(rows, SEED);

        long expectAll = 0;      // (a): REVENUE > 100, no role constraint
        long expectEu = 0;       // (b): REVENUE > 100 AND REGION == EU
        for (int i = 0; i < rows; i++) {
            if (expected.revenues()[i] > 100) {
                expectAll++;
                if (expected.regions()[i] == Orders.EU) {
                    expectEu++;
                }
            }
        }

        try (BricksSession session = Bricks.open(rows, SEED)) {
            long actualGlobal = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL)
                    .count();
            long actualEu = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.EU_ONLY)
                    .count();
            long actualDeny = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.DENY_ALL)
                    .count();

            c.eq("Role.GLOBAL: REVENUE > 100, no constraint, vs pure-Java recomputation",
                    expectAll, actualGlobal);
            c.eq("Role.EU_ONLY: REVENUE > 100 AND REGION == EU, vs pure-Java recomputation",
                    expectEu, actualEu);
            c.eq("Role.DENY_ALL: an unmatchable native predicate selects exactly zero rows",
                    0, actualDeny);

            c.that("EU count is not vacuous (count > 0)", expectEu > 0);
            c.that("EU is a genuine subset, strictly fewer rows than GLOBAL (count < GLOBAL)",
                    expectEu < expectAll);
            c.that("GLOBAL itself does not select every row (count < n)", expectAll < rows);
        }
    }

    // ------------------------------------------------------------------
    // 2. Fail-closed: can-fire (throws without authorize) and its silence twin
    //    (no throw, correct answer, with authorize) — both orders of composition.
    // ------------------------------------------------------------------

    private static void checkFailClosed(Checks c) {
        c.section("fail-closed terminals — can-fire half (no authorize -> throws)");

        final int rows = 64_000;
        try (BricksSession session = Bricks.open(rows, SEED)) {
            c.throwsUp("count() without authorize() throws UnauthorizedQueryException",
                    UnauthorizedQueryException.class,
                    () -> session.query().where(Orders.REVENUE.gt(100)).count());
            c.throwsUp("sum() without authorize() throws UnauthorizedQueryException",
                    UnauthorizedQueryException.class,
                    () -> session.query().where(Orders.REVENUE.gt(100)).sum(Orders.REVENUE));
            c.throwsUp("sumBy() without authorize() throws UnauthorizedQueryException",
                    UnauthorizedQueryException.class,
                    () -> session.query().where(Orders.REVENUE.gt(100))
                            .sumBy(Orders.REGION, Orders.REVENUE));
        }

        c.section("fail-closed terminals — silence twin (authorize present -> no throw)");

        Fixture expected = generate(rows, SEED);
        long expectGlobalCount = 0;
        for (int i = 0; i < rows; i++) {
            if (expected.revenues()[i] > 100) {
                expectGlobalCount++;
            }
        }

        try (BricksSession session = Bricks.open(rows, SEED)) {
            long counted = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL)
                    .count();
            c.eq("with authorize() present, count() does not throw and matches the recomputation",
                    expectGlobalCount, counted);

            // sum()/sumBy() must be reachable too, not merely count() — a design that special-cased
            // only count()'s guard would pass the can-fire test above by accident.
            long summed = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL)
                    .sum(Orders.REVENUE);
            c.that("sum() with authorize() present does not throw (reached this line)", true);
            c.that("sum() with authorize() present returns a real, non-trivially-zero total",
                    summed != 0);

            Map<Integer, Long> grouped = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL)
                    .sumBy(Orders.REGION, Orders.REVENUE);
            c.that("sumBy() with authorize() present does not throw (reached this line)", true);
            c.eq("sumBy() with authorize() present reports all 16 regions", 16, grouped.size());
        }

        c.section("authorize() composition order does not matter");

        try (BricksSession session = Bricks.open(rows, SEED)) {
            long whereThenAuthorize = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.EU_ONLY)
                    .count();
            long authorizeThenWhere = session.query()
                    .authorize(Role.EU_ONLY)
                    .where(Orders.REVENUE.gt(100))
                    .count();
            c.eq(".where(...).authorize(...) vs .authorize(...).where(...) — identical count",
                    whereThenAuthorize, authorizeThenWhere);
        }
    }

    // ------------------------------------------------------------------
    // 3. Compose-not-execute: crossings stay 0 through where()/authorize(), and the exact
    //    crossing cost of each terminal (count(): 1; sumBy(): 16, group-count-scaled, never
    //    row-count-scaled).
    // ------------------------------------------------------------------

    private static void checkComposeNotExecute(Checks c) {
        c.section("compose-not-execute (Diagnostics.crossings())");

        final int rows = 64_000;
        try (BricksSession session = Bricks.open(rows, SEED)) {
            long before = Diagnostics.crossings();
            BricksQuery chain = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.EU_ONLY);
            long afterBuild = Diagnostics.crossings();

            c.eq("composing .where(...).authorize(...) costs no crossings — "
                            + "NOTHING crosses before the fully-clamped plan does",
                    0, afterBuild - before);

            // Warm the resource's reusable scratch selection so the steady-state cost is what is
            // measured, matching LazinessTest's own discipline.
            chain.count();

            long c0 = Diagnostics.crossings();
            chain.count();
            long terminalCost = Diagnostics.crossings() - c0;
            c.eq("count() over an authorized chain costs exactly one crossing",
                    1, terminalCost);
        }
    }

    private static void checkSumByCrossingCost(Checks c, int rows) {
        try (BricksSession session = Bricks.open(rows, SEED)) {
            BricksQuery chain = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL);

            // Warm-up call, unmeasured — same discipline as count()'s crossing check above.
            chain.sumBy(Orders.REGION, Orders.REVENUE);

            long c0 = Diagnostics.crossings();
            chain.sumBy(Orders.REGION, Orders.REVENUE);
            long cost = Diagnostics.crossings() - c0;

            // A sum terminal is TWO crossings, not one: plan evaluation into the selection mask
            // (lgj_plan_eval) plus the reduction itself (lgj_reduce_sum_i32) — unlike count(),
            // whose plan evaluation RETURNS the count and so pays only one. 16 groups x 2 = 32.
            // The thesis assertion is the arithmetic's SHAPE: crossings are proportional to the
            // number of groups, never to the number of rows — which is why this same literal is
            // asserted at BOTH n = 1000 and n = 64000.
            c.eq("sumBy() at n = " + rows + " costs exactly 32 crossings"
                            + " (2 per region group — plan eval + reduce — never one per row)",
                    32, cost);
        }
    }

    // ------------------------------------------------------------------
    // 4. sumBy() parity — value correctness plus internal consistency.
    // ------------------------------------------------------------------

    private static void checkSumByParity(Checks c) {
        c.section("sumBy() parity");

        checkSumByCrossingCost(c, 1_000);
        checkSumByCrossingCost(c, 64_000);

        final int rows = 64_000;
        Fixture expected = generate(rows, SEED);

        long[] expectedByRegion = new long[16];
        long expectedGrandTotal = 0;
        for (int i = 0; i < rows; i++) {
            if (expected.revenues()[i] > 100) {
                int region = expected.regions()[i];
                expectedByRegion[region] += expected.revenues()[i];
                expectedGrandTotal += expected.revenues()[i];
            }
        }

        try (BricksSession session = Bricks.open(rows, SEED)) {
            Map<Integer, Long> actual = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL)
                    .sumBy(Orders.REGION, Orders.REVENUE);

            c.eq("sumBy() returns exactly 16 region entries", 16, actual.size());

            long actualGrandTotal = 0;
            for (int region = 0; region < 16; region++) {
                Long value = actual.get(region);
                c.that("sumBy() has an entry for region " + region, value != null);
                long v = value == null ? Long.MIN_VALUE : value;
                c.eq("region " + region + " sum matches pure-Java recomputation",
                        expectedByRegion[region], v);
                actualGrandTotal += v;
            }

            c.eq("sum of all 16 region sums equals the recomputed grand total"
                            + " (internal consistency, independent of the per-region parity above)",
                    expectedGrandTotal, actualGrandTotal);

            long actualDirectSum = session.query()
                    .where(Orders.REVENUE.gt(100))
                    .authorize(Role.GLOBAL)
                    .sum(Orders.REVENUE);
            c.eq("sum(REVENUE) under the identical predicate agrees with the sumBy() grand total",
                    actualDirectSum, actualGrandTotal);
        }
    }

    // ------------------------------------------------------------------
    // 5. Aggregate-only egress: BricksQuery's return-type surface, and Orders as a schema.
    // ------------------------------------------------------------------

    private static void checkAggregateOnlyEgress(Checks c) {
        c.section("aggregate-only egress — BricksQuery's public return types");

        for (Method m : BricksQuery.class.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;   // wait()/notify()/getClass()/... are not this class's surface
            }
            // BricksQuery OVERRIDES toString() (its debug rendering), so getDeclaringClass() no
            // longer reports Object for it — but an Object-defined contract method is still not
            // part of the aggregate-egress surface being audited here, whichever class's body
            // happens to serve it. Exclude the overridable Object trio by NAME.
            String name = m.getName();
            if ((name.equals("toString") || name.equals("equals") || name.equals("hashCode"))
                    && switch (name) {
                        case "toString", "hashCode" -> m.getParameterCount() == 0;
                        default -> m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == Object.class;
                    }) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            boolean aggregateOnly =
                    ret == BricksQuery.class || ret == long.class || ret == Map.class;
            c.that("BricksQuery." + m.getName() + "(...) returns BricksQuery, long, or Map"
                            + " (was " + ret.getSimpleName() + ") — no row-shaped public type exists",
                    aggregateOnly);
        }

        c.section("Orders is a schema, not an entity — the reflection guard");

        c.eq("Orders declares zero public constructors",
                0, publicConstructorCount(Orders.class));
        c.eq("Orders declares zero non-static instance fields",
                0, instanceFieldCount(Orders.class));

        // AssertionError specifically, matching TradesParityTest's guard on Trade — a plain
        // "some exception" check would let a typo (e.g. a stray NullPointerException) pass for
        // the wrong reason.
        c.throwsUp("forcing the private constructor accessible still cannot build an Orders",
                AssertionError.class, () -> {
                    try {
                        var ctor = Orders.class.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        ctor.newInstance();
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        if (cause instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(cause);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static int publicConstructorCount(Class<?> type) {
        return type.getConstructors().length;
    }

    private static int instanceFieldCount(Class<?> type) {
        int n = 0;
        for (var field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                n++;
            }
        }
        return n;
    }
}

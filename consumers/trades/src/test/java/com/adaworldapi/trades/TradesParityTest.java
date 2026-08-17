package com.adaworldapi.trades;

import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.Diagnostics;
import com.adaworldapi.lancegraph.NativePattern;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.View;

/**
 * The consumer-tier re-run of {@code FixtureParityTest} and {@code LazinessTest}, this time
 * against the DOMAIN vocabulary ({@code Trade.VENUE}, {@code Trade.PRICE}) instead of the raw
 * {@code Pattern} schema — proving the poster's "One Billion Objects. Zero Objects." claim holds
 * once a real-looking domain type sits on top of the same membrane, not only for the schema the
 * substrate tests were written against.
 *
 * <p>Three properties, each independently falsifiable:
 *
 * <ol>
 *   <li><strong>Value parity</strong> — the fluent domain chain's count agrees with a pure-Java
 *       recomputation from the transcribed fixture generator. The recomputation never touches
 *       {@code World} or the native library; it only knows the seed and the generator's published
 *       shape, so agreement is evidence about the schema binding, not a tautology.
 *   <li><strong>Laziness</strong> — composing a domain-vocabulary chain crosses the membrane zero
 *       times; the terminal {@code count()} crosses exactly once, whatever the chain length. This
 *       is {@code LazinessTest}'s instrument, reapplied at the consumer tier: {@code Trade.VENUE}
 *       and {@code Trade.PRICE} must fuse through {@link View#where} exactly the way {@code
 *       Pattern.CLASS} and {@code Pattern.VALUE} do, because they reuse the identical machinery.
 *   <li><strong>The thesis's reflection guard</strong> — {@code Trade} is a schema, not an entity.
 *       No instance is ever constructible, even by a caller who forces accessibility via
 *       reflection, and the class carries no instance state a constructed object could have held
 *       anyway.
 * </ol>
 */
public final class TradesParityTest {

    private TradesParityTest() {}

    public static void main(String[] args) {
        System.out.println("TradesParityTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("TradesParityTest"));
        }
        Checks c = new Checks("TradesParityTest");
        run(c);
        System.exit(c.report());
    }

    /**
     * The seed {@link #openResource} passes to {@code World.open}. Chosen to match
     * {@code NativePattern.DEFAULT_SEED} (docs/abi.md's normative fixture seed) so a reader can
     * cross-check this file's numbers against {@code FixtureParityTest} directly. Passed
     * explicitly on every call rather than relied on as a hidden default, so this file's parity
     * claim does not depend on what {@code World} happens to default to.
     */
    private static final long SEED = 0xABCDL;

    /**
     * The one line the orchestrator adjusts if {@code World}'s open entry point does not match
     * this shape exactly. Everything below this method is written against {@link NativePattern}
     * and {@link View} only — never against {@code World} directly — so a signature or
     * return-type change in {@code World.open} is a one-line fix here, not a rewrite of the
     * suite.
     *
     * <p>Handles both shapes named in the wave brief: {@code World.open(...)} may return a
     * {@link View} directly (the poster's own snippet chains {@code .where(...)} straight off the
     * open call) or a {@link NativePattern} (in which case {@code .view()} produces the
     * equivalent zero-predicate starting view). Field descriptors such as {@link Trade#VENUE} are
     * stateless plan-op factories — {@code eq}/{@code gt} do not depend on which {@link View}
     * instance carries them — so resolving either shape down to a fresh {@code .view()} off the
     * same resource is equivalent to using whatever {@code View} {@code World.open} handed back
     * directly.
     */
    private static NativePattern openResource(long rows, long seed) {
        Object opened = World.open(rows, seed);
        if (opened instanceof NativePattern pattern) {
            return pattern;
        }
        if (opened instanceof View view) {
            return view.source();
        }
        throw new IllegalStateException(
                "World.open(rows, seed) returned " + opened.getClass()
                        + ", expected either NativePattern or View — update openResource() to match"
                        + " the shape World actually ships.");
    }

    /**
     * SplitMix64, transcribed from the normative fixture generator. Provenance: this is the
     * identical constant-for-constant, shift-for-shift transcription already checked against the
     * published reference vector in {@code FixtureParityTest}. Duplicated rather than shared
     * because the original is {@code private} to that class and lives in a different compile
     * unit — this consumer module needs its own copy of the generator, not a dependency on the
     * substrate test's internals.
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
     * {@code (venue, price)} per row, recomputed independently in pure Java from the fixture
     * generator's published shape: two SplitMix64 draws per row, {@code a} then {@code b}, with
     * {@code venue = (a >>> 33) & 0xF} and {@code price = ((b >>> 40) & 0x1FF) - 150}. This is the
     * exact same derivation {@code FixtureParityTest} uses for {@code (class, value)} — the trades
     * schema binds domain names onto the identical two lanes, so the recomputation is identical
     * too, renamed to match the domain.
     */
    private record Trades(int[] venues, int[] prices) {}

    private static Trades generate(int nRows, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        int[] venues = new int[nRows];
        int[] prices = new int[nRows];
        for (int i = 0; i < nRows; i++) {
            long a = rng.next();   // FIRST draw of the row
            long b = rng.next();   // SECOND draw of the row
            venues[i] = (int) ((a >>> 33) & 0xF);
            prices[i] = (int) (((b >>> 40) & 0x1FF) - 150);
        }
        return new Trades(venues, prices);
    }

    public static void run(Checks c) {
        checkParity(c, 1_000);
        checkParity(c, 64_000);
        checkLaziness(c);
        checkNotAnEntity(c);
    }

    private static void checkParity(Checks c, int rows) {
        c.section("value parity at n = " + rows + " rows");

        Trades expected = generate(rows, SEED);
        long expectCount = 0;
        for (int i = 0; i < rows; i++) {
            if (expected.venues()[i] == Trade.XETRA && expected.prices()[i] > 100) {
                expectCount++;
            }
        }

        try (NativePattern resource = openResource(rows, SEED)) {
            long actualCount = resource.view()
                    .where(Trade.VENUE.eq(Trade.XETRA))
                    .where(Trade.PRICE.gt(100))
                    .count();

            c.eq("VENUE == XETRA AND PRICE > 100, domain chain vs pure-Java recomputation",
                    expectCount, actualCount);
            c.that("the predicate excludes a real number of rows (not vacuous: count > 0)",
                    actualCount > 0);
            c.that("the predicate is not satisfied by every row either (count < n)",
                    actualCount < rows);
        }
    }

    private static void checkLaziness(Checks c) {
        c.section("laziness through the domain vocabulary (Diagnostics.crossings())");

        try (NativePattern resource = openResource(64_000, SEED)) {
            long before = Diagnostics.crossings();
            View chain = resource.view()
                    .where(Trade.VENUE.eq(Trade.XETRA))
                    .where(Trade.PRICE.gt(100))
                    .where(Trade.PRICE.gt(-50))
                    .where(Trade.VENUE.eq(Trade.NASDAQ));
            long afterBuild = Diagnostics.crossings();

            c.eq("composing a 4-predicate Trade chain costs no crossings",
                    0, afterBuild - before);
            c.eq("the chain does carry all four conditions", 4, chain.conditionCount());

            // Warm the resource's reusable scratch selection so the steady-state cost is what is
            // measured, matching LazinessTest's own discipline.
            chain.count();

            long c0 = Diagnostics.crossings();
            chain.count();
            long terminalCost = Diagnostics.crossings() - c0;
            c.eq("count() over the domain chain costs exactly one crossing",
                    1, terminalCost);
        }
    }

    private static void checkNotAnEntity(Checks c) {
        c.section("Trade is a schema, not an entity — the reflection guard");

        c.eq("Trade declares zero public constructors",
                0, publicConstructorCount(Trade.class));
        c.eq("Trade declares zero non-static instance fields",
                0, instanceFieldCount(Trade.class));

        // The private constructor is expected to throw AssertionError, per the frozen contract
        // ("private throwing constructor"). AssertionError is required specifically — not just
        // "some exception" — so a typo that instead produced e.g. a NullPointerException would be
        // caught here rather than mistaken for the guard working.
        c.throwsUp("forcing the private constructor accessible still cannot build a Trade",
                AssertionError.class, () -> {
                    try {
                        var ctor = Trade.class.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        ctor.newInstance();
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        // The constructor itself threw — unwrap so throwsUp() sees the real cause
                        // (AssertionError) rather than the reflection wrapper type.
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
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                n++;
            }
        }
        return n;
    }
}

package com.adaworldapi.lancegraph;

/**
 * A genuine cross-language check: Java recomputes the expected answer from the normative fixture
 * description and compares it to what the native kernels returned.
 *
 * <p>Most of the other tests establish <em>properties</em> — the count never grows, the paths
 * agree, nothing crosses when nothing should. Properties can all hold while every number is wrong
 * together. This test is the one that pins the actual values, and it does so without shipping a
 * data file: the fixture is generated deterministically from a seed, so Java can transcribe the
 * generator and derive the same answers independently.
 *
 * <p>Independence is what gives it force. Java runs its own SplitMix64 over its own {@code long}
 * arithmetic and counts with an ordinary loop; Rust runs vectorised kernels over lanes Java never
 * sees. Agreement across those two is evidence; agreement between a kernel and a stored expectation
 * would only be evidence that nobody changed the file.
 *
 * <p>The generator is checked against its own published test vector first, so a failure downstream
 * points at the kernels rather than at this transcription.
 */
public final class FixtureParityTest {

    private FixtureParityTest() {}

    public static void main(String[] args) {
        System.out.println("FixtureParityTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("FixtureParityTest"));
        }
        Checks c = new Checks("FixtureParityTest");
        run(c);
        System.exit(c.report());
    }

    /**
     * SplitMix64, transcribed from the normative description of the fixture generator.
     *
     * <p>Every constant and shift is the published reference. All arithmetic is wrapping, which is
     * what Java's {@code long} does natively — so this is a transcription, not a port, and there is
     * no place for an off-by-one interpretation to hide.
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

    /** The three lanes, as Java believes they should be. */
    private record Lanes(int[] classes, int[] values) {}

    private static Lanes generate(int nRows, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        int[] classes = new int[nRows];
        int[] values = new int[nRows];
        for (int i = 0; i < nRows; i++) {
            long a = rng.next();          // FIRST draw of the row
            long b = rng.next();          // SECOND draw of the row
            classes[i] = (int) ((a >>> 33) & 0xF);
            values[i] = (int) (((b >>> 40) & 0x1FF) - 150);
        }
        return new Lanes(classes, values);
    }

    public static void run(Checks c) {
        c.section("the transcribed generator is the real SplitMix64");
        SplitMix64 r = new SplitMix64(0);
        c.eq("published vector, draw 1", 0xE220A8397B1DCDAFL, r.next());
        c.eq("published vector, draw 2", 0x6E789E6AA1B965F4L, r.next());
        c.eq("published vector, draw 3", 0x06C45D188009454FL, r.next());

        int rows = 65_536;
        long seed = NativePattern.DEFAULT_SEED;
        Lanes expected = generate(rows, seed);

        c.section("the lanes have the shape the contract describes");
        int minClass = Integer.MAX_VALUE, maxClass = Integer.MIN_VALUE;
        int minValue = Integer.MAX_VALUE, maxValue = Integer.MIN_VALUE;
        for (int i = 0; i < rows; i++) {
            minClass = Math.min(minClass, expected.classes()[i]);
            maxClass = Math.max(maxClass, expected.classes()[i]);
            minValue = Math.min(minValue, expected.values()[i]);
            maxValue = Math.max(maxValue, expected.values()[i]);
        }
        c.eq("classes start at 0", 0, minClass);
        c.eq("classes end at 15", 15, maxClass);
        c.that("values reach below zero, so > is a real signed comparison", minValue < 0);
        c.that("values reach above the test threshold", maxValue > 100);

        try (NativePattern data = NativePattern.open(rows, seed)) {

            c.section("counts: Java's own loop vs the native kernels");

            long expectClass = 0, expectValue = 0, expectBoth = 0, expectSum = 0;
            for (int i = 0; i < rows; i++) {
                boolean isClass = expected.classes()[i] == 7;
                boolean isValue = expected.values()[i] > 100;
                if (isClass) {
                    expectClass++;
                    expectSum += expected.values()[i];
                }
                if (isValue) {
                    expectValue++;
                }
                if (isClass && isValue) {
                    expectBoth++;
                }
            }

            c.eq("class == 7",
                    expectClass, data.view().where(Pattern.CLASS.eq(7)).count());
            c.eq("value > 100",
                    expectValue, data.view().where(Pattern.VALUE.gt(100)).count());
            c.eq("class == 7 AND value > 100", expectBoth,
                    data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100)).count());

            c.section("a reduction, likewise");
            c.eq("sum of value over class == 7", expectSum,
                    data.view().where(Pattern.CLASS.eq(7)).lens(Pattern.VALUE).sum());

            c.section("a signed threshold, where an unsigned comparison would differ");
            long expectNegative = 0;
            for (int i = 0; i < rows; i++) {
                if (expected.values()[i] > -100) {
                    expectNegative++;
                }
            }
            c.eq("value > -100", expectNegative,
                    data.view().where(Pattern.VALUE.gt(-100)).count());
            c.that("and that predicate excludes a real number of rows, so it is not vacuous",
                    expectNegative < rows);

            c.section("every class tag, so no single lucky value carries the test");
            for (int tag = 0; tag < 16; tag++) {
                long want = 0;
                for (int i = 0; i < rows; i++) {
                    if (expected.classes()[i] == tag) {
                        want++;
                    }
                }
                c.eq("class == " + tag, want, data.view().where(Pattern.CLASS.eq(tag)).count());
            }
        }

        c.section("a different seed produces different data, and parity still holds");
        long altSeed = 0x1234_5678L;
        Lanes alt = generate(4096, altSeed);
        long altExpect = 0;
        for (int i = 0; i < 4096; i++) {
            if (alt.classes()[i] == 7 && alt.values()[i] > 100) {
                altExpect++;
            }
        }
        try (NativePattern data = NativePattern.open(4096, altSeed)) {
            c.eq("class == 7 AND value > 100 under a second seed", altExpect,
                    data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100)).count());
        }
    }
}

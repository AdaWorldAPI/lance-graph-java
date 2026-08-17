package com.adaworldapi.lancegraph;

/**
 * The fused path, the unfused path and the scalar path must agree <em>exactly</em>.
 *
 * <p>Fusion is an optimisation, and an optimisation is only trustworthy against a reference. Two
 * references exist here, and they falsify different things:
 *
 * <ul>
 *   <li><strong>unfused</strong> — the same predicates evaluated one crossing at a time and
 *       combined with explicit mask operations. If fusion disagrees with this, the fused kernel's
 *       accumulator logic is wrong.
 *   <li><strong>scalar</strong> — the same fused semantics with the vector kernels forced off. If
 *       this disagrees, the SIMD kernel is wrong. Crucially the check happens <em>through the
 *       membrane</em>, so it also covers the marshalling, not only the Rust-internal kernels.
 * </ul>
 *
 * <p>Exact equality is the right assertion: these are counts of set bits, so "close" would mean
 * "wrong". A tolerance here would be a bug in the test.
 */
public final class FusionParityTest {

    private FusionParityTest() {}

    public static void main(String[] args) {
        System.out.println("FusionParityTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("FusionParityTest"));
        }
        Checks c = new Checks("FusionParityTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        c.note("backend under test: " + NativeRuntime.simdBackend());

        // Sizes chosen to straddle the mask word boundary: 64 bits per word, so a kernel that
        // mishandles a partial trailing word shows up at 1,000 and 65,535 but not at 65,536.
        long[] sizes = {1, 63, 64, 65, 1_000, 65_535, 65_536};

        for (long rows : sizes) {
            c.section(rows + " rows");
            try (NativePattern data = NativePattern.open(rows)) {

                check(c, "class == 7", data.view().where(Pattern.CLASS.eq(7)));
                check(c, "value > 100", data.view().where(Pattern.VALUE.gt(100)));
                check(c, "class == 7 AND value > 100",
                        data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100)));
                check(c, "a three-deep chain",
                        data.view().where(Pattern.VALUE.gt(-150))
                                   .where(Pattern.CLASS.eq(3))
                                   .where(Pattern.VALUE.gt(0)));
            }
        }

        c.section("the two paths really do cost different amounts");
        try (NativePattern data = NativePattern.open(65_536)) {
            View v = data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100));
            Diagnostics.countFused(v);
            Diagnostics.countUnfused(v);

            long a = Diagnostics.crossings();
            Diagnostics.countFused(v);
            long fused = Diagnostics.crossings() - a;

            long b = Diagnostics.crossings();
            Diagnostics.countUnfused(v);
            long unfused = Diagnostics.crossings() - b;

            c.eq("fused: one crossing", 1, fused);
            c.that("unfused: strictly more (it is what the design forbids)", unfused > fused);
            c.note("fused " + fused + " crossing vs unfused " + unfused
                    + " for the identical answer -- this is the cost of the rule, measured");
        }

        c.section("a materialised selection agrees with the count that produced it");
        try (NativePattern data = NativePattern.open(65_536)) {
            View v = data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100));
            try (Mask m = v.select()) {
                c.eq("Mask.count() equals View.count()", v.count(), m.count());
            }
        }
    }

    private static void check(Checks c, String what, View v) {
        long fused = Diagnostics.countFused(v);
        long unfused = Diagnostics.countUnfused(v);
        long scalar = Diagnostics.countScalar(v);

        if (fused == unfused && fused == scalar) {
            c.eq(what + ": fused == unfused == scalar", fused, scalar);
        } else {
            // Report all three, because which pair disagrees says which kernel is at fault.
            c.eq(what + ": fused vs unfused", fused, unfused);
            c.eq(what + ": fused vs scalar", fused, scalar);
        }
    }
}

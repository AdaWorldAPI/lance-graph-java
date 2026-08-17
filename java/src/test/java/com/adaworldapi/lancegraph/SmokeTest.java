package com.adaworldapi.lancegraph;

/**
 * The John Doe test: does the API in the README actually work, and does it read like Java?
 *
 * <p>Read the body of {@link #run} as documentation. Nothing in it mentions an arena, a segment, a
 * linker, a lane, an opcode, a mask word or a SIMD backend — and it is nevertheless the full,
 * real path through a columnar SIMD kernel over 65,536 rows held entirely outside the Java heap.
 */
public final class SmokeTest {

    private SmokeTest() {}

    public static void main(String[] args) {
        System.out.println("SmokeTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("SmokeTest"));
        }
        Checks c = new Checks("SmokeTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        c.section("the runtime that actually loaded");
        c.note(NativeRuntime.describe());

        c.section("the fluent chain from the README");

        // ── everything below is the entire consumer-facing surface ──────────────────────────
        try (var data = NativePattern.open(65_536)) {

            long n = data.view()
                         .where(Pattern.CLASS.eq(7))
                         .where(Pattern.VALUE.gt(100))
                         .count();

            System.out.println("      -> " + n + " of " + data.rowCount() + " rows matched"
                    + " class == 7 AND value > 100");
            // ────────────────────────────────────────────────────────────────────────────────

            c.that("the chain returns a plausible count", n > 0 && n < data.rowCount());
            c.eq("the resource reports the row count it was opened with", 65_536, data.rowCount());
            c.eq("the row range agrees with the row count", 65_536, data.rows().length());

            c.section("each step of the chain, so a wrong total can be localised");
            long all = data.view().count();
            long cls = data.view().where(Pattern.CLASS.eq(7)).count();
            long val = data.view().where(Pattern.VALUE.gt(100)).count();
            c.eq("an unconditioned view selects every row", 65_536, all);
            c.that("class == 7 selects roughly a sixteenth", cls > all / 32 && cls < all / 8);
            c.that("value > 100 selects a middling fraction", val > all / 4 && val < (all * 3) / 4);
            c.that("the conjunction is no larger than either part", n <= cls && n <= val);

            c.section("a reduction over the same rows");
            long sum = data.view()
                           .where(Pattern.CLASS.eq(7))
                           .lens(Pattern.VALUE)
                           .sum();
            c.note("sum of value over class == 7 is " + sum);
            c.that("the sum is a real number, not a default", sum != 0);

            c.section("a selection can be kept and asked twice");
            try (Mask selected = data.view().where(Pattern.CLASS.eq(7)).select()) {
                c.eq("a materialised selection agrees with the count", cls, selected.count());
                c.eq("and is stable when asked again", cls, selected.count());
                c.note("selection identity: " + selected.id());
                c.note("65,536 rows selected as packed bits = " + (65_536 / 8) + " bytes,"
                        + " not 65,536 objects");
            }

            c.section("views are immutable, so narrowing one does not disturb it");
            View base = data.view().where(Pattern.CLASS.eq(7));
            View narrower = base.where(Pattern.VALUE.gt(100));
            c.eq("the original still selects what it did", cls, base.count());
            c.eq("and the derived view is the narrowed one", n, narrower.count());
            c.eq("the original carries one condition", 1, base.conditionCount());
            c.eq("the derived one carries two", 2, narrower.conditionCount());
        }
    }
}

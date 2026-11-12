package com.adaworldapi.lancegraph;

/**
 * Proves the fluent chain is lazy and the terminal operation is fused — by measurement, not by
 * comment.
 *
 * <p>"Building a View makes zero crossings" is the kind of claim that is true when written and
 * quietly false a year later, because nothing checks it. So the membrane counts its own crossings
 * and these tests read the counter. The counter is the only reason the claim is a test rather than
 * a docstring.
 *
 * <p>Two properties, and the second is the load-bearing one:
 *
 * <ol>
 *   <li><strong>Laziness</strong> — building and narrowing a view moves the counter by exactly 0.
 *   <li><strong>Fusion</strong> — a terminal operation moves it by a small constant that does
 *       <em>not</em> grow with the number of predicates. Two predicates and sixteen predicates cost
 *       the same number of crossings. That is what makes the fluent style affordable.
 * </ol>
 */
public final class LazinessTest {

    private LazinessTest() {}

    public static void main(String[] args) {
        System.out.println("LazinessTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("LazinessTest"));
        }
        Checks c = new Checks("LazinessTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        try (NativePattern data = NativePattern.open(65_536)) {

            c.section("building a chain crosses the membrane zero times");

            long before = Diagnostics.crossings();
            View v = data.view()
                         .where(Pattern.CLASS.eq(7))
                         .where(Pattern.VALUE.gt(100))
                         .where(Pattern.VALUE.gt(-50))
                         .where(Pattern.CLASS.eq(7));
            long afterBuild = Diagnostics.crossings();

            c.eq("four .where() calls plus .view() cost no crossings", 0, afterBuild - before);
            c.eq("the chain does carry all four conditions", 4, v.conditionCount());
            c.note("nothing was evaluated: no mask was allocated, no row was read");

            // Warm the scratch selection so the steady-state cost is what is measured. The first
            // terminal op on a resource also allocates its reusable selection; counting that as
            // per-query cost would misreport the steady state.
            v.count();

            c.section("a terminal operation costs ONE crossing, whatever the chain length");

            long c0 = Diagnostics.crossings();
            long n4 = v.count();
            long cost4 = Diagnostics.crossings() - c0;
            c.eq("count() over a 4-condition chain", 1, cost4);

            View wide = data.view();
            for (int i = 0; i < 16; i++) {
                wide = wide.where(Pattern.VALUE.gt(-150 + i));
            }
            long c1 = Diagnostics.crossings();
            long n16 = wide.count();
            long cost16 = Diagnostics.crossings() - c1;
            c.eq("count() over a 16-condition chain", 1, cost16);
            c.eq("cost is independent of the number of conditions", cost4, cost16);
            c.note("4 conditions -> " + n4 + " rows, 16 conditions -> " + n16 + " rows,"
                    + " both for one crossing");

            c.section("cost is independent of the number of rows, too");
            try (NativePattern small = NativePattern.open(1_024);
                 NativePattern large = NativePattern.open(1_000_000)) {

                View sv = small.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100));
                View lv = large.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100));
                sv.count();
                lv.count();

                long a = Diagnostics.crossings();
                sv.count();
                long smallCost = Diagnostics.crossings() - a;

                long b = Diagnostics.crossings();
                long largeRows = lv.count();
                long largeCost = Diagnostics.crossings() - b;

                c.eq("1,024 rows", 1, smallCost);
                c.eq("1,000,000 rows", 1, largeCost);
                c.note("a thousand-fold more data for the same one crossing;"
                        + " the large query matched " + largeRows + " rows");
            }

            c.section("a reduction is one crossing too: the plan folds straight into the sum");
            // Until ABI minor 13 this was 2 (evaluate into a selection, then reduce over it).
            // lgj_plan_scalar folds the plan and the sum as one program (docs/abi.md §21).
            c.that("the loaded library carries lgj_plan_scalar (minor >= 13)",
                    NativeRuntime.abiMinor() >= 13);
            View sel = data.view().where(Pattern.CLASS.eq(7));
            sel.sumOf(Pattern.VALUE);
            long c2 = Diagnostics.crossings();
            sel.sumOf(Pattern.VALUE);
            long sumCost = Diagnostics.crossings() - c2;
            c.eq("sumOf() crosses once", 1, sumCost);

            long c3 = Diagnostics.crossings();
            data.view().sumOf(Pattern.VALUE);
            c.eq("sumOf() over an empty chain crosses once (a plan of zero ops)", 1,
                    Diagnostics.crossings() - c3);

            c.section("the scalar fold and the selection agree");
            View narrow = sel.where(Pattern.VALUE.gt(100));
            try (Mask kept = sel.select(); Mask keptNarrow = narrow.select()) {
                c.eq("count() equals the selection's own count", kept.count(), sel.count());
                c.eq("and for a longer chain", keptNarrow.count(), narrow.count());
                c.that("the two chains select different, non-empty populations",
                        keptNarrow.count() > 0 && keptNarrow.count() < kept.count());
            }

            c.section("nothing here hydrated a row");
            c.note("no API on View, Mask or Lens returns a row object, an index list, or an"
                    + " iterator; the crossing counts above are only meaningful because there is"
                    + " no per-row path that could have inflated them");
        }
    }
}

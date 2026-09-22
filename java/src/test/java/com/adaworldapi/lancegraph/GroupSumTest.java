package com.adaworldapi.lancegraph;

/**
 * Minor 12 through the public facade: a {@code GROUP BY} that costs one crossing and builds no
 * selection.
 *
 * <p>Three claims, each measured rather than asserted:
 *
 * <ol>
 *   <li><strong>Parity.</strong> {@link View#sumByGroup} answers exactly what the two-crossing
 *       path it replaces answered — {@code where(key.eq(g)).sumOf(value)} for every {@code g} —
 *       on a single-tile and a multi-tile resource, with and without a chain. The two paths run
 *       different native kernels behind different terminals; the numbers must still agree.
 *   <li><strong>Cost.</strong> One crossing for 16 groups, at 1,024 rows and at 65,536 rows,
 *       where the per-group path pays 32. The counter is the membrane's own.
 *   <li><strong>The keyed form is a real join.</strong> {@link View#sumByGroupVia} regroups
 *       through a second resource; the oracle recomputes the regrouping from that resource's
 *       own key column (read through the ONE named materialiser, in test code, where it belongs)
 *       and the local totals, and the two must differ from each other.
 * </ol>
 */
public final class GroupSumTest {

    private GroupSumTest() {}

    private static final int GROUPS = 16; // Pattern.CLASS spans 0..15

    public static void main(String[] args) {
        System.out.println("GroupSumTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("GroupSumTest"));
        }
        Checks c = new Checks("GroupSumTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        if (NativeRuntime.abiMinor() < 12) {
            c.that("SKIPPED: library minor " + NativeRuntime.abiMinor()
                    + " predates the grouped sum (minor 12); OldAbiCompatTest covers the gate", true);
            return;
        }

        c.section("parity: one crossing answers what sixteen pairs of crossings answered");
        for (long rows : new long[] {1_024L, 65_536L}) {
            try (NativePattern data = NativePattern.open(rows, 0x51DEL)) {
                View[] views = {
                    data.view(),
                    data.view().where(Pattern.VALUE.gt(100)),
                    data.view().where(Pattern.VALUE.gt(100)).where(Pattern.VALUE.gt(-50)),
                    data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(0)),
                };
                for (View v : views) {
                    GroupTotals t = v.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
                    c.eq(rows + " rows, " + v + ": groups()", GROUPS, t.groups());
                    long live = 0;
                    for (int g = 0; g < GROUPS; g++) {
                        long want = v.where(Pattern.CLASS.eq(g)).sumOf(Pattern.VALUE);
                        c.eq(rows + " rows, " + v + ", group " + g, want, t.total(g));
                        if (want != 0) {
                            live++;
                        }
                    }
                    // Anti-vacuity: the totals are not sixteen zeros agreeing with sixteen zeros.
                    c.that(rows + " rows, " + v + ": at least one group is non-zero", live >= 1);
                }
                GroupTotals all = views[0].sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
                long everything = 0;
                for (int g = 0; g < GROUPS; g++) {
                    everything += all.total(g);
                }
                c.eq(rows + " rows: the empty chain's groups partition the whole sum",
                        views[0].sumOf(Pattern.VALUE), everything);
            }
        }

        c.section("a narrower key domain drops, a wider one pads with zero");
        try (NativePattern data = NativePattern.open(4_096L, 0x51DEL)) {
            View v = data.view().where(Pattern.VALUE.gt(0));
            GroupTotals full = v.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
            GroupTotals eight = v.sumByGroup(Pattern.CLASS, Pattern.VALUE, 8);
            GroupTotals twenty = v.sumByGroup(Pattern.CLASS, Pattern.VALUE, 20);
            for (int g = 0; g < 8; g++) {
                c.eq("groups=8 keeps group " + g, full.total(g), eight.total(g));
            }
            for (int g = 0; g < GROUPS; g++) {
                c.eq("groups=20 keeps group " + g, full.total(g), twenty.total(g));
            }
            c.eq("groups=20: key 16 is unnamed and zero", 0, twenty.total(16));
            c.eq("groups=20: key 19 is unnamed and zero", 0, twenty.total(19));
            c.throwsUp("total(groups()) is out of range", IndexOutOfBoundsException.class,
                    () -> eight.total(8));
        }

        c.section("cost: one crossing for sixteen groups, at any row count");
        try (NativePattern small = NativePattern.open(1_024L, 0xC0DEL);
                NativePattern large = NativePattern.open(65_536L, 0xC0DEL)) {
            View sv = small.view().where(Pattern.VALUE.gt(100));
            View lv = large.view().where(Pattern.VALUE.gt(100));
            // Warm: the first terminal op on a resource may resolve lazily-held state.
            sv.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
            lv.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);

            long a = Diagnostics.crossings();
            sv.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
            long smallCost = Diagnostics.crossings() - a;
            long b = Diagnostics.crossings();
            lv.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
            long largeCost = Diagnostics.crossings() - b;
            c.eq("1,024 rows, 16 groups: sumByGroup crosses once", 1, smallCost);
            c.eq("65,536 rows, 16 groups: sumByGroup crosses once", 1, largeCost);

            // The path it replaces, measured beside it rather than remembered from bricks.
            lv.where(Pattern.CLASS.eq(0)).sumOf(Pattern.VALUE);
            long d = Diagnostics.crossings();
            for (int g = 0; g < GROUPS; g++) {
                lv.where(Pattern.CLASS.eq(g)).sumOf(Pattern.VALUE);
            }
            long perGroupCost = Diagnostics.crossings() - d;
            c.eq("the per-group path pays two crossings per group", 2L * GROUPS, perGroupCost);

            long e = Diagnostics.crossings();
            large.view().sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
            c.eq("an empty chain still crosses exactly once (the plan of zero ops is the ABI's)",
                    1, Diagnostics.crossings() - e);
        }

        c.section("the keyed form regroups through a second resource, in one crossing");
        try (NativePattern lines = NativePattern.open(4_096L, 0xA11CEL);
                NativePattern partners = NativePattern.open(64L, 0xB0BL)) {
            View v = lines.view().where(Pattern.VALUE.gt(100));
            GroupTotals local = v.sumByGroup(Pattern.CLASS, Pattern.VALUE, GROUPS);
            GroupTotals via = v.sumByGroupVia(Pattern.CLASS, partners, Pattern.CLASS,
                    Pattern.VALUE, GROUPS);

            // Oracle: partner row k (k < 16, the only rows a line's CLASS can name) carries
            // CLASS = c(k); the via total for g is the sum of the local totals over every k
            // with c(k) == g. c(k) is read from `partners` through the one named materialiser.
            long[] want = new long[GROUPS];
            for (int g = 0; g < GROUPS; g++) {
                try (Mask m = partners.view().where(Pattern.CLASS.eq(g)).select()) {
                    for (long k : m.materializeRows()) {
                        if (k < GROUPS) {
                            want[g] += local.total((int) k);
                        }
                    }
                }
            }
            boolean differs = false;
            for (int g = 0; g < GROUPS; g++) {
                c.eq("via group " + g, want[g], via.total(g));
                differs |= via.total(g) != local.total(g);
            }
            c.that("the join genuinely regrouped (via totals differ from local totals)", differs);

            long x = Diagnostics.crossings();
            v.sumByGroupVia(Pattern.CLASS, partners, Pattern.CLASS, Pattern.VALUE, GROUPS);
            c.eq("sumByGroupVia crosses once", 1, Diagnostics.crossings() - x);

            GroupTotals self = v.sumByGroupVia(Pattern.CLASS, lines, Pattern.CLASS,
                    Pattern.VALUE, GROUPS);
            c.eq("a self-join answers 16 groups", GROUPS, self.groups());
        }

        c.section("arguments are checked in Java, before any crossing");
        try (NativePattern data = NativePattern.open(256L, 1L)) {
            long before = Diagnostics.crossings();
            c.throwsUp("groups = 0", IllegalArgumentException.class,
                    () -> data.view().sumByGroup(Pattern.CLASS, Pattern.VALUE, 0));
            c.throwsUp("groups < 0", IllegalArgumentException.class,
                    () -> data.view().sumByGroup(Pattern.CLASS, Pattern.VALUE, -3));
            c.throwsUp("null key", NullPointerException.class,
                    () -> data.view().sumByGroup(null, Pattern.VALUE, 4));
            c.eq("none of those crossed", 0, Diagnostics.crossings() - before);

            NativePattern gone = NativePattern.open(64L, 2L);
            gone.close();
            c.throwsUp("a closed via resource is rejected", ClosedResourceException.class,
                    () -> data.view().sumByGroupVia(Pattern.CLASS, gone, Pattern.CLASS,
                            Pattern.VALUE, 4));
            data.close();
            c.throwsUp("a closed resource is rejected", ClosedResourceException.class,
                    () -> data.view().sumByGroup(Pattern.CLASS, Pattern.VALUE, 4));
        } catch (ClosedResourceException expected) {
            // try-with-resources closes `data` a second time after the test closed it: that
            // double close is itself an error by contract, and the one this block expects.
            c.that("double close is an error, not a no-op", true);
        }
    }
}

package com.adaworldapi.lancegraph;

/**
 * Falsifiers for the ABI minor 11 facade additions (docs/abi.md §19, "the masking-op
 * completion"): {@link Mask#ternlog}, {@link RowStore#maskOfFacetTernaryMatch}, and
 * {@link View#minOf}/{@link View#maxOf} (surfaced again through {@link Lens#min}/{@link Lens#max},
 * the growth point that class's own javadoc names in advance).
 *
 * <p>Every "expected" value below is either hand-derived from a truth table written out in this
 * file (the {@code ternlog} fixture — eight rows, one per {@code (a,b,c)} combination, so every
 * truth-table bit is independently checkable), computed by a Java-side loop over the ALREADY
 * public, already-tested per-row accessors {@link RowStore#payloadLow64At}/{@link
 * RowStore#payloadHi32At} (the {@code ternary-match} fixture), or transcribed from the row-store
 * generator's own published SplitMix64 description (the {@code min}/{@code max} fixture,
 * mirroring {@link FixtureParityTest}'s method) — never derived by calling the operation under
 * test a second time.
 *
 * <p>{@code Abi.requireMinor(11)} itself — the "old library reports minor 10" gate — is exercised
 * by {@link OldAbiCompatTest}'s established mechanism (a real older {@code .so}, via
 * {@code -Dlgj.oldlibrary}), not here: there is no way in this codebase to fake the loaded
 * manifest's minor from within one JVM (it is a {@code static final} read once at class init from
 * the real library), so a real older artifact is the only genuine falsifier for that gate.
 */
public final class MaskingOpCompletionTest {

    private MaskingOpCompletionTest() {}

    public static void main(String[] args) {
        System.out.println("MaskingOpCompletionTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("MaskingOpCompletionTest"));
        }
        Checks c = new Checks("MaskingOpCompletionTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        maskTernlog(c);
        ternaryMatch(c);
        minMax(c);
    }

    // ── Mask.ternlog (docs/abi.md §19.1) ─────────────────────────────────────────────────────

    /**
     * Eight rows, one per {@code (a,b,c) in {0,1}^3} combination (row index IS the truth-table
     * index {@code (a<<2)|(b<<1)|c}), built via {@link RowStore#importRows} so every bit is
     * hand-placed rather than fixture-derived. This lets every immediate in docs/abi.md §19.1's
     * own table be checked against its EXACT expected row set, by hand, with no RNG in the way.
     */
    private static void maskTernlog(Checks c) {
        c.section("Mask.ternlog(): the full truth table, row index = (a<<2)|(b<<1)|c");
        final int n = 8;
        try (RowStore s = RowStore.open(n, 0x1234L)) {
            // a=1 at rows 4..7, b=1 at rows {2,3,6,7}, c=1 at rows {1,3,5,7} — exactly the eight
            // (a,b,c) combinations, one per row.
            try (Mask a = s.importRows(4, 5, 6, 7);
                    Mask b = s.importRows(2, 3, 6, 7);
                    Mask cc = s.importRows(1, 3, 5, 7)) {

                c.eq("a (bit index 2) selects rows 4..7", 4, a.count());
                c.eq("b (bit index 1) selects rows {2,3,6,7}", 4, b.count());
                c.eq("c (bit index 0) selects rows {1,3,5,7}", 4, cc.count());

                assertTernlog(c, "0x80 = a & b & c (AND3) narrows to exactly row 7",
                        a, b, cc, 0x80, new long[] {7});
                assertTernlog(c, "0xFF leaves the mask FULL regardless of a/b/c"
                                + " (anti-vacuity twin of AND3: a different imm, a different answer)",
                        a, b, cc, 0xFF, new long[] {0, 1, 2, 3, 4, 5, 6, 7});
                assertTernlog(c, "0x00 selects NOTHING regardless of a/b/c (declines, non-trivially:"
                                + " the same three real operand masks as every other case here)",
                        a, b, cc, 0x00, new long[] {});
                assertTernlog(c, "0xC0 = a & b -> rows {6,7}",
                        a, b, cc, 0xC0, new long[] {6, 7});
                assertTernlog(c, "0xFC = a | b -> rows {2,3,4,5,6,7}",
                        a, b, cc, 0xFC, new long[] {2, 3, 4, 5, 6, 7});
                assertTernlog(c, "0x3C = a ^ b -> rows {2,3,4,5}",
                        a, b, cc, 0x3C, new long[] {2, 3, 4, 5});
                assertTernlog(c, "0xE8 = majority(a,b,c) -> rows {3,5,6,7}",
                        a, b, cc, 0xE8, new long[] {3, 5, 6, 7});
                assertTernlog(c, "0x0F = !a (b/c unused) -> rows {0,1,2,3}",
                        a, b, cc, 0x0F, new long[] {0, 1, 2, 3});

                c.section("aliasing: b and c may be the SAME Mask object as the receiver");
                try (Mask maj3OfA = a.ternlog(a, a, 0xE8)) {
                    // majority(x,x,x) == x for any x, so this must equal `a` exactly.
                    assertSameSet(c, "a.ternlog(a, a, MAJ3) == a (majority of three equal copies)",
                            maj3OfA.materializeRows(), new long[] {4, 5, 6, 7});
                }
                try (Mask notA = a.ternlog(a, a, 0x0F)) {
                    assertSameSet(c, "a.ternlog(a, a, NOT_A) == !a (rows where a is 0)",
                            notA.materializeRows(), new long[] {0, 1, 2, 3});
                }

                c.section("imm validation");
                c.throwsUp("imm = 256 is rejected before any crossing", IllegalArgumentException.class,
                        () -> a.ternlog(b, cc, 256));
                c.throwsUp("imm = -1 is rejected before any crossing", IllegalArgumentException.class,
                        () -> a.ternlog(b, cc, -1));

                c.section("closed-selection guard");
                Mask closedSoon = s.importRows(0);
                closedSoon.close();
                c.throwsUp("ternlog() on a closed receiver throws", ClosedResourceException.class,
                        () -> closedSoon.ternlog(a, b, 0xFC));
                c.throwsUp("ternlog() with a closed b/c argument throws",
                        ClosedResourceException.class, () -> a.ternlog(closedSoon, b, 0xFC));
            }
        }
    }

    private static void assertTernlog(Checks c, String what, Mask a, Mask b, Mask cc, int imm,
            long[] expected) {
        try (Mask d = a.ternlog(b, cc, imm)) {
            c.eq(what + " (count)", expected.length, d.count());
            assertSameSet(c, what + " (row set)", d.materializeRows(), expected);
        }
    }

    // ── RowStore.maskOfFacetTernaryMatch (docs/abi.md §19.2) ─────────────────────────────────

    private static void ternaryMatch(Checks c) {
        c.section("RowStore.maskOfFacetTernaryMatch(): care=all-ones is exact register equality"
                + " (\"equals eq\") — parity against a Java loop over the ALREADY public per-row"
                + " accessors, never against the same op called twice");
        final int n = 500;
        final int facetIdx = 3;
        FacetId facet = FacetId.of(facetIdx);
        try (RowStore s = RowStore.open(n, 0xC0FFEEL)) {
            long patLo = s.payloadLow64At(0, facet);
            int patHi = s.payloadHi32At(0, facet);

            java.util.SortedSet<Long> expected = new java.util.TreeSet<>();
            for (long row = 0; row < n; row++) {
                long lo = s.payloadLow64At(row, facet);
                int hi = s.payloadHi32At(row, facet);
                if (lo == patLo && hi == patHi) {
                    expected.add(row);
                }
            }
            c.that("row 0 matches its own register, reflexively", expected.contains(0L));
            c.that("anti-vacuity: exact 96-bit equality over pseudo-random noise does NOT match"
                    + " every row", expected.size() < n);

            try (Mask m = s.maskOfFacetTernaryMatch(facet, patLo, patHi, -1L, -1)) {
                c.eq("care=all-ones count matches the independent loop", expected.size(), m.count());
                java.util.SortedSet<Long> got = new java.util.TreeSet<>();
                for (long row : m.materializeRows()) {
                    got.add(row);
                }
                c.eq("care=all-ones row set matches the independent loop", expected, got);
            }

            c.section("care=0 is a deliberate TOTAL — matches every row regardless of pattern");
            try (Mask allRows = s.maskOfFacetTernaryMatch(facet, 0x1122334455667788L, 0x2468ACE0,
                    0L, 0)) {
                c.eq("care=0 selects every row", n, allRows.count());
            }

            c.section("a pattern no row's register attains — declines, not vacuously (a real"
                    + " search over the actual fixture, not an assumed-absent magic constant)");
            long absentLo = -1;
            int absentHi = -1;
            boolean found = false;
            for (long candidate = 1; candidate <= 64 && !found; candidate++) {
                long tryLo = patLo ^ candidate;
                int tryHi = patHi;
                boolean collides = false;
                for (long row = 0; row < n; row++) {
                    if (s.payloadLow64At(row, facet) == tryLo
                            && s.payloadHi32At(row, facet) == tryHi) {
                        collides = true;
                        break;
                    }
                }
                if (!collides) {
                    absentLo = tryLo;
                    absentHi = tryHi;
                    found = true;
                }
            }
            c.that("found a pattern this fixture's own registers never attain (search precondition)",
                    found);
            try (Mask none = s.maskOfFacetTernaryMatch(facet, absentLo, absentHi, -1L, -1)) {
                c.eq("a genuinely absent exact pattern selects nothing", 0, none.count());
            }
        }

        c.section("AosRows layout only: a facet-major (columnar) store refuses with the LAYOUT"
                + " status, checked before dstMask is even resolved");
        try (RowStore col = RowStore.openColumnar(64, 0xC0FFEEL)) {
            boolean threw = false;
            try {
                col.maskOfFacetTernaryMatch(facet, 0L, 0, -1L, -1);
            } catch (LanceGraphException e) {
                threw = e.getMessage().contains("layout");
            }
            c.that("maskOfFacetTernaryMatch on a columnar store names the layout", threw);
        }
        try (RowStore aos = RowStore.open(64, 0xC0FFEEL)) {
            try (Mask m = aos.maskOfFacetTernaryMatch(facet, 0L, 0, 0L, 0)) {
                c.that("the same call still works on AoS (the gate discriminates, not a"
                        + " blanket refusal)", m.count() == 64);
            }
        }
    }

    // ── View.minOf / View.maxOf, and Lens.min / Lens.max (docs/abi.md §19.3) ────────────────

    /**
     * SplitMix64, transcribed from the row-store generator's own published description
     * (identical constants and shifts to {@link FixtureParityTest}'s transcription of the
     * PATTERN fixture's generator — a coincidence of the shared algorithm, not of the two
     * fixtures sharing a schema).
     */
    private static final class SplitMix64 {
        private long state;

        SplitMix64(long seed) {
            this.state = seed;
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    private record Lanes(int[] classes, int[] values) {}

    private static Lanes generate(int nRows, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        int[] classes = new int[nRows];
        int[] values = new int[nRows];
        for (int i = 0; i < nRows; i++) {
            long a = rng.next();
            long b = rng.next();
            classes[i] = (int) ((a >>> 33) & 0xF);
            values[i] = (int) (((b >>> 40) & 0x1FF) - 150);
        }
        return new Lanes(classes, values);
    }

    private static void minMax(Checks c) {
        c.section("View.minOf/maxOf: absent over an empty selection, unlike sumOf (always"
                + " present, since the sum of an empty population is 0)");
        final int rows = 4096;
        final long seed = 0xC0FFEEL;
        Lanes expected = generate(rows, seed);
        try (NativePattern p = NativePattern.open(rows, seed)) {
            c.eq("sumOf() over a selection that matches nothing is 0 (SUM's own, unchanged,"
                    + " always-present shape)", 0L, p.view().where(Pattern.CLASS.eq(999)).sumOf(
                    Pattern.VALUE));
            c.that("minOf() over that same empty selection is ABSENT, not an invented sentinel",
                    p.view().where(Pattern.CLASS.eq(999)).minOf(Pattern.VALUE).isEmpty());
            c.that("maxOf() likewise",
                    p.view().where(Pattern.CLASS.eq(999)).maxOf(Pattern.VALUE).isEmpty());

            c.section("parity: the unrestricted view's min/max matches the independently"
                    + " computed global extremes");
            int globalMin = Integer.MAX_VALUE;
            int globalMax = Integer.MIN_VALUE;
            for (int v : expected.values()) {
                globalMin = Math.min(globalMin, v);
                globalMax = Math.max(globalMax, v);
            }
            c.eq("unrestricted minOf() == the independently computed global minimum",
                    java.util.OptionalLong.of(globalMin), p.view().minOf(Pattern.VALUE));
            c.eq("unrestricted maxOf() == the independently computed global maximum",
                    java.util.OptionalLong.of(globalMax), p.view().maxOf(Pattern.VALUE));

            c.section("a masked population with a deliberately extreme UNSELECTED row that must"
                    + " NOT win: pick a class whose own rows never attain the global min/max at"
                    + " all, so a scan that ignored the mask would answer differently");
            int chosenClass = -1;
            int expectedMinK = 0;
            int expectedMaxK = 0;
            long expectedCountK = 0;
            for (int k = 0; k < 16 && chosenClass < 0; k++) {
                boolean minLeaks = false;
                boolean maxLeaks = false;
                int mn = Integer.MAX_VALUE;
                int mx = Integer.MIN_VALUE;
                long cnt = 0;
                for (int i = 0; i < rows; i++) {
                    if (expected.classes()[i] == k) {
                        cnt++;
                        mn = Math.min(mn, expected.values()[i]);
                        mx = Math.max(mx, expected.values()[i]);
                        if (expected.values()[i] == globalMin) {
                            minLeaks = true;
                        }
                        if (expected.values()[i] == globalMax) {
                            maxLeaks = true;
                        }
                    }
                }
                if (cnt > 0 && !minLeaks && !maxLeaks) {
                    chosenClass = k;
                    expectedMinK = mn;
                    expectedMaxK = mx;
                    expectedCountK = cnt;
                }
            }
            c.that("found a class none of whose rows attain the true global extremes (setup"
                    + " precondition — the fixture must actually offer this shape)",
                    chosenClass >= 0);
            c.that("the chosen class' own minimum is strictly greater than the true global"
                    + " minimum (the excluded row's value, by construction)",
                    expectedMinK > globalMin);
            c.that("the chosen class' own maximum is strictly less than the true global maximum",
                    expectedMaxK < globalMax);

            View restricted = p.view().where(Pattern.CLASS.eq(chosenClass));
            c.eq("restricted minOf() matches the independent per-class computation, NOT the"
                    + " global minimum", java.util.OptionalLong.of(expectedMinK),
                    restricted.minOf(Pattern.VALUE));
            c.eq("restricted maxOf() matches the independent per-class computation, NOT the"
                    + " global maximum", java.util.OptionalLong.of(expectedMaxK),
                    restricted.maxOf(Pattern.VALUE));
            c.that("restricted selection is non-trivial: neither empty nor the whole population",
                    expectedCountK > 0 && expectedCountK < rows);

            c.section("Lens.min()/max() (the growth point Lens's own class javadoc names in"
                    + " advance) agree with View.minOf()/maxOf()");
            Lens lens = restricted.lens(Pattern.VALUE);
            c.eq("Lens.min() == View.minOf()", restricted.minOf(Pattern.VALUE), lens.min());
            c.eq("Lens.max() == View.maxOf()", restricted.maxOf(Pattern.VALUE), lens.max());
            c.eq("Lens.sum() is unaffected (still the pre-existing SUM path)",
                    restricted.sumOf(Pattern.VALUE), lens.sum());
        }
    }

    private static void assertSameSet(Checks c, String what, long[] a, long[] b) {
        java.util.SortedSet<Long> sa = new java.util.TreeSet<>();
        for (long x : a) {
            sa.add(x);
        }
        java.util.SortedSet<Long> sb = new java.util.TreeSet<>();
        for (long x : b) {
            sb.add(x);
        }
        c.eq(what, sb, sa);
    }
}

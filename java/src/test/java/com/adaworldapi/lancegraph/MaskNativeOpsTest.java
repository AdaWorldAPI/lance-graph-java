package com.adaworldapi.lancegraph;

/**
 * Falsifiers for the D-LGJ-W8 mask-native facade additions (docs/abi.md §13, ABI minor &ge; 4):
 * {@link WideFieldMask}, {@link RowStore#hop}, {@link RowStore#importRows}, {@link Mask#minus},
 * and {@link Mask#materializeRows} — the Java surface
 * {@code .claude/plans/mask-native-navigation-correction-v1.md} §3.5 describes.
 *
 * <p>Every scalar "expected" value below is either hand-computed (the {@code minus()} fixture)
 * or independently transcribed through the genuinely public per-row accessors (the {@code hop()}
 * fixture, mirroring {@link RowStoreParityTest}'s own {@code publicHop}) — never derived by
 * calling the same mask-native method under test a second time.
 */
public final class MaskNativeOpsTest {

    private MaskNativeOpsTest() {}

    public static void main(String[] args) {
        System.out.println("MaskNativeOpsTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("MaskNativeOpsTest"));
        }
        Checks c = new Checks("MaskNativeOpsTest");
        run(c);
        System.exit(c.report());
    }

    private static final int FACETS_PER_ROW = 32;
    private static final long SEED = 0xABCDL;

    public static void run(Checks c) {
        c.section("Mask.minus(): parity vs a hand-computed scalar expected set, at a"
                + " non-multiple-of-64 row count (the tail rule, docs/abi.md §13)");
        int n70 = 70;
        try (RowStore edgeStore = RowStore.openWithEdges(n70, SEED, 0, 0x0L, 10)) {
            // Hand-computed, not generated: independently verifiable without running anything.
            // Rows 63/64/65/69 straddle the word boundary a 70-row mask has (word 0 = bits 0..63,
            // word 1 = bits 64..127, of which only 64..69 are real rows) — exactly the tail this
            // section exists to exercise.
            long[] aRows = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 63, 64, 65, 69};
            long[] bRows = {5, 6, 7, 8, 20, 21, 64, 69};
            long[] expected = {0, 1, 2, 3, 4, 9, 63, 65}; // aRows, with bRows removed, by hand

            c.eq("n=70 is not a multiple of 64 -- exactly the tail word this fixture exercises",
                    6, n70 % 64);

            try (Mask a = edgeStore.importRows(aRows);
                 Mask b = edgeStore.importRows(bRows)) {
                c.eq("A's own count matches its row list", aRows.length, a.count());
                c.eq("B's own count matches its row list", bRows.length, b.count());

                try (Mask diff = a.minus(b)) {
                    c.eq("minus() count equals the hand-computed expected set size",
                            expected.length, diff.count());
                    assertSameSet(c, "minus() materialised rows vs the hand-computed set",
                            diff.materializeRows(), expected);
                    c.that("no phantom row at or beyond rowCount() survived the andnot"
                                    + " (the tail rule)",
                            java.util.Arrays.stream(diff.materializeRows())
                                    .noneMatch(row -> row >= n70));
                    c.that("anti-vacuity: the difference selects something, and not everything",
                            diff.count() > 0 && diff.count() < n70);
                }
            }

            c.section("Mask.minus(): a mask minus an empty mask changes nothing, at the same"
                    + " non-multiple-of-64 row count");
            long[] everyRow = new long[n70];
            for (int i = 0; i < n70; i++) {
                everyRow[i] = i;
            }
            try (Mask all = edgeStore.importRows(everyRow);
                 Mask empty = edgeStore.importRows()) {
                c.eq("importRows() of every row selects exactly rowCount() rows", n70, all.count());
                c.eq("importRows() with no arguments selects nothing", 0, empty.count());
                try (Mask result = all.minus(empty)) {
                    c.eq("minus() against an empty set changes nothing", n70, result.count());
                    c.eq("materializeRows() after minus() recovers every one of the 70 rows",
                            n70, result.materializeRows().length);
                    c.that("no phantom row at or beyond rowCount() here either",
                            java.util.Arrays.stream(result.materializeRows())
                                    .noneMatch(row -> row >= n70));
                }
            }
        }

        c.section("RowStore.hop(): the pinned n=2000 fixture, mask-native end to end"
                + " (RowStoreParityTest's own Rust-pinned regression, reproduced via the"
                + " public facade instead of internal.ffm)");
        long hopN = 2000;
        int edgeClassid = 0;
        try (RowStore hopStore = RowStore.openWithEdges(hopN, 0xF00D_CAFEL, edgeClassid, 0x0L, 25)) {
            long[] seedRows = new long[10];
            for (int i = 0; i < 10; i++) {
                seedRows[i] = i * 37L + 5;
            }
            try (Mask seed = hopStore.importRows(seedRows)) {
                c.eq("importRows() of the 10 seed rows selects exactly 10", 10, seed.count());

                try (Mask oneHop = hopStore.hop(edgeClassid, seed)) {
                    c.eq("1-hop count matches the Rust-pinned regression", 19, oneHop.count());

                    try (Mask twoHop = hopStore.hop(edgeClassid, oneHop)) {
                        c.eq("2-hop count matches the Rust-pinned regression", 29, twoHop.count());
                        c.that("three different, non-empty, non-total sizes (anti-vacuity)",
                                seed.count() != oneHop.count()
                                        && oneHop.count() != twoHop.count()
                                        && oneHop.count() > 0 && twoHop.count() > 0
                                        && oneHop.count() < hopN && twoHop.count() < hopN);

                        c.section("materializeRows() on the hop result matches the"
                                + " public-accessor transcription (RowStoreParityTest's own"
                                + " publicHop shape)");
                        long[] publicOneHop = publicHop(hopStore, seedRows, hopN, edgeClassid);
                        long[] publicTwoHop = publicHop(hopStore, publicOneHop, hopN, edgeClassid);
                        assertSameSet(c, "1-hop: mask-native vs public-accessor transcription",
                                oneHop.materializeRows(), publicOneHop);
                        assertSameSet(c, "2-hop: mask-native vs public-accessor transcription",
                                twoHop.materializeRows(), publicTwoHop);
                    }
                }
            }

            c.section("RowStore.hop(): WideFieldMask.ofFacets() with zero positions selects"
                    + " nothing -- an empty facet participation can never match an edge");
            try (Mask seed2 = hopStore.importRows(seedRows);
                 Mask zeroFacetHop = hopStore.hop(edgeClassid, WideFieldMask.ofFacets(), seed2)) {
                c.eq("hop() restricted to zero facets selects nothing", 0, zeroFacetHop.count());
            }

            c.section("RowStore.hop(int, Mask) overload agrees with hop(int, allFacets(), Mask)");
            try (Mask seed3 = hopStore.importRows(seedRows);
                 Mask viaOverload = hopStore.hop(edgeClassid, seed3);
                 Mask viaExplicitAllFacets =
                         hopStore.hop(edgeClassid, WideFieldMask.allFacets(), seed3)) {
                c.eq("the 2-arg overload selects the same count as the 3-arg call with"
                                + " allFacets() spelled out",
                        viaExplicitAllFacets.count(), viaOverload.count());
            }
        }

        c.section("importRows() -> materializeRows() round trip");
        try (RowStore rs = RowStore.open(500, SEED)) {
            long[] chosen = {0, 1, 2, 3, 17, 99, 250, 499};
            try (Mask m = rs.importRows(chosen)) {
                c.eq("importRows() selects exactly the rows given", chosen.length, m.count());
                assertSameSet(c, "importRows() -> materializeRows() round trip",
                        m.materializeRows(), chosen);
            }
            c.throwsUp("importRows() rejects a negative row", IndexOutOfBoundsException.class,
                    () -> rs.importRows(-1L));
            c.throwsUp("importRows() rejects row == rowCount()", IndexOutOfBoundsException.class,
                    () -> rs.importRows(500L));
            try (Mask none = rs.importRows()) {
                c.eq("importRows() with zero rows selects nothing", 0, none.count());
            }
        }

        c.section("W1.1 Mask half: the cached word lane is re-authorised by the substrate,"
                + " not by a Java boolean");
        {
            // The construction the plan mandates (epoch-recheck-v3 §6, W5's shape applied to the
            // Mask half): POPULATE THE CACHE FIRST, then invalidate the native resource behind
            // Java's back, then use the cache again.
            //
            // Populate-first is load-bearing. If the window were still unresolved, words() would
            // call describeMask() fresh and throw on its own, and this test would pass with the
            // probe deleted -- measuring the wrong mechanism entirely. Getting a real count out
            // of materializeRows() first is what proves the cache is warm.
            //
            // Note what is NOT done here: nothing reads the cached address after the free. The
            // probe refuses BEFORE returning the window, so no freed byte is ever dereferenced --
            // which is the whole reason the guard is a substrate question rather than a Java one.
            RowStore rs = RowStore.open(256, SEED);
            Mask m = rs.importRows(0L, 1L, 2L, 63L, 64L, 200L);
            long[] warm = m.materializeRows();
            c.eq("the cache is warm: materializeRows() returned every row before invalidation",
                    6, warm.length);

            // Close the NATIVE resource directly. Java's own bookkeeping is untouched, so
            // requireUsable() still passes and execution reaches the probe -- which is the only
            // thing left that can notice.
            com.adaworldapi.lancegraph.internal.ffm.Engine.close(rs.handle());

            c.throwsUp("a warm cached word lane is refused once the substrate no longer resolves"
                            + " the handle",
                    ClosedResourceException.class,
                    m::materializeRows);

            // ... and it stays refused: the probe cleared the stale window rather than leaving it
            // for a second caller to trip over.
            c.throwsUp("the refusal is not a one-shot -- the stale window was dropped",
                    ClosedResourceException.class,
                    m::materializeRows);

            // count() asks the substrate every time and never touches the cached address, so it
            // fails through the ABI's own status rather than through this probe. Asserted so the
            // two paths are not confused with one another.
            c.throwsUp("count() fails through the ABI status, not through the cache probe",
                    LanceGraphException.class,
                    m::count);
        }

        c.section("WideFieldMask factories and accessors");
        c.eq("allFacets() carries exactly 32 facets", 32, WideFieldMask.allFacets().count());
        c.that("allFacets() has every facet 0..31",
                java.util.stream.IntStream.range(0, 32)
                        .allMatch(i -> WideFieldMask.allFacets().has(i)));
        c.that("allFacets() does not claim facet 32 or facet 63",
                !WideFieldMask.allFacets().has(32) && !WideFieldMask.allFacets().has(63));

        WideFieldMask some = WideFieldMask.ofFacets(0, 5, 31);
        c.eq("ofFacets(0,5,31) carries exactly 3 facets", 3, some.count());
        c.that("ofFacets(0,5,31) has facet 0", some.has(0));
        c.that("ofFacets(0,5,31) has facet 5", some.has(5));
        c.that("ofFacets(0,5,31) has facet 31", some.has(31));
        c.that("ofFacets(0,5,31) does not have facet 1", !some.has(1));

        c.eq("ofFacets() with no positions is the empty mask", 0, WideFieldMask.ofFacets().count());
        c.eq("EMPTY carries zero facets", 0, WideFieldMask.EMPTY.count());

        c.throwsUp("ofFacets(-1) is rejected", IllegalArgumentException.class,
                () -> WideFieldMask.ofFacets(-1));
        c.throwsUp("ofFacets(32) is rejected", IllegalArgumentException.class,
                () -> WideFieldMask.ofFacets(32));

        int matchBits = (1 << 3) | (1 << 9) | (1 << 31);
        WideFieldMask fromMatch = WideFieldMask.ofMatchBits(matchBits);
        WideFieldMask fromFacets = WideFieldMask.ofFacets(3, 9, 31);
        c.eq("ofMatchBits() agrees with the equivalent ofFacets() call", fromFacets, fromMatch);
        c.eq("ofMatchBits() count agrees too", fromFacets.count(), fromMatch.count());

        WideFieldMask fromNegativeOne = WideFieldMask.ofMatchBits(-1); // all 32 bits, incl. sign
        c.eq("ofMatchBits(-1) zero-extends rather than sign-extends: exactly 32 facets, not 64",
                32, fromNegativeOne.count());
        c.eq("ofMatchBits(-1) equals allFacets()", WideFieldMask.allFacets(), fromNegativeOne);
    }

    /**
     * The same "public-accessor hop" transcription {@link RowStoreParityTest} pins independently
     * (its own {@code publicHop} lambda, over {@code classidAt}/{@code payloadHi32At}/{@code
     * payloadLow64At}) — duplicated here rather than shared, for the same reason {@link
     * RowStoreParityTest} itself duplicates its {@code SplitMix64} from {@code
     * FixtureParityTest}: this file needs its own independent copy, not a dependency on another
     * test's private internals.
     */
    private static long[] publicHop(RowStore store, long[] from, long hopN, int edgeClassid) {
        boolean[] seenRows = new boolean[(int) hopN];
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (long row : from) {
            for (int f = 0; f < FACETS_PER_ROW; f++) {
                FacetId facet = new FacetId(f);
                if (store.classidAt(row, facet) != edgeClassid) {
                    continue;
                }
                if (store.payloadHi32At(row, facet) != 0) {
                    continue;
                }
                long target = store.payloadLow64At(row, facet);
                if (target >= 0 && target < hopN && !seenRows[(int) target]) {
                    seenRows[(int) target] = true;
                    out.add(target);
                }
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** Set-equality, independent of the order either array's elements arrived in. */
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

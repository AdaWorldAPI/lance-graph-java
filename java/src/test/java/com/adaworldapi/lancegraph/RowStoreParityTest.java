package com.adaworldapi.lancegraph;

/**
 * A genuine cross-language check for the SoA row store (docs/abi.md §11), in the same spirit as
 * {@link FixtureParityTest}: Java recomputes the expected classids from the normative generator
 * description and compares them against what the native kernels returned, through both entry
 * points the ABI exposes — {@code lgj_op_eq_classid} (via {@link RowStore#maskOfFacetClass}) and
 * {@code lgj_row_facet_match} (via {@link RowStore#facetMatches}).
 *
 * <p>The row-store generator is two SplitMix64 draws per facet ({@code a} then {@code b}), 64
 * draws per row, with {@code classid = (a >>> 33) & 0xF}. Only the classid half of each draw pair
 * is needed here — the payload half ({@code b}, plus the low 32 bits of {@code a}) is opaque to
 * every assertion this file makes — but both draws are still consumed per facet so the recomputed
 * stream stays in lockstep with the Rust generator.
 */
public final class RowStoreParityTest {

    private RowStoreParityTest() {}

    public static void main(String[] args) {
        System.out.println("RowStoreParityTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("RowStoreParityTest"));
        }
        Checks c = new Checks("RowStoreParityTest");
        run(c);
        System.exit(c.report());
    }

    private static final int FACETS_PER_ROW = 32;

    /**
     * SplitMix64, copied from {@link FixtureParityTest}'s private helper of the same name.
     *
     * <p>Provenance: this is the identical transcription, constant-for-constant and
     * shift-for-shift, already checked there against the published reference vector. It is
     * duplicated rather than shared because the original is {@code private} to that class — this
     * file needs its own copy of the generator, not a dependency on another test's internals.
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
     * {@code classids[row][facet]}, recomputed independently in pure Java from the §11 generator.
     */
    private static int[][] generateClassids(int nRows, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        int[][] classids = new int[nRows][FACETS_PER_ROW];
        for (int row = 0; row < nRows; row++) {
            for (int facet = 0; facet < FACETS_PER_ROW; facet++) {
                long a = rng.next();   // FIRST draw of the facet — carries the classid
                rng.next();            // SECOND draw of the facet — the payload's high 64 bits;
                                        // unused here but consumed to keep the stream aligned
                classids[row][facet] = (int) ((a >>> 33) & 0xF);
            }
        }
        return classids;
    }

    private static long countFacetClassid(int[][] classids, int facet, int classId) {
        long count = 0;
        for (int[] row : classids) {
            if (row[facet] == classId) {
                count++;
            }
        }
        return count;
    }

    public static void run(Checks c) {
        long seed = 0xABCDL;

        c.section("maskOfFacetClass(7, c): pure-Java recomputation vs the native mask, several sizes");
        for (int n : new int[] {1, 64, 65, 1000}) {
            int[][] classids = generateClassids(n, seed);
            try (RowStore store = RowStore.open(n, seed)) {
                for (int classId : new int[] {0, 9, 15}) {
                    long expected = countFacetClassid(classids, 7, classId);
                    try (Mask mask = store.maskOfFacetClass(new FacetId(7), classId)) {
                        c.eq("n=" + n + " facet 7 classid==" + classId, expected, mask.count());
                    }
                }
            }
        }

        c.section("the edge facets (0 and 31) at n=1000");
        int n1000 = 1000;
        int[][] classids1000 = generateClassids(n1000, seed);
        try (RowStore store = RowStore.open(n1000, seed)) {
            for (int facet : new int[] {0, 31}) {
                for (int classId : new int[] {0, 9, 15}) {
                    long expected = countFacetClassid(classids1000, facet, classId);
                    try (Mask mask = store.maskOfFacetClass(new FacetId(facet), classId)) {
                        c.eq("facet " + facet + " classid==" + classId, expected, mask.count());
                    }
                }
            }
        }

        c.section("facetMatches(9) at n=1000: per-row 32-bit set and total cardinality");
        int needle = 9;
        long expectedTotalPopcount = 0;
        long mismatches = 0;
        try (RowStore store = RowStore.open(n1000, seed)) {
            FacetMatchView view = store.facetMatches(needle);
            c.eq("facetMatches reports the store's row count", n1000, view.rowCount());
            for (int row = 0; row < n1000; row++) {
                int expectedBits = 0;
                for (int facet = 0; facet < FACETS_PER_ROW; facet++) {
                    if (classids1000[row][facet] == needle) {
                        expectedBits |= (1 << facet);
                    }
                }
                expectedTotalPopcount += Integer.bitCount(expectedBits);
                if (view.matchesOf(row) != expectedBits) {
                    mismatches++;
                }
            }
            c.eq("every one of the 1000 rows' facet-9 bitset matches the pure-Java recomputation",
                    0, mismatches);
            c.eq("cardinality() equals the recomputed total popcount",
                    expectedTotalPopcount, view.cardinality());
        }

        c.section("anti-vacuity guards (the falsifiability rule)");
        long facet7eq9At1000 = countFacetClassid(classids1000, 7, 9);
        c.that("facet 7 classid==9 selects more than nothing at n=1000", facet7eq9At1000 > 0);
        c.that("facet 7 classid==9 selects less than everything at n=1000 — a middling"
                        + " selection, not an all-or-nothing predicate that cannot falsify",
                facet7eq9At1000 < n1000);

        boolean anyRowHasNonZeroFacet9Set = false;
        boolean twoRowsDifferInTheirFacet9Set = false;
        Integer firstBits = null;
        for (int row = 0; row < n1000; row++) {
            int bits = 0;
            for (int facet = 0; facet < FACETS_PER_ROW; facet++) {
                if (classids1000[row][facet] == needle) {
                    bits |= (1 << facet);
                }
            }
            if (bits != 0) {
                anyRowHasNonZeroFacet9Set = true;
            }
            if (firstBits == null) {
                firstBits = bits;
            } else if (bits != firstBits) {
                twoRowsDifferInTheirFacet9Set = true;
            }
        }
        c.that("at least one row has a non-zero facet-9 set", anyRowHasNonZeroFacet9Set);
        c.that("at least one row's facet-9 set differs from another's — the generator actually"
                        + " varies, rather than every row landing on the same bits",
                twoRowsDifferInTheirFacet9Set);

        c.section("FacetId range");
        c.throwsUp("FacetId(-1) is rejected", IllegalArgumentException.class, () -> new FacetId(-1));
        c.throwsUp("FacetId(32) is rejected", IllegalArgumentException.class, () -> new FacetId(32));
        c.eq("FacetId(0) is accepted", 0, new FacetId(0).index());
        c.eq("FacetId(31) is accepted", 31, new FacetId(31).index());

        // The THIRD independent read path (the wave file's original spec item, closing the gap
        // the W3 dispatch honestly flagged as dropped): Java reads every classid DIRECTLY from
        // the raw lane-0 segment, addressing it through ROW_LAYOUT's own byteOffset arithmetic —
        // no native kernel, no mask, no FacetMatchView anywhere on this path. Three ways now
        // reach the same numbers: the native kernels (sections above), the pure-Java generator
        // transcription, and the structured-layout segment read below. This is also
        // Layouts.ROW_LAYOUT's first real consumer — before this section its byteOffset
        // arithmetic was defined, size-checked at class-init, and read by nothing.
        c.section("raw lane-0 segment reads through ROW_LAYOUT offsets vs the generator");
        try (RowStore store = RowStore.open(n1000, seed)) {
            com.adaworldapi.lancegraph.internal.ffm.Engine.LaneWindow win =
                    com.adaworldapi.lancegraph.internal.ffm.Engine.describeLane(store.handle(), 0);
            // The raw lane's own description first: contiguous U8, exactly n*512 bytes — the
            // exact-span rule for a contiguous lane (abi.md §11).
            c.eq("raw lane byte length is n_rows * 512", (long) n1000 * 512L, win.byteLength());
            c.that("raw lane reports contiguous", win.isContiguous());

            java.lang.foreign.MemorySegment raw = win.segment();
            long rowBytes = com.adaworldapi.lancegraph.internal.ffm.Layouts.ROW_LAYOUT.byteSize();
            int layoutMismatches = 0;
            for (int row = 0; row < n1000; row++) {
                for (int facet = 0; facet < FACETS_PER_ROW; facet++) {
                    // The offset comes from the LAYOUT, not from hand-multiplied constants —
                    // that is the point: this section proves ROW_LAYOUT's carving agrees with
                    // the generator, not merely that 16*facet does.
                    long off = com.adaworldapi.lancegraph.internal.ffm.Layouts.ROW_LAYOUT.byteOffset(
                            java.lang.foreign.MemoryLayout.PathElement.sequenceElement(facet),
                            java.lang.foreign.MemoryLayout.PathElement.groupElement("classid"));
                    int got = raw.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED,
                            (long) row * rowBytes + off);
                    if (got != classids1000[row][facet]) {
                        layoutMismatches++;
                    }
                }
            }
            c.eq("all " + (n1000 * FACETS_PER_ROW) + " classids read through ROW_LAYOUT match the"
                    + " transcribed generator", 0, layoutMismatches);
        }
    }
}

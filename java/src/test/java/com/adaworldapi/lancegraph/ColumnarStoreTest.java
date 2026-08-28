package com.adaworldapi.lancegraph;

/**
 * ABI minor 10: the facet-major columnar store, proven LAYOUT-BLIND from Java.
 *
 * <p>The claim under test is the simd.rs-isomorphism's E3 carried to its end: Java holds no
 * spelling of the row geometry at all — every read goes through the lane descriptors the membrane
 * serves — so a store whose bytes are arranged completely differently must answer IDENTICALLY
 * through every facade surface. If any Java code still hand-computed an offset, the columnar
 * store is precisely the input that would expose it: same content, different addresses.
 *
 * <p>DISABLE (verified red-then-green): hard-code {@code strideBytes = 512} in
 * {@code RowStore.lane(int)}'s consumers and every per-row comparison below fails on the columnar
 * store while still passing on AoS — the two-sided proof the descriptors are load-bearing.
 */
public final class ColumnarStoreTest {

    private ColumnarStoreTest() {}

    public static void main(String[] args) {
        System.out.println("ColumnarStoreTest");
        Checks c = new Checks("ColumnarStoreTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        final long n = 2000;
        final long seed = 0xF00DCAFEL;

        try (RowStore aos = RowStore.openWithEdges(n, seed, 0, 0x0L, 25);
                RowStore col = RowStore.openColumnar(n, seed, 0, 0x0L, 25)) {

            c.section("same logical content through the per-row accessors (descriptor-served)");
            long checked = 0;
            for (long row : new long[] {0, 1, 63, 64, 999, n - 1}) {
                for (int f : new int[] {0, 1, 7, 31}) {
                    FacetId facet = FacetId.of(f);
                    if (aos.classidAt(row, facet) != col.classidAt(row, facet)
                            || aos.payloadLow64At(row, facet) != col.payloadLow64At(row, facet)
                            || aos.payloadHi32At(row, facet) != col.payloadHi32At(row, facet)) {
                        c.that("row " + row + " facet " + f + " identical across layouts", false);
                        return;
                    }
                    checked++;
                }
            }
            c.eq("per-row fields identical across layouts (spot grid)", 24L, checked);

            c.section("mask ops answer identically (native, layout-aware inside Rust)");
            long eqA;
            long eqC;
            try (Mask ma = aos.maskOfFacetClass(FacetId.of(7), 9);
                    Mask mc = col.maskOfFacetClass(FacetId.of(7), 9)) {
                eqA = ma.count();
                eqC = mc.count();
            }
            c.eq("eq-classid count, facet 7 needle 9", eqA, eqC);
            c.that("…and non-empty (vacuity guard)", eqA > 0);

            c.section("the pinned hop answers 19 / 29 on the COLUMNAR store");
            try (Mask src = col.importRows(seedRows());
                    Mask h1 = col.hop(0, src);
                    Mask h2 = col.hop(0, h1)) {
                c.eq("1-hop", 19L, h1.count());
                c.eq("2-hop", 29L, h2.count());
            }

            c.section("facet-match surface agrees, including the native slot count");
            FacetMatchView va = aos.facetMatches(9);
            FacetMatchView vc = col.facetMatches(9);
            long rows = 0;
            for (long row = 0; row < n; row++) {
                if (va.matchesOf(row) != vc.matchesOf(row)) {
                    c.that("facet bitset row " + row + " identical", false);
                    return;
                }
                rows++;
            }
            c.eq("facet bitsets identical for every row", n, rows);
            c.eq("native cardinality identical", va.cardinality(), vc.cardinality());
            c.that("…and non-zero", va.cardinality() > 0);

            c.section("the register-sweep family refuses with the LAYOUT status, and only there");
            try (Mask m = col.maskOfFacetClass(FacetId.of(0), 3)) {
                boolean threw = false;
                try {
                    col.facetSumAs(FacetId.of(0), Carving.RAILS_6X2, m);
                } catch (LanceGraphException e) {
                    threw = e.getMessage().contains("layout");
                }
                c.that("facetSumAs on columnar names the layout", threw);
            }
            try (Mask m = aos.maskOfFacetClass(FacetId.of(0), 3)) {
                aos.facetSumAs(FacetId.of(0), Carving.RAILS_6X2, m);
                c.that("the same call still works on AoS (the gate discriminates)", true);
            }
        }
    }

    private static long[] seedRows() {
        long[] rows = new long[10];
        for (int i = 0; i < 10; i++) {
            rows[i] = i * 37L + 5;
        }
        return rows;
    }
}

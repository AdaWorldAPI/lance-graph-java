package com.adaworldapi.lancegraph;

/**
 * Falsifiers for the mask-native sweep (docs/abi.md §14, ABI minor &ge; 5):
 * {@link RowStore#facetSumAs} and {@link Carving}.
 *
 * <p>Every expected value is recomputed <strong>in Java, from the public per-row accessors</strong>
 * ({@link RowStore#payloadLow64At} / {@link RowStore#payloadHi32At}) — never by calling
 * {@code facetSumAs} a second time. That is what makes this a cross-language check rather than a
 * tautology: the native kernel walks mask bits over a 512-byte-strided buffer, while the Java side
 * reconstructs each register from two independent accessors and re-carves it here. The two paths
 * share no code, so agreement is evidence.
 *
 * <p>The register is 12 bytes laid out little-endian as {@code payload_lo64} (bytes 0..7) followed
 * by {@code payload_hi32} (bytes 8..11) — abi.md §11's generator statement. Reassembling those two
 * accessors into the 12 bytes and re-splitting them per carving is the independent transcription.
 */
public final class FacetSumParityTest {

    private FacetSumParityTest() {}

    public static void main(String[] args) {
        System.out.println("FacetSumParityTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("FacetSumParityTest"));
        }
        Checks c = new Checks("FacetSumParityTest");
        run(c);
        System.exit(c.report());
    }

    private static final long SEED = 0x5EEDL;

    /** The facet's 12 register bytes, rebuilt from the two public accessors. */
    private static byte[] register(RowStore s, long row, FacetId f) {
        long lo = s.payloadLow64At(row, f);
        int hi = s.payloadHi32At(row, f);
        byte[] r = new byte[12];
        for (int k = 0; k < 8; k++) {
            r[k] = (byte) (lo >>> (8 * k));
        }
        for (int k = 0; k < 4; k++) {
            r[8 + k] = (byte) (hi >>> (8 * k));
        }
        return r;
    }

    /** Sum one register under one carving, entirely in Java. */
    private static long carve(byte[] reg, Carving carving) {
        long acc = 0;
        for (int g = 0; g < carving.groups(); g++) {
            long v = 0;
            for (int k = 0; k < carving.groupBytes(); k++) {
                v |= (long) (reg[g * carving.groupBytes() + k] & 0xFF) << (8 * k);
            }
            acc += v;
        }
        return acc;
    }

    /** The expected sum over exactly the rows a mask selects, computed row by row in Java. */
    private static long expected(RowStore s, FacetId f, Carving carving, long[] rows) {
        long acc = 0;
        for (long row : rows) {
            acc += carve(register(s, row, f), carving);
        }
        return acc;
    }

    public static void run(Checks c) {
        final int nRows = 1000;
        final FacetId facet = FacetId.of(3);

        try (RowStore store = RowStore.open(nRows, SEED)) {
            c.section("cross-language parity: the native sweep vs Java recomputation from the"
                    + " public per-row accessors, for all three carvings");

            // A real, non-trivial selection: the rows whose facet-3 classid is 7. Using a mask the
            // store itself produced is the point — the build half (maskOfFacetClass) feeding the
            // execution half is the whole shape §14 exists for.
            try (Mask selected = store.maskOfFacetClass(facet, 7)) {
                // materializeRows() is the ONE named O(n) exit, used here to drive the Java-side
                // ORACLE. It is deliberately not on the path under test.
                long[] rows = selected.materializeRows();
                c.that("the selection is non-trivial (some rows, but not all)",
                        rows.length > 0 && rows.length < nRows);

                for (Carving carving : Carving.values()) {
                    c.eq("facetSumAs(" + carving + ") agrees with Java recomputation",
                            expected(store, facet, carving, rows),
                            store.facetSumAs(facet, carving, selected));
                }

                c.section("the three carvings genuinely differ — otherwise the parity checks"
                        + " above would all pass for a kernel that ignored its carving argument");
                long rails = store.facetSumAs(facet, Carving.RAILS_6X2, selected);
                long trips = store.facetSumAs(facet, Carving.TRIPLETS_4X3, selected);
                long quads = store.facetSumAs(facet, Carving.QUADS_3X4, selected);
                c.notEq("rails differs from triplets", rails, trips);
                c.notEq("triplets differs from quads", trips, quads);
                c.notEq("rails differs from quads", rails, quads);
            }

            c.section("the mask SELECTS: an empty selection sums nothing, and disjoint"
                    + " selections are additive (a kernel ignoring the mask fails both)");
            try (Mask a = store.maskOfFacetClass(facet, 1);
                    Mask b = store.maskOfFacetClass(facet, 2)) {
                long[] ra = a.materializeRows();
                long[] rb = b.materializeRows();
                c.that("both selections are non-empty", ra.length > 0 && rb.length > 0);

                long sa = store.facetSumAs(facet, Carving.QUADS_3X4, a);
                long sb = store.facetSumAs(facet, Carving.QUADS_3X4, b);

                // The union is built by IMPORTING the two row sets — the named import exception,
                // used for a test oracle. Classids 1 and 2 are disjoint by construction, so the
                // union's sum must be exactly sa + sb.
                long[] union = new long[ra.length + rb.length];
                System.arraycopy(ra, 0, union, 0, ra.length);
                System.arraycopy(rb, 0, union, ra.length, rb.length);
                try (Mask both = store.importRows(union)) {
                    c.eq("union of disjoint selections sums to the sum of the parts",
                            sa + sb, store.facetSumAs(facet, Carving.QUADS_3X4, both));
                }
            }

            c.section("an empty selection costs nothing and sums to zero");
            try (Mask empty = store.importRows()) {
                c.eq("an empty mask selects no rows", 0L, empty.count());
                c.eq("an empty selection sums to zero", 0L,
                        store.facetSumAs(facet, Carving.QUADS_3X4, empty));
            }

            c.section("work scales with popcount, not row count (docs/abi.md §6): a one-row"
                    + " selection over a 1000-row store equals that row's own register");
            try (Mask one = store.importRows(517)) {
                c.eq("a single-row sweep equals that row, recomputed independently",
                        carve(register(store, 517, facet), Carving.TRIPLETS_4X3),
                        store.facetSumAs(facet, Carving.TRIPLETS_4X3, one));
            }

            c.section("guards");
            try (Mask m = store.maskOfFacetClass(facet, 7)) {
                c.throwsUp("a null carving is rejected", NullPointerException.class,
                        () -> store.facetSumAs(facet, null, m));
                c.throwsUp("a null selection is rejected", NullPointerException.class,
                        () -> store.facetSumAs(facet, Carving.RAILS_6X2, null));
            }

            c.section("a mask over a DIFFERENT store is rejected even at the same row count —"
                    + " an equal-length mask over another resource is a different population");
            try (RowStore other = RowStore.open(nRows, SEED ^ 1L);
                    Mask foreign = other.maskOfFacetClass(facet, 7)) {
                c.throwsUp("a foreign-parent mask of the same length is rejected",
                        RuntimeException.class,
                        () -> store.facetSumAs(facet, Carving.RAILS_6X2, foreign));
            }
        }

        c.section("Carving's own invariant: every reading covers exactly the 12-byte register");
        for (Carving carving : Carving.values()) {
            c.eq(carving + " covers 12 bytes", 12L,
                    (long) carving.groups() * carving.groupBytes());
        }
    }
}

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

        c.section("the RESOLVED sweep (abi.md §15): the grouping comes FROM the population,"
                + " and a population that does not resolve to one is refused");
        try (RowStore store = RowStore.open(1000, SEED)) {
            // A single-class selection resolves — and to that class's OWN grouping, which the
            // fixture provider varies as class % 3. Checked across all three so an implementation
            // returning one constant fails.
            int[] classids = {3, 4, 5};
            Carving[] want = {Carving.RAILS_6X2, Carving.TRIPLETS_4X3, Carving.QUADS_3X4};
            for (int i = 0; i < classids.length; i++) {
                try (Mask m = store.maskOfFacetClass(facet, classids[i])) {
                    c.that("classid " + classids[i] + " selects a non-empty population",
                            m.count() > 0);
                    FacetSum r = store.facetSum(facet, m);
                    c.eq("classid " + classids[i] + " resolves to its own grouping",
                            want[i], r.carving());
                    // The resolved sweep must agree with the raw one told the SAME grouping --
                    // otherwise "resolved" would be a different computation, not a verified one.
                    c.eq("resolved sum == facetSumAs under the resolved grouping",
                            store.facetSumAs(facet, r.carving(), m), r.sum());
                }
            }

            c.section("a population spanning classes that read the register differently is"
                    + " REFUSED -- paired with classes that share a grouping, which must not be");
            try (Mask a = store.maskOfFacetClass(facet, 3);
                    Mask b = store.maskOfFacetClass(facet, 4)) {
                long[] ra = a.materializeRows();
                long[] rb = b.materializeRows();
                long[] mixed = new long[ra.length + rb.length];
                System.arraycopy(ra, 0, mixed, 0, ra.length);
                System.arraycopy(rb, 0, mixed, ra.length, rb.length);
                try (Mask both = store.importRows(mixed)) {
                    c.throwsUp("classids 3 and 4 read the register differently -> refused",
                            RuntimeException.class, () -> store.facetSum(facet, both));
                }
            }
            // 3 and 6 are both class % 3 == 0, so they SHARE a grouping: a multi-class population
            // must still resolve. Without this, the refusal above would pass for an
            // implementation that rejected every multi-class population.
            try (Mask a = store.maskOfFacetClass(facet, 3);
                    Mask b = store.maskOfFacetClass(facet, 6)) {
                long[] ra = a.materializeRows();
                long[] rb = b.materializeRows();
                long[] union = new long[ra.length + rb.length];
                System.arraycopy(ra, 0, union, 0, ra.length);
                System.arraycopy(rb, 0, union, ra.length, rb.length);
                try (Mask both = store.importRows(union)) {
                    c.that("classids 3 and 6 both select rows",
                            ra.length > 0 && rb.length > 0);
                    c.eq("different classes that SHARE a grouping still resolve",
                            Carving.RAILS_6X2, store.facetSum(facet, both).carving());
                }
            }

            c.section("an empty selection is refused rather than defaulted -- zero rows carry"
                    + " zero classes, so any grouping would be invented");
            try (Mask empty = store.importRows()) {
                c.eq("the selection really is empty", 0L, empty.count());
                c.throwsUp("an empty population does not resolve", RuntimeException.class,
                        () -> store.facetSum(facet, empty));
            }
        }

        c.section("Carving's own invariant: every reading covers exactly the 12-byte register");
        for (Carving carving : Carving.values()) {
            c.eq(carving + " covers 12 bytes", 12L,
                    (long) carving.groups() * carving.groupBytes());
        }
    }
}

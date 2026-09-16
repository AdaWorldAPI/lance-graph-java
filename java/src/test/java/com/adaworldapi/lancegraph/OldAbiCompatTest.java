package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Abi;

/**
 * Falsifier for the ABI-minor feature gate against a genuinely OLDER native library.
 *
 * <p>This suite is the one that proves {@link Abi#requireMinor(int)} does what its own javadoc
 * promises — "fails loudly, before any downcall for the feature is attempted". Measured on review,
 * it did not: every handle in {@code Downcalls} was a static final resolved in {@code <clinit>}, so
 * a single absent symbol broke the whole class and the guard never ran.
 *
 * <p>Measured with the current Java against real libraries built from this repo's own history,
 * BEFORE the fix — each died in {@code Downcalls.<clinit>} on the first symbol from a LATER minor:
 *
 * <pre>
 *   minor 1 library -&gt; SmokeTest died on 'lgj_rowstore_open'             (a minor-2 symbol)
 *   minor 2 library -&gt; SmokeTest died on 'lgj_rowstore_open_with_edges'  (minor 3)
 *   minor 3 library -&gt; SmokeTest died on 'lgj_mask_andnot'               (minor 4)
 *   minor 4 library -&gt; SmokeTest died on 'lgj_reduce_facet_sum'          (minor 5)
 * </pre>
 *
 * <p>Note the severity: against the minor-1 library, minor-1 operations could not run either.
 *
 * <p>Two properties, and BOTH are needed — either alone is satisfiable by a broken build:
 *
 * <ol>
 *   <li>the minor-1 base surface still <em>works</em> against the old library, and</li>
 *   <li>each later minor is gated INDEPENDENTLY — available when the library has it, and
 *       {@link AbiMismatchException} naming that minor when it does not, never a bare
 *       "no such symbol" and never a failure of some other minor's feature.</li>
 * </ol>
 *
 * <p><strong>Skipped unless {@code -Dlgj.oldlibrary=/path/to/an/older/liblgj_abi.so} is set</strong>,
 * because it needs a second artifact this repo does not ship. Build one from a commit predating the
 * minor it tests — this repo's own history has one per minor. It is not part of {@code AllTests} for the same reason: a suite that silently
 * passes when its subject is absent is worse than one that is absent.
 */
public final class OldAbiCompatTest {

    private OldAbiCompatTest() {}

    public static void main(String[] args) {
        System.out.println("OldAbiCompatTest");
        String old = System.getProperty("lgj.oldlibrary");
        if (old == null || old.isBlank()) {
            System.out.println("  SKIPPED - set -Dlgj.oldlibrary=/path/to/older/liblgj_abi.so");
            System.out.println("  (and point -Dlgj.library at the SAME file, so the runtime loads"
                    + " it rather than a newer one found by the search path)");
            System.exit(2);
        }
        Checks c = new Checks("OldAbiCompatTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        int loaded = NativeRuntime.abiMinor();
        c.section("what this library actually is (everything below is relative to it)");
        c.that("the loaded library reports a minor: " + loaded, loaded >= 1);

        c.section("(1) the minor-1 base surface works — a symbol missing from a LATER minor must"
                + " not break unrelated, older features during class initialization");
        try (NativePattern p = NativePattern.open(1024)) {
            long n = p.view().where(Pattern.CLASS.eq(3)).count();
            c.that("a minor-1 fluent count succeeded, returning " + n, n >= 0);
        }

        c.section("(2) each later minor is gated INDEPENDENTLY: available if the library has it,"
                + " AbiMismatchException naming the minor if not — never a bare missing-symbol"
                + " failure, and never a failure of some OTHER minor's feature");

        // Minor 2 — the row store.
        gate(c, loaded, 2, "RowStore.open", () -> {
            try (RowStore s = RowStore.open(64, 0x1234L)) {
                if (!s.isOpen()) {
                    throw new IllegalStateException("store did not open");
                }
            }
        });

        // Minor 3 — the edge-bearing constructor.
        gate(c, loaded, 3, "RowStore.openWithEdges", () -> {
            try (RowStore s = RowStore.openWithEdges(64, 0x1234L, 0, 0x0L, 4)) {
                if (!s.isOpen()) {
                    throw new IllegalStateException("store did not open");
                }
            }
        });

        // Minor 11 — the parameterised min/max reduction. Gated here, UNCONDITIONALLY, rather
        // than nested inside the `if (loaded >= 2)` block below: this feature is built entirely
        // on NativePattern, the same minor-1 base surface already exercised in section (1)
        // above, and has no RowStore dependency at all — unlike every other minor-11+ gate
        // below, each of which needs a minor-2 RowStore just to construct its own fixture (a
        // Mask, a facet sum, ...). Nesting this gate the same way would have SKIPPED it
        // entirely against a genuinely minor-1 library — the exact library this compatibility
        // suite exists to check against, and precisely the case a review caught: an
        // eager-resolution or missing-symbol regression in lgj_reduce_i32 would have passed
        // this run clean.
        gate(c, loaded, 11, "View.minOf/maxOf", () -> {
            try (NativePattern p = NativePattern.open(64, 0x1234L)) {
                java.util.OptionalLong mn = p.view().minOf(Pattern.VALUE);
                java.util.OptionalLong mx = p.view().maxOf(Pattern.VALUE);
                if (mn.isEmpty() || mx.isEmpty()) {
                    throw new IllegalStateException("64 rows must select something");
                }
            }
        });

        // Minor 4 — mask complement. Needs a minor-2 store to build masks on, so it is only
        // meaningful once the library has minor 2 as well.
        if (loaded >= 2) {
            gate(c, loaded, 4, "Mask.minus", () -> {
                try (RowStore s = RowStore.open(64, 0x1234L);
                        Mask a = s.maskOfFacetClass(FacetId.of(0), 3);
                        Mask b = s.maskOfFacetClass(FacetId.of(0), 4);
                        Mask d = a.minus(b)) {
                    if (d.count() > 64) {
                        throw new IllegalStateException("impossible count");
                    }
                }
            });

            // Minor 5 — the mask-native sweep, under an asserted grouping.
            gate(c, loaded, 5, "RowStore.facetSumAs", () -> {
                try (RowStore s = RowStore.open(64, 0x1234L);
                        Mask m = s.maskOfFacetClass(FacetId.of(0), 3)) {
                    s.facetSumAs(FacetId.of(0), Carving.RAILS_6X2, m);
                }
            });

            // Minor 6 — the same sweep under a RESOLVED grouping. Uses a
            // single-class mask so the population genuinely resolves; a mixed
            // one would throw for a reason unrelated to the ABI minor and would
            // make this gate report the wrong thing.
            gate(c, loaded, 6, "RowStore.facetSum (resolved)", () -> {
                try (RowStore s = RowStore.open(64, 0x1234L);
                        Mask m = s.maskOfFacetClass(FacetId.of(0), 3)) {
                    if (m.count() == 0) {
                        throw new IllegalStateException("fixture selected no rows");
                    }
                    s.facetSum(FacetId.of(0), m);
                }
            });

            // Minor 9 — the native facet-match count (minors 7 and 8 have no
            // Java-reachable NEW symbol to gate: 7's probe is exercised by its
            // own suite and 8 added manifest data only). The reduction Java
            // used to run itself; against an older library the gate must name
            // minor 9, never fall back to a Java-side loop.
            gate(c, loaded, 9, "FacetMatchView.cardinality (native count)", () -> {
                try (RowStore s = RowStore.open(64, 0x1234L)) {
                    long total = s.facetMatches(3).cardinality();
                    if (total > 64L * 32L) {
                        throw new IllegalStateException("impossible count " + total);
                    }
                }
            });

            // Minor 10 — the facet-major columnar constructor. Against an
            // older library the gate must name minor 10; with it, the store
            // must answer through the same facade as AoS.
            gate(c, loaded, 10, "RowStore.openColumnar", () -> {
                try (RowStore s = RowStore.openColumnar(64, 0x1234L)) {
                    if (!s.isOpen()) {
                        throw new IllegalStateException("columnar store did not open");
                    }
                }
            });

            // Minor 11 — the masking-op completion: mask_ternlog and TCAM
            // ternary-match over the V3 register. Against an older library each
            // gate must name minor 11, never a missing symbol and never a
            // failure of some OTHER minor's feature. (The min/max reduce sibling
            // of this trio has no RowStore dependency and is gated unconditionally
            // above, before this block — see its own comment for why.)
            gate(c, loaded, 11, "Mask.ternlog", () -> {
                try (RowStore s = RowStore.open(64, 0x1234L);
                        Mask a = s.maskOfFacetClass(FacetId.of(0), 3);
                        Mask b = s.maskOfFacetClass(FacetId.of(0), 4);
                        Mask d = a.ternlog(a, b, 0xFC)) { // OR
                    if (d.count() > 64) {
                        throw new IllegalStateException("impossible count");
                    }
                }
            });

            gate(c, loaded, 11, "RowStore.maskOfFacetTernaryMatch", () -> {
                try (RowStore s = RowStore.open(64, 0x1234L);
                        Mask m = s.maskOfFacetTernaryMatch(FacetId.of(0), 0L, 0, 0L, 0)) {
                    if (m.count() != 64) {
                        throw new IllegalStateException("care=0 must match every row");
                    }
                }
            });
        } else {
            c.note("minors 4 and 5 need a minor-2 row store to build a mask on; skipped here"
                    + " because this library predates it");
        }
    }

    /**
     * One minor's gate, checked in whichever direction this library calls for.
     *
     * <p>Both directions matter and neither alone is sufficient. If the library HAS the minor the
     * feature must actually work — a gate that rejected everything would pass a
     * rejection-only check. If it does NOT, the failure must be
     * {@link AbiMismatchException} naming the minor, not the bare {@code LanceGraphException}
     * that eager handle resolution produced.
     */
    private static void gate(Checks c, int loaded, int minor, String what, Runnable body) {
        if (loaded >= minor) {
            try {
                body.run();
                c.that(what + " (minor " + minor + ") works — the library has it", true);
            } catch (RuntimeException e) {
                c.that(what + " (minor " + minor + ") should work but threw " + e, false);
            }
        } else {
            c.throwsUp(what + " (minor " + minor + ") reports an ABI mismatch, not a missing"
                    + " symbol", AbiMismatchException.class, body);
        }
    }
}

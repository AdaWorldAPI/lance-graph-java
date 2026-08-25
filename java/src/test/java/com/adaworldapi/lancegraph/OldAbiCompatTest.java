package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Abi;

/**
 * Falsifier for the ABI-minor feature gate against a genuinely OLDER native library.
 *
 * <p>This suite is the one that proves {@link Abi#requireMinor(int)} does what its own javadoc
 * promises — "fails loudly, before any downcall for the feature is attempted". Measured on review,
 * it did not: every handle in {@code Downcalls} was a static final resolved in {@code <clinit>}, so
 * a single absent symbol broke the whole class. Running this Java build against a real ABI 0.4
 * library, {@code SmokeTest} — which touches nothing newer than minor 1 — died with
 * {@code Downcalls.<clinit>} / "exports no symbol 'lgj_reduce_facet_sum'". The guard never ran.
 *
 * <p>Two properties, and BOTH are needed — either alone is satisfiable by a broken build:
 *
 * <ol>
 *   <li>an operation older than the gate still <em>works</em> against the old library, and</li>
 *   <li>the minor-5 operation alone fails, with {@link AbiMismatchException} naming the minor —
 *       not a bare "no such symbol" from handle resolution.</li>
 * </ol>
 *
 * <p><strong>Skipped unless {@code -Dlgj.oldlibrary=/path/to/an/older/liblgj_abi.so} is set</strong>,
 * because it needs a second artifact this repo does not ship. Build one from a commit predating the
 * minor it tests. It is not part of {@code AllTests} for the same reason: a suite that silently
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
        c.section("the loaded library really is older than the feature under test"
                + " (otherwise everything below is vacuous)");
        c.that("loaded ABI minor < 5, actually " + loaded, loaded < 5);

        c.section("(1) an operation older than the gate still works — a missing minor-5 symbol"
                + " must not break unrelated features during class initialization");
        try (NativePattern p = NativePattern.open(1024)) {
            long n = p.view().where(Pattern.CLASS.eq(3)).count();
            c.that("a minor-1 fluent count succeeded, returning " + n, n >= 0);
        }

        c.section("(2) the minor-5 operation alone fails, and fails with the INTENDED exception");
        try (RowStore store = RowStore.open(64, 0x1234L);
                Mask m = store.maskOfFacetClass(FacetId.of(0), 3)) {
            c.that("the row store (minor 2) still opened against this old library", store.isOpen());
            c.throwsUp("facetSumAs reports an ABI mismatch, not a missing symbol",
                    AbiMismatchException.class,
                    () -> store.facetSumAs(FacetId.of(0), Carving.RAILS_6X2, m));
        }
    }
}

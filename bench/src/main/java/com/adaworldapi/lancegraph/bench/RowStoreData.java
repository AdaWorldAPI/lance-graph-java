package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.FacetMatchView;
import com.adaworldapi.lancegraph.NativeAccess;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;
import com.adaworldapi.lancegraph.internal.ffm.Engine;

import java.lang.foreign.MemorySegment;

/**
 * One open {@link RowStore} plus the raw lane-0 buffer every benchmark arm in
 * {@link F_RowStoreFacetScan} needs, and — mirroring {@link Data}'s own non-negotiable rule — the
 * cross-check that every implementation under comparison computes the same answer, run in
 * {@code @Setup} rather than in a test.
 *
 * <p>Deliberately a <em>separate</em> class from {@link Data} rather than an extension of it:
 * {@code Data}'s fields and constructor are specific to the flat 3-lane fixture ({@code
 * NativePattern}, lanes {@code id/class/value}); the row store is a different native resource
 * ({@code RowStore}, a single {@code U8} raw lane plus 32 strided facet lanes) with a different
 * generator and a different question. Keeping them apart is the same "one {@code Setup} per shape"
 * discipline {@link C_ExecutionBoundary} already models by keeping Components C and D — but not an
 * unrelated fixture — in one class.
 */
public final class RowStoreData implements AutoCloseable {

    /**
     * The classid queried in every facet-match arm. {@code 9} matches the value already used as a
     * worked example in {@code RowStoreLifetimeTest} and {@code RowStoreParityTest} — no new magic
     * number introduced for the bench.
     */
    public static final int CLASSID_NEEDLE = 9;

    private static final long SEED = 0xF00DL;

    public final RowStore store;
    public final int rows;

    /**
     * Lane 0 of the row store: the raw {@code rows * 512}-byte {@code U8} buffer, read in place.
     * Fetched via {@code Engine.describeLane(NativeAccess.handleOf(store), NativeAccess
     * .LANE_ROWSTORE_RAW)} directly rather than through a new {@code NativeAccess.lane(RowStore,
     * int)} overload — {@link NativeAccess#lane} is typed to {@code NativePattern} and adding a
     * second overload just to shave one call at this single site was not worth widening that
     * bridge class's surface for.
     */
    public final MemorySegment raw;

    public RowStoreData(int rows) {
        if (!NativeRuntime.isAvailable()) {
            throw new IllegalStateException("native library unavailable: "
                    + NativeRuntime.unavailableReason().getMessage()
                    + "\nbuild it with:  cd native/lgj-abi && "
                    + "CARGO_TARGET_DIR=<repo>/target cargo build --release");
        }
        this.rows = rows;
        this.store = RowStore.open(rows, SEED);

        Engine.LaneWindow w = Engine.describeLane(
                NativeAccess.handleOf(store), NativeAccess.LANE_ROWSTORE_RAW);
        this.raw = w.segment();

        crossCheck();
    }

    /**
     * Every facet-match implementation under comparison must agree, row by row, before any of them
     * is timed — {@link Data#crossCheck}'s exact rationale: a Vector kernel with a folding bug can
     * be <em>faster</em> than a correct one and look like a win if nothing checks the answer first.
     *
     * <p>Checks BOTH {@link Kernels#facetMatchVector} and {@link Kernels#facetMatchScalar} against
     * the native {@link FacetMatchView}, not just one — a defect confined to either Java arm alone
     * would otherwise ship silently.
     */
    private void crossCheck() {
        int[] vec = new int[rows];
        int[] sca = new int[rows];
        Kernels.facetMatchVector(raw, rows, CLASSID_NEEDLE, vec);
        Kernels.facetMatchScalar(raw, rows, CLASSID_NEEDLE, sca);

        FacetMatchView nativeView = store.facetMatches(CLASSID_NEEDLE);
        for (int row = 0; row < rows; row++) {
            int n = nativeView.matchesOf(row);
            if (n != vec[row]) {
                throw new AssertionError("facet-match disagreement (vector) at rows=" + rows
                        + " row=" + row + ": native=" + n + " vector=" + vec[row]);
            }
            if (n != sca[row]) {
                throw new AssertionError("facet-match disagreement (scalar) at rows=" + rows
                        + " row=" + row + ": native=" + n + " scalar=" + sca[row]);
            }
        }
    }

    @Override
    public void close() {
        store.close();
    }
}

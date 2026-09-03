package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;

/**
 * The {@code RowStore} half of W1.1, as a <strong>build-time seam</strong> — this file is the
 * variant that ships: it does nothing.
 *
 * <p>{@code .claude/plans/epoch-recheck-v3.md} §5.5 rules that the per-access liveness probe on
 * {@code RowStore.lane(int)} is measured by a <em>build-time variant swap</em>, never by an
 * {@code if (guardEnabled)} inside the accessor: a runtime flag is hoistable and is not the shape
 * either arm ships. Two production variants therefore cannot share one classpath
 * ({@code ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT}), so the gate compiles {@code java/} twice with
 * exactly one file swapped — this one. {@code bench/variants/probed/LaneProbe.java} is the
 * {@code after} arm: it re-describes the lane through {@code lgj_lane_describe} on every accessor
 * call and compares the descriptor against the cached one, the same shape {@code Mask.words()}
 * shipped in #53.
 *
 * <p>What this buys over an inline edit of {@code RowStore.lane}: both arms are the
 * <em>production</em> accessor as the JIT sees it — {@code requireOpen}, the cached {@code lanes[]}
 * lookup, the {@code FacetId} null-check, the bounds check — with only the probe's presence
 * differing. §5.4: "that total is the right thing to gate on", not a bench-local reconstruction of
 * the accessor's body, which is what Component H measured and why its run is void as a gate.
 *
 * <p>A static call to an empty method is inlined to nothing; there is no branch here to hoist and
 * no flag to read. Package-private on purpose: this is not API, and {@code ApiSurfaceTest} would
 * be right to object if it were.
 *
 * <p><strong>Ships as a no-op until the §5 gate returns PASS.</strong> {@code RowStore}'s cached
 * lanes are therefore still guarded only by its own non-volatile {@code closed} flag —
 * {@code ISS-LGJ-EPOCH-UNCHECKED} stays OPEN for {@code RowStore} and
 * {@code ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW} with it. If the gate passes, the probed
 * variant moves here and the bench variant becomes the no-op; the swap is one file either way.
 */
final class LaneProbe {
    private LaneProbe() {}

    /**
     * Ship variant: return the cached window untouched. No crossing, no comparison.
     *
     * @param resource the row store's generation-checked handle (unused here)
     * @param laneId   the ABI lane id (unused here)
     * @param cached   the window resolved on first touch
     * @return {@code cached}
     */
    static Engine.LaneWindow check(long resource, int laneId, Engine.LaneWindow cached) {
        return cached;
    }
}

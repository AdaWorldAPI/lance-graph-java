package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;

/**
 * The {@code after} arm of the §5 gate — <strong>NOT on the production classpath.</strong>
 *
 * <p>{@code bench/gate-run.sh} compiles {@code java/src/main} with the shipping
 * {@code LaneProbe.java} replaced by this file, and nothing else changed. Every accessor call on
 * {@code RowStore} then pays one {@code lgj_lane_describe} crossing and a descriptor comparison —
 * the per-access liveness probe {@code epoch-recheck-v3.md} §6 specifies for the {@code RowStore}
 * half, mirroring {@code Mask.words()} (#53): the handle is resolved through the generation-checked
 * registry (fails closed on a stale one), and a moved epoch or a changed byte length means the
 * cached address describes an earlier state and must not be read.
 *
 * <p>If the gate returns PASS this body moves to {@code java/src/main} and the no-op moves here.
 * Keep the two files' signatures identical; the swap is a file copy.
 */
final class LaneProbe {
    private LaneProbe() {}

    static Engine.LaneWindow check(long resource, int laneId, Engine.LaneWindow cached) {
        Engine.LaneWindow fresh;
        try {
            fresh = Engine.describeLane(resource, laneId);
        } catch (LanceGraphException e) {
            throw new ClosedResourceException(
                    "lane " + laneId + " was resolved earlier, but the substrate no longer"
                            + " describes it (" + e.getMessage() + "). The cached address must"
                            + " not be read.");
        }
        if (fresh.epoch() != cached.epoch() || fresh.byteLength() != cached.byteLength()) {
            throw new ClosedResourceException(
                    "lane " + laneId + " was described at epoch " + cached.epoch()
                            + " but the substrate now reports epoch " + fresh.epoch()
                            + ". The cached address describes an earlier state and must not be"
                            + " read.");
        }
        return fresh;
    }
}

package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;

/**
 * A read-only bridge into the library's package-private handle, for measurement code only.
 *
 * <p><strong>Why this exists rather than a public accessor.</strong> The production API
 * deliberately never surfaces a handle or a {@code MemorySegment} — that is the whole accessibility
 * argument. But the lab has to reach the raw lane to build the very thing the thesis says you
 * should not build (65,536 Java objects), and it must build them from <em>the same bytes</em> the
 * native kernel reads, or the comparison is between two different datasets and proves nothing.
 *
 * <p>So the bridge lives here, in the library's package but in the <em>lab's</em> source tree,
 * compiled onto the classpath as a split package. Nothing under {@code java/} changes, no public
 * surface widens, and the coupling is visible in one file instead of leaking into the API.
 *
 * <p>It reads. It never writes, never closes, never mutates.
 */
public final class NativeAccess {

    private NativeAccess() {}

    /** Lane 0 — {@code u64} entity ids. */
    public static final int LANE_ID = 0;
    /** Lane 1 — {@code u32} class tags. */
    public static final int LANE_CLASS = 1;
    /** Lane 2 — {@code i32} signed values. */
    public static final int LANE_VALUE = 2;

    /**
     * {@link RowStore} lane 0 — the raw {@code n_rows * 512}-byte buffer, {@code U8}/stride 1,
     * {@code READABLE | CONTIGUOUS} (abi.md §11). Distinct constant from {@link #LANE_ID} even
     * though both are numerically 0: they describe different resource kinds ({@link NativePattern}
     * vs {@link RowStore}), and giving the row-store raw lane its own name avoids a reader
     * mistaking one resource's lane 0 for the other's.
     */
    public static final int LANE_ROWSTORE_RAW = 0;

    /** The generation-checked registry handle behind a pattern. Opaque; for describe calls only. */
    public static long handleOf(NativePattern pattern) {
        return pattern.handle();
    }

    /** The generation-checked registry handle behind a row store. Opaque; for describe calls only. */
    public static long handleOf(RowStore store) {
        return store.handle();
    }

    /**
     * A bounded, read-only window onto one native lane. No membrane crossing happens when this is
     * read — that is the point of the design, and the reason a Java-side Vector API kernel can
     * compete at all.
     */
    public static Engine.LaneWindow lane(NativePattern pattern, int laneId) {
        return Engine.describeLane(pattern.handle(), laneId);
    }

    /** The packed {@code u64} words behind a selection. */
    public static Engine.LaneWindow maskWords(Mask mask) {
        return Engine.describeMask(mask.id().token());
    }
}

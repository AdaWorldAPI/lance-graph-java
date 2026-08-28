package com.adaworldapi.lancegraph;

/**
 * Which of a {@link RowStore} row's 32 facet lanes to read.
 *
 * <p><strong>Not a lane id.</strong> abi.md §11 is explicit that {@code lgj_op_eq_classid} takes a
 * facet index, not a lane id — lane {@code 0} is the raw buffer and facet {@code f}'s lane id is
 * {@code 1 + f}. Mixing the two up is exactly the bug the end-to-end test pins (facet {@code 32}
 * is invalid while lane {@code 32} is valid). A distinct type is what stops that confusion at the
 * call site instead of relying on a comment.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>Same rules as {@link LaneId} and {@link Ordinal}: final, immutable, identity-free, so the
 * same source compiles as a {@code value record} under JEP 401. It wraps a single {@code int}
 * purely so a facet index cannot be confused with a lane id, a classid, or a row number, and under
 * Valhalla that type-safety is expected to cost nothing at all.
 *
 * @param index zero-based facet index, {@code 0..32} exclusive (a row has exactly 32 facets)
 */
public record FacetId(int index) {

    /** Facets per row — 32, the canonical `32 × 16 B = 512 B` row (abi.md §11). */
    public static final int COUNT = 32;


    public FacetId {
        if (index < 0 || index > 31) {
            throw new IllegalArgumentException("facet index must be in 0..31, was " + index);
        }
    }

    public static FacetId of(int index) {
        return new FacetId(index);
    }

    @Override
    public String toString() {
        return "facet#" + index;
    }
}

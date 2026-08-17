package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Layouts;
import com.adaworldapi.lancegraph.internal.ffm.PlanOp;

/**
 * A signed 32-bit column — a measurement, a score, a delta that can go negative.
 *
 * <p>{@link #gt(int)} is a <em>signed</em> comparison, which is not a detail: the fixture's values
 * straddle zero precisely so that an implementation which compared unsigned by mistake would be
 * caught rather than accidentally agreeing.
 *
 * <p>Only the comparisons the membrane implements are offered — see {@link U32Field} for why that
 * is a feature rather than a gap.
 */
public final class I32Field extends Field {

    /** <strong>Generator surface.</strong> Called by schema-vocabulary code, not by consumers. */
    public I32Field(String name, LaneId lane, Ordinal ordinal) {
        super(name, lane, ordinal);
    }

    /** Rows whose value in this column is strictly greater than {@code threshold}, signed. */
    public Predicate gt(int threshold) {
        return new Predicate(
                PlanOp.narrowing(Layouts.OP_GT_I32, lane().index(), threshold),
                name() + " > " + threshold);
    }

    @Override
    public String elementType() {
        return "i32";
    }
}

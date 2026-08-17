package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Layouts;
import com.adaworldapi.lancegraph.internal.ffm.PlanOp;

/**
 * An unsigned 32-bit column — a tag, a class, an enum-like discriminator.
 *
 * <p>The comparison methods here are exactly the ones the membrane has a kernel for. That is the
 * type-safety story working in both directions: {@code Pattern.CLASS.gt("Berlin")} does not
 * compile because a string is not a tag, and a comparison the native side cannot execute is not
 * offered in the first place, so it cannot fail at runtime with an unknown opcode.
 */
public final class U32Field extends Field {

    /**
     * <strong>Generator surface.</strong> Called by schema-vocabulary code, not by consumers.
     *
     * @param name    the field's name in the schema
     * @param lane    the column it reads
     * @param ordinal its position in the schema
     */
    public U32Field(String name, LaneId lane, Ordinal ordinal) {
        super(name, lane, ordinal);
    }

    /** Rows whose value in this column equals {@code value}. */
    public Predicate eq(int value) {
        return new Predicate(
                PlanOp.narrowing(Layouts.OP_EQ_U32, lane().index(), Integer.toUnsignedLong(value)),
                name() + " == " + Integer.toUnsignedString(value));
    }

    @Override
    public String elementType() {
        return "u32";
    }
}

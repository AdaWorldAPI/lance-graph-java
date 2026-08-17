package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.PlanOp;

/**
 * A single condition, obtained from a schema field: {@code Pattern.CLASS.eq(7)}.
 *
 * <p><strong>This is a descriptor, not a lambda</strong>, and that is the design's hinge. A
 * {@code java.util.function.Predicate<Row>} would look more idiomatic and would be catastrophic: it
 * forces a {@code Row} object per row and an upcall per row — 64,000 objects and 64,000 crossings
 * for 64,000 entities. As a descriptor, N conditions marshal into one contiguous little array and
 * cross <em>once</em>, whatever N and whatever the row count.
 *
 * <p>So the fluent chain looks like ordinary Java and behaves like a query planner. The developer
 * writes what they mean; the shape of what they wrote is what makes it fast.
 *
 * <p>Opaque on purpose: there is no accessor for the opcode, the lane, or the operand. Those are
 * physics. What a caller can do with a {@code Predicate} is pass it to {@link View#where}.
 */
public final class Predicate {

    private final PlanOp op;
    private final String description;

    Predicate(PlanOp op, String description) {
        this.op = op;
        this.description = description;
    }

    /** The marshallable form. Package-private: this is the membrane's business. */
    PlanOp op() {
        return op;
    }

    /** Human-readable form, e.g. {@code "class == 7"}. Diagnostics only. */
    @Override
    public String toString() {
        return description;
    }
}

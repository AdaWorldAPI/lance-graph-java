package com.adaworldapi.lancegraph.internal.ffm;

/**
 * One entry of a fused plan — the Java-side mirror of {@code LgjOpDesc} (docs/abi.md §5) before it
 * is marshalled.
 *
 * <p>A plan entry is <strong>data, not a lambda</strong>. That is the load-bearing choice: a
 * predicate expressed as a {@code java.util.function.Predicate<Row>} would force a row object per
 * element and an upcall per element — the JNI anti-pattern in disguise. Expressed as a descriptor,
 * N predicates marshal into one contiguous array and cross once.
 *
 * <p><strong>Internal.</strong> The public {@code Predicate} type wraps one of these and never
 * shows it.
 *
 * @param op       opcode, e.g. {@link Layouts#OP_EQ_U32}
 * @param laneId   which lane the predicate reads
 * @param operand  needle or threshold, sign-extended to 64 bits
 * @param combine  {@link Layouts#COMBINE_AND} (narrow) or {@link Layouts#COMBINE_OR} (widen)
 */
public record PlanOp(int op, int laneId, long operand, int combine) {

    /** A narrowing (AND-combined) op — the only kind reachable from {@code View.where}. */
    public static PlanOp narrowing(int op, int laneId, long operand) {
        return new PlanOp(op, laneId, operand, Layouts.COMBINE_AND);
    }
}

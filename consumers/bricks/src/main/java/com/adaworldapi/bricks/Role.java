package com.adaworldapi.bricks;

import com.adaworldapi.lancegraph.Predicate;

import java.util.Optional;

/**
 * An authorization constraint — the mask a {@link BricksQuery} composes into its chain <em>before</em>
 * any terminal operation executes.
 *
 * <h2>A mask, not an if</h2>
 *
 * <p>Every {@code Role} constant carries an optional additional {@link Predicate}. When present,
 * {@link BricksQuery#authorize(Role)} folds it into the same lazy chain a caller's own {@code
 * where(...)} conditions live in — via {@link com.adaworldapi.lancegraph.View#where}, the identical
 * membrane-zero-crossings machinery. There is no branch in Java that decides "let this row through"
 * or "drop it": the constraint is evaluated natively, fused with every other predicate, in the same
 * single crossing the rest of the chain pays for.
 *
 * <p>{@link #DENY_ALL} makes this concrete rather than aspirational: it is <strong>not</strong> a
 * Java-side short-circuit that skips execution. It is a real predicate ({@code
 * Orders.REGION.eq(0xFFFF)}) that can never match any row, because {@link Orders#REGION} only ever
 * holds fixture values {@code 0..15}. Denial is a mask that is unsatisfiable, evaluated the same way
 * any other mask is evaluated — the fail-closed philosophy extends all the way down, not just to the
 * "did you call authorize()" gate.
 *
 * <h2>Extension point, stated honestly</h2>
 *
 * <p>These three constants are a stand-in for a real RBAC source. A future integration replaces them
 * — same {@code Role} shape, same single composition point in {@link BricksQuery#authorize(Role)} —
 * without touching any caller. Multi-value roles (a role permitting several regions, or intersecting
 * several field-level constraints) arrive with mask-level composition; the underlying {@code Mask}
 * algebra already exists in the core module ({@code com.adaworldapi.lancegraph.Mask}) but is not
 * wired into this consumer.
 */
public final class Role {

    /** Authorized with no additional constraint: every row an unrestricted query would see. */
    public static final Role GLOBAL = new Role(Optional.empty());

    /** Authorized, restricted to {@link Orders#EU} rows via a real, natively-evaluated predicate. */
    public static final Role EU_ONLY = new Role(Optional.of(Orders.REGION.eq(Orders.EU)));

    /**
     * Authorized, restricted by a predicate that can never match any row ({@code
     * Orders.REGION.eq(0xFFFF)} — {@link Orders#REGION} only ever holds {@code 0..15} in the
     * fixture). See the class documentation: this is a real mask, evaluated natively, not a
     * Java-side "return nothing" branch.
     */
    public static final Role DENY_ALL = new Role(Optional.of(Orders.REGION.eq(0xFFFF)));

    private final Optional<Predicate> constraint;

    private Role(Optional<Predicate> constraint) {
        this.constraint = constraint;
    }

    /**
     * The additional predicate this role imposes, if any. Package-private: composing it into a
     * chain is {@link BricksQuery#authorize(Role)}'s job, not a caller's.
     */
    Optional<Predicate> constraint() {
        return constraint;
    }
}

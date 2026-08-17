package com.adaworldapi.lancegraph;

/**
 * One named, typed column of a schema.
 *
 * <p>A {@code Field} is the whole reason an ordinary Java developer never meets a lane index or an
 * opcode. The generated vocabulary hands out {@code Pattern.CLASS} and {@code Pattern.VALUE}; the
 * IDE completes them; the compiler type-checks the comparison. Everything underneath — which lane,
 * which element kind, which opcode, which SIMD backend ran — is physics the field carries and the
 * caller never states.
 *
 * <p><strong>Two surfaces, deliberately.</strong> The constructor and {@link #lane()} are the
 * <em>generator</em> surface: they exist for the code that mints a schema (today hand-written as
 * {@link Pattern}, tomorrow emitted from a schema definition). {@link #name()} and the typed
 * comparison methods on the subclasses are the <em>consumer</em> surface. A consumer never calls
 * the former.
 *
 * <p>Sealed: the set of element types the membrane implements is fixed by the ABI, so a schema
 * cannot invent a field type whose predicate has no kernel behind it.
 */
public abstract sealed class Field
        permits U32Field, I32Field, U64Field {

    private final String name;
    private final LaneId lane;
    private final Ordinal ordinal;

    Field(String name, LaneId lane, Ordinal ordinal) {
        this.name = java.util.Objects.requireNonNull(name, "name");
        this.lane = java.util.Objects.requireNonNull(lane, "lane");
        this.ordinal = java.util.Objects.requireNonNull(ordinal, "ordinal");
    }

    /** The field's name as written in the schema. */
    public final String name() {
        return name;
    }

    /** Position within the schema. Stable for a schema version. */
    public final Ordinal ordinal() {
        return ordinal;
    }

    /**
     * Which column this field lives in.
     *
     * <p><strong>Generator surface.</strong> A consumer has no use for this — writing
     * {@code Pattern.CLASS.eq(7)} is the point, and {@code lane 1} is the thing being hidden.
     */
    public final LaneId lane() {
        return lane;
    }

    /** The element type this field reads, for diagnostics. */
    public abstract String elementType();

    @Override
    public final String toString() {
        return name + ":" + elementType();
    }
}

package com.adaworldapi.lancegraph;

/**
 * A field's position within its schema.
 *
 * <p>Stable for the life of a schema version, which is what lets a generated vocabulary be
 * compact: the name is a compile-time thing that costs nothing at runtime, and the ordinal is what
 * actually travels.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>Same rules as {@link LaneId}: final, immutable, identity-free, so the same source compiles as
 * a {@code value record} under JEP 401. This one is the clearest illustration of the cost being
 * removed — it wraps a single {@code int} purely so that an ordinal cannot be confused with a lane
 * index or a row number, and under Valhalla that type-safety is expected to cost nothing at all.
 *
 * @param value zero-based position
 */
public record Ordinal(int value) {

    public Ordinal {
        if (value < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0, was " + value);
        }
    }

    public static Ordinal of(int value) {
        return new Ordinal(value);
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}

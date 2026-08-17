package com.adaworldapi.lancegraph;

import java.util.List;

/**
 * The schema vocabulary for a pattern resource.
 *
 * <h2>This file is shaped exactly as a code generator would emit it</h2>
 *
 * <p>It is hand-written today, and that is temporary. <strong>The generator is the accessibility
 * story</strong>, not a convenience: it is what turns "lane 1, opcode 5, operand 7" into
 * {@code Pattern.CLASS.eq(7)} with IDE autocompletion and a compile error when the types are
 * wrong. A developer who has never heard of struct-of-arrays, SIMD, packed masks or FFM writes
 * code that reads like every other Java API they have used, and gets columnar physics anyway.
 *
 * <p>Everything below is therefore written the way a generator writes: constants only, no logic,
 * lane indices and ordinals stated once and never repeated, names taken from the schema. A future
 * generator emits this same shape from a schema definition; nothing that consumes it changes.
 *
 * <pre>{@code
 * try (var data = NativePattern.open(65_536)) {
 *     long n = data.view()
 *                  .where(Pattern.CLASS.eq(7))
 *                  .where(Pattern.VALUE.gt(100))
 *                  .count();
 * }
 * }</pre>
 *
 * <p>Note what the snippet does <em>not</em> mention: no arena, no segment, no mask, no lane, no
 * opcode, no backend, no row loop. One resource, one chain, one number.
 */
public final class Pattern {

    private Pattern() {}

    /** The schema's name. */
    public static final String SCHEMA = "pattern";

    /** Entity identity. Dense and zero-based in the fixture, so it doubles as the row index. */
    public static final U64Field ID =
            new U64Field("id", LaneId.of(0), Ordinal.of(0));

    /** Class tag, {@code 0..15} in the fixture. */
    public static final U32Field CLASS =
            new U32Field("class", LaneId.of(1), Ordinal.of(1));

    /** Signed measurement, {@code -150..361} in the fixture. */
    public static final I32Field VALUE =
            new I32Field("value", LaneId.of(2), Ordinal.of(2));

    /** Every field, in schema order. */
    public static final List<Field> FIELDS = List.of(ID, CLASS, VALUE);
}

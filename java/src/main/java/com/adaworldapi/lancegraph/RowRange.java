package com.adaworldapi.lancegraph;

/**
 * A half-open span of row indices, {@code [start, endExclusive)}.
 *
 * <p>The unit in which this library thinks about "how much data". A caller may see one of these
 * from {@link NativePattern#rows()}; nothing about the physical layout of those rows is implied or
 * exposed.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>Same rules as {@link LaneId}: final, immutable, identity-free, so the same source compiles as
 * a {@code value record} under JEP 401. Identity-freedom is what lets a pair of longs live in
 * registers instead of on the heap — the abstraction stops being something you pay for.
 *
 * @param start        first row, inclusive
 * @param endExclusive one past the last row
 */
public record RowRange(long start, long endExclusive) {

    public RowRange {
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0, was " + start);
        }
        if (endExclusive < start) {
            throw new IllegalArgumentException(
                    "endExclusive (" + endExclusive + ") must be >= start (" + start + ")");
        }
    }

    /** All rows from 0 up to {@code count}. */
    public static RowRange of(long count) {
        return new RowRange(0, count);
    }

    /** Number of rows spanned. */
    public long length() {
        return endExclusive - start;
    }

    public boolean isEmpty() {
        return endExclusive == start;
    }

    public boolean contains(long row) {
        return row >= start && row < endExclusive;
    }

    @Override
    public String toString() {
        return "rows[" + start + "," + endExclusive + ")";
    }
}

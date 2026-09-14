package com.adaworldapi.lancegraph;

/**
 * One column, seen through one {@link View} — the projection concept.
 *
 * <pre>{@code
 * long total = data.view()
 *                  .where(Pattern.CLASS.eq(7))
 *                  .lens(Pattern.VALUE)
 *                  .sum();
 * }</pre>
 *
 * <h2>Deliberately narrow, and honest about it</h2>
 *
 * <p>A lens is <em>where</em> ⨯ <em>which column</em>, and that pairing is the durable idea: a
 * selection says which rows, a lens says which column of them, and every bulk operation is some
 * reduction over that pair. What a lens offers today is exactly what the membrane implements —
 * a count and a signed 32-bit sum. It is a thin thing on purpose rather than a speculative
 * framework with methods that would have to fail at runtime.
 *
 * <p>What it becomes: the place bulk projection lands — min/max, a histogram, a group-by, and bulk
 * extraction of the selected values into a caller-supplied array. Every one of those is a single
 * bulk crossing over an existing selection, so none of them changes the shape of this type; they
 * are methods it grows. What it must never become is an iterator over rows, because a per-row
 * accessor across the membrane is the exact anti-pattern the whole design exists to avoid.
 */
public final class Lens {

    private final View view;
    private final I32Field field;

    Lens(View view, I32Field field) {
        this.view = view;
        this.field = field;
    }

    /** Sum of this column over the selected rows, widened to 64 bits. */
    public long sum() {
        return view.sumOf(field);
    }

    /**
     * Minimum of this column over the selected rows, widened to 64 bits (docs/abi.md §19.3, ABI
     * minor &ge; 11) — the first of this class's own documented growth: min/max. Absent when the
     * underlying view selects no rows: unlike {@link #sum} (always present, since the sum of an
     * empty population is {@code 0}), the minimum of an empty population has no answer.
     */
    public java.util.OptionalLong min() {
        return view.minOf(field);
    }

    /** {@link #min}'s maximum sibling — same shape, same absence-on-empty-selection reading. */
    public java.util.OptionalLong max() {
        return view.maxOf(field);
    }

    /** How many rows the underlying view selects. */
    public long count() {
        return view.count();
    }

    /** The column being projected. */
    public I32Field field() {
        return field;
    }

    /** The rows being projected through. */
    public View view() {
        return view;
    }

    @Override
    public String toString() {
        return "Lens[" + field.name() + " over " + view + "]";
    }
}

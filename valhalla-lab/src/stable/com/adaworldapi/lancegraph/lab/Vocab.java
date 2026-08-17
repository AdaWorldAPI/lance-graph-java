package com.adaworldapi.lancegraph.lab;

/**
 * The A/B vocabulary. This file exists twice — once under {@code src/stable} and once under
 * {@code src/valhalla} — and the two copies are byte-identical apart from the word {@code value}
 * on each declaration. {@code run.sh} diffs them and refuses to run if any other difference has
 * crept in, so "same semantic contract, one modifier" is checked rather than claimed.
 *
 * <p>Each type mirrors the corresponding type in the real API
 * ({@code com.adaworldapi.lancegraph.LaneId} and friends). The mirror exists because the real
 * types must keep compiling on the production JDK; the lab is where the same contract is compiled
 * a second way and measured.
 */
final class VocabDoc { private VocabDoc() {} }

/** Which column of the struct-of-arrays a field lives in. Mirrors the production {@code LaneId}. */
record LaneId(int index) {
    LaneId {
        if (index < 0) throw new IllegalArgumentException("lane index must be >= 0, was " + index);
    }
    static LaneId of(int index) { return new LaneId(index); }
    @Override public String toString() { return "lane#" + index; }
}

/** A half-open span of row indices. Mirrors the production {@code RowRange}. */
record RowRange(long start, long endExclusive) {
    RowRange {
        if (start < 0) throw new IllegalArgumentException("start must be >= 0, was " + start);
        if (endExclusive < start) throw new IllegalArgumentException("endExclusive < start");
    }
    static RowRange of(long count) { return new RowRange(0, count); }
    long length() { return endExclusive - start; }
    boolean isEmpty() { return endExclusive == start; }
    boolean contains(long row) { return row >= start && row < endExclusive; }
    @Override public String toString() { return "rows[" + start + "," + endExclusive + ")"; }
}

/** The identity of a selection, as an opaque token. Mirrors the production {@code MaskId}. */
record MaskId(long token) {
    int slot() { return (int) (token & 0xFFFF_FFFFL); }
    int generation() { return (int) (token >>> 32); }
    @Override public String toString() { return "mask#" + slot() + "@" + generation(); }
}

/** A field's position within its schema. Mirrors the production {@code Ordinal}. */
record Ordinal(int value) {
    Ordinal {
        if (value < 0) throw new IllegalArgumentException("ordinal must be >= 0, was " + value);
    }
    static Ordinal of(int value) { return new Ordinal(value); }
    @Override public String toString() { return "#" + value; }
}

/**
 * One logical entity, materialised as a Java object.
 *
 * <p>This is the type the thesis says must NOT exist per row. It is declared here precisely so
 * that "must not" can be measured instead of asserted: {@code ThesisExperiment} builds 65,536 of
 * these and reports what they cost against the native lane that needs none of them.
 */
record Row(long id, int cls, int value) {}

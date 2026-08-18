package com.adaworldapi.lancegraph;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * For every row of the {@link RowStore} it was taken from, which of its 32 facets carry the
 * queried classid — one {@code int} bitset per row, bit {@code f} set exactly when facet
 * {@code f}'s classid equals the value {@link RowStore#facetMatches} was called with.
 *
 * <p>Backed by a Java-owned segment a single native crossing wrote into ({@code
 * lgj_row_facet_match}, abi.md §11). Every read below — {@link #matchesOf} and
 * {@link #cardinality} — is an in-process access of that already-fetched segment; no further
 * crossing occurs.
 *
 * <h2>Lifetime</h2>
 *
 * <p>A view is a child of the {@link RowStore} it was taken from: the segment it wraps is
 * allocated from that store's own arena. It may outlive its parent as an object, but reading from
 * it after the parent closes throws {@link ClosedResourceException} — the segment's backing arena
 * dies with the store, so there is no arrangement of closes that lets a view read freed memory.
 */
public final class FacetMatchView {

    private final RowStore owner;
    private final MemorySegment data;
    private final long rowCount;

    FacetMatchView(RowStore owner, MemorySegment data, long rowCount) {
        this.owner = owner;
        this.data = data;
        this.rowCount = rowCount;
    }

    /**
     * How many rows this view covers.
     *
     * <p>Reads no segment bytes, but is guarded exactly like {@link #matchesOf} and
     * {@link #cardinality} for the reason the class doc states: once the store closes, this view is
     * no longer usable at all, not merely unsafe to read the segment through — a caller that checks
     * {@code rowCount()} before deciding whether to call {@link #matchesOf} must not be able to
     * observe a stale, disconnected-from-reality number from a dead view.
     */
    public long rowCount() {
        requireUsable("rowCount()");
        return rowCount;
    }

    /**
     * The 32-bit facet-match bitset for {@code row}: bit {@code f} set means facet {@code f}'s
     * classid equals the value queried.
     *
     * <p>An in-process segment read. No membrane crossing occurs.
     *
     * <p><strong>Low-level inspection / diagnostics.</strong> High-level query or traversal
     * implementations MUST NOT use this as their execution engine — see the root CLAUDE.md
     * mask-native policy. {@link WideFieldMask#ofMatchBits(int)} is the typed bridge for a caller
     * that does need this bitset as a {@link WideFieldMask}.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public int matchesOf(long row) {
        requireUsable("matchesOf()");
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException(
                    "row " + row + " is out of range [0, " + rowCount + ")");
        }
        return data.getAtIndex(ValueLayout.JAVA_INT, row);
    }

    /**
     * The total number of set bits across every row's bitset.
     *
     * <p>Deliberately Java-side: this is a bulk reduction over a result that already crossed the
     * membrane once (the single {@code lgj_row_facet_match} call behind
     * {@link RowStore#facetMatches}), so a second crossing just to reduce it would undo the point
     * of having fetched the whole thing in bulk.
     */
    public long cardinality() {
        requireUsable("cardinality()");
        long total = 0;
        for (long row = 0; row < rowCount; row++) {
            total += Integer.bitCount(data.getAtIndex(ValueLayout.JAVA_INT, row));
        }
        return total;
    }

    private void requireUsable(String what) {
        // Checked before any segment access, on purpose: the segment's backing arena is the
        // owning store's, so once the store is closed the segment must not be touched at all.
        if (!owner.isOpen()) {
            throw new ClosedResourceException(
                    what + " was called on a facet-match view whose store is closed. The segment's"
                            + " backing arena dies with the store, so it can never be read again.");
        }
    }

    @Override
    public String toString() {
        return "FacetMatchView[" + rowCount + " rows]";
    }
}

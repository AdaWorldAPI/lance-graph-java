package com.adaworldapi.lancegraph;

import java.util.Optional;

/**
 * Which register grouping each of a row store's facets carries, for one selection — the whole-row
 * alignment answer (abi.md §16).
 *
 * <p><strong>Alignment is arithmetic here, not a scan.</strong> The native side accumulates, per
 * facet, a 3-bit SET of the groupings its selected rows resolve to (plus one bit for "some row's
 * classid has no ClassView answer"). A facet is aligned exactly when that set has a single member:
 *
 * <pre>
 *   aligned(facet)  ⟺  bitCount(set) == 1  &amp;&amp;  no unanswerable bit
 * </pre>
 *
 * <p>An OR-accumulated set is exact where cheaper accumulators are not: a sum of wire values cannot
 * tell {@code {0,2}} from {@code {1,1}}, and an XOR cannot tell {@code {1,1}} from {@code {}}. The
 * set forgets multiplicity, which is exactly the information the question does not need.
 *
 * <p>One crossing covers all 32 facets. Asking per facet would be 32 crossings and is how a
 * consumer drifts into the per-element loop abi.md §6 forbids.
 */
public final class RowLayout {

    /** Bit meaning "some selected row's classid had no ClassView answer". */
    private static final int UNANSWERABLE = 0b1000;

    private final byte[] sets;

    RowLayout(byte[] sets) {
        this.sets = sets;
    }

    /** How many facets this covers. */
    public int facetCount() {
        return sets.length;
    }

    /**
     * The grouping facet {@code f}'s selected rows all share, or empty when they do not.
     *
     * <p>Empty covers three genuinely different situations, and a caller that needs to tell them
     * apart should use {@link #isAligned}, {@link #isEmpty} and {@link #hasUnanswerable}: the rows
     * disagree; some row's classid is unanswerable; or no row was selected at all.
     */
    public Optional<Carving> carvingOf(FacetId f) {
        int set = sets[f.index()] & 0xFF;
        if (Integer.bitCount(set) != 1 || (set & UNANSWERABLE) != 0) {
            return Optional.empty();
        }
        return Optional.of(Carving.ofWire(Integer.numberOfTrailingZeros(set)));
    }

    /** Whether facet {@code f}'s selected rows all read the register the same way. */
    public boolean isAligned(FacetId f) {
        int set = sets[f.index()] & 0xFF;
        return Integer.bitCount(set) == 1 && (set & UNANSWERABLE) == 0;
    }

    /** Whether no selected row carried this facet — the empty set, distinct from disagreement. */
    public boolean isEmpty(FacetId f) {
        return sets[f.index()] == 0;
    }

    /** Whether some selected row's classid had no ClassView answer at this facet. */
    public boolean hasUnanswerable(FacetId f) {
        return (sets[f.index()] & UNANSWERABLE) != 0;
    }

    /** Whether EVERY facet is aligned — the whole row reads uniformly. */
    public boolean isFullyAligned() {
        for (int i = 0; i < sets.length; i++) {
            int set = sets[i] & 0xFF;
            if (Integer.bitCount(set) != 1 || (set & UNANSWERABLE) != 0) {
                return false;
            }
        }
        return true;
    }

    /** How many facets are aligned. */
    public int alignedCount() {
        int n = 0;
        for (int i = 0; i < sets.length; i++) {
            int set = sets[i] & 0xFF;
            if (Integer.bitCount(set) == 1 && (set & UNANSWERABLE) == 0) {
                n++;
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "RowLayout[" + alignedCount() + "/" + sets.length + " facets aligned]";
    }
}

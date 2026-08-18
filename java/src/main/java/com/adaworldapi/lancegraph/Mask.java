package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;

/**
 * A materialised selection: which rows a {@link View} chose, held natively as packed bits.
 *
 * <p>One bit per row. Selecting 64,000 of 64,000 entities costs 8,000 bytes — not 64,000 objects,
 * not a list of indices, not a copy of anything. That is the whole reason the concept is exposed:
 * a caller who wants to ask several questions about the same rows should pay for the answer once.
 *
 * <p>A caller who only wants a number should not use this at all — {@link View#count()} never
 * materialises a selection the caller can see.
 *
 * <h2>Lifetime</h2>
 *
 * <p>A selection is a child of the resource it was taken from. It may outlive its parent as an
 * object, but it can never work after the parent closes: every operation then throws
 * {@link ClosedResourceException}, which is the Java face of the ABI's {@code PARENT_CLOSED}. There
 * is no arrangement of closes that lets a selection read freed memory.
 */
public final class Mask implements AutoCloseable {

    private final NativeResource parent;
    private final long handle;
    private boolean closed;

    /**
     * The mask's own packed-bit word window, resolved once via {@code lgj_mask_describe} (a
     * lifecycle crossing, per abi.md §6, not a bulk one) and cached: mask storage is allocated
     * once and never reallocated, resized, or moved while the resource is alive (a hard ABI
     * guarantee), the same invariant {@link RowStore#rawLane()} relies on for its own caching.
     * Every read or write through {@link #materializeRows()} after the first call is an
     * in-process segment access with no further crossing at all.
     */
    private Engine.LaneWindow words;

    Mask(NativeResource parent, long handle) {
        this.parent = parent;
        this.handle = handle;
    }

    /** How many rows are selected. */
    public long count() {
        requireUsable("count()");
        return Engine.maskCount(handle);
    }

    /** The opaque identity of this selection. Diagnostics, logging, map keys. */
    public MaskId id() {
        return new MaskId(handle);
    }

    /** The resource whose rows this selects — a {@link NativePattern} or a {@link RowStore}. */
    public NativeResource source() {
        return parent;
    }

    public boolean isOpen() {
        return !closed && parent.isOpen();
    }

    /**
     * A new selection: the rows in {@code this} that are NOT in {@code other} — {@code this &
     * !other}, word-wise (docs/abi.md §13; {@code lgj_mask_andnot}). One native crossing.
     *
     * <p>The only mask-algebra surfacing this wave — public {@code and}/{@code or} composition
     * stays out of scope (D-LGJ-W8 spec §4 NG7). {@code other} need not share this selection's
     * parent resource; if it does not, or the row counts differ, the ABI's own
     * {@code MASK_LENGTH_MISMATCH} surfaces as a {@link NativeCallException} — this method
     * performs no redundant Java-side parent/row-count check of its own, matching the spec's
     * "let the ABI status surface as its exception on mismatch" instruction.
     *
     * @throws ClosedResourceException if either selection, or its resource, is closed
     */
    public Mask minus(Mask other) {
        java.util.Objects.requireNonNull(other, "other");
        requireUsable("minus()");
        other.requireUsable("minus()'s argument");
        long dst = Engine.createMask(resourceHandleOf(parent), false);
        Engine.maskAndNot(handle, other.handle, dst);
        return new Mask(parent, dst);
    }

    /**
     * The set row indices, materialised into a fresh {@code long[]} — the ONE named terminal that
     * turns a native population into row ids (root CLAUDE.md's mask-native invariant, operator
     * §10: "row IDs are produced only by an explicit terminal whose NAME makes materialisation
     * visible").
     *
     * <p>O(n) in the number of set bits, allocation included. Reads this mask's own packed-bit
     * word lane in-process (resolved once, cached — see {@link #words} — a lifecycle crossing,
     * not a bulk one) and expands each set bit to a row index at the boundary only. Every other
     * operation on this facade stays mask-native end to end; this method is the deliberate,
     * explicitly-named exit from that currency.
     *
     * <p>Prefer {@link #count()} when only a number is needed — it never reads a word.
     *
     * @throws ClosedResourceException if this selection, or its resource, is closed
     */
    public long[] materializeRows() {
        requireUsable("materializeRows()");
        Engine.LaneWindow w = words();
        long wordCount = w.lengthElements();
        long[] out = new long[16];
        int size = 0;
        for (long word = 0; word < wordCount; word++) {
            long bits = w.getU64(word);
            while (bits != 0) {
                int bit = Long.numberOfTrailingZeros(bits);
                if (size == out.length) {
                    out = java.util.Arrays.copyOf(out, out.length * 2);
                }
                out[size++] = word * 64 + bit;
                bits &= bits - 1; // clear the lowest set bit
            }
        }
        return java.util.Arrays.copyOf(out, size);
    }

    /** Release the packed bits. Idempotency is not offered — a double close is an error. */
    @Override
    public void close() {
        if (closed) {
            throw new ClosedResourceException("close() called on a selection that is already closed");
        }
        closed = true;
        if (parent.isOpen()) {
            Engine.close(handle);
        }
        // If the parent is already gone, the selection was freed with it; calling close again
        // would only earn an INVALID_HANDLE. Nothing leaks either way.
    }

    // ── package-private: the native handle, for peers that build further native operations from
    // an existing selection (e.g. RowStore.hop's src argument) ──────────────────────────────────

    long handle() {
        return handle;
    }

    private Engine.LaneWindow words() {
        if (words == null) {
            words = Engine.describeMask(handle);
        }
        return words;
    }

    /**
     * Resolve the raw native handle of a {@link NativeResource} so a new {@link Mask} can be
     * allocated over it (docs/abi.md §7 {@code lgj_mask_create} takes the PARENT RESOURCE's
     * handle, never another mask's).
     *
     * <p>{@link NativeResource} is deliberately minimal (see its own javadoc: "just enough for
     * Mask to report a count and to check liveness") and does not expose a raw handle itself —
     * widening its public interface for this one internal need would leak the handle past this
     * package. Both concrete implementations that exist today ({@link NativePattern}, {@link
     * RowStore}) already carry a package-private {@code handle()} exactly like this class's own;
     * this closed-world dispatch is what lets {@link #minus} reach one without touching
     * {@link NativeResource}'s contract. A third {@link NativeResource} implementation would need
     * a case added here.
     */
    private static long resourceHandleOf(NativeResource resource) {
        if (resource instanceof NativePattern p) {
            return p.handle();
        }
        if (resource instanceof RowStore r) {
            return r.handle();
        }
        throw new IllegalStateException(
                "Mask.minus() cannot allocate a result mask over an unknown NativeResource"
                        + " implementation: " + resource.getClass());
    }

    private void requireUsable(String what) {
        if (closed) {
            throw new ClosedResourceException(what + " was called on a closed selection");
        }
        if (!parent.isOpen()) {
            throw new ClosedResourceException(
                    what + " was called on a selection whose resource is closed. The selection may"
                            + " outlive its parent as an object, but it can never work again"
                            + " (ABI status PARENT_CLOSED).");
        }
    }

    @Override
    public String toString() {
        return "Mask[" + id() + (closed ? ", closed" : "") + "]";
    }
}

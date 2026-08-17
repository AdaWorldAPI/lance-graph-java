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

    private final NativePattern parent;
    private final long handle;
    private boolean closed;

    Mask(NativePattern parent, long handle) {
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

    /** The resource whose rows this selects. */
    public NativePattern source() {
        return parent;
    }

    public boolean isOpen() {
        return !closed && parent.isOpen();
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

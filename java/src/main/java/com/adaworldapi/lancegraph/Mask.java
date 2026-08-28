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

    /**
     * Release the packed bits. Idempotency is not offered — a double close is an error.
     *
     * <p><strong>This object is the sole closer of its handle</strong> (abi.md, "Concurrency").
     * Unlike {@link RowStore}, this class re-authorises its cached word window with the substrate
     * on every use of that cache, so a close landing <em>before</em> a scan is caught. What is
     * still unguarded is a close landing <em>between</em> that re-authorisation and the segment
     * read — the probe narrows the window because its read is a native acquire through the
     * registry lock, but it cannot close it. Documented, not enforced; see
     * {@code ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW}.
     */
    @Override
    public void close() {
        if (closed) {
            throw new ClosedResourceException("close() called on a selection that is already closed");
        }
        closed = true;
        // Closed UNCONDITIONALLY, including when the parent is already gone.
        //
        // This used to be guarded by `if (parent.isOpen())`, with the comment "the selection was
        // freed with it; calling close again would only earn an INVALID_HANDLE. Nothing leaks
        // either way." Both halves were false, and the registry says so: `registry::close` takes
        // only the handle's OWN slot and never cascades to children, and a mask owns its own
        // `Box<[u64]>` words. An orphaned selection is therefore still a live resource holding a
        // live allocation -- `lgj_close` on it returns OK and releases it, which the ABI's own
        // `a_mask_whose_parent_closed_reports_parent_closed` asserts on its last line. Skipping
        // the call leaked both the words and the registry slot for the life of the process.
        //
        // Pinned by `MaskNativeOpsTest.orphanCloseActuallyReleases`, which falsifies via the
        // complement: a second close on the same handle must be REJECTED, which can only happen
        // if the first one really ran.
        Engine.close(handle);
    }

    // ── package-private: the native handle, for peers that build further native operations from
    // an existing selection (e.g. RowStore.hop's src argument) ──────────────────────────────────

    long handle() {
        return handle;
    }

    /**
     * The mask's packed-bit word lane, resolved once and cached — and, on every use of that
     * cache, <strong>re-authorised by the substrate</strong>.
     *
     * <p>Java holds no liveness authority here. The {@code closed} boolean and
     * {@link #requireUsable} are this facade's own bookkeeping; they cannot know that the native
     * resource behind the cached address is gone, because nothing in Java observes that. So
     * before any use of a cached window this asks the substrate to describe the mask again and
     * compares the answer with the stamp the cached window carries.
     *
     * <p><strong>Why re-describe rather than {@link Engine#epoch}.</strong> Measured, not
     * assumed: {@code lgj_resource_info} resolves the mask's OWN registry slot, and that slot
     * outlives its parent — closing the parent store natively left the probe silent while
     * {@code count()} correctly reported {@code PARENT_CLOSED}, and {@code materializeRows()}
     * went on to read freed bytes without crashing. (That it did not crash is worth stating: an
     * absent segfault is not evidence of safety.) {@code lgj_mask_describe} resolves the mask
     * WITH its parent, so it is the answer that actually covers these bytes.
     *
     * <p><strong>Exactly one downcall per whole scan, never per word</strong> — and, stated
     * plainly because it is a real change: <strong>a cached scan is no longer free.</strong>
     * Before this probe, the first scan cost one {@code lgj_mask_describe} and every later scan
     * cost nothing; now every scan costs exactly one, first or hundredth. (Not two: a cached
     * scan re-describes and does not also re-resolve.) What the cache still buys is the
     * segment construction and, more importantly, the previous descriptor to compare the new one
     * against — it is a reference value for change detection, no longer a crossing-avoidance
     * device. Do not read "cached" here as "free".
     *
     * <p>What that cost is NOT is work proportional to the population: callers resolve the window
     * once and then read every element in-process from the returned segment, so the crossing is a
     * lifecycle question asked once at the boundary. This is the {@code Mask} half of W1.1
     * (`.claude/plans/epoch-recheck-v3.md` §6): a native generation-checked liveness probe
     * replacing a Java boolean's sole authority.
     *
     * <p>Two conditions are distinguished on purpose, because "stop" is a weaker signal than
     * "stop, and here is what moved":
     *
     * <ul>
     *   <li>the handle no longer resolves — the resource was closed and its slot's generation has
     *       advanced past this handle, so the registry refuses it before any dereference;
     *   <li>the handle resolves but the epoch has moved — the cached address describes an earlier
     *       state of a resource that still exists. Unreachable today short of a {@code u32}
     *       generation wrap (§0), and checked anyway: the cost is one comparison, and a rule that
     *       is only sound because of an argument made elsewhere is exactly what this plan spent
     *       three rounds learning not to rely on.
     * </ul>
     */
    private Engine.LaneWindow words() {
        if (words == null) {
            words = Engine.describeMask(handle);
            return words;
        }
        // Re-describe, once. `lgj_mask_describe` resolves the mask WITH ITS PARENT
        // (`registry::resolve_mask_with_parent`) and is O(1) — it fills a descriptor, it does no
        // work over the population — so it is the parent-aware lifecycle answer this needs, with
        // no new ABI symbol.
        Engine.LaneWindow fresh;
        try {
            fresh = Engine.describeMask(handle);
        } catch (LanceGraphException e) {
            words = null;
            throw new ClosedResourceException(
                    "the packed bits of " + id() + " were resolved earlier, but the substrate no"
                            + " longer describes this selection (" + e.getMessage() + "). The"
                            + " cached address must not be read.");
        }
        if (fresh.epoch() != words.epoch() || fresh.byteLength() != words.byteLength()) {
            long stamped = words.epoch();
            words = null;
            throw new ClosedResourceException(
                    "the packed bits of " + id() + " were described at epoch " + stamped
                            + " but the substrate now reports epoch " + fresh.epoch()
                            + ". The cached address describes an earlier state and must not be"
                            + " read.");
        }
        words = fresh;
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

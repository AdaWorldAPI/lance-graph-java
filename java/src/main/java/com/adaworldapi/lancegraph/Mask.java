package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Abi;
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
 *
 * <h2>Thread safety — this class is NOT thread-safe (arm (ii), plan W1)</h2>
 *
 * <p><strong>The caller must establish <em>happens-before</em> between {@link #close()} and every
 * other access.</strong> A concurrent close-vs-access is <strong>undefined, and no guard detects
 * it</strong> — not the {@code closed} flag (a plain non-volatile field, so it carries both a
 * check-then-act and a visibility race) and not the substrate, because a cached descriptor read
 * resolves no handle and so returns no status.
 *
 * <p>This is the Java-side half of {@code docs/abi.md}'s <em>sole-closer contract</em>: one
 * handle, one closer, no concurrent close against a live reader. Documented, not enforced —
 * {@code Engine.close(long)} is reachable outside this object and nothing here can stop it.
 * Tracked as {@code ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW}.
 */
public final class Mask implements AutoCloseable {

    private final NativeResource parent;
    private final long handle;
    private boolean closed;

    /**
     * The mask's own packed-bit word window, obtained via {@code lgj_mask_describe} (a lifecycle
     * crossing, per abi.md §6, never a bulk one) and cached: mask storage is allocated once and
     * never reallocated, resized, or moved while the resource is alive (a hard ABI guarantee),
     * the same invariant {@link RowStore#rawLane()} relies on for its own caching.
     *
     * <p><strong>The cache is re-validated, not trusted, so every facade call pays one lifecycle
     * crossing — including calls after the first.</strong> {@link #words()} re-describes and
     * compares the returned epoch against the stamp it holds, so a cached address can never be
     * read after the substrate has moved under it. What the cache still buys is what matters:
     * the words are never re-fetched and the population is never re-scanned, because
     * {@code lgj_mask_describe} fills a descriptor and does no work over the rows. The cost is
     * therefore CONSTANT PER CALL, not zero after the first.
     *
     * <p>⊘ This paragraph replaces *"resolved once … and cached: every read or write through
     * {@link #materializeRows()} after the first call is an in-process segment access with no
     * further crossing at all"*, which described the pure-cache behaviour that preceded the
     * re-validation and was false once it landed. Corrected 2026-09-22 alongside the
     * {@code GraphHopTest} pin that measured it; found by review, not by a test, because a
     * javadoc contract has none.
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
        // Same reasoning as ternlog() below: a pure manifest check, no handle touched, run
        // BEFORE dst is allocated rather than left solely to Engine.maskAndNot's own copy of the
        // same guard, so a too-old library never leaves an orphaned mask behind.
        Abi.requireMinor(4);
        long dst = Engine.createMask(resourceHandleOf(parent), false);
        try {
            Engine.maskAndNot(handle, other.handle, dst);
        } catch (LanceGraphException e) {
            // The version gate above already passed; a failure here is the actual op
            // (MASK_LENGTH_MISMATCH if other belongs to a mismatched population). dst was
            // allocated for a Mask this method never got to construct, so nothing else owns it
            // -- release it before the failure propagates, or it and its registry slot leak for
            // the life of the process.
            closeOnFailure(dst, e);
            throw e;
        }
        return new Mask(parent, dst);
    }

    /**
     * A new selection: {@code ternlog::<imm>(this, b, c)}, word-wise — the mask-op family's
     * general member (docs/abi.md §19.1; {@code lgj_mask_ternlog}). {@code imm} is the 8-bit
     * VPTERNLOG truth table, index {@code (a<<2)|(b<<1)|c} where {@code a} is {@code this}, result
     * bit {@code (imm >> index) & 1}; every value {@code 0..255} is legal, so there is no
     * unknown-immediate rejection path.
     *
     * <p>This generalises {@link #minus} and the raw AND/OR/XOR/NOT this facade otherwise omits
     * (the {@code minus()} javadoc's "public and/or composition stays out of scope" scoped a
     * DIFFERENT wave's plan, not this symbol — abi.md §19.1's whole argument is that one
     * parameterised member closes the family a per-truth-table method set never would): common
     * immediates are {@code 0xC0} = {@code a & b}, {@code 0xFC} = {@code a | b}, {@code 0x3C} =
     * {@code a ^ b}, {@code 0x0F} = {@code !a} ({@code b}/{@code c} unused), {@code 0x80} =
     * {@code a & b & c}, {@code 0xE8} = majority of three.
     *
     * <p>{@code b}/{@code c} need not share this selection's parent resource; if either does not,
     * or the row counts differ, the ABI's own {@code MASK_LENGTH_MISMATCH} surfaces as a
     * {@link NativeCallException} — this method performs no redundant Java-side parent/row-count
     * check of its own, matching {@link #minus}'s own reading. {@code b} and/or {@code c} may be
     * {@code this} (or each other) — every operand is read as it stood BEFORE the call, so no
     * arrangement of aliasing changes the answer.
     *
     * @param b   the second operand
     * @param c   the third operand
     * @param imm the 8-bit truth table, {@code 0..255}
     * @throws IllegalArgumentException if {@code imm} is outside {@code 0..255}
     * @throws ClosedResourceException  if this selection, {@code b}, {@code c}, or its resource,
     *                                  is closed
     * @throws AbiMismatchException     if the loaded library reports ABI minor &lt; 11
     */
    public Mask ternlog(Mask b, Mask c, int imm) {
        java.util.Objects.requireNonNull(b, "b");
        java.util.Objects.requireNonNull(c, "c");
        if (imm < 0 || imm > 255) {
            throw new IllegalArgumentException("imm must be in 0..255, was " + imm);
        }
        requireUsable("ternlog()");
        b.requireUsable("ternlog()'s b argument");
        c.requireUsable("ternlog()'s c argument");
        // A pure manifest check, no handle touched -- run BEFORE dst is allocated rather than
        // left solely to Engine.maskTernlog's own copy of the same guard, so a too-old library
        // never leaves an orphaned mask behind (there is nothing yet to leak). Engine.maskTernlog
        // still carries its own requireMinor(11) too, for any caller that reaches it directly.
        Abi.requireMinor(11);
        long dst = Engine.createMask(resourceHandleOf(parent), false);
        try {
            Engine.maskTernlog(handle, b.handle, c.handle, dst, (byte) imm);
        } catch (LanceGraphException e) {
            // The version gate above already passed; a failure here is the actual op (e.g.
            // MASK_LENGTH_MISMATCH if b/c belong to a mismatched population). dst was allocated
            // for a Mask this method never got to construct, so nothing else owns it -- release
            // it before the failure propagates, or it and its registry slot leak for the life of
            // the process.
            closeOnFailure(dst, e);
            throw e;
        }
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
     * <p><strong>Guard scope (plan W1): the liveness check runs ONCE for the whole scan, not
     * per bit.</strong> {@link #words} re-authorises the cached window with the substrate when
     * this method resolves it, and the bit walk that follows is entirely in-process. So a close
     * landing <em>after</em> that check and <em>during</em> the walk is not detected — see the
     * class-level thread-safety note, which requires the caller to establish
     * <em>happens-before</em> between {@link #close()} and this call. That one-check-per-scan
     * shape is exactly what makes the check affordable here, and is why the same guard was never
     * a foregone conclusion for {@code RowStore}'s per-accessor-call reads.
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

    /**
     * Release a mask allocated as a would-be result, after the fallible native call meant to
     * populate it failed before a {@link Mask} could be constructed to own it — the shared
     * close-on-failure step for every {@code Engine.createMask(...)} + fallible-op pair on this
     * facade ({@link #ternlog}; {@link RowStore#maskOfFacetTernaryMatch} reaches this too, since
     * it is package-private "for peers", exactly like {@link #handle()} just above per the
     * section comment introducing it).
     *
     * <p>Without this, {@code handleToClose}'s native allocation and registry slot would leak
     * silently: there is no {@link Mask} object anywhere for a caller to close, because the
     * constructor that would have handed one out never ran. The original failure is always what
     * propagates to the caller — a failure while releasing {@code handleToClose} is attached to
     * it as a {@linkplain Throwable#addSuppressed suppressed} exception rather than replacing it,
     * so diagnosing "why did the operation fail" is never hijacked by "why did the cleanup of the
     * failed operation fail".
     */
    static void closeOnFailure(long handleToClose, LanceGraphException primary) {
        try {
            Engine.close(handleToClose);
        } catch (LanceGraphException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
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

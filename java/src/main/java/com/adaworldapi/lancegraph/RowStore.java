package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A native SoA row store: {@code n_rows} rows of 512 bytes each, 32 sixteen-byte facet lanes per
 * row — a 4-byte little-endian classid followed by a 12-byte opaque payload. This is the
 * lance-graph V3 content-blind facet shape, enforced everywhere on the Rust side (abi.md §11,
 * {@code .claude/knowledge/soa-row-store-layout.md}). The Java side's view of the 12-byte payload
 * may differ from any other consumer's; the 512/32/16 geometry itself is substrate truth and is
 * not re-derived here — it is enforced natively.
 *
 * <p>Zero-serialization, the same discipline as {@link NativePattern}: {@link #maskOfFacetClass}
 * never leaves native storage, and {@link #facetMatches} crosses the membrane exactly once and
 * hands back a Java segment that native code wrote into directly — never a per-row copy into a
 * Java object.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Ordinary try-with-resources, mirroring {@link NativePattern}. After {@link #close()} every
 * operation on this resource, and on any {@link Mask} or {@link FacetMatchView} derived from it,
 * throws {@link ClosedResourceException}. Closing twice is an error, not a silent no-op.
 */
public final class RowStore implements NativeResource, AutoCloseable {

    private final long handle;
    private final long rowCount;
    private final Arena arena;

    private boolean closed;

    private RowStore(long handle, long rowCount) {
        this.handle = handle;
        this.rowCount = rowCount;
        // Owns exactly the segments this store hands out via facetMatches(): allocated once per
        // store, closed once when this store closes, so a FacetMatchView's lifetime is provably
        // tied to its owner's rather than to a per-call scratch buffer.
        this.arena = Arena.ofShared();
    }

    /**
     * Open {@code nRows} rows generated deterministically from {@code seed}.
     *
     * <p>See abi.md §11 for the normative generator (two SplitMix64 draws per facet, {@code a}
     * then {@code b}): {@code classid = (a >>> 33) & 0xF}, {@code payload = le64(b) ++
     * le32(a & 0xFFFFFFFF)}.
     *
     * @throws NativeLibraryNotFoundException if the native artifact is not present
     * @throws AbiMismatchException           if it is present but does not support the row-store
     *                                         resource (ABI minor &lt; 2)
     */
    public static RowStore open(long nRows, long seed) {
        if (nRows < 0) {
            throw new IllegalArgumentException("nRows must be >= 0, was " + nRows);
        }
        long h = Engine.openRowStore(nRows, seed);
        // Read the row count back from the resource rather than trusting the request, matching
        // NativePattern.open: it is the resource that decides.
        return new RowStore(h, Engine.rowCount(h));
    }

    /**
     * Open {@code nRows} rows generated deterministically from {@code seed}, with a sparse, gated
     * subset of {@code edgeClassid}-matching facets carrying a bounded-local-neighbourhood target
     * row instead of raw noise (docs/abi.md §12) — what a non-vacuous BFS traversal needs; {@link
     * #open} alone produces uniform-random payloads that saturate any hop within one or two steps.
     *
     * <p>Byte-identical classid stream to {@link #open}. An {@code edgeClassid} that never occurs
     * in the classid stream (e.g. {@code 16}, one past the 4-bit range) reproduces {@link #open}
     * exactly.
     *
     * @param edgeGateMask sparsity gate: a facet is edge-shaped iff {@code a & edgeGateMask == 0}
     *                     on its underlying draw; {@code 0} is the densest setting
     * @param edgeRadius   bounds how far a structured target may land from its source row; must be
     *                     {@code < nRows}
     * @throws NativeLibraryNotFoundException if the native artifact is not present
     * @throws AbiMismatchException           if it is present but ABI minor &lt; 3
     */
    public static RowStore openWithEdges(long nRows, long seed, int edgeClassid,
                                         long edgeGateMask, int edgeRadius) {
        if (nRows < 0) {
            throw new IllegalArgumentException("nRows must be >= 0, was " + nRows);
        }
        long h = Engine.openRowStoreWithEdges(nRows, seed, edgeClassid, edgeGateMask, edgeRadius);
        return new RowStore(h, Engine.rowCount(h));
    }

    /** How many rows this resource holds. */
    @Override
    public long rowCount() {
        requireOpen("rowCount()");
        return rowCount;
    }

    /** False once {@link #close()} has run. */
    @Override
    public boolean isOpen() {
        return !closed;
    }

    /**
     * A selection of the rows whose {@code facet}'s classid equals {@code classId}.
     *
     * <p>The result is an ordinary {@link Mask}: it composes with the whole existing mask algebra
     * (and/or/count/describe) unchanged, because a row store is just as valid a {@link Mask}
     * parent as a {@link NativePattern} — abi.md §11: "masks may parent onto a pattern OR a row
     * store — both are read-only, row-shaped resources." This is one native crossing
     * ({@code lgj_op_eq_classid}).
     *
     * @param facet   which of the 32 facet lanes to read — a facet index, not a lane id
     * @param classId the classid to match against that facet
     */
    public Mask maskOfFacetClass(FacetId facet, int classId) {
        java.util.Objects.requireNonNull(facet, "facet");
        requireOpen("maskOfFacetClass()");
        long mask = Engine.createMask(handle, false);
        Engine.eqClassid(handle, facet.index(), classId, mask);
        return new Mask(this, mask);
    }

    /**
     * For every row, which of its 32 facets carry {@code classId} as their classid — one native
     * crossing ({@code lgj_row_facet_match}, abi.md §11).
     *
     * <p>The result wraps a Java-owned segment allocated from this store's own arena and written
     * into once by native code — zero-copy out, nothing serialized. The segment's lifetime is this
     * store's: it stays valid, and readable, until this store closes.
     */
    public FacetMatchView facetMatches(int classId) {
        requireOpen("facetMatches()");
        MemorySegment out = arena.allocate(ValueLayout.JAVA_INT, rowCount);
        Engine.rowFacetMatch(handle, classId, out, rowCount);
        return new FacetMatchView(this, out, rowCount);
    }

    private static final long ROW_BYTES = 512;
    private static final long FACET_BYTES = 16;

    /**
     * The raw lane-0 window (docs/abi.md §11), resolved once via {@code lgj_lane_describe} (ABI
     * minor &ge; 1 — already required by every {@code RowStore}) and cached: this is a
     * <strong>lifecycle</strong> crossing, per abi.md §6, not a bulk one, and every read through
     * {@link #classidAt}/{@link #payloadLow64At}/{@link #payloadHi32At} afterward is an
     * in-process segment read with no further crossing at all — exports.rs's own doctrine, applied:
     * "if Java wants one row it reads the MemorySegment in-process, with no crossing at all."
     */
    private MemorySegment rawLane;

    private MemorySegment rawLane() {
        requireOpen("row read");
        if (rawLane == null) {
            rawLane = Engine.describeLane(handle, 0).segment();
        }
        return rawLane;
    }

    private long rowOffset(long row, FacetId facet) {
        // No requireOpen() here -- rawLane() (evaluated first, as the receiver, in every one of
        // this method's three callers) already owns that check. Pure arithmetic, touches nothing,
        // needs no guard of its own; duplicating it here would be a second lock on a door only one
        // key opens.
        java.util.Objects.requireNonNull(facet, "facet");
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException(
                    "row " + row + " is out of range [0, " + rowCount + ")");
        }
        return row * ROW_BYTES + facet.index() * FACET_BYTES;
    }

    /**
     * The classid at {@code (row, facet)} — a zero-copy, in-process read (see {@link #rawLane()}'s
     * doc for why this never crosses the membrane after the first call on this store).
     *
     * <p>This is the per-row escape hatch the bulk predicates exist alongside, not a replacement
     * for them: use {@link #maskOfFacetClass}/{@link #facetMatches} for a bulk selection over every
     * row, this for reading one row a caller already knows it wants — e.g. decoding a graph hop's
     * target row after {@link #facetMatches} has already named which facet matched.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public int classidAt(long row, FacetId facet) {
        return rawLane().get(ValueLayout.JAVA_INT_UNALIGNED, rowOffset(row, facet));
    }

    /**
     * The low 64 payload bits at {@code (row, facet)}, little-endian — the row-store's
     * structured-edge target-row convention (docs/abi.md §12, {@link #openWithEdges}) when this
     * facet is a structured edge for this row. Meaningless, but always safe to read, when it is
     * not: combine with {@link #classidAt} and {@link #payloadHi32At} to decide that — the
     * generator's own convention is {@code classidAt(row, facet) == edgeClassid &&
     * payloadHi32At(row, facet) == 0}.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public long payloadLow64At(long row, FacetId facet) {
        return rawLane().get(ValueLayout.JAVA_LONG_UNALIGNED, rowOffset(row, facet) + 4);
    }

    /**
     * The high 32 payload bits at {@code (row, facet)} — {@code 0} exactly when {@link
     * #openWithEdges}'s generator wrote a structured edge target here (docs/abi.md §12);
     * overwhelmingly non-zero, by construction, for ordinary noise payload — including every
     * facet of a store opened with plain {@link #open}.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public int payloadHi32At(long row, FacetId facet) {
        return rawLane().get(ValueLayout.JAVA_INT_UNALIGNED, rowOffset(row, facet) + 12);
    }

    /**
     * The generation-checked registry handle. Package-private — mirrors {@link
     * NativePattern#handle()} exactly, including its consumer: {@code bench}'s {@code
     * NativeAccess} reaches package-private accessors like this one from a split-package bridge
     * class, never through a public API widening.
     */
    long handle() {
        return handle;
    }

    /**
     * Release the native storage and this store's own arena.
     *
     * @throws ClosedResourceException if already closed
     */
    @Override
    public void close() {
        if (closed) {
            throw new ClosedResourceException(
                    "close() called on a resource that is already closed. A double close is an"
                            + " error rather than a no-op, because the second call cannot know"
                            + " whether the handle was recycled in between.");
        }
        closed = true;
        Engine.close(handle);
        arena.close();
    }

    // ── package-private ─────────────────────────────────────────────────────────────────────

    void requireOpen(String what) {
        if (closed) {
            throw new ClosedResourceException(
                    what + " was called on a closed resource. Java rejected it before reaching"
                            + " native code; had it not, the generation-checked handle would have"
                            + " returned INVALID_HANDLE rather than touching freed memory.");
        }
    }

    @Override
    public String toString() {
        return "RowStore[" + rowCount + " rows" + (closed ? ", closed" : "") + "]";
    }
}

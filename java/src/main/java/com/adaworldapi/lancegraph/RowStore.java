package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;
import com.adaworldapi.lancegraph.internal.ffm.Layouts;

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

    /**
     * Open a facet-major COLUMNAR store (docs/abi.md §18; ABI minor &ge; 10): the SAME logical
     * content as {@link #openWithEdges} — same generator, same draws, same pinned hop counts —
     * arranged so every single-field native sweep is contiguous. Java cannot tell the layouts
     * apart except by speed: every read on this class goes through the lane descriptors the
     * membrane serves, never through hand-computed offsets, so the answers are layout-blind by
     * construction (root CLAUDE.md, E3).
     */
    public static RowStore openColumnar(long nRows, long seed, int edgeClassid,
                                        long edgeGateMask, int edgeRadius) {
        if (nRows < 0) {
            throw new IllegalArgumentException("nRows must be >= 0, was " + nRows);
        }
        long h = Engine.openRowStoreColumnar(nRows, seed, edgeClassid, edgeGateMask, edgeRadius);
        return new RowStore(h, Engine.rowCount(h));
    }

    /** {@link #openColumnar} with no structured edges (edge classid outside the 0..16 range). */
    public static RowStore openColumnar(long nRows, long seed) {
        return openColumnar(nRows, seed, 16, 0x0L, 1);
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
     * store — both are read-only, row-shaped resources." This is two native crossings
     * ({@code lgj_mask_create} then {@code lgj_op_eq_classid}) — a flat, per-call cost,
     * independent of row count. (Measured 2026-08-18; this sentence previously claimed
     * "one native crossing" while the body below visibly makes both calls.)
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
     * For every facet, which register grouping this selection's rows carry (abi.md §16) — the
     * whole-row alignment answer, in ONE crossing.
     *
     * <p>Use this to ask "is this population layout-aligned?" before sweeping it, or to discover
     * which facets a heterogeneous selection can still be swept on. Asking {@link #facetSum} per
     * facet to find out would be 32 crossings and would fail on the misaligned ones rather than
     * reporting them.
     *
     * @param selection the rows to probe; must belong to this store
     */
    public RowLayout layout(Mask selection) {
        java.util.Objects.requireNonNull(selection, "selection");
        requireOpen("layout()");
        return new RowLayout(Engine.rowLayoutProbe(handle, selection.handle(), FacetId.COUNT));
    }

    /**
     * Sum one facet's 12-byte register under the grouping the SELECTION ITSELF resolves to
     * (abi.md §15, ABI minor 6) — the verified sibling of {@link #facetSumAs}.
     *
     * <p>This is the shape {@code facetSumAs} could only name: for every selected row the native
     * side resolves {@code facet classid → ClassId → ClassView::cascade_shape} and requires every
     * row to agree, then sweeps monomorphically under that answer.
     *
     * <pre>
     *   classid → ClassView → ResolvedCarving → (population + its grouping) → sum
     * </pre>
     *
     * <p>The question is asked ONCE at the population's edge, never inside the sweep — resolving
     * per row would be the defect this whole path exists to avoid.
     *
     * <p>Throws when the selection does not resolve to one grouping: it spans classes that read
     * the register differently, a row's classid has no ClassView answer, or it is <em>empty</em>
     * (zero rows carry zero classes, so any answer would be invented).
     *
     * @param facet     which of the 32 facet lanes to read — a facet index, not a lane id
     * @param selection the rows to sum over; must belong to this store
     * @return the sum, and the grouping it was resolved under
     */
    public FacetSum facetSum(FacetId facet, Mask selection) {
        java.util.Objects.requireNonNull(facet, "facet");
        java.util.Objects.requireNonNull(selection, "selection");
        requireOpen("facetSum()");
        long[] r = Engine.facetSumResolved(handle, facet.index(), selection.handle());
        return new FacetSum(r[0], Carving.ofWire((int) r[1]));
    }

    /**
     * Sum one facet's 12-byte register, <strong>reinterpreted as</strong> {@code carving}, over the
     * rows {@code selection} selects — the mask-native sweep (abi.md §14, ABI minor 5).
     *
     * <p><strong>This is a raw reinterpretation primitive, and the {@code As} in its name is the
     * honest part.</strong> It applies the carving you name to every selected row. It does NOT
     * verify that the carving is the one those rows' classes actually specify — it cannot: the
     * mask is an opaque population (it may be a {@link #importRows} union spanning several
     * classids), and the fixture {@code ClassView} carries no carving resolver at all today. So
     * this method claims no ClassView authority; the caller supplies the reading and owns its
     * correctness.
     *
     * <p>The stronger shape — binding the resolved answer to the population ONCE, so the sweep
     * receives an answer rather than a promise — is a named seam, not something this method
     * pretends to be:
     *
     * <pre>
     *   classid → ClassView → ResolvedCarving → (population + its carving) → sum
     * </pre>
     *
     * <p>That preserves the property this whole path exists for (the ALU gets the answer, not the
     * question) while making the binding checkable. It needs a real ClassView carving resolver
     * upstream, which does not exist yet — so it is recorded as the next rung rather than faked
     * with a per-row consult, which would put the entropy straight back into the loop.
     *
     * <p>This is the execution half of the mask path whose build half is
     * {@link #maskOfFacetClass}. Together they are the whole shape: classid becomes a mask once,
     * then the sweep runs over a population that no longer carries the classid question. The
     * population never leaves mask form — there is no row-id list, no index array, no per-row Java
     * object anywhere on this path.
     *
     * <p>One native crossing, and its work is proportional to the mask's <em>popcount</em> rather
     * than to the row count, so a narrow selection over a large store is cheap and an empty one
     * costs only the mask scan.
     *
     * @param facet     which of the 32 facet lanes to read — a facet index, not a lane id
     * @param carving   the reading to apply — supplied by the caller, NOT verified against the
     *                  selected rows' classes
     * @param selection the rows to sum over; must belong to this store
     */
    public long facetSumAs(FacetId facet, Carving carving, Mask selection) {
        java.util.Objects.requireNonNull(facet, "facet");
        java.util.Objects.requireNonNull(carving, "carving");
        java.util.Objects.requireNonNull(selection, "selection");
        requireOpen("facetSumAs()");
        return Engine.facetSumAs(handle, facet.index(), carving.wire(), selection.handle());
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
        return new FacetMatchView(this, out, rowCount, classId);
    }

    /**
     * Package-private bridge for {@link FacetMatchView#cardinality()}: the native slot count for
     * {@code classId}, one crossing. Not public API — the public surface for this answer is the
     * view, so the question and its projection stay together.
     */
    long facetMatchCount(int classId) {
        requireOpen("facetMatchCount()");
        return Engine.rowstoreFacetMatchCount(handle, classId);
    }

    /**
     * The one-hop reachable set from {@code src} over this store's {@code edgeClassid}-matching
     * facets, restricted to {@code facets} and narrowed further by the class's {@code ClassView}
     * -resolved edge participation (docs/abi.md §13; {@code lgj_hop}). Two native crossings,
     * flat ({@code lgj_mask_create} then {@code lgj_hop}) — measured 2026-08-18, pinned by
     * {@code GraphHopTest}; never per-row, never per-frontier-size.
     *
     * <p>THE §5 conceptual op on the substrate facade (root CLAUDE.md's mask-native invariant):
     * {@code src} and the returned {@link Mask} are both native population handles the whole way
     * through. No row id, {@code long[]}, or per-row Java loop is ever part of this call's own
     * execution — "hop may look like hop; it must execute as Mask × ClassView/WideFieldMask →
     * Mask."
     *
     * @param edgeClassid the classid a facet must carry to be treated as an edge for this hop
     * @param facets      which of the 32 facet lanes to consider; the ABI narrows this further by
     *                    the class's own edge-participation law — a caller cannot widen past what
     *                    the class actually permits merely by passing {@link
     *                    WideFieldMask#allFacets()}
     * @param src         the population to hop FROM
     * @throws AbiMismatchException if the loaded library reports ABI minor &lt; 4
     */
    public Mask hop(int edgeClassid, WideFieldMask facets, Mask src) {
        java.util.Objects.requireNonNull(facets, "facets");
        java.util.Objects.requireNonNull(src, "src");
        requireOpen("hop()");
        long dst = Engine.createMask(handle, false);
        Engine.hop(handle, edgeClassid, facets.bits(), 0, src.handle(), dst);
        return new Mask(this, dst);
    }

    /**
     * {@link #hop(int, WideFieldMask, Mask)} over every one of this store's 32 facets ({@link
     * WideFieldMask#allFacets()}).
     */
    public Mask hop(int edgeClassid, Mask src) {
        return hop(edgeClassid, WideFieldMask.allFacets(), src);
    }

    /**
     * The ONE named external-selection import: build a selection from row ids the caller already
     * has, from outside this library.
     *
     * <p><strong>Classification (root CLAUDE.md's mask-native invariant, operator §7):</strong>
     * this is an escape hatch / external-selection import / test utility. It is NEVER the
     * internal currency of {@code where}/{@code hop}/{@code authorize}/{@code navigate} — every
     * other operation on this facade stays mask-native from end to end, and the only way a
     * {@code long[]} of row ids may enter this library at all is through this method, by name,
     * visibly, at the call site.
     *
     * <p>Implementation: two native crossings, flat — one to allocate the destination selection
     * ({@code lgj_mask_create}) and one to describe its writable word lane ({@code
     * lgj_mask_describe}) — then one in-process word write per row through that window: no
     * crossing per row, and zero new ABI surface. (Measured 2026-08-18: 3 rows and 29 rows cost
     * identically, pinned by {@code GraphHopTest}'s row-count-independence check.)
     *
     * @throws IndexOutOfBoundsException if any row is not in {@code [0, rowCount())}; every row
     *                                   is validated before any native allocation, so a caller
     *                                   never sees a partially built, unreachable selection on
     *                                   failure
     */
    public Mask importRows(long... rows) {
        java.util.Objects.requireNonNull(rows, "rows");
        requireOpen("importRows()");
        for (long row : rows) {
            if (row < 0 || row >= rowCount) {
                throw new IndexOutOfBoundsException(
                        "row " + row + " is out of range [0, " + rowCount + ")");
            }
        }
        long dst = Engine.createMask(handle, false);
        Engine.LaneWindow window = Engine.describeMask(dst);
        for (long row : rows) {
            long word = row >>> 6;
            int bit = (int) (row & 63);
            window.setU64(word, window.getU64(word) | (1L << bit));
        }
        return new Mask(this, dst);
    }


    /**
     * Lazily-resolved lane windows, keyed by ABI lane id (docs/abi.md §11/§18) — the SERVED
     * geometry. Each resolve is one lifecycle crossing ({@code lgj_lane_describe}); every read
     * after it is an in-process segment access at {@code row * strideBytes}, which is correct
     * under EITHER layout because the stride comes from the descriptor, never from Java. This is
     * root CLAUDE.md E3 carried to its end: after minor 10 no Java code computes a row-store
     * offset from a constant — the membrane answers, Java reads.
     */
    private final Engine.LaneWindow[] lanes = new Engine.LaneWindow[3 * FacetId.COUNT + 1];

    private Engine.LaneWindow lane(int laneId) {
        requireOpen("row read");
        Engine.LaneWindow w = lanes[laneId];
        if (w == null) {
            w = Engine.describeLane(handle, laneId);
            lanes[laneId] = w;
        }
        return w;
    }

    private long checkedRow(long row) {
        if (row < 0 || row >= rowCount) {
            throw new IndexOutOfBoundsException(
                    "row " + row + " is out of range [0, " + rowCount + ")");
        }
        return row;
    }

    /**
     * The classid at {@code (row, facet)} — a zero-copy, in-process read through the SERVED classid lane (see {@code lane(int)}'s
     * doc for why this never crosses the membrane after the first call on this store).
     *
     * <p>This is the per-row escape hatch the bulk predicates exist alongside, not a replacement
     * for them: use {@link #maskOfFacetClass}/{@link #facetMatches} for a bulk selection over every
     * row, this for reading one row a caller already knows it wants — e.g. decoding a graph hop's
     * target row after {@link #facetMatches} has already named which facet matched.
     *
     * <p><strong>Low-level inspection / diagnostics.</strong> High-level query or traversal
     * implementations MUST NOT use this as their execution engine — see the root CLAUDE.md
     * mask-native policy; {@link #hop} is the mask-native path this method is not a substitute for.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public int classidAt(long row, FacetId facet) {
        java.util.Objects.requireNonNull(facet, "facet");
        Engine.LaneWindow w = lane(Layouts.LANE_FACET_BASE + facet.index());
        return w.segment().get(ValueLayout.JAVA_INT_UNALIGNED, checkedRow(row) * w.strideBytes());
    }

    /**
     * The low 64 payload bits at {@code (row, facet)}, little-endian — the row-store's
     * structured-edge target-row convention (docs/abi.md §12, {@link #openWithEdges}) when this
     * facet is a structured edge for this row. Meaningless, but always safe to read, when it is
     * not: combine with {@link #classidAt} and {@link #payloadHi32At} to decide that — the
     * generator's own convention is {@code classidAt(row, facet) == edgeClassid &&
     * payloadHi32At(row, facet) == 0}.
     *
     * <p><strong>Low-level inspection / diagnostics.</strong> High-level query or traversal
     * implementations MUST NOT use this as their execution engine — see the root CLAUDE.md
     * mask-native policy; {@link #hop} is the mask-native path this method is not a substitute for.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public long payloadLow64At(long row, FacetId facet) {
        java.util.Objects.requireNonNull(facet, "facet");
        Engine.LaneWindow w = lane(Layouts.LANE_LO64_BASE + facet.index());
        return w.segment().get(ValueLayout.JAVA_LONG_UNALIGNED, checkedRow(row) * w.strideBytes());
    }

    /**
     * The high 32 payload bits at {@code (row, facet)} — {@code 0} exactly when {@link
     * #openWithEdges}'s generator wrote a structured edge target here (docs/abi.md §12);
     * overwhelmingly non-zero, by construction, for ordinary noise payload — including every
     * facet of a store opened with plain {@link #open}.
     *
     * <p><strong>Low-level inspection / diagnostics.</strong> High-level query or traversal
     * implementations MUST NOT use this as their execution engine — see the root CLAUDE.md
     * mask-native policy; {@link #hop} is the mask-native path this method is not a substitute for.
     *
     * @throws IndexOutOfBoundsException if {@code row} is not in {@code [0, rowCount())}
     */
    public int payloadHi32At(long row, FacetId facet) {
        java.util.Objects.requireNonNull(facet, "facet");
        Engine.LaneWindow w = lane(Layouts.LANE_HI32_BASE + facet.index());
        return w.segment().get(ValueLayout.JAVA_INT_UNALIGNED, checkedRow(row) * w.strideBytes());
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
     * <p><strong>This object is the sole closer of its handle</strong> (abi.md, "Concurrency").
     * It caches lane descriptors in {@code lanes[]} and reads them directly on every accessor
     * call, so a close by any other route — or from any other thread — leaves those cached
     * addresses pointing at freed memory with nothing to report it: {@code requireOpen} consults
     * this object's own non-volatile {@code closed} flag, never the registry. The contract is
     * documented, not enforced; see {@code ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW}.
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

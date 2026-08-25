package com.adaworldapi.lancegraph.internal.ffm;

import com.adaworldapi.lancegraph.LanceGraphException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * The only place in the project where a plan is turned into bytes and pushed across the membrane.
 *
 * <p>Everything above this class speaks {@link PlanOp} and {@code long} handles; everything below
 * speaks {@link MemorySegment}. Keeping the marshalling here is what lets the public API contain no
 * FFM type at all.
 *
 * <h2>Scratch memory</h2>
 *
 * <p>Out-parameters and the op array live in a per-thread scratch buffer allocated from an
 * automatic arena, so a terminal operation performs <em>no</em> allocation in steady state. This
 * matters for the benchmark: if each {@code count()} allocated an arena, the measurement would be
 * reporting allocator behaviour rather than crossing cost.
 *
 * <p><strong>Internal.</strong>
 */
public final class Engine {

    private Engine() {}

    /** Room for the widest out-parameter set any single call needs. */
    private static final long OUT_BYTES = 32;

    private static final class Scratch {
        // ofAuto: freed when this Scratch becomes unreachable, i.e. when the thread dies. No
        // explicit lifetime to get wrong, and no leak because the ThreadLocal holds exactly one.
        private final Arena arena = Arena.ofAuto();
        private final MemorySegment out = arena.allocate(OUT_BYTES, 8);
        private final MemorySegment info = arena.allocate(Layouts.RESOURCE_INFO);
        private final MemorySegment desc = arena.allocate(Layouts.LANE_DESC);
        private MemorySegment ops = arena.allocate(Layouts.OP_DESC, 8);
        private long opsCapacity = 8;

        MemorySegment opsFor(int nOps) {
            if (nOps > opsCapacity) {
                long want = Math.max(nOps, opsCapacity * 2);
                ops = arena.allocate(Layouts.OP_DESC, want);
                opsCapacity = want;
            }
            return ops;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    /** Open the deterministic SoA fixture; returns the resource handle. */
    public static long openPattern(long nRows, long seed) {
        Scratch s = SCRATCH.get();
        return Downcalls.patternOpen(nRows, seed, s.out);
    }

    /** Create a selection over {@code parent}; returns the mask handle. */
    public static long createMask(long parent, boolean allSet) {
        Scratch s = SCRATCH.get();
        return Downcalls.maskCreate(parent,
                allSet ? Layouts.MASK_INIT_ALL : Layouts.MASK_INIT_EMPTY, s.out);
    }

    public static void close(long handle) {
        Downcalls.close(handle);
    }

    /** Row count of a resource, read from {@code LgjResourceInfo}. */
    public static long rowCount(long handle) {
        Scratch s = SCRATCH.get();
        Downcalls.resourceInfo(handle, s.info);
        return (long) Layouts.INFO_N_ROWS.get(s.info, 0L);
    }

    /** Liveness stamp of a resource. Java re-checks this before trusting a cached lane segment. */
    public static long epoch(long handle) {
        Scratch s = SCRATCH.get();
        Downcalls.resourceInfo(handle, s.info);
        return (long) Layouts.INFO_EPOCH.get(s.info, 0L);
    }

    public static long maskCount(long mask) {
        Scratch s = SCRATCH.get();
        return Downcalls.maskCount(mask, s.out);
    }

    // ── evaluation ───────────────────────────────────────────────────────────────────────────

    /**
     * The production path: marshal the whole chain and cross <strong>once</strong>.
     *
     * @return popcount of the resulting selection
     */
    public static long evaluateFused(long resource, List<PlanOp> plan, long dstMask) {
        MemorySegment ops = marshal(plan);
        Scratch s = SCRATCH.get();
        return Downcalls.planEval(resource, ops, plan.size(), dstMask, s.out);
    }

    /**
     * Same semantics, forced down the scalar reference kernel.
     *
     * <p>Only for falsifying SIMD-vs-scalar parity from the Java side, which is where the tests
     * live. Never the default.
     */
    public static long evaluateScalar(long resource, List<PlanOp> plan, long dstMask) {
        MemorySegment ops = marshal(plan);
        Scratch s = SCRATCH.get();
        return Downcalls.planEvalScalar(resource, ops, plan.size(), dstMask, s.out);
    }

    /**
     * The comparison path: one crossing per predicate plus one per combine plus one to count.
     *
     * <p>Retained <em>only</em> so the fused path can be benchmarked against something and so
     * parity can be checked predicate-by-predicate. Using it in production would be the anti-JNI
     * rule's exact failure mode, just with a coarser granularity than per element.
     *
     * @param scratchMask a second mask over the same parent, used to hold each intermediate
     */
    public static long evaluateUnfused(long resource, List<PlanOp> plan, long dstMask,
                                       long scratchMask) {
        if (plan.isEmpty()) {
            throw new LanceGraphException("an unfused plan needs at least one predicate");
        }
        for (int i = 0; i < plan.size(); i++) {
            PlanOp p = plan.get(i);
            // The first predicate writes straight into dst (each op *overwrites* its destination);
            // every later one goes to scratch and is folded in.
            long target = (i == 0) ? dstMask : scratchMask;
            applyOne(resource, p, target);
            if (i > 0) {
                if (p.combine() == Layouts.COMBINE_OR) {
                    Downcalls.maskOr(dstMask, scratchMask, dstMask);
                } else {
                    Downcalls.maskAnd(dstMask, scratchMask, dstMask);
                }
            }
        }
        return maskCount(dstMask);
    }

    private static void applyOne(long resource, PlanOp p, long dst) {
        switch (p.op()) {
            case Layouts.OP_EQ_U32 -> Downcalls.opEqU32(resource, p.laneId(), (int) p.operand(), dst);
            case Layouts.OP_GT_I32 -> Downcalls.opGtI32(resource, p.laneId(), (int) p.operand(), dst);
            default -> throw new LanceGraphException(
                    "opcode " + p.op() + " has no unfused equivalent; the unfused path is a"
                            + " benchmark comparison only and is not required to cover every op");
        }
    }

    /** Sum an {@code I32} lane over the set bits of {@code mask}, widened to 64 bits. */
    public static long sumI32(long resource, int laneId, long mask) {
        Scratch s = SCRATCH.get();
        return Downcalls.reduceSumI32(resource, laneId, mask, s.out);
    }

    // ── row store (docs/abi.md §11, ABI minor ≥ 2) ──────────────────────────────────────────
    //
    // Every entry point below calls Abi.requireMinor(2) before touching the membrane at all, so a
    // Java build compiled against the row store never reaches a missing symbol inside Downcalls —
    // it fails here, first, naming the version gap (see Abi#requireMinor's own doc for why the base
    // load gate at minor >= 1 cannot cover this by itself).

    /**
     * Open the 64K×512-byte SoA row store, generated deterministically from {@code seed}
     * (docs/abi.md §11). Returns the resource handle. Requires ABI minor &gt;= 2.
     */
    public static long openRowStore(long nRows, long seed) {
        Abi.requireMinor(2);
        Scratch s = SCRATCH.get();
        return Downcalls.rowstoreOpen(nRows, seed, s.out);
    }

    /**
     * Open the edge-bearing SoA row store (docs/abi.md §12): byte-identical classid stream to
     * {@link #openRowStore}, plus a sparse, gated subset of {@code edgeClassid}-matching facets
     * carrying a bounded-local-neighbourhood target row instead of raw noise — what a non-vacuous
     * BFS over the row store needs. Returns the resource handle. Requires ABI minor &gt;= 3.
     *
     * @param edgeGateMask sparsity gate: a facet is edge-shaped iff {@code a & edgeGateMask == 0}
     *                     on its draw; {@code 0} is the densest setting
     * @param edgeRadius   bounds how far a structured target may land from its source row; must be
     *                     {@code < nRows}
     */
    public static long openRowStoreWithEdges(long nRows, long seed, int edgeClassid,
                                             long edgeGateMask, int edgeRadius) {
        Abi.requireMinor(3);
        Scratch s = SCRATCH.get();
        return Downcalls.rowstoreOpenWithEdges(nRows, seed, edgeClassid, edgeGateMask, edgeRadius,
                s.out);
    }

    /**
     * Overwrite {@code dstMask} with {@code classid(facet, row) == classId} for every row of
     * {@code store} (docs/abi.md §11). {@code facet} is a facet index {@code 0..32} into the row's
     * 32 facet lanes, not a lane id. Requires ABI minor &gt;= 2.
     */
    /**
     * Sum one facet's 12-byte register, under {@code carving}, over the rows {@code mask} selects
     * (abi.md §14, ABI minor 5). Work scales with the mask's popcount, not the row count.
     */
    public static long facetSum(long store, int facet, int carving, long mask) {
        Abi.requireMinor(5);
        Scratch s = SCRATCH.get();
        return Downcalls.reduceFacetSum(store, facet, carving, mask, s.out);
    }

    public static void eqClassid(long store, int facet, int classId, long dstMask) {
        Abi.requireMinor(2);
        Downcalls.opEqClassid(store, facet, classId, dstMask);
    }

    /**
     * Write, for every row of {@code store}, a {@code u32} bitset of which of its 32 facets carry
     * {@code classId} as classid, into the caller's {@code out} segment — one crossing, zero-copy
     * out, nothing serialized (docs/abi.md §11). Requires ABI minor &gt;= 2.
     *
     * @param out          a caller-owned segment, at least {@code outLenElems} {@link
     *                     Layouts#FACET_MATCH_ELEM}-wide elements; capacity is checked natively
     *                     before anything is written
     * @param outLenElems  the element count (not byte count) {@code out} was allocated for
     */
    public static void rowFacetMatch(long store, int classId, MemorySegment out, long outLenElems) {
        Abi.requireMinor(2);
        Downcalls.rowFacetMatch(store, classId, out, outLenElems);
    }

    // ── mask complement + hop (docs/abi.md §13, ABI minor ≥ 4) ─────────────────────────────
    //
    // Same requireMinor-before-any-downcall discipline as the row store section above: a Java
    // build compiled against this feature never reaches a missing symbol inside Downcalls, it
    // fails here, first, naming the version gap.

    /**
     * {@code dst = a & !b}, word-wise (docs/abi.md §13). Not commutative — unlike {@link
     * Downcalls#maskAnd}/{@link Downcalls#maskOr}, swapping {@code a} and {@code b} changes the
     * answer. Requires ABI minor &gt;= 4.
     */
    public static void maskAndNot(long a, long b, long dst) {
        Abi.requireMinor(4);
        Downcalls.maskAndNot(a, b, dst);
    }

    /**
     * Overwrite {@code dstMask} with the one-hop reachable set from {@code srcMask} over
     * {@code store}'s {@code edgeClassid}-matching facets, gated by the effective participation
     * {@code facetMask ∩ provider.edge_participation(edgeClassid)} (docs/abi.md §13). Requires
     * ABI minor &gt;= 4.
     *
     * @param facetMask  the wire form of the contract's {@code FieldMask}; bits &gt;= 32 are
     *                   ignored by this store
     * @param decodeMode {@code 0} is the only mode this build implements (the §12 fixture
     *                   convention); {@code 1..=3} are RESERVED and fail with the ABI's
     *                   {@code UNSUPPORTED_DECODE_MODE} status, checked before {@code store}/
     *                   {@code srcMask}/{@code dstMask} are even resolved
     */
    public static void hop(long store, int edgeClassid, long facetMask, int decodeMode,
                           long srcMask, long dstMask) {
        Abi.requireMinor(4);
        Downcalls.hop(store, edgeClassid, facetMask, decodeMode, srcMask, dstMask);
    }

    private static MemorySegment marshal(List<PlanOp> plan) {
        int n = plan.size();
        if (n == 0) {
            throw new LanceGraphException(
                    "an empty plan cannot be evaluated (ABI status EMPTY_PLAN). A View with no"
                            + " predicates selects every row; ask for the row count instead.");
        }
        MemorySegment ops = SCRATCH.get().opsFor(n);
        long stride = Layouts.OP_DESC.byteSize();
        for (int i = 0; i < n; i++) {
            PlanOp p = plan.get(i);
            long base = i * stride;
            Layouts.OP_OP.set(ops, base, p.op());
            Layouts.OP_LANE_ID.set(ops, base, p.laneId());
            Layouts.OP_OPERAND.set(ops, base, p.operand());
            Layouts.OP_COMBINE.set(ops, base, p.combine());
            // Reserved must be 0 — set explicitly rather than relying on the buffer being fresh,
            // since the scratch is reused across calls.
            Layouts.OP_RESERVED.set(ops, base, 0);
        }
        return ops;
    }

    // ── lane access (in-process, zero crossings) ─────────────────────────────────────────────

    /**
     * Describe a lane and hand back a bounded, read-only window onto it.
     *
     * <p>There is deliberately no {@code readElement} anywhere in the ABI: reading one row across
     * the membrane would be the per-element crossing the whole design forbids. If Java wants
     * element access it reads this segment directly, in-process, with no crossing at all.
     *
     * <p>The segment is valid until the owning resource is closed (lanes are allocated once and
     * never reallocated, resized or moved — a hard ABI guarantee). The {@code epoch} returned
     * alongside lets a caller detect a segment it is still holding after a close.
     */
    public static LaneWindow describeLane(long resource, int laneId) {
        Scratch s = SCRATCH.get();
        Downcalls.laneDescribe(resource, laneId, s.desc);
        return windowOf(s.desc);
    }

    /** As {@link #describeLane}, for the packed-bit words of a mask. */
    public static LaneWindow describeMask(long mask) {
        Scratch s = SCRATCH.get();
        Downcalls.maskDescribe(mask, s.desc);
        return windowOf(s.desc);
    }

    private static LaneWindow windowOf(MemorySegment desc) {
        long addr = (long) Layouts.LANE_ADDR.get(desc, 0L);
        long lenElems = (long) Layouts.LANE_LEN_ELEMS.get(desc, 0L);
        long byteLen = (long) Layouts.LANE_BYTE_LEN.get(desc, 0L);
        long owner = (long) Layouts.LANE_OWNER.get(desc, 0L);
        long epoch = (long) Layouts.LANE_EPOCH.get(desc, 0L);
        int elemKind = (int) Layouts.LANE_ELEM_KIND.get(desc, 0L);
        int elemBytes = (int) Layouts.LANE_ELEM_BYTES.get(desc, 0L);
        int strideBytes = (int) Layouts.LANE_STRIDE_BYTES.get(desc, 0L);
        int flags = (int) Layouts.LANE_FLAGS.get(desc, 0L);

        MemorySegment segment = MemorySegment.ofAddress(addr).reinterpret(byteLen);
        return new LaneWindow(segment, lenElems, byteLen, owner, epoch, elemKind, elemBytes,
                strideBytes, flags);
    }

    /**
     * A bounded view of native storage plus the liveness stamp needed to distrust it later.
     *
     * <p><strong>Internal.</strong> This is the one type that carries a {@link MemorySegment}, and
     * it must never appear in a public signature.
     */
    public record LaneWindow(
            MemorySegment segment,
            long lengthElements,
            long byteLength,
            long owner,
            long epoch,
            int elemKind,
            int elemBytes,
            int strideBytes,
            int flags) {

        public boolean isWritable() {
            return (flags & Layouts.FLAG_WRITABLE) != 0;
        }

        public boolean isContiguous() {
            return (flags & Layouts.FLAG_CONTIGUOUS) != 0;
        }

        /** Read one {@code i32} element, in-process. No membrane crossing occurs. */
        public int getI32(long index) {
            return segment.get(ValueLayout.JAVA_INT, index * strideBytes);
        }

        /** Read one {@code u32} element as an unsigned value widened to {@code long}. */
        public long getU32(long index) {
            return Integer.toUnsignedLong(segment.get(ValueLayout.JAVA_INT, index * strideBytes));
        }

        /** Read one 64-bit element (an id, or a packed mask word). */
        public long getU64(long index) {
            return segment.get(ValueLayout.JAVA_LONG, index * strideBytes);
        }

        /**
         * Write one 64-bit element (a mask word) — in-process, no membrane crossing.
         *
         * <p>The caller is responsible for having already confirmed {@link #isWritable()}: mask
         * word lanes are ({@code lgj_mask_describe}, docs/abi.md §5); pattern and row-store lanes
         * are not, and this method does not itself re-check the flag on every call — the same
         * "describe once, trust the flags you already read" discipline every other accessor here
         * follows.
         */
        public void setU64(long index, long value) {
            segment.set(ValueLayout.JAVA_LONG, index * strideBytes, value);
        }
    }
}

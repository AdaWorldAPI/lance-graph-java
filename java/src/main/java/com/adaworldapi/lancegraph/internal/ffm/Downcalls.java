package com.adaworldapi.lancegraph.internal.ffm;

import com.adaworldapi.lancegraph.LanceGraphException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.LongAdder;

/**
 * Every downcall handle in the project, resolved <strong>once</strong> into static finals.
 *
 * <p>Resolution is not free — {@code Linker.downcallHandle} builds a native stub — so doing it per
 * call would be a per-crossing tax on top of the crossing itself. There are 20 downcall symbols
 * resolved here (docs/abi.md §7 + §11 + §13) and 20 handles; the 21st ABI symbol, {@code
 * lgj_abi_manifest}, resolves separately in {@link Abi} because it returns a pointer rather than a
 * status and has no failure mode.
 *
 * <p><strong>The anti-JNI rule (docs/abi.md §6) lives here.</strong> Panama makes it easy to write
 * JNI-shaped code: one downcall per element. Every wrapper below either does work proportional to
 * {@code n_rows} or is lifecycle. There is no {@code readElement}, no upcall, no serialization, no
 * {@code byte[]} bounce buffer, and no Java-side mirror of native data — by construction, not by
 * convention, because the ABI exposes no symbol that would permit one.
 *
 * <p>Each wrapper translates a negative status into a specific exception via {@link Status}, so no
 * caller above this class ever inspects a status code.
 *
 * <p><strong>Internal.</strong> {@link MemorySegment} appears in these signatures and must not
 * escape this package.
 */
public final class Downcalls {

    private Downcalls() {}

    private static final Linker LINKER = Linker.nativeLinker();

    // Widths follow docs/abi.md exactly. u32 and i32 are both JAVA_INT: the ABI passes a 32-bit
    // machine word and signedness is an interpretation, applied on the Rust side.
    private static final MethodHandle PATTERN_OPEN = mh("lgj_pattern_open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle CLOSE = mh("lgj_close",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    private static final MethodHandle RESOURCE_INFO = mh("lgj_resource_info",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle LANE_DESCRIBE = mh("lgj_lane_describe",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MASK_CREATE = mh("lgj_mask_create",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MASK_DESCRIBE = mh("lgj_mask_describe",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle MASK_AND = mh("lgj_mask_and",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

    private static final MethodHandle MASK_OR = mh("lgj_mask_or",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

    private static final MethodHandle MASK_COUNT = mh("lgj_mask_count",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle OP_EQ_U32 = mh("lgj_op_eq_u32",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    private static final MethodHandle OP_GT_I32 = mh("lgj_op_gt_i32",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    private static final MethodHandle PLAN_EVAL = mh("lgj_plan_eval",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));

    private static final MethodHandle PLAN_EVAL_SCALAR = mh("lgj_plan_eval_scalar",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));

    private static final MethodHandle REDUCE_SUM_I32 = mh("lgj_reduce_sum_i32",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    // The mask-native sweep (docs/abi.md §14, ABI minor 5). The execution half of a mask path
    // whose build half (OP_EQ_CLASSID, below) has existed since minor 2.
    private static final MethodHandle REDUCE_FACET_SUM = mh("lgj_reduce_facet_sum",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS));

    // Row store (docs/abi.md §11, ABI minor 2). Argument widths follow §11 argument-for-argument,
    // same u32/i32-are-both-JAVA_INT rule as above.
    private static final MethodHandle ROWSTORE_OPEN = mh("lgj_rowstore_open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle OP_EQ_CLASSID = mh("lgj_op_eq_classid",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    private static final MethodHandle ROW_FACET_MATCH = mh("lgj_row_facet_match",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    // Edge-bearing row store (docs/abi.md §12, ABI minor 3). One extra constructor over the
    // minor-2 shape above — no new lane, no new op.
    private static final MethodHandle ROWSTORE_OPEN_WITH_EDGES = mh("lgj_rowstore_open_with_edges",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // Mask complement + one-hop graph traversal (docs/abi.md §13, ABI minor 4). Two new symbols,
    // one status code (Status#UNSUPPORTED_DECODE_MODE); no existing symbol's semantics changed.
    private static final MethodHandle MASK_ANDNOT = mh("lgj_mask_andnot",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

    private static final MethodHandle HOP = mh("lgj_hop",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,   // store
                    ValueLayout.JAVA_INT,    // edge_classid
                    ValueLayout.JAVA_LONG,   // facet_mask
                    ValueLayout.JAVA_INT,    // decode_mode
                    ValueLayout.JAVA_LONG,   // src_mask
                    ValueLayout.JAVA_LONG)); // dst_mask

    private static MethodHandle mh(String symbol, FunctionDescriptor descriptor) {
        MemorySegment addr = Abi.lookup().find(symbol).orElseThrow(() ->
                new LanceGraphException("the native library exports no symbol '" + symbol
                        + "'; it does not implement ABI " + Layouts.LGJ_ABI_MAJOR + "."
                        + Layouts.LGJ_ABI_MINOR));
        return LINKER.downcallHandle(addr, descriptor);
    }

    // ── crossing instrumentation ─────────────────────────────────────────────────────────────

    private static final LongAdder CROSSINGS = new LongAdder();

    /**
     * Total number of membrane crossings made since JVM start.
     *
     * <p>This exists so laziness and fusion are <em>observable</em> rather than merely asserted in
     * a comment: a test can snapshot this around building a {@code View} (expecting a delta of
     * zero) and around a terminal operation (expecting a fixed small delta independent of how many
     * predicates or rows are involved).
     */
    public static long crossings() {
        return CROSSINGS.sum();
    }

    private static void crossed() {
        CROSSINGS.increment();
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    /** Build the deterministic SoA fixture. Returns the resource handle. */
    public static long patternOpen(long nRows, long seed, MemorySegment outHandle) {
        crossed();
        int st;
        try {
            st = (int) PATTERN_OPEN.invokeExact(nRows, seed, outHandle);
        } catch (Throwable t) {
            throw wrap("lgj_pattern_open", t);
        }
        Status.check("lgj_pattern_open", st);
        return outHandle.get(ValueLayout.JAVA_LONG, 0);
    }

    /** Free a resource, bump its generation, orphan its children. */
    public static void close(long handle) {
        crossed();
        int st;
        try {
            st = (int) CLOSE.invokeExact(handle);
        } catch (Throwable t) {
            throw wrap("lgj_close", t);
        }
        Status.check("lgj_close", st);
    }

    /** Fill {@code out} (a {@link Layouts#RESOURCE_INFO}-shaped segment). */
    public static void resourceInfo(long handle, MemorySegment out) {
        crossed();
        int st;
        try {
            st = (int) RESOURCE_INFO.invokeExact(handle, out);
        } catch (Throwable t) {
            throw wrap("lgj_resource_info", t);
        }
        Status.check("lgj_resource_info", st);
    }

    // ── lanes ────────────────────────────────────────────────────────────────────────────────

    /** Fill {@code out} (a {@link Layouts#LANE_DESC}-shaped segment). */
    public static void laneDescribe(long handle, int laneId, MemorySegment out) {
        crossed();
        int st;
        try {
            st = (int) LANE_DESCRIBE.invokeExact(handle, laneId, out);
        } catch (Throwable t) {
            throw wrap("lgj_lane_describe", t);
        }
        Status.check("lgj_lane_describe", st);
    }

    // ── masks ────────────────────────────────────────────────────────────────────────────────

    /** @param initial {@link Layouts#MASK_INIT_EMPTY} or {@link Layouts#MASK_INIT_ALL} */
    public static long maskCreate(long parent, int initial, MemorySegment outHandle) {
        crossed();
        int st;
        try {
            st = (int) MASK_CREATE.invokeExact(parent, initial, outHandle);
        } catch (Throwable t) {
            throw wrap("lgj_mask_create", t);
        }
        Status.check("lgj_mask_create", st);
        return outHandle.get(ValueLayout.JAVA_LONG, 0);
    }

    public static void maskDescribe(long mask, MemorySegment out) {
        crossed();
        int st;
        try {
            st = (int) MASK_DESCRIBE.invokeExact(mask, out);
        } catch (Throwable t) {
            throw wrap("lgj_mask_describe", t);
        }
        Status.check("lgj_mask_describe", st);
    }

    /** {@code dst = a & b}. {@code dst} may alias {@code a} or {@code b}. */
    public static void maskAnd(long a, long b, long dst) {
        crossed();
        int st;
        try {
            st = (int) MASK_AND.invokeExact(a, b, dst);
        } catch (Throwable t) {
            throw wrap("lgj_mask_and", t);
        }
        Status.check("lgj_mask_and", st);
    }

    /** {@code dst = a | b}. */
    public static void maskOr(long a, long b, long dst) {
        crossed();
        int st;
        try {
            st = (int) MASK_OR.invokeExact(a, b, dst);
        } catch (Throwable t) {
            throw wrap("lgj_mask_or", t);
        }
        Status.check("lgj_mask_or", st);
    }

    public static long maskCount(long mask, MemorySegment outCount) {
        crossed();
        int st;
        try {
            st = (int) MASK_COUNT.invokeExact(mask, outCount);
        } catch (Throwable t) {
            throw wrap("lgj_mask_count", t);
        }
        Status.check("lgj_mask_count", st);
        return outCount.get(ValueLayout.JAVA_LONG, 0);
    }

    // ── unfused predicates (benchmark / parity comparison only) ──────────────────────────────

    /** Overwrites {@code dstMask} with {@code lane == needle}. */
    public static void opEqU32(long res, int laneId, int needle, long dstMask) {
        crossed();
        int st;
        try {
            st = (int) OP_EQ_U32.invokeExact(res, laneId, needle, dstMask);
        } catch (Throwable t) {
            throw wrap("lgj_op_eq_u32", t);
        }
        Status.check("lgj_op_eq_u32", st);
    }

    /** Overwrites {@code dstMask} with {@code lane > threshold}, signed. */
    public static void opGtI32(long res, int laneId, int threshold, long dstMask) {
        crossed();
        int st;
        try {
            st = (int) OP_GT_I32.invokeExact(res, laneId, threshold, dstMask);
        } catch (Throwable t) {
            throw wrap("lgj_op_gt_i32", t);
        }
        Status.check("lgj_op_gt_i32", st);
    }

    // ── fused plan — N predicates, ONE crossing ──────────────────────────────────────────────

    /**
     * Evaluate a whole predicate chain in one crossing and return the popcount.
     *
     * <p>This is the call that makes {@code .where(..).where(..).count()} cost one crossing
     * regardless of predicate count or row count.
     */
    public static long planEval(long res, MemorySegment ops, int nOps, long dstMask,
                                MemorySegment outCount) {
        crossed();
        int st;
        try {
            st = (int) PLAN_EVAL.invokeExact(res, ops, nOps, dstMask, outCount);
        } catch (Throwable t) {
            throw wrap("lgj_plan_eval", t);
        }
        Status.check("lgj_plan_eval", st);
        return outCount.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Identical semantics to {@link #planEval} but forced down the scalar reference path.
     *
     * <p>Exists only so SIMD-vs-scalar parity is falsifiable <em>through the membrane</em>, which
     * is where the Java tests live. Not for production use.
     */
    public static long planEvalScalar(long res, MemorySegment ops, int nOps, long dstMask,
                                      MemorySegment outCount) {
        crossed();
        int st;
        try {
            st = (int) PLAN_EVAL_SCALAR.invokeExact(res, ops, nOps, dstMask, outCount);
        } catch (Throwable t) {
            throw wrap("lgj_plan_eval_scalar", t);
        }
        Status.check("lgj_plan_eval_scalar", st);
        return outCount.get(ValueLayout.JAVA_LONG, 0);
    }

    // ── reduction ────────────────────────────────────────────────────────────────────────────

    /** Sum an {@code I32} lane over set mask bits into a widened {@code i64}. */
    public static long reduceSumI32(long res, int laneId, long mask, MemorySegment outSum) {
        crossed();
        int st;
        try {
            st = (int) REDUCE_SUM_I32.invokeExact(res, laneId, mask, outSum);
        } catch (Throwable t) {
            throw wrap("lgj_reduce_sum_i32", t);
        }
        Status.check("lgj_reduce_sum_i32", st);
        return outSum.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Sum one facet's 12-byte register, under {@code carving}, over the rows a mask selects.
     *
     * <p>Work is proportional to the mask's popcount, not the row count — this is a bulk op in
     * the §6 sense, and an empty mask costs O(mask words).
     */
    public static long reduceFacetSum(long res, int facet, int carving, long mask,
            MemorySegment outSum) {
        crossed();
        int st;
        try {
            st = (int) REDUCE_FACET_SUM.invokeExact(res, facet, carving, mask, outSum);
        } catch (Throwable t) {
            throw wrap("lgj_reduce_facet_sum", t);
        }
        Status.check("lgj_reduce_facet_sum", st);
        return outSum.get(ValueLayout.JAVA_LONG, 0);
    }

    // ── row store (docs/abi.md §11, ABI minor 2) ─────────────────────────────────────────────
    //
    // Callers above this class are expected to have already checked Abi.requireMinor(2) — these
    // wrappers do not re-check it, matching every other wrapper here: this class marshals and maps
    // status, it does not gate on version. See Engine for the requireMinor call sites.

    /**
     * Build the 64K×512-byte SoA row store deterministically from {@code seed} (docs/abi.md §11).
     * Returns the resource handle.
     */
    public static long rowstoreOpen(long nRows, long seed, MemorySegment outHandle) {
        crossed();
        int st;
        try {
            st = (int) ROWSTORE_OPEN.invokeExact(nRows, seed, outHandle);
        } catch (Throwable t) {
            throw wrap("lgj_rowstore_open", t);
        }
        Status.check("lgj_rowstore_open", st);
        return outHandle.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Build the edge-bearing SoA row store (docs/abi.md §12). Byte-identical classid stream to
     * {@link #rowstoreOpen}; a sparse, gated subset of {@code edgeClassid}-matching facets carries
     * a bounded-local-neighbourhood target row instead of raw noise.
     */
    public static long rowstoreOpenWithEdges(long nRows, long seed, int edgeClassid,
                                             long edgeGateMask, int edgeRadius,
                                             MemorySegment outHandle) {
        crossed();
        int st;
        try {
            st = (int) ROWSTORE_OPEN_WITH_EDGES.invokeExact(
                    nRows, seed, edgeClassid, edgeGateMask, edgeRadius, outHandle);
        } catch (Throwable t) {
            throw wrap("lgj_rowstore_open_with_edges", t);
        }
        Status.check("lgj_rowstore_open_with_edges", st);
        return outHandle.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Overwrites {@code dstMask} with {@code classid(facet, row) == needle}. {@code facet} is a
     * facet index {@code 0..32} into the row's 32 facet lanes, <strong>not</strong> a lane id
     * (docs/abi.md §11).
     */
    public static void opEqClassid(long res, int facet, int needle, long dstMask) {
        crossed();
        int st;
        try {
            st = (int) OP_EQ_CLASSID.invokeExact(res, facet, needle, dstMask);
        } catch (Throwable t) {
            throw wrap("lgj_op_eq_classid", t);
        }
        Status.check("lgj_op_eq_classid", st);
    }

    /**
     * Writes, for every row, a {@code u32} bitset of which of its 32 facets carry {@code needle} as
     * classid, into the caller's {@code out} buffer — zero-copy out, nothing serialized
     * (docs/abi.md §11). Capacity is checked by the native side before anything is written
     * ({@code MASK_LENGTH_MISMATCH} on a short buffer).
     */
    public static void rowFacetMatch(long res, int needle, MemorySegment out, long outLenElems) {
        crossed();
        int st;
        try {
            st = (int) ROW_FACET_MATCH.invokeExact(res, needle, out, outLenElems);
        } catch (Throwable t) {
            throw wrap("lgj_row_facet_match", t);
        }
        Status.check("lgj_row_facet_match", st);
    }

    // ── mask complement + one-hop graph traversal (docs/abi.md §13, ABI minor 4) ─────────────
    //
    // Same discipline as the row store section above: callers above this class are expected to
    // have already checked Abi.requireMinor(4) (see Engine's maskAndNot/hop) — these wrappers
    // marshal and map status only.

    /**
     * {@code dst = a & !b}, word-wise. Not commutative: {@code dst} aliasing {@code a} is a
     * different case from {@code dst} aliasing {@code b} (docs/abi.md §13's full aliasing rule).
     */
    public static void maskAndNot(long a, long b, long dst) {
        crossed();
        int st;
        try {
            st = (int) MASK_ANDNOT.invokeExact(a, b, dst);
        } catch (Throwable t) {
            throw wrap("lgj_mask_andnot", t);
        }
        Status.check("lgj_mask_andnot", st);
    }

    /**
     * Overwrites {@code dstMask} with the one-hop reachable set from {@code srcMask} over
     * {@code store}'s {@code edgeClassid}-matching facets, gated by the effective participation
     * {@code facetMask ∩ provider.edge_participation(edgeClassid)} (docs/abi.md §13).
     */
    public static void hop(long store, int edgeClassid, long facetMask, int decodeMode,
                           long srcMask, long dstMask) {
        crossed();
        int st;
        try {
            st = (int) HOP.invokeExact(store, edgeClassid, facetMask, decodeMode, srcMask, dstMask);
        } catch (Throwable t) {
            throw wrap("lgj_hop", t);
        }
        Status.check("lgj_hop", st);
    }

    private static LanceGraphException wrap(String symbol, Throwable t) {
        if (t instanceof LanceGraphException e) {
            return e;
        }
        return new LanceGraphException("the downcall to " + symbol + " itself failed", t);
    }
}

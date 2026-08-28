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
 * call would be a per-crossing tax on top of the crossing itself. There are 21 downcall symbols
 * resolved here (docs/abi.md §7 + §11 + §13 + §14) and 21 handles — 20 eagerly, plus the minor-5
 * {@code lgj_reduce_facet_sum} in the lazy {@code Minor5} holder below. The 22nd ABI symbol,
 * {@code lgj_abi_manifest}, resolves separately in {@link Abi} because it returns a pointer rather
 * than a status and has no failure mode.
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

    // ── Handles for later minors live in LAZY HOLDERS, one per minor ────────────────────────
    //
    // Everything above this line is the minor-1 base surface: a library missing any of it is not
    // an older library, it is a WRONG one, so eager resolution there is correct — that failure
    // should be immediate and total.
    //
    // Below is a different case, and eager resolution there was a real defect. It defeats
    // {@link Abi#requireMinor(int)}, whose own contract promises to fail "before any downcall for
    // the feature is attempted": one absent symbol breaks <clinit>, so the guard never runs and an
    // unrelated, OLDER feature dies with a bare "no such symbol".
    //
    // Measured with the CURRENT Java against real libraries built from this repo's own history:
    //
    //     minor 1 library -> SmokeTest died on 'lgj_rowstore_open'             (a minor-2 symbol)
    //     minor 2 library -> SmokeTest died on 'lgj_rowstore_open_with_edges'  (minor 3)
    //     minor 3 library -> SmokeTest died on 'lgj_mask_andnot'               (minor 4)
    //
    // SmokeTest uses nothing newer than minor 1. Against the minor-1 library it could not even run
    // minor-1 operations. A nested holder initialises on first ACCESS rather than with Downcalls,
    // so each minor now fails only when a caller actually reaches for it — and every Engine entry
    // point calls requireMinor(N) first, so what surfaces is AbiMismatchException naming the
    // minor, which is what the gate was written to deliver.

    /** Row store (docs/abi.md §11, ABI minor 2). */
    private static final class Minor2 {
        // Argument widths follow §11 argument-for-argument, same u32/i32-are-both-JAVA_INT rule as
        // the base surface above.
        static final MethodHandle ROWSTORE_OPEN = mh("lgj_rowstore_open",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

        static final MethodHandle OP_EQ_CLASSID = mh("lgj_op_eq_classid",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

        static final MethodHandle ROW_FACET_MATCH = mh("lgj_row_facet_match",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        private Minor2() {}
    }

    /**
     * Edge-bearing row store (docs/abi.md §12, ABI minor 3) — one extra constructor over the
     * minor-2 shape; no new lane, no new op.
     */
    private static final class Minor3 {
        static final MethodHandle ROWSTORE_OPEN_WITH_EDGES = mh("lgj_rowstore_open_with_edges",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        private Minor3() {}
    }

    /**
     * Mask complement + one-hop graph traversal (docs/abi.md §13, ABI minor 4). Two symbols, one
     * status code (Status#UNSUPPORTED_DECODE_MODE); no existing symbol's semantics changed.
     */
    private static final class Minor4 {
        static final MethodHandle MASK_ANDNOT = mh("lgj_mask_andnot",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        static final MethodHandle HOP = mh("lgj_hop",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,   // store
                        ValueLayout.JAVA_INT,    // edge_classid
                        ValueLayout.JAVA_LONG,   // facet_mask
                        ValueLayout.JAVA_INT,    // decode_mode
                        ValueLayout.JAVA_LONG,   // src_mask
                        ValueLayout.JAVA_LONG)); // dst_mask

        private Minor4() {}
    }


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
     * The minor-5 handle, resolved LAZILY.
     *
     * <p>Every other handle in this class is a static final resolved in {@code <clinit>}, which
     * means a single absent symbol makes the whole class unusable. That is not a style
     * preference — it actively DEFEATS {@link Abi#requireMinor(int)}, whose own contract promises
     * to fail "before any downcall for the feature is attempted". Measured against a real
     * ABI 0.4 library: {@code SmokeTest}, which touches nothing newer than minor 1, died in
     * {@code Downcalls.<clinit>} because {@code lgj_reduce_facet_sum} was missing. The guard
     * never got to run.
     *
     * <p>A nested holder class is initialised on first ACCESS, not when {@code Downcalls} is,
     * so an old library now fails only when the minor-5 feature is actually called — and
     * {@link Engine#facetSum} calls {@code requireMinor(5)} first, so what a caller sees is the
     * intended {@link com.adaworldapi.lancegraph.AbiMismatchException}, not a bare "no such
     * symbol".
     *
     * <p><strong>The same latent defect applies to minors 2-4</strong> (row store, edges, hop):
     * their handles are still eager, so an ABI 0.1 library would break this class before
     * {@code requireMinor(2)} could report anything useful. Not changed here — that is a
     * pre-existing gap this PR did not introduce, and converting every handle is its own
     * change with its own falsifier. Recorded so it is tracked rather than noticed twice.
     */
    private static final class Minor5 {
        static final MethodHandle REDUCE_FACET_SUM = mh("lgj_reduce_facet_sum",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));

        private Minor5() {}
    }

    /** The mask-native sweep under a RESOLVED grouping (docs/abi.md §15, ABI minor 6). */
    private static final class Minor6 {
        static final MethodHandle REDUCE_FACET_SUM_RESOLVED = mh("lgj_reduce_facet_sum_resolved",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));

        private Minor6() {}
    }

    /** The whole-row layout probe (docs/abi.md §16, ABI minor 7). */
    private static final class Minor7 {
        static final MethodHandle ROW_LAYOUT_PROBE = mh("lgj_row_layout_probe",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        private Minor7() {}
    }

    /** ABI minor 9 symbols (minor 8 added manifest data, no symbol). Lazy per the minor-2..7 rule. */
    private static final class Minor9 {
        static final MethodHandle ROWSTORE_FACET_MATCH_COUNT = mh("lgj_rowstore_facet_match_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        private Minor9() {}
    }

    /** ABI minor 10 symbols. Lazy per the minor-2..9 rule. */
    private static final class Minor10 {
        static final MethodHandle ROWSTORE_OPEN_COLUMNAR = mh("lgj_rowstore_open_columnar",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        private Minor10() {}
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
            st = (int) Minor5.REDUCE_FACET_SUM.invokeExact(res, facet, carving, mask, outSum);
        } catch (Throwable t) {
            throw wrap("lgj_reduce_facet_sum", t);
        }
        Status.check("lgj_reduce_facet_sum", st);
        return outSum.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Sum one facet's register under the grouping the population itself resolves to, writing the
     * sum to {@code outSum} and the resolved grouping's wire value to {@code outCarving}.
     */
    public static long reduceFacetSumResolved(long res, int facet, long mask, MemorySegment outSum,
            MemorySegment outCarving) {
        crossed();
        int st;
        try {
            st = (int) Minor6.REDUCE_FACET_SUM_RESOLVED.invokeExact(res, facet, mask, outSum,
                    outCarving);
        } catch (Throwable t) {
            throw wrap("lgj_reduce_facet_sum_resolved", t);
        }
        Status.check("lgj_reduce_facet_sum_resolved", st);
        return outSum.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * For every facet, the SET of register groupings the selected rows carry — one crossing for
     * all 32 facets.
     */
    public static void rowLayoutProbe(long res, long mask, MemorySegment out, long outLen) {
        crossed();
        int st;
        try {
            st = (int) Minor7.ROW_LAYOUT_PROBE.invokeExact(res, mask, out, outLen);
        } catch (Throwable t) {
            throw wrap("lgj_row_layout_probe", t);
        }
        Status.check("lgj_row_layout_probe", st);
    }

    /**
     * Open a facet-major COLUMNAR row store (abi.md §18, minor 10) — same logical content as the
     * AoS constructors, every single-field sweep contiguous. One crossing.
     */
    public static void rowstoreOpenColumnar(long nRows, long seed, int edgeClassid,
            long edgeGateMask, int edgeRadius, MemorySegment outResource) {
        crossed();
        int st;
        try {
            st = (int) Minor10.ROWSTORE_OPEN_COLUMNAR.invokeExact(
                    nRows, seed, edgeClassid, edgeGateMask, edgeRadius, outResource);
        } catch (Throwable t) {
            throw wrap("lgj_rowstore_open_columnar", t);
        }
        Status.check("lgj_rowstore_open_columnar", st);
    }

    /**
     * Total {@code (row, facet)} slots carrying {@code classId} — the reduction over
     * {@code lgj_row_facet_match}'s answer, computed natively (abi.md §11, minor 9). One
     * crossing, one {@code u64} out-param.
     */
    public static void rowstoreFacetMatchCount(long res, int classId, MemorySegment outCount) {
        crossed();
        int st;
        try {
            st = (int) Minor9.ROWSTORE_FACET_MATCH_COUNT.invokeExact(res, classId, outCount);
        } catch (Throwable t) {
            throw wrap("lgj_rowstore_facet_match_count", t);
        }
        Status.check("lgj_rowstore_facet_match_count", st);
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
            st = (int) Minor2.ROWSTORE_OPEN.invokeExact(nRows, seed, outHandle);
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
            st = (int) Minor3.ROWSTORE_OPEN_WITH_EDGES.invokeExact(
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
            st = (int) Minor2.OP_EQ_CLASSID.invokeExact(res, facet, needle, dstMask);
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
            st = (int) Minor2.ROW_FACET_MATCH.invokeExact(res, needle, out, outLenElems);
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
            st = (int) Minor4.MASK_ANDNOT.invokeExact(a, b, dst);
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
            st = (int) Minor4.HOP.invokeExact(store, edgeClassid, facetMask, decodeMode, srcMask, dstMask);
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

package com.adaworldapi.lancegraph.bench;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * <strong>Component F — does the execution-boundary finding from Component C survive a bulkier,
 * strided predicate?</strong>
 *
 * <p>{@code C_ExecutionBoundary} answered "where does execution belong?" for a single flat
 * predicate over two contiguous {@code int} lanes: one comparison, one AND, one crossing versus a
 * Vector-API kernel reading the same bytes in place. This class asks the same question on the
 * <em>real</em> row-store layout instead of that fixture — 512-byte rows, 32 sixteen-byte facets —
 * where the "predicate" is a bulk per-row facet-match: for every row, which of its 32 strided
 * classid lanes equal a needle, folded into one {@code int} bitset per row. That is <em>more work
 * per crossing</em> than Component C's flat single-column scan (32 strided compares per row instead
 * of one), and the access pattern is fundamentally different (a facet's classid sits every 16 bytes
 * inside a 512-byte row, never contiguous with the next facet's classid at any useful vector width
 * beyond the 4 that share a 64-byte chunk).
 *
 * <p><strong>This class does not assert an answer — it measures one.</strong> Same discipline as
 * the rest of {@code bench/}: every number is produced by {@code run.sh}, every table by {@code
 * summarise.sh}, so nothing here can drift from its data (see {@code bench/README.md}'s own
 * header). Whether "Vector API beats the crossing for a single predicate" (Component C's finding)
 * still holds when the crossing carries 32× the per-row work, and when the layout is strided rather
 * than flat, is exactly what this sweep exists to find out.
 *
 * <h2>The three arms</h2>
 *
 * <p>Same shape as {@code C_ExecutionBoundary}: {@code native} crosses once into the fused
 * {@code lgj_row_facet_match} kernel ({@code ndarray::simd::MultiLaneColumn} + {@code
 * U32x16::eq_bitmask}, abi.md §11); {@code vector} reads the very same lane-0 bytes with {@code
 * jdk.incubator.vector}, zero copies ({@link Kernels#facetMatchVector}); {@code scalar} is the
 * auto-vectorisation control ({@link Kernels#facetMatchScalar}). All three are cross-checked to
 * agree row-for-row in {@link RowStoreData}'s {@code @Setup}, before anything is timed.
 *
 * <h2>A methodological asymmetry, disclosed rather than hidden</h2>
 *
 * <p>{@code native_facetMatch()} is <strong>not</strong> directly comparable to {@code
 * C_ExecutionBoundary#native_fusedPlan()} in one respect: {@code NativePattern.countOf} evaluates
 * into a <em>lazily-created, cached</em> scratch mask (see {@code NativePattern#scratch()}) — after
 * the first call, {@code native_fusedPlan()} allocates nothing on the timed path. {@code
 * RowStore.facetMatches()} has no such reusable-output variant in the public API today: every call
 * does a fresh {@code arena.allocate(JAVA_INT, rowCount)} plus constructs a new {@link
 * com.adaworldapi.lancegraph.FacetMatchView}, on every single invocation. The {@code vector} and
 * {@code scalar} arms below, by contrast, write into an {@code out} array allocated <em>once</em>
 * in {@code @Setup} — matching {@link Data}'s own precedent of pre-allocating {@code valuesHeap}
 * outside the timed body. So this sweep's {@code native} arm pays a real per-call allocation its
 * two Java competitors do not, which the equivalent Component C arm does not either. This is stated
 * up front rather than left for a reader of the raw numbers to discover: a {@code native} result
 * here reflects the public API exactly as it exists (there is no lower-level reusable-output escape
 * hatch for this operation to fall back to, the way {@code View.select()} exists for masks), but it
 * is not a clean like-for-like allocation comparison the way Component C's three arms are. A future
 * {@code facetMatchesInto(classId, MemorySegment)} overload would be the fix, and is a candidate
 * follow-up rather than something this class invents on its own.
 *
 * <p>The row sweep is narrower than Component C's nine points — three, spanning small/medium/large
 * — because this class asks a sharper, single question (does the per-row-work multiplier change the
 * crossover) rather than needing to bracket a crossover point across four orders of magnitude from
 * scratch.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class F_RowStoreFacetScan {

    @Param({"4096", "65536", "1048576"})
    public int rows;

    private RowStoreData data;

    /**
     * Allocated once in {@code @Setup}, reused by every {@code java_*} invocation — the same
     * "pre-allocate outside the timed body" rule {@link Data#valuesHeap} already models. See the
     * class doc's asymmetry note: {@code native_facetMatch()} has no equivalent to reuse.
     */
    private int[] out;

    @Setup(Level.Trial)
    public void setup() {
        data = new RowStoreData(rows);
        out = new int[rows];
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        data.close();
    }

    /**
     * The product path. One crossing into {@code lgj_row_facet_match}; the result is a
     * caller-owned segment the native kernel wrote into directly, reduced to a bit count in Java
     * (the same "bulk reduction over an already-crossed result" rationale as {@link
     * com.adaworldapi.lancegraph.FacetMatchView#cardinality}).
     *
     * <p>See the class doc's asymmetry note — this arm allocates a fresh output segment every
     * call, unlike {@code C_ExecutionBoundary#native_fusedPlan()}.
     */
    @Benchmark
    public long native_facetMatch() {
        return data.store.facetMatches(RowStoreData.CLASSID_NEEDLE).cardinality();
    }

    /** Java Vector API over the same lane-0 bytes. Zero copies, {@code out} reused across calls. */
    @Benchmark
    public long java_vectorApi() {
        Kernels.facetMatchVector(data.raw, rows, RowStoreData.CLASSID_NEEDLE, out);
        return bitCountAll(out);
    }

    /** Ordinary Java loop over the same lane-0 bytes. The auto-vectorisation control. */
    @Benchmark
    public long java_scalar() {
        Kernels.facetMatchScalar(data.raw, rows, RowStoreData.CLASSID_NEEDLE, out);
        return bitCountAll(out);
    }

    /** Sum of set bits across every row's facet-match bitset — comparable to {@code cardinality()}. */
    private static long bitCountAll(int[] values) {
        long total = 0;
        for (int v : values) {
            total += Integer.bitCount(v);
        }
        return total;
    }
}

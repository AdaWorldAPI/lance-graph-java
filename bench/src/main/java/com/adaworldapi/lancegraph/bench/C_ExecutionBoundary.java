package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.Pattern;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * <strong>Components C and D together — WHERE DOES EXECUTION BELONG?</strong>
 *
 * <p>These are in one class deliberately. The question is a comparison, the comparison only means
 * anything if both sides see the same bytes at the same row count in the same JVM run, and putting
 * them in separate classes would let a difference in setup masquerade as a difference in
 * execution.
 *
 * <p>Same 65,536-row (and 64-row, and 4,194,304-row, …) fixture, same predicate
 * {@code class == 7 AND value > 100}, same answer asserted equal in {@link Data#crossCheck} before
 * anything is timed:
 *
 * <ul>
 *   <li><strong>native</strong> — the fluent chain, one crossing, the fused plan, an
 *       {@code ndarray::simd} AVX-512 kernel;
 *   <li><strong>vector</strong> — {@code jdk.incubator.vector}, 512-bit species, reading the very
 *       same {@code MemorySegment} with zero copies;
 *   <li><strong>scalar</strong> — an ordinary Java loop over the same segment, which C2 may
 *       auto-vectorise. The control that separates "the Vector API helped" from "the JIT was
 *       already doing it".
 * </ul>
 *
 * <p><strong>The row sweep is the actual experiment.</strong> A single row count cannot answer the
 * question, because the two sides have different shapes: the native path pays a fixed crossing and
 * then runs at native speed, the Java path pays nothing fixed and runs at whatever the JIT
 * achieves. Two lines with different intercepts and different slopes cross somewhere, and the
 * crossing point — not either endpoint — is the engineering answer. The sweep spans four orders of
 * magnitude so the crossover is bracketed rather than extrapolated.
 *
 * <p>The row counts also deliberately straddle the cache hierarchy on this host: 65,536 rows is
 * 256 KiB per lane, 1,048,576 rows is 4 MiB per lane, and 4,194,304 rows is 16 MiB per lane — past
 * L3, where both sides become memory-bound and the answer changes for a reason that has nothing to
 * do with either implementation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class C_ExecutionBoundary {

    @Param({"64", "256", "1024", "4096", "16384", "65536", "262144", "1048576", "4194304"})
    public int rows;

    private Data data;

    @Setup(Level.Trial)
    public void setup() { data = new Data(rows); }

    @TearDown(Level.Trial)
    public void tearDown() { data.close(); }

    /** The product path. One crossing, whatever the row count and whatever the predicate count. */
    @Benchmark
    public long native_fusedPlan() {
        return data.pattern.view()
                .where(Pattern.CLASS.eq(Data.CLASS_NEEDLE))
                .where(Pattern.VALUE.gt(Data.VALUE_THRESHOLD))
                .count();
    }

    /** Java Vector API over the same native memory. Zero copies, 512-bit species. */
    @Benchmark
    public long java_vectorApi() {
        return Kernels.countVector(data.classes, data.values, rows,
                Data.CLASS_NEEDLE, Data.VALUE_THRESHOLD);
    }

    /** Ordinary Java loop over the same native memory. The auto-vectorisation control. */
    @Benchmark
    public long java_scalarLoop() {
        return Kernels.countScalar(data.classes, data.values, rows,
                Data.CLASS_NEEDLE, Data.VALUE_THRESHOLD);
    }
}

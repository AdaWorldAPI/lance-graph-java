package com.adaworldapi.lancegraph.bench;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * <strong>Component B — how fast can Java read native memory at all?</strong>
 *
 * <p>Separate from every other component because it is the ceiling on the Java-side answer. If
 * reading a {@code MemorySegment} were much slower than reading an {@code int[]}, then "execute in
 * Java over native memory" would be dead before any kernel was written, and the Vector API
 * comparison in Component D would be measuring a handicap rather than a design.
 *
 * <p>Three subjects over the same values, same length, same answer (asserted in
 * {@link Data#crossCheck}):
 *
 * <ul>
 *   <li>{@code MemorySegment} scalar — native memory, one element at a time;
 *   <li>{@code MemorySegment} vector — native memory, 16 lanes at a time, zero copy;
 *   <li>{@code int[]} heap — the same data already on the Java heap, which is the fastest thing
 *       Java has and therefore the right baseline.
 * </ul>
 *
 * <p>The heap array exists <em>only</em> as this baseline. Nothing that compares against the
 * native kernel uses it, because populating it is precisely the bulk copy the ABI forbids —
 * measuring against it here is legitimate (it answers "is FFM access competitive with heap
 * access?"), and using it in Component D would not be.
 *
 * <p>Throughput is reported per operation over the whole lane, so a per-element cost can be
 * divided out and compared against memory bandwidth.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class B_SegmentAccess {

    /**
     * 65,536 rows = 256 KiB per lane. Chosen to sit above L2 and below L3 on this host, so the
     * measurement is of the access mechanism rather than of a cache that happens to hold
     * everything. The full cache-size sweep lives in Component D, where it changes the answer.
     */
    @Param({"65536"})
    public int rows;

    private Data data;

    @Setup(Level.Trial)
    public void setup() { data = new Data(rows); }

    @TearDown(Level.Trial)
    public void tearDown() { data.close(); }

    @Benchmark
    public long segmentScalar() {
        return Kernels.sumAllScalar(data.values, rows);
    }

    @Benchmark
    public long segmentVector() {
        return Kernels.sumAllVector(data.values, rows);
    }

    @Benchmark
    public long heapArrayBaseline() {
        return Kernels.sumAllHeap(data.valuesHeap);
    }
}

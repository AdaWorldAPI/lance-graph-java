package com.adaworldapi.lancegraph.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;

/**
 * <strong>Component A — what does crossing the membrane cost, on its own?</strong>
 *
 * <p>This is the number every other number in this harness has to be read against, and it is
 * measured in isolation on purpose. Conflating "the crossing" with "the work" is the single
 * easiest way to produce a benchmark that argues for whichever side the author already preferred:
 * put enough work behind the call and the crossing vanishes; put none behind it and the crossing
 * is everything.
 *
 * <p>Two subjects, both real symbols of this ABI — nothing was added to the native library for
 * benchmarking, because a symbol that exists only to be measured is not the thing being measured:
 *
 * <ul>
 *   <li>{@code lgj_abi_manifest()} — no arguments, no allocation, returns a pointer to a static.
 *       This is as close to a bare downcall as this ABI can offer.
 *   <li>{@code lgj_mask_count(handle, out)} — two arguments, one out-pointer write, and O(1) real
 *       work (a single word's popcount on a 64-row pattern). This is "a downcall that also did
 *       something", and the gap between the two is the marshalling cost of arguments and the
 *       out-parameter round trip.
 * </ul>
 *
 * <p>The handles are bound here rather than reused from {@code internal.ffm.Downcalls} so that the
 * measurement is of the JDK's linker, not of this project's wrapper layer. The wrapper's own
 * overhead shows up as the difference between this component and Component C.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class A_DowncallOverhead {

    private MethodHandle manifest;
    private MethodHandle maskCount;
    private Arena arena;
    private MemorySegment out;
    private long maskHandle;
    private Data data;

    @Setup(Level.Trial)
    public void setup() {
        SymbolLookup lookup = SymbolLookup.libraryLookup(Harness.libraryPath(), Arena.global());
        Linker linker = Linker.nativeLinker();

        manifest = linker.downcallHandle(
                lookup.find("lgj_abi_manifest").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));

        maskCount = linker.downcallHandle(
                lookup.find("lgj_mask_count").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

        arena = Arena.ofShared();
        out = arena.allocate(ValueLayout.JAVA_LONG);

        // 64 rows: exactly one mask word, so lgj_mask_count does the least work it can while
        // still being a real bulk entry point rather than a synthetic no-op.
        data = new Data(64);
        maskHandle = data.pattern.view().select().id().token();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
        data.close();
    }

    /** The floor: a downcall that takes nothing, allocates nothing, and returns a static pointer. */
    @Benchmark
    public MemorySegment bareDowncall_noArgs() throws Throwable {
        return (MemorySegment) manifest.invokeExact();
    }

    /** A downcall with two arguments and an out-pointer, doing O(1) work behind it. */
    @Benchmark
    public long downcall_twoArgs_outPointer() throws Throwable {
        int status = (int) maskCount.invokeExact(maskHandle, out);
        if (status != 0) throw new IllegalStateException("status " + status);
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * The control: a Java method call of comparable shape that does not cross anything.
     *
     * <p>Without this row, "a downcall costs N ns" has no scale. With it, the reader can see how
     * many ordinary calls a crossing is worth, which is the form the number is actually used in.
     */
    @Benchmark
    public void javaCallControl(Blackhole bh) {
        bh.consume(notACrossing(maskHandle));
    }

    private static long notACrossing(long h) {
        return h ^ 0x5DEECE66DL;
    }
}

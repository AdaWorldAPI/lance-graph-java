package com.adaworldapi.lancegraph.bench;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Java-side implementations of the same predicate the Rust kernel evaluates, so that
 * "where does execution belong?" is answered by two real implementations rather than by one
 * implementation and an estimate.
 *
 * <h2>Zero-copy is the whole point</h2>
 *
 * <p>Every method here reads the <strong>native lane directly</strong> through
 * {@link IntVector#fromMemorySegment}. No {@code byte[]}, no {@code int[]}, no bulk copy, no
 * {@code MemorySegment.toArray}. If any copy existed the comparison would be dishonest twice
 * over: the Java side would be paying a cost the Rust side does not, and the Rust side would be
 * getting credit for avoiding a copy that nothing in the design requires.
 *
 * <p>{@link ByteOrder#nativeOrder()} is passed explicitly. The ABI states little-endian and
 * asserts it via the manifest's magic probe, but a vector load that assumed an order would be a
 * silent corruption on a big-endian host rather than a failure.
 *
 * <h2>Why the scalar version is here too</h2>
 *
 * <p>Because "the Vector API was fast" and "the JIT auto-vectorised an ordinary loop" are
 * different claims with different consequences, and only measuring both separates them. If the
 * scalar loop matches the explicit vector loop, the Vector API bought nothing on this shape.
 */
public final class Kernels {

    private Kernels() {}

    /** 512-bit on this host: 16 {@code int} lanes, the same width the Rust AVX-512 kernel uses. */
    public static final VectorSpecies<Integer> I32 = IntVector.SPECIES_PREFERRED;

    /**
     * {@code count(class == needle AND value > threshold)} over two native lanes, explicitly
     * vectorised, reading the native memory in place.
     *
     * <p>The {@code u32} equality is done on the raw {@code int} bits, which is correct: equality
     * is sign-agnostic, so no widening is needed and none is done. The {@code i32} comparison is
     * {@link VectorOperators#GT}, a signed compare — the fixture's values straddle zero precisely
     * so that getting this wrong would show up as a wrong answer rather than as agreement.
     */
    public static long countVector(MemorySegment classes, MemorySegment values, int rows,
                                   int needle, int threshold) {
        long count = 0;
        int upper = I32.loopBound(rows);
        int i = 0;
        for (; i < upper; i += I32.length()) {
            long off = (long) i * Integer.BYTES;
            IntVector c = IntVector.fromMemorySegment(I32, classes, off, ByteOrder.nativeOrder());
            IntVector v = IntVector.fromMemorySegment(I32, values, off, ByteOrder.nativeOrder());
            VectorMask<Integer> m = c.compare(VectorOperators.EQ, needle)
                    .and(v.compare(VectorOperators.GT, threshold));
            count += m.trueCount();
        }
        // Tail. Handled scalar rather than with a masked load because the two produce identical
        // results and the scalar tail is the one a reader can check by eye.
        for (; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            if (classes.get(ValueLayout.JAVA_INT, off) == needle
                    && values.get(ValueLayout.JAVA_INT, off) > threshold) {
                count++;
            }
        }
        return count;
    }

    /** As {@link #countVector}, also summing the matching {@code i32} values into an {@code i64}. */
    public static long sumVector(MemorySegment classes, MemorySegment values, int rows,
                                 int needle, int threshold) {
        long sum = 0;
        int upper = I32.loopBound(rows);
        int i = 0;
        for (; i < upper; i += I32.length()) {
            long off = (long) i * Integer.BYTES;
            IntVector c = IntVector.fromMemorySegment(I32, classes, off, ByteOrder.nativeOrder());
            IntVector v = IntVector.fromMemorySegment(I32, values, off, ByteOrder.nativeOrder());
            VectorMask<Integer> m = c.compare(VectorOperators.EQ, needle)
                    .and(v.compare(VectorOperators.GT, threshold));
            // Widening to i64 lane-wise would need two long vectors per int vector; the values are
            // bounded by the fixture to [-150, 361] so an int-lane reduction cannot overflow at
            // 16 lanes, and the accumulation into `sum` is the widening.
            sum += v.reduceLanes(VectorOperators.ADD, m);
        }
        for (; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            int v = values.get(ValueLayout.JAVA_INT, off);
            if (classes.get(ValueLayout.JAVA_INT, off) == needle && v > threshold) sum += v;
        }
        return sum;
    }

    /**
     * The same predicate as an ordinary scalar loop over the same native memory.
     *
     * <p>Present as the control. C2 auto-vectorises loops like this one, so if it matches
     * {@link #countVector} then the Vector API contributed nothing here and the honest conclusion
     * is about the JIT, not about the API.
     */
    public static long countScalar(MemorySegment classes, MemorySegment values, int rows,
                                   int needle, int threshold) {
        long count = 0;
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            if (classes.get(ValueLayout.JAVA_INT, off) == needle
                    && values.get(ValueLayout.JAVA_INT, off) > threshold) {
                count++;
            }
        }
        return count;
    }

    /** Sum every element of an {@code i32} lane. Used for raw segment read throughput. */
    public static long sumAllScalar(MemorySegment lane, int rows) {
        long sum = 0;
        for (int i = 0; i < rows; i++) sum += lane.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
        return sum;
    }

    /** Sum every element of an {@code i32} lane, explicitly vectorised. */
    public static long sumAllVector(MemorySegment lane, int rows) {
        IntVector acc = IntVector.zero(I32);
        int upper = I32.loopBound(rows);
        int i = 0;
        for (; i < upper; i += I32.length()) {
            acc = acc.add(IntVector.fromMemorySegment(I32, lane, (long) i * Integer.BYTES,
                    ByteOrder.nativeOrder()));
        }
        long sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < rows; i++) sum += lane.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
        return sum;
    }

    /** Sum every element of a heap {@code int[]}. The "data already in Java" baseline. */
    public static long sumAllHeap(int[] lane) {
        long sum = 0;
        for (int v : lane) sum += v;
        return sum;
    }
}

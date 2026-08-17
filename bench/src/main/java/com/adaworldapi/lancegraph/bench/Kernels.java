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

    // ── row-store facet match (docs/abi.md §11) ─────────────────────────────────────────────
    //
    // Mirrors native/lgj-abi/src/kernels.rs::simd_rowstore_facet_match exactly: 512-byte rows,
    // 32 sixteen-byte facets (4-byte LE classid + 12-byte payload). Both methods below read the
    // *raw lane-0 buffer* (RowStore's `n_rows * 512`-byte U8 segment) directly — no facet-typed
    // MemorySegment, no per-facet strided lane, matching the Rust kernel's own choice to walk the
    // whole byte buffer rather than 32 separate strided columns.

    /** One row is 512 bytes; one facet is 16 bytes; a row therefore holds 32 facets. */
    private static final int ROW_BYTES = 512;
    private static final int FACET_BYTES = 16;
    private static final int FACETS_PER_ROW = ROW_BYTES / FACET_BYTES; // 32
    /** One 64-byte chunk covers 4 facets (16 {@code int} lanes = 4 facets × 4 ints each). */
    private static final int CHUNKS_PER_ROW = ROW_BYTES / 64; // 8

    static {
        // The chunked vector algorithm below assumes a 16-lane (512-bit) preferred species, the
        // same assumption this class's Javadoc already states for I32 and that C_ExecutionBoundary
        // relies on implicitly. Fail loudly rather than silently mis-fold facet bits on a host
        // where SPECIES_PREFERRED is narrower (e.g. 256-bit/8-lane AVX2) — a correct narrower
        // implementation would need 2 chunks per 64-byte block and was not written, since this
        // bench targets the AVX-512 host the rest of the repo already targets.
        if (I32.length() != 16) {
            throw new ExceptionInInitializerError(
                    "facetMatchVector's chunk math assumes a 16-lane (512-bit) preferred species; "
                            + "this host's IntVector.SPECIES_PREFERRED has " + I32.length() + " lanes");
        }
    }

    /**
     * For every row of {@code raw} (the {@link com.adaworldapi.lancegraph.RowStore} lane-0 buffer),
     * which of its 32 facets carry {@code needle} as classid — the Java Vector API arm, mirroring
     * {@code simd_rowstore_facet_match} in {@code native/lgj-abi/src/kernels.rs} bit-for-bit.
     *
     * <h2>Algorithm (identical to the Rust kernel)</h2>
     *
     * <p>Each row is walked as 8 sixty-four-byte chunks; each chunk is loaded as one 16-lane
     * {@code int} vector (4 facets × 4 ints — classid, then 3 payload ints). Comparing the whole
     * chunk against {@code needle} for equality and masking to bits {@code 0/4/8/12} isolates
     * exactly the 4 classid lanes — bits 1/2/3, 5/6/7, 9/10/11, 13/14/15 are payload and MUST NOT
     * contribute, or a payload int that happens to equal {@code needle} would be mistaken for a
     * classid match (see {@code facet_match_ignores_needle_patterns_in_payload_bytes} in the Rust
     * test module for the exact failure mode this masking prevents). The four isolated classid bits
     * are folded into 4 consecutive output bits and OR'd into the row's accumulator at the chunk's
     * facet offset.
     *
     * <p>{@code out} is <strong>fully overwritten</strong> — {@code out[row]} is assigned, never
     * OR'd into, so stale caller bits never survive (same convention as every native mask writer
     * in this project).
     *
     * @param raw    the row store's lane-0 segment, {@code rows * 512} bytes, read in place
     * @param rows   how many rows {@code raw} covers
     * @param needle the classid to match
     * @param out    fully overwritten; must have length &gt;= {@code rows}
     */
    public static void facetMatchVector(MemorySegment raw, int rows, int needle, int[] out) {
        for (int row = 0; row < rows; row++) {
            int acc = 0;
            long rowBase = (long) row * ROW_BYTES;
            for (int chunkInRow = 0; chunkInRow < CHUNKS_PER_ROW; chunkInRow++) {
                long off = rowBase + (long) chunkInRow * 64;
                IntVector v = IntVector.fromMemorySegment(I32, raw, off, ByteOrder.nativeOrder());
                // Bit i of toLong() is set exactly when lane i compared equal. Masking to
                // 0x1111 keeps only the four classid lanes (0, 4, 8, 12); the fold below packs
                // those four bits down into bits 0..3 of facetBits, the same shape the Rust
                // kernel's `(m & 1) | ((m >> 4) & 1) << 1 | ...` produces.
                long m = v.compare(VectorOperators.EQ, needle).toLong() & 0x1111L;
                int facetBits = (int) ((m & 1L)
                        | (((m >>> 4) & 1L) << 1)
                        | (((m >>> 8) & 1L) << 2)
                        | (((m >>> 12) & 1L) << 3));
                acc |= facetBits << (4 * chunkInRow);
            }
            out[row] = acc;
        }
    }

    /**
     * As {@link #facetMatchVector}, as a plain scalar loop — the auto-vectorisation control, same
     * rationale as {@link #countScalar} beside {@link #countVector}.
     *
     * <p>Reads each facet's classid directly at its own byte offset ({@code row*512 + facet*16})
     * rather than walking 64-byte chunks — a simpler, independently-derived formulation of the same
     * algorithm (facet {@code f}'s classid always sits at that exact offset regardless of how the
     * vector arm groups facets into chunks), so a bug shared between the two arms is less likely to
     * agree by coincidence.
     *
     * @param raw    the row store's lane-0 segment, {@code rows * 512} bytes, read in place
     * @param rows   how many rows {@code raw} covers
     * @param needle the classid to match
     * @param out    fully overwritten; must have length &gt;= {@code rows}
     */
    public static void facetMatchScalar(MemorySegment raw, int rows, int needle, int[] out) {
        for (int row = 0; row < rows; row++) {
            int acc = 0;
            long rowBase = (long) row * ROW_BYTES;
            for (int facet = 0; facet < FACETS_PER_ROW; facet++) {
                long off = rowBase + (long) facet * FACET_BYTES;
                if (raw.get(ValueLayout.JAVA_INT, off) == needle) {
                    acc |= 1 << facet;
                }
            }
            out[row] = acc;
        }
    }
}

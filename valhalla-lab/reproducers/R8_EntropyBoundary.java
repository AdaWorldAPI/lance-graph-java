// Reproducer R8 — the four-arm symmetric benchmark the R7 review demanded. R7 proved Java
// projection allocates nothing; it deliberately proved NOTHING about Java-vs-Rust speed
// (no FFI, no Rust in the loop). R8 measures that, brutally symmetric: same 1 MiB of bytes
// (filled by the SAME native r8_fill for every arm), same classid distribution, same
// 10^9-op accounting, same checksum -- checksum equality across arms is the proof that
// every arm did the same work on the same bytes.
//
//   A  Java MemorySegment + carving switch          (R7's loop, in-process)
//   B  one bulk FFI call -> Rust generic sweep       (Rust re-derives carving per row)
//   C  per-projection FFI                            (the anti-JNI shape, 10^8 ops, scaled)
//   D  Java resolves the ClassView preset, then calls MONOMORPHIC Rust kernels per
//      carving subpopulation -- the "entropy reduced before the call" arm
//   E  the MASK-NATIVE lawful shape (part 2 only): per-carving bitmasks built by
//      ndarray::simd::eq_u32_strided_to_mask (the polyfill dispatch surface -- abi.md §8),
//      sweep driven by mask-bit iteration. D' materializes an index-list population; E
//      keeps the population as the mask currency the workspace law requires.
//
//   javac --enable-preview -source 27 -target 27 -d out R8_EntropyBoundary.java
//   java  --enable-preview --enable-native-access=ALL-UNNAMED -Dr8.lib=$PWD/libr8_native.so \
//         -cp out R8_EntropyBoundary
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public class R8_EntropyBoundary {

    static final long ROWS = 65_536;
    static final int FACET_BYTES = 16;
    static final int REGISTER_OFF = 4;
    static final long TARGET = 1_000_000_000L;   // divisible by 16 => no row ends mid-visit
    static final long C_TARGET = 100_000_000L;   // arm C is 10x smaller; reported per-op

    public static void main(String[] args) throws Throwable {
        Linker lk = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(System.getProperty("r8.lib"), Arena.global());
        MethodHandle fill = lk.downcallHandle(lib.findOrThrow("r8_fill"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MethodHandle generic = lk.downcallHandle(lib.findOrThrow("r8_sweep_generic"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        MethodHandle one = lk.downcallHandle(lib.findOrThrow("r8_project_one"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle fillRandom = lk.downcallHandle(lib.findOrThrow("r8_fill_random"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG));
        MethodHandle rails = mono(lk, lib, "r8_sweep_rails");
        MethodHandle triplets = mono(lk, lib, "r8_sweep_triplets");
        MethodHandle quads = mono(lk, lib, "r8_sweep_quads");
        MethodHandle idxRails = idx(lk, lib, "r8_idx_rails");
        MethodHandle idxTriplets = idx(lk, lib, "r8_idx_triplets");
        MethodHandle idxQuads = idx(lk, lib, "r8_idx_quads");
        SymbolLookup libE = SymbolLookup.libraryLookup(System.getProperty("r8.ndlib"), Arena.global());
        MethodHandle masksBuild = lk.downcallHandle(libE.findOrThrow("r8e_masks_build"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG));
        MethodHandle maskCount = lk.downcallHandle(libE.findOrThrow("r8e_mask_count"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG));
        MethodHandle maskRails = msk(lk, libE, "r8e_mask_sweep_rails");
        MethodHandle maskTriplets = msk(lk, libE, "r8e_mask_sweep_triplets");
        MethodHandle maskQuads = msk(lk, libE, "r8e_mask_sweep_quads");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ROWS * FACET_BYTES);
            fill.invokeExact(seg, ROWS); // ONE fill authority for every arm, incl. arm A

            // warm every arm's code path
            long w = sweepJava(seg, 50_000_000L);
            for (int i = 0; i < 3; i++) w += (long) generic.invokeExact(seg, ROWS, 50_000_000L);
            w += armD(rails, triplets, quads, seg, 16_000_000L);
            for (long r = 0; r < 2_000_000; r++)
                w += (int) one.invokeExact(seg, r % ROWS, (int) (r & 3), 0);

            System.out.println("arm  ops           s        M ops/s   checksum");
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = sweepJava(seg, TARGET);
                report("A ", TARGET, t0, acc);
            }
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = (long) generic.invokeExact(seg, ROWS, TARGET);
                report("B ", TARGET, t0, acc);
            }
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = armC(one, seg, C_TARGET);
                report("C ", C_TARGET, t0, acc);
            }
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = armD(rails, triplets, quads, seg, TARGET);
                report("D ", TARGET, t0, acc);
            }
            System.out.println("(warm-sink " + (w != 0) + ")");
            // cross-check arm C's prefix checksum against the Java sweep at the same op count
            System.out.println("C-prefix cross-check (Java sweep @1e8): " + sweepJava(seg, C_TARGET));

            // ── PART 2: RANDOM classid distribution ─────────────────────────────────────
            // The period-4 pattern above hands the generic sweep free specialization via the
            // branch predictor. Random classids take that away -- this is the configuration
            // where "entropy reduced before the call" can actually earn something.
            fillRandom.invokeExact(seg, ROWS, 0x5DEECE66DL);

            // Java scans ONCE to build the per-carving row partitions (the entropy-reduction
            // step, priced separately below). The partition is the materialized form of a
            // per-carving mask; it lives in native memory, not on the Java heap.
            long tScan = System.nanoTime();
            int[] counts = new int[3];
            for (long r = 0; r < ROWS; r++)
                counts[carvingIdx(seg.get(ValueLayout.JAVA_INT, r * FACET_BYTES) & 3)]++;
            MemorySegment[] idxs = new MemorySegment[3];
            for (int c = 0; c < 3; c++) idxs[c] = arena.allocate((long) counts[c] * 4);
            int[] fillPos = new int[3];
            for (long r = 0; r < ROWS; r++) {
                int c = carvingIdx(seg.get(ValueLayout.JAVA_INT, r * FACET_BYTES) & 3);
                idxs[c].setAtIndex(ValueLayout.JAVA_INT, fillPos[c]++, (int) r);
            }
            double scanMs = (System.nanoTime() - tScan) / 1e6;
            long opsPerPass = 6L * counts[0] + 4L * counts[1] + 3L * counts[2];
            long passes = TARGET / opsPerPass;
            long target2 = passes * opsPerPass; // full passes: multiset-equal across arms
            System.out.printf("%nrandom fill: counts rails=%d triplets=%d quads=%d, "
                    + "opsPerPass=%d, passes=%d, target=%,d, partition scan %.2f ms%n",
                    counts[0], counts[1], counts[2], opsPerPass, passes, target2, scanMs);

            // warm part-2 paths
            long w2 = sweepJava(seg, 50_000_000L);
            for (int i = 0; i < 3; i++) w2 += (long) generic.invokeExact(seg, ROWS, 50_000_000L);
            w2 += armDIdx(idxRails, idxTriplets, idxQuads, seg, idxs, counts, 2);

            System.out.println("arm  ops           s        M ops/s   checksum");
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = sweepJava(seg, target2);
                report("A'", target2, t0, acc);
            }
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = (long) generic.invokeExact(seg, ROWS, target2);
                report("B'", target2, t0, acc);
            }
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = armDIdx(idxRails, idxTriplets, idxQuads, seg, idxs, counts, passes);
                report("D'", target2, t0, acc);
            }

            // ── Arm E: masks built by ndarray::simd, sweep driven by the masks ──
            long words = (ROWS + 63) / 64;
            MemorySegment mRails = arena.allocate(words * 8);
            MemorySegment mTriplets = arena.allocate(words * 8);
            MemorySegment mQuads = arena.allocate(words * 8);
            long tMask = System.nanoTime();
            masksBuild.invokeExact(seg, ROWS, mRails, mTriplets, mQuads, words);
            double maskMs = (System.nanoTime() - tMask) / 1e6;
            long cr = (long) maskCount.invokeExact(mRails, words);
            long ct = (long) maskCount.invokeExact(mTriplets, words);
            long cq = (long) maskCount.invokeExact(mQuads, words);
            System.out.printf("mask build (ndarray::simd, one bulk FFI call): %.3f ms; "
                    + "popcounts rails=%d triplets=%d quads=%d (must equal the scan's counts)%n",
                    maskMs, cr, ct, cq);
            if (cr != counts[0] || ct != counts[1] || cq != counts[2])
                throw new AssertionError("mask popcounts disagree with the Java partition scan");
            long we = armE(maskRails, maskTriplets, maskQuads, seg, mRails, mTriplets, mQuads,
                    words, 2);
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                long acc = armE(maskRails, maskTriplets, maskQuads, seg, mRails, mTriplets,
                        mQuads, words, passes);
                report("E'", target2, t0, acc);
            }
            System.out.println("(warm-sink " + (w2 != 0 && we != 0) + ")");
        }
    }

    static int carvingIdx(int cid) { return cid == 0 ? 0 : cid == 1 ? 1 : 2; }

    static MethodHandle idx(Linker lk, SymbolLookup lib, String name) {
        return lk.downcallHandle(lib.findOrThrow(name),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    }

    static MethodHandle msk(Linker lk, SymbolLookup lib, String name) {
        return lk.downcallHandle(lib.findOrThrow(name),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    }

    static long armE(MethodHandle r, MethodHandle t, MethodHandle q, MemorySegment seg,
                     MemorySegment mr, MemorySegment mt, MemorySegment mq, long words,
                     long passes) throws Throwable {
        long acc = (long) r.invokeExact(seg, mr, words, passes);
        acc += (long) t.invokeExact(seg, mt, words, passes);
        acc += (long) q.invokeExact(seg, mq, words, passes);
        return acc;
    }

    static long armDIdx(MethodHandle r, MethodHandle t, MethodHandle q, MemorySegment seg,
                        MemorySegment[] idxs, int[] counts, long passes) throws Throwable {
        long acc = (long) r.invokeExact(seg, idxs[0], (long) counts[0], passes);
        acc += (long) t.invokeExact(seg, idxs[1], (long) counts[1], passes);
        acc += (long) q.invokeExact(seg, idxs[2], (long) counts[2], passes);
        return acc;
    }

    static MethodHandle mono(Linker lk, SymbolLookup lib, String name) {
        return lk.downcallHandle(lib.findOrThrow(name),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    }

    static void report(String arm, long ops, long t0, long acc) {
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%s   %,13d %7.2f %9.1f   %d%n", arm, ops, s, ops / s / 1e6, acc);
    }

    // ── Arm A: R7's Java loop, verbatim shape ──
    static long sweepJava(MemorySegment seg, long target) {
        long acc = 0, done = 0, row = 0;
        while (done < target) {
            int cid = seg.get(ValueLayout.JAVA_INT, row * FACET_BYTES) & 3;
            int n = switch (cid) { case 0 -> 6; case 1 -> 4; default -> 3; };
            for (int g = 0; g < n && done < target; g++, done++) acc += groupAt(seg, row, cid, g);
            row = (row + 1) % ROWS;
        }
        return acc;
    }

    static int groupAt(MemorySegment seg, long row, int cid, int g) {
        long base = row * FACET_BYTES + REGISTER_OFF;
        return switch (cid) {
            case 0 -> (seg.get(ValueLayout.JAVA_BYTE, base + g * 2L) & 0xFF)
                    | ((seg.get(ValueLayout.JAVA_BYTE, base + g * 2L + 1) & 0xFF) << 8);
            case 1 -> (seg.get(ValueLayout.JAVA_BYTE, base + g * 3L) & 0xFF)
                    | ((seg.get(ValueLayout.JAVA_BYTE, base + g * 3L + 1) & 0xFF) << 8)
                    | ((seg.get(ValueLayout.JAVA_BYTE, base + g * 3L + 2) & 0xFF) << 16);
            default -> seg.get(ValueLayout.JAVA_INT_UNALIGNED, base + g * 4L);
        };
    }

    // ── Arm C: one FFI crossing per projection ──
    static long armC(MethodHandle one, MemorySegment seg, long target) throws Throwable {
        long acc = 0, done = 0, row = 0;
        while (done < target) {
            int cid = seg.get(ValueLayout.JAVA_INT, row * FACET_BYTES) & 3;
            int n = switch (cid) { case 0 -> 6; case 1 -> 4; default -> 3; };
            for (int g = 0; g < n && done < target; g++, done++)
                acc += (int) one.invokeExact(seg, row, cid, g);
            row = (row + 1) % ROWS;
        }
        return acc;
    }

    // ── Arm D: Java has resolved the preset; Rust runs monomorphic kernels per carving
    // subpopulation. Apportioning per 4-row cycle (6+4+3+3 = 16 ops): rails 6/16,
    // triplets 4/16, quads 3/16 per start row. ROWS % 4 == 0, so row%4 -> carving is
    // stable across wraps and the op multiset equals arm B's exactly (hence checksum ==).
    static long armD(MethodHandle rails, MethodHandle triplets, MethodHandle quads,
                     MemorySegment seg, long target) throws Throwable {
        long cycles = target / 16;
        long acc = (long) rails.invokeExact(seg, ROWS, 0L, 4L, cycles * 6);
        acc += (long) triplets.invokeExact(seg, ROWS, 1L, 4L, cycles * 4);
        acc += (long) quads.invokeExact(seg, ROWS, 2L, 4L, cycles * 3);
        acc += (long) quads.invokeExact(seg, ROWS, 3L, 4L, cycles * 3);
        return acc;
    }
}

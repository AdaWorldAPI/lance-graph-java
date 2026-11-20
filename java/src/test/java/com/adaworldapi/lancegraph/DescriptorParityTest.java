package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;
import com.adaworldapi.lancegraph.internal.ffm.Layouts;
import java.lang.foreign.MemoryLayout.PathElement;

/**
 * Layout parity from the Java side: the lane descriptors Java reads through Panama agree with the
 * geometry documented in docs/abi.md §11/§18 for BOTH store layouts, the Java struct offsets agree
 * with the literals Rust pins ({@code abi::tests::field_offsets_are_pinned_to_literals}), and every
 * (row, facet) read through the public accessors is equal across the layouts AND equal to an
 * independent Java transcription of the documented generator.
 *
 * <p>The expected geometry here is computed from the documentation, never from {@code Layouts} or
 * from any offset the production code derives. The generator transcription is a TEST ORACLE only;
 * nothing in {@code src/main} gains a compute path from this file.
 *
 * <p>DISABLE (verified red-then-green): change the facet-major lo64 expected offset in
 * {@link #expected} (e.g. {@code 128 * n + f * 8 * n} to {@code 128 * n + f * 8 * n + 1}) and the
 * descriptor check goes red for every facet-major lo64 lane, while row-major stays green. Change a
 * literal in {@link #pinStructOffsets} and the struct-offset check goes red.
 *
 * <p>Alignment is deliberately NOT asserted: block offsets are 64-aligned only when n is a
 * multiple of 16 (docs/abi.md §18), and no Java read requires it.
 */
public final class DescriptorParityTest {

    private DescriptorParityTest() {}

    private static final int FACETS = 32;
    private static final int LANES = 1 + 3 * FACETS;

    public static void main(String[] args) {
        System.out.println("DescriptorParityTest");
        Checks c = new Checks("DescriptorParityTest");
        run(c);
        System.exit(c.report());
    }

    /** SplitMix64, transcribed from docs/abi.md §11 (no warm-up draws). */
    private static final class SplitMix64 {
        private long state;

        SplitMix64(long seed) {
            this.state = seed;
        }

        long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    /** Documented geometry of one lane: offset from the raw lane, length, element bytes, stride. */
    private record Geometry(long offset, long len, int elemBytes, int stride, int kind) {}

    private static Geometry expected(boolean columnar, long n, int lane) {
        if (lane == 0) {
            return new Geometry(0, 512 * n, 1, 1, 1);
        }
        int field = (lane - 1) / FACETS;
        long f = (lane - 1) % FACETS;
        if (!columnar) {
            return switch (field) {
                case 0 -> new Geometry(f * 16, n, 4, 512, 5);
                case 1 -> new Geometry(f * 16 + 4, n, 8, 512, 7);
                default -> new Geometry(f * 16 + 12, n, 4, 512, 5);
            };
        }
        return switch (field) {
            case 0 -> new Geometry(f * 4 * n, n, 4, 4, 5);
            case 1 -> new Geometry(128 * n + f * 8 * n, n, 8, 8, 7);
            default -> new Geometry(384 * n + f * 4 * n, n, 4, 4, 5);
        };
    }

    public static void run(Checks c) {
        pinStructOffsets(c);

        for (boolean columnar : new boolean[] {false, true}) {
            String name = columnar ? "facet-major" : "row-major";
            for (long n : new long[] {16, 257, 4096}) {
                c.section(name + " descriptors at n=" + n);
                try (RowStore s = columnar ? RowStore.openColumnar(n, 0x1A7EL)
                                           : RowStore.open(n, 0x1A7EL)) {
                    checkDescriptors(c, s, columnar, n, name);
                }
            }
        }

        accessorsAgree(c);
    }

    private static void pinStructOffsets(Checks c) {
        c.section("Java struct offsets equal the literals Rust pins");
        c.eq("LgjLaneDesc size", 56L, Layouts.LANE_DESC.byteSize());
        String[] lane = {"addr", "len_elems", "byte_len", "owner", "epoch", "elem_kind",
            "elem_bytes", "stride_bytes", "flags"};
        long[] laneOff = {0, 8, 16, 24, 32, 40, 44, 48, 52};
        for (int i = 0; i < lane.length; i++) {
            c.eq("LgjLaneDesc." + lane[i], laneOff[i],
                    Layouts.LANE_DESC.byteOffset(PathElement.groupElement(lane[i])));
        }
        c.eq("LgjResourceInfo size", 32L, Layouts.RESOURCE_INFO.byteSize());
        String[] info = {"kind", "lane_count", "n_rows", "epoch", "parent"};
        long[] infoOff = {0, 4, 8, 16, 24};
        for (int i = 0; i < info.length; i++) {
            c.eq("LgjResourceInfo." + info[i], infoOff[i],
                    Layouts.RESOURCE_INFO.byteOffset(PathElement.groupElement(info[i])));
        }
        c.eq("LgjOpDesc size", 24L, Layouts.OP_DESC.byteSize());
        String[] op = {"op", "lane_id", "operand", "combine", "_reserved"};
        long[] opOff = {0, 4, 8, 16, 20};
        for (int i = 0; i < op.length; i++) {
            c.eq("LgjOpDesc." + op[i], opOff[i],
                    Layouts.OP_DESC.byteOffset(PathElement.groupElement(op[i])));
        }
    }

    private static void checkDescriptors(Checks c, RowStore s, boolean columnar, long n,
            String name) {
        long handle = s.handle();
        Engine.LaneWindow raw = Engine.describeLane(handle, 0);
        long base = raw.segment().address();
        int bad = 0;
        long covered = 0;
        boolean[] seenByte = new boolean[(int) (512 * n)];
        boolean disjoint = true;
        for (int lane = 0; lane < LANES; lane++) {
            Engine.LaneWindow w = Engine.describeLane(handle, lane);
            Geometry g = expected(columnar, n, lane);
            long byteLen = (g.len() - 1) * g.stride() + g.elemBytes();
            boolean contiguous = g.stride() == g.elemBytes();
            boolean ok = w.segment().address() - base == g.offset()
                    && w.lengthElements() == g.len()
                    && w.elemBytes() == g.elemBytes()
                    && w.strideBytes() == g.stride()
                    && w.elemKind() == g.kind()
                    && w.byteLength() == byteLen
                    && w.segment().byteSize() == byteLen
                    && w.isContiguous() == contiguous
                    && (w.flags() & Layouts.FLAG_READABLE) != 0
                    && !w.isWritable();
            if (!ok) {
                bad++;
                System.out.println("      lane " + lane + " " + w + " expected " + g);
            }
            if (lane != 0) {
                for (long row = 0; row < g.len(); row++) {
                    int at = (int) (g.offset() + row * g.stride());
                    for (int b = 0; b < g.elemBytes(); b++) {
                        if (seenByte[at + b]) {
                            disjoint = false;
                        }
                        seenByte[at + b] = true;
                        covered++;
                    }
                }
            }
        }
        c.eq(name + " n=" + n + ": lanes whose descriptor differs from the documented geometry",
                0L, bad);
        c.that(name + " n=" + n + ": the 96 facet lanes are pairwise disjoint", disjoint);
        c.eq(name + " n=" + n + ": and cover exactly 512n bytes", 512 * n, covered);
    }

    private static void accessorsAgree(Checks c) {
        c.section("every (row, facet) agrees across layouts and with the generator oracle, n=257");
        final int n = 257;
        final long seed = 0xF00DCAFEL;
        // Oracle: classid = (a >>> 33) & 0xF; payload = le64(b) ++ le32(a & 0xFFFFFFFF).
        int[] classid = new int[n * FACETS];
        long[] lo64 = new long[n * FACETS];
        int[] hi32 = new int[n * FACETS];
        SplitMix64 rng = new SplitMix64(seed);
        for (int i = 0; i < n * FACETS; i++) {
            long a = rng.next();
            long b = rng.next();
            classid[i] = (int) ((a >>> 33) & 0xF);
            lo64[i] = b;
            hi32[i] = (int) a;
        }
        try (RowStore aos = RowStore.open(n, seed);
                RowStore col = RowStore.openColumnar(n, seed)) {
            long compared = 0;
            long crossMismatch = 0;
            long oracleMismatchAos = 0;
            long oracleMismatchCol = 0;
            for (int row = 0; row < n; row++) {
                for (int f = 0; f < FACETS; f++) {
                    FacetId facet = FacetId.of(f);
                    int i = row * FACETS + f;
                    int ca = aos.classidAt(row, facet);
                    int cc = col.classidAt(row, facet);
                    long la = aos.payloadLow64At(row, facet);
                    long lc = col.payloadLow64At(row, facet);
                    int ha = aos.payloadHi32At(row, facet);
                    int hc = col.payloadHi32At(row, facet);
                    if (ca != cc || la != lc || ha != hc) {
                        crossMismatch++;
                    }
                    if (ca != classid[i] || la != lo64[i] || ha != hi32[i]) {
                        oracleMismatchAos++;
                    }
                    if (cc != classid[i] || lc != lo64[i] || hc != hi32[i]) {
                        oracleMismatchCol++;
                    }
                    compared++;
                }
            }
            c.eq("(row, facet) pairs compared", (long) n * FACETS, compared);
            c.eq("row-major vs facet-major mismatches", 0L, crossMismatch);
            c.eq("row-major vs the Java generator oracle mismatches", 0L, oracleMismatchAos);
            c.eq("facet-major vs the Java generator oracle mismatches", 0L, oracleMismatchCol);
            // Anti-vacuity: the oracle is not constant, and the layouts' bytes really differ.
            c.that("oracle classids span the full 0..16 range",
                    java.util.Arrays.stream(classid).distinct().count() == 16);
            c.that("oracle payloads are not constant", lo64[0] != lo64[1] && hi32[0] != hi32[1]);
            c.that("the two stores really are arranged differently (lane 1 base-relative offset"
                    + " of facet 1 differs)",
                    Engine.describeLane(col.handle(), 2).segment().address()
                                    - Engine.describeLane(col.handle(), 0).segment().address()
                            != Engine.describeLane(aos.handle(), 2).segment().address()
                                    - Engine.describeLane(aos.handle(), 0).segment().address());
        }
    }
}

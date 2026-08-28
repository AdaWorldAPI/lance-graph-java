// Reproducer R11 — the physical layout (AoS row-major vs SoA facet-lanes) expressed as DATA,
// applied to ONE sweep function, and the difference measured rather than predicted.
//
// The claim under test, flagged in review as "arithmetic, not a result": the current store is
// AoS (32 facets x 16 B interleaved in a 512-B row), so a one-facet sweep uses 16 of every
// 64-B line (25%). A true SoA facet lane (that facet's 16 B contiguous across rows) packs 4
// rows per line (100%). Predicted ~4x on a line-bound sweep — HERE MEASURED.
//
// The second claim is structural and matters more than the ratio: because Java only ever
// PROJECTS (R5/R7), the AoS->SoA flip touches no Java type and no sweep code. The layout is a
// LayoutSchema record — data — and the same projector runs under either. Valhalla is untouched
// by construction: what crosses is still a <=4-B group; only OFFSETS moved. And the native
// kernels are already stride-parameterized (masked_strided_group_sum, eq_u32_strided_to_mask),
// so the same holds below the membrane: AoS is stride 512, an SoA lane is stride 16, one code
// path either way.
//
//   javac --enable-preview -source 27 -target 27 -d out R11_LayoutIsASchema.java
//   java  --enable-preview --enable-native-access=ALL-UNNAMED -cp out R11_LayoutIsASchema
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class R11_LayoutIsASchema {

    static final int ROWS = 1 << 16;       // 65,536
    static final int FACETS = 32;
    static final int FACET_BYTES = 16;     // classid(4) + register(12)
    static final long TOTAL = (long) ROWS * FACETS * FACET_BYTES;  // 32 MiB — larger than L2

    /**
     * The layout AS DATA: where facet f's slot for row r begins. Both layouts describe the
     * same 32 MiB of facet slots; only the address function differs.
     *
     *   AoS : base = r * 512 + f * 16          (rows outer   — today's store)
     *   SoA : base = f * (ROWS*16) + r * 16    (facets outer — 32 lanes of 12-byte registers)
     */
    record LayoutSchema(String name, long rowStrideBytes, long facetBase) {
        long slot(long row, int facet) {
            return facet * facetBase + row * rowStrideBytes
                    + (facetBase == 0 ? facet * (long) FACET_BYTES : 0);
        }

        static LayoutSchema aos() {
            return new LayoutSchema("AoS 512-stride", (long) FACETS * FACET_BYTES, 0);
        }

        static LayoutSchema soa() {
            return new LayoutSchema("SoA facet-lane", FACET_BYTES, (long) ROWS * FACET_BYTES);
        }
    }

    /** ONE projector for both layouts — quads sweep of facet f over every row. */
    static long sweep(MemorySegment seg, LayoutSchema ls, int facet) {
        long acc = 0;
        for (long r = 0; r < ROWS; r++) {
            long reg = ls.slot(r, facet) + 4;                       // past the classid
            acc += seg.get(ValueLayout.JAVA_INT_UNALIGNED, reg);
            acc += seg.get(ValueLayout.JAVA_INT_UNALIGNED, reg + 4);
            acc += seg.get(ValueLayout.JAVA_INT_UNALIGNED, reg + 8);
        }
        return acc;
    }

    /** Fill both layouts with the SAME logical content, so checksums must agree. */
    static void fill(MemorySegment seg, LayoutSchema ls) {
        for (long r = 0; r < ROWS; r++) {
            for (int f = 0; f < FACETS; f++) {
                long base = ls.slot(r, f);
                seg.set(ValueLayout.JAVA_INT, base, (int) ((r + f) & 0xF));
                for (int k = 0; k < 12; k++) {
                    seg.set(ValueLayout.JAVA_BYTE, base + 4 + k, (byte) (r + f * 3 + k));
                }
            }
        }
    }

    public static void main(String[] args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment aosSeg = arena.allocate(TOTAL);
            MemorySegment soaSeg = arena.allocate(TOTAL);
            LayoutSchema aos = LayoutSchema.aos();
            LayoutSchema soa = LayoutSchema.soa();
            fill(aosSeg, aos);
            fill(soaSeg, soa);

            // The two layouts must be READINGS of the same logical content: every facet's
            // sweep must produce identical checksums, or the comparison below is of two
            // different datasets rather than two layouts.
            for (int f = 0; f < FACETS; f++) {
                if (sweep(aosSeg, aos, f) != sweep(soaSeg, soa, f)) {
                    throw new AssertionError("layouts diverge at facet " + f);
                }
            }
            System.out.println("checksum parity: all 32 facets identical across layouts");

            // warm
            long sink = 0;
            for (int i = 0; i < 10; i++) {
                sink += sweep(aosSeg, aos, 3) + sweep(soaSeg, soa, 3);
            }

            System.out.printf("%n%-16s %12s %10s   (one-facet full sweep, %d rows, 3 runs)%n",
                    "layout", "ns/sweep", "ns/row", ROWS);
            for (LayoutSchema ls : new LayoutSchema[] {aos, soa, aos, soa, aos, soa}) {
                MemorySegment seg = ls.facetBase == 0 ? aosSeg : soaSeg;
                long t0 = System.nanoTime();
                long acc = 0;
                for (int i = 0; i < 50; i++) {
                    acc += sweep(seg, ls, 3);
                }
                long ns = (System.nanoTime() - t0) / 50;
                sink += acc;
                System.out.printf("%-16s %,12d %10.3f%n", ls.name(), ns, ns / (double) ROWS);
            }
            System.out.println("(sink " + (sink != 0) + ")");
            System.out.println();
            System.out.println("line arithmetic: AoS touches 16/64 B per line (1 row/line);");
            System.out.println("SoA packs 4 rows/line — the measured ratio above is the extent");
            System.out.println("to which this sweep is line-bound rather than compute-bound.");
        }
    }
}

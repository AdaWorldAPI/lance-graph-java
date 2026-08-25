// Reproducer R7 — the endgame claim, stated falsifiably: ONE BILLION Java operations over
// substrate bytes with ZERO per-operation materialization.
//
// "Zero copy" is not a vibe; it is a number that must not grow. R5 measured the projecting
// path at 800 B TOTAL over 65,536 rows. If that 800 B is genuinely fixed overhead (the
// measurement scaffolding itself), then a BILLION operations must allocate the same ~constant
// bytes -- 0.000001 B/op, not 0.01. If anything per-op survives (an iterator, a boxed long,
// a lambda capture, a hidden hydration), one billion ops will multiply it into megabytes and
// the claim dies loudly.
//
// The operation is the real one: read classid -> dispatch carving -> project every group of
// the 12-byte register straight out of the MemorySegment. No element type ever exists.
//
//   javac --enable-preview -source 27 -target 27 -d out R7_BillionOpsZeroAlloc.java
//   java  --enable-preview --enable-native-access=ALL-UNNAMED -cp out R7_BillionOpsZeroAlloc
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class R7_BillionOpsZeroAlloc {

    static final int ROWS = 65_536;
    static final int FACET_BYTES = 16;
    static final int REGISTER_OFF = 4;
    static final long TARGET_OPS = 1_000_000_000L;

    enum Carving { RAILS_6x2, TRIPLETS_4x3, QUADS_3x4 }

    static Carving carvingOf(int classid) {
        return switch (classid & 0x3) {
            case 0 -> Carving.RAILS_6x2;
            case 1 -> Carving.TRIPLETS_4x3;
            default -> Carving.QUADS_3x4;
        };
    }

    // One "operation" = one group projection. Identical shape to R5's projector.
    static int groupAt(MemorySegment seg, long row, Carving c, int index) {
        long base = row * FACET_BYTES + REGISTER_OFF;
        return switch (c) {
            case RAILS_6x2 -> {
                long o = base + index * 2L;
                yield (seg.get(ValueLayout.JAVA_BYTE, o) & 0xFF)
                    | ((seg.get(ValueLayout.JAVA_BYTE, o + 1) & 0xFF) << 8);
            }
            case TRIPLETS_4x3 -> {
                long o = base + index * 3L;
                yield (seg.get(ValueLayout.JAVA_BYTE, o) & 0xFF)
                    | ((seg.get(ValueLayout.JAVA_BYTE, o + 1) & 0xFF) << 8)
                    | ((seg.get(ValueLayout.JAVA_BYTE, o + 2) & 0xFF) << 16);
            }
            case QUADS_3x4 -> seg.get(ValueLayout.JAVA_INT_UNALIGNED, base + index * 4L);
        };
    }

    static int groupsIn(Carving c) {
        return switch (c) { case RAILS_6x2 -> 6; case TRIPLETS_4x3 -> 4; case QUADS_3x4 -> 3; };
    }

    static long allocated() {
        var b = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean();
        return b.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    public static void main(String[] args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) ROWS * FACET_BYTES);
            for (long r = 0; r < ROWS; r++) {
                seg.set(ValueLayout.JAVA_INT, r * FACET_BYTES, (int) (r & 0x3));
                for (int b = 0; b < 12; b++) {
                    seg.set(ValueLayout.JAVA_BYTE, r * FACET_BYTES + 4 + b, (byte) (r + b));
                }
            }

            // Warm-up: let C2 finish its business BEFORE the measured window, so JIT-compile
            // -time allocation is not misattributed to the operations themselves.
            long sink = 0;
            for (int pass = 0; pass < 3; pass++) sink += sweep(seg, 50_000_000L);

            long a0 = allocated();
            long t0 = System.nanoTime();
            long acc = sweep(seg, TARGET_OPS);
            long ns = System.nanoTime() - t0;
            long bytes = allocated() - a0;

            System.out.printf("ops                : %,d group projections%n", TARGET_OPS);
            System.out.printf("allocated          : %,d B total  ->  %.9f B/op%n",
                    bytes, bytes / (double) TARGET_OPS);
            System.out.printf("wall               : %.2f s  ->  %.1f M ops/s  (%.2f ns/op)%n",
                    ns / 1e9, TARGET_OPS / (ns / 1e3), ns / (double) TARGET_OPS);
            System.out.println("checksums non-zero : " + (acc != 0) + " " + (sink != 0));
        }
    }

    // Runs `target` group projections, cycling rows. Returns a live checksum so nothing DCEs.
    static long sweep(MemorySegment seg, long target) {
        long acc = 0, done = 0, row = 0;
        while (done < target) {
            int cid = seg.get(ValueLayout.JAVA_INT, row * FACET_BYTES);
            Carving c = carvingOf(cid);
            int n = groupsIn(c);
            for (int g = 0; g < n && done < target; g++, done++) {
                acc += groupAt(seg, row, c, g);
            }
            row = (row + 1) % ROWS;
        }
        return acc;
    }
}

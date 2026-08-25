// Reproducer R5 — a classid-dependent layout has no static spelling in either Panama or
// Valhalla, and hydrating one hands the JVM layout authority it should never have.
//
// Context: the V3 facet is classid(4) + a 12-byte content-blind register, and the classid's
// ClassView decides how the register is read — 6x(u8:u8) rails, 4x(u8:u8:u8) triplets, or
// 3x(u8:u8:u8:u8) quads. The reading is chosen by a RUNTIME value. This file measures what
// each mechanism can actually express, and what hydration costs.
//
//   javac --enable-preview -source 27 -target 27 -d out R5_ClassidHasNoStaticSpelling.java
//   java  --enable-preview --enable-native-access=ALL-UNNAMED \
//     -cp out R5_ClassidHasNoStaticSpelling
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

public class R5_ClassidHasNoStaticSpelling {

    static final int ROWS = 65_536;
    static final int FACET_BYTES = 16;          // classid(4) + register(12)
    static final int REGISTER_OFF = 4;

    // The three carvings of the SAME 12 bytes. Which one applies is a property of the classid.
    enum Carving { RAILS_6x2, TRIPLETS_4x3, QUADS_3x4 }

    // Stand-in for ClassView. In the real system this resolves through the contract; the shape
    // that matters here is that it is a RUNTIME lookup, not a static type.
    static Carving carvingOf(int classid) {
        return switch (classid & 0x3) {
            case 0 -> Carving.RAILS_6x2;
            case 1 -> Carving.TRIPLETS_4x3;
            default -> Carving.QUADS_3x4;
        };
    }

    // ── (1) What Panama can express ────────────────────────────────────────────────────────
    // Precision matters here (wording tightened on operator review, 2026-08-25): Panama CAN
    // construct or choose a MemoryLayout at runtime after seeing a classid. What it cannot do
    // is make one ALREADY-BOUND VarHandle reinterpret its path per row. So layout selection
    // cannot live inside one bound handle or one static value type -- it lives in
    // descriptor/accessor dispatch, and the dispatch is Java-side in every possible design.
    static final MemoryLayout FACET = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("classid"),
            MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_BYTE).withName("register"));
    static final VarHandle CLASSID =
            FACET.varHandle(MemoryLayout.PathElement.groupElement("classid"));

    // ── (2) The projector: read the carving's fields straight out of the segment ───────────
    // No element type, no value class, no object at any width. The JVM is never asked to lay
    // anything out, so its 8-byte budget (R4) never enters the picture.
    static int railAt(MemorySegment seg, long row, int rail) {
        long base = row * FACET_BYTES + REGISTER_OFF + rail * 2L;
        int lo = seg.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
        int hi = seg.get(ValueLayout.JAVA_BYTE, base + 1) & 0xFF;
        return lo | (hi << 8);
    }

    static int groupAt(MemorySegment seg, long row, Carving c, int index) {
        long base = row * FACET_BYTES + REGISTER_OFF;
        return switch (c) {
            case RAILS_6x2 -> railAt(seg, row, index);
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

    // ── (3) The hydrator, for contrast: a 16-byte facet as a value object ─────────────────
    // R4 measured this shape as NOT flat at any spelling, so an array of it is an array of
    // references and every row costs a real object.
    static value record Quad(byte a, byte b, byte c, byte d) {}
    static value record Facet(int classid, Quad q0, Quad q1, Quad q2) {}

    static Facet hydrate(MemorySegment seg, long row) {
        long base = row * FACET_BYTES;
        int cid = seg.get(ValueLayout.JAVA_INT, base);
        return new Facet(cid,
                quadAt(seg, base + 4), quadAt(seg, base + 8), quadAt(seg, base + 12));
    }

    static Quad quadAt(MemorySegment seg, long o) {
        return new Quad(seg.get(ValueLayout.JAVA_BYTE, o), seg.get(ValueLayout.JAVA_BYTE, o + 1),
                        seg.get(ValueLayout.JAVA_BYTE, o + 2), seg.get(ValueLayout.JAVA_BYTE, o + 3));
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

            System.out.println("== (1) what each mechanism can express ==");
            System.out.println("Panama VarHandle bound to a fixed path : classid of row 0 = "
                    + (int) CLASSID.get(seg, 0L));
            System.out.println("  an already-bound VarHandle cannot re-derive its path per row;");
            System.out.println("  runtime classid therefore requires descriptor/accessor dispatch.");
            System.out.println("Valhalla value class is a static type   : "
                    + "cannot be selected by a runtime int either.");

            // warm both paths
            long sink = 0;
            for (int i = 0; i < 20_000; i++) {
                sink += projectAll(seg, i % ROWS);
                sink += hydrate(seg, i % ROWS).classid();
            }

            System.out.println("\n== (2) allocated bytes per row, " + ROWS + " rows ==");

            long a0 = allocated();
            long acc = 0;
            for (long r = 0; r < ROWS; r++) acc += projectAll(seg, r);
            long projectBytes = allocated() - a0;

            long b0 = allocated();
            long acc2 = 0;
            for (long r = 0; r < ROWS; r++) acc2 += hydrate(seg, r).classid();
            long hydrateBytes = allocated() - b0;

            System.out.printf("project (classid-dispatched, no element type) : %,10d B total, "
                    + "%6.2f B/row%n", projectBytes, projectBytes / (double) ROWS);
            System.out.printf("hydrate (16-byte Facet value object)          : %,10d B total, "
                    + "%6.2f B/row%n", hydrateBytes, hydrateBytes / (double) ROWS);
            System.out.println("checksums (must be non-zero, else the loops were optimised away): "
                    + (acc != 0) + " " + (acc2 != 0) + " " + (sink != 0));
        }
    }

    static long projectAll(MemorySegment seg, long row) {
        int cid = seg.get(ValueLayout.JAVA_INT, row * FACET_BYTES);
        Carving c = carvingOf(cid);
        long acc = 0;
        for (int g = 0, n = groupsIn(c); g < n; g++) acc += groupAt(seg, row, c, g);
        return acc;
    }
}

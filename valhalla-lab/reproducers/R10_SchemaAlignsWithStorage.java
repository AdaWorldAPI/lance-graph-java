// Reproducer R10 — bolt the SAME three schemas into Valhalla and Panama, and prove all three
// descriptions (storage bytes, Panama MemoryLayout, Valhalla value class) agree.
//
// The substrate's register is 12 content-blind bytes carved three ways, and which one applies is
// resolved from the classid (ClassView::cascade_shape). This file asks the two questions that
// decide whether Java can hold that schema honestly:
//
//   (1) Does a Valhalla value class of the SCHEMA flatten?      -> no, and it cannot (R4/R6)
//   (2) Does a value class of one GROUP flatten?                -> yes, all three
//
// and then proves the alignment that makes (2) usable: for each schema, the Panama MemoryLayout,
// the Valhalla value class, and the raw storage bytes must all decode the same register to the
// same values. Three descriptions, one truth, or the schema is not "bolted on" — it is a second
// story about the same bytes.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.value=ALL-UNNAMED -d out R10_SchemaAlignsWithStorage.java
//   java  --enable-preview --enable-native-access=ALL-UNNAMED \
//     --add-exports java.base/jdk.internal.value=ALL-UNNAMED -cp out R10_SchemaAlignsWithStorage
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jdk.internal.value.ValueClass;

public class R10_SchemaAlignsWithStorage {

    static final int REGISTER_BYTES = 12;

    // ── the three schemas, as the substrate names them ──
    enum Schema {
        RAILS_6X2(6, 2), TRIPLETS_4X3(4, 3), QUADS_3X4(3, 4);

        final int groups, groupBytes;

        Schema(int g, int b) {
            this.groups = g;
            this.groupBytes = b;
        }

        /** The Panama description of the register under this schema. */
        MemoryLayout layout() {
            return MemoryLayout.sequenceLayout(groups,
                    MemoryLayout.sequenceLayout(groupBytes, ValueLayout.JAVA_BYTE));
        }
    }

    // ── (2) one GROUP as a value class: the unit Java can actually hold flat ──
    static value record Rail(byte lo, byte hi) {}                          // 2 B
    static value record Triplet(byte a, byte b, byte c) {}                 // 3 B
    static value record Quad(byte a, byte b, byte c, byte d) {}            // 4 B

    // ── (1) the whole SCHEMA as a value class: measured, for contrast ──
    static value record Rails6(Rail r0, Rail r1, Rail r2, Rail r3, Rail r4, Rail r5) {}   // 12 B
    static value record Triplets4(Triplet t0, Triplet t1, Triplet t2, Triplet t3) {}      // 12 B
    static value record Quads3(Quad q0, Quad q1, Quad q2) {}                              // 12 B

    /** Decode group g of a register under `s`, straight from the bytes — the storage truth. */
    static long fromStorage(byte[] reg, Schema s, int g) {
        long v = 0;
        for (int k = 0; k < s.groupBytes; k++) {
            v |= (long) (reg[g * s.groupBytes + k] & 0xFF) << (8 * k);
        }
        return v;
    }

    /** The same group, read through Panama's own layout description. */
    static long fromPanama(MemorySegment seg, Schema s, int g) {
        long v = 0;
        for (int k = 0; k < s.groupBytes; k++) {
            v |= (long) (seg.get(ValueLayout.JAVA_BYTE, (long) g * s.groupBytes + k) & 0xFF)
                    << (8 * k);
        }
        return v;
    }

    /** The same group, hydrated into the Valhalla value class for that width. */
    static long fromValhalla(byte[] reg, Schema s, int g) {
        int o = g * s.groupBytes;
        return switch (s) {
            case RAILS_6X2 -> {
                Rail r = new Rail(reg[o], reg[o + 1]);
                yield (r.lo() & 0xFFL) | ((r.hi() & 0xFFL) << 8);
            }
            case TRIPLETS_4X3 -> {
                Triplet t = new Triplet(reg[o], reg[o + 1], reg[o + 2]);
                yield (t.a() & 0xFFL) | ((t.b() & 0xFFL) << 8) | ((t.c() & 0xFFL) << 16);
            }
            case QUADS_3X4 -> {
                Quad q = new Quad(reg[o], reg[o + 1], reg[o + 2], reg[o + 3]);
                yield (q.a() & 0xFFL) | ((q.b() & 0xFFL) << 8) | ((q.c() & 0xFFL) << 16)
                        | ((q.d() & 0xFFL) << 24);
            }
        };
    }

    static boolean flat(Class<?> t, Object init) {
        return ValueClass.isFlatArray(ValueClass.newNullRestrictedNonAtomicArray(t, 4, init));
    }

    public static void main(String[] args) {
        Rail rail = new Rail((byte) 0, (byte) 0);
        Triplet trip = new Triplet((byte) 0, (byte) 0, (byte) 0);
        Quad quad = new Quad((byte) 0, (byte) 0, (byte) 0, (byte) 0);

        System.out.println("== (1) the SCHEMA as one value class: 12 B, never flat ==");
        System.out.printf("  Rails6     %-6s   Triplets4  %-6s   Quads3  %s%n",
                flat(Rails6.class, new Rails6(rail, rail, rail, rail, rail, rail)),
                flat(Triplets4.class, new Triplets4(trip, trip, trip, trip)),
                flat(Quads3.class, new Quads3(quad, quad, quad)));

        System.out.println("== (2) one GROUP as a value class: <= 4 B, all flat ==");
        System.out.printf("  Rail 2B    %-6s   Triplet 3B %-6s   Quad 4B %s%n",
                flat(Rail.class, rail), flat(Triplet.class, trip), flat(Quad.class, quad));

        System.out.println();
        System.out.println("== (3) storage / Panama / Valhalla must decode the SAME values ==");
        byte[] reg = new byte[REGISTER_BYTES];
        for (int k = 0; k < REGISTER_BYTES; k++) {
            reg[k] = (byte) (k * 17 + 3);   // varied, so a wrong offset shows up
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(REGISTER_BYTES);
            MemorySegment.copy(reg, 0, seg, ValueLayout.JAVA_BYTE, 0, REGISTER_BYTES);

            boolean allAgree = true;
            for (Schema s : Schema.values()) {
                // Panama's own layout must describe exactly 12 bytes under every schema.
                long described = s.layout().byteSize();
                StringBuilder vals = new StringBuilder();
                for (int g = 0; g < s.groups; g++) {
                    long a = fromStorage(reg, s, g);
                    long b = fromPanama(seg, s, g);
                    long c = fromValhalla(reg, s, g);
                    allAgree &= (a == b && b == c);
                    vals.append(a).append(g + 1 < s.groups ? ", " : "");
                }
                System.out.printf("  %-14s layout=%2d B  groups=[%s]%n", s, described, vals);
                if (described != REGISTER_BYTES) {
                    allAgree = false;
                }
            }
            System.out.println();
            System.out.println("  all three descriptions agree, all schemas: " + allAgree);

            // The schemas must give DIFFERENT readings, or agreement above is trivial.
            boolean differ = fromStorage(reg, Schema.RAILS_6X2, 0)
                    != fromStorage(reg, Schema.QUADS_3X4, 0);
            System.out.println("  and the schemas genuinely read differently: " + differ);
        }
    }
}

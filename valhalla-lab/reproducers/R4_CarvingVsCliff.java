// Reproducer R4 — can the R2 8-byte array-flattening cliff be dodged by CASTING a 12/16-byte
// payload into sub-groups, rather than declaring it as one wide value class?
//
// Motivation: the lance-graph V3 content-blind facet is classid(4) + a 12-byte register that
// le-contract.md §3 carves three ways — 6x(u8:u8) rails, 4x(u8:u8:u8) SPO triplets,
// 3x(u8:u8:u8:u8) quads (6*2 = 4*3 = 3*4 = 12). R2 measured MONOLITHIC shapes (P12, P16) and
// found them never flat. It never asked whether the same total, spelled as a COMPOSITION of
// sub-8-byte value classes, behaves differently — nor whether nesting itself costs flattening
// even when the total stays under the 8-byte budget. Both are measured here.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.value=ALL-UNNAMED -d out R4_CarvingVsCliff.java
//   java  --enable-preview --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
//     -cp out R4_CarvingVsCliff
// Add -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlatArrayLayout for the VM's own element sizes.
import jdk.internal.value.ValueClass;
import jdk.internal.vm.annotation.NullRestricted;

public class R4_CarvingVsCliff {

    // ── the three V3 sub-group shapes, each individually under the 8-byte budget ──
    static value record Pair(byte a, byte b) {}                 // 2 B — the 6x2 rail
    static value record Triplet(byte a, byte b, byte c) {}      // 3 B — the 4x3 SPO triplet
    static value record Quad(byte a, byte b, byte c, byte d) {} // 4 B — the 3x4 odoo quad

    // ── Group A: nesting UNDER the budget. The question R2 never asked. ──
    // Same 4 bytes, two spellings: flat field list vs a composition of two 2-byte values.
    static value record Flat4(byte a, byte b, byte c, byte d) {}
    static value record Nest4(Pair lo, Pair hi) {}
    // Same 8 bytes, three spellings.
    static value record Flat8(long a) {}
    static value record Nest8AsPairs(Pair p0, Pair p1, Pair p2, Pair p3) {}
    static value record Nest8AsQuads(Quad lo, Quad hi) {}

    // ── Group A2: where exactly is the NESTED cliff? Sweep 5..8 B, and separate
    // "how many nested fields" from "how deep" from "how wide in total".
    static value record Nest5(Pair p, Triplet t) {}             // 5 B, 2 nested fields
    static value record Nest6AsPairs(Pair p0, Pair p1, Pair p2) {}    // 6 B, 3 nested
    static value record Nest6AsTriplets(Triplet t0, Triplet t1) {}    // 6 B, 2 nested
    static value record Nest7(Triplet t, Quad q) {}             // 7 B, 2 nested
    static value record Nest8Single(Flat8 inner) {}             // 8 B, ONE nested field
    static value record Flat6(byte a, byte b, byte c, byte d, byte e, byte f) {} // 6 B, no nesting
    static value record Flat7(byte a, byte b, byte c, byte d, byte e, byte f, byte g) {} // 7 B

    // ── Group B: the actual carvings, at the real 12-byte register width ──
    static value record Reg12AsRails(Pair r0, Pair r1, Pair r2, Pair r3, Pair r4, Pair r5) {}
    static value record Reg12AsTriplets(Triplet t0, Triplet t1, Triplet t2, Triplet t3) {}
    static value record Reg12AsQuads(Quad q0, Quad q1, Quad q2) {}
    static value record Reg12Flat(byte b0, byte b1, byte b2, byte b3, byte b4, byte b5,
                                  byte b6, byte b7, byte b8, byte b9, byte b10, byte b11) {}

    // ── Group C: the full 16-byte facet — classid(4) + the 12-byte register ──
    static value record Facet16AsRails(int classid, Pair r0, Pair r1, Pair r2,
                                       Pair r3, Pair r4, Pair r5) {}
    static value record Facet16AsQuads(int classid, Quad q0, Quad q1, Quad q2) {}

    // ── Group D: the DECISIVE test of the mechanism. ──
    // Hypothesis from the PrintFlatArrayLayout numbers: a nested value COMPONENT is stored in
    // its NULLABLE flat layout, because record components are not null-restricted by default.
    // That inflates each component (Pair 2->4, Quad 4->8) and it is the inflated sum, not the
    // nominal byte sum, that the container's flattening budget must satisfy. If that is the
    // mechanism, marking the components @NullRestricted should collapse them back to their
    // null-free sizes and flip these to flat. A value class's fields are implicitly strict, so
    // R1's VerifyError does not apply here.
    static value record Nest7NR(@NullRestricted Triplet t, @NullRestricted Quad q) {}      // 3+4
    static value record Nest8AsQuadsNR(@NullRestricted Quad lo, @NullRestricted Quad hi) {} // 4+4
    static value record Nest6AsPairsNR(@NullRestricted Pair p0, @NullRestricted Pair p1,
                                       @NullRestricted Pair p2) {}                          // 2*3

    // ── Group E: the operator's actual question, with nullability inflation REMOVED. ──
    // If @NullRestricted is what rescued Group D, does it also rescue the real 12-byte
    // register and the 16-byte facet — or is the cliff genuinely on total payload once the
    // inflation is gone? This is the case that answers "can the carving dodge the cliff".
    static value record Reg12AsQuadsNR(@NullRestricted Quad q0, @NullRestricted Quad q1,
                                       @NullRestricted Quad q2) {}                          // 12 B
    static value record Reg12AsRailsNR(@NullRestricted Pair r0, @NullRestricted Pair r1,
                                       @NullRestricted Pair r2, @NullRestricted Pair r3,
                                       @NullRestricted Pair r4, @NullRestricted Pair r5) {} // 12 B
    static value record Facet16AsQuadsNR(int classid, @NullRestricted Quad q0,
                                         @NullRestricted Quad q1,
                                         @NullRestricted Quad q2) {}                        // 16 B

    // ── Group F: the word-aligned family — "would 32x(2x8 byte) behave differently?" ──
    // The byte-packed carvings (Groups B/C) could in principle be losing flatness to sub-word
    // field packing rather than to total size. This group removes that variable entirely: every
    // field is a naturally-aligned long, no byte packing anywhere. Lane8 is one 8-byte word
    // (the budget exactly); Two8 is the 2x8 pair the operator named; the rest stack that pair
    // toward the 512-byte canonical row (32 x 2 x 8 = 512).
    static value record Lane8(long a) {}                                  //   8 B, 1 word
    static value record Two8(long a, long b) {}                           //  16 B, the 2x8 pair
    static value record Two8NR(@NullRestricted Lane8 a, @NullRestricted Lane8 b) {} // 16 B nested
    static value record Four8AsTwo8(@NullRestricted Two8 lo, @NullRestricted Two8 hi) {} // 32 B
    static value record Blk64AsTwo8(@NullRestricted Two8 a, @NullRestricted Two8 b,
                                    @NullRestricted Two8 c, @NullRestricted Two8 d) {}   // 64 B

    record Case(String name, int payload, Class<?> type, Object init) {}

    static Pair pair() { return new Pair((byte) 0, (byte) 0); }
    static Triplet trip() { return new Triplet((byte) 0, (byte) 0, (byte) 0); }
    static Quad quad() { return new Quad((byte) 0, (byte) 0, (byte) 0, (byte) 0); }
    static Two8 two8() { return new Two8(0L, 0L); }

    public static void main(String[] x) {
        Case[] cases = {
            // the sub-groups alone — each under the budget
            new Case("Pair",    2, Pair.class,    pair()),
            new Case("Triplet", 3, Triplet.class, trip()),
            new Case("Quad",    4, Quad.class,    quad()),

            // Group A — nesting under the budget: does composition itself cost flattening?
            new Case("Flat4",        4, Flat4.class,
                    new Flat4((byte) 0, (byte) 0, (byte) 0, (byte) 0)),
            new Case("Nest4",        4, Nest4.class,        new Nest4(pair(), pair())),
            new Case("Flat8",        8, Flat8.class,        new Flat8(0L)),
            new Case("Nest8AsPairs", 8, Nest8AsPairs.class,
                    new Nest8AsPairs(pair(), pair(), pair(), pair())),
            new Case("Nest8AsQuads", 8, Nest8AsQuads.class, new Nest8AsQuads(quad(), quad())),

            // Group A2 — pinning the nested cliff, and separating the candidate causes
            new Case("Flat6",           6, Flat6.class,
                    new Flat6((byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0)),
            new Case("Flat7",           7, Flat7.class,
                    new Flat7((byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0)),
            new Case("Nest5",           5, Nest5.class,           new Nest5(pair(), trip())),
            new Case("Nest6AsPairs",    6, Nest6AsPairs.class,
                    new Nest6AsPairs(pair(), pair(), pair())),
            new Case("Nest6AsTriplets", 6, Nest6AsTriplets.class,
                    new Nest6AsTriplets(trip(), trip())),
            new Case("Nest7",           7, Nest7.class,           new Nest7(trip(), quad())),
            new Case("Nest8Single",     8, Nest8Single.class,     new Nest8Single(new Flat8(0L))),

            // Group D — same shapes, components marked @NullRestricted
            new Case("Nest7NR",          7, Nest7NR.class,        new Nest7NR(trip(), quad())),
            new Case("Nest8AsQuadsNR",   8, Nest8AsQuadsNR.class,
                    new Nest8AsQuadsNR(quad(), quad())),
            new Case("Nest6AsPairsNR",   6, Nest6AsPairsNR.class,
                    new Nest6AsPairsNR(pair(), pair(), pair())),

            // Group B — the three carvings at the real 12-byte register width
            new Case("Reg12AsRails",    12, Reg12AsRails.class,
                    new Reg12AsRails(pair(), pair(), pair(), pair(), pair(), pair())),
            new Case("Reg12AsTriplets", 12, Reg12AsTriplets.class,
                    new Reg12AsTriplets(trip(), trip(), trip(), trip())),
            new Case("Reg12AsQuads",    12, Reg12AsQuads.class,
                    new Reg12AsQuads(quad(), quad(), quad())),
            new Case("Reg12Flat",       12, Reg12Flat.class,
                    new Reg12Flat((byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
                                  (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0)),

            // Group C — the full 16-byte facet
            new Case("Facet16AsRails", 16, Facet16AsRails.class,
                    new Facet16AsRails(0, pair(), pair(), pair(), pair(), pair(), pair())),
            new Case("Facet16AsQuads", 16, Facet16AsQuads.class,
                    new Facet16AsQuads(0, quad(), quad(), quad())),

            // Group E — the real register/facet with nullability inflation removed
            new Case("Reg12AsQuadsNR",   12, Reg12AsQuadsNR.class,
                    new Reg12AsQuadsNR(quad(), quad(), quad())),
            new Case("Reg12AsRailsNR",   12, Reg12AsRailsNR.class,
                    new Reg12AsRailsNR(pair(), pair(), pair(), pair(), pair(), pair())),
            new Case("Facet16AsQuadsNR", 16, Facet16AsQuadsNR.class,
                    new Facet16AsQuadsNR(0, quad(), quad(), quad())),
            // Group F — the word-aligned family: no byte packing, 8 B .. 64 B
            new Case("Lane8",         8, Lane8.class,       new Lane8(0L)),
            new Case("Two8",         16, Two8.class,        two8()),
            new Case("Two8NR",       16, Two8NR.class,      new Two8NR(new Lane8(0L), new Lane8(0L))),
            new Case("Four8AsTwo8",  32, Four8AsTwo8.class, new Four8AsTwo8(two8(), two8())),
            new Case("Blk64AsTwo8",  64, Blk64AsTwo8.class,
                    new Blk64AsTwo8(two8(), two8(), two8(), two8())),
        };

        System.out.printf("%-18s %-8s %-16s %-16s %s%n",
                "type", "payload", "NR-nonAtomic", "NR-atomic", "nullable-atomic");
        for (Case c : cases) {
            System.out.printf("%-18s %5d B  %-16s %-16s %s%n", c.name(), c.payload(),
                ValueClass.isFlatArray(
                    ValueClass.newNullRestrictedNonAtomicArray(c.type(), 16, c.init())),
                ValueClass.isFlatArray(
                    ValueClass.newNullRestrictedAtomicArray(c.type(), 16, c.init())),
                ValueClass.isFlatArray(ValueClass.newNullableAtomicArray(c.type(), 16)));
        }
    }
}

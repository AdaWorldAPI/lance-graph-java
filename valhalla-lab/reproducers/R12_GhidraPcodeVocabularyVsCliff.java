// Reproducer R12 — can Ghidra's P-code vocabulary cross the Panama membrane as Valhalla
// value classes, or must the facade address it instead of carrying it?
//
// Motivation: the r2il-machine-semantic-contract-v1 plan's W5 puts a lance-graph-java facade
// under Ghidra — `PcodeOp`/`Instruction`/`Varnode` as lazy views over handles + masks, with
// `getPcode()` paying materialization ONLY when called. Before designing that facade, one
// question decides its whole shape: are Ghidra's own P-code types flattenable?
//
// The field shapes below are TRANSCRIBED from Ghidra at the paths cited, not invented:
//
//   Varnode  (program/model/pcode/Varnode.java:51-54)
//       private Address address;   // a REFERENCE
//       private int size;
//       private int spaceID;
//       private long offset;
//
//   PcodeOp  (program/model/pcode/PcodeOp.java:102-105)
//       private int opcode;
//       private SequenceNumber seqnum;   // a REFERENCE
//       private Varnode[] input;         // a REFERENCE to an array of REFERENCES
//       private Varnode output;          // a REFERENCE
//
// R2 measured the cliff at an 8-byte payload; R4 added the finding that a reference-holding
// composition can REPORT flat while storing its components as references (`Four8AsTwo8`, 32 B,
// VM element size 8). Both traps are live here, so both are measured rather than assumed.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.value=ALL-UNNAMED -d out \
//     R12_GhidraPcodeVocabularyVsCliff.java
//   java  --enable-preview --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
//     -cp out R12_GhidraPcodeVocabularyVsCliff
// Add -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlatArrayLayout for the VM's own element sizes.
import jdk.internal.value.ValueClass;

public class R12_GhidraPcodeVocabularyVsCliff {

    // ── A: Ghidra's shapes as-is, minus the references it is not legal to model here ──
    // `Address` and `SequenceNumber` are identity classes; a value record cannot hold one
    // without giving up flatness by construction. So these are the OPTIMISTIC lower bounds:
    // the payload with every reference DELETED. If even these do not flatten, the real types
    // certainly cannot.
    static value record VarnodePayload(int size, int spaceID, long offset) {}   // 16 B
    static value record PcodeOpPayload(int opcode, long seq) {}                 // 12 B

    // ── B: the same identity spelled as an ORDINAL into a lane ──
    // The mask-native reading: a varnode's identity is WHERE it is, not what it contains.
    static value record VarnodeRef(long ordinal) {}                            // 8 B
    static value record PcodeOpRef(long ordinal) {}                            // 8 B
    static value record InstructionRef(long ordinal) {}                        // 8 B

    // ── C: the narrowed-content middle ground, to find where the line actually falls ──
    // Is 8 B enough to carry the varnode's real content rather than a pointer to it?
    // spaceID as u8, size as u8, offset as 48 bits.
    static value record VarnodeNarrow(byte spaceId, byte size, short offHi, int offLo) {} // 8 B

    record Case(String name, Class<?> type, Object init, String cited) {}

    public static void main(String[] args) {
        Case[] cases = {
            new Case("VarnodePayload(int,int,long)", VarnodePayload.class,
                    new VarnodePayload(0, 0, 0L), "Varnode.java:51-54, refs deleted"),
            new Case("PcodeOpPayload(int,long)", PcodeOpPayload.class,
                    new PcodeOpPayload(0, 0L), "PcodeOp.java:102-105, refs deleted"),
            new Case("VarnodeNarrow(8B packed)", VarnodeNarrow.class,
                    new VarnodeNarrow((byte) 0, (byte) 0, (short) 0, 0), "narrowed content"),
            new Case("VarnodeRef(long)", VarnodeRef.class,
                    new VarnodeRef(0L), "ordinal into a lane"),
            new Case("PcodeOpRef(long)", PcodeOpRef.class,
                    new PcodeOpRef(0L), "ordinal into a lane"),
            new Case("InstructionRef(long)", InstructionRef.class,
                    new InstructionRef(0L), "ordinal into a lane"),
        };

        System.out.println("R12 — Ghidra's P-code vocabulary against the 8-byte array-flattening cliff");
        System.out.println();
        System.out.printf("%-32s  %-9s  %-7s  %s%n", "shape", "nonAtomic", "atomic", "transcribed from");
        System.out.printf("%-32s  %-9s  %-7s  %s%n", "-".repeat(32), "-".repeat(9), "-".repeat(7), "-".repeat(30));

        int flatRefs = 0;
        int flatPayloads = 0;
        for (Case c : cases) {
            boolean nonAtomic = ValueClass.isFlatArray(
                    ValueClass.newNullRestrictedNonAtomicArray(c.type(), 16, c.init()));
            boolean atomic = ValueClass.isFlatArray(
                    ValueClass.newNullRestrictedAtomicArray(c.type(), 16, c.init()));
            System.out.printf("%-32s  %-9s  %-7s  %s%n", c.name(), nonAtomic, atomic, c.cited());
            if (c.name().endsWith("Ref(long)") && nonAtomic) {
                flatRefs++;
            }
            if (c.name().startsWith("VarnodePayload") || c.name().startsWith("PcodeOpPayload")) {
                if (nonAtomic) {
                    flatPayloads++;
                }
            }
        }

        System.out.println();
        System.out.println("Reading the table:");
        System.out.println("  * The two *Payload rows are the OPTIMISTIC lower bound for Ghidra's real");
        System.out.println("    types — every reference deleted. The real Varnode additionally holds an");
        System.out.println("    Address, and the real PcodeOp a SequenceNumber, a Varnode[] and a Varnode.");
        System.out.println("  * A 2-input PcodeOp is therefore FIVE heap objects: the op, its");
        System.out.println("    SequenceNumber, the input array, and one Varnode per operand.");
        System.out.println();
        System.out.printf("  refs flat: %d/3    payloads flat: %d/2%n", flatRefs, flatPayloads);

        if (flatRefs == 3 && flatPayloads == 0) {
            System.out.println();
            System.out.println("  VERDICT: the facade must ADDRESS the vocabulary, not CARRY it.");
            System.out.println("  Ordinals flatten; Ghidra's payloads do not, even at their lower bound.");
            System.out.println("  This is the same result LaneId/Ordinal/MaskId already rely on, and the");
            System.out.println("  same reason RowRange (16 B) does not flatten. Nothing new is needed —");
            System.out.println("  the existing descriptor discipline already answers it.");
        } else {
            System.out.println();
            System.out.println("  UNEXPECTED: re-measure before designing the facade. The cliff did not");
            System.out.println("  fall where R2/R4 measured it, so those findings need re-checking first.");
        }
    }
}

// Reproducer R6 — is the 8-byte cliff a JDK-version gap, a tunable policy, or Valhalla's
// design? R4 measured WHERE the cliff is; this measures WHY, and whether it can be moved.
//
// Three questions, three arms:
//   (1) Is it a version gap?  No -- R4/R5/R6 all run on JDK 27 EA (27-jep401ea3), the JEP 401
//       early access build. These ARE the Java 27 numbers.
//   (2) Is it a tunable?      No -- forcing all five flattening flags changes nothing (see
//       R6-observed.txt, which pins the flags-on run beside the default run).
//   (3) Is it by design?      Yes. JEP 401, "Reference flattening": "A flattened reference
//       must always be read and written atomically, or it could become corrupted. On common
//       hardware architectures, this limits the size of mutable fields that store flattened
//       references to no more than 64 bits."
//
// The JEP then draws a distinction R4 never tested: "The fields of a value class, by
// contrast, do not have this atomicity limitation, since the fields of value objects can
// never be observed to be mutated." ARRAYS are mutable; value-class FIELDS are not. If that
// distinction is live, a 12-byte value should flatten into a value-class field even though
// it cannot flatten into an array element. This file tests exactly that -- and it is the
// case that matters, because SoA lanes ARE arrays.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -d out R6_WhyEightBytes.java
//   java  --enable-preview -XX:+UnlockDiagnosticVMOptions -XX:+UnlockExperimentalVMOptions \
//     -XX:+UseFieldFlattening -XX:+UseNonAtomicValueFlattening -XX:+PrintFieldLayout \
//     -cp out R6_WhyEightBytes
import jdk.internal.vm.annotation.NullRestricted;

public class R6_WhyEightBytes {

    static value record Quad(byte a, byte b, byte c, byte d) {}                      //  4 B
    static value record Reg12(@NullRestricted Quad q0, @NullRestricted Quad q1,
                              @NullRestricted Quad q2) {}                            // 12 B

    // A 4-byte value as a value-class field — the control. Expected to flatten.
    static value record HoldsQuad(@NullRestricted Quad q) {}
    // A 12-byte value as a value-class field — the JEP's "no atomicity limitation" case.
    static value record HoldsReg12(@NullRestricted Reg12 r) {}

    static Quad q() { return new Quad((byte) 0, (byte) 0, (byte) 0, (byte) 0); }

    public static void main(String[] a) {
        // Touch each so the VM loads and lays out the class; -XX:+PrintFieldLayout reports it.
        System.out.println(new HoldsQuad(q()));
        System.out.println(new HoldsReg12(new Reg12(q(), q(), q())));
        System.out.println("Read the layout dump above: a field reported REGULAR is a "
                + "REFERENCE (not flattened); FLAT n/n is inline payload.");
    }
}

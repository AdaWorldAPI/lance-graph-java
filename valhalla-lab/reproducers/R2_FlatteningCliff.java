// Reproducer R2 — array flattening stops at an 8-byte payload in this build.
// Sweeps payload shapes and asks the VM directly via ValueClass.isFlatArray for all three
// array flavours. Every shape wider than 8 bytes is NOT flattened, in any flavour.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.value=ALL-UNNAMED -d out R2_FlatteningCliff.java
//   java  --enable-preview --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
//     -cp out R2_FlatteningCliff
// Add -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlatArrayLayout to see the VM's own layout log.
import jdk.internal.value.ValueClass;

public class R2_FlatteningCliff {
    static value record P4(int a) {}                     //  4 B
    static value record P8i(int a, int b) {}             //  8 B
    static value record P8l(long a) {}                   //  8 B
    static value record P12(long a, int b) {}            // 12 B
    static value record P16(long a, int b, int c) {}     // 16 B  <- the shape of a real entity
    static value record P16l(long a, long b) {}          // 16 B

    record Case(String name, int payload, Class<?> type, Object init) {}

    public static void main(String[] x) {
        Case[] cases = {
            new Case("P4",   4, P4.class,   new P4(0)),
            new Case("P8i",  8, P8i.class,  new P8i(0, 0)),
            new Case("P8l",  8, P8l.class,  new P8l(0)),
            new Case("P12", 12, P12.class,  new P12(0, 0)),
            new Case("P16", 16, P16.class,  new P16(0, 0, 0)),
            new Case("P16l",16, P16l.class, new P16l(0, 0)),
        };
        System.out.printf("%-6s %-8s %-16s %-16s %s%n",
                "type", "payload", "NR-nonAtomic", "NR-atomic", "nullable-atomic");
        for (Case c : cases) {
            System.out.printf("%-6s %5d B  %-16s %-16s %s%n", c.name(), c.payload(),
                ValueClass.isFlatArray(ValueClass.newNullRestrictedNonAtomicArray(c.type(), 16, c.init())),
                ValueClass.isFlatArray(ValueClass.newNullRestrictedAtomicArray(c.type(), 16, c.init())),
                ValueClass.isFlatArray(ValueClass.newNullableAtomicArray(c.type(), 16)));
        }
    }
}

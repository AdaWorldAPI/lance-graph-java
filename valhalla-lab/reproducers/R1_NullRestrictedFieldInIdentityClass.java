// Reproducer R1 — @NullRestricted on a field of an ORDINARY (identity) class fails at class load.
// javac emits the fields' initialisers AFTER the super() call; the VM demands strict fields be
// assigned BEFORE it. There is no @Strict in this build for javac to key on and no source form
// that expresses the required order, so the combination is unreachable from Java source.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -d out R1_*.java
//   java  --enable-preview \
//     --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -cp out R1_NullRestrictedFieldInIdentityClass
import jdk.internal.vm.annotation.NullRestricted;

public class R1_NullRestrictedFieldInIdentityClass {
    static value record LaneId(int index) {}

    /** An ordinary class that wants a flat LaneId field. Compiles. Does not load. */
    static final class Descriptor {
        @NullRestricted final LaneId lane;
        Descriptor(int i) { this.lane = new LaneId(i); }
    }

    /** The workaround: make the CONTAINER a value class too. Its fields are then strict already. */
    static value class ValueDescriptor {
        @NullRestricted final LaneId lane;
        ValueDescriptor(int i) { this.lane = new LaneId(i); }
    }

    public static void main(String[] a) {
        try {
            System.out.println("identity container: " + new Descriptor(1).lane);
        } catch (Throwable t) {
            System.out.println("identity container FAILED: " + t.getClass().getName());
            System.out.println("  " + String.valueOf(t.getMessage()).lines().findFirst().orElse(""));
        }
        System.out.println("value container:    " + new ValueDescriptor(1).lane + "  (works)");
    }
}

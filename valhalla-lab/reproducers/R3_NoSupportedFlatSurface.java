// Reproducer R3 — the ideal API cannot be expressed in supported Java.
//
// The API wants to say "an array of LaneId, densely packed, no nulls". Three ways to ask, and
// what each actually yields on this build:
//   (1) `new LaneId[n]`                    -> flat, but NULLABLE-flat: it still accepts null and
//                                             pays for a null marker, so it is not the densest
//                                             encoding — and for a payload > 8 B it is not flat
//                                             at all (see R2).
//   (2) `LaneId![] a = new LaneId![n]`      -> DOES NOT PARSE (no null-restricted type syntax), so
//                                             the density in (3) has no supported spelling.
//   (3) ValueClass.newNullRestrictedNonAtomicArray -> densest, but jdk.internal + --add-exports.
//   (4) `List<LaneId>`                      -> generics erase; the flattening is undone at the
//                                             collection boundary regardless of (1)-(3).
//
// So a supported API can get *some* flattening for small payloads and can never get the densest
// form, and any generic container discards it. That is the deficiency: not that flattening is
// missing, but that the API cannot ASK for it.
//
//   javac --enable-preview -source 27 -target 27 \
//     --add-exports java.base/jdk.internal.value=ALL-UNNAMED -d out R3_NoSupportedFlatSurface.java
//   java  --enable-preview --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
//     -cp out R3_NoSupportedFlatSurface
import jdk.internal.value.ValueClass;
import java.util.ArrayList;
import java.util.List;

public class R3_NoSupportedFlatSurface {
    static value record LaneId(int index) {}

    public static void main(String[] a) {
        LaneId[] supported = new LaneId[8];
        System.out.println("(1) new LaneId[8]                     flat=" + ValueClass.isFlatArray(supported)
                + "  accepts null=" + tryNull(supported) + "  (nullable-flat: pays for a null marker)");

        // (2) `LaneId![] x = new LaneId![8];`  <-- uncomment to see: this syntax does not exist.
        System.out.println("(2) LaneId![]                         DOES NOT PARSE — no null-restricted type syntax");

        Object[] internal = ValueClass.newNullRestrictedNonAtomicArray(LaneId.class, 8, new LaneId(0));
        System.out.println("(3) ValueClass.newNullRestricted...   flat=" + ValueClass.isFlatArray(internal)
                + "  accepts null=" + tryNull(internal)
                + "  (jdk.internal, needs --add-exports)");

        List<LaneId> generic = new ArrayList<>();
        for (int i = 0; i < 8; i++) generic.add(new LaneId(i));
        Object[] backing = generic.toArray();
        System.out.println("(4) List<LaneId>.toArray()            flat=" + ValueClass.isFlatArray(backing)
                + "  — generics erase to Object[]; the flattening is undone at the collection boundary");
    }

    private static boolean tryNull(Object[] arr) {
        try { Object keep = arr[0]; arr[0] = null; arr[0] = keep; return true; }
        catch (Throwable t) { return false; }
    }
}

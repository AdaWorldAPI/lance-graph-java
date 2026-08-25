package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Abi;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The DRIFT FALSIFIER for the register groupings served as data (ABI minor 8).
 *
 * <p>The encoding used to be hand-written in three places — a Rust map, {@link Carving}, and
 * {@code abi.md}'s table — with nothing to catch them disagreeing. Now the native side derives it
 * from the contract's own {@code CascadeShape::ROTATIONS} and SERVES it in the manifest, and Java
 * reads what was served. That only helps if something checks the two sides still describe the same
 * set — otherwise "derived" just moves the hand-written copy one file over.
 *
 * <p>So this suite compares MEMBERSHIP both ways, and neither direction is redundant:
 *
 * <ul>
 *   <li>A grouping the library serves that Java cannot name — a variant ADDED upstream. Without
 *       this check it would be silently unreachable: {@link Carving#ofWire} would throw only if a
 *       row happened to resolve to it, i.e. on someone's data, not in the build.
 *   <li>A grouping Java names that the library does not serve — a variant REMOVED upstream, or a
 *       Java constant invented locally. Without this check {@link Carving#wire()} would throw only
 *       when that constant was actually passed.
 * </ul>
 *
 * <p>It also pins the property the wire ORDER rests on: the served table is strictly descending by
 * group count. That is what makes a variant reorder upstream unable to re-map the encoding, and it
 * is a fact about the served bytes here, not a restatement of the Rust rule.
 */
public final class CarvingTableTest {

    private CarvingTableTest() {}

    public static void main(String[] args) {
        System.out.println("CarvingTableTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("CarvingTableTest"));
        }
        Checks c = new Checks("CarvingTableTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        int[] served = Abi.manifest().carvings();

        c.section("the library serves the table (ABI minor 8)");
        // Below minor 8 there is nothing to compare, and a suite that quietly passes when its
        // subject is absent is worse than one that says so.
        if (served.length == 0) {
            c.that("SKIPPED: library minor " + Abi.manifest().abiMinor()
                    + " serves no carving table (minor 8 added it); the pre-minor-8 encoding is in"
                    + " use and nothing here can be falsified", true);
            return;
        }
        c.that("carvings served: " + CarvingTableDescribe.of(served), served.length > 0);

        c.section("membership agrees BOTH ways — an addition or a removal upstream must fail here,"
                + " in the build, not later on someone's data");

        Set<Integer> servedSet = new LinkedHashSet<>();
        for (int packed : served) {
            servedSet.add(packed);
        }
        Set<Integer> javaSet = new LinkedHashSet<>();
        for (Carving carving : Carving.values()) {
            javaSet.add((carving.groups() << 8) | carving.groupBytes());
        }

        for (int packed : servedSet) {
            boolean known = javaSet.contains(packed);
            c.that("served " + (packed >>> 8) + "x" + (packed & 0xFF)
                    + " has a Java constant" + (known ? "" : " — a grouping added upstream that"
                            + " this Java build cannot name"), known);
        }
        for (Carving carving : Carving.values()) {
            int packed = (carving.groups() << 8) | carving.groupBytes();
            boolean present = servedSet.contains(packed);
            c.that(carving + " (" + carving.groups() + "x" + carving.groupBytes()
                    + ") is served" + (present ? "" : " — a Java constant the library does not"
                            + " know, so passing it would fail at the call"), present);
        }

        c.section("every reading covers exactly the 12-byte register");
        for (int packed : served) {
            int groups = packed >>> 8;
            int groupBytes = packed & 0xFF;
            c.that(groups + "x" + groupBytes + " = " + (groups * groupBytes) + " bytes",
                    groups * groupBytes == 12);
        }

        c.section("the served order is STRICTLY descending by group count — the property that stops"
                + " a variant reorder upstream from re-mapping the wire");
        for (int w = 1; w < served.length; w++) {
            int prev = served[w - 1] >>> 8;
            int cur = served[w] >>> 8;
            c.that("wire " + (w - 1) + " (" + prev + " groups) > wire " + w + " (" + cur + ")",
                    prev > cur);
        }

        c.section("the round trip through the served table is the identity");
        for (Carving carving : Carving.values()) {
            Carving back = Carving.ofWire(carving.wire());
            c.that(carving + " -> wire " + carving.wire() + " -> " + back, back == carving);
        }

        c.section("a wire value the table does not name is REJECTED, never aliased onto a"
                + " neighbouring reading");
        boolean threw;
        try {
            Carving.ofWire(served.length);
            threw = false;
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        c.that("wire " + served.length + " (one past the served table) throws", threw);
    }

    /** Local formatter — {@code CarvingTable} is package-private to the main tree, not the test one. */
    private static final class CarvingTableDescribe {
        private CarvingTableDescribe() {}

        static String of(int[] table) {
            StringBuilder b = new StringBuilder("[");
            for (int w = 0; w < table.length; w++) {
                if (w > 0) {
                    b.append(", ");
                }
                b.append(w).append("=>").append(table[w] >>> 8).append('x').append(table[w] & 0xFF);
            }
            return b.append(']').toString();
        }
    }
}

package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Abi;

/**
 * The register-grouping table the loaded library serves, as data.
 *
 * <p>Entry {@code w} is the wire value {@code w}, packed {@code (groups << 8) | groupBytes}. The
 * native side derives it from the contract's own {@code CascadeShape::ROTATIONS} ordered by group
 * count descending, so the SET is the contract's, the ENCODING is derived from it, and neither is
 * hand-written on this side.
 *
 * <p><strong>Package-private, and not a public view of the ABI.</strong> A consumer names a
 * {@link Carving}; the encoding is this package's business.
 */
final class CarvingTable {

    private CarvingTable() {}

    /**
     * The encoding a library predating ABI minor 8 used, restated once, here.
     *
     * <p>Those artifacts serve no table, and a Java build that refused to talk to them would break
     * docs/abi.md §2's additive promise for a purely cosmetic reason — the encoding they use is
     * known, fixed, and shipped. It is written here rather than back in {@link Carving} so that
     * exactly one place in this build carries a literal encoding, and that place says in its name
     * that it is a compatibility shim rather than the current answer.
     */
    private static final int[] PRE_MINOR_8 = {
        (6 << 8) | 2, (4 << 8) | 3, (3 << 8) | 4,
    };

    /** The served table, or the pre-minor-8 encoding when the library serves none. */
    static int[] get() {
        int[] served = Abi.manifest().carvings();
        return served.length == 0 ? PRE_MINOR_8 : served;
    }

    /** True when the table came from the library rather than the compatibility shim. */
    static boolean isServed() {
        return Abi.manifest().carvings().length != 0;
    }

    /** Human-readable, for the failure messages that report a table this build cannot match. */
    static String describe(int[] table) {
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

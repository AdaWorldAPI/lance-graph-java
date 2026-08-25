package com.adaworldapi.lancegraph;

/**
 * Which reading of a facet's 12-byte content-blind register applies.
 *
 * <p>The V3 facet is {@code classid(4) + 12}, and those 12 bytes carry no shape of their own —
 * {@code le-contract.md} §3 carves them three ways, and {@code 6*2 = 4*3 = 3*4 = 12} is the whole
 * reason all three are legal readings of the <em>same</em> bytes. Which one applies is a property
 * of the class, resolved through its {@code ClassView}; it is never a property of the bytes and
 * never inferable from them.
 *
 * <p><strong>This enum MIRRORS the contract's own {@code CascadeShape}</strong>
 * ({@code G6D2}/{@code G4D3}/{@code G3D4}), which carries the grouping algebra and the statement
 * that the reading is class-conditioned. It is a Java-side name for the same three answers, not a
 * second vocabulary — the native side type-aliases straight to the contract type, and the wire
 * values are pinned by GROUP COUNT so a variant reorder upstream cannot silently re-map them.
 *
 * <p><strong>Naming a reading is not authority for one.</strong> {@link RowStore#facetSumAs}
 * applies the carving it is handed, unchecked. {@link RowStore#facetSum} is the verified sibling:
 * it derives the grouping from the population via {@code ClassView::cascade_shape} and refuses a
 * population that does not resolve to one. Prefer it; reach for {@code facetSumAs} only when you
 * deliberately want a reinterpretation the class does not sanction.
 *
 * <p><strong>Why this is a parameter rather than something the sweep looks up per row.</strong>
 * Whatever resolves the reading does so <em>before</em> crossing the membrane, and hands the
 * answer down. Re-resolving it inside the sweep would put the question back in the hot loop —
 * precisely what {@link RowStore#facetSumAs} exists to take out of it.
 */
public enum Carving {
    /** {@code 6 x (u8:u8)} — six little-endian {@code u16} rails. */
    RAILS_6X2(6, 2),
    /** {@code 4 x (u8:u8:u8)} — four little-endian {@code u24} SPO triplets, zero-extended. */
    TRIPLETS_4X3(4, 3),
    /** {@code 3 x (u8:u8:u8:u8)} — three little-endian {@code u32} quads, zero-extended. */
    QUADS_3X4(3, 4);

    private final int groups;
    private final int groupBytes;

    Carving(int groups, int groupBytes) {
        this.groups = groups;
        this.groupBytes = groupBytes;
    }

    /**
     * Groups per register under this reading. {@code groups() * groupBytes() == 12}, always.
     *
     * <p>Declared, not looked up, and that is the one thing here that <em>should</em> be: the
     * arity IS the constant's identity — {@code RAILS_6X2} named anything other than {@code 6 x 2}
     * would be a lie in its own name. What is NOT declared is the wire value (see {@link #wire()}),
     * because that is an encoding rather than a meaning.
     */
    public int groups() {
        return groups;
    }

    /** Bytes per group under this reading. */
    public int groupBytes() {
        return groupBytes;
    }

    /**
     * The ABI wire value (docs/abi.md §14) — <strong>read from the library's manifest table, not
     * declared here</strong>. Package-private on purpose: a consumer names the reading, never its
     * encoding.
     *
     * <p>The encoding used to be hand-written in three places — a Rust map, this enum, and
     * {@code abi.md}'s table — with nothing to catch drift between them. Now the native side
     * derives it from the contract's own {@code CascadeShape::ROTATIONS} (group count descending),
     * serves it in the manifest, and this looks its own arity up in what was served. A reorder or
     * an addition upstream propagates; it cannot silently re-map.
     *
     * @throws IllegalStateException if the loaded library serves no slot for this arity — a
     *     grouping this Java build knows and that library does not, which must fail rather than be
     *     approximated
     */
    int wire() {
        int[] table = CarvingTable.get();
        int packed = (groups << 8) | groupBytes;
        for (int w = 0; w < table.length; w++) {
            if (table[w] == packed) {
                return w;
            }
        }
        throw new IllegalStateException("the loaded library serves no wire value for " + this
                + " (" + groups + "x" + groupBytes + "); its carving table is "
                + CarvingTable.describe(table));
    }

    /**
     * The reading a wire value names — the inverse of {@link #wire()}, used when the native side
     * REPORTS which grouping it resolved.
     *
     * @throws IllegalArgumentException on a value this build cannot name, so an unrecognised
     *     grouping can never be silently read as a known one
     */
    static Carving ofWire(int wire) {
        int[] table = CarvingTable.get();
        if (wire >= 0 && wire < table.length) {
            int packed = table[wire];
            for (Carving c : values()) {
                if (((c.groups << 8) | c.groupBytes) == packed) {
                    return c;
                }
            }
            throw new IllegalArgumentException("the loaded library's wire value " + wire
                    + " names a " + (packed >>> 8) + "x" + (packed & 0xFF) + " grouping, which this"
                    + " Java build has no constant for");
        }
        throw new IllegalArgumentException("no carving for wire value " + wire
                + "; the loaded library's table is " + CarvingTable.describe(table));
    }
}

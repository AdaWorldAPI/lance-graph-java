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
 * <p><strong>This enum names a reading; it does not carry authority for one.</strong>
 * {@link RowStore#facetSumAs} applies the carving it is handed without checking it against the
 * selected rows' classes — see that method's own note. Binding a resolved carving to a population
 * is a named seam awaiting a real ClassView carving resolver.
 *
 * <p><strong>Why this is a parameter rather than something the sweep looks up per row.</strong>
 * Whatever resolves the reading does so <em>before</em> crossing the membrane, and hands the
 * answer down. Re-resolving it inside the sweep would put the question back in the hot loop —
 * precisely what {@link RowStore#facetSumAs} exists to take out of it.
 */
public enum Carving {
    /** {@code 6 x (u8:u8)} — six little-endian {@code u16} rails. */
    RAILS_6X2(0),
    /** {@code 4 x (u8:u8:u8)} — four little-endian {@code u24} SPO triplets, zero-extended. */
    TRIPLETS_4X3(1),
    /** {@code 3 x (u8:u8:u8:u8)} — three little-endian {@code u32} quads, zero-extended. */
    QUADS_3X4(2);

    private final int wire;

    Carving(int wire) {
        this.wire = wire;
    }

    /**
     * The ABI wire value (docs/abi.md §14). Package-private on purpose: a consumer names the
     * reading, never its encoding.
     */
    int wire() {
        return wire;
    }

    /** Groups per register under this reading. {@code groups() * groupBytes() == 12}, always. */
    public int groups() {
        return switch (this) {
            case RAILS_6X2 -> 6;
            case TRIPLETS_4X3 -> 4;
            case QUADS_3X4 -> 3;
        };
    }

    /** Bytes per group under this reading. */
    public int groupBytes() {
        return switch (this) {
            case RAILS_6X2 -> 2;
            case TRIPLETS_4X3 -> 3;
            case QUADS_3X4 -> 4;
        };
    }
}

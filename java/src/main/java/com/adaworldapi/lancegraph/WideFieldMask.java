package com.adaworldapi.lancegraph;

/**
 * Which of a {@link RowStore} row's 32 facet lanes participate in an operation — the Java mirror
 * of the contract currency {@code lance_graph_contract::class_view::WideFieldMask}
 * (D-LGJ-W8 spec §3.1's three-currency table: ClassView / WideFieldMask+FieldMask / Mask).
 *
 * <h2>Small tier only — the ceiling is stated here, not discovered (spec §4 NG12)</h2>
 *
 * <p>The Rust type this mirrors is {@code Small(u64)} until a class needs more than 64 field
 * positions, at which point it promotes once to {@code Wide(Box<[u64]>)}.
 * <strong>This Java type carries the Small tier only</strong> — one {@code long}, no promotion
 * path. A {@link RowStore} has exactly 32 facets today, so the low 32 bits are the only ones this
 * library ever populates or reads through {@link #allFacets()}/{@link #ofFacets}: positions
 * 32..63 are addressable (a {@code long} has room for them, matching the Rust {@code Small}
 * representation bit-for-bit) but no {@link RowStore} shape in this codebase uses them. A future
 * surface past 64 fields needs the Wide-tier promotion on the Rust side, mirrored here as a
 * genuinely new type — this one does not grow into it.
 *
 * <h2>The bridge from the raw facet-match bitset (council S5-2)</h2>
 *
 * <p>{@link #ofMatchBits(int)} is the stated conversion from {@link FacetMatchView#matchesOf}'s
 * raw {@code int} bitset into this typed currency — without it this library would carry three
 * facet-adjacent vocabularies ({@link FacetId}, the raw match bitset, this type) with no declared
 * relationship between any of them.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>Same rules as {@link FacetId} and {@link MaskId}: final, immutable, identity-free, so the
 * same source compiles as a {@code value record} under JEP 401. Two masks are "the same mask"
 * when their bits are equal, never because they are the same object — nothing here relies on
 * reference equality, so flattening changes no observable behaviour.
 */
public record WideFieldMask(long value) {

    private static final int FACET_COUNT = 32;

    /** No facet participates. */
    public static final WideFieldMask EMPTY = new WideFieldMask(0L);

    /** Every one of this store's 32 facets participates — the low 32 bits set, nothing above. */
    public static WideFieldMask allFacets() {
        return new WideFieldMask((1L << FACET_COUNT) - 1);
    }

    /**
     * Build a mask from the populated facet positions.
     *
     * @param positions each must be in {@code 0..31} — the 32-facet domain of a {@link RowStore}
     *                  row. Validated eagerly rather than silently folded to a no-op: the
     *                  contract's own {@code FieldMask::from_positions} ignores an out-of-range
     *                  position rather than panicking, but this facade prefers a loud failure at
     *                  the call site, matching {@link FacetId}'s own convention in this codebase.
     * @throws IllegalArgumentException if any position is outside {@code 0..31}
     */
    public static WideFieldMask ofFacets(int... positions) {
        java.util.Objects.requireNonNull(positions, "positions");
        long bits = 0L;
        for (int p : positions) {
            if (p < 0 || p >= FACET_COUNT) {
                throw new IllegalArgumentException(
                        "facet position must be in 0.." + (FACET_COUNT - 1) + ", was " + p);
            }
            bits |= 1L << p;
        }
        return new WideFieldMask(bits);
    }

    /**
     * The bridge from {@link FacetMatchView#matchesOf(long)}'s raw per-row bitset: bit {@code f}
     * of {@code matchBits} becomes facet position {@code f} of this mask.
     *
     * <p>Zero-extended, never sign-extended — a negative {@code int} still becomes a mask whose
     * only meaningfully-populated positions are its low 32 bits, matching a {@link RowStore}'s
     * own facet count exactly.
     */
    public static WideFieldMask ofMatchBits(int matchBits) {
        return new WideFieldMask(Integer.toUnsignedLong(matchBits));
    }

    /**
     * Is facet position {@code position} populated?
     *
     * <p>Out-of-range positions (negative, or {@code >= 64}) are always {@code false} rather than
     * thrown — mirroring the contract's own {@code FieldMask::has}, which never panics on an
     * out-of-range position.
     */
    public boolean has(int position) {
        return position >= 0 && position < 64 && (value & (1L << position)) != 0;
    }

    /** How many facet positions this mask carries. */
    public int count() {
        return Long.bitCount(value);
    }

    /**
     * The wire form this mask marshals as — the {@code facet_mask} argument {@code lgj_hop}
     * takes (docs/abi.md §13).
     *
     * <p>Package-private: {@link RowStore} is the only caller that needs the raw bits directly;
     * every other consumer works through {@link #has}, {@link #count}, and the factories above.
     * (The record's own canonical accessor, {@link #value()}, is unavoidably public — a Java
     * record's canonical component accessor cannot be declared non-public — but this method is
     * the one the facade's internals actually call, named the way the D-LGJ-W8 spec names it.)
     */
    long bits() {
        return value;
    }

    @Override
    public String toString() {
        return "WideFieldMask[" + count() + " facets, 0x" + Long.toHexString(value) + "]";
    }
}

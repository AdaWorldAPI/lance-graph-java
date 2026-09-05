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
 * library ever populates or reads through {@link #allFacets()} (or, in-package, {@code ofFacets}): positions
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
 * <h2>Why this is a final class and not a record (T2/T3 membrane, ledger L1)</h2>
 *
 * <p>A Java record's canonical constructor is unavoidably public. For this type that constructor
 * takes the raw bits — {@code new WideFieldMask(1L << slot)} would be a public path from a facet
 * SLOT position to a participation mask, and a slot index is a byte position, which never crosses
 * the consumer wall (lance-graph {@code .claude/knowledge/membrane-tiers.md}). So the constructor
 * is private and the ONLY public ways to obtain a mask are {@link #EMPTY} and {@link #allFacets()}
 * — "let the class decide": a consumer names the edge CLASS ({@link RowStore#hop(int, Mask)}
 * takes a classid) and the native side narrows participation to that class's
 * {@code ClassView}-resolved facets. The bit-level factories ({@code ofFacets},
 * {@code ofMatchBits}) are package-private bridges for the inspection surface and the bit-layout
 * tests. {@code ApiSurfaceTest} pins the shape: no public constructor, and every public factory
 * takes zero arguments.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>Same rules as {@link FacetId} and {@link MaskId}: final, immutable, identity-free, so the
 * same source compiles as a {@code value class} under JEP 401 (a value class may keep a private
 * constructor and public factories — the fence above costs nothing under flattening). Two masks
 * are "the same mask" when their bits are equal, never because they are the same object —
 * {@link #equals(Object)} and {@link #hashCode()} are value-based, and nothing here relies on
 * reference equality, so flattening changes no observable behaviour.
 */
public final class WideFieldMask {

    private static final int FACET_COUNT = 32;

    /** The low 32 bits are this store's facets; the record-era {@code value} component, now private. */
    private final long value;

    private WideFieldMask(long value) {
        this.value = value;
    }

    /** No facet participates. */
    public static final WideFieldMask EMPTY = new WideFieldMask(0L);

    /** Every one of this store's 32 facets participates — the low 32 bits set, nothing above. */
    public static WideFieldMask allFacets() {
        return new WideFieldMask((1L << FACET_COUNT) - 1);
    }

    /**
     * Build a mask from raw facet SLOT positions — <strong>package-private by the T2/T3
     * membrane</strong> (lance-graph {@code .claude/knowledge/membrane-tiers.md}, ledger L1).
     *
     * <p>A slot index is a byte position, and byte positions never cross the consumer wall: a
     * consumer names <em>which edge class</em> it is hopping ({@link RowStore#hop(int, Mask)}
     * takes a classid) and the native side narrows participation to that class's
     * {@code ClassView}-resolved facets ({@code edge_participation}). {@link #allFacets()} is
     * therefore the consumer's whole vocabulary — "let the class decide" — never a hand-picked
     * set of slots. This factory remains for the in-package bridge from the inspection surface
     * ({@link #ofMatchBits(int)}) and for tests that pin the bit layout; it was public through
     * ABI minor 10 and is demoted here, not removed (I-LEGACY-API-FEATURE-GATED: the shape is
     * preserved, only its reach changes).
     *
     * @param positions each must be in {@code 0..31} — the 32-facet domain of a {@link RowStore}
     *                  row. Validated eagerly rather than silently folded to a no-op: the
     *                  contract's own {@code FieldMask::from_positions} ignores an out-of-range
     *                  position rather than panicking, but this facade prefers a loud failure at
     *                  the call site, matching {@link FacetId}'s own convention in this codebase.
     * @throws IllegalArgumentException if any position is outside {@code 0..31}
     */
    static WideFieldMask ofFacets(int... positions) {
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
     * <p><strong>Package-private by the T2/T3 membrane</strong> (ledger L1): a raw facet bitset is
     * a set of slot positions, and {@code matchesOf} is an inspection/diagnostics read — feeding
     * its bits back into {@link RowStore#hop} would be Java deciding membership from per-row
     * reads, the exact execution state the mask-native policy forbids. The bridge stays for the
     * in-package tests that pin the bit layout; it is not consumer vocabulary.
     *
     * <p>Zero-extended, never sign-extended — a negative {@code int} still becomes a mask whose
     * only meaningfully-populated positions are its low 32 bits, matching a {@link RowStore}'s
     * own facet count exactly.
     */
    static WideFieldMask ofMatchBits(int matchBits) {
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
     * There is deliberately NO public accessor for the raw bits — the record-era public
     * {@code value()} went with the record (see the class javadoc): reading the bits is a slot
     * read, and slots do not cross the consumer wall in either direction.
     */
    long bits() {
        return value;
    }

    /** Value-based: two masks are equal iff their bits are equal (identity is irrelevant). */
    @Override
    public boolean equals(Object o) {
        return o instanceof WideFieldMask other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "WideFieldMask[" + count() + " facets, 0x" + Long.toHexString(value) + "]";
    }
}

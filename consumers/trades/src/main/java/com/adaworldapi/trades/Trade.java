package com.adaworldapi.trades;

import com.adaworldapi.lancegraph.Field;
import com.adaworldapi.lancegraph.I32Field;
import com.adaworldapi.lancegraph.LaneId;
import com.adaworldapi.lancegraph.Ordinal;
import com.adaworldapi.lancegraph.Pattern;
import com.adaworldapi.lancegraph.U32Field;

import java.util.List;

/**
 * The domain schema for the "One Billion Objects. Zero Objects." poster.
 *
 * <h2>This is a schema, not an entity</h2>
 *
 * <p>There is no such thing as a {@code Trade} instance, on purpose, and the constructor enforces
 * that rather than merely discouraging it (see below). The thesis this consumer example exists to
 * demonstrate: <strong>a million logical trades are one native lane set, not a million Java
 * objects.</strong> {@code Trade} is the vocabulary a fluent query is written against —
 * {@code Trade.VENUE.eq(Trade.XETRA)} — never a bag of fields a row gets copied into.
 *
 * <p>This class is written the same generated shape as {@link Pattern}: constants only, no logic,
 * names taken from the schema, lane indices stated once. It is a domain-named sibling of
 * {@code Pattern}, not a replacement for it — see the field notes below for exactly how the two
 * relate.
 *
 * <pre>{@code
 * try (var world = World.open(1_000_000, World.DEFAULT_SEED).source()) {
 *     long xetraCount = world.view()
 *             .where(Trade.VENUE.eq(Trade.XETRA))
 *             .where(Trade.PRICE.gt(100))
 *             .count();
 * }
 * }</pre>
 *
 * <h2>Field mapping — honest about what the fixture actually offers</h2>
 *
 * <p>The generated fixture ({@link com.adaworldapi.lancegraph.NativePattern}) has exactly three
 * lanes: an id, an unsigned 32-bit class tag ({@code 0..15}), and a signed 32-bit value
 * ({@code -150..361}). This schema binds the domain names a trading example would actually use
 * onto those same two data lanes — {@link #VENUE} is the class lane, {@link #PRICE} is the value
 * lane — rather than inventing a fourth lane the substrate does not have.
 *
 * <ul>
 *   <li>{@link #VENUE} — {@link Pattern#CLASS}'s lane ({@code lane 1}), read as a trading venue
 *       identifier. The fixture generates 16 distinct values ({@code 0..15}); {@link #XETRA} and
 *       {@link #NASDAQ} are two of them, chosen so a demo predicate selects a measured, known
 *       fraction of the rows rather than an arbitrary one.
 *   <li>{@link #PRICE} — {@link Pattern#VALUE}'s lane ({@code lane 2}), read as a price tick. The
 *       fixture's signed range ({@code -150..361}) does not correspond to a real price in any
 *       currency; the demo's fiction is that these are cents-scale ticks, deliberately unrealistic
 *       (a real price is never negative) so that a signed-comparison bug shows up rather than
 *       agreeing with an unsigned one by accident — see {@link I32Field#gt(int)}.
 * </ul>
 *
 * <h2>QUANTITY is deliberately absent</h2>
 *
 * <p>The poster's domain fiction has a third field, quantity. It is <strong>not fabricated
 * here</strong>: the current flat three-lane fixture has no third numeric lane to bind it to, and
 * inventing one would mean this schema quietly grows its own private data rather than projecting
 * the real substrate. A quantity field arrives with the multi-lane {@code ClassView} facet slice
 * (32 facets, {@code W6} in the substrate plan) — at that point {@code Trade} gains a
 * {@code QUANTITY} constant bound to a real lane, and this class's shape does not otherwise change.
 * Until then, honesty about what exists beats completeness of the poster.
 *
 * <h2>Binds onto the flat pattern, not the row store</h2>
 *
 * <p>{@link World#open} opens a {@link com.adaworldapi.lancegraph.NativePattern} and returns its
 * {@link com.adaworldapi.lancegraph.View} — the flat, three-lane substrate {@link Pattern} already
 * uses, not the 32-facet {@code RowStore} the substrate wave shipped alongside it. That is where
 * the {@code gt}/{@code eq} lazy {@code View} machinery lives today. A facet-row binding (one
 * {@code Trade} row addressed as a lane slice of a {@code RowStore} facet, via a future
 * {@code ClassView}) is future work, not a gap in this example — see the {@code QUANTITY} note
 * above for the same boundary from the data side.
 */
public final class Trade {

    /**
     * Never constructed. A {@code Trade} instance would be exactly the per-entity object this
     * example exists to prove unnecessary — a million of them is the anti-thesis, not a
     * convenience. Thrown even through reflection with {@code setAccessible(true)}, so the
     * guarantee is "impossible," not merely "discouraged by visibility."
     */
    private Trade() {
        throw new AssertionError(
                "a Trade is never materialized — 1,000,000 logical trades are one native lane set,"
                        + " not 1,000,000 objects. There is no Trade instance to construct.");
    }

    /** Venue identifier — {@link Pattern#CLASS}'s lane, read under a domain name. */
    public static final U32Field VENUE =
            new U32Field("venue", LaneId.of(1), Ordinal.of(0));

    /** Price tick — {@link Pattern#VALUE}'s lane, read under a domain name. See the class doc for
     * why this is a signed, fictional cents-scale tick rather than a real currency amount. */
    public static final I32Field PRICE =
            new I32Field("price", LaneId.of(2), Ordinal.of(1));

    /**
     * A venue id used throughout this example's predicates. {@code 7} is the fixture class the
     * rest of this repo's test corpus already exercises, so {@code Trade.VENUE.eq(Trade.XETRA)}
     * selects the same, already-measured ~6% (1-in-16) fraction of rows as {@code
     * Pattern.CLASS.eq(7)} does elsewhere.
     */
    public static final int XETRA = 7;

    /** A second, distinct venue id, for predicates that need two venues to compare or exclude. */
    public static final int NASDAQ = 3;

    /** Every field this schema defines, in schema order. See the class doc for why there is no
     * {@code QUANTITY} entry yet. */
    public static final List<Field> FIELDS = List.of(VENUE, PRICE);
}

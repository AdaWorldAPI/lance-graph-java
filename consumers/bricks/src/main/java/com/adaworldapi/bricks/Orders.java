package com.adaworldapi.bricks;

import com.adaworldapi.lancegraph.Field;
import com.adaworldapi.lancegraph.I32Field;
import com.adaworldapi.lancegraph.LaneId;
import com.adaworldapi.lancegraph.Ordinal;
import com.adaworldapi.lancegraph.Pattern;
import com.adaworldapi.lancegraph.U32Field;

import java.util.List;

/**
 * The domain schema for the OGAR-Bricks poster: security as a mask composed <em>before</em>
 * execution, never a post-filter over already-fetched rows.
 *
 * <h2>This is a schema, not an entity</h2>
 *
 * <p>There is no such thing as an {@code Orders} instance, on purpose — see the constructor. This
 * class is written the same generated shape as {@link Pattern} and {@code
 * com.adaworldapi.trades.Trade}: constants only, no logic, names taken from the schema, lane
 * indices stated once.
 *
 * <h2>Field mapping — honest about what the fixture actually offers</h2>
 *
 * <p>The generated fixture ({@link com.adaworldapi.lancegraph.NativePattern}) has exactly two
 * data lanes beyond identity: an unsigned 32-bit class tag ({@code 0..15}) and a signed 32-bit
 * value. This schema binds order-domain names onto those same two lanes — {@link #REGION} is the
 * class lane, {@link #REVENUE} is the value lane — rather than inventing a lane the substrate does
 * not have.
 *
 * <h2>YEAR is deliberately absent</h2>
 *
 * <p>The poster's domain fiction implies a time dimension (orders <em>by year</em>, revenue
 * <em>trending</em>). It is <strong>not fabricated here</strong>: the current flat two-lane fixture
 * has no third numeric lane to bind it to. Exactly the same boundary {@code Trade} draws around its
 * absent {@code QUANTITY} field — a multi-lane {@code ClassView} facet slice ({@code W6} in the
 * substrate plan) is what a real {@code YEAR} lane would bind onto; until then, honesty about what
 * exists beats completeness of the poster.
 */
public final class Orders {

    /**
     * Never constructed. See {@code com.adaworldapi.trades.Trade}'s constructor for the same
     * guarantee stated in full: this class is vocabulary, not a row template.
     */
    private Orders() {
        throw new AssertionError(
                "an Orders instance is never materialized — orders are addressed as a native lane"
                        + " set through Bricks/BricksQuery, never hydrated one-by-one. There is no"
                        + " Orders instance to construct.");
    }

    /**
     * Region identifier — {@link Pattern#CLASS}'s lane ({@code lane 1}), read under a domain name.
     * Fixture values are {@code 0..15}.
     */
    public static final U32Field REGION =
            new U32Field("region", LaneId.of(1), Ordinal.of(0));

    /**
     * Revenue — {@link Pattern#VALUE}'s lane ({@code lane 2}), read under a domain name. Signed,
     * matching the fixture's underlying value lane; see {@link I32Field} for why only the
     * comparisons the membrane implements are offered.
     */
    public static final I32Field REVENUE =
            new I32Field("revenue", LaneId.of(2), Ordinal.of(1));

    /**
     * The region id used by {@link Role#EU_ONLY} to build its authorization mask. One of the
     * fixture's 16 distinct {@link #REGION} values.
     */
    public static final int EU = 7;

    /** A second, distinct region id, for predicates that need to compare or exclude by region. */
    public static final int APAC = 3;

    /** Every field this schema defines, in schema order. See the class doc for why there is no
     * {@code YEAR} entry yet. */
    public static final List<Field> FIELDS = List.of(REGION, REVENUE);
}

package com.adaworldapi.bricks;

import com.adaworldapi.lancegraph.NativePattern;

/**
 * Entry point for the OGAR-Bricks poster example: security as a mask composed <em>before</em>
 * execution, never a post-filter over already-fetched data.
 *
 * <h2>Zero membrane growth — the iron rule</h2>
 *
 * <p>This package adds no ABI symbol, no native call, no query engine, and no row-shaped public
 * type. It consumes {@code com.adaworldapi.lancegraph} exactly as a third-party developer would:
 * {@link NativePattern#open} to obtain rows, {@link NativePattern#view()} (via {@link
 * BricksSession#query()}) to obtain the lazy description, {@link
 * com.adaworldapi.lancegraph.View#where} (via {@link BricksQuery#where} and {@link
 * BricksQuery#authorize}) to narrow it.
 *
 * <pre>{@code
 * try (var session = Bricks.open(1_000_000, Bricks.DEFAULT_SEED)) {
 *     long euOrderCount = session.query()
 *             .authorize(Role.EU_ONLY)
 *             .where(Orders.REVENUE.gt(0))
 *             .count();
 * }
 * }</pre>
 */
public final class Bricks {

    private Bricks() {}

    /** The default seed, re-exported from {@link NativePattern#DEFAULT_SEED}. */
    public static final long DEFAULT_SEED = NativePattern.DEFAULT_SEED;

    /**
     * Open {@code nRows} orders generated deterministically from {@code seed}.
     *
     * @throws IllegalArgumentException if {@code nRows} is negative
     */
    public static BricksSession open(long nRows, long seed) {
        return new BricksSession(NativePattern.open(nRows, seed));
    }
}

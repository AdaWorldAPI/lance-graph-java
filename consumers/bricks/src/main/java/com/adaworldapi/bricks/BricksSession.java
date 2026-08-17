package com.adaworldapi.bricks;

import com.adaworldapi.lancegraph.NativePattern;

/**
 * A set of order rows held natively, opened once and closed once — the RBAC-poster sibling of
 * {@code com.adaworldapi.trades.World}.
 *
 * <p>Owns exactly one {@link NativePattern}. {@link #query()} returns a fresh, <strong>unauthorized
 * </strong> {@link BricksQuery} over that pattern's rows every time it is called — see {@link
 * BricksQuery} for why every chain must call {@code authorize(...)} before any terminal operation,
 * with no exceptions for {@link Role#GLOBAL}.
 *
 * <pre>{@code
 * try (var session = Bricks.open(1_000_000, Bricks.DEFAULT_SEED)) {
 *     long euRevenue = session.query()
 *             .authorize(Role.EU_ONLY)
 *             .sum(Orders.REVENUE);
 * }
 * }</pre>
 */
public final class BricksSession implements AutoCloseable {

    private final NativePattern pattern;

    BricksSession(NativePattern pattern) {
        this.pattern = pattern;
    }

    /**
     * A fresh, unauthorized query over every row in this session.
     *
     * <p>Crosses the membrane zero times to build, exactly as {@link NativePattern#view()} does not.
     * The returned query throws {@link UnauthorizedQueryException} from any terminal operation until
     * {@link BricksQuery#authorize(Role)} is called on it.
     */
    public BricksQuery query() {
        return new BricksQuery(pattern.view(), false);
    }

    /**
     * Release the native storage.
     *
     * <p>Delegates directly to {@link NativePattern#close()} — same double-close semantics: a second
     * call throws {@link com.adaworldapi.lancegraph.ClosedResourceException} rather than being a
     * silent no-op, and every {@link BricksQuery} derived from this session's rows stops working
     * once this returns.
     */
    @Override
    public void close() {
        pattern.close();
    }
}

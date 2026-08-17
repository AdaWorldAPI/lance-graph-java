package com.adaworldapi.bricks;

/**
 * Thrown by a {@link BricksQuery} terminal operation ({@link BricksQuery#count()}, {@link
 * BricksQuery#sum}, {@link BricksQuery#sumBy}) when {@link BricksQuery#authorize(Role)} was never
 * called on that chain.
 *
 * <h2>Fail-closed, not default-allow</h2>
 *
 * <p>This is the workspace's own RBAC doctrine, restated as a Java exception rather than left as a
 * convention: <em>"a missing role mask never falls back to emit everything."</em> A {@link
 * BricksQuery} that has not been authorized is not treated as globally readable — it is treated as
 * a programming error, thrown before any native crossing happens. There is no default-allow path
 * anywhere in this package; the only way past this exception is an explicit {@link
 * BricksQuery#authorize(Role)} call, even if that role is {@link Role#GLOBAL}.
 */
public final class UnauthorizedQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnauthorizedQueryException(String message) {
        super(message);
    }
}

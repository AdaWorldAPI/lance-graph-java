package com.adaworldapi.trades;

import com.adaworldapi.lancegraph.NativePattern;
import com.adaworldapi.lancegraph.View;

/**
 * The entry point for the "One Billion Objects. Zero Objects." poster example.
 *
 * <h2>The claim</h2>
 *
 * <p>A million (or a billion, given enough rows in the fixture) logical trades never become that
 * many Java objects. {@link #open} opens one native lane set and hands back one lazy {@link View}
 * — the same fluent {@code where}/{@code count}/{@code sumOf} machinery {@link
 * com.adaworldapi.lancegraph.NativePattern} already provides, addressed through domain names
 * ({@link Trade#VENUE}, {@link Trade#PRICE}) instead of schema-generic ones ({@code
 * Pattern.CLASS}, {@code Pattern.VALUE}).
 *
 * <pre>{@code
 * try (var pattern = NativePattern.open(1_000_000, World.DEFAULT_SEED)) {
 *     long n = World.viewOf(pattern)
 *             .where(Trade.VENUE.eq(Trade.XETRA))
 *             .where(Trade.PRICE.gt(100))
 *             .count();
 * }
 * }</pre>
 *
 * <h2>Zero membrane growth — the iron rule</h2>
 *
 * <p>This class adds no ABI symbol, no native call, no query engine. It consumes {@code
 * com.adaworldapi.lancegraph} exactly as a third-party developer would: {@link NativePattern#open}
 * to obtain rows, {@link NativePattern#view()} to obtain the lazy description, {@link View#where}
 * to narrow it. If a future domain example needs a capability the public API does not expose, the
 * fix is a change to the substrate plan, never a shortcut taken here.
 *
 * <h2>Lifetime, stated honestly</h2>
 *
 * <p>A {@link View} does not own the rows it describes — its parent {@link NativePattern} does,
 * and the parent must outlive every view (and every terminal operation) derived from it, exactly
 * as {@link NativePattern}'s own class documentation specifies. {@link View#source()} is how a
 * caller gets back to the resource that must eventually be closed.
 *
 * <p>{@link #open(long, long)} returns a {@code View} rather than the {@code NativePattern}
 * itself, matching the wave's domain-facade shape ("returns the existing lazy {@code View}
 * machinery under domain names") — but that means the resource is reachable only through {@link
 * View#source()}, which is why every example in this class's Javadoc closes via {@code
 * World.open(...).source().close()} (or, more idiomatically, by holding the pattern in the
 * try-with-resources and deriving the view from it, as shown above). A caller who wants the
 * cleaner try-with-resources shape should open the {@link NativePattern} directly and call {@link
 * NativePattern#view()} — {@link #open} exists for the one-line poster snippet, not to hide the
 * resource.
 */
public final class World {

    private World() {}

    /** The default seed, re-exported from {@link NativePattern#DEFAULT_SEED} so a caller need not
     * import the core package just to name it. */
    public static final long DEFAULT_SEED = NativePattern.DEFAULT_SEED;

    /**
     * Open {@code nRows} trades generated deterministically from {@link #DEFAULT_SEED} and return
     * their lazy view.
     *
     * <p>See the class documentation for the lifetime note: the returned view's underlying
     * resource is reachable, and must eventually be closed, via {@link View#source()}.
     */
    public static View open(long nRows) {
        return open(nRows, DEFAULT_SEED);
    }

    /**
     * Open {@code nRows} trades generated deterministically from {@code seed} and return their
     * lazy view.
     *
     * <p>Building and narrowing the returned view crosses the native membrane zero times, exactly
     * as {@link View}'s own documentation specifies — only a terminal operation ({@code count()},
     * {@code sumOf(...)}, {@code select()}) executes.
     *
     * @throws IllegalArgumentException if {@code nRows} is negative
     */
    public static View open(long nRows, long seed) {
        return NativePattern.open(nRows, seed).view();
    }
}

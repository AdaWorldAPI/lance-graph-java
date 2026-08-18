package com.adaworldapi.graph;

import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.FacetMatchView;
import com.adaworldapi.lancegraph.RowStore;

import java.util.Objects;
import java.util.TreeSet;

/**
 * An immutable, fluent description of a set of row indices reached by traversing a {@link RowStore}
 * opened with {@link RowStore#openWithEdges} — one hop at a time.
 *
 * <h2>Currency: a plain row-index set, not a native {@code Mask}</h2>
 *
 * <p>{@link com.adaworldapi.lancegraph.Mask} has no public constructor from an arbitrary Java-side
 * collection of row indices in this codebase today, so a traversal frontier here is a plain sorted
 * {@code long[]} — a Java-heap value, not a native resource. This is a deliberate simplification for
 * this consumer example, not a workaround: it still satisfies zero-serialization (a {@code long[]}
 * living on the Java heap is neither {@code byte[]} nor JSON, and nothing crosses the membrane to
 * build, narrow, or reduce it), and it still gives crossings proportional to hops rather than to
 * rows — {@link #hop(int)} pays exactly <strong>one</strong> native crossing
 * ({@link RowStore#facetMatches}) for the whole hop, regardless of how many rows are in the current
 * frontier, and {@link #from(long...)}, {@link #minus(long...)}, and {@link #count()} pay zero —
 * they are plain Java-heap set operations over an already-fetched result.
 *
 * <h2>Immutable, chainable steps</h2>
 *
 * <p>{@link #from(long...)}, {@link #hop(int)}, and {@link #minus(long...)} each return a
 * <strong>new</strong> {@code Graph} holding the next frontier; none of them mutate the receiver.
 * This mirrors {@code com.adaworldapi.bricks.BricksQuery}'s own chaining shape (each step wraps a
 * new, immutable value around the previous one) rather than {@code com.adaworldapi.lancegraph.View}'s
 * lazy-until-terminal-operation shape — there is no laziness to preserve here, since every step
 * (other than a hop) is already a zero-cost Java-heap operation with nothing to defer.
 *
 * <pre>{@code
 * try (var store = RowStore.openWithEdges(2000, 0xF00D_CAFEL, Edge.KNOWS, 0x0L, 25)) {
 *     long twoHopCount = Graph.open(store)
 *             .from(5, 42, 79, 116, 153, 190, 227, 264, 301, 338)
 *             .hop(Edge.KNOWS)
 *             .hop(Edge.KNOWS)
 *             .count();
 * }
 * }</pre>
 *
 * <h2>Ownership — the store is the caller's, never this class's</h2>
 *
 * <p>{@link #open(RowStore)} wraps an already-open {@link RowStore}; a {@code Graph} never opens or
 * closes one. This differs from {@code com.adaworldapi.bricks.BricksSession}, which does own its
 * {@code NativePattern} — a graph traversal's caller typically wants to keep hopping, re-seed, and
 * inspect the same store across many independent {@code Graph} chains, so ownership stays with
 * whichever code opened the store, exactly as {@code com.adaworldapi.lancegraph.View} does not own
 * the {@code NativePattern} it describes. {@link #close()} is therefore a no-op: a {@code Graph}
 * holds no resource of its own to release, only a reference to one it does not own.
 *
 * <h2>The decode convention a hop applies</h2>
 *
 * <p>{@link RowStore#facetMatches} reports, per row, a 32-bit bitset of which facets carry a given
 * classid. Not every facet that carries {@link Edge#KNOWS}'s classid is a real structured edge —
 * see {@link RowStore#payloadHi32At}'s documentation: a real edge target has high payload bits
 * exactly {@code 0}; ordinary noise that happens to share the classid does not, overwhelmingly. A
 * hop therefore reads {@link RowStore#payloadHi32At} for every matched facet and only follows the
 * ones where it is {@code 0}, then reads the target row from {@link RowStore#payloadLow64At}.
 */
public final class Graph implements AutoCloseable {

    private static final long[] EMPTY = new long[0];

    private final RowStore store;
    private final long[] rows;

    private Graph(RowStore store, long[] rows) {
        this.store = store;
        this.rows = rows;
    }

    /**
     * Wrap an already-open {@code store} with an empty frontier. Call {@link #from(long...)} to seed
     * it before hopping.
     *
     * <p>Crosses the membrane zero times: this method reads nothing from {@code store} at all.
     */
    public static Graph open(RowStore store) {
        Objects.requireNonNull(store, "store");
        return new Graph(store, EMPTY);
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is exactly {@code seedRows} —
     * deduplicated and sorted, replacing whatever frontier this chain had before.
     *
     * <p>Crosses the membrane zero times. Row-index validity is not checked here; an out-of-range
     * row surfaces as {@link IndexOutOfBoundsException} the first time a subsequent {@link
     * #hop(int)} tries to read it, from {@link RowStore#classidAt} and friends — the same place
     * every other row-index bounds check in this codebase already lives, so this method does not
     * duplicate it.
     */
    public Graph from(long... seedRows) {
        Objects.requireNonNull(seedRows, "seedRows");
        TreeSet<Long> next = new TreeSet<>();
        for (long row : seedRows) {
            next.add(row);
        }
        return new Graph(store, toArray(next));
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is every row reached from the current
     * frontier by exactly one {@code edgeClassid} edge — deduplicated: a target reached from two
     * different source rows in the current frontier counts once.
     *
     * <p>Pays exactly <strong>one</strong> native crossing ({@link RowStore#facetMatches}) for the
     * whole hop — called once, before iterating the frontier, never once per row. Every row in the
     * current frontier is then checked against that single already-fetched bitset ({@link
     * FacetMatchView#matchesOf}).
     *
     * <p><strong>Measured, not merely designed:</strong> the very first row-level read against a
     * given {@code store} — the first call to {@link RowStore#payloadHi32At} or {@link
     * RowStore#payloadLow64At} anywhere in that store's life, which the first hop on a fresh store
     * always triggers — pays one additional, one-time crossing of its own ({@code
     * RowStore}'s lazily-resolved raw lane-0 window; see {@code RowStore#rawLane()}'s doc). That
     * cost is paid once per {@code RowStore}, not once per hop or once per {@code Graph}: every
     * {@code Graph} derived from the same {@link #open(RowStore)} call shares the same underlying
     * store, so the SECOND and every later hop on that store cost exactly the documented one
     * crossing, steady-state. Measured directly: hop 1 costs 2, hops 2/3/4 each cost exactly 1.
     *
     * <p>An empty current frontier short-circuits before paying even the {@code facetMatches}
     * crossing — there is nothing to hop from, so nothing is fetched.
     */
    public Graph hop(int edgeClassid) {
        if (rows.length == 0) {
            return new Graph(store, EMPTY);
        }
        FacetMatchView view = store.facetMatches(edgeClassid);
        TreeSet<Long> next = new TreeSet<>();
        for (long row : rows) {
            int bits = view.matchesOf(row);
            while (bits != 0) {
                int facetIndex = Integer.numberOfTrailingZeros(bits);
                bits &= bits - 1; // clear the lowest set bit
                FacetId facet = FacetId.of(facetIndex);
                if (store.payloadHi32At(row, facet) == 0) {
                    next.add(store.payloadLow64At(row, facet));
                }
            }
        }
        return new Graph(store, toArray(next));
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is the current frontier with {@code
     * excludedRows} removed — plain set difference, no membrane crossing.
     */
    public Graph minus(long... excludedRows) {
        Objects.requireNonNull(excludedRows, "excludedRows");
        TreeSet<Long> next = new TreeSet<>();
        for (long row : rows) {
            next.add(row);
        }
        for (long excluded : excludedRows) {
            next.remove(excluded);
        }
        return new Graph(store, toArray(next));
    }

    /** How many rows are in the current frontier. Pure Java, zero crossings. */
    public long count() {
        return rows.length;
    }

    /**
     * The current frontier's row indices, sorted ascending, deduplicated. Returns a defensive copy —
     * mutating the result never affects this {@code Graph}.
     */
    public long[] rows() {
        return rows.clone();
    }

    /**
     * A no-op. This {@code Graph} does not own {@code store} — see the class documentation's
     * ownership section — so there is nothing here to release. Present so a {@code Graph} chain can
     * be used in a try-with-resources block alongside the store it wraps without special-casing it,
     * and so a future version that does need to release something has a place to put it without an
     * API change.
     */
    @Override
    public void close() {
        // Deliberately empty -- see the class doc's "Ownership" section.
    }

    private static long[] toArray(TreeSet<Long> set) {
        if (set.isEmpty()) {
            return EMPTY;
        }
        long[] out = new long[set.size()];
        int i = 0;
        for (long row : set) {
            out[i++] = row;
        }
        return out;
    }

    @Override
    public String toString() {
        return "Graph[" + rows.length + " rows]";
    }
}

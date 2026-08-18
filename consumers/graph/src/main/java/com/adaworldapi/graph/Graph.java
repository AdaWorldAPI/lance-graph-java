package com.adaworldapi.graph;

import com.adaworldapi.lancegraph.Mask;
import com.adaworldapi.lancegraph.RowStore;
import com.adaworldapi.lancegraph.WideFieldMask;

import java.util.Objects;

/**
 * An immutable, fluent description of a set of row indices reached by traversing a {@link RowStore}
 * opened with {@link RowStore#openWithEdges} — one hop at a time.
 *
 * <h2>Currency: a native {@link Mask}, not a Java-heap row-index array</h2>
 *
 * <p>A traversal frontier here is a native {@link Mask} — packed bits behind a generation-checked
 * handle, exactly the currency {@code where}/{@code hop}/{@code authorize}/{@code navigate} are
 * required to use everywhere in this repo (see the root {@code CLAUDE.md}'s "mask-native
 * invariant"). This class previously carried its frontier as a plain {@code long[]}/{@code
 * TreeSet<Long>}, decoding each matched row's payload directly in Java; that implementation's own
 * javadoc called the choice "a deliberate simplification … not a workaround." {@code EPIPHANIES.md}'s
 * {@code E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1} corrects that: the old shape was a valid
 * <strong>scalar reference oracle</strong> — preserved verbatim as {@code GraphHopTest}'s two
 * independent BFS transcriptions — but never a valid target for how this class's own execution
 * engine should carry a population. Java-surface convenience must never dictate substrate
 * representation. {@link #hop(int)} and {@link #hop(int, WideFieldMask)} now delegate entirely to
 * {@link RowStore#hop(int, Mask)} / {@link RowStore#hop(int, WideFieldMask, Mask)}, which perform
 * the classid match, the payload decode, and the scatter into the destination {@link Mask}
 * natively, in one crossing — no row is ever read into Java to decide whether a hop follows it.
 *
 * <h2>The three currencies, briefly</h2>
 *
 * <p>{@link Mask} — which rows. {@link WideFieldMask} — which of a row's 32 facets participate in a
 * hop (the default, {@link #hop(int)}, is all of them). {@code ClassView} — what a facet's
 * classid/payload combination <em>means</em>; consulted natively via {@code lgj-abi}'s late-bound
 * provider (a deterministic fixture today; a real ontology/cache provider is a named seam, not this
 * class's concern). See the root {@code CLAUDE.md} for the full table.
 *
 * <pre>{@code
 * try (var store = RowStore.openWithEdges(2000, 0xF00D_CAFEL, Edge.KNOWS, 0x0L, 25)) {
 *     // the flagship: zero row-ids anywhere, a predicate-born seed straight into a hop chain.
 *     long twoHopCount = Graph.open(store)
 *             .from(store.maskOfFacetClass(FacetId.of(7), 5))
 *             .hop(Edge.KNOWS)
 *             .hop(Edge.KNOWS)
 *             .count();
 * }
 * }</pre>
 *
 * <p>{@link #from(long...)} remains the ONE documented external-selection import on this class —
 * for when row ids genuinely come from outside (a caller's own list, a prior fixture) — and it
 * delegates to {@link RowStore#importRows}, the root {@code CLAUDE.md}'s named escape hatch. It is
 * never the internal currency of a hop chain; {@link #from(Mask)} is. The old {@code
 * minus(long...)} overload is removed: a caller with row ids to exclude composes {@code
 * .minus(store.importRows(rows))}, so the import stays lexically visible at the call site rather
 * than hiding inside this class.
 *
 * <h2>Immutable, chainable steps</h2>
 *
 * <p>{@link #from(long...)}, {@link #from(Mask)}, {@link #hop(int)}, {@link #hop(int,
 * WideFieldMask)}, {@link #minus(Graph)}, and {@link #minus(Mask)} each return a
 * <strong>new</strong> {@code Graph} holding the next frontier; none of them mutate the receiver.
 *
 * <h2>Ownership — the store is the caller's, never this class's</h2>
 *
 * <p>{@link #open(RowStore)} wraps an already-open {@link RowStore}; a {@code Graph} never opens or
 * closes it, and {@link #open} itself crosses the membrane zero times — its frontier starts unset,
 * so there is nothing to fetch until a {@link #from(long...)} or {@link #from(Mask)} call gives it
 * one.
 *
 * <h2>Lifecycle — this IS something to release now</h2>
 *
 * <p>Unlike the old {@code long[]}-frontier implementation, whose {@link #close()} was a permanent
 * no-op (a Java-heap array owns nothing native), a {@code Graph} now owns exactly one native
 * resource — its own frontier {@link Mask} — and {@link #close()} releases it. It does
 * <strong>not</strong> reach back and close any ancestor {@code Graph}'s frontier in the same chain:
 * each step in a fluent chain owns only the {@link Mask} it itself was constructed with.
 *
 * <p>A caller who wants to release memory sooner than "when the store closes" holds and closes an
 * intermediate {@code Graph} explicitly. A caller who does not is not leaking in any sense this
 * repo treats as an error: words-proportional retention — at most {@code n_rows/8} bytes (one bit
 * per row, matching {@link Mask}'s own documented cost — "selecting 64,000 of 64,000 entities
 * costs 8,000 bytes") per un-closed step — until the store closes IS the sanctioned currency (root
 * {@code CLAUDE.md}'s "Forbidden as normal execution state" list is about row-id collections, not
 * about this).
 *
 * <p>{@link #from(Mask)} takes logical ownership of the {@link Mask} it is given: do not separately
 * close that {@link Mask} afterward — closing the {@code Graph} it became the frontier of releases
 * it. {@link #minus(Mask)}'s argument is different: it is read once to compute a brand-new result
 * and is never retained, so its lifecycle stays entirely the caller's.
 */
public final class Graph implements AutoCloseable {

    private final RowStore store;

    // null means "no frontier set yet" (see #open) -- never an empty long[] or an empty-but-real
    // Mask paid for just to have something non-null to hold. Every other state is a real, native
    // Mask -- there is no Java-heap row-id representation anywhere in this class.
    private final Mask frontier;

    private Graph(RowStore store, Mask frontier) {
        this.store = store;
        this.frontier = frontier;
    }

    /**
     * Wrap an already-open {@code store} with no frontier set yet. Call {@link #from(long...)} or
     * {@link #from(Mask)} to seed it before hopping.
     *
     * <p>Crosses the membrane zero times: this method reads nothing from {@code store} at all.
     */
    public static Graph open(RowStore store) {
        Objects.requireNonNull(store, "store");
        return new Graph(store, null);
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is exactly {@code seedRows} — the
     * ONE documented external-selection import on this class (root {@code CLAUDE.md}'s "named
     * exceptions": external row ids in). Delegates to {@link RowStore#importRows}; it is never the
     * internal currency of a hop chain — see {@link #from(Mask)} for that.
     *
     * <p>Row-index validity is not checked here; an out-of-range row surfaces as an exception the
     * first time a subsequent {@link #hop(int)} tries to read it natively.
     */
    public Graph from(long... seedRows) {
        Objects.requireNonNull(seedRows, "seedRows");
        return new Graph(store, store.importRows(seedRows));
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier IS {@code population} — the
     * predicate-born seeding entry point: {@code Graph.open(store).from(store.maskOfFacetClass(f,
     * c)).hop(...)} composes a traversal with zero row-id values anywhere.
     *
     * <p>Takes logical ownership of {@code population} — see the class documentation's "Lifecycle"
     * section: do not separately close it once it has been passed here.
     */
    public Graph from(Mask population) {
        Objects.requireNonNull(population, "population");
        return new Graph(store, population);
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is every row reached from the
     * current frontier by exactly one {@code edgeClassid} edge, over ALL 32 facets — equivalent to
     * {@link #hop(int, WideFieldMask)} with {@link WideFieldMask#allFacets()}.
     *
     * <p>Pays exactly one native crossing ({@link RowStore#hop(int, Mask)}), which performs the
     * classid match, the payload decode, and the scatter into the destination {@link Mask} entirely
     * natively — flat, regardless of how many rows are in the current frontier or which hop number
     * this is in a chain (see {@code GraphHopTest}'s crossing section for the measured, pinned
     * cost).
     *
     * <p>An unseeded {@code Graph} — the result of {@link #open} with no {@link #from(long...)} or
     * {@link #from(Mask)} call yet — short-circuits to an equally-unseeded result without any
     * native crossing at all: there is nothing to hop from.
     */
    public Graph hop(int edgeClassid) {
        if (frontier == null) {
            return new Graph(store, null);
        }
        return new Graph(store, store.hop(edgeClassid, frontier));
    }

    /**
     * As {@link #hop(int)}, but restricted to the facets named by {@code facets} — the wire form of
     * the contract's {@code FieldMask}. Pays exactly one native crossing ({@link RowStore#hop(int,
     * WideFieldMask, Mask)}); the effective participation the native side applies is {@code facets}
     * intersected with the {@code ClassView} provider's own declared edge participation for {@code
     * edgeClassid} — a caller cannot widen participation past what the provider allows.
     */
    public Graph hop(int edgeClassid, WideFieldMask facets) {
        Objects.requireNonNull(facets, "facets");
        if (frontier == null) {
            return new Graph(store, null);
        }
        return new Graph(store, store.hop(edgeClassid, facets, frontier));
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is the current frontier with {@code
     * other}'s frontier removed — {@code this.frontier & !other.frontier} — one native crossing
     * ({@link Mask#minus}).
     */
    public Graph minus(Graph other) {
        Objects.requireNonNull(other, "other");
        if (frontier == null) {
            return new Graph(store, null);
        }
        return new Graph(store, frontier.minus(other.frontierOrEmpty()));
    }

    /**
     * A new {@code Graph}, over the same store, whose frontier is the current frontier with {@code
     * other} removed — {@code this.frontier & !other} — one native crossing ({@link Mask#minus}).
     *
     * <p>{@code other} is read once to compute the result and is never retained; its lifecycle
     * stays entirely the caller's, unlike {@link #from(Mask)}'s argument. This is the replacement
     * for the removed {@code minus(long...)} overload: a caller with row ids to exclude composes
     * {@code graph.minus(store.importRows(rows))}.
     */
    public Graph minus(Mask other) {
        Objects.requireNonNull(other, "other");
        if (frontier == null) {
            return new Graph(store, null);
        }
        return new Graph(store, frontier.minus(other));
    }

    /** How many rows are in the current frontier. One native popcount ({@link Mask#count()}). */
    public long count() {
        return frontier == null ? 0L : frontier.count();
    }

    /**
     * The current frontier's row indices — the ONE named materialising terminal on this class (root
     * {@code CLAUDE.md}'s "named exceptions": row ids out). {@code O(n)} in the frontier's
     * population; see {@link Mask#materializeRows()} for the exact cost shape (a one-time describe,
     * then zero further crossings for repeated calls against the same underlying {@link Mask}).
     */
    public long[] materializeRows() {
        return frontier == null ? new long[0] : frontier.materializeRows();
    }

    /**
     * Closes this {@code Graph}'s own frontier {@link Mask}, if it has one. Does not close the
     * {@link RowStore} — see the class documentation's "Ownership" section — and does not close any
     * ancestor {@code Graph}'s frontier in the same chain — see "Lifecycle".
     *
     * <p>A no-op, exactly as before, ONLY for a {@code Graph} whose frontier was never set (fresh
     * from {@link #open}). Once a frontier exists, closing this {@code Graph} twice is an error —
     * the same discipline every other native resource in this codebase applies.
     */
    @Override
    public void close() {
        if (frontier != null) {
            frontier.close();
        }
    }

    /**
     * This {@code Graph}'s frontier, or a freshly imported empty {@link Mask} if none was ever set.
     * Used only by {@link #minus(Graph)}, so that operating against an unseeded {@code other} never
     * has to special-case "there is no {@link Mask} here yet" inside {@link Mask#minus}: an empty
     * import composes correctly (subtracting nothing) with zero extra logic at the call site.
     */
    private Mask frontierOrEmpty() {
        return frontier != null ? frontier : store.importRows();
    }

    @Override
    public String toString() {
        return "Graph[" + (frontier == null ? "unseeded" : frontier.toString()) + "]";
    }
}

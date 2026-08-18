package com.adaworldapi.graph;

import com.adaworldapi.lancegraph.RowStore;

/**
 * The edge-classid vocabulary for this consumer example: graph traversal over a {@link RowStore}
 * opened with {@link RowStore#openWithEdges}.
 *
 * <h2>This is a schema, not an entity</h2>
 *
 * <p>There is no such thing as an {@code Edge} instance, on purpose, and the constructor enforces
 * that rather than merely discouraging it (see below) — the same shape as {@code
 * com.adaworldapi.trades.Trade} and {@code com.adaworldapi.bricks.Orders}. There is nothing here to
 * hydrate: an edge is never a Java object with a source, a target, and a label. It is one classid
 * constant, used as a query argument (a "which facets carry this predicate" filter, via {@link
 * RowStore#facetMatches}) and decoded per-facet with {@link RowStore#classidAt}/{@link
 * RowStore#payloadLow64At}/{@link RowStore#payloadHi32At} — never materialized as its own row.
 *
 * <h2>Honest about what this convention actually is</h2>
 *
 * <p>docs/abi.md §1 states the ABI's own vocabulary discipline plainly: the membrane "speaks
 * resource, lane, view, mask, operation, descriptor, status, epoch — not {@code Node}, not
 * {@code Edge}, not {@code Person}." There is no edge type anywhere below this class. What exists
 * natively (docs/abi.md §12, {@link RowStore#openWithEdges}) is a fixture-generator convention: a
 * sparse, gated subset of facets whose classid equals a chosen constant carry a real target row in
 * their payload (recognizable because {@link RowStore#payloadHi32At} reads exactly {@code 0} for
 * those facets) instead of uniform noise. That is deliberately weaker than a real graph edge in a
 * lance-graph deployment, where an edge's predicate is resolved through a real {@code ClassView} /
 * ontology binding (see the lance-graph {@code CLAUDE.md} "CANON — Minimal SoA node" section and its
 * {@code EdgeCodecFlavor} discussion) — a classid there names a *concept*, minted and resolved
 * through the shared vocabulary, not merely "the value the fixture generator happened to gate on."
 *
 * <p>{@link #KNOWS} names classid {@code 0} for exactly that reason: it is the constant this
 * example's own pinned regression fixture ({@code RowStore.openWithEdges(2000, 0xF00D_CAFEL, 0, 0x0L,
 * 25)}) already uses, not a concept minted anywhere. A future integration against a real
 * ClassView-backed store replaces this single constant with real, ontology-resolved predicate
 * classids — the point where this class's honesty caveat above stops applying — without changing
 * {@link Graph}'s shape at all: {@link Graph#hop(int)} already takes the classid as a plain
 * argument, so it does not care whether the caller supplies {@link #KNOWS} or a minted concept id.
 */
public final class Edge {

    /**
     * Never constructed. An {@code Edge} instance would be exactly the per-relationship object this
     * example exists to prove unnecessary — a graph traversal reads classid/payload bytes directly
     * off the row store, it never allocates one Java object per edge. Thrown even through reflection
     * with {@code setAccessible(true)}, so the guarantee is "impossible," not merely "discouraged by
     * visibility."
     */
    private Edge() {
        throw new AssertionError(
                "an Edge is never materialized — a hop reads facet classid/payload bytes directly off"
                        + " the RowStore; there is no Edge instance to construct.");
    }

    /**
     * The edge classid this example's pinned regression fixture hops on. See the class documentation
     * for why this is a fixture-generator convention, not a real, ontology-resolved predicate.
     */
    public static final int KNOWS = 0;
}

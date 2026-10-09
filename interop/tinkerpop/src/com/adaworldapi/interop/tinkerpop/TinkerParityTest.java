package com.adaworldapi.interop.tinkerpop;

import com.adaworldapi.graph.Edge;
import com.adaworldapi.graph.Graph;
import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.List;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

/**
 * Quick win A1: one seeded graph, two engines, the same answers.
 *
 * <p>For each fixture, a {@link RowStore} is opened with {@link RowStore#openWithEdges}, and the
 * same edges are copied into a TinkerGraph (TinkerPop's reference in-memory implementation). Then
 * k-hop expansions from the same seed rows are answered twice:
 *
 * <ul>
 *   <li>by lance-graph-java, as a mask hop chain ({@code Graph.from(seeds).hop(KNOWS)...count()});
 *   <li>by Gremlin, {@code g.V(seeds).out("knows")...dedup().count()}.
 * </ul>
 *
 * <p><strong>The copy into TinkerGraph is test code only.</strong> It reads every row's facets one
 * at a time ({@code classidAt} / {@code payloadHi32At} / {@code payloadLow64At}), the same scalar
 * decoding {@code GraphHopTest}'s reference oracles use. lance-graph-java licenses a per-row Java
 * loop only as a test oracle (rule E2), never as an execution path.
 *
 * <p><strong>Walks are not vertices.</strong> {@code out().out().count()} counts walks, duplicates
 * included; a mask hop chain counts distinct rows. Only the {@code dedup()} form is comparable. The
 * suite records both, and requires at least one fixture where they differ, so the harness is shown
 * able to tell them apart.
 */
public final class TinkerParityTest {

    private TinkerParityTest() {}

    private static final int FACETS_PER_ROW = 32;
    private static final String KNOWS = "knows";
    private static final int MAX_HOPS = 3;

    private record Fixture(String name, long nRows, long seed, long gateMask, int radius,
            long[] seeds) {}

    private static long[] stride(int count, long step, long offset) {
        long[] rows = new long[count];
        for (int i = 0; i < count; i++) {
            rows[i] = i * step + offset;
        }
        return rows;
    }

    private static List<Fixture> fixtures() {
        return List.of(
                // GraphHopTest's pinned fixture: 1 hop reaches 19 rows, 2 hops 29.
                new Fixture("pinned n=2000", 2000L, 0xF00D_CAFEL, 0x0L, 25, stride(10, 37, 5)),
                new Fixture("n=10000 r=60", 10_000L, 0x5EED_1234L, 0x0L, 60, stride(40, 241, 3)),
                new Fixture("n=20000 r=120", 20_000L, 0xBADC_0FFEL, 0x0L, 120,
                        stride(100, 197, 11)));
    }

    public static void main(String[] args) {
        System.out.println("TinkerParityTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("TinkerParityTest"));
        }
        Checks c = new Checks("TinkerParityTest");
        boolean[] walksDifferedSomewhere = {false};
        for (Fixture f : fixtures()) {
            runFixture(c, f, walksDifferedSomewhere);
        }
        c.section("walk count vs distinct count");
        c.that("on at least one fixture, out()...count() (walks) differs from the dedup count,"
                + " so the harness can tell bag from set", walksDifferedSomewhere[0]);
        System.exit(c.report());
    }

    private static void runFixture(Checks c, Fixture f, boolean[] walksDiffered) {
        c.section(f.name());
        try (RowStore store = RowStore.openWithEdges(f.nRows(), f.seed(), Edge.KNOWS,
                f.gateMask(), f.radius())) {
            TinkerGraph tg = copyIntoTinkerGraph(store);
            long edges = tg.traversal().E().count().next();
            c.that("TinkerGraph copy has edges (" + edges + "), so the comparison is not vacuous",
                    edges > 0);
            GraphTraversalSource g = tg.traversal();
            Object[] seedIds = boxed(f.seeds());

            for (int k = 1; k <= MAX_HOPS; k++) {
                long lance = lanceHopCount(store, f.seeds(), Edge.KNOWS, k);
                long gremlinSet = outN(g.V(seedIds), k).dedup().count().next();
                long gremlinWalks = outN(g.V(seedIds), k).count().next();
                c.note(String.format("k=%d  lance-graph=%d  gremlin dedup=%d  gremlin walks=%d",
                        k, lance, gremlinSet, gremlinWalks));
                c.eq("k=" + k + ": lance-graph hop chain equals Gremlin dedup count",
                        gremlinSet, lance);
                c.that("k=" + k + ": walks >= distinct vertices", gremlinWalks >= gremlinSet);
                if (gremlinWalks != gremlinSet) {
                    walksDiffered[0] = true;
                }
                if (k == 1 && gremlinSet > 0) {
                    // Falsifier: the comparison must be able to fail. Hopping a classid that
                    // has no edges must not reproduce Gremlin's answer.
                    long wrong = lanceHopCount(store, f.seeds(), Edge.KNOWS + 1, k);
                    c.notEq("k=1: a hop over the wrong edge classid does NOT match Gremlin",
                            gremlinSet, wrong);
                }
            }
            tg.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GraphTraversal<Vertex, Vertex> outN(GraphTraversal<Vertex, Vertex> t, int k) {
        for (int i = 0; i < k; i++) {
            t = t.out(KNOWS);
        }
        return t;
    }

    private static long lanceHopCount(RowStore store, long[] seeds, int edgeClassid, int hops) {
        List<Graph> chain = new ArrayList<>();
        try {
            Graph g = Graph.open(store);
            chain.add(g);
            g = g.from(seeds);
            chain.add(g);
            for (int i = 0; i < hops; i++) {
                g = g.hop(edgeClassid);
                chain.add(g);
            }
            return g.count();
        } finally {
            for (Graph g : chain) {
                g.close();
            }
        }
    }

    /** Test-oracle copy: every KNOWS facet becomes one Gremlin edge, duplicates kept. */
    private static TinkerGraph copyIntoTinkerGraph(RowStore store) {
        TinkerGraph tg = TinkerGraph.open();
        long n = store.rowCount();
        Vertex[] vs = new Vertex[(int) n];
        for (long row = 0; row < n; row++) {
            vs[(int) row] = tg.addVertex(T.id, row);
        }
        for (long row = 0; row < n; row++) {
            for (int f = 0; f < FACETS_PER_ROW; f++) {
                FacetId facet = FacetId.of(f);
                if (store.classidAt(row, facet) != Edge.KNOWS) {
                    continue;
                }
                if (store.payloadHi32At(row, facet) != 0) {
                    continue;
                }
                long target = store.payloadLow64At(row, facet);
                if (target >= 0 && target < n) {
                    vs[(int) row].addEdge(KNOWS, vs[(int) target]);
                }
            }
        }
        return tg;
    }

    private static Object[] boxed(long[] rows) {
        Object[] out = new Object[rows.length];
        for (int i = 0; i < rows.length; i++) {
            out[i] = rows[i];
        }
        return out;
    }
}

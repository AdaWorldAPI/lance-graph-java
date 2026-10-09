package com.adaworldapi.interop.tinkerpop;

import com.adaworldapi.graph.Edge;
import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;

import java.util.Map;
import java.util.function.Function;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

/**
 * Quick win A2: {@link LanceHopStrategy} lowers {@code V(ids).out(l)^k.dedup().count()} onto the
 * mask hop chain and leaves everything else to Gremlin.
 *
 * <p>Four groups of checks:
 *
 * <ol>
 *   <li><b>Parity.</b> With the strategy, the answer equals plain Gremlin's for k = 1..3 on the
 *       A1 fixtures, through both {@code out()^k} and {@code repeat(out()).times(k)}, and the
 *       traversal really ran as one {@link LanceHopCountStep}.
 *   <li><b>Provenance.</b> Run against an EMPTY TinkerGraph, the lowered traversal still returns
 *       the row store's count. A strategy that only matched the shape and let Gremlin answer
 *       would return 0 here.
 *   <li><b>Fallback stays silent.</b> Shapes it must not lower (walk counts without
 *       {@code dedup()}, {@code has()}, {@code in()}, an unmapped label, {@code V()} without ids,
 *       an id outside the store) are counted as fallbacks and still answer exactly as Gremlin.
 *   <li><b>Counting.</b> The lowered and fallback counters move by exactly one per root
 *       traversal.
 * </ol>
 */
public final class LoweringStrategyTest {

    private LoweringStrategyTest() {}

    private static final String KNOWS = "knows";
    private static final long[] SEEDS = {5, 42, 79, 116, 153, 190, 227, 264, 301, 338};

    public static void main(String[] args) {
        System.out.println("LoweringStrategyTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("LoweringStrategyTest"));
        }
        Checks c = new Checks("LoweringStrategyTest");
        try (RowStore store = RowStore.openWithEdges(2000L, 0xF00D_CAFEL, Edge.KNOWS, 0x0L, 25)) {
            TinkerGraph tg = TinkerParityTest.copyIntoTinkerGraph(store);
            parity(c, store, tg);
            provenance(c, store);
            fallbacks(c, store, tg);
            tg.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.exit(c.report());
    }

    private static LanceHopStrategy strategy(RowStore store) {
        return new LanceHopStrategy(store, Map.of(KNOWS, Edge.KNOWS));
    }

    private static GraphTraversal<Vertex, Vertex> outN(GraphTraversal<Vertex, Vertex> t, int k) {
        for (int i = 0; i < k; i++) {
            t = t.out(KNOWS);
        }
        return t;
    }

    private static boolean ranAsOneLoweredStep(Traversal.Admin<?, ?> t) {
        return t.getSteps().size() == 1 && t.getSteps().get(0) instanceof LanceHopCountStep;
    }

    private static void parity(Checks c, RowStore store, TinkerGraph tg) {
        c.section("parity with plain Gremlin");
        GraphTraversalSource plain = tg.traversal();
        Object[] ids = TinkerParityTest.boxed(SEEDS);
        for (int k = 1; k <= 3; k++) {
            long expected = outN(plain.V(ids), k).dedup().count().next();

            LanceHopStrategy s = strategy(store);
            GraphTraversalSource g = tg.traversal().withStrategies(s);
            Traversal.Admin<?, Long> t = outN(g.V(ids), k).dedup().count().asAdmin();
            t.applyStrategies();
            c.that("k=" + k + ": out()^k runs as one LanceHopCountStep (got " + t.getSteps() + ")",
                    ranAsOneLoweredStep(t));
            c.eq("k=" + k + ": lowered out()^k equals Gremlin", expected, (long) t.next());
            c.eq("k=" + k + ": counted as lowered once", 1L, s.lowered());
            c.eq("k=" + k + ": no fallback", 0L, s.fellBack());

            LanceHopStrategy r = strategy(store);
            long viaRepeat = tg.traversal().withStrategies(r)
                    .V(ids).repeat(__.out(KNOWS)).times(k).dedup().count().next();
            c.eq("k=" + k + ": lowered repeat(out()).times(k) equals Gremlin", expected, viaRepeat);
            c.eq("k=" + k + ": repeat form also lowered", 1L, r.lowered());
        }
    }

    private static void provenance(Checks c, RowStore store) {
        c.section("provenance: the answer comes from the row store");
        TinkerGraph empty = TinkerGraph.open();
        Object[] ids = TinkerParityTest.boxed(SEEDS);
        long plain = empty.traversal().V(ids).out(KNOWS).out(KNOWS).dedup().count().next();
        long lowered = empty.traversal().withStrategies(strategy(store))
                .V(ids).out(KNOWS).out(KNOWS).dedup().count().next();
        c.eq("plain Gremlin over an empty graph counts nothing", 0L, plain);
        c.that("lowered traversal over the same empty graph still answers from the row store ("
                + lowered + ")", lowered > 0);
        empty.close();
    }

    private static void fallbacks(Checks c, RowStore store, TinkerGraph tg) {
        c.section("fallbacks leave the answer to Gremlin");
        Object[] ids = TinkerParityTest.boxed(SEEDS);
        fallback(c, store, tg, "walk count without dedup()",
                g -> g.V(ids).out(KNOWS).out(KNOWS).count(), "walks, not vertices");
        // Gremlin rewrites out().count() into outE().count() before provider strategies run,
        // so the case above never reaches the dedup check. With a barrier() in between, the
        // out() steps survive and only the dedup check stands between this walk count and a
        // wrong (distinct) answer.
        fallback(c, store, tg, "walk count through barrier()",
                g -> g.V(ids).out(KNOWS).out(KNOWS).barrier().count(), "walks, not vertices");
        fallback(c, store, tg, "has() on the start step",
                g -> g.V(ids).has("x", 1).out(KNOWS).dedup().count(), "has()");
        fallback(c, store, tg, "in() instead of out()",
                g -> g.V(ids).in(KNOWS).dedup().count(), "not an out()");
        fallback(c, store, tg, "unmapped edge label",
                g -> g.V(ids).out("likes").dedup().count(), "no lance-graph classid");
        fallback(c, store, tg, "V() without ids",
                g -> g.V().out(KNOWS).dedup().count(), "explicit numeric row ids");
        fallback(c, store, tg, "id outside the row store",
                g -> g.V(5L, 999_999L).out(KNOWS).dedup().count(), "explicit numeric row ids");
    }

    private static void fallback(Checks c, RowStore store, TinkerGraph tg, String what,
            Function<GraphTraversalSource, GraphTraversal<?, Long>> query, String reasonPart) {
        long expected = query.apply(tg.traversal()).next();
        LanceHopStrategy s = strategy(store);
        Traversal.Admin<?, Long> t = query.apply(tg.traversal().withStrategies(s)).asAdmin();
        t.applyStrategies();
        boolean lowered = t.getSteps().stream().anyMatch(st -> st instanceof LanceHopCountStep);
        long got = t.next();
        c.that(what + ": not lowered", !lowered);
        c.eq(what + ": counted as one fallback", 1L, s.fellBack());
        c.eq(what + ": lowered counter untouched", 0L, s.lowered());
        c.that(what + ": reason names the cause ('" + s.lastFallbackReason() + "')",
                s.lastFallbackReason().contains(reasonPart));
        c.eq(what + ": Gremlin's answer is unchanged", expected, got);
    }
}

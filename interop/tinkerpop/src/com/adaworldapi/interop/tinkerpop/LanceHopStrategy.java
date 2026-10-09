package com.adaworldapi.interop.tinkerpop;

import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Quick win A2: a TinkerPop provider strategy that lowers one traversal shape onto the
 * lance-graph-java mask hop chain, and leaves every other traversal to Gremlin.
 *
 * <p>The lowered shape is exactly
 *
 * <pre>{@code g.V(ids).out(label)^k.dedup().count()}</pre>
 *
 * with k &gt;= 1, one mapped edge label per hop, and no {@code has()} folded into the start
 * step. {@code repeat(out(label)).times(k)} reaches the same shape, because TinkerPop's
 * {@code RepeatUnrollStrategy} unrolls it before provider strategies run. A1 proved the
 * {@code dedup()} form equal to the mask chain; the form without {@code dedup()} counts walks,
 * not vertices, and is never lowered.
 *
 * <p><strong>Every decision is counted.</strong> {@link #lowered()} counts traversals replaced
 * by a {@link LanceHopCountStep}; {@link #fellBack()} counts root traversals left to Gremlin,
 * with the first reason kept in {@link #lastFallbackReason()}. A fallback is never an error:
 * Gremlin still answers, so a caller only loses speed, never correctness.
 *
 * <p>The strategy reads the row store it was given; the graph the traversal source wraps is not
 * consulted for a lowered traversal. Keeping the two consistent is the caller's job (in the
 * harness both are built from the same fixture). Vertex ids are row numbers.
 */
public final class LanceHopStrategy
        extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
        implements ProviderOptimizationStrategy {

    private final RowStore store;
    private final Map<String, Integer> edgeClassids;
    private final AtomicLong lowered = new AtomicLong();
    private final AtomicLong fellBack = new AtomicLong();
    private volatile String lastFallbackReason = "";

    /**
     * @param store the row store lowered traversals are answered from
     * @param edgeClassids Gremlin edge label to lance-graph edge classid; a label not in the map
     *     is not lowered
     */
    public LanceHopStrategy(RowStore store, Map<String, Integer> edgeClassids) {
        this.store = store;
        this.edgeClassids = Map.copyOf(edgeClassids);
    }

    /** Number of traversals answered by the mask hop chain. */
    public long lowered() {
        return lowered.get();
    }

    /** Number of root traversals left to Gremlin. */
    public long fellBack() {
        return fellBack.get();
    }

    /** Why the most recent fallback happened; empty if none has. */
    public String lastFallbackReason() {
        return lastFallbackReason;
    }

    @Override
    public void apply(Traversal.Admin<?, ?> traversal) {
        // Child traversals (inside by(), repeat() bodies, ...) are not candidates; only the
        // root is, and only the root is counted, so one query counts once.
        if (!(traversal.getParent() instanceof EmptyStep)) {
            return;
        }
        String reason = plan(traversal);
        if (reason != null) {
            fellBack.incrementAndGet();
            lastFallbackReason = reason;
        }
    }

    /** Lowers the traversal and returns null, or leaves it untouched and returns the reason. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String plan(Traversal.Admin<?, ?> traversal) {
        List<Step> steps = new ArrayList<>();
        for (Step s : traversal.getSteps()) {
            if (!(s instanceof NoOpBarrierStep)) {
                steps.add(s);
            }
        }
        if (steps.size() < 4) {
            return "too few steps for V().out()..dedup().count()";
        }
        if (!(steps.get(0) instanceof GraphStep<?, ?> start) || !start.returnsVertex()) {
            return "does not start with V()";
        }
        if (start instanceof HasContainerHolder holder && !holder.getHasContainers().isEmpty()) {
            return "has() on the start step";
        }
        long[] seeds = seedRows(start.getIds());
        if (seeds == null) {
            return "V() needs explicit numeric row ids within the row store";
        }
        int last = steps.size() - 1;
        if (!(steps.get(last) instanceof CountGlobalStep)) {
            return "does not end with count()";
        }
        if (!(steps.get(last - 1) instanceof DedupGlobalStep<?> dedup)
                || !dedup.getLocalChildren().isEmpty()
                || !dedup.getScopeKeys().isEmpty()) {
            return "count() without a plain dedup() counts walks, not vertices";
        }
        int[] hops = new int[last - 2];
        for (int i = 1; i < last - 1; i++) {
            // Provider strategies run in no fixed order, so has() can still be its own step
            // here rather than folded into the start step.
            if (steps.get(i) instanceof HasContainerHolder) {
                return "has() at step " + i;
            }
            if (!(steps.get(i) instanceof VertexStep<?> v)) {
                return "step " + i + " is not out(label): " + steps.get(i);
            }
            if (v.getDirection() != Direction.OUT || !v.returnsVertex()) {
                return "step " + i + " is not an out() to vertices";
            }
            if (v.getEdgeLabels().length != 1) {
                return "step " + i + " needs exactly one edge label";
            }
            Integer classid = edgeClassids.get(v.getEdgeLabels()[0]);
            if (classid == null) {
                return "edge label '" + v.getEdgeLabels()[0] + "' has no lance-graph classid";
            }
            hops[i - 1] = classid;
        }
        if (!steps.get(0).getLabels().isEmpty() || anyLabelled(steps)) {
            return "as() labels would be lost";
        }
        TraversalHelper.removeAllSteps(traversal);
        traversal.addStep(new LanceHopCountStep(traversal, store, seeds, hops));
        lowered.incrementAndGet();
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static boolean anyLabelled(List<Step> steps) {
        for (Step s : steps) {
            if (!s.getLabels().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Row numbers for V(ids), or null when any id is not a row of this store. */
    private long[] seedRows(Object[] ids) {
        if (ids == null || ids.length == 0) {
            return null;
        }
        long rows = store.rowCount();
        long[] out = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Object id = ids[i] instanceof Vertex v ? v.id() : ids[i];
            if (!(id instanceof Long || id instanceof Integer || id instanceof Short)) {
                return null;
            }
            long row = ((Number) id).longValue();
            if (row < 0 || row >= rows) {
                return null;
            }
            out[i] = row;
        }
        return out;
    }
}

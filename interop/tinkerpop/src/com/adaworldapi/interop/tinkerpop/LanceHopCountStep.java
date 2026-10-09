package com.adaworldapi.interop.tinkerpop;

import com.adaworldapi.graph.Graph;
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;

/**
 * The step {@link LanceHopStrategy} puts in place of {@code V(ids).out(l)^k.dedup().count()}.
 * It emits one value: the number of distinct rows the mask hop chain reaches from the seeds.
 *
 * <p>The answer is computed when the traversal is first iterated, not when the strategy runs,
 * so building a traversal costs nothing until it is used.
 */
final class LanceHopCountStep<S> extends AbstractStep<S, Long> {

    private final RowStore store;
    private final long[] seeds;
    private final int[] hops;
    private boolean done;

    LanceHopCountStep(Traversal.Admin<?, ?> traversal, RowStore store, long[] seeds, int[] hops) {
        super(traversal);
        this.store = store;
        this.seeds = seeds.clone();
        this.hops = hops.clone();
    }

    @Override
    protected Traverser.Admin<Long> processNextStart() {
        if (done) {
            throw FastNoSuchElementException.instance();
        }
        done = true;
        // The generator is typed by the step the traverser starts at; this step starts the
        // lowered traversal and produces the Long it carries.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Traverser.Admin<Long> t = getTraversal().getTraverserGenerator()
                .generate(count(), (org.apache.tinkerpop.gremlin.process.traversal.Step) this, 1L);
        return t;
    }

    private long count() {
        List<Graph> chain = new ArrayList<>();
        try {
            Graph g = Graph.open(store);
            chain.add(g);
            g = g.from(seeds);
            chain.add(g);
            for (int classid : hops) {
                g = g.hop(classid);
                chain.add(g);
            }
            return g.count();
        } finally {
            for (Graph g : chain) {
                g.close();
            }
        }
    }

    @Override
    public void reset() {
        super.reset();
        done = false;
    }

    @Override
    public String toString() {
        return "LanceHopCountStep(seeds=" + seeds.length + ", hops=" + Arrays.toString(hops) + ")";
    }
}

package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.Diagnostics;
import com.adaworldapi.lancegraph.Pattern;
import com.adaworldapi.lancegraph.Predicate;
import com.adaworldapi.lancegraph.View;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <strong>Components E and F — is fusion worth it, and what does the fluent API itself cost?</strong>
 *
 * <h2>E: fused vs unfused</h2>
 *
 * <p>{@code .where(a).where(b).where(c).count()} is <em>one</em> crossing. The same chain
 * evaluated predicate-by-predicate is one crossing per predicate, plus one per mask combine, plus
 * one to count — and the ABI keeps the unfused entry points alive for exactly this comparison,
 * so that "fusion matters" can be a measurement rather than an argument.
 *
 * <p>The predicate count is swept, because fusion's value is a slope, not a point: if the
 * difference did not grow with the number of predicates, it would not be fusion causing it.
 *
 * <p>{@code plan_eval_scalar} is measured alongside as the SIMD-vs-scalar spread through the same
 * membrane. It is the ABI's parity escape hatch, never a production path.
 *
 * <h2>F: the Java-side cost of the abstraction</h2>
 *
 * <p>Building the view and its predicate list touches no native code at all. If that construction
 * were expensive, the fluent API would be taxing the developer for the ergonomics — so it is
 * measured on its own, with the terminal operation omitted, and with {@link Diagnostics#crossings}
 * asserted unchanged so the "no native code" claim is checked rather than assumed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class E_FusionAndPlanning {

    /**
     * Two scales, because fusion's whole argument is about a FIXED per-crossing cost. At 65,536
     * rows the memory traffic dwarfs it and fusion should be invisible; at 256 rows the crossing
     * is most of the work and it should dominate. Measuring only the large one would have
     * produced "fusion does not help", which is true there and false in general.
     */
    @Param({"256", "65536"})
    public int rows;

    /** How many predicates in the chain. Fusion's advantage should scale with this. */
    @Param({"1", "2", "4", "8"})
    public int predicates;

    private Data data;
    private List<Predicate> chain;

    @Setup(Level.Trial)
    public void setup() {
        data = new Data(rows);
        chain = new ArrayList<>();
        // Predicates chosen so every one of them actually narrows: repeating an identical
        // predicate would let a hypothetical optimiser collapse the chain and would measure
        // deduplication rather than fusion. Each VALUE threshold is distinct and each is below
        // the fixture's maximum, so each contributes real work.
        chain.add(Pattern.CLASS.eq(Data.CLASS_NEEDLE));
        int[] thresholds = {100, 50, 0, -50, 120, 20, -100};
        for (int i = 0; i < predicates - 1; i++) {
            chain.add(Pattern.VALUE.gt(thresholds[i % thresholds.length]));
        }

        // Falsify the "fused and unfused agree" claim once, here, rather than trusting it.
        View v = view();
        long fused = Diagnostics.countFused(v);
        long unfused = Diagnostics.countUnfused(v);
        long scalar = Diagnostics.countScalar(v);
        if (fused != unfused || fused != scalar) {
            throw new AssertionError("fused=" + fused + " unfused=" + unfused + " scalar=" + scalar);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() { data.close(); }

    private View view() {
        View v = data.pattern.view();
        for (Predicate p : chain) v = v.where(p);
        return v;
    }

    /** ONE crossing regardless of {@link #predicates}. */
    @Benchmark
    public long fused() {
        return Diagnostics.countFused(view());
    }

    /** One crossing per predicate, plus combines, plus the count. */
    @Benchmark
    public long unfused() {
        return Diagnostics.countUnfused(view());
    }

    /** One crossing, forced down the scalar reference kernel. Parity path, not production. */
    @Benchmark
    public long fusedScalarKernel() {
        return Diagnostics.countScalar(view());
    }

    /**
     * Component F: build the whole chain and stop. No terminal operation, so no crossing.
     *
     * <p>The crossing counter is read before and after and compared, which is what makes this a
     * measurement of "the lazy API is lazy" rather than an assumption about it.
     */
    @Benchmark
    public void planConstructionOnly(Blackhole bh) {
        long before = Diagnostics.crossings();
        View v = view();
        bh.consume(v.conditionCount());
        if (Diagnostics.crossings() != before) {
            throw new AssertionError("building a view crossed the membrane; it must not");
        }
        bh.consume(v);
    }
}

package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.NativeAccess;
import com.adaworldapi.lancegraph.RowStore;
import com.adaworldapi.lancegraph.internal.ffm.Engine;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/**
 * <strong>Component H — what does a per-access liveness probe cost on a cached-descriptor
 * accessor?</strong>
 *
 * <p>This is the gate {@code .claude/plans/epoch-recheck-v3.md} §5 names for the {@code RowStore}
 * half of W1.1, and until now it did not exist — §5's own words, "gated on a benchmark that does
 * not yet exist". The {@code Mask} half shipped without it because it was never cost-gated: one
 * crossing per whole scan is not a number anyone needs measured. {@code RowStore} is the opposite
 * shape, and that asymmetry is the entire reason this class exists.
 *
 * <h2>Why the shapes differ, which is why only one half is gated</h2>
 *
 * <p>{@code Mask.words()} is called <em>once per scan</em>: {@code materializeRows()} resolves the
 * window and then reads every set bit in-process. A probe there is one downcall amortised over the
 * whole population.
 *
 * <p>{@code RowStore.lane(laneId)} is called <em>once per accessor invocation</em> — every
 * {@code classidAt}, {@code payloadLow64At}, {@code payloadHi32At} calls it, and each of those
 * reads exactly one row. A probe there is one downcall <em>per row read</em>. That is what §5's
 * "per-access or not at all" means, and why its cost is worth a number rather than a shrug.
 *
 * <h2>Both arms live here, and that is a correction to this plan's own board entry</h2>
 *
 * <p>{@code ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT} recorded that §5.5's build-time variant swap
 * "forces TWO BUILDS" — separate builds of {@code java/} compared across separate JMH runs, since
 * one source tree cannot put both arms on one classpath. <strong>That is true for swapping the
 * shipped class, and it is not what this measurement needs.</strong> §5.5's actual prohibition is
 * an {@code if (guardEnabled)} branch <em>inside</em> the accessor — correctly, because such a
 * branch is hoistable and is not the shape either arm ships. Two distinct methods, each with a
 * straight-line body and no branch on a mode flag, honour that prohibition exactly. So both arms
 * are built here, in one run, and the two-build requirement dissolves.
 *
 * <p>What that costs, stated rather than buried: these arms reconstruct {@code classidAt}'s body
 * rather than calling it, so this measures <em>the probe's cost on that shape of accessor</em>,
 * not {@code RowStore.classidAt} itself. The bounds check and the {@code FacetId} null-check are
 * deliberately excluded — they are identical in both arms and would only add a constant to both
 * sides of a subtraction. A reader wanting the absolute cost of the public method should not read
 * these numbers as that; a reader wanting the delta the gate is about should.
 *
 * <h2>The arms</h2>
 *
 * <ul>
 *   <li>{@code cached} — the window is resolved once in {@code @Setup} and read directly. This is
 *       today's {@code RowStore}: after the first touch, an accessor call crosses nothing.
 *   <li>{@code probed} — the window is re-described on every call, then read. This is the
 *       per-access probe: {@code lgj_lane_describe} resolves the handle through the
 *       generation-checked registry and fails closed on a stale one, exactly as the {@code Mask}
 *       half does, but paid once per row instead of once per scan.
 * </ul>
 *
 * <p>Both consume their result through a {@link Blackhole} so neither arm can be eliminated, and
 * both walk the same row sequence so neither gets a cache-locality advantage the other does not.
 *
 * <h2>What this class does NOT do</h2>
 *
 * <p>It does not decide anything. §5's verdict function takes {@code delta_ns} and an amendment's
 * {@code N}; <strong>no {@code N} has been recorded yet</strong>, and §5.2 requires that amendment
 * to be committed <em>before</em> the first run for the result to count as pre-registered. So the
 * numbers this produces are a measurement of the delta and nothing more — running it does not
 * ship the {@code RowStore} probe, and cannot, until an amendment names {@code N > 0} and a
 * results commit cites that amendment's sha.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 5, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class H_CachedAccessorProbe {

    /**
     * §5's frozen fixture size. Kept even though this measurement is per-call rather than
     * per-population: the row count still decides whether the walk stays in cache, and changing it
     * would make these numbers incomparable with every other §5 quantity.
     */
    @Param({"65536"})
    public int rows;

    /** One accessor call per {@code @Benchmark} invocation — the divisor §5's Score rule needs. */
    public static final int CALLS_PER_OP = 1;

    private RowStore store;
    private long handle;
    private int laneId;
    private Engine.LaneWindow cached;
    private long strideBytes;
    private long row;

    @Setup
    public void setUp() {
        store = RowStore.open(rows, 0xABCDL);
        handle = NativeAccess.handleOf(store);
        // Facet 0's classid lane — the lane RowStore.classidAt(row, FacetId.of(0)) resolves.
        laneId = com.adaworldapi.lancegraph.internal.ffm.Layouts.LANE_FACET_BASE;
        cached = NativeAccess.rowStoreLane(store, laneId);
        strideBytes = cached.strideBytes();

        // Cross-check the two arms agree before either is timed — the same discipline
        // RowStoreData's @Setup applies. If the probed path ever described a different lane, the
        // delta would be measuring two different reads and the gate would be meaningless.
        for (long r = 0; r < Math.min(rows, 1024L); r++) {
            int a = cached.segment().get(ValueLayout.JAVA_INT_UNALIGNED, r * strideBytes);
            Engine.LaneWindow fresh = Engine.describeLane(handle, laneId);
            int b = fresh.segment().get(ValueLayout.JAVA_INT_UNALIGNED, r * fresh.strideBytes());
            if (a != b) {
                throw new IllegalStateException(
                        "arms disagree at row " + r + ": cached=" + a + " probed=" + b);
            }
        }
    }

    @TearDown
    public void tearDown() {
        store.close();
    }

    /** Advance the row cursor identically for both arms. */
    private long nextRow() {
        long r = row;
        row = r + 1 == rows ? 0 : r + 1;
        return r;
    }

    /** Probe ABSENT: today's behaviour — the window was resolved once, the read crosses nothing. */
    @Benchmark
    public void cached(Blackhole bh) {
        long r = nextRow();
        bh.consume(cached.segment().get(ValueLayout.JAVA_INT_UNALIGNED, r * strideBytes));
    }

    /** Probe PRESENT: one generation-checked {@code lgj_lane_describe} per accessor call. */
    @Benchmark
    public void probed(Blackhole bh) {
        long r = nextRow();
        Engine.LaneWindow w = Engine.describeLane(handle, laneId);
        bh.consume(w.segment().get(ValueLayout.JAVA_INT_UNALIGNED, r * w.strideBytes()));
    }
}

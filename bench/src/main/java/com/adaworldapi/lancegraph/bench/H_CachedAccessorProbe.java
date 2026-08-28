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
 * <h2>This is NOT the gate, and the two-build requirement still stands</h2>
 *
 * <p>An earlier version of this header claimed both arms living in one run "corrects"
 * {@code ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT}'s finding that §5.5's variant swap "forces TWO
 * BUILDS", and that the requirement therefore "dissolves". <strong>That claim was wrong and has
 * been withdrawn</strong> (Codex P1 on #55; the board entry is struck to match).
 *
 * <p>The reason is what these arms actually call. Both reach {@link Engine#describeLane} directly,
 * so neither one is {@code RowStore.classidAt}: they bypass the cached {@code lanes[]} lookup, the
 * {@code requireOpen} check, the {@code FacetId} null-check and the bounds check. §5.4 is explicit
 * that the accessor "is an inlining/compile barrier that changes the surrounding loop's
 * optimization… That total is the right thing to gate on" — the whole method as the JIT sees it,
 * not a bench-local reconstruction of its body. Two <em>production</em> variants genuinely cannot
 * share one classpath without the hoistable branch §5.5 forbids, so two builds it is.
 *
 * <p>What this class measures, stated plainly: <strong>the cost of one bare
 * {@code lgj_lane_describe} crossing on a hot read loop.</strong> That is a real and useful
 * number — an order-of-magnitude input to whatever {@code N} an amendment eventually names — and
 * it is not the cost of the code shape that would ship.
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
 * <p>It does not decide anything, on two independent grounds. §5's verdict function takes
 * {@code delta_ns} and an amendment's {@code N}; <strong>no {@code N} has been recorded</strong>,
 * and §5.2 requires that amendment to be committed <em>before</em> the run for the result to count
 * as pre-registered. And even with one, the arms above are not the production accessor. A valid
 * {@code RowStore} gate needs all three: an amendment naming {@code N > 0} committed first, then
 * before/after variants of the real accessor in two builds of {@code java/}, then a results commit
 * citing that amendment's sha. {@code ISS-LGJ-EPOCH-UNCHECKED} stays OPEN for {@code RowStore}
 * until that runs.
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

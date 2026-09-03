package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.NativeAccess;
import com.adaworldapi.lancegraph.RowStore;
import com.adaworldapi.lancegraph.internal.ffm.Engine;
import com.adaworldapi.lancegraph.internal.ffm.Layouts;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/**
 * <strong>Component I — the §5 gate's instrument: the PRODUCTION accessor, timed in two
 * builds.</strong> ({@code D-LGJ-MMV-1a-bench})
 *
 * <p>Component H measured a bare {@code lgj_lane_describe} crossing and is void as a gate on two
 * grounds (Codex P1, #55): both its arms call {@link Engine#describeLane} directly, so neither is
 * {@code RowStore.classidAt}; and no {@code N} was pre-registered. This class fixes the first.
 * There is <em>one</em> benchmark method and it calls {@link RowStore#classidAt} — the whole
 * accessor as the JIT sees it: {@code requireOpen}, the cached {@code lanes[]} lookup, the
 * {@code FacetId} null-check, the bounds check, and the {@code LaneProbe} seam.
 *
 * <p>Which arm this is comes from the <em>build</em>, not from a parameter: {@code gate-run.sh}
 * compiles {@code java/src/main} twice — once as shipped ({@code before}, {@code LaneProbe} a
 * no-op) and once with {@code bench/variants/probed/LaneProbe.java} swapped in ({@code after},
 * one generation-checked re-describe per accessor call) — and runs this same class against each.
 * That is §5.5's build-time variant swap, and {@code ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT}'s
 * two-build consequence, taken literally. The two CSVs are then handed to {@code bench/gate.py},
 * which implements the §5 verdict table and nothing else.
 *
 * <p>The result is consumed through a {@link Blackhole} (§5.4 — an eliminated arm manufactures
 * the ratio), the row walk is identical in both builds, and the fixture is §5's frozen 65,536
 * rows. {@code @Fork(5)} is §5's floor, not a target; {@code gate.py}'s ex-ante power check
 * ({@code hw_delta < N/2}) is what says whether five were enough.
 *
 * <p><strong>This class decides nothing.</strong> It does not know {@code N}; it does not read the
 * other arm; it emits one CSV. The verdict is {@code gate.py}'s, and only with an amendment sha
 * that precedes both runs.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 5, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class I_ProductionAccessorGate {

    /** §5's frozen fixture size. */
    @Param({"65536"})
    public int rows;

    /** One accessor call per {@code @Benchmark} invocation — the divisor §5's Score rule states. */
    public static final int CALLS_PER_OP = 1;

    private RowStore store;
    private FacetId facet;
    private long row;

    @Setup
    public void setUp() {
        store = RowStore.open(rows, 0xABCDL);
        facet = FacetId.of(0);
        // Cross-check the accessor against a direct lane read before timing it, so the number is
        // known to be the cost of reading the right bytes, whichever LaneProbe is compiled in.
        Engine.LaneWindow direct = NativeAccess.rowStoreLane(store, Layouts.LANE_FACET_BASE);
        for (long r = 0; r < Math.min(rows, 1024L); r++) {
            int a = store.classidAt(r, facet);
            int b = direct.segment().get(ValueLayout.JAVA_INT_UNALIGNED, r * direct.strideBytes());
            if (a != b) {
                throw new IllegalStateException(
                        "accessor disagrees with the lane at row " + r + ": " + a + " vs " + b);
            }
        }
    }

    @TearDown
    public void tearDown() {
        store.close();
    }

    private long nextRow() {
        long r = row;
        row = r + 1 == rows ? 0 : r + 1;
        return r;
    }

    /** The production accessor. Which arm this is, the build decided. */
    @Benchmark
    public void classidAt(Blackhole bh) {
        bh.consume(store.classidAt(nextRow(), facet));
    }
}

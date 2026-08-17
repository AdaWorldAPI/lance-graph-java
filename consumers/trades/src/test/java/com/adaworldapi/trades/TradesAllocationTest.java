package com.adaworldapi.trades;

import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.NativePattern;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.View;

/**
 * The zero-allocation falsifier for the poster's headline claim: "One Billion Objects. Zero
 * Objects." — measured, not asserted in a comment.
 *
 * <p>The instrument is {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}, the same
 * one {@code valhalla-lab}'s {@code Lab.allocatedBytes} uses (see {@code
 * valhalla-lab/src/shared/com/adaworldapi/lancegraph/lab/Lab.java}), reapplied here as {@link
 * AllocMeter}: touch the instrument once so its own class-init cost is not attributed to the
 * measured body, snapshot allocated bytes before and after, subtract.
 *
 * <h2>The design is two-sided, on purpose</h2>
 *
 * <p>The thesis this test exists to check is <strong>row-count independence</strong>, not any
 * particular byte count: a query over a domain vocabulary must not allocate per logical row, so
 * the floor measured at 64,000 rows and the floor measured at 1,000,000 rows must be the same
 * (within measurement slack), even though the second query touches sixty-two times as much data
 * and matches far more rows. That is the load-bearing assertion — an implementation that
 * allocates a small, constant amount of bookkeeping per {@code count()} call passes it; one that
 * allocates per row fails it regardless of the constant it starts from.
 *
 * <p>The absolute bound (a generous fixed ceiling well below any plausible per-row cost) is the
 * backstop: it exists so an implementation that allocates a LARGE but row-count-independent
 * amount every call — which would otherwise satisfy the independence check by accident — still
 * fails loudly rather than passing on a technicality.
 *
 * <h2>Why the chain is composed outside the measured window</h2>
 *
 * <p>{@link View#where} is already proven allocation-bearing at the substrate tier (it allocates
 * one new {@code List} per call — see {@link View#where}'s Javadoc: "one 24-byte descriptor
 * appended to a list"). That is deliberate, cheap, and orthogonal to this test's question, which
 * is specifically about the <em>terminal</em> operation's cost as row count grows. So the chain is
 * built once, outside every measured window, and only the repeated {@code count()} calls on the
 * already-composed {@link View} are timed — exactly {@code LazinessTest}'s own discipline of
 * warming the reusable scratch selection before measuring the terminal cost.
 */
public final class TradesAllocationTest {

    private TradesAllocationTest() {}

    public static void main(String[] args) {
        System.out.println("TradesAllocationTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("TradesAllocationTest"));
        }
        Checks c = new Checks("TradesAllocationTest");
        run(c);
        System.exit(c.report());
    }

    /** Same seed as {@code TradesParityTest}, for the same reason: an explicit, checkable value. */
    private static final long SEED = 0xABCDL;

    /** JIT warm-up calls before any allocation is measured — allocation noise settles by then. */
    private static final int WARMUP_CALLS = 100;

    /** Measured calls after warm-up; the reported floor is the minimum delta across these. */
    private static final int MEASURED_CALLS = 10;

    /**
     * Slack allowed between the 64K floor and the 1M floor. Not zero: {@code
     * ThreadMXBean#getThreadAllocatedBytes} accounting and any residual JIT/GC noise are real, so
     * an exact-equality assertion would be a flaky test rather than a meaningful one. 4,096 bytes
     * is small next to the 64 KiB absolute ceiling below and large next to plausible measurement
     * jitter — chosen so a genuinely row-count-independent implementation clears it easily while a
     * per-row allocator (which would add many kilobytes per extra ~936,000 rows) cannot.
     */
    private static final long ROW_COUNT_INDEPENDENCE_SLACK_BYTES = 4_096;

    /**
     * Absolute backstop: any implementation whose steady-state {@code count()} allocates more than
     * this, at ANY row count, fails outright — independent of whether it happens to be
     * row-count-independent. 64 KiB is generous (far above any plausible constant-bookkeeping
     * cost: a handful of descriptors and a mask handle) and far below what a single row's worth of
     * boxed objects would cost at 64,000+ rows, so it cannot be cleared by accident.
     */
    private static final long ABSOLUTE_CEILING_BYTES = 64 * 1024;

    /**
     * The one line the orchestrator adjusts if {@code World.open}'s shape differs from what this
     * file assumes. See {@code TradesParityTest#openResource} for the full reasoning — this is the
     * identical helper, duplicated because this file is its own standalone suite (matching this
     * project's existing convention of duplicating small generator/adapter helpers per suite
     * rather than sharing test-internal code across compile units).
     */
    private static NativePattern openResource(long rows, long seed) {
        Object opened = World.open(rows, seed);
        if (opened instanceof NativePattern pattern) {
            return pattern;
        }
        if (opened instanceof View view) {
            return view.source();
        }
        throw new IllegalStateException(
                "World.open(rows, seed) returned " + opened.getClass()
                        + ", expected either NativePattern or View — update openResource() to match"
                        + " the shape World actually ships.");
    }

    /**
     * Bytes this thread allocated while running {@code body}, minus the instrument's own
     * overhead — the {@code valhalla-lab} {@code Lab.allocatedBytes} pattern, reapplied here with
     * a provenance comment rather than a dependency on the lab's classpath (the lab is a separate,
     * unpublished compile unit; this consumer module cannot depend on it).
     */
    private static final class AllocMeter {

        private AllocMeter() {}

        private static final com.sun.management.ThreadMXBean THREADS =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

        static long allocatedBytes(Runnable body) {
            long tid = Thread.currentThread().threadId();
            // Touch the instrument once so its own (one-time) class-init allocation is not
            // attributed to body.
            THREADS.getThreadAllocatedBytes(tid);
            long before = THREADS.getThreadAllocatedBytes(tid);
            body.run();
            long after = THREADS.getThreadAllocatedBytes(tid);
            return after - before;
        }
    }

    /**
     * The measured allocation floor for repeated {@code count()} calls over an already-composed,
     * two-predicate domain chain at {@code rows} rows: {@link #WARMUP_CALLS} untimed calls (to let
     * the JIT settle and the resource's reusable scratch selection get allocated once, matching
     * {@link NativePattern}'s own documented "reused destination for terminal operations"
     * behaviour), then the minimum delta across {@link #MEASURED_CALLS} timed calls — the minimum,
     * not the mean, because it is the honest floor: any allocation the query path genuinely
     * requires shows up on every call, so the best case across several calls is the query path's
     * real cost, and anything above it on a given call is incidental noise (a stray GC-adjacent
     * bookkeeping allocation, a scheduler artifact) rather than signal.
     */
    private static long measureFloor(long rows) {
        try (NativePattern resource = openResource(rows, SEED)) {
            View chain = resource.view()
                    .where(Trade.VENUE.eq(Trade.XETRA))
                    .where(Trade.PRICE.gt(100));

            for (int i = 0; i < WARMUP_CALLS; i++) {
                chain.count();
            }

            long floor = Long.MAX_VALUE;
            for (int i = 0; i < MEASURED_CALLS; i++) {
                long delta = AllocMeter.allocatedBytes(chain::count);
                floor = Math.min(floor, delta);
            }
            return floor;
        }
    }

    public static void run(Checks c) {
        c.section("allocation instrument sanity");
        long instrumentBaseline = AllocMeter.allocatedBytes(() -> {});
        c.note("instrument's own overhead on this VM: " + instrumentBaseline + " bytes"
                + " (not asserted to be exactly zero — VMs differ — only reported)");

        c.section("steady-state allocation floor, count() over a 2-predicate domain chain");

        long floor64k = measureFloor(64_000);
        long floor1m = measureFloor(1_000_000);
        c.note("64,000 rows:     " + floor64k + " bytes/call floor");
        c.note("1,000,000 rows:  " + floor1m + " bytes/call floor  (15.6x the row count)");

        c.section("thesis: allocation does not scale with rows");
        c.that("the 1M floor is not meaningfully larger than the 64K floor"
                        + " (allowed slack " + ROW_COUNT_INDEPENDENCE_SLACK_BYTES + " bytes)",
                floor1m <= floor64k + ROW_COUNT_INDEPENDENCE_SLACK_BYTES);
        c.note("a per-row-allocating implementation would fail this by many kilobytes,"
                + " not by a rounding error");

        c.section("backstop: both floors are small in absolute terms");
        c.atMost("64,000-row floor stays under " + ABSOLUTE_CEILING_BYTES + " bytes ("
                        + (ABSOLUTE_CEILING_BYTES / 1024) + " KiB)",
                ABSOLUTE_CEILING_BYTES, floor64k);
        c.atMost("1,000,000-row floor stays under " + ABSOLUTE_CEILING_BYTES + " bytes ("
                        + (ABSOLUTE_CEILING_BYTES / 1024) + " KiB)",
                ABSOLUTE_CEILING_BYTES, floor1m);
    }
}

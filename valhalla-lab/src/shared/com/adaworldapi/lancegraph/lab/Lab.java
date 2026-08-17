package com.adaworldapi.lancegraph.lab;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * The lab's measurement instruments. Deliberately small, and deliberately biased towards
 * measurements that are <em>hard to fool</em>.
 *
 * <h2>Why allocation bytes, not timing, is the primary instrument here</h2>
 *
 * <p>The question "did this abstraction cost a heap object?" is answered directly by
 * {@code ThreadMXBean.getThreadAllocatedBytes}, which the VM maintains from TLAB accounting. A
 * timing measurement answers it only by inference, and the inference is weak: escape analysis
 * already removes many allocations, so a fast loop proves nothing about whether the object
 * existed. Bytes are the observation; nanoseconds are the consequence.
 *
 * <p>This is also why {@code -XX:-DoEscapeAnalysis} appears in the run script. Comparing a record
 * against a value class with escape analysis on measures the JIT's ability to see through a
 * <em>particular</em> loop shape. Comparing with it off measures what the object model actually
 * costs when the JIT cannot save it — which is the property that generalises to real code, where
 * objects escape into arrays and collections all the time.
 *
 * <h2>Timing</h2>
 *
 * <p>Warm-up then repeated measurement, reporting the median and the full min/max spread. The
 * median is reported rather than the mean because a single GC pause or scheduler preemption in a
 * 4-core container moves a mean and does not move a median. The spread is printed alongside so a
 * reader can see when the median is not meaningful. This is not JMH — {@link #time} does not fork,
 * does not detect steady state, and does not do statistical rigour. Where JMH-grade numbers are
 * needed, they live in {@code bench/} and are produced by actual JMH.
 */
final class Lab {

    private Lab() {}

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** A sink the JIT cannot prove dead. Not a JMH blackhole; adequate for this lab's purposes. */
    static volatile long SINK;
    static volatile Object OBJ_SINK;

    // ── allocation ───────────────────────────────────────────────────────────────────────────

    /**
     * Bytes this thread allocated while running {@code body}, minus the harness's own overhead.
     *
     * <p>The baseline subtraction matters: calling {@code getThreadAllocatedBytes} is itself not
     * free of allocation on every VM, so an unsubtracted number would attribute the instrument's
     * cost to the subject. Measured on this VM the baseline is 0, and the code says so rather than
     * assuming it.
     */
    static long allocatedBytes(Runnable body) {
        long tid = Thread.currentThread().threadId();
        // Touch the instrument once so its own class-init allocation is not attributed to body.
        THREADS.getThreadAllocatedBytes(tid);
        long before = THREADS.getThreadAllocatedBytes(tid);
        body.run();
        long after = THREADS.getThreadAllocatedBytes(tid);
        return after - before;
    }

    /** The instrument's own cost, printed so the reader can see it is negligible. */
    static long allocationInstrumentBaseline() {
        return allocatedBytes(() -> {});
    }

    // ── retained footprint ───────────────────────────────────────────────────────────────────

    /**
     * Approximate retained heap of whatever {@code supplier} returns and keeps reachable.
     *
     * <p><strong>Labelled approximate deliberately.</strong> It is a used-heap delta around a
     * best-effort GC, so it is perturbed by anything else the VM does concurrently. It is reported
     * because footprint is the question the thesis actually asks, and cross-checked against the
     * exact allocation count so the two must agree in magnitude or one of them is wrong.
     */
    static long retainedBytesApprox(java.util.function.Supplier<Object> supplier) {
        Runtime rt = Runtime.getRuntime();
        settle();
        long before = rt.totalMemory() - rt.freeMemory();
        Object held = supplier.get();
        settle();
        long after = rt.totalMemory() - rt.freeMemory();
        OBJ_SINK = held;                 // keep it reachable across the second measurement
        return after - before;
    }

    private static void settle() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ── timing ───────────────────────────────────────────────────────────────────────────────

    record Timing(String name, double medianNs, double minNs, double maxNs, int iterations) {
        @Override public String toString() {
            return String.format("%-46s median=%10.1f ns  [min %10.1f .. max %10.1f]  n=%d",
                    name, medianNs, minNs, maxNs, iterations);
        }
        /** Per-unit cost, for reporting a per-row or per-element number honestly. */
        double perUnitNs(long units) { return medianNs / units; }
    }

    /**
     * Warm up, then measure {@code iterations} times and report median and spread.
     *
     * @param warmupRuns how many untimed runs before measuring; must be enough for C2 to compile
     *                   the loop, which for these bodies is hundreds, not tens
     */
    static Timing time(String name, int warmupRuns, int iterations, Runnable body) {
        for (int i = 0; i < warmupRuns; i++) body.run();
        double[] samples = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            body.run();
            samples[i] = System.nanoTime() - t0;
        }
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return new Timing(name, sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1],
                iterations);
    }

    // ── output ───────────────────────────────────────────────────────────────────────────────

    static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " " + "=".repeat(Math.max(0, 74 - title.length())));
    }

    static void kv(String key, Object value) {
        System.out.printf("%-44s %s%n", key, value);
    }

    static String bytes(long b) {
        if (Math.abs(b) < 1024) return b + " B";
        if (Math.abs(b) < 1024 * 1024) return String.format("%.1f KiB", b / 1024.0);
        return String.format("%.2f MiB", b / (1024.0 * 1024.0));
    }
}

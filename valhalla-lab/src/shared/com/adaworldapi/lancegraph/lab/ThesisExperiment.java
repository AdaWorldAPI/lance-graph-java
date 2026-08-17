package com.adaworldapi.lancegraph.lab;

import com.adaworldapi.lancegraph.NativeAccess;
import com.adaworldapi.lancegraph.NativePattern;
import com.adaworldapi.lancegraph.Pattern;
import com.adaworldapi.lancegraph.internal.ffm.Engine;

/**
 * The central claim, put at risk.
 *
 * <blockquote>64,000 logical entities must NOT require 64,000 Java objects.</blockquote>
 *
 * <p>Three ways to answer <em>the same question</em> over <em>the same 65,536 rows</em> —
 * "how many rows have {@code class == 7} and {@code value > 100}, and what do their values sum
 * to?" — measured for heap cost and for time:
 *
 * <ol>
 *   <li><strong>native</strong> — one lane set, one packed mask, one bulk crossing. Zero Java
 *       objects per row.
 *   <li><strong>objects</strong> — 65,536 materialised {@code Row} instances, then an ordinary
 *       Java loop. This is what a developer writes when the API hands them entities.
 *   <li><strong>values</strong> — the same 65,536 rows as Valhalla value objects in a flat array.
 *       This is the "Valhalla will fix it" hypothesis, given its best case: null-restricted,
 *       non-atomic, flattened storage.
 * </ol>
 *
 * <p><strong>The comparison is only fair if all three read identical bytes.</strong> They do:
 * paths 2 and 3 are populated by copying out of the very lanes path 1 scans, so no path enjoys a
 * different dataset, a different distribution, or a warmer cache than the others. The three
 * answers are asserted equal before any number is reported — a benchmark whose variants compute
 * different things is measuring nothing.
 *
 * <p>The expected finding is that Valhalla helps the tiny descriptor vocabulary (see
 * {@link FootprintExperiment}) and does <em>not</em> rescue per-entity materialisation. Expected is
 * not observed; the numbers are printed either way, including if they contradict it.
 */
final class ThesisExperiment {

    private ThesisExperiment() {}

    static final int ROWS = 65_536;
    private static final int CLASS_NEEDLE = 7;
    private static final int VALUE_THRESHOLD = 100;

    static void run() {
        Lab.section("THE THESIS — 65,536 entities, three representations");
        Lab.kv("platform", Platform.NAME);
        Lab.kv("rows", ROWS);
        Lab.kv("question", "count(class==" + CLASS_NEEDLE + " AND value>" + VALUE_THRESHOLD
                + ") and sum(value)");

        try (NativePattern data = NativePattern.open(ROWS)) {

            // ── (1) native: one lane set, one packed mask, one bulk op ───────────────────────
            // Warm first. The View's scratch mask is created lazily on the first terminal call,
            // and the FFM scratch arena on the first crossing, so an unwarmed measurement would
            // report one-time setup as if it were per-query cost.
            for (int i = 0; i < 2_000; i++) {
                Lab.SINK = data.view()
                        .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                        .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                        .count();
            }
            long nativeAllocBytes = Lab.allocatedBytes(() ->
                    Lab.SINK = data.view()
                            .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                            .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                            .count());
            long nativeCount = data.view()
                    .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                    .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                    .count();
            long nativeSum = data.view()
                    .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                    .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                    .sumOf(Pattern.VALUE);

            // ── build (2)/(3) from the SAME native bytes ─────────────────────────────────────
            Engine.LaneWindow ids = NativeAccess.lane(data, NativeAccess.LANE_ID);
            Engine.LaneWindow classes = NativeAccess.lane(data, NativeAccess.LANE_CLASS);
            Engine.LaneWindow values = NativeAccess.lane(data, NativeAccess.LANE_VALUE);

            Object[] rows = Platform.newRowArray(ROWS);
            long hydrateAllocBytes = Lab.allocatedBytes(() -> {
                for (int i = 0; i < ROWS; i++) {
                    rows[i] = new Row(ids.getU64(i), (int) classes.getU32(i), values.getI32(i));
                }
            });
            Lab.OBJ_SINK = rows;

            long objCount = 0, objSum = 0;
            for (int i = 0; i < ROWS; i++) {
                Row r = (Row) rows[i];
                if (r.cls() == CLASS_NEEDLE && r.value() > VALUE_THRESHOLD) { objCount++; objSum += r.value(); }
            }

            // ── the falsifier: all three must agree, or nothing below means anything ─────────
            if (objCount != nativeCount || objSum != nativeSum) {
                throw new AssertionError("the three paths do not compute the same answer: native="
                        + nativeCount + "/" + nativeSum + " objects=" + objCount + "/" + objSum);
            }
            Lab.kv("answer (identical across all paths)", nativeCount + " rows, sum " + nativeSum);
            Lab.kv("selectivity", String.format("%.2f%%", 100.0 * nativeCount / ROWS));

            // ── heap cost ────────────────────────────────────────────────────────────────────
            Lab.section("  heap cost");
            Lab.kv("(1) native — Java bytes allocated (warm)", Lab.bytes(nativeAllocBytes)
                    + "  per query, for the fluent chain itself");
            Lab.kv("(1) native — Java objects per row", 0);
            Lab.kv("(1) native — native lane bytes",
                    Lab.bytes(ROWS * (8L + 4 + 4)) + "  (u64 id + u32 class + i32 value)");
            Lab.kv("(1) native — mask bytes",
                    Lab.bytes((ROWS + 63) / 64 * 8L) + "  (1 bit per row, packed)");
            Lab.kv("(2)/(3) hydrate " + ROWS + " Row — allocated",
                    Lab.bytes(hydrateAllocBytes));
            Lab.kv("        ... per row", String.format("%.2f B", hydrateAllocBytes / (double) ROWS));
            Lab.kv("        array flatness", Platform.arrayFlatness(rows));
            Lab.kv("        ratio vs native lane bytes",
                    String.format("%.2fx", hydrateAllocBytes / (double) (ROWS * 16L)));

            long retained = Lab.retainedBytesApprox(() -> {
                Object[] held = Platform.newRowArray(ROWS);
                for (int i = 0; i < ROWS; i++) {
                    held[i] = new Row(ids.getU64(i), (int) classes.getU32(i), values.getI32(i));
                }
                return held;
            });
            Lab.kv("        retained heap (APPROX, gc-delta)", Lab.bytes(retained));

            // ── time ─────────────────────────────────────────────────────────────────────────
            Lab.section("  time to answer the question");
            System.out.println(Lab.time("(1) native  one crossing, fused plan", 2_000, 51, () ->
                    Lab.SINK = data.view()
                            .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                            .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                            .count()));

            System.out.println(Lab.time("(2/3) hydrate " + ROWS + " Row objects", 200, 51, () -> {
                Object[] a = Platform.newRowArray(ROWS);
                for (int i = 0; i < ROWS; i++) {
                    a[i] = new Row(ids.getU64(i), (int) classes.getU32(i), values.getI32(i));
                }
                Lab.OBJ_SINK = a;
            }));

            System.out.println(Lab.time("(2/3) scan the materialised objects", 2_000, 51, () -> {
                long c = 0, s = 0;
                for (int i = 0; i < ROWS; i++) {
                    Row r = (Row) rows[i];
                    if (r.cls() == CLASS_NEEDLE && r.value() > VALUE_THRESHOLD) { c++; s += r.value(); }
                }
                Lab.SINK = c + s;
            }));

            System.out.println(Lab.time("(2/3) hydrate THEN scan (honest total)", 200, 51, () -> {
                Object[] a = Platform.newRowArray(ROWS);
                for (int i = 0; i < ROWS; i++) {
                    a[i] = new Row(ids.getU64(i), (int) classes.getU32(i), values.getI32(i));
                }
                long c = 0, s = 0;
                for (int i = 0; i < ROWS; i++) {
                    Row r = (Row) a[i];
                    if (r.cls() == CLASS_NEEDLE && r.value() > VALUE_THRESHOLD) { c++; s += r.value(); }
                }
                Lab.SINK = c + s;
                Lab.OBJ_SINK = a;
            }));
        }
    }
}

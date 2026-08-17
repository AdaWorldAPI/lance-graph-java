package com.adaworldapi.lancegraph.lab;

import com.adaworldapi.lancegraph.NativeAccess;
import com.adaworldapi.lancegraph.NativePattern;
import com.adaworldapi.lancegraph.internal.ffm.Engine;

/**
 * Does a semantic wrapper cost anything when it is used to <em>address</em> native memory?
 *
 * <p>This is the question that decides whether the API's vocabulary can go all the way down. The
 * production API hides {@code MemorySegment} entirely, but the shape it hides is
 * "offset arithmetic on a native address". If wrapping a row index in a meaningful type made that
 * arithmetic slower, the vocabulary would have to stop at the API surface and become bare
 * {@code long}s underneath — an abstraction that is only free where nobody is looking.
 *
 * <p>Three variants over the same lane, same 65,536 elements, same result asserted equal:
 *
 * <ul>
 *   <li>a bare {@code long} index — the floor;
 *   <li>a {@code RowRange} driving the loop bounds — a wrapper read once per loop;
 *   <li>a per-element wrapper ({@code Ordinal}) constructed inside the loop — a wrapper read once
 *       per <em>element</em>, which is the shape that would actually be expensive.
 * </ul>
 *
 * <p>The third is the one that matters. A wrapper hoisted out of a loop is free on any JDK; a
 * wrapper allocated 65,536 times is exactly the case a value class is supposed to make free, and
 * exactly the case escape analysis sometimes already handles. Measuring both is what separates
 * "Valhalla helped" from "the JIT was already doing it".
 */
final class FfmAddressingExperiment {

    private FfmAddressingExperiment() {}

    private static final int ROWS = 65_536;

    static void run() {
        Lab.section("FFM ADDRESSING — is the wrapper free where it touches native memory?");
        Lab.kv("platform", Platform.NAME);

        try (NativePattern data = NativePattern.open(ROWS)) {
            Engine.LaneWindow values = NativeAccess.lane(data, NativeAccess.LANE_VALUE);

            long bare = 0;
            for (long i = 0; i < ROWS; i++) bare += values.getI32(i);

            RowRange range = RowRange.of(ROWS);
            long viaRange = 0;
            for (long i = range.start(); i < range.endExclusive(); i++) viaRange += values.getI32(i);

            long viaWrapper = 0;
            for (int i = 0; i < ROWS; i++) viaWrapper += values.getI32(Ordinal.of(i).value());

            if (bare != viaRange || bare != viaWrapper) {
                throw new AssertionError("addressing variants disagree: " + bare + " / "
                        + viaRange + " / " + viaWrapper);
            }
            Lab.kv("sum (identical across all three)", bare);

            System.out.println(Lab.time("bare long index", 2_000, 51, () -> {
                long acc = 0;
                for (long i = 0; i < ROWS; i++) acc += values.getI32(i);
                Lab.SINK = acc;
            }));

            System.out.println(Lab.time("RowRange bounds (wrapper hoisted)", 2_000, 51, () -> {
                long acc = 0;
                for (long i = range.start(); i < range.endExclusive(); i++) acc += values.getI32(i);
                Lab.SINK = acc;
            }));

            long wrapperAlloc = Lab.allocatedBytes(() -> {
                long acc = 0;
                for (int i = 0; i < ROWS; i++) acc += values.getI32(Ordinal.of(i).value());
                Lab.SINK = acc;
            });
            Lab.kv("per-element wrapper: bytes allocated", Lab.bytes(wrapperAlloc));
            Lab.kv("  ... per element", String.format("%.2f B", wrapperAlloc / (double) ROWS));

            System.out.println(Lab.time("Ordinal built per element", 2_000, 51, () -> {
                long acc = 0;
                for (int i = 0; i < ROWS; i++) acc += values.getI32(Ordinal.of(i).value());
                Lab.SINK = acc;
            }));
        }
    }
}

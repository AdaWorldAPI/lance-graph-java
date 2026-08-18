package com.adaworldapi.graph;

import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.Diagnostics;
import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.FacetMatchView;
import com.adaworldapi.lancegraph.Mask;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;
import com.adaworldapi.lancegraph.WideFieldMask;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The falsifier suite for the graph-traversal consumer example: {@link Graph#hop(int)} over a
 * {@link RowStore} opened with {@link RowStore#openWithEdges}, where a sparse gated subset of facets
 * whose classid equals {@code Edge.KNOWS} carry a real target-row payload instead of noise.
 *
 * <p><strong>Mask-native re-pin (D-LGJ-W8, PR-W8b):</strong> {@link Graph} used to carry its
 * frontier as a plain {@code long[]}/{@code TreeSet<Long>}; it now carries it as a native {@link
 * Mask}. This file's checks are re-pinned accordingly:
 *
 * <ol>
 *   <li><strong>Hop correctness</strong> — {@link Graph#materializeRows()}'s 1-hop and 2-hop row
 *       sets agree with TWO independently-written, mutually-agreeing pure-Java BFS transcriptions
 *       over the same {@link RowStore} public read surface ({@link RowStore#facetMatches},
 *       {@link RowStore#classidAt}, {@link RowStore#payloadHi32At},
 *       {@link RowStore#payloadLow64At}) — neither transcription calls into {@code Graph} at all,
 *       so agreement is evidence about the traversal semantics, not a tautology. <strong>Both
 *       transcriptions are preserved VERBATIM</strong> from the pre-migration suite (frozen spec
 *       §1.6): they are the scalar reference oracle, not the implementation under test.
 *   <li><strong>Crossings proportional to hops, not rows</strong> — the per-hop cost measured via
 *       {@link Diagnostics#crossings()} is identical whether the underlying store has 2000 rows
 *       or 500, a second and third hop cost exactly what the first one did (no lifecycle-warmup
 *       asymmetry survives the migration — that asymmetry lived entirely in the retired Java-side
 *       per-row decode loop), and {@code count()}/{@code minus()}/{@code materializeRows()} each
 *       have their own measured, pinned cost shape.
 *   <li><strong>Anti-vacuity</strong> — the seed set, the 1-hop set, and the 2-hop set are three
 *       different, non-empty, non-total sizes, checked as six independent boolean assertions.
 *   <li><strong>The mask-native structural surface (G1/G8)</strong> — {@link Graph} (and {@link
 *       Edge}) are checked reflectively: zero fields typed {@code long[]}/{@code Long[]}/any
 *       {@code java.util.Collection}; the ONLY public method with a {@code long[]}/{@code long...}
 *       PARAMETER is {@code from}; the ONLY public methods returning {@code long[]} are named
 *       {@code materialize*}; every other public return type is {@code Graph}/{@code long}/
 *       {@code void}/{@link Mask}/{@link WideFieldMask}. This subsumes the old "zero
 *       serialization" reflective check (no {@code byte[]}/{@code Map}/serialization type was ever
 *       in the allowed return set, and still is not) under its correct, more specific name.
 *   <li><strong>G9 flagship composition</strong> — {@code from(maskOfFacetClass(...)).hop(...)
 *       .count()} end-to-end, with zero row-id values anywhere in the composition (the oracle used
 *       only to prove the seed is non-vacuous is a scalar running count, not even an array).
 *   <li><strong>G3 allocation independence</strong> — {@code from -> hop -> hop -> count}'s
 *       Java-heap allocation does not scale with how many rows are in the traversal frontier,
 *       measured via {@code ThreadMXBean#getThreadAllocatedBytes} at two materially different
 *       frontier scales (mirroring {@code TradesAllocationTest}'s instrument and reasoning).
 * </ol>
 *
 * <p>Also carries the reflection guard {@code TradesParityTest}/{@code BricksAuthTest} both apply
 * to their domain schema types, here against {@link Edge}: no public constructor, no instance
 * state, and forcing the private constructor accessible still cannot build one.
 */
public final class GraphHopTest {

    private GraphHopTest() {}

    public static void main(String[] args) {
        System.out.println("GraphHopTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("GraphHopTest"));
        }
        Checks c = new Checks("GraphHopTest");
        run(c);
        System.exit(c.report());
    }

    // ------------------------------------------------------------------
    // The pinned regression fixture (docs/abi.md §12, RowStore::generate_with_edges) — n=2000,
    // seed=0xF00D_CAFE, edge_classid=0 (== Edge.KNOWS), gate_mask=0x0 (densest), radius=25, a
    // 10-row seed set (i*37+5 for i in 0..10). Measured and pinned twice already in this repo: a
    // native Rust regression test and RowStoreParityTest's own Java-side transcription. 1 hop
    // reaches exactly 19 distinct rows; 2 hops reach exactly 29.
    // ------------------------------------------------------------------

    private static final long HOP_N = 2000L;
    private static final long HOP_SEED = 0xF00D_CAFEL;
    private static final long GATE_MASK = 0x0L;
    private static final int RADIUS = 25;
    private static final int FACETS_PER_ROW = 32;

    /** A second row count, same seed/gate/radius, used only to prove crossings don't scale with n. */
    private static final long SECOND_N = 500L;

    // ------------------------------------------------------------------
    // Predicted crossing-cost constants (spec §3.7's "expected shape", mask-native-navigation-
    // correction-v1.md). Each is a best-effort PREDICTION, not a measurement — the orchestrator
    // runs this suite, reads the real deltas printed via Checks.note(), and corrects these
    // literals before the wave lands (the D-LGJ-W6/W7 measure-then-pin discipline; root
    // CLAUDE.md's "Iron rules": "Measure-then-pin: crossing counts... are pinned from
    // measurement, never predicted.").
    //
    // MEASURE-THEN-PIN (orchestrator) applies to every constant below. Reasoning behind each
    // guess, so a wrong one is easy to diagnose rather than mysterious:
    //   - HOP: RowStore.hop(...) is documented (spec §3.5) as "creates the dst mask, one lgj_hop
    //     crossing" -- read literally that is ONE crossing, but RowStore.maskOfFacetClass's own
    //     javadoc makes the identical claim ("This is one native crossing (lgj_op_eq_classid)")
    //     for a method whose body calls BOTH Engine.createMask AND Engine.eqClassid -- two real
    //     downcalls. If RowStore.hop follows that same create-then-operate shape, the true number
    //     here is 2, not 1.
    //   - COUNT: Mask.count() is a single, unconditional Engine.maskCount() call with no lazy
    //     setup -- higher confidence than the others, but still measured, not assumed.
    //   - MINUS: Mask.minus(Mask) most plausibly follows the same create-then-operate shape as
    //     maskOfFacetClass (lgj_mask_andnot's signature takes a pre-existing dst, per spec §3.4),
    //     so the guess mirrors HOP's reasoning: create + op = 2.
    //   - MATERIALIZE_FIRST: spec §3.5 says "describe cached per Mask; zero crossings
    //     steady-state" -- so SOME one-time cost is expected on the first call against a given
    //     Mask; 1 is the simplest guess consistent with that sentence.
    //   - IMPORT: spec §3.5 says importRows is "createMask(EMPTY) + in-process word writes" --
    //     word writes are documented as NOT crossing the membrane, so the guess is the same
    //     shape as a bare create: 1. (Spec §3.7 additionally hints at "+1 lifecycle describe
    //     amortized" on some first call, whose exact scope this suite could not resolve without
    //     running it -- see the row-count-independence check below for the assertion that does
    //     NOT depend on getting this number right.)
    // ------------------------------------------------------------------
    // MEASURED AND PINNED (orchestrator, 2026-08-18, ABI 0.4, release .so,
    // JDK 26.0.2). Two predictions were wrong, both in the direction the HOP
    // reasoning above anticipated, and each measured value has a mechanical
    // attribution read from the RowStore/Engine source, not guessed:
    //   - HOP measured 2 (predicted 1): Engine.createMask(dst) + Engine.hop —
    //     the create-then-operate shape, exactly as the reasoning above
    //     suspected. Flat across all three hops and both store sizes (the
    //     structural checks that do not depend on the constant all passed
    //     before the pin).
    //   - IMPORT measured 2 (predicted 1): Engine.createMask +
    //     Engine.describeMask (the lane-window describe is itself a
    //     downcall); the per-row word writes are in-process MemorySegment
    //     access — proven by the passing row-count-independence check
    //     (3 rows vs 29 rows cost identically) and the flat G3 floor.
    //   - COUNT 1, MINUS 2, MATERIALIZE_FIRST 1: measured equal to the
    //     predictions above.

    private static final long PREDICTED_HOP_CROSSINGS = 2;               // MEASURED-AND-PINNED
    private static final long PREDICTED_COUNT_CROSSINGS = 1;             // MEASURED-AND-PINNED
    private static final long PREDICTED_MINUS_CROSSINGS = 2;             // MEASURED-AND-PINNED
    private static final long PREDICTED_MATERIALIZE_FIRST_CROSSINGS = 1; // MEASURED-AND-PINNED
    private static final long PREDICTED_IMPORT_CROSSINGS = 2;            // MEASURED-AND-PINNED

    private static long[] seedRows() {
        long[] rows = new long[10];
        for (int i = 0; i < 10; i++) {
            rows[i] = i * 37L + 5;
        }
        return rows;
    }

    public static void run(Checks c) {
        checkHopCorrectness(c);
        checkCrossingsProportionalToHops(c);
        checkWideFieldMaskHopParity(c);
        checkAntiVacuity(c);
        checkMaskNativeSurface(c);
        checkFlagshipMaskNativeComposition(c);
        checkAllocationIndependence(c);
        checkEdgeIsASchema(c);
    }

    // ------------------------------------------------------------------
    // 1. Hop correctness: Graph's row sets vs TWO independent pure-Java BFS transcriptions.
    // ------------------------------------------------------------------

    private static void checkHopCorrectness(Checks c) {
        c.section("hop correctness at the pinned fixture (n=2000) — Graph vs two independent"
                + " pure-Java BFS transcriptions");

        long[] seeds = seedRows();

        try (RowStore store = RowStore.openWithEdges(HOP_N, HOP_SEED, Edge.KNOWS, GATE_MASK,
                RADIUS)) {
            // Two transcriptions that never call into Graph or each other's helper: one walks the
            // per-store facetMatches() bitset, the other independently re-derives the same bitset
            // by scanning all 32 facets' classidAt() per row. Neither imports the other; agreement
            // between them is itself evidence, before Graph is ever consulted.
            long[] bitsetOneHop = bfsHopViaFacetMatches(store, seeds, Edge.KNOWS);
            long[] scanOneHop = bfsHopViaClassidScan(store, seeds, Edge.KNOWS);

            c.eq("the two independent Java transcriptions agree on 1-hop set size",
                    bitsetOneHop.length, scanOneHop.length);
            c.that("the two independent Java transcriptions agree on the 1-hop row SET",
                    sameRowSet(bitsetOneHop, scanOneHop));
            c.eq("bitset-based transcription: 1-hop reaches the pinned 19 rows",
                    19, bitsetOneHop.length);
            c.eq("classid-scan transcription: 1-hop reaches the pinned 19 rows",
                    19, scanOneHop.length);

            long[] bitsetTwoHop = bfsHopViaFacetMatches(store, bitsetOneHop, Edge.KNOWS);
            long[] scanTwoHop = bfsHopViaClassidScan(store, scanOneHop, Edge.KNOWS);

            c.eq("the two independent Java transcriptions agree on 2-hop set size",
                    bitsetTwoHop.length, scanTwoHop.length);
            c.that("the two independent Java transcriptions agree on the 2-hop row SET",
                    sameRowSet(bitsetTwoHop, scanTwoHop));
            c.eq("bitset-based transcription: 2-hop reaches the pinned 29 rows",
                    29, bitsetTwoHop.length);
            c.eq("classid-scan transcription: 2-hop reaches the pinned 29 rows",
                    29, scanTwoHop.length);

            // Now the actual consumer facade under test.
            try (Graph graph = Graph.open(store)) {
                Graph afterFrom = graph.from(seeds);
                c.eq("Graph: from(seedRows) count() equals the seed set size",
                        seeds.length, afterFrom.count());
                c.that("Graph: from(seedRows) row SET equals the seed set exactly",
                        sameRowSet(seeds, afterFrom.materializeRows()));

                // from(Mask) must agree exactly with from(long...) for the identical logical seed
                // set -- the two named entry points into the same mask-native currency (root
                // CLAUDE.md's "named exceptions": from(long...) is the external-selection import;
                // from(Mask) is predicate-born seeding, but nothing stops it from wrapping an
                // imported Mask too).
                Mask seedMaskFromImport = store.importRows(seeds);
                Graph afterFromMask = Graph.open(store).from(seedMaskFromImport);
                c.that("Graph: from(Mask) row SET matches from(long...) for the identical seed"
                                + " set",
                        sameRowSet(afterFrom.materializeRows(), afterFromMask.materializeRows()));

                Graph afterOneHop = afterFrom.hop(Edge.KNOWS);
                c.eq("Graph: 1-hop count() matches the pinned regression",
                        19, afterOneHop.count());
                c.eq("Graph: 1-hop count() matches materializeRows().length",
                        afterOneHop.count(), afterOneHop.materializeRows().length);
                c.that("Graph: 1-hop row SET matches the independent recomputation exactly",
                        sameRowSet(bitsetOneHop, afterOneHop.materializeRows()));

                Graph afterTwoHops = afterOneHop.hop(Edge.KNOWS);
                c.eq("Graph: 2-hop count() matches the pinned regression",
                        29, afterTwoHops.count());
                c.that("Graph: 2-hop row SET matches the independent recomputation exactly",
                        sameRowSet(bitsetTwoHop, afterTwoHops.materializeRows()));

                // minus(store.importRows(seedRows)): every seed row present in the 2-hop set must
                // be gone afterward, and count() must drop by exactly that many -- no more, no
                // fewer. The old minus(long...) varargs overload is REMOVED (council-ruled): the
                // caller composes the import explicitly, right here, so the row-id-import
                // boundary crossing stays lexically visible at the call site instead of hiding
                // inside Graph.
                long seedsPresentInTwoHop = countPresent(afterTwoHops.materializeRows(), seeds);
                Mask excludedSeeds = store.importRows(seeds);
                Graph afterMinus = afterTwoHops.minus(excludedSeeds);
                c.eq("minus(importRows(seedRows)) removes exactly the seed rows that were"
                                + " present in the 2-hop set (and nothing else)",
                        afterTwoHops.count() - seedsPresentInTwoHop, afterMinus.count());
                c.that("minus(importRows(seedRows)) leaves zero seed rows behind",
                        countPresent(afterMinus.materializeRows(), seeds) == 0);
            }
        }
    }

    /**
     * BFS one hop, decoding via the per-store {@link FacetMatchView} bitset: one
     * {@link RowStore#facetMatches} call up front (independent of how many source rows there are),
     * then for each source row only its matched facet indices are decoded via
     * {@link RowStore#payloadHi32At}/{@link RowStore#payloadLow64At}. This is the shape a
     * D1a-style {@code Graph.hop()} is expected to take internally (docs/abi.md §12 decode
     * convention): a facet is a structured edge iff its classid matches AND
     * {@code payloadHi32At == 0}; the target row is {@code payloadLow64At}. Never calls into
     * {@code Graph}.
     *
     * <p><strong>Preserved VERBATIM</strong> from the pre-migration suite (frozen spec §1.6): this
     * is the scalar reference oracle, not the implementation under test, and it is never
     * re-expressed in terms of {@link Mask}.
     */
    private static long[] bfsHopViaFacetMatches(RowStore store, long[] fromRows, int edgeClassid) {
        FacetMatchView view = store.facetMatches(edgeClassid);
        boolean[] seen = new boolean[(int) store.rowCount()];
        List<Long> out = new ArrayList<>();
        for (long row : fromRows) {
            int bits = view.matchesOf(row);
            while (bits != 0) {
                int facetIdx = Integer.numberOfTrailingZeros(bits);
                bits &= bits - 1;   // clear the lowest set bit
                FacetId facet = FacetId.of(facetIdx);
                if (store.payloadHi32At(row, facet) != 0) {
                    continue;   // not a structured edge for this facet — ordinary noise payload
                }
                long target = store.payloadLow64At(row, facet);
                if (target >= 0 && target < store.rowCount() && !seen[(int) target]) {
                    seen[(int) target] = true;
                    out.add(target);
                }
            }
        }
        return toArray(out);
    }

    /**
     * BFS one hop, decoding by scanning all 32 facets per source row via
     * {@link RowStore#classidAt} directly, rather than trusting the {@link FacetMatchView} bitset
     * — a genuinely different code path from {@link #bfsHopViaFacetMatches}, so the two agreeing
     * is real cross-checking rather than two names for the same computation. Never calls into
     * {@code Graph} and never calls {@link RowStore#facetMatches}.
     *
     * <p><strong>Preserved VERBATIM</strong> from the pre-migration suite (frozen spec §1.6): this
     * is the scalar reference oracle, not the implementation under test.
     */
    private static long[] bfsHopViaClassidScan(RowStore store, long[] fromRows, int edgeClassid) {
        boolean[] seen = new boolean[(int) store.rowCount()];
        List<Long> out = new ArrayList<>();
        for (long row : fromRows) {
            for (int f = 0; f < FACETS_PER_ROW; f++) {
                FacetId facet = FacetId.of(f);
                if (store.classidAt(row, facet) != edgeClassid) {
                    continue;
                }
                if (store.payloadHi32At(row, facet) != 0) {
                    continue;
                }
                long target = store.payloadLow64At(row, facet);
                if (target >= 0 && target < store.rowCount() && !seen[(int) target]) {
                    seen[(int) target] = true;
                    out.add(target);
                }
            }
        }
        return toArray(out);
    }

    private static long[] toArray(List<Long> values) {
        long[] arr = new long[values.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = values.get(i);
        }
        return arr;
    }

    private static Set<Long> toSet(long[] rows) {
        Set<Long> set = new HashSet<>();
        for (long row : rows) {
            set.add(row);
        }
        return set;
    }

    private static boolean sameRowSet(long[] a, long[] b) {
        return toSet(a).equals(toSet(b));
    }

    private static long countPresent(long[] haystack, long[] needles) {
        Set<Long> hay = toSet(haystack);
        long count = 0;
        for (long needle : needles) {
            if (hay.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 2. Crossings: proportional to hops/ops, independent of row count and frontier size
    //    (Diagnostics.crossings()) -- MASK-NATIVE RE-PIN.
    //
    // The old "first hop pays 2, every later hop pays 1" story does not carry over: that
    // asymmetry came entirely from RowStore's lazily-resolved raw lane-0 window (rawLane()),
    // which only the OLD per-row Java decode loop ever touched. The new Graph.hop() never reads a
    // row directly -- it is one RowStore.hop() call, fully decoded natively -- so there is no
    // Java-side warmup left to observe. Every exact numeric literal below is a PREDICTION, marked
    // for the orchestrator to re-measure and correct (see the class-level constants above for the
    // reasoning behind each guess). The structural, prediction-independent relations are the real
    // falsifying power here: they hold regardless of which exact constant the orchestrator lands
    // on, which is why they are asserted as equalities between two MEASURED quantities rather
    // than against a literal.
    // ------------------------------------------------------------------

    private static void checkCrossingsProportionalToHops(Checks c) {
        c.section("crossings are proportional to hops/ops, independent of row count and frontier"
                + " size (Diagnostics.crossings()) -- mask-native re-pin");

        long[] seeds = seedRows();

        long deltaAt2000 = measureOneHopCrossingDelta(HOP_N, HOP_SEED, GATE_MASK, RADIUS, seeds);
        long deltaAt500 = measureOneHopCrossingDelta(SECOND_N, HOP_SEED, GATE_MASK, RADIUS, seeds);

        // A "small constant" sanity bound: definitely excludes a design that crosses once per row
        // of the (2000-row) store, and is generous enough not to false-fail on a few bookkeeping
        // crossings this suite cannot predict without running it. The real falsifying power is in
        // the equality checks below, not this bound.
        c.that("a single hop costs a small, non-zero number of crossings (observed "
                        + deltaAt2000 + ")", deltaAt2000 > 0 && deltaAt2000 <= 50);
        c.eq("the per-hop crossing cost is IDENTICAL at n=2000 and n=500 — crossings do not"
                        + " scale with row count", deltaAt2000, deltaAt500);

        try (RowStore store = RowStore.openWithEdges(HOP_N, HOP_SEED, Edge.KNOWS, GATE_MASK,
                RADIUS)) {
            // The seed Mask is created OUTSIDE every measured window below, so from(Mask)'s own
            // zero-crossing composition never pollutes a hop/count/minus/materialize measurement.
            Mask seedMask = store.importRows(seeds);
            Graph seeded = Graph.open(store).from(seedMask);

            long beforeFirstHop = Diagnostics.crossings();
            Graph afterOneHop = seeded.hop(Edge.KNOWS);
            long firstHopCost = Diagnostics.crossings() - beforeFirstHop;

            long beforeSecondHop = Diagnostics.crossings();
            Graph afterTwoHops = afterOneHop.hop(Edge.KNOWS);
            long secondHopCost = Diagnostics.crossings() - beforeSecondHop;

            long beforeThirdHop = Diagnostics.crossings();
            Graph afterThreeHops = afterTwoHops.hop(Edge.KNOWS);
            long thirdHopCost = Diagnostics.crossings() - beforeThirdHop;

            c.eq("hop() costs exactly " + PREDICTED_HOP_CROSSINGS + " crossing(s), flat -- no"
                            + " lifecycle-warmup asymmetry survives the migration (contrast the"
                            + " retired 2/1/1 pattern this section used to pin)",
                    PREDICTED_HOP_CROSSINGS, firstHopCost);
            c.eq("the second hop (19 source rows) costs identically to the first (10 source"
                            + " rows) -- cost tracks hop count, not frontier size",
                    firstHopCost, secondHopCost);
            c.eq("the third hop (29+ source rows) ALSO costs identically -- a second, different"
                            + " frontier size confirms the relation is not a coincidence between"
                            + " exactly two measurements",
                    secondHopCost, thirdHopCost);

            // count(): Mask.count() is Engine.maskCount() -- a single, unconditional native
            // popcount, so this is NOT a "first call vs steady state" story the way
            // materializeRows() below is.
            long beforeCount = Diagnostics.crossings();
            long countValue = afterThreeHops.count();
            long countCost = Diagnostics.crossings() - beforeCount;
            c.eq("count() costs exactly " + PREDICTED_COUNT_CROSSINGS + " crossing -- one"
                            + " native popcount, every call",
                    PREDICTED_COUNT_CROSSINGS, countCost);
            c.that("count() returned a real value while we were at it (sanity, not a repeat of"
                            + " the anti-vacuity section)", countValue > 0);

            // minus(Mask): predicted create+andnot, matching the maskOfFacetClass
            // create-then-operate shape (see the class-level constant's reasoning) -- called
            // against a freshly imported Mask so the import's own cost is excluded from this
            // window.
            Mask excluded = store.importRows(seeds);
            long beforeMinus = Diagnostics.crossings();
            Graph afterMinus = afterThreeHops.minus(excluded);
            long minusCost = Diagnostics.crossings() - beforeMinus;
            c.eq("minus(Mask) costs exactly " + PREDICTED_MINUS_CROSSINGS + " crossing(s)",
                    PREDICTED_MINUS_CROSSINGS, minusCost);

            // materializeRows(): "describe cached per Mask; zero crossings steady-state" (spec
            // §3.5) -- so the FIRST call on a given Mask should cost something, the SECOND call
            // on the very same Mask should cost nothing further.
            long beforeFirstMaterialize = Diagnostics.crossings();
            long[] materializedOnce = afterMinus.materializeRows();
            long firstMaterializeCost = Diagnostics.crossings() - beforeFirstMaterialize;
            long beforeSecondMaterialize = Diagnostics.crossings();
            long[] materializedAgain = afterMinus.materializeRows();
            long secondMaterializeCost = Diagnostics.crossings() - beforeSecondMaterialize;

            c.eq("materializeRows() first call costs " + PREDICTED_MATERIALIZE_FIRST_CROSSINGS
                            + " crossing (the one-time describe of this Mask's word lane)",
                    PREDICTED_MATERIALIZE_FIRST_CROSSINGS, firstMaterializeCost);
            c.eq("materializeRows() SECOND call on the SAME Mask costs zero further crossings"
                            + " -- steady-state, per spec §3.5",
                    0, secondMaterializeCost);
            c.that("both materializeRows() calls agree on content (the describe caching did not"
                            + " change the answer)",
                    sameRowSet(materializedOnce, materializedAgain));

            // from()/importRows(): row-count independence, mirroring the n=2000-vs-n=500
            // store-wide check above but for the IMPORT path itself -- a per-row-write native
            // implementation would show up here as a cost that scales with how many rows are
            // imported. This is deliberately the PREDICTION-INDEPENDENT half of the import story
            // (see the class-level constants' note on why the exact "amortized" shape could not
            // be pinned without running the suite).
            long[] smallSeed = Arrays.copyOf(seeds, 3);
            long[] largeSeed = afterThreeHops.materializeRows(); // 29+ rows, much bigger than 3

            long beforeSmallImport = Diagnostics.crossings();
            Mask smallImported = store.importRows(smallSeed);
            long smallImportCost = Diagnostics.crossings() - beforeSmallImport;

            long beforeLargeImport = Diagnostics.crossings();
            Mask largeImported = store.importRows(largeSeed);
            long largeImportCost = Diagnostics.crossings() - beforeLargeImport;

            c.eq("importRows()'s crossing cost does not depend on how many rows are imported (3"
                            + " vs " + largeSeed.length + ")",
                    smallImportCost, largeImportCost);
            c.eq("importRows() costs exactly " + PREDICTED_IMPORT_CROSSINGS + " crossing(s) --"
                            + " mask create; word writes happen in-process, not per row",
                    PREDICTED_IMPORT_CROSSINGS, smallImportCost);

            smallImported.close();
            largeImported.close();
            seedMask.close();
            // afterOneHop/afterTwoHops/afterThreeHops/afterMinus/excluded/seeded are deliberately
            // left open here -- intermediate chain steps hold their masks until closed or the
            // store closes (class javadoc's "Lifecycle" section); the store's own close() below
            // is exactly that release point, "the sanctioned currency" this suite is not trying
            // to additionally exercise.
        }

        c.note("measured per-hop crossing cost: " + deltaAt2000 + ". Unlike the retired long[]/"
                + "TreeSet implementation, the mask-native hop has no Java-side lazy warmup left"
                + " to amortize -- every hop after from()/importRows() costs the identical, flat"
                + " amount, confirmed above across three consecutive hops with three different"
                + " frontier sizes (10, 19, 29+ rows) and independently across two store sizes"
                + " (2000 and 500 rows).");
    }

    /**
     * Opens a fresh store, seeds a fresh {@code Graph} via {@link Graph#from(Mask)} (zero
     * crossings of its own), then measures {@code hop()}'s own crossing cost in isolation.
     *
     * <p>Unlike the pre-migration version of this helper, this one does <strong>not</strong> call
     * {@code count()} afterward "to force realization": {@link Graph#hop(int)} is unconditionally
     * eager under the mask-native design (one {@code RowStore.hop(...)} call, synchronously), and
     * {@link Graph#count()} itself now costs a real crossing (see the dedicated {@code count()}
     * measurement above) — calling it inside this window would silently fold two different costs
     * into one number.
     */
    private static long measureOneHopCrossingDelta(long nRows, long seed, long gateMask,
            int radius, long[] seeds) {
        try (RowStore store = RowStore.openWithEdges(nRows, seed, Edge.KNOWS, gateMask, radius)) {
            Mask seedMask = store.importRows(seeds);
            Graph seeded = Graph.open(store).from(seedMask);
            long before = Diagnostics.crossings();
            Graph afterHop = seeded.hop(Edge.KNOWS);
            long after = Diagnostics.crossings();
            return after - before;
        }
    }

    // ------------------------------------------------------------------
    // 3. hop(int, WideFieldMask) parity: the explicit-participation overload must agree with the
    //    convenience hop(int) overload when given WideFieldMask.allFacets() -- both content and
    //    crossing cost.
    // ------------------------------------------------------------------

    private static void checkWideFieldMaskHopParity(Checks c) {
        c.section("hop(edgeClassid, WideFieldMask.allFacets()) agrees with hop(edgeClassid) --"
                + " content and crossing cost");

        long[] seeds = seedRows();
        try (RowStore store = RowStore.openWithEdges(HOP_N, HOP_SEED, Edge.KNOWS, GATE_MASK,
                RADIUS)) {
            Mask seedMaskA = store.importRows(seeds);
            Mask seedMaskB = store.importRows(seeds);

            Graph seededA = Graph.open(store).from(seedMaskA);
            Graph seededB = Graph.open(store).from(seedMaskB);

            Graph viaConvenience = seededA.hop(Edge.KNOWS);
            Graph viaExplicit = seededB.hop(Edge.KNOWS, WideFieldMask.allFacets());

            c.that("hop(edgeClassid) and hop(edgeClassid, allFacets()) reach the identical row"
                            + " SET",
                    sameRowSet(viaConvenience.materializeRows(), viaExplicit.materializeRows()));
            c.eq("hop(edgeClassid) and hop(edgeClassid, allFacets()) agree on count()",
                    viaConvenience.count(), viaExplicit.count());

            long beforeConvenience = Diagnostics.crossings();
            Graph again1 = seededA.hop(Edge.KNOWS);
            long convenienceCost = Diagnostics.crossings() - beforeConvenience;

            long beforeExplicit = Diagnostics.crossings();
            Graph again2 = seededB.hop(Edge.KNOWS, WideFieldMask.allFacets());
            long explicitCost = Diagnostics.crossings() - beforeExplicit;

            c.eq("the two overloads cost the same number of crossings -- same underlying"
                            + " RowStore.hop(...) native op either way",
                    convenienceCost, explicitCost);

            // again1/again2 exist purely so the crossing-cost snapshots above bracket a real
            // hop() call each; their content was already proven identical to viaConvenience/
            // viaExplicit above (same seed, same edge classid, same store), so re-asserting
            // anything about them here would only restate "a long is never negative" -- not a
            // real check. Deliberately not asserted further.
            c.note("re-hop crossing costs: convenience=" + convenienceCost + ", explicit="
                    + explicitCost + " (again1.count()=" + again1.count() + ", again2.count()="
                    + again2.count() + ")");
        }
    }

    // ------------------------------------------------------------------
    // 4. Anti-vacuity: seed / 1-hop / 2-hop sizes are three different, non-empty, non-total sizes.
    // ------------------------------------------------------------------

    private static void checkAntiVacuity(Checks c) {
        c.section("anti-vacuity guards on the pinned fixture");

        long[] seeds = seedRows();
        try (RowStore store = RowStore.openWithEdges(HOP_N, HOP_SEED, Edge.KNOWS, GATE_MASK,
                RADIUS);
                Graph graph = Graph.open(store)) {
            Graph afterFrom = graph.from(seeds);
            Graph afterOneHop = afterFrom.hop(Edge.KNOWS);
            Graph afterTwoHops = afterOneHop.hop(Edge.KNOWS);

            long seedCount = afterFrom.count();       // 10
            long oneHopCount = afterOneHop.count();    // 19
            long twoHopCount = afterTwoHops.count();   // 29

            // Six independent boolean assertions, one per inequality, so a failure names exactly
            // which relationship broke rather than one combined condition hiding which half failed.
            c.that("seed count (" + seedCount + ") != 1-hop count (" + oneHopCount + ")",
                    seedCount != oneHopCount);
            c.that("1-hop count (" + oneHopCount + ") != 2-hop count (" + twoHopCount + ")",
                    oneHopCount != twoHopCount);
            c.that("1-hop count is non-empty (> 0)", oneHopCount > 0);
            c.that("2-hop count is non-empty (> 0)", twoHopCount > 0);
            c.that("1-hop count is not the total row count (< " + HOP_N + ")",
                    oneHopCount < HOP_N);
            c.that("2-hop count is not the total row count (< " + HOP_N + ")",
                    twoHopCount < HOP_N);
        }
    }

    // ------------------------------------------------------------------
    // 5. The mask-native structural surface (G1/G8) -- over Graph AND Edge.
    //
    // Replaces the pre-migration "zero serialization" section, which opened with a vacuous
    // literal-true prose assertion (c.that(..., true) -- cannot fail, per this repo's own
    // falsifiability discipline) backed by a genuinely falsifiable reflective arm. The vacuous
    // half is deleted outright; the reflective arm is kept and extended to the full G1 gate
    // (field check + parameter rule), not just the return-type check it used to be alone.
    // ------------------------------------------------------------------

    private static void checkMaskNativeSurface(Checks c) {
        c.section("G1/G8: the mask-native surface guard -- no row-id collection fields, only"
                + " from(...) takes a long[]/long... parameter, only materialize*(...) returns"
                + " long[] -- over Graph AND Edge. (This subsumes the old zero-serialization"
                + " check: byte[]/Map/serialization types were never, and still are not, in the"
                + " allowed return set.)");

        for (Class<?> type : new Class<?>[] {Graph.class, Edge.class}) {
            checkNoRowIdCollectionFields(c, type);
            checkOnlyFromTakesRowIdArrays(c, type);
            checkReturnTypesAreMaskNative(c, type);
        }
    }

    /** G1, field half: zero fields typed {@code long[]}/{@code Long[]}/any {@code Collection}. */
    private static void checkNoRowIdCollectionFields(Checks c, Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            Class<?> ft = f.getType();
            boolean forbidden = ft == long[].class || ft == Long[].class
                    || java.util.Collection.class.isAssignableFrom(ft);
            c.that(type.getSimpleName() + "." + f.getName() + " is not a long[]/Long[]/"
                            + "Collection field (was " + ft.getSimpleName() + ") -- row"
                            + " populations are a native Mask, never a Java-heap row-id"
                            + " collection",
                    !forbidden);
        }
    }

    /** G1, parameter half: the ONLY public method with a {@code long[]}/{@code long...} PARAMETER
     * is {@code from}. */
    private static void checkOnlyFromTakesRowIdArrays(Checks c, Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            boolean hasLongArrayParam = false;
            for (Class<?> pt : m.getParameterTypes()) {
                if (pt == long[].class) {
                    hasLongArrayParam = true;
                    break;
                }
            }
            if (hasLongArrayParam) {
                c.that(type.getSimpleName() + "." + m.getName() + "(...) is the only kind of"
                                + " method allowed to take a long[]/long... parameter, and its"
                                + " name must be \"from\" (was \"" + m.getName() + "\")",
                        m.getName().equals("from"));
            }
        }
    }

    /** G8 (subsumes the old zero-serialization check) + the {@code materialize*} naming half of
     * G1: returns ∈ {@code {type, long, void, Mask, WideFieldMask}} ∪ {@code {long[] ONLY from
     * methods named materialize*}}. */
    private static void checkReturnTypesAreMaskNative(Checks c, Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;   // wait()/notify()/getClass()/... are not this class's surface
            }
            String name = m.getName();
            if ((name.equals("toString") || name.equals("equals") || name.equals("hashCode"))
                    && switch (name) {
                        case "toString", "hashCode" -> m.getParameterCount() == 0;
                        default -> m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == Object.class;
                    }) {
                continue;   // an Object-defined contract override, not part of the audited surface
            }
            Class<?> ret = m.getReturnType();
            boolean isMaterializer = name.startsWith("materialize");
            boolean acceptable =
                    ret == type || ret == long.class || ret == void.class
                            || ret == Mask.class || ret == WideFieldMask.class
                            || (ret == long[].class && isMaterializer);
            c.that(type.getSimpleName() + "." + name + "(...) returns " + type.getSimpleName()
                            + "/long/void/Mask/WideFieldMask, or long[] ONLY when its name starts"
                            + " with \"materialize\" (was " + ret.getSimpleName() + ", method"
                            + " name \"" + name + "\")",
                    acceptable);
        }
    }

    // ------------------------------------------------------------------
    // 6. G9 flagship composition: from(maskOfFacetClass(...)).hop(...).count() end-to-end, with
    //    zero row-id values anywhere in the composition -- not even a long[] local.
    // ------------------------------------------------------------------

    private static void checkFlagshipMaskNativeComposition(Checks c) {
        c.section("G9 flagship: from(maskOfFacetClass(...)).hop(...).count() -- zero row-id"
                + " values anywhere in this test body except the independent oracle comparison"
                + " below");

        // Facet 7 mirrors RowStoreParityTest's own choice of a load-bearing facet index; classid
        // 5 is arbitrary and deliberately != Edge.KNOWS (0), so this seed predicate is visibly
        // unrelated to the edge classid the subsequent hop follows.
        final int seedFacet = 7;
        final int seedClassid = 5;

        try (RowStore store = RowStore.openWithEdges(HOP_N, HOP_SEED, Edge.KNOWS, GATE_MASK,
                RADIUS)) {
            // The independent oracle: recomputes the expected seed count using the same per-row
            // read surface bfsHopVia* already uses (exempt -- test/oracle code, G2's "Test/oracle
            // files exempt" carve-out). Deliberately a SCALAR running count, not even a long[] --
            // this method declares no long[] local anywhere, stronger than the gate requires.
            long expectedSeedCount = 0;
            for (long row = 0; row < store.rowCount(); row++) {
                if (store.classidAt(row, FacetId.of(seedFacet)) == seedClassid) {
                    expectedSeedCount++;
                }
            }
            c.that("the chosen facet/classid pair yields a non-empty, non-total seed on the"
                            + " pinned fixture (anti-vacuity -- a trivial 0 or all-rows seed"
                            + " would prove nothing about traversal)",
                    expectedSeedCount > 0 && expectedSeedCount < HOP_N);

            Graph graph = Graph.open(store)
                    .from(store.maskOfFacetClass(FacetId.of(seedFacet), seedClassid));
            c.eq("the flagship's seed count matches the independent per-row oracle",
                    expectedSeedCount, graph.count());

            Graph afterHop = graph.hop(Edge.KNOWS);
            long afterHopCount = afterHop.count();
            c.that("hop() from a predicate-born seed yields a non-empty result set"
                            + " (anti-vacuity)",
                    afterHopCount > 0);
            c.that("hop() from a predicate-born seed does not select every row"
                            + " (anti-vacuity)",
                    afterHopCount < HOP_N);
            c.note("flagship end-to-end, zero row-ids anywhere but the oracle comparison above:"
                    + " seed=" + expectedSeedCount + " rows -> hop -> " + afterHopCount
                    + " rows");
        }
    }

    // ------------------------------------------------------------------
    // 7. G3 allocation: Java-heap allocation of from->hop->hop->count is independent of frontier
    //    scale (materializeRows() excluded -- its javadoc states its O(n) cost explicitly, so it
    //    is the one sanctioned exception, exactly as in the root CLAUDE.md's forbidden-state
    //    list's own carve-out for named materialisers).
    // ------------------------------------------------------------------

    /**
     * Warm-up iterations before any allocation is measured. Fewer than {@code
     * TradesAllocationTest}'s 100: each iteration here does several real native round trips
     * (import + two hops + count, each a genuine {@code RowStore}/{@code Mask} call), not one
     * cheap repeated {@code count()} over an already-composed chain, so fewer iterations already
     * give the JIT and allocator enough to settle.
     */
    private static final int ALLOC_WARMUP_CALLS = 30;

    /** Measured iterations after warm-up; the reported floor is the minimum delta across these. */
    private static final int ALLOC_MEASURED_CALLS = 10;

    /**
     * Slack allowed between the small-frontier floor and the large-frontier floor. See {@code
     * TradesAllocationTest}'s identical field for the reasoning: not zero (real GC/JIT noise),
     * small next to the absolute ceiling below, large next to plausible jitter.
     */
    private static final long ALLOC_FRONTIER_INDEPENDENCE_SLACK_BYTES = 4_096;

    /**
     * Absolute backstop, mirroring {@code TradesAllocationTest}'s {@code ABSOLUTE_CEILING_BYTES}:
     * any implementation whose {@code from->hop->hop->count} allocates more than this, at ANY
     * frontier scale, fails outright.
     */
    private static final long ALLOC_ABSOLUTE_CEILING_BYTES = 64 * 1024;

    /**
     * Bytes this thread allocated while running {@code body}, minus the instrument's own
     * overhead — the {@code TradesAllocationTest}/{@code valhalla-lab} {@code
     * Lab.allocatedBytes} pattern, reapplied here with a provenance comment rather than a
     * dependency on either module's classpath (this consumer module cannot depend on either).
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

    private static void runFromHopHopCount(RowStore store, long[] seedRows) {
        try (Graph seeded = Graph.open(store).from(seedRows);
                Graph afterOneHop = seeded.hop(Edge.KNOWS);
                Graph afterTwoHops = afterOneHop.hop(Edge.KNOWS)) {
            afterTwoHops.count();
        }
    }

    private static long measureFrontierChainAllocationFloor(RowStore store, long[] seedRows) {
        for (int i = 0; i < ALLOC_WARMUP_CALLS; i++) {
            runFromHopHopCount(store, seedRows);
        }
        long floor = Long.MAX_VALUE;
        for (int i = 0; i < ALLOC_MEASURED_CALLS; i++) {
            long delta = AllocMeter.allocatedBytes(() -> runFromHopHopCount(store, seedRows));
            floor = Math.min(floor, delta);
        }
        return floor;
    }

    private static void checkAllocationIndependence(Checks c) {
        c.section("G3: from->hop->hop->count allocation is independent of frontier scale"
                + " (materializeRows() excluded -- it is the one named, O(n) exception)");

        long instrumentBaseline = AllocMeter.allocatedBytes(() -> {});
        c.note("instrument's own overhead on this VM: " + instrumentBaseline + " bytes (not"
                + " asserted to be exactly zero -- VMs differ -- only reported)");

        long[] smallSeed = seedRows(); // 10 rows, the pinned fixture
        long[] largeSeed = new long[500];
        for (int i = 0; i < largeSeed.length; i++) {
            largeSeed[i] = i;
        }

        try (RowStore store = RowStore.openWithEdges(HOP_N, HOP_SEED, Edge.KNOWS, GATE_MASK,
                RADIUS)) {
            long floorSmall = measureFrontierChainAllocationFloor(store, smallSeed);
            long floorLarge = measureFrontierChainAllocationFloor(store, largeSeed);

            c.note(smallSeed.length + "-row frontier: " + floorSmall + " bytes/call floor");
            c.note(largeSeed.length + "-row frontier: " + floorLarge + " bytes/call floor");

            c.that("the " + largeSeed.length + "-row floor is not meaningfully larger than the "
                            + smallSeed.length + "-row floor (allowed slack "
                            + ALLOC_FRONTIER_INDEPENDENCE_SLACK_BYTES + " bytes)",
                    floorLarge <= floorSmall + ALLOC_FRONTIER_INDEPENDENCE_SLACK_BYTES);
            c.that("the " + smallSeed.length + "-row floor is not meaningfully larger than the "
                            + largeSeed.length + "-row floor either (same slack, both"
                            + " directions -- a genuinely row-count-independent implementation"
                            + " clears this easily in either direction)",
                    floorSmall <= floorLarge + ALLOC_FRONTIER_INDEPENDENCE_SLACK_BYTES);

            c.atMost(smallSeed.length + "-row floor stays under " + ALLOC_ABSOLUTE_CEILING_BYTES
                            + " bytes", ALLOC_ABSOLUTE_CEILING_BYTES, floorSmall);
            c.atMost(largeSeed.length + "-row floor stays under " + ALLOC_ABSOLUTE_CEILING_BYTES
                            + " bytes", ALLOC_ABSOLUTE_CEILING_BYTES, floorLarge);
        }
    }

    // ------------------------------------------------------------------
    // 8. Edge is a schema, not an entity — the reflection guard (contract's own invitation,
    //    mirroring TradesParityTest's guard on Trade and BricksAuthTest's on Orders).
    // ------------------------------------------------------------------

    private static void checkEdgeIsASchema(Checks c) {
        c.section("Edge is a schema, not an entity — the reflection guard");

        c.eq("Edge declares zero public constructors",
                0, publicConstructorCount(Edge.class));
        c.eq("Edge declares zero non-static instance fields",
                0, instanceFieldCount(Edge.class));
        c.eq("Edge.KNOWS is the classid 0, matching the pinned fixture's edge_classid",
                0, Edge.KNOWS);

        // AssertionError specifically — matching TradesParityTest/BricksAuthTest's own guard — so
        // a typo producing e.g. a NullPointerException is caught rather than mistaken for the
        // guard working.
        c.throwsUp("forcing the private constructor accessible still cannot build an Edge",
                AssertionError.class, () -> {
                    try {
                        var ctor = Edge.class.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        ctor.newInstance();
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        if (cause instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(cause);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static int publicConstructorCount(Class<?> type) {
        return type.getConstructors().length;
    }

    private static int instanceFieldCount(Class<?> type) {
        int n = 0;
        for (var field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                n++;
            }
        }
        return n;
    }
}

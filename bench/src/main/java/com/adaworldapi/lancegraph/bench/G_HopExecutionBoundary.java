package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.FacetMatchView;
import com.adaworldapi.lancegraph.Mask;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;

import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <strong>Component G — the F-PARITY harness: does crossing into {@code lgj_hop} beat doing the
 * same hop in Java, and does the answer move with scale or frontier density?</strong>
 *
 * <p>Pre-registered by the D-LGJ-W8 spec twice over: §3.8 names it as the promotion trigger for
 * anything wider than the shipped kernel placement (<em>"native {@code lgj_hop} vs the preserved
 * Java scalar arm vs an optional Vector-API arm, at ≥2 row counts and ≥2 frontier densities.
 * Promotion/demotion of the placement follows that measurement — never taste"</em>), and §12 lists
 * <strong>F-PARITY</strong> with the scope note <em>"W8 seeds the HARNESS only … the falsifier
 * itself gates the COMPUTE wave, not W8"</em>. §3.8 declares it non-gating. So this class is
 * deliberately a measuring instrument, not a gate: it asserts agreement, never a winner.
 *
 * <h2>The arms</h2>
 *
 * <p>Same three-arm shape as {@link C_ExecutionBoundary} and {@link F_RowStoreFacetScan}:
 *
 * <ul>
 *   <li>{@code native_hop} — one {@link RowStore#hop(int, Mask)} crossing, mask in, mask out. The
 *       whole composition stays native; no row id is ever produced.
 *   <li>{@code java_scalar_facetMatches} — the {@link FacetMatchView} bitset path.
 *   <li>{@code java_scalar_classidScan} — 32 {@link RowStore#classidAt} reads per source row.
 * </ul>
 *
 * <p><strong>The two scalar arms are preserved verbatim from {@code GraphHopTest}</strong>
 * ({@code bfsHopViaFacetMatches} / {@code bfsHopViaClassidScan}), which the frozen spec §1.6 keeps
 * as the scalar reference oracle. Reusing them rather than writing a third is the point: they are
 * already cross-checked against each other and against the native path in a suite that runs on
 * every change, so a divergence here is a real divergence and not a fresh transcription bug. They
 * are genuinely different code paths from one another (bitset vs. per-facet classid scan), so the
 * two agreeing is real cross-checking rather than two names for one computation.
 *
 * <p>The <strong>Vector-API arm §3.8 calls "optional" is deliberately absent</strong>, and its
 * absence is the honest state rather than an oversight: a Java Vector-API hop would have to
 * re-express the structured-edge decode ({@code hi32 == 0} marker, {@code payloadLow64} target,
 * bounds check, dedup into a set) over a 16-byte-strided facet layout — that is a second
 * implementation of the kernel, not a bench arm, and building one speculatively before the scalar
 * arms have been measured against native would be exactly the taste-before-measurement §3.8
 * forbids. The slot is named here so a later wave adds it against numbers.
 *
 * <h2>What the sweep varies, and why both axes</h2>
 *
 * <p>{@code rows} sets the population; {@code frontierPct} sets how much of it the hop starts
 * from. Both matter and they pull in different directions: the native arm's per-call cost is
 * dominated by the fixed crossing plus a mask-width sweep (∝ rows), while the scalar arms' cost is
 * dominated by per-source-row work (∝ frontier). A single-axis sweep could therefore report a
 * winner that is an artifact of the one ratio it happened to sample — which is the whole reason
 * §3.8 asks for two of each.
 *
 * <h2>Disclosed asymmetries</h2>
 *
 * <p>Following {@link F_RowStoreFacetScan}'s house rule of naming these rather than burying them:
 *
 * <ol>
 *   <li><strong>Both arms allocate, differently.</strong> {@code native_hop} allocates one
 *       destination mask per call ({@code Engine.createMask}); the scalar arms allocate a
 *       {@code boolean[rowCount]} seen-set plus a growing {@code ArrayList<Long>}. Neither is
 *       allocation-free, so this is not an allocation comparison — {@code GraphHopTest}'s G3 gate
 *       is where allocation is pinned, and it pins the composed native path flat against frontier
 *       size.
 *   <li><strong>The returned shapes differ by construction.</strong> Native returns a {@link Mask}
 *       and is counted with {@link Mask#count()}; the scalar arms return a {@code long[]} of row
 *       ids. That difference IS the thing under measurement (mask-native vs. row-id materialising),
 *       so equalising it would delete the comparison. Both are reduced to a count for the
 *       {@code @Benchmark} return so JMH sees comparable work.
 *   <li><strong>The frontier is built once, in {@code @Setup}.</strong> {@link
 *       RowStore#importRows(long...)} — the ONE sanctioned import — is called outside the timed
 *       path for the native arm's source mask, so no arm pays for constructing its own input.
 * </ol>
 *
 * <p>Every number this class produces comes from {@code run.sh} and every table from
 * {@code summarise.sh}, per {@code bench/README.md}'s header rule: nothing here can drift from its
 * data because nothing here is hand-transcribed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class G_HopExecutionBoundary {

    /** Classid 0 — the same constant the graph consumer's {@code Edge.KNOWS} names. */
    private static final int EDGE_CLASSID = 0;

    /** {@code 0} is the densest edge-gate setting (see {@code RowStore.openWithEdges}). */
    private static final long EDGE_GATE_MASK = 0x0L;

    private static final int EDGE_RADIUS = 25;
    private static final long SEED = 0xF00D_CAFEL;
    private static final int FACETS_PER_ROW = 32;

    @Param({"4096", "65536"})
    public int rows;

    /** Frontier size as a percentage of {@code rows} — the second axis §3.8 requires. */
    @Param({"1", "25"})
    public int frontierPct;

    private RowStore store;
    private long[] fromRows;
    private Mask src;

    @Setup(Level.Trial)
    public void setUp() {
        if (!NativeRuntime.isAvailable()) {
            throw new IllegalStateException("native library unavailable: "
                    + NativeRuntime.unavailableReason().getMessage()
                    + "\nbuild it with:  cd native/lgj-abi && "
                    + "CARGO_TARGET_DIR=<repo>/target cargo build --release");
        }
        store = RowStore.openWithEdges(rows, SEED, EDGE_CLASSID, EDGE_GATE_MASK, EDGE_RADIUS);

        int frontier = Math.max(1, (int) ((long) rows * frontierPct / 100L));
        fromRows = new long[frontier];
        // A deterministic spread rather than a prefix: a contiguous 0..n block would give the
        // scalar arms an unrepresentatively cache-friendly source order and flatter the wrong arm.
        long stride = Math.max(1L, (long) rows / frontier);
        for (int i = 0; i < frontier; i++) {
            fromRows[i] = (i * stride) % rows;
        }
        src = store.importRows(fromRows);

        crossCheck();
    }

    /**
     * All three arms must agree on the destination SET before anything is timed — the same
     * discipline {@link RowStoreData} applies to Component F's arms. A benchmark comparing three
     * implementations that compute different answers measures nothing.
     */
    private void crossCheck() {
        long[] viaBitset = bfsHopViaFacetMatches(store, fromRows, EDGE_CLASSID);
        long[] viaScan = bfsHopViaClassidScan(store, fromRows, EDGE_CLASSID);
        Arrays.sort(viaBitset);
        Arrays.sort(viaScan);
        if (!Arrays.equals(viaBitset, viaScan)) {
            throw new IllegalStateException("the two scalar oracles disagree: "
                    + viaBitset.length + " vs " + viaScan.length + " targets");
        }

        long nativeCount;
        try (Mask dst = store.hop(EDGE_CLASSID, src)) {
            nativeCount = dst.count();
        }
        if (nativeCount != viaBitset.length) {
            throw new IllegalStateException("native hop and the scalar oracle disagree: "
                    + nativeCount + " vs " + viaBitset.length + " targets"
                    + " (rows=" + rows + ", frontierPct=" + frontierPct + ")");
        }
        // Anti-vacuity: a hop that reached nothing would let all three arms "agree" on emptiness
        // and would time an empty loop in every arm.
        if (nativeCount == 0) {
            throw new IllegalStateException("the fixture produced an empty hop at rows=" + rows
                    + ", frontierPct=" + frontierPct + " — nothing would be measured");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (src != null) {
            src.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Benchmark
    public long native_hop() {
        try (Mask dst = store.hop(EDGE_CLASSID, src)) {
            return dst.count();
        }
    }

    @Benchmark
    public long java_scalar_facetMatches() {
        return bfsHopViaFacetMatches(store, fromRows, EDGE_CLASSID).length;
    }

    @Benchmark
    public long java_scalar_classidScan() {
        return bfsHopViaClassidScan(store, fromRows, EDGE_CLASSID).length;
    }

    // ---- the preserved scalar oracles (GraphHopTest, frozen spec §1.6) --------------------

    private static long[] bfsHopViaFacetMatches(RowStore store, long[] fromRows, int edgeClassid) {
        FacetMatchView view = store.facetMatches(edgeClassid);
        boolean[] seen = new boolean[(int) store.rowCount()];
        List<Long> out = new ArrayList<>();
        for (long row : fromRows) {
            int bits = view.matchesOf(row);
            while (bits != 0) {
                int facetIdx = Integer.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                FacetId facet = FacetId.of(facetIdx);
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

    private static long[] toArray(List<Long> xs) {
        long[] out = new long[xs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = xs.get(i);
        }
        return out;
    }
}

package com.adaworldapi.lancegraph.lab;

/**
 * Truths (b) and (c) — <em>representation</em>. What does the same source actually become?
 *
 * <p>Four questions, each measured by the instrument that can least easily be fooled:
 *
 * <ol>
 *   <li><strong>Does constructing one cost a heap object?</strong> — allocated bytes, not timing.
 *   <li><strong>Is an array of them flattened?</strong> — the VM's own {@code isFlatArray} where it
 *       exists, plus the array's measured footprint either way.
 *   <li><strong>Is a field of one flattened into its container?</strong> — allocated bytes for the
 *       container, which changes by exactly the header+pointer cost when it is not.
 *   <li><strong>Does passing one to a method cost anything?</strong> — allocated bytes across a
 *       call chain deep enough that escape analysis has to give up.
 * </ol>
 *
 * <p>Every count below is per {@link #N} operations, so a per-instance number can be divided out
 * and compared against the theoretical object size (16-byte header + 4-byte int, padded to 16 for
 * a one-int record on a 64-bit VM with compressed oops).
 */
final class FootprintExperiment {

    private FootprintExperiment() {}

    /** Large enough that per-operation bytes resolve cleanly; small enough to stay in a young gen. */
    static final int N = 1_000_000;

    static void run() {
        Lab.section("(b)/(c) REPRESENTATION — allocation, arrays, fields, arguments");
        Lab.kv("platform", Platform.NAME);
        Lab.kv("allocation instrument baseline", Lab.bytes(Lab.allocationInstrumentBaseline()));
        Lab.kv("N (operations per measurement)", N);

        // ── 1. constructing a descriptor ─────────────────────────────────────────────────────
        // The array store is what forces the object to escape. Without it, escape analysis
        // deletes the allocation on BOTH platforms and the experiment measures nothing — which is
        // itself worth stating, because it is exactly why a fast microbenchmark is not evidence.
        Object[] sink = Platform.newLaneIdArray(N);
        long ctorBytes = Lab.allocatedBytes(() -> {
            for (int i = 0; i < N; i++) sink[i] = LaneId.of(i & 0xFFFF);
        });
        Lab.OBJ_SINK = sink;
        Lab.kv("construct N LaneId, store into array", Lab.bytes(ctorBytes));
        Lab.kv("  ... per LaneId", String.format("%.2f B", ctorBytes / (double) N));

        // Non-escaping variant, for contrast. If this is ~0 on the stable platform too, that is
        // escape analysis doing the value class's job for one particular loop — and the reason
        // the run script also reports a -XX:-DoEscapeAnalysis pass.
        long nonEscapingBytes = Lab.allocatedBytes(() -> {
            long acc = 0;
            for (int i = 0; i < N; i++) acc += LaneId.of(i).index();
            Lab.SINK = acc;
        });
        Lab.kv("construct N LaneId, never escaping", Lab.bytes(nonEscapingBytes));
        Lab.kv("  ... per LaneId", String.format("%.2f B", nonEscapingBytes / (double) N));

        // ── 2. array representation ──────────────────────────────────────────────────────────
        Object[] probe = Platform.newLaneIdArray(1024);
        Lab.kv("LaneId[1024] flatness", Platform.arrayFlatness(probe));
        long arrayBytes = Lab.allocatedBytes(() -> {
            Object[] a = Platform.newLaneIdArray(N);
            for (int i = 0; i < N; i++) a[i] = LaneId.of(i);
            Lab.OBJ_SINK = a;
        });
        Lab.kv("allocate+fill LaneId[N] (array + elements)", Lab.bytes(arrayBytes));
        Lab.kv("  ... per element", String.format("%.2f B", arrayBytes / (double) N));

        long emptyArrayBytes = Lab.allocatedBytes(() -> Lab.OBJ_SINK = Platform.newLaneIdArray(N));
        Lab.kv("bare LaneId[N] with no elements stored", Lab.bytes(emptyArrayBytes));
        Lab.kv("  ... per slot", String.format("%.2f B", emptyArrayBytes / (double) N));

        // ── 3. field flattening ──────────────────────────────────────────────────────────────
        Lab.kv("Descriptor kind", Containers.kind());
        Lab.kv("Descriptor fields null-restricted", Containers.fieldsAreNullRestricted());
        Object[] descSink = new Object[N];
        long descBytes = Lab.allocatedBytes(() -> {
            for (int i = 0; i < N; i++) descSink[i] = Containers.make(i & 7, i & 3);
        });
        Lab.OBJ_SINK = descSink;
        Lab.kv("construct N Descriptor (2 wrappers each)", Lab.bytes(descBytes));
        Lab.kv("  ... per Descriptor", String.format("%.2f B", descBytes / (double) N));

        // ── 4. method-passing ────────────────────────────────────────────────────────────────
        long passBytes = Lab.allocatedBytes(() -> {
            long acc = 0;
            for (int i = 0; i < N; i++) acc += consume(LaneId.of(i), Ordinal.of(i & 15));
            Lab.SINK = acc;
        });
        Lab.kv("pass 2 wrappers through 3 call levels", Lab.bytes(passBytes));
        Lab.kv("  ... per call", String.format("%.2f B", passBytes / (double) N));

        // ── timing, secondary ────────────────────────────────────────────────────────────────
        // Reported after the byte counts, and second in importance. A timing that disagrees with
        // the byte counts is a signal to distrust the timing, not the bytes.
        Object[] readArr = Platform.newLaneIdArray(65_536);
        for (int i = 0; i < 65_536; i++) readArr[i] = LaneId.of(i);
        Lab.OBJ_SINK = readArr;
        System.out.println(Lab.time("read 65,536 LaneId from array", 2_000, 51, () -> {
            long acc = 0;
            for (int i = 0; i < 65_536; i++) acc += ((LaneId) readArr[i]).index();
            Lab.SINK = acc;
        }));
    }

    // Three levels: shallow enough to inline, deep enough that a naive reading of "the JIT will
    // fix it" is not automatically true.
    private static long consume(LaneId l, Ordinal o) { return level2(l, o); }
    private static long level2(LaneId l, Ordinal o) { return level3(l, o); }
    private static long level3(LaneId l, Ordinal o) { return l.index() + o.value(); }
}

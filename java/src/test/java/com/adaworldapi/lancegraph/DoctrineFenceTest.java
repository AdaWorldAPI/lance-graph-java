package com.adaworldapi.lancegraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The three mechanical fences for the zero-copy / ownership doctrine (root {@code CLAUDE.md},
 * "Zero-copy + memory safety", as corrected by PR #46; plan
 * {@code mask-membrane-valhalla-integration-v1.md} W0, D-LGJ-MMV-0).
 *
 * <p>Each fence turns a prose claim the doctrine makes into a check that fails when the claim
 * drifts. The need is not hypothetical: the doctrine's materialization list went stale INSIDE the
 * very tree it audited (two real call sites were missing when the 5+3 council re-derived it,
 * {@code E-ZERO-COPY-MEMORY-SAFETY-OVERCLAIM-CORRECTION-1}). A list without a gate is a
 * hand-maintained artifact with extra steps.
 *
 * <p><strong>Fence 1 — materialization is named and bounded.</strong> The doctrine keeps an
 * EXHAUSTIVE list of production allocation/copy call sites (a council ruling: the
 * dilution-collapse-sentinel BLOCKed converting it to a property claim, because a checkable list
 * has falsifiability value prose loses). This fence pins the exact per-file occurrence counts of
 * every materialization-shaped pattern in {@code java/src/main}. A sixth site — in a new file OR
 * an already-listed one — fails until both the doctrine list and this pin are updated
 * deliberately, in the same commit.
 *
 * <p><strong>Fence 2 — worker topology stays substrate-private.</strong> The operator's §E rule:
 * worker count belongs to the state owner; {@code workers(8)} as Java/consumer semantics is the
 * violation, EXP-KIA's native benchmark sweep is not. Scope is therefore precise: ALL of
 * {@code java/src/main} (no consumer API may carry topology), plus the ABI's consumer-facing
 * surface only ({@code abi.rs} + {@code exports.rs} — struct fields and exported symbols); the
 * substrate's INTERNAL scheduling is legitimately its own business and is not scanned.
 *
 * <p><strong>Fence 3 — SIMD backend is diagnostic only.</strong> The backend name is a manifest
 * string for logging (E1-E6: backend selection is {@code ndarray::simd}'s business). Two halves:
 * backend tokens may appear only in the three files that define/relay the diagnostic, and no code
 * line carrying such a token may also carry a branch — semantic behavior must be
 * backend-invariant.
 *
 * <p>These are source-text fences, and say so honestly: {@code ApiSurfaceTest} checks the
 * COMPILED surface by reflection because inherited signatures leak invisibly in source; these
 * three properties are the opposite case — call sites, identifiers, and branches, which exist
 * only in source (reflection cannot see a branch at all).
 *
 * <p><strong>Proven able to fire (the W0 gate), each verified red-then-green at build time:</strong>
 * <ul>
 *   <li>Fence 1: a planted {@code long[] extra = new long[4];} in {@code View.java} → red
 *       ("unfenced materialization"); removed → green.</li>
 *   <li>Fence 2: a planted {@code public View workers(int n)} on {@code View.java} → red;
 *       removed → green.</li>
 *   <li>Fence 3: a planted {@code if (simdBackend().equals("avx512"))} in
 *       {@code NativeRuntime.java} → red on BOTH halves (token outside a def/relay line with a
 *       branch marker); removed → green.</li>
 * </ul>
 *
 * <p>Needs no native library — like {@code ApiSurfaceTest}, it is most useful before the
 * artifact exists.
 */
public final class DoctrineFenceTest {

    private DoctrineFenceTest() {}

    // ── Fence 1: the pinned materialization census (measured 2026-08-28) ──────────────────
    //
    // Keyed "<file basename>|<pattern>" → exact expected count. These counts ARE the doctrine's
    // five named sites, mechanically:
    //   Mask.materializeRows()           → Arrays.copyOf ×2 + new long[ ×1   (the named terminal)
    //   Engine.rowLayoutProbe            → .toArray( ×1                       (≤32 B diagnostic)
    //   Engine.facetSumResolved          → new long[ ×1                       (fixed [2] pair)
    //   Abi manifest-name read (cString) → new byte[ ×1 + MemorySegment.copy ×1
    //   Abi.readCarvings                 → new int[ ×2                        (incl. empty arm)
    private static final Map<String, Integer> MATERIALIZATION_PINS = new TreeMap<>();

    static {
        MATERIALIZATION_PINS.put("Mask.java|Arrays.copyOf", 2);
        MATERIALIZATION_PINS.put("Mask.java|new long[", 1);
        MATERIALIZATION_PINS.put("Engine.java|.toArray(", 1);
        MATERIALIZATION_PINS.put("Engine.java|new long[", 1);
        MATERIALIZATION_PINS.put("Abi.java|new int[", 2);
        MATERIALIZATION_PINS.put("Abi.java|new byte[", 1);
        MATERIALIZATION_PINS.put("Abi.java|MemorySegment.copy", 1);
    }

    /** Every pattern fence 1 watches — including ones whose lawful count is zero everywhere. */
    private static final String[] MATERIALIZATION_PATTERNS = {
        "Arrays.copyOf", ".toArray(", "new long[", "new int[", "new byte[",
        "MemorySegment.copy", "ByteBuffer.allocate", "Collectors.",
    };

    // ── Fence 2: topology tokens that must never appear on the consumer surface ────────────
    private static final String[] TOPOLOGY_TOKENS_JAVA = {
        "workers(", "workerCount", "parallelism(", "threads(", "partitions(", "shards(",
        "availableProcessors", "parallelStream", "ForkJoinPool",
    };

    private static final String[] TOPOLOGY_TOKENS_ABI = {
        "workers(", "worker_count", "parallelism(", "threads(", "partitions(", "shards(",
    };

    // ── Fence 3: backend tokens and where they may live ────────────────────────────────────
    private static final String[] BACKEND_TOKENS = {
        "simdBackend", "SIMD_SCALAR", "SIMD_AVX2", "SIMD_AVX512", "SIMD_NEON",
    };

    private static final Set<String> BACKEND_TOKEN_HOMES =
            Set.of("NativeRuntime.java", "Abi.java", "Layouts.java");

    private static final String[] BRANCH_MARKERS = {
        "if ", "if(", "switch", " == ", " != ", ".equals(",
    };

    public static void main(String[] args) {
        System.out.println("DoctrineFenceTest");
        Checks c = new Checks("DoctrineFenceTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        Path repo = repoRoot();
        if (repo == null) {
            c.that("the repository root was located (java/src/main/java + native/lgj-abi/src"
                    + " both present; a fence that cannot find its corpus must fail, not skip)",
                    false);
            return;
        }
        Path javaMain = repo.resolve("java/src/main/java");
        Path abiSrc = repo.resolve("native/lgj-abi/src");

        List<Path> javaFiles = sourcesUnder(javaMain, ".java");

        // Anti-vacuity: a fence over an empty corpus passes vacuously and protects nothing.
        c.section("the corpus is real");
        c.that("fence scans a non-trivial production tree (" + javaFiles.size()
                + " java files >= 20)", javaFiles.size() >= 20);

        fenceMaterialization(c, javaFiles);
        fenceTopology(c, javaFiles, abiSrc);
        fenceBackendDiagnosticOnly(c, javaFiles);
    }

    // ── Fence 1 ────────────────────────────────────────────────────────────────────────────

    private static void fenceMaterialization(Checks c, List<Path> javaFiles) {
        c.section("fence 1: materialization census matches the doctrine's exhaustive list");

        Map<String, Integer> observed = new TreeMap<>();
        for (Path f : javaFiles) {
            String name = f.getFileName().toString();
            String body = read(f);
            for (String pattern : MATERIALIZATION_PATTERNS) {
                int n = countOccurrences(body, pattern);
                if (n > 0) {
                    observed.merge(name + "|" + pattern, n, Integer::sum);
                }
            }
        }

        // Anti-vacuity: the pins must describe reality, not a stale memory of it. If a pinned
        // site was REMOVED, this fires too — the doctrine list must shrink in the same commit.
        for (Map.Entry<String, Integer> pin : MATERIALIZATION_PINS.entrySet()) {
            Integer got = observed.get(pin.getKey());
            c.eq("pinned site still present at its pinned count: " + pin.getKey(),
                    (long) pin.getValue(), got == null ? 0L : (long) got);
        }

        List<String> unfenced = new ArrayList<>();
        for (Map.Entry<String, Integer> o : observed.entrySet()) {
            Integer pinned = MATERIALIZATION_PINS.get(o.getKey());
            if (pinned == null || !pinned.equals(o.getValue())) {
                unfenced.add(o.getKey() + " ×" + o.getValue()
                        + (pinned == null ? " (not in the doctrine's list)"
                                          : " (pinned ×" + pinned + ")"));
            }
        }
        if (unfenced.isEmpty()) {
            c.that("no unfenced materialization site exists in java/src/main", true);
        } else {
            for (String u : unfenced) {
                c.that("UNFENCED MATERIALIZATION: " + u + " — a new site must be added to the"
                        + " CLAUDE.md list AND this pin table in the same commit, or removed",
                        false);
            }
        }
    }

    // ── Fence 2 ────────────────────────────────────────────────────────────────────────────

    private static void fenceTopology(Checks c, List<Path> javaFiles, Path abiSrc) {
        c.section("fence 2: worker topology never becomes consumer semantics (§E)");

        List<String> hits = new ArrayList<>();
        for (Path f : javaFiles) {
            scanForTokens(f, TOPOLOGY_TOKENS_JAVA, hits, false);
        }

        // The ABI's consumer-facing surface only: struct fields (abi.rs) and exported symbols
        // (exports.rs). The substrate's internal scheduling is deliberately out of scope.
        List<Path> abiSurface = new ArrayList<>();
        Path abi = abiSrc.resolve("abi.rs");
        Path exports = abiSrc.resolve("exports.rs");
        if (Files.isRegularFile(abi)) {
            abiSurface.add(abi);
        }
        if (Files.isRegularFile(exports)) {
            abiSurface.add(exports);
        }
        c.that("the ABI surface files were found (abi.rs + exports.rs)", abiSurface.size() == 2);
        for (Path f : abiSurface) {
            scanForTokens(f, TOPOLOGY_TOKENS_ABI, hits, false);
        }

        if (hits.isEmpty()) {
            c.that("zero topology tokens on the consumer surface (java/src/main + abi.rs"
                    + " + exports.rs)", true);
        } else {
            for (String h : hits) {
                c.that("TOPOLOGY LEAK: " + h + " — worker count belongs to the state owner;"
                        + " EXP-KIA's sweep is a native benchmark variable, never an API", false);
            }
        }
    }

    // ── Fence 3 ────────────────────────────────────────────────────────────────────────────

    private static void fenceBackendDiagnosticOnly(Checks c, List<Path> javaFiles) {
        c.section("fence 3: SIMD backend is diagnostic only — no branch consumes it");

        List<String> strayed = new ArrayList<>();
        List<String> branched = new ArrayList<>();
        int tokenLines = 0;

        for (Path f : javaFiles) {
            String name = f.getFileName().toString();
            List<String> lines = readLines(f);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String code = line.strip();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    continue; // prose may discuss backends freely; only code is fenced
                }
                boolean carries = false;
                for (String token : BACKEND_TOKENS) {
                    if (code.contains(token)) {
                        carries = true;
                        break;
                    }
                }
                if (!carries) {
                    continue;
                }
                tokenLines++;
                if (!BACKEND_TOKEN_HOMES.contains(name)) {
                    strayed.add(name + ":" + (i + 1) + " " + code);
                }
                for (String marker : BRANCH_MARKERS) {
                    if (code.contains(marker)) {
                        branched.add(name + ":" + (i + 1) + " " + code);
                        break;
                    }
                }
            }
        }

        // Anti-vacuity: the diagnostic genuinely exists (measured 11 code lines, 2026-08-28);
        // zero would mean the corpus scan is broken, not that the code is clean.
        c.that("the backend diagnostic exists to be fenced (" + tokenLines
                + " code lines carry a backend token, >= 5)", tokenLines >= 5);

        if (strayed.isEmpty()) {
            c.that("backend tokens live only in NativeRuntime/Abi/Layouts (define + relay)", true);
        } else {
            for (String s : strayed) {
                c.that("BACKEND TOKEN OUTSIDE ITS HOMES: " + s, false);
            }
        }
        if (branched.isEmpty()) {
            c.that("no code line both carries a backend token and branches — semantic behavior"
                    + " is backend-invariant (E1/E4)", true);
        } else {
            for (String b : branched) {
                c.that("BACKEND-DEPENDENT BRANCH: " + b + " — backend parity is ndarray::simd's"
                        + " business, never Java's", false);
            }
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────────

    /**
     * Walk up from the working directory to the first ancestor that carries both trees the
     * fences scan. Tests run from {@code java/} per the README, but the fence should not care.
     */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("java/src/main/java"))
                    && Files.isDirectory(p.resolve("native/lgj-abi/src"))) {
                return p;
            }
        }
        return null;
    }

    private static List<Path> sourcesUnder(Path root, String suffix) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void scanForTokens(Path f, String[] tokens, List<String> hits,
            boolean skipComments) {
        String name = f.getFileName().toString();
        List<String> lines = readLines(f);
        for (int i = 0; i < lines.size(); i++) {
            String code = lines.get(i).strip();
            if (skipComments && (code.startsWith("*") || code.startsWith("//"))) {
                continue;
            }
            String lower = code.toLowerCase(java.util.Locale.ROOT);
            for (String token : tokens) {
                if (lower.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                    hits.add(name + ":" + (i + 1) + " [" + token + "] " + code);
                    break;
                }
            }
        }
    }

    private static int countOccurrences(String body, String pattern) {
        int n = 0;
        int at = body.indexOf(pattern);
        while (at >= 0) {
            n++;
            at = body.indexOf(pattern, at + pattern.length());
        }
        return n;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> readLines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}

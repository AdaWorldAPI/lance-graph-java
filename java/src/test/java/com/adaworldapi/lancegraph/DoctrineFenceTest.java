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
 * COMPILED surface by reflection because inherited signatures leak invisibly in source; the
 * fenced properties are mostly the opposite case — call sites, identifiers, and branches, which
 * exist only in source (reflection cannot see a branch at all). The one property that IS visible
 * in the compiled surface — a topology-named public method — additionally gets a reflective arm
 * (fence 2b below), which no spelling can evade, so the lexical fence 2 is the tripwire and the
 * reflective arm is the proof for that half. Lexical fences remain tripwires, not proofs: they
 * catch the accidental violation and the lazy evasion, and the census pins force a determined
 * evader to touch the pin table in the same diff, which is what makes the evasion reviewable.
 *
 * <p><strong>Evasion hardening (Codex + CodeRabbit reviews, PR #48):</strong> all fences read
 * the SAME comment-filtered code lines ({@link #codeLines}), so prose in a javadoc or block
 * comment can neither add a materialization count nor report a topology leak; fence 1's
 * patterns and fence 2's tokens match whitespace-tolerantly ({@code new long [n]},
 * {@code .toArray (...)}, {@code Arrays .copyOf(...)}, {@code workers (int n)} all still hit);
 * fence 3's branch markers match over the WHITESPACE-CANONICALIZED line, and the marker set
 * covers ternaries and boolean operators ({@code ?}, {@code &&}, {@code ||}), so an in-place
 * rewrite like {@code return simdBackend()=="avx2" ? a() : b();} — which keeps the carrier
 * census unchanged — is caught by the marker half. And because fence 3's same-line branch check
 * can be laundered through a local ({@code String s = simdBackend();} then branching on
 * {@code s} a line later), fence 3 ALSO pins the exact per-file count of token-carrying code
 * lines — the laundering assignment itself carries the token, so a new carrier line anywhere
 * fails the census even when no branch shares its line. The stated residual: aliasing an
 * already-aliased value is beyond any source fence and is what review + the doctrine's wording
 * discipline remain for.
 *
 * <p><strong>Proven able to fire (the W0 gate), each verified red-then-green at build time:</strong>
 * <ul>
 *   <li>Fence 1: a planted {@code long[] extra = new long[4];} in {@code View.java} → red
 *       ("unfenced materialization"); removed → green.</li>
 *   <li>Fence 2: a planted {@code public View workers(int n)} on {@code View.java} → red;
 *       removed → green.</li>
 *   <li>Fence 2b: the same plant, spelled {@code workers (int n)}, → red on the REFLECTIVE arm
 *       (the compiled method name carries no spelling); removed → green.</li>
 *   <li>Fence 3: a planted {@code if (simdBackend().equals("avx512"))} in
 *       {@code NativeRuntime.java} → red on BOTH halves (token outside a def/relay line with a
 *       branch marker); removed → green.</li>
 *   <li>Fence 3 homes half: a stray {@code SIMD_AVX2} reference in {@code View.java} → red;
 *       removed → green.</li>
 *   <li>Fence 3 marker half alone: a pinned carrier line in {@code Layouts.java} rewritten
 *       in place to a ternary (census count unchanged) → red on the branch check; restored →
 *       green.</li>
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

    /**
     * Every pattern fence 1 watches — including ones whose lawful count is zero everywhere.
     * Display name → whitespace-tolerant regex, so {@code new long [n]} /
     * {@code Arrays .copyOf} / {@code .toArray (...)} spellings cannot evade (Codex, PR #48).
     * The {@code new} keyword keeps a real identifier boundary on BOTH sides ({@code \b} +
     * mandatory {@code \s+} before the type), which plain whitespace-stripping cannot express
     * ({@code return new int[0]} stripped becomes {@code returnnewint[0]} and the boundary is
     * destroyed — measured, not hypothetical).
     */
    private static final Map<String, java.util.regex.Pattern> MATERIALIZATION_PATTERNS =
            new TreeMap<>();

    static {
        MATERIALIZATION_PATTERNS.put("Arrays.copyOf",
                java.util.regex.Pattern.compile("\\bArrays\\s*\\.\\s*copyOf"));
        MATERIALIZATION_PATTERNS.put(".toArray(",
                java.util.regex.Pattern.compile("\\.\\s*toArray\\s*\\("));
        MATERIALIZATION_PATTERNS.put("new long[",
                java.util.regex.Pattern.compile("\\bnew\\s+long\\s*\\["));
        MATERIALIZATION_PATTERNS.put("new int[",
                java.util.regex.Pattern.compile("\\bnew\\s+int\\s*\\["));
        MATERIALIZATION_PATTERNS.put("new byte[",
                java.util.regex.Pattern.compile("\\bnew\\s+byte\\s*\\["));
        MATERIALIZATION_PATTERNS.put("MemorySegment.copy",
                java.util.regex.Pattern.compile("\\bMemorySegment\\s*\\.\\s*copy"));
        MATERIALIZATION_PATTERNS.put("ByteBuffer.allocate",
                java.util.regex.Pattern.compile("\\bByteBuffer\\s*\\.\\s*allocate"));
        MATERIALIZATION_PATTERNS.put("Collectors.",
                java.util.regex.Pattern.compile("\\bCollectors\\s*\\."));
    }

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

    /**
     * The exact per-home count of CODE lines carrying a backend token (measured 2026-08-28).
     * This is the anti-laundering half (Codex, PR #48): {@code String s = simdBackend();}
     * followed by branching on {@code s} evades the same-line branch check — but the
     * assignment line itself carries the token, so ANY new carrier line fails this census
     * until deliberately re-pinned. Rewriting a pinned line in place to add a branch is the
     * same-line check's job; the two nets overlap by design.
     */
    private static final Map<String, Integer> BACKEND_LINE_PINS = new TreeMap<>();

    static {
        BACKEND_LINE_PINS.put("NativeRuntime.java", 3); // simdBackend() def, its body, describe()
        BACKEND_LINE_PINS.put("Abi.java", 4);           // record components ×2, relay def + body
        BACKEND_LINE_PINS.put("Layouts.java", 4);       // the four SIMD_* manifest constants
    }

    /**
     * Matched against the WHITESPACE-CANONICALIZED carrier line (CodeRabbit, PR #48):
     * {@code simdBackend()=="avx2"} carries {@code ==} with no spaces, and a ternary rewrite of
     * a pinned line ({@code ... ? a() : b()}) keeps the carrier census unchanged, so the marker
     * set must see it. {@code ?} / {@code &&} / {@code ||} cannot appear on any lawful
     * define/relay line (a constant definition, a record component, a getter body), so they are
     * safe markers here even though they are not branches everywhere in Java.
     */
    private static final String[] BRANCH_MARKERS = {
        "if(", "switch", "==", "!=", ".equals(", "?", "&&", "||", "case",
        // Loop conditions are branches too: `while(accepts(simdBackend()))` consumes the
        // diagnostic without carrying any of the markers above (CodeRabbit round 5).
        "for(", "while(",
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
        fenceThreadSafetyJavadoc(c, javaMain);
        fenceDoctrineWording(c, repo);
        fenceTopology(c, javaFiles, abiSrc);
        fenceTopologyReflective(c);
        fenceBackendDiagnosticOnly(c, javaFiles);
    }

    // ── Fence 1 ────────────────────────────────────────────────────────────────────────────

    /**
     * <strong>Fence 1b — W1: the thread-safety scope exists in each facade's CLASS javadoc.</strong>
     *
     * <p>Plan W1 requires a thread-safety block on {@code RowStore} and {@code Mask}: the facade is
     * not thread-safe, the caller must establish <em>happens-before</em> between {@code close()}
     * and every access, and a concurrent close-vs-access is undefined with no guard detecting it.
     * Until this leg existed the obligation was recorded as discharged while neither class carried
     * a word of it — found by Codex on #59.
     *
     * <p><strong>Scoped to the class javadoc, and the first version was NOT — which made it
     * partially vacuous (Codex, #60).</strong> A whole-file scan is satisfied by any occurrence
     * anywhere: {@code Mask.materializeRows()} independently names <em>happens-before</em>, so
     * deleting the class block — the exact regression this leg targets — left the fence green.
     * <strong>The disproof was in my own disable run and I did not read it:</strong> two
     * occurrences had to be stripped to turn it red, and "why two?" is the whole finding. A
     * disable that removes more than the regression does not demonstrate the regression is caught.
     *
     * <p>So this walks back from the {@code public final class} declaration to the javadoc block
     * immediately above it and scans <strong>only that region</strong>, and it requires the whole
     * obligation rather than one literal — a block that says "happens-before" while dropping "no
     * guard detects it" would be a weaker promise passing as the full one.
     *
     * <p>Reads RAW lines, not {@code codeLines}: every other fence strips comments because it hunts
     * code; this one hunts javadoc, so the shared filter would delete exactly what it looks for.
     */
    private static void fenceThreadSafetyJavadoc(Checks c, Path javaMain) {
        c.section("fence 1b: both facade classes carry the W1 scope IN THEIR CLASS JAVADOC");
        // The complete W1 obligation, not just its headline literal.
        // Lowercase: `classJavadocOf` returns a case-folded, whitespace-canonical region.
        //
        // `close()` is required (CodeRabbit, #60) because W1's obligation is not "mention
        // happens-before" — it is happens-before *between `close()` and every access*. A block
        // naming the relation without naming the operation it orders is a vaguer promise
        // wearing the same words.
        String[] obligation = {
            "not thread-safe",
            "happens-before",
            "close()",
            "no guard detects it",
        };
        String[][] targets = {
            {"RowStore.java", "public final class RowStore"},
            {"Mask.java", "public final class Mask"},
        };
        for (String[] t : targets) {
            String fileName = t[0];
            Path f = javaMain.resolve("com/adaworldapi/lancegraph/" + fileName);
            c.that(fileName + " exists to be scanned", java.nio.file.Files.isRegularFile(f));
            String doc = classJavadocOf(readLines(f), t[1]);
            c.that(fileName + "'s class javadoc was located and is non-trivial ("
                    + doc.length() + " chars >= 200)", doc.length() >= 200);
            for (String required : obligation) {
                c.that(fileName + " class javadoc states W1's \"" + required + "\"",
                        doc.contains(required));
            }
        }
        // Discriminating half: a facade class W1 does not name must NOT carry the scope, or a
        // scanner that matched anything at all would pass this leg.
        Path control = javaMain.resolve("com/adaworldapi/lancegraph/NativePattern.java");
        if (java.nio.file.Files.isRegularFile(control)) {
            String doc = classJavadocOf(readLines(control), "public final class NativePattern");
            c.that("the scan discriminates: NativePattern (not named by W1) has no W1 scope in its"
                    + " class javadoc, so a match-everything scanner fails this leg",
                    !doc.contains("happens-before"));
        }
    }

    /**
     * The javadoc block immediately preceding {@code declaration}, or {@code ""} if there is none.
     *
     * <p>Returned <strong>whitespace-canonical and lower-cased</strong>, with the {@code *}
     * furniture stripped, so a required phrase that WRAPS across two javadoc lines is still one
     * phrase. Skipping that made four arms fail on prose that plainly said the right thing — a
     * fence keyed to where an author wrapped a line is enforcing formatting, not meaning.
     *
     * <p>Walks up from the declaration to the nearest javadoc terminator and back to its opening
     * delimiter. Anything else in the file — another method's javadoc, a string literal — is
     * outside the returned region by construction, which is the point: fence 1b must fail when
     * THIS block is deleted, regardless of what the rest of the file happens to say.
     */
    private static String classJavadocOf(List<String> lines, String declaration) {
        int decl = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(declaration)) {
                decl = i;
                break;
            }
        }
        if (decl <= 0) {
            return "";
        }
        int end = -1;
        for (int i = decl - 1; i >= 0; i--) {
            String t = lines.get(i).strip();
            if (t.isEmpty() || t.startsWith("@")) {
                continue; // annotations and blank lines may sit between javadoc and declaration
            }
            if (t.endsWith("*/")) {
                end = i;
            }
            break; // the first non-blank, non-annotation line decides: javadoc or nothing
        }
        if (end < 0) {
            return "";
        }
        int start = -1;
        for (int i = end; i >= 0; i--) {
            if (lines.get(i).strip().startsWith("/**")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String t = lines.get(i).strip();
            // Drop the javadoc furniture so a phrase that WRAPS is still one phrase. Without
            // this, "no guard detects it" broken across two lines reads as
            // "no guard detects\n * it" and no literal match can see it — the fence would then
            // depend on where the author happened to wrap, which is not a property worth
            // enforcing. Measured: this exact case failed 4 arms before normalization.
            if (t.startsWith("/**")) {
                t = t.substring(3);
            } else if (t.startsWith("*/")) {
                t = t.substring(2);
            } else if (t.startsWith("*")) {
                t = t.substring(1);
            }
            sb.append(' ').append(t);
        }
        // Whitespace-canonical and case-insensitive, matching this class's `canonical` treatment
        // of code: a doc fence should read the prose, not the formatting.
        return sb.toString().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * <strong>Fence 1c — W2: the doctrine wording matches the mechanism it describes.</strong>
     *
     * <p>Plan W2 requires root {@code CLAUDE.md}'s cached-descriptor scope note to say the path is
     * re-validated <em>at each top-level facade call</em> and is <em>not atomic</em> with respect
     * to a concurrent close — and never to say "unconditional" or "on every dereference". W2 also
     * notes that fence 1 cites {@code CLAUDE.md} as an out-of-band list it never reads, and that
     * <em>this precedent must be built, not merely cited</em>. This leg builds it: the first fence
     * in this class that actually opens the doctrine file.
     *
     * <p><strong>Scoped to the note, not the file, and that is load-bearing.</strong> The same
     * bullet legitimately contains "unconditionally" about a falsifier that holds unconditionally
     * — a true sentence. A whole-file or whole-bullet scan would fire on it, and a fence that
     * flags a correct sentence is the fires-on-everything defect this repo's own rules name.
     */
    private static void fenceDoctrineWording(Checks c, Path repo) {
        c.section("fence 1c: the cached-descriptor doctrine says what the mechanism does (W2)");
        Path doctrine = repo.resolve("CLAUDE.md");
        c.that("root CLAUDE.md is readable — W2's 'build the precedent, do not cite it'",
                java.nio.file.Files.isRegularFile(doctrine));
        List<String> lines = readLines(doctrine);

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Scope note") && lines.get(i).contains("cached-descriptor")) {
                start = i;
                break;
            }
        }
        c.that("the cached-descriptor scope note is present and locatable", start >= 0);
        if (start < 0) {
            return;
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- **")) {
                end = i;
                break;
            }
        }
        StringBuilder note = new StringBuilder();
        for (int i = start; i < end; i++) {
            note.append(' ').append(lines.get(i).strip());
        }
        // Whitespace-canonical and case-folded, exactly as `classJavadocOf` does for javadoc.
        // Markdown wraps too: "generation-checked registry" is split across two lines in the
        // doctrine today, so a raw-line scan cannot see it. This normalization was written for
        // fence 1b and NOT carried here when 1c was built — the identical defect, one function
        // over, found only because a later assertion needed a phrase that happened to wrap.
        String text = note.toString().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        c.that("the note is a real region, not an empty match (" + (end - start) + " lines >= 5)",
                end - start >= 5);

        for (String forbidden : new String[] {"unconditional", "on every dereference"}) {
            c.that("the note never claims the re-validation is \"" + forbidden
                    + "\" — it is per-call and racy, not that", !text.contains(forbidden));
        }
        for (String req : new String[] {"at each top-level facade call", "not atomic"}) {
            c.that("the note states W2's required wording: \"" + req + "\"", text.contains(req));
        }
        // The MECHANISM, not just its properties (CodeRabbit, #60 — posted 44 seconds before
        // that PR merged, so it landed against an already-merged head and is fixed here).
        //
        // Required because the two phrases above are properties a rewrite can KEEP while
        // deleting what they are properties OF. Measured: a note reading "Both halves are
        // checked at each top-level facade call, and neither is not atomic..." — false about
        // `RowStore`, and naming no mechanism at all — passed 38/38.
        //
        // My own disable had deleted the whole `Mask` bullet, which took the two phrases with
        // it and went red, and I read that as proof the half was protected. It proved deletion
        // was caught, never rewrite. **A disable proves the path it walks and no other.**
        c.that("the note names Mask's mechanism, not merely its properties — a rewrite can keep"
                        + " \"at each top-level facade call\" while deleting what re-validates",
                text.contains("mask")
                        && text.contains("re-validates")
                        && text.contains("generation-checked registry"));
        c.that("the note keeps the two halves apart — RowStore is named as still unguarded",
                text.contains("rowstore") && text.contains("iss-lgj-epoch-unchecked"));
    }

    private static void fenceMaterialization(Checks c, List<Path> javaFiles) {
        c.section("fence 1: materialization census matches the doctrine's exhaustive list");

        Map<String, Integer> observed = new TreeMap<>();
        for (Path f : javaFiles) {
            String name = f.getFileName().toString();
            // Comment-filtered code, matched as ONE text: filtering per line keeps prose out of
            // the counts (CodeRabbit round 1), and joining the filtered lines back with \n lets
            // the \s+ in each pattern span a line break — `new` and `long[4]` on separate lines
            // is legal Java and must still count (CodeRabbit round 3).
            String code = String.join("\n", codeLines(f));
            for (Map.Entry<String, java.util.regex.Pattern> p
                    : MATERIALIZATION_PATTERNS.entrySet()) {
                int n = 0;
                java.util.regex.Matcher m = p.getValue().matcher(code);
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    observed.merge(name + "|" + p.getKey(), n, Integer::sum);
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
            scanForTokens(f, TOPOLOGY_TOKENS_JAVA, hits);
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
            scanForTokens(f, TOPOLOGY_TOKENS_ABI, hits);
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

    // ── Fence 2b: the reflective arm ───────────────────────────────────────────────────────

    /**
     * The spelling-immune half of the topology fence (the review round's own lesson made
     * structural): a lexical scan can be evaded by any legal spelling the pattern did not
     * anticipate, but a public method NAMED for topology exists in the compiled class file with
     * exactly one spelling. This is the same footing as {@code ApiSurfaceTest} — reflection over
     * compiled classes — applied to §E. The lexical fence 2 stays for what reflection cannot
     * see: the ABI struct fields and Rust exports, and non-public / local uses.
     */
    private static void fenceTopologyReflective(Checks c) {
        c.section("fence 2b: no public facade method is topology-named (reflective, §E)");

        List<Class<?>> types = publicFacadeTypes(c);
        // Anti-vacuity: the compiled facade must actually be on the classpath, at its real
        // size — 29 public types measured 2026-08-28; the bound leaves headroom for pruning
        // but a near-empty scan (a broken classpath) must fail, not pass vacuously.
        c.that("the reflective arm sees the compiled facade (" + types.size()
                + " public types >= 20)", types.size() >= 20);

        List<String> leaks = new ArrayList<>();
        for (Class<?> t : types) {
            for (java.lang.reflect.Method m : t.getMethods()) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                for (String stem : new String[] {"worker", "parallelism", "partition", "shard"}) {
                    if (n.contains(stem)) {
                        leaks.add(t.getSimpleName() + "." + m.getName());
                        break;
                    }
                }
                if (n.equals("threads")) {
                    leaks.add(t.getSimpleName() + "." + m.getName());
                }
            }
        }
        if (leaks.isEmpty()) {
            c.that("no public method on any facade type carries a topology name"
                    + " (worker/parallelism/partition/shard/threads)", true);
        } else {
            for (String l : leaks) {
                c.that("TOPOLOGY-NAMED PUBLIC METHOD: " + l + " — worker count belongs to the"
                        + " state owner; no spelling of this method is admissible (§E)", false);
            }
        }
    }

    /** Enumerate public facade types the same way {@code ApiSurfaceTest} does. */
    private static List<Class<?>> publicFacadeTypes(Checks c) {
        List<Class<?>> found = new ArrayList<>();
        try {
            Path root = Path.of(DoctrineFenceTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path pkg = root.resolve("com/adaworldapi/lancegraph");
            if (!Files.isDirectory(pkg)) {
                return found;
            }
            try (Stream<Path> files = Files.list(pkg)) {
                for (Path p : files.toList()) {
                    String file = p.getFileName().toString();
                    if (!file.endsWith(".class") || file.contains("$")) {
                        continue;
                    }
                    String simple = file.substring(0, file.length() - ".class".length());
                    Class<?> t = Class.forName("com.adaworldapi.lancegraph." + simple);
                    if (java.lang.reflect.Modifier.isPublic(t.getModifiers())
                            && !simple.endsWith("Test") && !simple.equals("Checks")
                            && !simple.equals("AllTests")) {
                        found.add(t);
                    }
                }
            }
        } catch (Exception e) {
            // A partial discovery must not satisfy the anti-vacuity bound with whatever it found
            // before dying (CodeRabbit round 5) — fail the suite loudly, same rule as readLines.
            throw new IllegalStateException("reflective facade discovery failed mid-scan", e);
        }
        found.sort(java.util.Comparator.comparing(Class::getSimpleName));
        return found;
    }

    // ── Fence 3 ────────────────────────────────────────────────────────────────────────────

    private static void fenceBackendDiagnosticOnly(Checks c, List<Path> javaFiles) {
        c.section("fence 3: SIMD backend is diagnostic only — no branch consumes it");

        List<String> strayed = new ArrayList<>();
        List<String> branched = new ArrayList<>();
        Map<String, Integer> carrierCensus = new TreeMap<>();
        int tokenLines = 0;

        for (Path f : javaFiles) {
            String name = f.getFileName().toString();
            List<String> lines = codeLines(f); // prose may discuss backends freely
            for (int i = 0; i < lines.size(); i++) {
                String code = lines.get(i);
                if (code.isEmpty()) {
                    continue;
                }
                // Canonical form for BOTH halves (CodeRabbit, PR #48): the tokens are plain
                // identifiers (whitespace inside one is illegal Java), but the branch markers
                // are operators, and `x=="avx2"` must match `==` regardless of spacing.
                String canon = canonical(code);
                boolean carries = false;
                for (String token : BACKEND_TOKENS) {
                    if (canon.contains(token)) {
                        carries = true;
                        break;
                    }
                }
                if (!carries) {
                    continue;
                }
                tokenLines++;
                carrierCensus.merge(name, 1, Integer::sum);
                if (!BACKEND_TOKEN_HOMES.contains(name)) {
                    strayed.add(name + ":" + (i + 1) + " " + code);
                }
                for (String marker : BRANCH_MARKERS) {
                    if (canon.contains(marker)) {
                        branched.add(name + ":" + (i + 1) + " " + code);
                        break;
                    }
                }
            }
        }

        // The anti-laundering census: every carrier line accounted for, per home, exactly.
        List<String> censusDrift = new ArrayList<>();
        for (Map.Entry<String, Integer> pin : BACKEND_LINE_PINS.entrySet()) {
            Integer got = carrierCensus.get(pin.getKey());
            if (got == null || !got.equals(pin.getValue())) {
                censusDrift.add(pin.getKey() + " carries " + (got == null ? 0 : got)
                        + " token lines, pinned " + pin.getValue());
            }
        }
        for (Map.Entry<String, Integer> o : carrierCensus.entrySet()) {
            if (!BACKEND_LINE_PINS.containsKey(o.getKey())) {
                censusDrift.add(o.getKey() + " carries " + o.getValue()
                        + " token lines, pinned 0 (not a home)");
            }
        }
        if (censusDrift.isEmpty()) {
            c.that("the backend-token carrier census matches its pin exactly (a laundering"
                    + " assignment is itself a new carrier line and fails here)", true);
        } else {
            for (String d : censusDrift) {
                c.that("BACKEND CARRIER CENSUS DRIFT: " + d + " — a new line consuming the"
                        + " diagnostic (even without a same-line branch) must be deliberately"
                        + " re-pinned or removed", false);
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

    /**
     * Rust raw-string opener ({@code r"..."} / {@code r#"..."#}): a construct {@link #codeLines}
     * does NOT model — its quote-scan would end the "literal" at the wrong quote and can then
     * silently drop code after a {@code //} inside it (CodeRabbit round 5, correcting this
     * class's earlier claim that the unmodeled construct fails loud). Rather than growing a Rust
     * lexer for two files that contain no raw strings today, the fence TRIPS on the opener
     * itself: introducing one into abi.rs/exports.rs fails fence 2 until the filter is taught.
     */
    private static final java.util.regex.Pattern RUST_RAW_STRING_OPENER =
            java.util.regex.Pattern.compile("(^|[^A-Za-z0-9_])r#*\"");

    private static void scanForTokens(Path f, String[] tokens, List<String> hits) {
        String name = f.getFileName().toString();
        if (name.endsWith(".rs")) {
            List<String> raw = readLines(f);
            for (int i = 0; i < raw.size(); i++) {
                if (RUST_RAW_STRING_OPENER.matcher(raw.get(i)).find()) {
                    hits.add(name + ":" + (i + 1) + " [rust raw string — unmodeled by the"
                            + " comment filter; teach codeLines before using one here] "
                            + raw.get(i).strip());
                }
            }
        }
        List<String> lines = codeLines(f); // one comment filter for every fence (CodeRabbit)
        for (int i = 0; i < lines.size(); i++) {
            String code = lines.get(i);
            if (code.isEmpty()) {
                continue;
            }
            // Whitespace-canonical, case-insensitive: `public View workers (int n)` becomes
            // `publicviewworkers(intn)` and still carries `workers(`.
            String lower = canonical(code).toLowerCase(java.util.Locale.ROOT);
            for (String token : tokens) {
                if (lower.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                    hits.add(name + ":" + (i + 1) + " [" + token + "] " + code);
                    break;
                }
            }
        }
    }

    /**
     * The ONE comment filter every fence reads through (CodeRabbit, PR #48: three fences with
     * three notions of "code" means prose can fail the build under one fence and pass under
     * another). Returns lines index-aligned with the file — a fully-commented line becomes
     * {@code ""} so reported line numbers stay real.
     *
     * <p>Comment SPANS are removed rather than lines skipped (the second CodeRabbit round: a
     * prefix-only skip blanked the whole of {@code /* c *}{@code /} {@code new long[4];}, so
     * code sharing a line with a block comment escaped every fence). String and char literals
     * are honored while scanning, so a {@code "//"} or {@code "/*"} INSIDE a literal (a URL, a
     * glob) neither starts a comment nor truncates the code after it. The same lexing covers
     * the Rust files fence 2 scans ({@code //}, {@code ///}, block comments); Rust's nested
     * block comments and raw strings are not modeled — neither occurs in the two scanned files,
     * and a raw-string OPENER now trips fence 2 outright ({@link #RUST_RAW_STRING_OPENER}), so
     * the unmodeled construct fails loud instead of silently mis-lexing.
     */
    private static List<String> codeLines(Path f) {
        List<String> raw = readLines(f);
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        for (String line : raw) {
            StringBuilder kept = new StringBuilder(line.length());
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) {
                        i = line.length();
                    } else {
                        inBlock = false;
                        i = end + 2;
                    }
                    continue;
                }
                char ch = line.charAt(i);
                if (ch == '"' || ch == '\'') {
                    int j = i + 1;
                    while (j < line.length()) {
                        char cj = line.charAt(j);
                        if (cj == '\\') {
                            j += 2;
                            continue;
                        }
                        if (cj == ch) {
                            break;
                        }
                        j++;
                    }
                    int stop = Math.min(j + 1, line.length());
                    kept.append(line, i, stop);
                    i = stop;
                    continue;
                }
                if (ch == '/' && i + 1 < line.length()) {
                    char next = line.charAt(i + 1);
                    if (next == '/') {
                        break; // line comment: drop the rest of the line
                    }
                    if (next == '*') {
                        inBlock = true;
                        i += 2;
                        continue;
                    }
                }
                kept.append(ch);
                i++;
            }
            out.add(kept.toString().strip());
        }
        return out;
    }

    /** Strip every whitespace character — the canonical form all fences match against. */
    private static String canonical(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isWhitespace(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * An unreadable source file must fail the fence LOUDLY, never scan as empty — an
     * {@code IOException} silently converted to {@code List.of()} would let fence 2 report zero
     * topology hits without ever scanning {@code abi.rs} (CodeRabbit, PR #48). The unchecked
     * rethrow surfaces through the suite runner's per-suite catch as a failed suite.
     */
    private static List<String> readLines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("fence corpus file unreadable: " + f, e);
        }
    }
}

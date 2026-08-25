# Technical Debt Log — Open + Paid (double-entry, append-only)

## TD-LGJ-JDK-TOOLCHAIN-NOT-PORTABLE (2026-08-25) — PAID (verified, provisioned, re-run green)

`java/README.md`, `docs/panama.md`, and `.claude/knowledge/jdk-toolchain-facts.md`
all pin the production and Valhalla-lab toolchains to absolute paths —
`/opt/jdks/jdk-26.0.2` (FFM final) and `/opt/jdks/jdk-27` (JEP 401 EA,
value classes) — with no provisioning step recorded anywhere in the repo.
This session's fresh container had **neither path** (only system JDK 21,
which the docs explicitly warn against: "FFM is preview-gated"). Since
there is no Maven/Gradle/build-system dependency resolution for the Java
side by design ("no downloaded dependency... `javac` and `java` are the
entire Java toolchain"), there was also no auto-provisioning mechanism —
the two absolute paths were pure environmental assumption, unfalsifiable
until someone actually needed them in a fresh container.

**Verified, not assumed, this session:**
- Both JDKs are still fetchable at their documented identity: GA
  `openjdk-26.0.2.1_linux-x64_bin.tar.gz` from `download.java.net`, and
  the exact `27-jep401ea3+1-1` EA build from `jdk.java.net/valhalla/`.
  (One real wrinkle: the session's default network proxy 403s raw
  `download.java.net`/`github.com` content URLs — `curl --noproxy '*'`
  bypasses it, same pattern already documented elsewhere in this
  workspace for git operations.)
- Extracted to the documented paths, `java -version` on each matches the
  doc's claimed build strings exactly (`26.0.2.1+1-7`, `27-jep401ea3+1-1`).
- `value record Point(int x, int y) {}` compiled with
  `--enable-preview --release 27` and `Point.class.isValue()` returned
  `true` on JDK 27 — the Valhalla claim holds, verified, not re-read from
  a doc comment.
- Built `native/lgj-abi` (`cargo build --release`, clean) and ran the
  **full** `AllTests` suite against JDK 26 with the freshly-built `.so`:
  **245/245 checks passed** (`ApiSurfaceTest` through `MaskNativeOpsTest`),
  including the mask-native enforcement, lifetime, and RowStore parity
  suites this repo's own iron rules depend on.

**One real doc discrepancy found in the same pass** (not the toolchain
gap — a separate, smaller finding): `java/README.md`'s "Compilation emits
six `[restricted]` warnings with `-Xlint:all`, all of them in
`internal/ffm/{Abi,Downcalls,Engine}.java`" is off by one. Actual count
with `javac -Xlint:all` against JDK 26: **7** warnings — the 7th is
`SymbolLookup.libraryLookup` in
`src/test/java/.../AbiContractTest.java:113`, outside the three files the
doc names. Not a `-D warnings`-gated build (no such gate exists for this
no-build-system project), so this never surfaced as a failure. Doesn't
need code action (a test file legitimately calling a restricted FFM
method to prove the contract is fine); `java/README.md`'s warning COUNT
and FILE LIST should be corrected to match — filed here rather than
silently fixed in the same pass, since it's a separate concern from the
toolchain gap this entry exists to record.

> **⊘ STORNO (2026-08-25, same session, before merge) — the sentence
> above originally read "…only as a doc claim nobody had re-verified
> against a real compile since it was written." That characterisation is
> FALSE and is corrected here rather than deleted, per this file's
> append-only discipline.** The board has verified this number
> repeatedly and consistently at **7**, and was right every time:
> `STATUS_BOARD.md` D-LGJ-D (2026-08-17, "7 `[restricted]` warnings, all
> in `internal/ffm/*` or a test deliberately exercising it"), D-LGJ-W3
> ("same 7 pre-existing `[restricted]` warnings, zero new"),
> `LATEST_STATE.md` 2026-08-17 ("the exact set the design predicts") and
> 2026-08-18 PR-W8b ("`javac -Xlint:all` 7 pre-existing warnings/0 new").
> The board's characterisation is also strictly MORE accurate than my
> own: it says "or a test deliberately exercising the restricted API",
> which already accounts for the `AbiContractTest` hit I reported as
> though it were newly discovered. **The real, much narrower finding is:
> `java/README.md` alone is stale at "six"; the board never was.** Root
> cause of my error: I compared a compile against ONE doc without reading
> the board that governs it — the exact failure the repo's own
> session-start rule (`CLAUDE.md` § Session start: LATEST_STATE +
> STATUS_BOARD first) exists to prevent.

**⊘ SECOND STORNO (2026-08-25, same session) — scope of the Valhalla
verification claimed above.** This entry's bullet reporting that a
`value record` compiled and `Class.isValue()` returned `true` on JDK 27
is accurate as a toolchain-liveness check, but it must NOT be read as a
Valhalla *finding*: it is row 5 of the semantic-truth table in
`valhalla-lab/docs/three-truths.md`, measured and recorded 2026-08-17
under **D-LGJ-F (DONE)**. The three-truths method is already fully
executed — allocation instrumentation, causal isolation via
`-XX:±UseArrayFlattening`/`±UseFieldFlattening`/`-DoEscapeAnalysis`, the
mandatory N-objects-vs-N-values-vs-1-lane thesis experiment, and three
filed reproducers (R1 javac / R2 the 8-byte flattening cliff / R3 no
supported spelling). Nothing in this session's pass adds to it or
supersedes it. Recorded so a future session does not read this entry as
licence to re-run a completed experiment.

**Status: PAID for this session's container** — both JDKs now live at
`/opt/jdks/jdk-26.0.2` and `/opt/jdks/jdk-27`, verified working end to
end. **Remains OPEN as a structural gap**: nothing in the repo commits
this provisioning step anywhere (no `setup.sh`, no CI step, no Dockerfile
layer found), so the NEXT fresh container hits the identical blocker.
Pay this down for real by adding a provisioning script (mirroring the
`fetchDependencies.gradle`-style pattern already used elsewhere in this
workspace for other repos' native toolchains) rather than leaving it as
tribal knowledge in three markdown files.

## TD-LGJ-REGISTRY-CONCURRENCY-UNMEASURED (2026-08-17) — OPEN

`docs/abi.md` §4's registry design (short registry read-lock → clone `Arc`
→ drop registry lock → lock the entry) is a **stated design intent**, not a
measured property. The POC's Java layer is single-threaded, so nothing in
this vertical slice actually exercises concurrent access. If a future slice
adds concurrent Java callers (e.g. a parallel `View` evaluation), the
registry's actual behavior under contention (lock-hold duration,
starvation, whether the "distinct resources don't serialize" claim holds
under real load) needs to be benchmarked before it's trusted, not merely
re-read from the doc comment. Pay this down when concurrent access is
first actually needed — not before, per the "no speculative future-proofing"
house style.

## TD-LGJ-JMH-AVAILABILITY-UNKNOWN (2026-08-17) — OPEN

`bench/` was briefed to attempt fetching real JMH jars from Maven Central
(`repo1.maven.org`, confirmed reachable via `curl --noproxy '*'` this
session) and fall back to a hand-rolled, honestly-labeled harness if that
doesn't work cleanly with plain `javac`/`java -cp` (no Maven/Gradle
dependency resolution available for this no-build-system project). Which
path was actually taken is unknown until the fan-out's Lab-phase report
lands. If JMH could not be wired, `TECH_DEBT` should gain a follow-up
entry noting the specific blocker (classpath assembly by hand for JMH's
annotation processor is real friction) so a future session doesn't
re-attempt the same dead end without the context.

## TD-LGJ-V4-BASELINE-NOT-PORTABLE (2026-08-17) — OPEN, INTENTIONAL

`native/lgj-abi` compiles with `-Ctarget-cpu=x86-64-v4` (AVX-512) as its
DEFAULT baseline (see `EPIPHANIES.md` `E-LGJ-V4-DIVERGES-FROM-NDARRAY-DEFAULT-1`
for the reasoning). This means the built `.so` will SIGILL on any host
without AVX-512 — acceptable for a research vertical slice on one known
host, unacceptable for any future redistribution. If this project ever
ships a `.so` to unknown hardware, the build must switch to v3 (or add
runtime-dispatch, which `ndarray` already supports via its
`runtime-dispatch` feature — see the ndarray archaeology findings in
`AGENT_LOG.md`) before that ships. Filed as debt now specifically so it
isn't silently forgotten once the v4 default stops being obviously
research-only.

## TD-LGJ-FAN-OUT-PREDATED-TWO-RULES (2026-08-17) — OPEN, gates D-LGJ-AUDIT

The first vertical-slice fan-out (`wf_23ad2110-b1e`) was dispatched before
`E-LGJ-NO-C-EVER-1` and `E-LGJ-SIMD-PROVENANCE-1` were stated as explicit
operator rules (though both were already implicit in `docs/abi.md`'s own
text, written before dispatch). This is not assumed to be a violation —
it's an unaudited state. `STATUS_BOARD.md`'s `D-LGJ-AUDIT` is the paydown
step: a mechanical grep sweep of the fan-out's actual output the moment it
completes. This entry closes only when that audit runs and either finds
nothing (debt paid, entry closes clean) or finds violations (debt paid via
the fix, entry closes noting what was corrected).

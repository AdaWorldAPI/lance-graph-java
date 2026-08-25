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
no-build-system project), so this never surfaced as a failure — only as
a doc claim nobody had re-verified against a real compile since it was
written. Doesn't need code action (a test file legitimately calling a
restricted FFM method to prove the contract is fine); the doc's warning
COUNT and FILE LIST should be corrected to match — filed here rather than
silently fixed in the same pass, since it's a separate concern from the
toolchain gap this entry exists to record.

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

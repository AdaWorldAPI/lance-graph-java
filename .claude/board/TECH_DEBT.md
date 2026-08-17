# Technical Debt Log — Open + Paid (double-entry, append-only)

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

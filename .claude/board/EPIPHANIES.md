# Epiphanies Log — Findings, Corrections, "Aha" Moments (APPEND-ONLY)

> Prepend new entries at the top. Never edit a past entry except its
> `**Status:**`/`**Confidence:**` line. A correction gets its own new,
> dated entry that references the one it corrects — the storno rule.

## 2026-08-17 — E-LGJ-VECTOR-API-BEATS-THE-CROSSING-1

**Status:** FINDING. **Confidence:** High (real JMH 1.37, `Data.crossCheck()` guards every fork,
independently cross-checked against a second, mechanically-generated computation of the same CSV).

Completes D-LGJ-G, the mission's mandated "where does execution belong — measure it, do not assume
the Rust side wins" comparison. The honest answer complicates the thesis in a useful way: **for a
single predicate over one native lane, the Java Vector API — reading the SAME native
`MemorySegment` zero-copy via `IntVector.fromMemorySegment`, no `byte[]`, no bounce buffer — beats
the native `lgj_plan_eval` crossing at every row count tested, from 64 to 4,194,304**, by 56.4× at
small sizes down to 1.33-1.41× at the largest:

| rows | native (µs) | vectorApi (µs) | vectorApi wins by |
|---:|---:|---:|---:|
| 64 | 0.612 | 0.011 | 56.40× |
| 65,536 | 15.324 | 8.027 | 1.91× |
| 4,194,304 | 1858.686 | 1319.107 | 1.41× |

A second, separate crossover is also real: native beats a plain Java **scalar** loop only past
roughly 4,096-16,384 rows — below that the crossing's own fixed cost (consistent with Component A's
measured ~22 ns bare-downcall floor) is not yet repaid.

**Why this does not overturn the project's thesis, and where the thesis's own machinery already
shows the real answer.** Component C isolates exactly one predicate, one lane — the case with
nothing to fuse and nothing to coordinate, which is precisely the case a zero-copy Vector kernel is
best at. Component E (multi-predicate fusion) shows the picture change: SIMD-vs-scalar is the
largest lever measured anywhere in this benchmark (10.8×-31.1×, growing with predicate count), and
`fused`/`unfused` land within this harness's own stated ~10% noise floor of each other at 65,536
rows — meaning the fused plan's real value is the STRUCTURAL guarantee of exactly one crossing
regardless of predicate count (already proven separately by `LazinessTest`), not a large measured
time saving at this scale. The honest verdict, matching the mission brief's own framing rather than
either extreme: **the crossing is worth paying for composed, multi-predicate work — not for reading
one predicate off one lane, where Java on the same memory is simply faster.**

**Method note, since two independent computations of the same data is itself worth recording as a
discipline:** `RESULTS.md` was hand-written from the raw `results/jmh-results.csv`, then verified
against `bench/summarise.sh` — a separate script the same PR ships that mechanically regenerates
the tables from the CSV "so a re-run's numbers can be regenerated mechanically — a table
transcribed by hand is a table that can drift from its own data" (the script's own doc comment).
Both productions of the same 50-row CSV agreed to 3 decimal places on every cell checked.

## 2026-08-17 — E-LGJ-VALHALLA-MEASURED-NOT-ASSUMED-1

**Status:** FINDING. **Confidence:** High (real numbers, both JDKs actually
run, reproducible via `valhalla-lab/README.md`).

The mandatory N-objects-vs-N-values-vs-1-lane experiment
(`.claude/knowledge/valhalla-three-truths-method.md`'s "one experiment that
must never be skipped") ran on both real JDKs. Headline, on 65,536 rows,
identical question, identical answer on every path:

| | native, one crossing | hydrate 65,536 `Row`, then scan |
|---|---:|---:|
| stable JDK 26 | 19.5 µs, 289 KiB | 746 µs, 2.00 MiB |
| Valhalla JDK 27 EA | 15.7 µs, 289.5 KiB | 900 µs, 2.50 MiB |

**The thesis's prediction held, and the reason why is itself a measured
finding, not an assumption:** `LaneId` (one field) measured `FLAT` under
Valhalla via the real VM query `ValueClass.isFlatArray` (2.90 B/element vs
16.00 B on stable — ~5.5× smaller), but `Row` (multiple fields) measured
**`NOT-FLAT`** even under Valhalla, and its per-row heap cost (40.01 B) was
*larger* than the stable JDK's own record-array cost (32.01 B). Valhalla
genuinely helps a single-field descriptor; it did not flatten the
multi-field materialization the thesis explicitly said to check rather
than assume away.

**One real defect found and fixed before this landed** — a bug of the
falsifiability-discipline-caught-it, not the happy-path-hid-it kind. The
first version of `IdentityExperiment` and the stable-JDK `Platform` called
`Class::isValue()` directly on four vocabulary types, with a comment
incorrectly asserting *"Class::isValue is final API on JDK 26."* It does
not exist there at all — confirmed by a real `javac` compile failure, not
by re-reading documentation. Fixed by routing every identity query through
`Platform.isValueClass(Class<?>)`: the stable half answers `false`
honestly (a JDK with no value-class concept can never produce one — the
answer is exact, not a guess, unlike the genuinely-unknowable
`arrayFlatness` case the same file already handles correctly), the
Valhalla half answers with the real `type.isValue()`. The correction
mirrors `E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1`'s finding about
`kernels.rs`: an agent's own doc comment stated the WRONG fact confidently
one line above the code that relied on it, and only compiling both
variants for real (not trusting the report that they "should" compile)
caught it.

**Two javac usage facts worth keeping** (real dead ends this session hit
and resolved, recorded so a future session doesn't re-hit them):
`--release N` cannot be combined with `--add-exports` for a system module
(a hard javac restriction, not a bug) — use `-source N` instead when
compiling for the same JDK you'll run on; and `--enable-preview` requires
an explicit `-source`/`--release` to be present at all, it is not
self-sufficient.

## 2026-08-17 — E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1

**Status:** FINDING. **Confidence:** High (measured, not asserted — every
number below came from an actual command run, not from an agent's report).

The core vertical slice (`docs/abi.md` + `native/lgj-abi` + `java/`) is
real, compiles clean, and its safety claims are not merely tested but
**disable-verified**: `registry.rs::resolve`'s generation check
(`slot.generation != gen`) was deliberately short-circuited to
`if false && ...`, and the suite re-run. Exactly the two tests whose names
claim to guard this property —
`a_reused_slot_invalidates_the_old_handle` and
`fabricated_handles_are_rejected_not_dereferenced` — went red; all other
70 stayed green. This is the falsifiability discipline this workspace's
sibling repos (tesseract-rs, MedCare-rs) both independently arrived at —
"a test that passes on the happy path is not evidence" — applied to this
repo's very first disable-verification, and it passed the meta-test: the
tests were real, not decorative.

**The one real rule violation the D-LGJ-AUDIT sweep found**:
`native/lgj-abi/src/kernels.rs::simd_popcount` called
`ndarray::hpc::bitwise::popcount_batch_u64` directly — the exact pattern
`E-LGJ-SIMD-PROVENANCE-1` exists to forbid. This is worth recording as a
finding in its own right: **the rule was stated correctly in the agent's
own doc comment one line above the violation** ("Reused, not
reimplemented — this already exists in `ndarray`...") — the agent
correctly identified WHERE the function lived but reached for the
internal path it happened to see in ndarray's source rather than the
re-export it was told to prefer. A soft "verify the exact path" brief
instruction was not sufficient; a mechanical grep gate is what actually
caught it. **Consequence for future briefs in this repo:** soft
instructions ("prefer X") get a mechanical audit regardless of how
clearly they were stated — this is now standing practice, not a
one-time fix.

**Numbers on record**, so a future session can spot-check rather than
re-run everything from scratch: Rust `cargo test` 72/72; `ndarray`
`simd_int_ops` tests 41/41; `clippy -D warnings` and `fmt --check` both
clean; release build exports exactly the 14 symbols `docs/abi.md` §7
names; Java `javac -Xlint:all` produces exactly 7 `[restricted]`
warnings, all in `internal/ffm/*` or one test deliberately exercising it;
`AllTests` 132/132 across 8 suites. Full breakdown on `STATUS_BOARD.md`'s
D-LGJ-B/C/D/E rows.

## 2026-08-17 — E-LGJ-V4-DIVERGES-FROM-NDARRAY-DEFAULT-1

**Status:** FINDING. **Confidence:** High (operator-directed, mechanically
applied).

`native/lgj-abi/.cargo/config.toml` pins `-Ctarget-cpu=x86-64-v4`
(AVX-512), **deliberately diverging** from `/home/user/ndarray`'s own
default of `-Ctarget-cpu=x86-64-v3` (AVX2). This is not a mistake to
reconcile later — the two repos have different distribution goals: ndarray
targets portable redistribution (v3 = Haswell-and-later, ~2013+), while
`lance-graph-java`'s native artifact in this phase is built and run on one
known host (verified AVX-512-capable this session) for a research vertical
slice, not shipped broadly. The `LgjAbiManifest::simd_backend` field is
what makes this divergence self-documenting at runtime rather than a
silent assumption — a consumer reads the manifest rather than assuming
which tier compiled.

**Consequence:** any future portable-distribution build of `lgj-abi` must
override with `CARGO_BUILD_RUSTFLAGS='-Ctarget-cpu=x86-64-v3'` at build
time, per the comment left in `.cargo/config.toml`. Do not silently change
the file's *default* back to v3 without a stated reason — v4 is the
deliberate choice for this phase.

## 2026-08-17 — E-LGJ-VALHALLA-ALREADY-MAINLINE-1

**Status:** FINDING. **Confidence:** High (measured by direct `diff -rq`
across three local checkouts + live compile/run verification).

JEP 401 (Value Classes and Objects) has **already integrated into mainline
JDK** as a preview feature — it is not exclusive to a separate Valhalla
fork. Measured this session: `/home/user/valhalla` (`lworld` branch,
2026-07-30 HEAD) is **behind** mainline `/home/user/jdk` (2026-08-17 HEAD)
for value-class purposes; its own last relevant commit is literally
*"[lworld] things to delete from lworld just before integrating JEP-401."*
`/home/user/panama-foreign`'s `java.lang.foreign` package is **byte-
identical** to mainline (`diff -rq` exit 0).

**Consequence:** this project needs exactly ONE production JDK (a GA
build, verified this session as `/opt/jdks/jdk-26.0.2`, where FFM is
final) and ONE Valhalla-preview JDK (the *official EA binary*
`27-jep401ea3+1-1` from `jdk.java.net/valhalla/`, not a source build of any
local fork). Building any of the three local OpenJDK source checkouts from
source for this project would have cost real time for zero benefit — the
binary already exists and was verified to work. See
`.claude/knowledge/jdk-toolchain-facts.md` for the full toolchain matrix.

**Corollary, stated so a future session doesn't re-litigate it:** null-
restricted *type* syntax (`Foo!`) and specialized generics do **not**
exist in any checkout verified this session — only the internal
`@jdk.internal.vm.annotation.NullRestricted` field annotation plus
`jdk.internal.value.ValueClass` factories, gated behind `--add-exports`.
Do not assume `Foo!` syntax is available; it measurably is not, as of this
session's verification.

## 2026-08-17 — E-LGJ-NO-C-EVER-1

**Status:** RULE (operator directive, locked). **Confidence:** N/A —
founding constraint, not a discovered fact.

Operator, verbatim: *"There's no C ever. We reuse Panama project for a
rust only."* `extern "C"` names the SysV AMD64 psABI (a platform calling
convention), not the C language; `#[repr(C)]` names a platform aggregate
layout rule, not a C struct. Consequence: no `.h` file, no `cbindgen`, no
`jextract` (structurally inapplicable — its only input is a C header, and
none exists), no JNI, anywhere in this repo, ever. Full statement:
`.claude/knowledge/no-c-ever.md`. This is the single most load-bearing
rule in the project and the one most likely to be violated by habit
(reaching for `jextract` because "that's how Panama projects usually
work") rather than by disagreement — flagged here so it is checked
mechanically (`abi-membrane-warden`'s doctrine item 1) rather than trusted
to memory.

## 2026-08-17 — E-LGJ-SIMD-PROVENANCE-1

**Status:** RULE (operator directive, locked). **Confidence:** N/A —
founding constraint.

Operator, verbatim: *"Never use ndarray::hpc, trampoline to
ndarray::simd::* instead."* `ndarray::hpc::*` is ndarray's internal
implementation namespace; `ndarray::simd::*` is the sanctioned re-export
surface every consumer in the Ada stack is expected to use (ndarray's own
CLAUDE.md: *"Consumer writes `crate::simd::F32x16`. Period."*). This repo
is one more consumer of that invariant, not an exception. Full statement
and falsifier grep: `.claude/knowledge/simd-provenance.md`. This directive
arrived AFTER the first vertical-slice fan-out was already dispatched
(whose briefs mentioned the popcount primitive via its `hpc::bitwise`
path, with an instruction to "verify the exact path" and prefer the
`simd` re-export) — `STATUS_BOARD.md`'s `D-LGJ-AUDIT` entry exists
specifically to mechanically check the fan-out's actual output against
this rule rather than assume the earlier, softer brief language was
sufficient.

## lgj-vertical-slice-v1 — the first Panama×Valhalla×ndarray::simd proof (PRE-REGISTERED 2026-08-17)

Plan: `.claude/plans/lgj-vertical-slice-v1.md`. Every D-id below maps 1:1 to
a phase in that plan and to a Phase-tracking task in this session's task
list.

| D-id | Deliverable | Status | Feeds |
|---|---|---|---|
| D-LGJ-A | Archaeology: ndarray SIMD/mask surface, lance-graph ClassView/WideFieldMask/SoaEnvelope/ownership, current Panama+Valhalla state | **DONE 2026-08-17** — 3 parallel Explore/Opus agents, findings folded into `docs/abi.md` and the `.claude/knowledge/*` docs | everything downstream |
| D-LGJ-ABI | `docs/abi.md` — the normative Rust↔Java contract | **DONE 2026-08-17** — 14 symbols, 4 `#[repr(C)]` types, 13 status codes, generation-checked handle | B, C, D, E |
| D-LGJ-ENS | `.claude/agents` (6 cards) + `.claude/knowledge` (6 docs) + `.claude/board` | **DONE 2026-08-17** — this board | every future review pass |
| D-LGJ-B | `ndarray::simd` primitives: `eq_u32_to_mask`, `gt_i32_to_mask`, `mask_and`/`mask_or`(`_assign`), `masked_sum_i32` | **DONE 2026-08-17** — `ndarray/src/simd_int_ops.rs`; `cargo test --lib simd_int_ops` **41/41** incl. signed-vs-bitwise `gt_i32`, tail-bit-zeroing, `u32::MAX` edge cases | C's kernels.rs |
| D-LGJ-C | `native/lgj-abi` — manifest, generation-checked registry, generic SoA fixture, kernels, `extern "C"` surface | **DONE 2026-08-17** — `cargo test` **72/72**, `clippy -D warnings` clean, `fmt --check` clean, release build → 14/14 symbols verified via `nm -D`. **Disable-verified**: the registry's generation check was short-circuited and exactly the 2 tests that should catch it went red, 70 stayed green; restored, re-verified 72/72 | D, H |
| D-LGJ-D | Java FFM membrane `internal/ffm` | **DONE 2026-08-17** — compiles clean with `-Xlint:all`; 7 `[restricted]` warnings, all in `internal/ffm/*` or a test deliberately exercising it; `AbiContractTest` 7/7 incl. proving the manifest cross-check genuinely rejects a wrong `.so` (`libz.so.1` loads but is refused for exporting no `lgj_abi_manifest`) | E |
| D-LGJ-E | Java public facade (`NativePattern`/`View`/`Predicate`/`Pattern`/`Mask`) | **DONE 2026-08-17** — `AllTests` **132/132**: `ApiSurfaceTest` (reflection-enforced zero-FFM-leakage), `SmokeTest` 14/14, `FixtureParityTest` 30/30 (Java independently recomputes expected counts from the transcribed generator), `FusionParityTest` 31/31 (fused/unfused/scalar bit-identical across 6 row-count shapes), `LazinessTest` 8/8 (empirically: 0 crossings to build a 16-condition chain, exactly 1 to evaluate it, independent of rows up to 1,000,000 — the thesis's central claim, measured), `NarrowingTest` 16/16, `LifetimeTest` 23/23 | F, G |
| D-LGJ-F | Valhalla lab — three-truths method on the small semantic value vocabulary | **DONE 2026-08-17** — `valhalla-lab/`: 4 experiments + a self-verifying `run.sh` (mechanically diffs the two `Vocab.java`s modulo the `value` keyword before trusting the A/B) + 3 causal-isolation runs (escape-analysis off; `UseArrayFlattening`/`UseFieldFlattening` toggled independently). 3 real Valhalla limitations reproduced and filed under `reproducers/` (R1: `@NullRestricted` field on an identity class is a `VerifyError`, javac's fault — no source form expresses required strict-field order; **R2: array flattening has a hard 8-byte payload cliff, VM-confirmed via `-XX:+PrintFlatArrayLayout`** — `LaneId`/`Ordinal`/`MaskId` (≤8B) flatten, `RowRange`/`Row` (16B) do not, so "Valhalla helps descriptors not entities" is a measured VM cutoff, not a hand-wave, and `RowRange` landing on the wrong side is flagged as the one place the expectation was too optimistic; R3: the densest null-restricted array form is `jdk.internal`-only and generics erase flattening entirely — `Foo!` type syntax confirmed NOT to parse, matching the archaeology finding). 1 real defect found + fixed before landing (see `EPIPHANIES.md`). None of the three limitations changed the production API — the migration path stays exactly `record` → `value record` | I |
| D-LGJ-G | Java Vector API comparative bench vs Panama→`ndarray::simd` | **In flight** — real JMH + JOL jars fetched (`jmh-core`/`jmh-generator-annprocess`/`jopt-simple`/`commons-math3`/`jol-core`) to `bench/lib/` (gitignored); no bench source written yet; the ONLY remaining open row | I |
| D-LGJ-H | Falsification: handle lifecycle (adversarial), SIMD/scalar parity, Java/native parity | **DONE 2026-08-17 for the Rust+Java core** — see D-LGJ-C's disable-verification and D-LGJ-E's `FusionParityTest`/`LifetimeTest`. Re-opens for F/G once the Lab lands | I |
| D-LGJ-I | Docs: `architecture.md`, `panama.md`, `valhalla-lab.md`, `execution-boundary.md` | **Queued** — gated on F/G landing (the docs synthesize Lab results, not just the core) | — |
| D-LGJ-AUDIT | Mechanical post-fan-out audit: `grep` for `ndarray::hpc` imports, any `.h`/`cbindgen`/`jextract` artifact, any FFM type leaking into public Java API | **DONE 2026-08-17** — 1 real violation found (`kernels.rs::simd_popcount` used the internal `ndarray::hpc::bitwise` path), fixed in place; everything else confirmed to be the one sanctioned exception or explanatory prose | closed D-LGJ-C/D/E for the core |

### Reading this table

**"In flight" means dispatched to a background agent, not reviewed and not
verified. "DONE" means a disable-verified test or a mechanical grep gate
actually ran** — per this workspace's own falsifiability discipline. D-LGJ-F
and D-LGJ-G are the only rows still open; they are deliberately NOT blocking
PR #1 (the core slice is independently complete and green) and will land as
their own PR once the Lab agent finishes and is reviewed with the same
rigor.

# lgj-vertical-slice-v1 — the first Panama×Valhalla×ndarray::simd proof

**Status: ACTIVE (session 1, 2026-08-17).** No PR opened yet — everything
below is pre-first-commit on `claude/lance-graph-java-panama-valhalla-sus9w8`.

## Goal

Not "Java bindings for lance-graph." A proof that an ordinary Java developer
can write familiar-looking fluent Java and get zero-copy columnar/SIMD
execution without learning Rust/SoA/SIMD/FFM — see
`.claude/knowledge/john-doe-migration-thesis.md`, which is the actual thesis
this plan serves.

## Phases (mirrors the mission brief's Phase A–I, mapped to D-ids on
`STATUS_BOARD.md`)

| Phase | What | D-id |
|---|---|---|
| A | Archaeology: ndarray SIMD/mask surface, lance-graph ClassView/WideFieldMask/SoaEnvelope, current Panama+Valhalla state | D-LGJ-A |
| — | Normative contract: `docs/abi.md` | D-LGJ-ABI |
| — | `.claude/agents` + `.claude/knowledge` ensemble (this file's sibling work) | D-LGJ-ENS |
| B | Missing `ndarray::simd` primitives (`eq_u32_to_mask`, `gt_i32_to_mask`, `mask_and`/`mask_or`(`_assign`), `masked_sum_i32`) | D-LGJ-B |
| C | Rust ABI crate `native/lgj-abi` — manifest, generation-checked registry, generic SoA fixture, kernels, `extern "C"` surface | D-LGJ-C |
| D | Java FFM membrane `internal/ffm` — Layouts, Downcalls, Abi manifest cross-check | D-LGJ-D |
| E | Java public facade — `NativePattern`/`View`/`Predicate`/`Pattern`/`Mask`, fused-plan execution | D-LGJ-E |
| F | Valhalla lab — three-truths method on the small semantic value vocabulary | D-LGJ-F |
| G | Java Vector API comparative bench (zero-copy `fromMemorySegment`) vs Panama→`ndarray::simd` | D-LGJ-G |
| H | Falsification: handle lifecycle, SIMD/scalar parity, Java/native parity | D-LGJ-H |
| I | Docs: `architecture.md`, `panama.md`, `valhalla-lab.md`, `execution-boundary.md` | D-LGJ-I |

## Sequencing decision (recorded, not just executed)

B/C/D were fanned out **in parallel** as three disjoint trees
(`/home/user/ndarray` vs `native/lgj-abi` vs `java/`) against the single
frozen contract `docs/abi.md`, rather than sequentially — the contract is
what makes that safe: each side is checked against the doc, not against the
other side's in-progress code. F (Valhalla lab) was sequenced AFTER D so it
could read the real `View`/`Predicate` types rather than guess their shape.

## Falsifiable gates before this plan's phases count as done

- `grep -rn "ndarray::hpc" native/lgj-abi/src` → must be empty (see
  `.claude/knowledge/simd-provenance.md`).
- `grep -rln '\.h$\|cbindgen\|jextract' .` (repo-wide) → must be empty (see
  `.claude/knowledge/no-c-ever.md`).
- `grep -rn "java.lang.foreign" java/src/main/java/com/adaworldapi/lancegraph`
  excluding `internal/ffm/` → must be empty (java-surface-warden's rule).
- Rust-side handle lifecycle tests pass AND were disable-verified (per
  `handle-lifecycle-auditor`'s card — a happy-path-only test is not evidence).
- Every `bench/` number has a reproduction command line attached.

## Where this plan's own status lives

`STATUS_BOARD.md` (per-D-id status), `AGENT_LOG.md` (what each spawned agent
actually did), `LATEST_STATE.md` (current contract inventory + what's
active right now). This file records intent and sequencing; those three
record ground truth as it lands.

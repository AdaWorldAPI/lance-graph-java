## 2026-08-17 — lgj-vertical-slice-v1 (PLAN; the first Panama×Valhalla×ndarray::simd proof)

Plan: `.claude/plans/lgj-vertical-slice-v1.md`. Active plan index — this
board file is APPEND-ONLY (prepend new plan entries; a superseding plan
gets its own new entry, the old one's `**Status:**` line updates in place,
nothing else about it changes).

**What it covers:** the full first vertical slice — the normative
`docs/abi.md` contract, the `ndarray::simd` primitives it needs, the Rust
ABI crate (`native/lgj-abi`), the Java FFM membrane + public semantic
facade (`java/`), the Valhalla three-truths lab (`valhalla-lab/`), and the
cost-separated benchmark harness (`bench/`). Maps 1:1 to the D-ids on
`STATUS_BOARD.md`.

**Sequencing decision on record:** B (ndarray primitives) / C (Rust ABI) /
D (Java FFM+facade) were fanned out in **parallel** as three disjoint
trees, checked independently against the one frozen `docs/abi.md` contract
rather than sequentially against each other. F/G (Valhalla lab + bench)
were sequenced **after** D specifically so they could read the real
`View`/`Predicate` Java types instead of guessing their shape.

**Status: ACTIVE.** No PR opened yet. See `STATUS_BOARD.md` for
per-deliverable status and `AGENT_LOG.md` for what each spawned agent
actually reported.

# Agent Ensemble — Session Entry Point

This folder contains focused agent cards for `lance-graph-java`.

The goal is not to multiply personalities for decoration. This is a
small, sharply-scoped project (a research vertical slice, not a
26-repo rollout) — six specialists, each guarding one real seam,
matched to the actual size of the problem.

## Mandatory reads, in order

1. **This file.**
2. **`docs/abi.md`** — the normative Rust↔Java contract. Every agent
   below is checked against it.
3. **`.claude/knowledge/john-doe-migration-thesis.md`** — the actual
   mission. Read this before writing a single line of public API or
   README prose. Every other decision in this repo serves this thesis;
   treating the project as "an FFI showcase" instead is the single
   most common way a session drifts here.
4. **`.claude/knowledge/no-c-ever.md`** and
   **`.claude/knowledge/simd-provenance.md`** — two operator-locked
   rules that are easy to violate by accident (importing
   `ndarray::hpc` because it happens to compile; reaching for
   `jextract`/`cbindgen` out of habit).
5. **`.claude/knowledge/jdk-toolchain-facts.md`** — which JDK path to
   use for which purpose. Getting this wrong (e.g. using `/usr/bin/java`
   instead of `/opt/jdks/jdk-26.0.2`) produces confusing preview-flag
   errors that look like a design problem but are a toolchain-selection
   mistake.
6. **`.claude/knowledge/agent-cargo-hygiene.md`** — operator directive:
   spawned agents do NOT run `cargo` in any form (build/check/test/
   clippy), ever. Only the orchestrating main thread compiles. This
   MUST be pasted (or equivalently stated) verbatim into every worker
   brief that touches `native/lgj-abi` or `/home/user/ndarray` — it is
   not optional context, it is a line every such brief must contain.

After these, load the domain-specific knowledge doc only as triggered
by the task (see the table below).

## Board — read before claiming anything is "done"

`.claude/board/LATEST_STATE.md` (current contract inventory — what
exists right now), `.claude/board/STATUS_BOARD.md` (per-deliverable
D-id status), `.claude/board/AGENT_LOG.md` (ONE WRITER: the
orchestrating main thread only — spawned agents report back, they do
not append here themselves), `.claude/board/EPIPHANIES.md` /
`.claude/board/TECH_DEBT.md` / `.claude/board/ISSUES.md` (the
append-only triple ledger — findings/corrections, open technical debt,
open blockers, each double-entry and prepend-only), and
`.claude/board/INTEGRATION_PLANS.md` (the versioned plan index; the
active plan lives at `.claude/plans/lgj-vertical-slice-v1.md`). A
status of "in flight" on `STATUS_BOARD.md` means dispatched, not
reviewed — do not cite it as shipped.

## Knowledge Activation Protocol

| Trigger | Agent | Also loads |
|---|---|---|
| touching `native/lgj-abi/src/exports.rs`, adding/changing any `lgj_*` symbol | `abi-membrane-warden` | `no-c-ever.md`, `abi-ownership-and-handles.md` |
| touching `native/lgj-abi/src/kernels.rs`, any numeric primitive | `simd-savant` | `simd-provenance.md`, `simd-lane-width-family.md` |
| touching `native/lgj-abi/src/registry.rs`, any handle lifecycle question | `handle-lifecycle-auditor` | `abi-ownership-and-handles.md` |
| touching `java/src/main/java/.../lancegraph/*` (public API) | `java-surface-warden` | `john-doe-migration-thesis.md` |
| touching `java/src/main/java/.../internal/ffm/*` | `panama-bridge-engineer` | `jdk-toolchain-facts.md`, `docs/abi.md` §5 |
| touching `valhalla-lab/` or `bench/`, any performance/representation claim | `valhalla-lab-scientist` | `valhalla-three-truths-method.md`, `jdk-toolchain-facts.md` |

## Model policy

Matches the operator's standing instruction for this repo: **Sonnet for
grindwork, Opus for filigree planning and adversarial review** — to
save tokens without dropping quality where it matters.

- `abi-membrane-warden`, `simd-savant`, `panama-bridge-engineer`,
  `java-surface-warden` — **Sonnet**. Each checks a bounded, well-
  specified contract (`docs/abi.md`, the knowledge docs) against a
  diff. Bounded input, known output shape.
- `handle-lifecycle-auditor`, `valhalla-lab-scientist` — **Opus**. Both
  require holding a multi-step safety argument or a multi-axis
  measurement claim in mind at once and actively trying to break it —
  synthesis and adversarial reasoning, not checklist verification.
- **Never Haiku** for any agent in this workspace, matching the
  sibling repos' standing rule.

## Why six, not twenty

lance-graph's ensemble is sized for a 26-repo, multi-year cognitive
architecture. This repo is a single vertical slice proving one
architectural claim (Panama membrane + `ndarray::simd` population +
Valhalla-for-the-tiny-vocabulary). Six agents cover its actual seams —
the ABI contract, SIMD provenance, handle safety, Java API ergonomics,
FFM correctness, and measurement discipline — without manufacturing
specialists for concerns this repo doesn't have. If the repo grows a
real second concern (e.g. a real graph query surface once
`ClassView`/`WideFieldMask` get wired in, per `docs/abi.md` §10's
"what is deliberately absent"), add a card for it then, not now.

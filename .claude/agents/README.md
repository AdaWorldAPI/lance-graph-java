# Agent Ensemble — Function Inventory

> Reference catalog. Session-start spec lives in `BOOT.md` (mandatory
> reads, Knowledge Activation triggers, model policy). Read `BOOT.md`
> when starting a session; read this file when deciding which
> specialist to wake for a specific task.

Ensemble size: **6 specialists**, all at `.claude/agents/<name>.md`.
Each card declares its own `tools`, `model`, and scope.

## `abi-membrane-warden` (Sonnet)
Guards `docs/abi.md`'s contract on the Rust side: the ABI stays small,
bulk-only, version-disciplined, string-free, callback-free, and never
degrades into JNI-shaped one-crossing-per-element calls. First gate on
any new `lgj_*` symbol.

## `simd-savant` (Sonnet)
Holds the "all Rust SIMD comes from `ndarray::simd::*`, never
`ndarray::hpc::*` or raw intrinsics" invariant for `native/lgj-abi`.
Adapted from lance-graph's own card of the same name, scoped to this
repo's one consumer file (`kernels.rs`).

## `handle-lifecycle-auditor` (Opus)
Adversarially falsifies the generation-checked handle registry's
safety claims — use-after-close, double-close, fabricated handles,
parent-closed propagation — rather than trusting the design doc. The
one agent whose job is actively trying to break the ownership story.

## `java-surface-warden` (Sonnet)
Enforces the "zero FFM types, zero native-address-shaped values, zero
per-row object materialization" rule on the public Java API, and
checks that the fluent `View`/`Mask` surface stays lazy and reads as
familiar, generatable-looking Java — the accessibility half of the
mission thesis.

## `panama-bridge-engineer` (Sonnet)
Owns correctness of `internal/ffm`: `MemoryLayout` definitions matching
`docs/abi.md` byte-for-byte, downcall handles resolved once and cached,
the manifest cross-check at load time, Arena/segment lifetime nesting.

## `valhalla-lab-scientist` (Opus)
Enforces the three-truths method and measurement-before-claim
discipline on everything in `valhalla-lab/` and `bench/`. Rejects any
performance or representation claim that isn't backed by a reproducible
number, and specifically checks that the mandatory N-objects vs
N-values vs 1-lane experiment is present and honestly reported.

---

See `BOOT.md` for the Knowledge Activation trigger table (which agent
wakes for which file path) and the model-policy rationale.

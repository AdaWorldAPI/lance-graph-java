# abi-membrane-warden — ruling on PR4 (proposed, pre-implementation)

**Scope:** `docs/abi.md` contract only. Not Java ergonomics (java-surface-warden),
not SIMD provenance (simd-savant), not registry internals (handle-lifecycle-auditor).
Read-only pass: no code written, no cargo run.

**Subject:** replace `native/lgj-abi/src/exports.rs::plan_eval_impl`'s allocating
evaluator loop with the shared `lance-graph-mask-risc` / `ndarray::simd` floor.

## Verdicts

| Q | Verdict |
|---|---|
| Q1 path dep on `lance-graph-mask-risc` | **PERMITTED, GATED** — fence extension owed in the same commit |
| Q2 ABI surface change | **NO surface change** (29 symbols, same struct, same manifest, no minor bump) — but **BLOCKED on a semantic gap** |
| Q3 where scratch lives | (B) resource-owned **BLOCKED**, (C) scratch handle **BLOCKED**, (E) Java-owned **BLOCKED**, (D) thread-local admissible, **(F) substrate-first is the ruling** |

## Q1 evidence

- `tests/g11_contract_import_fence.rs` `const CRATE = "lance_graph_contract"`,
  `const ALLOWED = ["canonical_node","class_view","facet","ontology"]`. It scans
  `lgj-abi/src` for that literal ONLY. A `lance_graph_mask_risc::` import is
  invisible to it — **the fence does not speak to a new crate dep at all.**
- mask-risc is contract-shaped: its `Cargo.toml` has EXACTLY ONE dependency,
  `ndarray { default-features = false, features = ["std"] }` — identical
  coordinates to lgj-abi's own line. Zero contract dep ("`Planes::masks` is
  `&[&[u64]]`"). `#![forbid(unsafe_code)]` (lib.rs:79). No `cfg(target_feature)`
  (law L3). Workspace MEMBER (`lance-graph/Cargo.toml:8`), so CI-covered.
- No new `[patch]` risk: the existing `[patch."…/lance-graph"]` exists because
  `ogar-class-view` pulls the contract by git; mask-risc pulls nothing.
- **Gap:** G11 scans `src/` only, so a future mask-risc contract import is
  invisible from here and the engine could arrive transitively.

**Keeping it honest:** extend `g11_contract_import_fence.rs` with a CRATE
allowlist — parse `[dependencies]` from `lgj-abi/Cargo.toml` (and mask-risc's)
and assert equality with a named `ALLOWED_CRATES`, reusing the existing
`documented_allowlist` / `g11_documented_allowlists_equal_the_enforced_one`
shape. Disable-verify with a bogus dep line.

## Q2 evidence

- Surface is exactly **29** `pub [unsafe] extern "C" fn` in exports.rs, matching
  abi.md §7/§2 minor 11. (`lgj_rowstore_open_handle` at :3741 is a `cfg(test)`
  helper, not an export.)
- `plan_eval_impl` (:1680) is PRIVATE; `lgj_plan_eval` (:1778) and
  `lgj_plan_eval_scalar` (:1803) signatures unchanged.
- `LgjOpDesc` unchanged: opcodes 1..9 (abi.rs:263-306) map **1:1 and
  exhaustively** onto `mask_risc::Pred` (ir.rs:62-83). Manifest unchanged
  (`size_of_op_desc`/`align_of_op_desc` derive from an unchanged struct).
  **No minor bump owed** — a body swap adds nothing observable.
- PR4 must NOT take a new symbol for scratch: abi.md §1 (growth is a smell),
  §19.4 precedent (15 capabilities / 3 symbols), §4 ("Rust. Always.").

**BLOCKER — the all-set accumulator is inexpressible in mask-risc's IR.**
abi.md §7 normative `V0 = all`; exports.rs:1718-1723 implements it.
`MaskOp` (ir.rs:95-120) = {Pred, And, Or, Xor, AndNot, Not, Ternlog} — **no
Fill/Const**. Pre-filling a `Scratch` slot is refused by design:
`ExecError::ScratchReadBeforeWrite` (value.rs:56), enforced in `validate`
(reference.rs:106) called by BOTH `execute` (exec.rs:376) and
`reference_execute` (reference.rs:348); its doc says "pre-filled scratch is a
named PR5 gap, not a supported input."
The tempting rewrite (start from `op0`) holds only for `combine == AND`
(`all ∩ op0 = op0`, abi.md §19.4). For `ops[0].combine == OR` the answer is
`all ∪ op0 = all` — every row. **And no test pins it:** `LGJ_COMBINE_OR`
appears at exports.rs:2792 and :2958, both at op index >= 1.

Unblock: `MaskOp::Fill { value, dst }` lands in mask-risc FIRST (+ reference
arm, differential case, no_alloc case, tail-clear semantics). The
`Pred; Not; Or` fabrication is available without an IR change but burns a full
O(n_rows) sweep to synthesise a constant — reject it.

**Hazard 2 — `Path::Scalar` != `reference_execute`.** kernels.rs:814-819 selects
between two word-parallel implementations; `reference_execute` is a row-at-a-time
oracle that never touches ndarray (law L4) and allocates (reference.rs:396).
Re-pointing `lgj_plan_eval_scalar` at it strengthens the claim but changes what
abi.md §7's membrane parity test compares. Rule deliberately; do not let it drift.

**Hazard 3 — ternlog owner (mask-risc lib.rs:19-23).** lgj carries
`kernels::simd_mask_ternlog_assign_dyn` (kernels.rs:360-362); mask-risc carries
`ternlog_dispatch` (758 lines). Real duplication, but its call sites are
`lgj_mask_ternlog` (:816) and `lgj_hop` (:2162, const-generic `::<AND3>`, not
even the dyn table) — NOT plan_eval. File separately; do not bundle.

## Q3 evidence

- **(B) resource-owned — BLOCKED.** registry.rs:123-127: `Payload::Pattern` is
  "read-only … no lock needed, because no ABI path mutates it"; RowStore
  "likewise lock-free". Only `Mask` has a `RwLock` (:129), and :120 says why
  ("so bulk ops on distinct masks do not serialize"). Mutable scratch on the
  pattern needs a lock (first same-resource serialization point in the ABI;
  today two `plan_eval`s on one pattern are fully parallel) or unsound interior
  mutability across the cloned `Arc<ResourceEntry>` (registry.rs:133-134).
  abi.md §4 already admits contention is unbenchmarked. **No precedent:** grep
  of `lgj-abi/src` finds only `OnceLock` (registry.rs:226,
  class_view_provider.rs:84/:298 — init-once) and the mask `RwLock`. No Cell /
  RefCell / Mutex / static mut / thread_local anywhere.
- **(C) scratch handle — BLOCKED.** Needs >=2 lifecycle symbols (Q2 rules that
  out) AND a parameter on an existing symbol = **major** bump (abi.md §2). Drags
  scratch into the sole-closer contract and PARENT_CLOSED for zero capability.
- **(E) Java-owned buffer — BLOCKED.** Signature change (major) + hands Java a
  writable native buffer for general use, against CLAUDE.md "mutation crosses as
  verbs, not writable memory" whose one exception is `RowStore.importRows`.
- **(D) thread-local — admissible, not first.** Invisible at the ABI, ownership
  stays Rust, no lock added. Honest costs: per-thread retention for process life,
  `catch_unwind` (§9) must not leave an inconsistent arena, and it would be the
  first `thread_local!` in this crate.
- **(F) substrate-first — THE RULING.** Two measured reasons:
  1. Allocation census, exports.rs non-test: `lgj_row_facet_match` :1558 (1);
     `lgj_rowstore_facet_match_count` :1627 (1); `plan_eval_impl` :1718+:1724
     (2); **`lgj_hop` :2090 `to_vec()` + :2097 + :2133 + :2134 (4)**;
     `mask_ternlog_impl` :736/:740/:750 (up to 3). plan_eval is 2 of ~11 and
     **not the worst site**. A bespoke fix here gets invented twice.
  2. Missing-capability STOP rule: the lacked capability is "reusable
     non-allocating scratch for `extern "C"` bodies". mask-risc's `Scratch`
     (exec.rs:46-102) + `tests/no_alloc.rs` IS that capability — but it lacks
     (i) `Fill`, (ii) any contract for reuse across differently-shaped programs.

**Trap that must be named:** `Scratch::for_program` (exec.rs:63-75) allocates
`scratch_slots × words` PER CALL. A `plan_eval_impl` that builds a `Program`
(`Vec<MaskOp>`, ir.rs:169) + fresh `Scratch` each downcall replaces two 8 KB
`vec!`s with a `Vec<MaskOp>` PLUS the same two 8 KB buffers — strictly worse
while looking like the fix. mask-risc's `no_alloc` test builds both ONCE outside
its 1000-iteration loop: **the zero-alloc law is a property of `execute`, not of
getting to `execute`**, and the per-call path is PR4's entire premise.

## Falsifiers owed (each disable-verified red-then-green)

1. `ops[0].combine == OR` → `out_count == n_rows`, dst all-set clean tail.
   **Land BEFORE the swap** — currently untested, and it is the exact semantic
   the likely rewrite breaks silently.
2. Native allocation gate on `lgj_plan_eval` (counting-global-allocator shape
   mask-risc already uses), population-size independent. No current gate
   measures the native side.
3. Existing `lgj_plan_eval` vs `lgj_plan_eval_scalar` parity suite green,
   unchanged — if it moves, Hazard 2 fired.
4. G11 crate-allowlist test, disabled by a bogus `[dependencies]` line.

## Measurement caveat

"16 KB/call" is arithmetic (`n_words = 1024` × 2 × 8 B), **not a measurement**.
Repo rule is measure-then-pin; R8's recorded finding is that bulk crossings cost
nothing. PR4 must carry a before/after number or drop the performance framing —
the architecture argument (ONE evaluator, not two) is the strong one and stands
alone.

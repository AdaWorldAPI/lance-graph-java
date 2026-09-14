# pr4-plan-eval-mask-risc-equivalence-v1 — the falsifier set for PR4

> **Status:** PROPOSAL (2026-09-14). Read-only design pass, authored by an Opus
> planner and persisted by the orchestrator. Scope: the falsifier set only — the
> tests, gates, disable table and commit order that make PR4's claims
> refutable. It does not design the lowering; it designs what would catch a
> wrong one. Nothing here was run; nothing is claimed to compile or pass.
>
> Companion rulings, same session:
> `.claude/board/exec-runs/pr4-abi-membrane.md` (ABI surface + scratch
> ownership) and `.claude/board/exec-runs/pr4-kernel-membrane.md` (T1/T2 tier,
> the duplicate ternlog table). Read all three before writing PR4 code.

## §0 — What PR4 changes, and the three separate claims it makes

`native/lgj-abi/src/exports.rs::plan_eval_impl` (`:1680`-`:1756`) today:
validate the plan (`validate_plan`, `:1651`), allocate `acc` and `scratch`
(`:1718`, `:1724`), seed `acc` to all-ones + `clear_tail_bits`, then per op
`kernels::eval_predicate` followed by `kernels::combine_into`, then a final
`clear_tail_bits`, `kernels::popcount`, and one `copy_from_slice` publish.

PR4 replaces the opcode loop with the shared mask-risc floor and removes the
per-call allocation. That is **three claims needing three kinds of evidence**:

| # | Claim | Evidence that can settle it |
|---|---|---|
| **C-BEHAVE** | the new `plan_eval` answers **identically to the old one** | a differential against a **frozen copy of the old loop** — nothing else |
| **C-ALLOC** | no per-call allocation, **at any row count** | a Rust-side counting global allocator, swept over n and both directions |
| **C-ONE** | there is now **ONE** evaluator, not two | a **structural** guard, plus an instrument for the cost half |

Conflating these is the standard failure. C-ONE is not falsifiable by any
differential: two evaluators that agree are exactly what a differential
certifies.

## §1 — The behaviour-equivalence falsifier

### 1.1 The reference side: **both**, and the old loop is load-bearing

Three answers per input:

1. **`legacy_plan_eval_impl`** — a byte-for-byte frozen copy of today's body,
   moved into `#[cfg(test)]`, with a header comment pinning the pre-PR4 sha.
2. **the new `lgj_plan_eval`** (mask-risc `execute`).
3. **`lgj_plan_eval_scalar`** (see §1.2).

**Why mask-risc's own oracle is not enough, and this is the central point.**
PR4 introduces a component that did not exist before: the lowering
`&[LgjOpDesc] -> mask_risc::Program`. It sits ABOVE both `execute` and
`reference_execute`. A bug in it (a swapped opcode, a dropped all-ones seed, a
mis-packed TCAM operand) produces the same wrong `Program` for both, both
evaluate it faithfully, and mask-risc's differential stays green. Only an
oracle that never sees the `Program` catches it. That oracle is the old loop.

**Freeze discipline (non-negotiable).** The frozen copy must not be refactored
to share a helper with the new path — a shared helper is a shared bug and turns
the differential into a tautology. Two checks: the copy calls only
`kernels::{eval_predicate, combine_into, popcount}` + `clear_tail_bits`; and
disable row **D7** breaks the new path only, then the oracle only, asserting
the differential reddens both times. One direction alone leaves "the oracle is
inert" indistinguishable from "the guard works".

### 1.2 The `lgj_plan_eval_scalar` fork — decide explicitly

`kernels::Path::{Simd, Scalar}` exists so SIMD/scalar parity is falsifiable
through the membrane. mask-risc has no `Path`.

- **Fork A — scalar -> `reference_execute`.** Elegant, but `reference_scratch`
  **allocates by design**, so **C-ALLOC's gate must be scoped to
  `lgj_plan_eval` only**. A gate accidentally written against the scalar symbol
  is green-for-the-wrong-reason; row **D9** disables exactly that.
- **Fork B — scalar keeps `kernels::scalar_*`.** Two evaluators remain, so
  C-ONE must be narrowed in the PR text to "one *SIMD* evaluator", with the
  structural guard narrowed to match or it is decoration.

State the fork in the PR description. Do not let it be settled by whichever
code compiles first.

### 1.3 The input space

Compare **status, `out_count`, and `dst_mask` words bit-for-bit including the
tail word**, across:

- **row counts** `[0, 1, 63, 64, 65, 127, 128, 999, 1000, 4097, 65_536]`.
  `999` is deliberate — not in the allocation gate's warm-up set (§2.4) and in
  no existing test.
- **seeds** `[0, 7, 0xFEEDFACE, 0xABCD]`.
- **op counts** `{1, 2, 3, 4, 8}`. Nothing today exceeds 4.
- **combine modes** — the full `{AND, OR}^n` product at `n <= 3`, sampled at 4
  and 8. **The most important axis, and currently 1-dimensional** (trap V1).
- **opcodes** — all nine, x **three positions**: first, non-first after AND,
  non-first after OR. Today the seven minor-11 opcodes appear only in
  single-op plans.
- **operands** — per opcode, one selecting ~nothing, ~half, ~everything.
- **TCAM** (op 9): `care in {0, 0b111, 0b1000, u32::MAX}` x `pattern in
  {5, 7, 0xDEADBEEF}`, always including `pattern != care`.
- **error inputs** — the five from `a_bad_plan_leaves_dst_mask_untouched`, each
  ALSO at a non-first and a last position, plus `MASK_LENGTH_MISMATCH` and
  `EMPTY_PLAN` with and without a null `ops`.
- **dst_mask prior state** — `{fresh-empty, fresh-all, re-used, poisoned via
  set_words with a dirty tail}`. **Entirely absent today** (trap V4).
- **scratch prior state** — `{cold, primed dense, primed sparse}` (trap V3).

### 1.4 The vacuity traps — named BEFORE they are written

Every one of these is a fixture that a wrong PR4 would pass today.

**V1 — every existing plan begins with AND.** Verified by reading every
`LGJ_COMBINE_OR` site: `:2792` and `:2958` are both the *second* op. No test
anywhere has a leading OR. Old semantics are "acc starts as all rows set", so
a plan whose FIRST op is OR must return **all rows**, regardless of the
predicate. A lowering taking the natural shortcut — *"with an all-ones
accumulator the first AND is a no-op, so let `acc := pred_0`"* — and applying
it unconditionally is **wrong only for a leading OR**, and passes 100% of the
current suite.
> Falsifier **F-PR4-SEED**: for every opcode and operand, a 1-op plan with
> `combine = LGJ_COMBINE_OR` yields `LGJ_OK`, `out_count == n_rows`, and
> `dst_mask` all-ones with a clean tail. Asserted against the frozen oracle,
> not a hand-derived expectation.

**V1b — leading-OR and the tail trap compose.** `n = 65`, single OR op: correct
answer `count == 65`. A whole-word all-ones seed without `clear_tail_bits`
gives `128`. This combination exists in no test today. Assert the count AND
`words.last() >> (n % 64) == 0`, so a failure names which defect fired.

**V2 — the "then == els" analogue is an exhausted accumulator.** Once
`acc == 0` every subsequent AND is a no-op and ops `k+1..n` are unexercised;
once all-ones, every subsequent OR is. The existing monotonicity test asserts
non-degeneracy only at the END, and only for one plan.
> Anti-vacuity **A1**: for every plan designated non-degenerate, at **every
> prefix k**, `0 < count_k < n_rows` (guarded at `n_rows >= 3`). Degenerate
> plans get explicitly-named cases and are excluded by name, never by an
> unstated `if`.

**V3 — the scratch-reuse trap, which mask-risc structurally cannot see.**
`reference.rs`'s own doc says it: *"a divergence no fixture built from
`Scratch::for_program` can express, because every such fixture starts
zeroed."* PR4's whole allocation win is exactly that reuse. Worse: a gated
predicate that SKIPS gated-off words instead of WRITING zero is
indistinguishable from a correct one under a fresh zeroed arena, and becomes a
stale-bit leak the moment the buffer is reused. [unverified: whether ndarray's
`pack_under` writes or skips is the open question — this falsifier answers it
rather than assuming.]
> Falsifier **F-PR4-REUSE**, through the ABI with no new API: (1) run a plan
> selecting everything so scratch ends dense; (2) run a selective multi-op plan
> into a DIFFERENT dst; (3) assert (2) is bit-identical to `legacy` for (2).
> Repeat inverted, with alternating `n_rows` and patterns. Plus idempotence.

**V4 — `dst_mask` prior content and its dirty tail.** The most tempting
allocation saving is to make the destination's own words the accumulator.
Nothing today catches accumulating INTO dst rather than overwriting: the
monotonicity test reuses one mask and an AND-into-prior-content still produces
a shrinking chain — it passes.
> Falsifier **F-PR4-DST**: for each of `{fresh-empty, fresh-all, re-used,
> poisoned}`, the same plan yields identical status, `out_count` and words; and
> the published tail word is zero even when the prior tail was `u64::MAX`.
> Separate row: `dst_mask` must NOT be passed to `Planes::masks` — `validate`
> refuses a plane with a dirty tail (`ExecError::PlaneTail`), so a poisoned dst
> would turn a clean overwrite into a spurious error. `Planes::masks` is `&[]`.

**V5 — arms no `LgjOpDesc` can reach.** The lowering emits only `Pred`, `And`,
`Or` (+ possibly one `Ternlog{0xFF}` seed). So `AndNot`, `Not`, the
non-commutative in-place remap, `remap_imm`'s 3-cycles, and 255 of 256
immediates are unreachable from any admissible plan. **Do not claim PR4's
differential covers them** — that coverage lives in
`lance-graph-mask-risc/tests/differential.rs`, inherited as a dependency, not
as evidence. If PR4 seeds via `Ternlog{0xFF}`, that is `ternlog_self`'s
fill-ones arm — the arm the mask-risc council found vacuous — and F-PR4-SEED
becomes its second consumer and must say so.

**V6 — the TCAM operand halves.** `(care << 32) | pattern`. A swap is invisible
whenever `pattern == care`, and the existing partial-care case uses
`tcam_operand(0b1000, 0b1000)` — exactly that. The `care=0`/`care=MAX` cases do
pin the halves, but **only at position 0**.
> Falsifier **F-PR4-TCAM**: `tcam_operand(5, 0b111)` at positions 0,
> 1-after-AND and 1-after-OR. `pattern != care` in every one.

**V7 — opcode identity under the gate.** A mis-map is caught at position 0
(with an all-ones accumulator a 1-op AND plan IS the predicate). It is NOT
caught if the mis-map exists only in the gated (`under: Some`) arm — a
plausible shape, since AND- and OR-combines take different routes. Hence the
opcode x position matrix.

**V8 — the error path at a non-first position.** The existing test puts the bad
op second in all five cases, never first or last in a 4-op plan. Extend to
positions `{first, middle, last}` and `n_ops = 4`. An `ExecError -> LGJ_ERR_*`
map that collapses two codes passes a test that only checks "dst unchanged".

**V9 — `out_count` not written on failure.** Under mask-risc the count arrives
as `Value::Count`; a `let mut count = 0; ... *out_count = count;` written
before the error check restores the bug. Keep the `12345` sentinel shape.

## §2 — The allocation falsifier

### 2.1 What is removed

`:1718` + `:1724` = `2 * n_words * 8` bytes per call. At `n_rows = 65_536`,
`n_words = 1024` -> 16 KiB/call. **State the number with its row count
attached; it is not a constant, and it is arithmetic, not a measurement.**

### 2.2 The counter, and where it lives

**Rust side, not Java side.** `getThreadAllocatedBytes` — the counter this
repo's existing allocation gates use — measures **Java heap**. It cannot see a
Rust `vec!`. Citing the existing Java gates as evidence for C-ALLOC would be an
overclaim. Say so in the PR body.

The instrument is the one `lance-graph-mask-risc/tests/no_alloc.rs` uses: a
pass-through `#[global_allocator]` over `System` incrementing an
`AtomicUsize`. Placement: a new integration test
`native/lgj-abi/tests/plan_eval_no_alloc.rs` — a separate binary, so the global
allocator does not perturb the in-crate suite. lgj-abi is `["cdylib","rlib"]`,
so the rlib links and the exports are callable directly.

**Header constraint:** this binary contains **exactly one `#[test]`**. The
counter is process-global; a second concurrent test adds bytes and flakes. Do
not "fix" that by relaxing `== 0` to a tolerance — that is how the gate dies.

### 2.3 BLOCKING design finding: unreachable with today's mask-risc `Scratch`

`execute` requires `scratch.words == words_for(planes.n_rows)` **exactly**,
returning `ExecError::ScratchWords` otherwise, and `Scratch`'s only
constructors allocate. Consequences:

- one cached `Scratch` sized for 65_536 rows **cannot serve a 64-row call**;
- a cache keyed by `words` allocates the first time each distinct `n_rows` is
  seen — allocation becomes a function of the population's HISTORY, the exact
  property C-ALLOC denies. A gate that warms up over a fixed sweep and then
  measures the same sweep is **green while the property is false**. The
  `n = 999` arm is what reddens it;
- relaxing the check to `>=` is **not** safe: `clear_tail` clears only the tail
  WORD while ndarray's `mask_not` zeroes every word PAST it, and they agree
  only on exactly-sized buffers. An oversized scratch reopens the tail law.

**Clean resolution: a mask-risc co-change that lands FIRST** (same rule that
put D-MRX-0 in ndarray before PR3): a borrowing constructor, shape
`Scratch::over(buf: &mut [u64], slots, words)`, carving exactly
`words_for(n_rows)` per slot from one caller-owned growing buffer. Upstream
falsifier: pre-poison the buffer past `slots * words_for(n)` with `u64::MAX`
and assert every result and every `slot()` read is unchanged.

**If PR4 ships without it**, narrow C-ALLOC in the PR text to "zero per-call
allocation for row counts already seen by this process", write the `n = 999`
arm anyway marked `#[ignore]`, and record the STOP in `ISSUES.md` — never
silently omit it, because an omitted arm reads as a passing property.

### 2.4 The exact assertion

One test, one binary. Handles opened and fixture generated BEFORE the baseline.

```
SWEEP  = [0, 1, 63, 64, 65, 127, 128, 1000, 4097, 65_536]
UNSEEN = 999                       // NOT in SWEEP, never warmed
PLAN   = 3 ops, mixed AND/OR/AND, mixed opcodes and lane kinds

// warm-up: one call at every n in SWEEP
let before = BYTES.load(Relaxed);
for _round in 0..64 {
    for &n in SWEEP.iter()       { plan_eval(pattern[n], PLAN, dst[n]) }  // ascending
    for &n in SWEEP.iter().rev() { plan_eval(pattern[n], PLAN, dst[n]) }  // descending
}
assert_eq!(BYTES.load(Relaxed) - before, 0, "... per-n: {per_n:?}");

let b = BYTES.load(Relaxed);
plan_eval(pattern[UNSEEN], PLAN, dst[UNSEEN]);
assert_eq!(BYTES.load(Relaxed) - b, 0,
    "a row count this process has not seen before must still allocate nothing");

// can-it-fire, without which the file proves only that the counter is broken
let a = BYTES.load(Relaxed);
let probe = vec![0u8; 4096];
assert!(BYTES.load(Relaxed) - a >= probe.len());
```

**Why this is population-size independent, precisely.** Three mechanisms, and
the failure message names which: (1) the sweep spans 0..65_536, so a gate that
holds only at one n cannot be green; (2) both directions in the same round — a
cache reallocating on GROW is caught ascending, on SHRINK descending; (3) an
unseen n — a per-n cache is green under (1) and (2) after warm-up and red only
here.

**Not evidence:** RSS, `mallinfo`, wall-clock, or any Java-side counter.

## §3 — The disable-run table

Standing rule: a guard is not verified until its disable run is
**red-then-green**. Two traps designed around here:

> **(a) Zeroing a constant is not a disable when the guarded quantity can reach
> the same outcome by another route.** Every row names WHICH test must go red,
> and the operator records the FULL set that went red — a disable reddening
> twenty tests localizes nothing.
>
> **(b) An edit whose anchor has moved silently no-ops, and a green run then
> reads as "the guard is inert."** Every disable is applied by a script that
> asserts `git status --porcelain` is EMPTY first, asserts the anchor matched
> **exactly once**, asserts the result differs from the input, runs, then
> restores with `git checkout <file>`. Anchors are authored AFTER `cargo fmt`
> and a re-read. (Recorded failure: anchors written against pre-`fmt` text, three
> edits silently no-op'd, three guards reported GREEN having never landed.
> Recorded a second time on the PR3 arc: a pin added mid-pass was destroyed by
> the restore, because the restore reverts to the last COMMIT.)

| # | Guard | Mutation that must turn it red | Must go red | Trap note |
|---|---|---|---|---|
| **D1** | F-PR4-SEED (leading OR => all rows) | make the first op's result the accumulator **unconditionally** | leading-OR arms only; all-AND stays green | (a): seeding to `0` reaches the same wrong answer by another route — that is D2. Record each separately |
| **D2** | the all-ones seed | seed to all-**zero** | all-AND multi-op arms + `fused_plan_equals_the_unfused_composition` | if this reddens the SAME set as D1, one guard is redundant — say so rather than counting both |
| **D3** | seed tail clear | delete `clear_tail_bits` on the seed | `n in {63,65,127,999,4097}` leading-OR arms | (a): running at `n % 64 == 0` is NOT a disable — the quantity is unreachable there |
| **D4** | final `clear_tail_bits` | delete it | F-PR4-DST tail assertion at `n = 65`, plus `out_count` there | as D3 |
| **D5** | F-PR4-DST (overwrite, not accumulate) | change publish from `copy_from_slice` to `\|=` | re-used and poisoned arms; NOT fresh-empty | this row proves the fresh-mask tests were never evidence for this property |
| **D6** | tail repair on publish | skip the tail word | the poisoned-dirty-tail arm | |
| **D7a** | differential independence, new side | in the NEW path only, map `LE_I32 -> Pred::LtI32` | the whole opcode x position matrix | (a): if done where both paths read it, nothing reddens — and the correct reading is "the oracle is not independent", not "the guard is inert" |
| **D7b** | differential independence, oracle side | in the FROZEN oracle only, swap AND and OR | the same matrix | both directions required |
| **D8** | F-PR4-TCAM | swap `pattern` and `care` in the unpack | the `pattern != care` arms at all three positions | (a): the existing `tcam_operand(0b1000,0b1000)` survives this — it is vacuous fixture V6 |
| **D9** | C-ALLOC gate, correct symbol | route the gate through `lgj_plan_eval_scalar` | Fork A: must go RED. Fork B: stays green, and THAT is the tell the gate does not distinguish the symbols — narrow the claim | the gate names its symbol in its own failure message |
| **D10** | C-ALLOC gate, sizing | put `let _ = vec![0u64; n_words];` back in the new path | `plan_eval_no_alloc` | (a): a `vec![0u8;1]` proves only the counter moves; `vec![_;0]` allocates NOTHING — run at `n_rows = 65_536`, never at `n = 0` |
| **D11** | C-ALLOC population independence | key the scratch cache on `words` | the `UNSEEN = 999` arm ONLY | if the sweep arms also redden, the cache was never shared and the gate measured something else |
| **D12** | C-ONE structural guard | add a second opcode-dispatch `match` in a production module | the structural guard | (a): if written `<= 1`, deleting BOTH sites also passes. Must be `== 1` with the allowed site NAMED, as `exactly_one_materialiser` names `reference_scratch`. Second disable: delete the one allowed site, assert red for "zero" too |
| **D13** | the `ExecError -> LGJ_ERR_*` map | delete one arm (fold two codes into one) | `..._rejects_a_lane_of_the_wrong_kind` + extended `a_bad_plan_leaves_dst_mask_untouched` | once per arm, not once for the map |
| **D14** | F-PR4-REUSE | stop zeroing the ping-pong slot between calls / route the gated predicate to SKIP rather than write | reuse arms; NOT fresh-call arms | if no code change makes this red, the gated predicate writes totally and V3 closes as **verified negative** — record that, it is a result |
| **D15** | `out_count` not written on failure | move the write above the error check | the `12345` sentinel | |

**Deliberately excluded, with the reason stated:** A1, the `lt + ge == n` /
`eq + ne == n` partitions, and the fixture-fraction pins. These are **fixture
anti-vacuity checks, not code guards** — no production line's deletion reddens
them, and pretending otherwise inflates the table. They get a different check:
mutate the FIXTURE (move the `GT_I32` threshold to `-1000`) and assert A1
fires. Run once, record once, exclude from the code-disable table.

## §4 — The ordering, and where the commit goes

**COMMIT, THEN DISABLE, THEN RESTORE.** The restore is `git checkout <file>`,
which reverts to the last COMMIT; uncommitted work is destroyed by it. **No
disable run begins while `git status --porcelain` is non-empty**, and that check
is the first line of the script, not a habit. Anything added mid-pass restarts
the clock.

| Commit | Content | Tree | Disable runs |
|---|---|---|---|
| **C0** *(sibling repo, if §2.3's co-change is taken)* | `lance-graph`: the borrowing `Scratch` constructor + poisoned-buffer falsifier. Merges FIRST | green | its own, after C0 is committed |
| **C1** | the frozen oracle + ALL behaviour falsifiers of §1 — **written against the OLD implementation, still in place** | **green** — the load-bearing property of the whole ordering | — |
| | *Gate at C1:* every new test green BEFORE any implementation change. A test red here has found a **pre-existing defect in the old loop**. Report it; do not fix it silently in the same PR — a silently fixed old bug changes what "equivalent to the old" means | | |
| **C2** | the lowering + `plan_eval_impl` routed through `execute`; the error map; the path dep; the scalar fork decision; **board hygiene in the same commit** | green — **C1's tests pass UNCHANGED.** Editing a C1 test in C2 is how an equivalence claim dies; if one must change, that is a finding | after C2: D1-D8, D13-D15 |
| **C3** | `tests/plan_eval_no_alloc.rs` + the C-ONE structural guard | green. Both were RED at C2^ and that run IS their red-then-green evidence — record the sha | after C3: D9-D12 |
| **C4** | only if §2.3's STOP is taken without C0: the `#[ignore]`d arm + the `ISSUES.md` entry | green | — |

ABI surface: behaviour unchanged, therefore **`abi_minor` must not move**. Add
`lgj_abi_manifest().abi_minor == <current>` to the manifest test — a weak guard,
but it catches the reflexive bump. `docs/abi.md` needs no section.

## §5 — What I would NOT test, and why

**5.1 Which routing the AND-combines take.** Gated (`Pred{under: Some(acc)}`)
vs `Pred{under: None}` + `mask_and_assign` is **semantically identical** — this
is F-X1 from the mask-risc arc, inherited verbatim. Its instrument there was
`count_probe`'s sparse-gate pair (3572 ns vs 10729 ns at count 1741). PR4's
analogous claim needs the same: **a probe, never a threshold assertion.**
Measure-then-pin means a number in the board entry, not an `assert!` in CI.

**5.2 Whether the code physically goes through `execute`.** No behavioural test
distinguishes "delegates" from "reimplemented identically inline". That is
C-ONE, and its only honest evidence is a **structural guard** in the family of
`the_crate_names_no_isa` / `exactly_one_materialiser`: exactly one production
opcode-dispatch site, allowed site NAMED, `== 1` never `<= 1`.

**5.3 Where the scratch buffer lives.** Registry-owned vs thread-local vs
carved-from-dst is invisible to the allocation counter — all three read zero
once warm. The CONSEQUENCES are testable and are rows above: reuse-staleness
(V3/D14), per-n allocation (D11), dst accumulation (V4/D5).

**5.4 Memory-footprint deltas.** If scratch moves onto the mask resource, two
masks over one pattern each carry a buffer — a real regression, but RSS is not
a gate. A probe printing the total across `mask_create`, recorded as a number.

**5.5 The mask-risc arms PR4 cannot reach** (V5). Do not test them here, do not
claim them. Cite the sibling suite by path.

**5.6 Backend parity across ISAs.** `execute` carries no ISA (law L2). CI builds
`x86-64-v3`; `-v4` is local-only and there is **no v4 CI arm**. Do not write
"verified on all backends."

## Open questions PR4 must close before writing code

- **OQ-1 (blocking).** §2.3: the borrowing `Scratch` co-change (C0), or the
  narrowed C-ALLOC claim + `ISSUES.md` entry. Picking neither means the
  allocation gate is green for the wrong reason.
- **OQ-2.** §1.2: Fork A or Fork B for `lgj_plan_eval_scalar`.
- **OQ-3.** Does ndarray's `pack_under` WRITE zero into gated-off words or SKIP
  them? [unverified.] V3/D14 answers it, and the answer decides whether scratch
  reuse is safe at all.
- **OQ-4.** The G11 fence covers `lance_graph_contract::` module names only. A
  new crate dependency is outside it — see `exec-runs/pr4-abi-membrane.md` Q1
  and `exec-runs/pr4-kernel-membrane.md` (c), which both require the fence to
  grow a crate allowlist **in the same commit as the dep**.
- **OQ-5 (from the ABI ruling, not this pass).** `mask_risc::MaskOp` has **no
  Fill/Const op**, so the all-set accumulator `V0 = all` that `abi.md` §7
  mandates has no representation. See `exec-runs/pr4-abi-membrane.md` — this is
  a second substrate-first blocker, and V1/F-PR4-SEED is the falsifier for the
  rewrite that would paper over it.

---

## OQ-5 — RESOLVED (2026-09-14, main thread). No `Fill` op is needed.

The all-set accumulator is not a missing IR primitive. It is a statement about
a **prefix of the op list**, decidable at lowering time.

### The algebra

`abi.md` §7 is `acc_0 = ALL`, then `acc_i = acc_{i-1} ⊕_{combine_i} pred_i`.
Two facts settle it:

- `ALL & p = p` — an AND against the all-set accumulator **is** the predicate.
- `ALL | p = ALL` — an OR against it is a **no-op that discards `pred_i`**.

So let `k` = the least index with `ops[k].combine == AND` (`k = n` if none).
Every op before `k` ORs into `ALL` and leaves it `ALL`; op `k` reduces to
`acc = pred_k`. Therefore:

- **`k < n`** — drop ops `0..k`; lower op `k` as
  `MaskOp::Pred { under: None, dst: 0 }` (a direct write, no seed); lower
  `k+1..n` as their own combine into slot 0. Exact.
- **`k = n`** (every combine is OR) — the result is `ALL`, tail-cleared, and
  `count = n_rows`. A constant; no program runs.

### Why dropping ops `0..k` cannot change ERROR behaviour

This is the half that had to be checked rather than assumed, and it holds by
construction rather than by test. After `validate_plan` returns `Ok`,
`kernels::eval_predicate` is **infallible** for every op in the plan: its only
two error arms are `LGJ_ERR_LANE_KIND_MISMATCH` and `LGJ_ERR_UNKNOWN_OPCODE`
(`kernels.rs:928-938`), and `validate_plan` already rejects both, for every
op, up front (`exports.rs:1662-1672` — `opcode_required_kind` and the
`lane.kind() != required` test). The kernels below it return `()`, never a
`Result`. The in-loop `lane_view` is the same argument: `validate_plan` called
it for the same `lane_id` already.

So a skipped op has no observable effect of any kind. That is what makes the
prefix rewrite a **rewrite** rather than a behaviour change.

### The trap the rewrite must NOT fall into

`k == 0` for every plan the Java facade can currently build — `View` exposes
no `or`, `PlanOp::narrowing` is documented as "the only kind reachable from
`View.where`", and `COMBINE_OR` is "deliberately unreachable from here"
(`View.java:23-32`, `PlanOp.java:22-24`). So the simple rewrite — *op 0 always
writes directly* — passes every test that exists, and is **wrong for a C
caller**, which `validate_plan` explicitly permits to send `LGJ_COMBINE_OR` in
any position. Implement the general prefix rule; do not special-case `k = 0`.

F-PR4-SEED must therefore carry a `combine = OR` leading op built at the ABI
level, not through the Java facade, which cannot express one.

### An ABI observation, recorded not fixed

A plan whose ops are **all** `OR` returns every row, whatever the predicates
say. That is the §7 contract read literally, it is reachable from C today, and
it is not PR4's to change — PR4 owes behaviour equivalence, not correction.
Filed here so the lowering's `k = n` arm reads as deliberate rather than as a
degenerate case someone should "clean up" later.

### Consequence for the OQ list

OQ-5 no longer blocks: it needs no substrate change, no new `MaskOp` variant,
and no ndarray primitive. It becomes a lowering requirement plus one falsifier.
**OQ-1 remains the blocking one** — and note its shape is narrower than the
plan states: `n_rows` is a property of the *pattern resource* and immutable for
its lifetime, so there is exactly ONE row count per pattern and no
"first sight of each n" cache is required at all. What that leaves open is
purely **where a per-pattern scratch lives and how concurrent
`lgj_plan_eval` calls on one handle share it** — a different and smaller
question than the one recorded above.

---

## OQ-1 — the obvious home is DISQUALIFIED, and one of the two buffers can just go (2026-09-14, main thread)

Not yet closed, but narrowed twice and with the tempting answer ruled out.

### Per-pattern scratch would serialize a path that is parallel today

`n_rows` is immutable per pattern resource, so "cache a scratch per row
count" collapses to "cache a scratch per pattern" — one buffer, no keying,
no first-sight-of-each-n problem. That is why the plan's framing was too
wide. But the narrower version is **wrong for a different reason**, and the
registry says so in its own words.

`registry.rs:22-34`: a call takes a **short read lock**, clones the
`Arc<ResourceEntry>`, and **drops the registry lock before touching the
payload**; masks then carry a per-resource `RwLock` specifically "so bulk ops
on distinct masks do not serialize". A pattern's payload is a fixture whose
buffer is "immutable for the resource's whole life" (`:126`) — it takes no
lock at all, which is exactly what lets N threads evaluate N plans against
one pattern with zero contention.

Hanging a mutable scratch off `ResourceEntry` ends that. It would need its
own `RwLock`, and two concurrent `lgj_plan_eval` calls on the same pattern —
the common shape, since a pattern is the thing you query repeatedly — would
then block on each other. **Trading a per-call allocation for a per-call
lock on the hot path is not an optimisation**, and it converts the one
resource kind that is deliberately lock-free into a contended one.

So per-pattern scratch is out. Remaining candidates, in preference order:
a thread-local keyed by word count (no contention, allocates once per
(thread, size), and the number of live sizes is the number of live
patterns); or a scratch RESOURCE with its own handle, which is ABI-clean in
principle — a handle is a name, not a byte position — but costs a new symbol
and owes the membrane warden a reason.

### `acc` needs no home at all — it can be deleted

`plan_eval_impl` allocates TWO buffers. The second one, `acc`, exists for a
stated reason (`exports.rs`): "dst_mask is written exactly once, and an error
at any point leaves it byte-for-byte as it was."

That reason is discharged by the OQ-5 finding above. After `validate_plan`
returns `Ok`, **there is no error path left**: `eval_predicate` is infallible
for every op in the plan, and the kernels beneath it return `()`. The
in-loop `lane_view` re-check is the same argument. So "an error at any
point" describes a set of points that is empty, and the copy-then-publish
dance is protecting against nothing.

With validation total and up front, the loop can accumulate directly into
the `dst_mask` words under their existing write guard. One allocation
disappears with no new storage, no new lock, and no ABI change — and it is
the one that is exactly `n_words` of the caller's own destination.

That leaves a single buffer to home, which is a materially smaller question
than the one this OQ started as. **Falsifier owed:** a test that an error
returned by `plan_eval` leaves `dst_mask` unchanged must still pass — the
errors that remain (`EMPTY_PLAN`, `NULL_ARGUMENT`, the resolve failures,
`validate_plan`'s own rejections, `MASK_LENGTH_MISMATCH`) all fire before
the first write, and that ordering is now load-bearing rather than
incidental. It should be pinned as such, not assumed.

---

## OQ-2 — RESOLVED: Fork A. The mask-risc oracle is MORE independent, not less (2026-09-14, main thread)

The question read as a trade — keep `kernels::scalar_*` (Fork B, two
evaluators, C-ONE narrowed to "one SIMD evaluator") or route
`lgj_plan_eval_scalar` at `reference_execute` (Fork A, C-ONE stays strong but
the allocation gate must be scoped). It is not a trade, because both sides
were being judged against the wrong property.

`kernels.rs` states the property in its own words: *"`scalar_*` below is
written in plain Rust loops with **no ndarray at all**. That independence is
the entire value of `lgj_plan_eval_scalar`: if the reference shared code with
the SIMD path, a parity test between them would be checking that a function
agrees with itself."*

Measured against that property, `reference.rs` wins outright: *"the same
Program evaluated ONE ROW AT A TIME in plain Rust, with no SIMD facade
anywhere in this file (law L4: a test greps the source)"* — and L4 is
**enforced by a grep test**, where `kernels::scalar_*`'s independence rests on
the discipline of living in the same file as the SIMD wrappers and not calling
them. Fork A therefore **strengthens** the exact property the symbol exists
for, and it upgrades the comparison from kernel-vs-kernel to
whole-evaluator-vs-whole-evaluator.

**Decision: Fork A.** `lgj_plan_eval_scalar` lowers the same plan and runs
`reference_execute`.

### The two costs, stated rather than absorbed

**1. `reference_scratch` allocates by design** — it materialises one `bool`
per row per slot, which is its whole reason for existing (an oracle sharing
the executor's bit packing could not falsify a bit-packing bug). So the
C-ALLOC gate is scoped to `lgj_plan_eval` **only**, and must name its symbol
in its own failure message. That is row **D9**, and under Fork A D9 must go
RED — a gate accidentally written against the scalar symbol is green for the
wrong reason.

**2. `simd_and_scalar_plans_agree_bit_for_bit` changes meaning and must be
renamed.** It stops being a SIMD-vs-scalar backend-parity test and becomes
executor-vs-oracle. That is not a loss: per §5.6 and the `lib.rs` correction
in lance-graph #1230, **backend parity is `ndarray::simd`'s business**, and an
ABI-level test could only reach it through a proxy. Renaming it is mandatory —
a test whose name claims backend parity it no longer checks is worse than no
test, because a future session reads the name.

### The consequence that makes C1 permanent

Both symbols now share the **lowering**. A lowering bug is therefore shared,
and no ABI-level differential between the two can see it — which is precisely
why the frozen oracle exists. **`legacy_plan_eval_impl` is not PR4 scaffolding
to be deleted after the merge; it is the only remaining independent check on
the lowering, and it stays.** Its cost is one frozen function that nothing
else may call.

---

## OQ-4 — the G11 fence must grow a CRATE allowlist in the same commit as the dep

The fence today (`native/lgj-abi/tests/g11_contract_import_fence.rs`) walks
`src/` and rejects any `lance_graph_contract::` module outside four named
ones. It says nothing about which CRATES `lgj-abi` may depend on, so adding
`lance-graph-mask-risc` passes it silently — and the fence's own history is
that it *was prose for months while already false*.

PR4 adds the crate allowlist in the same commit as the dep, spelled once in
the test and once in `CLAUDE.md`, with the same red-then-green discipline the
module allowlist got.

---

## OQ-1 — CLOSED: a thread-local growing arena

The blocker is gone: `Scratch::over(buf, words, slots)` and
`scratch_words_for` landed in lance-graph #1230 and are on `main`. What
remained was only *where the buffer lives*, with per-pattern already
disqualified (it would give the one deliberately lock-free resource kind a
`RwLock` and serialise concurrent `lgj_plan_eval` calls on one pattern — the
common shape).

**Decision: a thread-local `RefCell<Vec<u64>>`, grown monotonically to
`scratch_words_for(words_for(n_rows), SLOTS)`.**

- No contention by construction — it is not shared, so there is no lock to
  take and nothing for two threads to serialise on.
- Allocation becomes a function of the **maximum** row count a thread has
  seen, not of the history: a smaller call carves a strict prefix of the same
  buffer. That is what makes the `UNSEEN = 999` arm (D11) pass rather than
  being warmed into passing.
- It allocates once per `(thread, high-water mark)`. The honest bound to state
  in the PR body: a thread that has evaluated one 65,536-row plan holds
  `SLOTS * 1024 * 8` bytes until it exits. That is a real cost, and it is
  bounded, and it is not per-call.

`acc` is deleted outright, per the finding above: after `validate_plan`
returns `Ok` there is no error path left, so the copy-then-publish dance
protects against an empty set of points. Slot 0 of the scratch is the
accumulator, and one `copy_from_slice` publishes it under the existing write
guard — the same single write the old loop did.

**Falsifier owed and not optional:** the ordering that makes this safe —
every remaining error fires BEFORE the first write — is now load-bearing
rather than incidental. T4/T5 of `pr4_dst_reuse` pin it at every position in
a plan, with a pre-poisoned destination so "unchanged" is an observation and
not "still zero".

### The lowering, stated so the implementation has nothing to invent

Per OQ-5, `k` = the least index whose combine is AND (`k = n` if none).

- **`k = n`** (every combine is OR): the answer is ALL rows, tail cleared,
  `count = n_rows`. A constant — no program is built and none runs.
- **`k < n`**: drop ops `0..k`; lower op `k` as
  `MaskOp::Pred { under: None, dst: 0 }`; lower each later op `i` as
  `Pred { under: .., dst: 1 }` followed by `And`/`Or { a: 0, b: 1, dst: 0 }`.
- Terminal: `Count { mask: Scratch(0) }`; the words are read back out of
  `scratch.slot(0)`.
- `SLOTS = 2`.

Opcode map, one arm each, exhaustive over the nine:
`EQ_U32 -> EqU32`, `GT_I32 -> GtI32`, `NE_U32 -> NeU32`, `EQ_I32 -> EqI32`,
`NE_I32 -> NeI32`, `LT_I32 -> LtI32`, `LE_I32 -> LeI32`, `GE_I32 -> GeI32`,
`TERNARY_MATCH_U32 -> MatchU32 { pattern, care }` unpacked from
`(care << 32) | pattern`.

---

## C2 disable run — RESULTS (2026-09-14, commit `93da288`)

Eleven arms run against the NEW implementation, each patched, tested and
restored by a script that asserts its anchor matched exactly once first (the
recorded failure this discipline exists for: anchors written against pre-`fmt`
text, three edits silently no-op'd, three guards reported GREEN having never
landed).

**Nine load-bearing.** D1, D2, D4, D6, D7a, D7b, D8, plus two arms not in the
table above that cover the two decisions C2 actually had to make:

- **D-GATE** — gate an OR-combined op `under` the accumulator (i.e. delete the
  `is_and` condition). RED, including `or_plans_widen` and the whole combine
  sweep. This is the asymmetry the lowering's doc calls the correctness
  question, and it is now measured rather than argued.
- **D-LANE** — shift the lane index by one inside `pred_of`. RED across 17
  tests. The `const _` lane-id assertions guard the OTHER direction (the
  fixture renumbering under a correct lowering), which no runtime test can
  reach; this arm covers the lowering getting it wrong directly.

**D3 has no guard left to disable, and that is a finding rather than a gap.**
The row reads "seed tail clear: delete `clear_tail_bits` on the seed". There
is no seed. The prefix rewrite means op `k` writes the accumulator directly, so
the all-ones seed the old loop built — and had to tail-clear — does not exist
in the new path. The one remaining `clear_tail_bits` is the `AllRows` arm, and
that is D4, which is RED.

**Two came back VACUOUS, and both are exactly what the table predicted.**

| arm | result | the table's own trap note |
|---|---|---|
| **D5** — publish accumulates (`\|=`) instead of overwriting | GREEN | *"this row proves the fresh-mask tests were never evidence for this property"* |
| **D15** — `out_count` written above the error check | GREEN | the `12345` sentinel arm |

Neither is a weak guard: every test that exists today publishes into a FRESHLY
CREATED empty mask, and `|=` into an all-zero destination is `copy_from_slice`.
Nothing in the suite has a non-empty prior to be polluted, and nothing triggers
an error and then reads the sentinel. The arms that would redden both live in
`pr4_dst_reuse.rs` (F-PR4-DST's re-used/poisoned destinations; the
error-at-every-position sweep with a pre-poisoned `out_count`), which is why
that file is C1's last owed piece rather than a nice-to-have.

So the run did the thing a disable table is for: it converted "we think these
two properties are untested" from a prediction into a measurement, BEFORE the
file that fixes it landed. D5 and D15 are re-run once it does, and a green
result there would then be the real defect.

### D5 and D15 re-run once `pr4_dst_reuse.rs` landed — the receipt

The two arms recorded VACUOUS above were re-run against the same C2
implementation with the falsifier file in place. Both are now covered, and the
second one is a correction to the disable, not to the code.

**D5 — RED.** Changing `publish` from `copy_from_slice` to `|=` now reddens
`the_same_plan_lands_identically_whatever_the_destination_held`,
`a_poisoned_tail_is_published_clean` and
`a_dirty_destination_is_not_read_as_an_input_plane`. Red-then-green complete,
and the prediction the table made in advance — *"this row proves the fresh-mask
tests were never evidence for this property"* — is now a measurement at both
ends.

**D15 — still GREEN as the table spells it, and that is a defect in the
DISABLE.** The row says "move the write above the error check". Under the old
loop the write and the error checks shared one function. Under C2 they do not:
`out_count` is written in exactly ONE place (`publish`), and `publish` is
reached only after every error has already returned — so moving the write to
the top of `publish` cannot break anything, because on a failing plan `publish`
is never called at all.

The error check that is actually reachable is `validate_plan`, one level up.
Re-targeted there, both directions redden:

| arm | mutation | result |
|---|---|---|
| **D15a** | write `*out_count` before `validate_plan` | **RED** — `a_bad_plan_leaves_dst_mask_untouched`, `an_error_at_any_position_in_a_plan_leaves_the_destination_untouched` |
| **D15b** | zero the destination before `validate_plan` | **RED** — the two above plus `every_minor_11_opcode_rejects_a_lane_of_the_wrong_kind` |

So the property holds and is load-bearing; what changed is WHERE its guard
lives. The lesson is the one this table's own header already carries in a
different form: a disable written against the old structure can pass against
the new one for a reason that has nothing to do with the guard. **The right
reading of a green disable is "find out why", never "the guard is inert".**

Both `publish`-internal error paths (`write_mask()` returning `None`, and the
length mismatch) are unreachable for the same reason the `ExecError` map's arms
are: `resolve_pattern_and_mask` rejects a wrong-kind handle and a
wrong-length mask before the plan runs. They stay as defensive returns and are
not claimed to be falsifiable.

# PR4 pre-ruling — kernel-membrane-warden (T1/T2)

**Scope:** a PROPOSED change, not written code. Read-only run; no cargo.
**Question:** what tier is `lance-graph-mask-risc`, and does `lgj_plan_eval`
consuming it cross T1/T2 in the legal direction?

**Verdicts:** (a) **T2-COMPOSER — the card's T2 row is too narrow, split the
row, do NOT add a rung.** (b) **HAND-COMPOSED ×2, and the violating tier is
T1, not T2 — the one table belongs in ndarray; the filed TECH_DEBT entry's
two options are both wrong.** (c) **NAMED / legal as composition, BLOCKED on
an absent fence — G11 cannot see this dependency edge.**

---

## (a) mask-risc is T2 (a composer), not a second T1

`execute` (`exec.rs:357-512`) runs an arbitrary-length `Program` under an
opcode loop, resolves operands and aliasing (`two_input`, `exec.rs:175-208`),
manages caller-owned scratch slots, and remaps ternlog immediates
(`remap_imm`, `:149`). A T1 word has fixed arity and one pass; this is a
composer of T1 words by construction — the same shape as `plan_eval_impl`.

It sits correctly ABOVE the T0/T1 membrane: `exec.rs:25-34` imports ~30
`ndarray::simd` facade words and nothing else, and L2 is structurally gated
by `the_crate_names_no_isa` (`exec.rs:566-603`), which greps every production
module for `target_feature` / `core::arch` / `std::arch` / `cfg(target_` /
`is_x86_feature_detected!`.

**The card cannot classify it, and that is the card's defect.** My T2 row
enumerates "`lgj_hop`, `where`, `plan_eval`, the ABI exports" — i.e. T2 is
implicitly *the membrane crate*. mask-risc is T2-shaped but exports no ABI
symbol, crosses no Panama boundary, holds no handle: it is a **reusable T2
library**, a T2 whose consumer is another T2 rather than T3.

**Fix: split the T2 row into two ROLES; do not add a T1.5.**
- **T2-composer** — IR + evaluator: opcode loop, operand resolution, scratch.
  (`mask_risc::execute`, `plan_eval_impl`.)
- **T2-membrane** — the crossing: handles, status codes, generation checks.
  (`exports.rs`.)

A new rung would be actively wrong: the T1/T2 law (no hand-composed T1 op, no
computed geometry) must apply to mask-risc UNCHANGED, and it does bite there
(`ternlog_self`'s `fill(0)` / `fill(u64::MAX)`, `exec.rs:215-225`; `clear_tail`,
`:135`) — each licensed by a NAMED T1 gap, which is the correct handling. A
rung would create a tier where those rules are weaker.

Precedent for splitting a row rather than adding a rung is in the doctrine
itself (`membrane-tiers.md:47-49`): *"The ladder does not need a sixth tier.
**T1 was described too narrowly.**"* Same move, one tier up.

## (b) The duplicate ternlog table — T1 is the violator

**What is duplicated is not an algorithm.** Both tables call the identical
`ndarray::simd` word. What is duplicated is the **runtime-`u8` →
const-generic-`i32` bridge**:

| | lgj-abi | mask-risc |
|---|---|---|
| shape | `static TERNLOG_ASSIGN: [[fn; 16]; 16]`, `ternlog_row16!` (`kernels.rs:282-342`) | two 256-arm `match`es, generated (`ternlog_dispatch.rs`, 758 lines) |
| forms | in-place only (`:360`) | in-place `:320` **and** out-of-place `:58` |
| gate | none | `--check` regenerate-and-diff (`rust-test.yml:126`) |
| consumers | 1 (`exports.rs:768`) | `exec.rs:38` |

**Root cause, measured:** `ndarray::simd` exports ONLY the const-generic form
— `mask_ternlog<const IMM: i32>` (`simd_masking_ops.rs:595`) and
`mask_ternlog_assign<const IMM: i32>` (`:630`), re-exported at
`simd.rs:805-806`. **There is no runtime-immediate form at T1.** A const
generic instantiates only from a literal, so *every* consumer holding a
runtime immediate is forced into a 256-way fan-out. Both consumers were
forced; neither did anything wrong locally.

So: **a T1 gap, surfacing as a T2 duplication.** Per my card's own rule —
*"if none exists, say 'the T1 primitive is missing; add it at T1 first (W1a
discipline), then call it,' never 'compose it at T2 for now'"* — the verdict
is HAND-COMPOSED at both sites, with the primitive owed at T1.

**The one table belongs in ndarray.** Three reasons, two of them already
written down in this tree:
1. `kernels.rs:305-319` argues it for itself: *"`ndarray`'s POLYFILL LAW puts
   truth-table specialisation inside the backend file… a locally-written SIMD
   abstraction, the thing `abi.md` §8 forbids outright."* The identical
   reasoning condemns hosting the *dispatch* outside ndarray — the fan-out
   exists solely to reach ndarray's specialization, so it is part of ndarray's
   realization, not of anyone's behavior.
2. A backend may beat a 256-way branch: `VPTERNLOGQ` takes its immediate as an
   instruction byte, so AVX-512 could offer a genuinely dynamic form. A
   consumer-side fan-out forecloses that — a consumer making a realization
   decision, which the polyfill law forbids.
3. lgj `CLAUDE.md` E5 / the STOP rule: *"A facade method that cannot be one
   delegation is the signal the substrate is missing a word."*
   `simd_mask_ternlog_assign_dyn` is verbatim that.

**The filed TECH_DEBT entry is right on the facts and wrong on the options.**
`TECH_DEBT.md:3-9` correctly identifies the duplication, the agreement on index
convention, and the cheap-now/expensive-later economics. But its option set —
*"either lgj-abi delegates to the crate, or the crate is scoped to the
evaluator"* — omits the only resolution that REMOVES the duplication:
- option A makes the membrane crate depend on a lance-graph workspace crate
  solely to obtain a fan-out, and drags an unfenced edge across G11 (see (c));
- option B **preserves** the duplication and relabels it.
Storno the entry with the third option.

**Migration order — additive only, three commits, each green:**
1. **ndarray**: add `mask_ternlog_dyn(imm: u8, …)` + `mask_ternlog_assign_dyn`
   to `simd_masking_ops.rs`, re-export from `simd.rs`. Purely additive; the
   const-generic forms STAY (correct when the immediate is static, e.g.
   `exports.rs:2162`'s `AND3`). Falsifier: all 256 immediates agree with the
   const-generic form. Nothing downstream moves.
2. **mask-risc**: bodies become two delegations; delete
   `tools/gen_ternlog_dispatch.py` **and** `rust-test.yml:126` in the same
   commit (a generator with no output is the stale-gate shape). Keep the public
   names `ternlog_dispatch`/`ternlog_dispatch_assign` as one-line wrappers so
   `exec.rs:38` and `lib.rs:96` do not move.
3. **lgj-abi**: `simd_mask_ternlog_assign_dyn` keeps its name and signature and
   becomes one delegation; `TERNLOG_ASSIGN` + `ternlog_row16!` deleted.
   `exports.rs:768` does not move — **no ABI minor bump** (implementation
   change behind an existing symbol).

1 strictly before 2 and 3. 2 and 3 are independent. Doing 2-or-3 first is the
only red window.

### The generalization — worth more than the table

The tail clear has the SAME shape and **three** spellings:
- `ndarray/src/simd_masking_ops.rs:857` — `fn clear_mask_tail` — **private**
- `lance-graph-mask-risc/src/exec.rs:135` — `fn clear_tail`, whose own doc says
  it is *"Narrower than `ndarray`'s private `clear_mask_tail`"*
- `lgj-abi/src/abi.rs:526` — `pub fn clear_tail_bits`

Three tiers, one function, because T1 keeps it private. Same root cause as the
ternlog table; nobody filed it. **Rule to add: if two consumers of
`ndarray::simd` independently write the same helper, that helper is a missing
T1 export, not a consumer concern.** Fixing ternlog alone leaves the pattern
live — file `clear_tail` as its own TECH_DEBT entry.

**Not charged as GEOMETRY-LEAK, deliberately.** `clear_tail` computes
`n_rows % 64` / `n_rows / 64`, but the bit order is NORMATIVE and PUBLISHED
(`kernels.rs:29-32`; `lib.rs:26-28` "`ndarray::simd`'s normative mask order").
A published contract constant is not a computed geometry — same distinction lgj
`CLAUDE.md` draws for the 512×32×(4+12) row contract. It is a duplication with
a different fix (export it), and over-charging it would be wrong.

## (c) `plan_eval_impl` consuming mask-risc

**Same shape, confirmed:**

| | `plan_eval_impl` (`exports.rs:1680-1756`) | `execute` (`exec.rs:357-512`) |
|---|---|---|
| input | flat `&[LgjOpDesc]` | `&Program { ops, terminal }` |
| per op | `eval_predicate` → `combine_into` (`:1731-1738`) | `run_pred` / `two_input` / ternlog arm |
| combine | AND, OR only | full Boolean + 256 ternlog |
| terminal | popcount → `out_count` | `Terminal::{Count,Any,All,MaskedSum,…}` |

So it is **T2 calling T2**, with the callee strictly more expressive.

**No rule exists.** The doctrine states the vertical law only —
*"A tier may only know the vocabulary of the membrane directly beneath it.
Nothing crosses a membrane except by NAME"* (`membrane-tiers.md:15-16`) — and
every row of the table is about crossing DOWN or UP. The horizontal case is
unaddressed. It must be ALLOWED (otherwise every helper is forbidden;
`plan_eval_impl` already calls its peers `validate_plan` and
`resolve_pattern_and_mask`).

**Minimal proposed rule — the no-laundering rule:**

> **T2 may call a T2 peer, provided the call does not re-cross a membrane.** A
> T2 composer may delegate to another T2 composer only if the callee reaches T1
> by the same route the caller would have: the same `ndarray::simd` facade, no
> second SIMD provenance, no second geometry spelling, and no widening of the
> caller's own import fence. **A tier may never obtain vocabulary through a peer
> that its fence would have refused directly.**

Vertical analog already enforced: `Cargo.toml`'s fence comment — the cognitive
modules *"compile UNCONDITIONALLY… so the fence, not `default-features`, is the
only barrier."*

**Applying it: composition LEGAL.** mask-risc's only dependency is `ndarray`,
`default-features = false, features = ["std"]` — the SAME coordinates lgj-abi
uses; its manifest says outright *"No contract dep."* Plus
`#![forbid(unsafe_code)]` (`lib.rs:79`) and the structural no-ISA test. No
laundering.

**But the EDGE is unfenced — this is the blocker:**
1. **G11 structurally cannot see it.** `ALLOWED = ["canonical_node",
   "class_view", "facet", "ontology"]` and `const CRATE: &str =
   "lance_graph_contract"`; `references()` scans for that one string. A
   `use lance_graph_mask_risc::…` is invisible. A test named "the
   contract-import fence" would give false assurance of coverage — the exact
   `ISS-LGJ-G11-FENCE-WAS-PROSE` failure mode, one crate over.
2. **Policy has no verdict for this category.** lgj `CLAUDE.md`: *"the full
   lance-graph ENGINE is never a dependency."* mask-risc is neither engine nor
   contract — it is a third thing: a lance-graph **workspace member**
   (`lance-graph/Cargo.toml:8`).
3. **First non-leaf dependency.** Today: `ndarray`, `lance-graph-contract`
   (zero-dep by design), optional `ogar-class-view`. mask-risc is zero-risk
   *today* (one dep, std-only) and nothing pins it there.

**Requirement: the fence moves in the SAME commit as the dependency.**
Generalize `g11_contract_import_fence.rs` from one `CRATE` constant to a
per-crate policy table admitting `lance_graph_mask_risc` by name; update
`CLAUDE.md` § Enforcement and the `Cargo.toml` comment together (the test
parses both and fails on drift, in either direction).

### Two sub-findings PR4 must decide, in neither doc

- **`lgj_plan_eval_scalar` would be orphaned.** `kernels::eval_predicate` /
  `combine_into`'s `Path::Scalar` arms are a SHIPPED symbol whose whole value
  is independence (`kernels.rs:20-27`: *"if the reference shared code with the
  SIMD path, a parity test between them would be checking that a function
  agrees with itself"*). mask-risc has its own oracle (`reference.rs`, L4) but
  it is not reachable across the ABI. Delegating naively either kills that
  symbol's independence or silently leaves two evaluators.
- **Not a like-for-like swap.** `LgjOpDesc[]` (AND/OR) → `Program` (full
  Boolean + ternlog + terminals) is a NEW lowering pass. Correct per
  *"Extend the plan language, NOT the ABI surface"* (`membrane-tiers.md:105-118`),
  but materially more than "pick one owner".

## Doc defect found in passing — the law numbering does not agree with itself

`lib.rs:40` — *"## The four laws this crate is built to make structural"* (4
laws, unnumbered-as-L). `exec.rs:4-23` — L1..L5 (5 laws). Not the same set:
lib.rs's law 2 (*"Masks choose admissibility; magnitude is a separate reduction
over survivors"*) has no L-number; exec.rs's **L3** (one delegation per op) and
**L5** (one materialiser) are absent from lib.rs's four. In lib.rs, "no ISA" is
law **3**; in exec.rs it is **L2**. Anyone citing "L2" from `lib.rs` cites the
wrong law. One-line fix; worth doing before PR4 cites a law number.

**L3 is correctly labelled `[claimed, unverified]`** (`exec.rs:6-7`,
`TECH_DEBT.md:15-18`) — no instrument counts facade calls. Note that if step (b)
lands, L3 gets *easier* to instrument, because the fan-out stops being a
delegation the counter would have to special-case.

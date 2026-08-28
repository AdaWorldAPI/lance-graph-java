## 2026-08-28 — mask-membrane-valhalla-integration-v1 (PROPOSED; the layered consolidation)

Plan: `.claude/plans/mask-membrane-valhalla-integration-v1.md`. The
synthesis of the PR #44→#46 arc into one wave plan under the operator's
layer model: **masking underneath** (hop = MASK × CLASSVIEW → MASK, the
execution currency), **Panama the membrane** (shape/meaning/operations
cross, ownership does not), **Valhalla cheap addresses** (describe the one
substrate, never become a second one), **everything else underneath**
(placement, publication, worker topology, the sealed horizon — all
substrate-private). Seven frozen decisions cited (F1–F7), including the
measured F7 ground truth that the convergence tail, not compute, is ~90%
of a 64K cycle.

Waves: **W0** mechanical fences for the council-corrected doctrine
(materialization-list gate, §E topology fence, SIMD-branch fence — each
proven able to fire); **W1** close the membrane's two named honesty gaps
(`ISS-LGJ-EPOCH-UNCHECKED` epoch re-check; minors 2-4 lazy holders so
`requireMinor` covers every minor); **W2** finish the mask layer (the
fused single-plane pass' ~10× lab claim verified through the real ABI;
register sweeps answering facet-major instead of −18); **W3** Valhalla
value-class promotions on measured evidence only; **W4** temporal READ
binding via the contract's clean vocabulary — the WRITE seam stays
blocked with GridLake (F5) and deliberately carries no D-id.

**Status: PROPOSED.** D-LGJ-MMV-0 (W0 fences) is the cheapest first
action; W1.1's Phase-0 council spec is COMMITTED at
`.claude/plans/epoch-recheck-phase0-v1.md` (frozen decisions, both
accepted resolutions, pre-registered gates, five per-savant question
sets).

## 2026-08-17 — ogar-machine-v1 (EXPLORATORY; the population emulator)

Plan: `.claude/plans/ogar-machine-v1.md`. Captured from the operator's
second archived ChatGPT context (the first was convergent confirmation;
this one is a genuinely NEW workload): one row = one machine STATE,
control flow becomes population masks ("which machines currently execute
ADD?"), Ghidra P-code as the normalized guest ISA
(`AdaWorldAPI/ghidra` now attached + shallow-cloned at
`/workspace/ghidra`), differential migration testing as the killer demo
(65,536 worlds through legacy XOR replacement → the divergent few),
Lance as the time machine (via `lance-graph-hydrate`, lance-graph
#957/#958 — inherited, never re-implemented). Strong/weak claims
separated in the plan; gated on W3 + one W5 example + Ghidra
archaeology + probe P-M1.

**Status: NAMED, not scheduled.** W3 remains the next action.

## 2026-08-17 — consumer-{world-trades,bricks-analytics,graph-traversal}-v1 (PLANS; the three W5 consumer examples)

Plans: `.claude/plans/consumer-world-trades-v1.md`,
`consumer-bricks-analytics-v1.md`, `consumer-graph-traversal-v1.md`.

One operator poster made runnable per plan, all three over the SAME
substrate, each exercising a different face: the fluent domain API with
zero object allocation (trades); mask-first authorization where the RBAC
clamp composes BEFORE execution and only aggregates leave (bricks); and
traversal as facet addressing with crossings that scale with HOPS, not
rows (graph). Each carries its own falsifier set, including the
anti-vacuity requirement that a result be neither empty nor total.

**Sequencing:** all three are gated on `lgj-soa-substrate-v1` W3 (the
Java `RowStore` facade); after that they are independently shippable in
any order and none blocks the others. **Iron rule recorded in all three:**
a consumer example that needs a new ABI symbol goes back through the
substrate plan's wave process — the membrane never grows from the consumer
side.

**Status: PLANNED.**

## 2026-08-17 — lgj-soa-substrate-v1 (PLAN; the lance-graph-shaped SoA substrate)

Plan: `.claude/plans/lgj-soa-substrate-v1.md`. Successor to
`lgj-vertical-slice-v1` (COMPLETE, PRs #1-#4).

**What it covers:** the real layout — 64K × 512-byte rows, 32 facet lanes
of (4-byte classid + 12-byte payload) — wired end to end, in five waves:
W1 ndarray primitives (`iter_u32x16`, `eq_u32_strided_to_mask`), W2 the
Rust row store (`LGJ_RESOURCE_ROWSTORE`, `lgj_rowstore_open`,
`lgj_op_eq_classid`, `lgj_row_facet_match`, ABI minor 2, `abi.md` §11),
W3 the Java `RowStore` facade, W4 a Vector-API-vs-crossing bench on the
REAL layout, W5 the three consumer examples.

**Framing decision on record:** this plan exists because the flat
three-lane fixture was always scaffolding
(`E-LGJ-THE-FLAT-FIXTURE-WAS-SCAFFOLDING-NOT-THE-TARGET-1`). The doctrine
it serves — the middle tier is deleted rather than wrapped, objects are
eliminated rather than optimized, security collapses into the data
boundary — is `E-LGJ-THE-MIDDLE-TIER-IS-DELETED-NOT-WRAPPED-1`.

**Status: ACTIVE.** W1 shipped (ndarray PR #279); W2 shipped (84/84,
18/18 symbols); W3 is the next action.

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

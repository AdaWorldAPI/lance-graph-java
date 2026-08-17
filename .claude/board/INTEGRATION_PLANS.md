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

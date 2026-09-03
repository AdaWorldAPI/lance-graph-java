# mask-risc-lowering-v1 — the API speaks database, the backend does photolithography

> **Status:** DRAFT for the 5+3 council (`.claude/agents/5plus3-council.md`). Source-first:
> every "exists" claim below carries `file:line` at HEAD 2026-09-03; every "proposed" claim
> carries a falsifier. Nothing in this document has been built. No kernel, no ABI symbol, no
> mint.
>
> **Operator framing this plan serves (2026-09-03, verbatim in spirit):** *the endgame is that
> the backend does photolithography; focus of awareness are cached masks; ogar-loco gets
> `where()` / `scan()` / traversal ergonomics; behind the scenes a verb like `BELNAP_JOIN`
> decomposes into masking ops at 2000×.* And the one-liner that names the payoff: **once you
> reach `mammal`, everything below it is one ternary set mask.**

---

## §0 — The model, in one picture

```
   loco verb           where / scan / join / BELNAP_JOIN / …        V4: ergonomics, "speaks database"
        │ lowers to
   Plan { ops }        AND | OR | ANDNOT | TERNLOG(imm) | TERNARY_MATCH | COUNT   RISC over masks, ONE crossing
        │ reads / writes
   mask trie           (Lance version, prefix) → cached mask         "focus of awareness"
        │ two axes
   vertical            HHTL tiers: HEEL → HIP → TWIG, prefix containment = ancestry (the zone map)
   horizontal          rail-group ternary sets: 2 × 3 rails, support/refute planes per 64 rows
        │ over
   the version's SoA   512 × 32 × (4+12), never invalidated             V3: shape transport
```

Photolithography, literally: the version is the wafer, each cached mask a developed stencil,
each RISC op an exposure, a query a stack of exposures. **Awareness = which stencils are
already developed.** A second query sharing a prefix pays only for its undeveloped layers.

**The `mammal` sentence, made mechanical.** Walking the vertical axis down the taxonomy
(`is_a` rails, HHTL prefix) to the node `mammal` yields one mask: every row whose key carries
that prefix. That mask is a trie node. Every query about anything below `mammal` — every
species, every phenotype restricted to mammals, every join whose left side is a mammal —
**starts from that one cached set** and only exposes its residue. The subtree never has to be
re-selected, because the version never changes and the prefix never moves. That is why the
vertical masks are the most reusable objects in the system and go into the memo first.

---

## §1 — What exists (DOCUMENTATION register: every row cites source)

| piece | where | what it already gives us |
|---|---|---|
| Mask algebra over the population | `native/lgj-abi/src/exports.rs:518,524,680` (`lgj_mask_and/or/andnot`), `:332` (`lgj_mask_create`) | horizontal AND / OR / ANDNOT, `Box<[u64]>` per mask, generation-checked handles |
| The narrowing chain | `exports.rs:1522` `lgj_plan_eval`; `abi.rs:337` `LgjOpDesc` (24 B, `combine` at `:345`) | n predicates, ONE crossing, accumulator starts all-set, monotone `V(k+1) ⊆ V(k)`. Today's op kinds: `eq_u32` (`:755`), `gt_i32` (`:787`), `eq_classid` (`:829`) — all scalar-valued, none bitwise over the payload |
| The graph op | `exports.rs:1712` `lgj_hop` | Boolean-semiring `mxm` as mask × ClassView/WideFieldMask → mask (root `CLAUDE.md` mask-native invariant) |
| The 3-input bit op | `ndarray/src/simd.rs:570` `pub mod ternlog` (`MAJ3 = 0xE8` at `:582`); `simd_avx512.rs:4934,4972` (`U64x8`/`U32x16::ternlog::<IMM>`, native `vpternlogq`); AVX2 `simd_avx2.rs:3559`; NEON/WASM/scalar ports exist (W1a-#9) | any 3-input boolean per bit, one instruction per 8 words, every backend, parity-tested |
| The minted opcode | `OGAR/crates/ogar-loco/src/lib.rs:607` `TERNLOG = 0x86`; `:596` and `vocabulary.rs:94`: **`0x87..0x8B` reserved**, previously `BELNAP_JOIN`, `INFO_GAIN`, `SIGMA_TENSION`, `ACCUMULATE`, `STANCE_ENTROPY`, torn down post-#1132 as "no producer, no basin" | the RISC op has a loco slot; the verbs have reserved slots waiting for a producer |
| The Belnap encoding | lance-graph `EPIPHANIES.md` `E-THE-STATE-LAYER-IS-A-BELNAP-BILATTICE-AND-THE-JOIN-IS-THE-ACCUMULATOR-1` (#1129) | two planes `support_mask` / `refute_mask`; `(0,0)` Neither, `(1,0)` True, `(0,1)` False, `(1,1)` Both; **knowledge-order join = bitwise OR of both planes**, proven and pinned |
| The vertical axis | `lance-graph-contract/src/hhtl.rs:56` `NiblePath`; `rail_geometry.rs:178` `is_ancestor_of` | prefix containment IS ancestry; the tier of a nibble is `n >> 2` (OGAR `CLAUDE.md`, 3×4 canon) |
| The reject-early cascade | `ndarray/src/hpc/splat3d/depth_cascade.rs:54` `HhtlAction`, `:137` `cascade_block`, `:194` `cascade_blocks` | a working precedent for cheap-reject-before-expensive-compute over tiered blocks |
| The rail carving | `lance-graph-contract/src/facet.rs:367-369` `CascadeShape::{G6D2, G4D3, G3D4}` | 6 × 2 rails is a ClassView-selected reading of the 12-byte register; a 2 × 3 rail grouping is a policy over `G6D2`, not a new carving |
| Field-mask algebra | `class_view.rs:385,395,413,426` `intersect / union / difference / is_subset_of` (D-MAR-1, #1099) | the WideFieldMask half of "which fields participate" is complete |
| The falsifier already designed | lance-graph `.claude/harvest/spatial-mask-r2il-audit-2026-08-24/REPORT.md` §6 item 5 | `DOWN[x]` bitmask over a synthetic 64-node DAG vs `is_ancestor_of`, one multi-parent exception routed to `FieldMask`, timed at N=64 and N=4096. **Never run.** |

**What does NOT exist (verified absent):** any mask memo in `lance-graph-planner`,
`lance-graph-contract`, or `lgj-abi` (`NativePattern`'s three internal masks are scratch, not
a memo); any bitwise op over the 12-byte payload in `plan_eval`; any survivor-word skip in
`plan_eval`'s per-op sweep; any lowering from a loco verb to `LgjOpDesc`.

---

## §2 — The two axes and the semiring honesty fence

**Vertical = tiers.** A mask at a node = every row whose key has this prefix. Parent mask =
OR of children by construction. Same shape at every level: the fractal.

**Horizontal = population at a node**, narrowed by exposures.

**"Pretending to be a semiring", scoped exactly** — the council must not let this widen:

| semiring (blasgraph inventory) | mask-native? |
|---|---|
| Boolean | **exact**: multiply = AND, add = OR, closure = the cascade. This is what `lgj_hop` already is |
| XorBundle, XorField | **exact**: XOR is a `ternlog` table |
| HammingMin, SimilarityMax, Resonance, NarsTruth | **no**: a value per cell, not a bit. Masks do the SELECTION; a popcount or distance kernel does the SCORING. Two ops. The 2000× is on selection, where the row count dies |

Anyone claiming HammingMin is an AND fails this fence.

---

## §3 — DuckDB, so nobody rediscovers the comparison

DuckDB is already "masking pretending to be SQL": selection vectors per 2048-row chunk,
validity bitmasks, zone maps. Where we are structurally different, and where we are not:

| DuckDB | this substrate |
|---|---|
| selection vector = index list per chunk, rebuilt per operator per query | mask = bit-plane over the population, one `u64` per 64 rows, persistent |
| nothing survives the query | trie keyed on an **immutable** version: the memo is free and never stale |
| typed columns; bitwise on a payload goes through the expression engine | content-blind 12-byte register; `TERNLOG` on raw bits, no decode |
| multi-valued logic = `CASE`, row at a time | Belnap = two planes, one OR |
| Java gets a copy (JDBC/Arrow) | Panama reads in place; only the handle crosses |
| **better than us:** general SQL, hash joins, spilling, optimiser, morsel parallelism | not claimed. We win on the awareness workload: fixed population per version, bit-pattern predicates, heavy prefix reuse |

Zone-map skipping is not stolen from DuckDB: the HHTL tiers ARE the zone map (§2 vertical).

---

## §4 — The cost ladder (three rungs, two different wins)

| rung | what | 64K rows | provenance |
|---|---|---|---|
| cold, AoS | row-at-a-time / full 512-B-row sweep per query | ~25 ms per group | 32 MB touched. This is what `classidAt`-per-row costs today, i.e. the path root `CLAUDE.md` forbids as an engine |
| cold, SoA | build one 3-rail group set from the rail lane | ~5 µs per group | 384 KB, one AVX-512 pass. **Estimate — the probe measures it** |
| warm | trie hit, two cached sets, one AND | ~25 ns per group | crossing floor 21.9 ns measured (`bench/RESULTS.md` A); a 1024-word AND is ~100 ns unless the AND result is itself a trie node |

`2 × 25 ms → 2 × 25 ns` = **5000× (AoS→SoA, already paid by the contract) × 200× (SoA→memo,
this plan)**. Two numbers, measured separately. Never report one.

---

## §5 — Rail-group factoring (why the memo hits)

Group the 6 rails 2 × 3: two groups of three rails, each with a ternary cached set (support
plane + refute plane per 64 rows). Query = `group_A(pattern_A) AND group_B(pattern_B)` plus a
`TERNLOG` inside a group for a partial pattern.

Why 2 × 3 beats 1 × 6: a 6-rail pattern lives in 256⁶ and never recurs; a 3-rail pattern lives
in 256³ and recurs constantly, and the halves recur **independently**. The grouping is a
per-class cache policy resolved by the same classid lookup as the carving (§1 `CascadeShape`).
**The number that picks 2×3 vs 3×2 vs 6×1 is memo hit rate on a real pattern stream** — wave 0.

Cost per exposure, warm: 4 `u64` words per 64 rows, one AND, one TERNLOG.

---

## §6 — What is PROPOSED (PLAN register), substrate-first per the STOP rule

### Wave 0 — measure before minting (no production code)

| D-id | probe | pass / kill |
|---|---|---|
| **D-MRL-0a** | Run the audit's `DOWN[x]` falsifier: synthetic 64-node DAG, one multi-parent exception, `DOWN[x]` as a real bitmask vs `is_ancestor_of` on every single-inheritance node; time N=64 and N=4096 | exact agreement on single-inheritance nodes; multi-parent routes to `FieldMask`, corrupts neither. **Kill:** any disagreement — the vertical axis is not a bitmask and §2 is wrong |
| **D-MRL-0b** | Rail-group hit-rate replay: a real rail-pattern stream (an OBO closure walk, or a MedCare query log expressed as rail tuples) counted under 1×6, 2×3, 3×2 groupings | the grouping with the fewest distinct keys per query wins. **Kill:** if no grouping beats 1×6 by ≥ 3×, the memo does not pay and Wave 1c is dropped |
| **D-MRL-0c** | Cold group-set build cost on the real `plan_eval` scalar path vs a hand `ternlog` composition, 64K rows | pins the "cold SoA" rung with a number, replacing §4's estimate |

### Wave 1 — `lgj-abi` (the RISC and the memo)

| D-id | deliverable | falsifier |
|---|---|---|
| **D-MRL-1a** | `LgjOpDesc` kinds `TERNLOG(imm, a, b, c)` and `TERNARY_MATCH(pattern[12], care[12])` over the facet register; composed inside `plan_eval` so a whole cascade is one crossing. Backend = `ndarray::simd::ternlog`; no raw intrinsic (simd-savant) | scalar oracle parity through `lgj_plan_eval_scalar` on a slab with planted hits; a disable of the care mask must flip a planted miss to a hit |
| **D-MRL-1b** | survivor-word skip in `plan_eval`: skip any 64-row block whose accumulator word is zero | 1..7-level cascade at 64K rows: cost per level after the first must be ∝ survivors. **Kill:** superlinear anywhere |
| **D-MRL-1c** | the mask trie in the registry, keyed `(version, prefix hash)`, entries are ordinary generation-checked mask handles; eviction is a miss, never a stale read (the version is immutable — the liveness question of `epoch-recheck-v3` does not arise here, by construction) | warm cascade ≤ 1.2× one cold level; a second identical query returns the same handle |
| **D-MRL-1d** | vertical tier masks as the first trie population: one mask per visited HHTL node | the `mammal` test: select a subtree once, then N queries under it never re-select it (measured by trie hit count, not by timing alone) |

### Wave 2 — loco (the verbs get a producer)

| D-id | deliverable | falsifier |
|---|---|---|
| **D-MRL-2a** | re-mint `0x87..0x8B` (`BELNAP_JOIN` first) as vocabulary verbs **with a lowering table** to Wave-1 op sequences. `BELNAP_JOIN` = OR of support planes ∥ OR of refute planes, per the #1129 identity | byte-exact: verb result == hand-composed `plan_eval` result on the same version |
| **D-MRL-2b** | `where` / `scan` as verbs lowering to `TERNARY_MATCH` + vertical-prefix AND | same |

### Wave 3 — the Java glove (last, by E5)

| D-id | deliverable | falsifier |
|---|---|---|
| **D-MRL-3** | verbs on the facade; a 24-byte `TernaryPattern` value beside `WideFieldMask`; nothing else new | `GraphHopTest` reflective allowlist + G2 no-per-row-engine + allocation gates extended and green; `ApiSurfaceTest` unchanged |

---

## §7 — Frozen decisions (the council attacks these, not the prose)

- **F1.** No verb ever executes row-at-a-time. A verb that cannot be one `plan_eval` crossing is the signal the RISC is missing a word (root `CLAUDE.md` E5).
- **F2.** The memo key is `(Lance version, prefix)`. There is no invalidation path; there is eviction. Any design that adds an epoch check to a trie read is rejected — that question was measured and closed on 2026-09-03 (`epoch-recheck-v3.md` Amendment A1, gate FAIL) and does not apply to immutable versions.
- **F3.** Belnap is data-side, two planes, per #1129. The pattern-side TCAM (`pattern`, `care`) is a separate op. They compose; they are not the same op.
- **F4.** Rail grouping is a per-class policy selected by classid, never a global constant.
- **F5.** Ship order is 0 → 1 → 2 → 3, and Wave 0 is not skippable: D-MRL-0b can kill 1c.

## §8 — Model allocation (operator-ruled 2026-09-03, declared here, not per spawn)

- **Planning is Opus.** The 5 council savants, the 3 reviewers, the consolidation passes,
  every wave's spec, every "does this measurement mean what it appears to mean" call, and
  the orchestrator (central gates: `cargo`/`javac`/`AllTests`, every commit and push).
- **Grindwork is Sonnet.** Bounded transcription against a written spec: port THIS kernel
  composition, write THIS probe from THIS table, thread THIS op kind through THESE call
  sites, replay THIS pattern stream. One source in, one shape out. Never a worktree, never
  its own cargo run, never a claim that it compiles.
- **Never Haiku** for anything in this plan.

## §9 — Open questions for the council

- **Q1.** Trie ownership: `lgj-abi` registry (consumer-local) or `lance-graph` (shared by every consumer)? The version is lance-graph's; the handles are lgj's. Substrate-first says lance-graph, but no consumer other than lgj exists yet.
- **Q2.** Does `TERNARY_MATCH` belong in `LgjOpDesc` (24 B, fixed) or does a 24-byte pattern+care force a wider descriptor and an ABI minor bump?
- **Q3.** Eviction policy: LRU by handle, or pinned-forever for vertical tier masks and LRU only for horizontal sets?
- **Q4.** Which real pattern stream for D-MRL-0b, and who owns it (MedCare-rs is private).

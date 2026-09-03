# mask-risc-lowering-v1 — the API speaks database, the backend does photolithography

> **Status:** DRAFT v2 — Phase-2 consolidation of the 5+3 council (5 savants reported;
> reviewers NOT yet cast). The change ledger is §12. Spec v1 is the git history of this file.
> Original header follows.
>
> **Status (v1):** DRAFT for the 5+3 council (`.claude/agents/5plus3-council.md`). Source-first:
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

> **The metaphor's scope (S5-Q5).** "Photolithography" is load-bearing in exactly ONE place:
> the wafer is immutable, which is what licenses F2's no-invalidation-only-eviction rule and
> makes exposure composition order-free. Everywhere else it renames what the two-axis model
> already states, and — stated explicitly because it reads otherwise — **it predicts nothing
> about hit rate**, which is the one quantity the whole plan depends on and nobody has measured.

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
| The 3-input bit op | `ndarray/src/simd.rs:570` `pub mod ternlog` (`MAJ3 = 0xE8` at `:582`); `simd_avx512.rs:4934,4972` (`U64x8`/`U32x16::ternlog::<IMM>`, native `vpternlogq`); AVX2 `simd_avx2.rs:3559`; NEON/WASM/scalar ports **CLAIMED, not verified** (S3-2) | any 3-input boolean per bit, one instruction per 8 words. AVX-512 + AVX2 + facade CONFIRMED; the other three backends are a Wave-0 verification item, not an assumption |
| The minted opcode | `OGAR/crates/ogar-loco/src/lib.rs:607` `TERNLOG = 0x86`; `:596` and `vocabulary.rs:94`: **`0x87..0x8B` reserved**, previously `BELNAP_JOIN`, `INFO_GAIN`, `SIGMA_TENSION`, `ACCUMULATE`, `STANCE_ENTROPY`, torn down post-#1132 as "no producer, no basin" | the RISC op has a loco slot; the verbs have reserved slots waiting for a producer |
| The Belnap encoding | lance-graph `.claude/board/EPIPHANIES.md:438` `E-THE-STATE-LAYER-IS-A-BELNAP-BILATTICE-AND-THE-JOIN-IS-THE-ACCUMULATOR-1` (#1129) | two planes `support_mask` / `refute_mask`; `(0,0)` Neither, `(1,0)` True, `(0,1)` False, `(1,1)` Both; **knowledge-order join = bitwise OR of both planes**, proven and pinned |
| The vertical axis | `lance-graph-contract/src/hhtl.rs:56` `NiblePath`; `rail_geometry.rs:178` `is_ancestor_of` | prefix containment IS ancestry; the tier of a nibble is `n >> 2` (OGAR `CLAUDE.md`, 3×4 canon) |
| The reject-early cascade | `ndarray/src/hpc/splat3d/depth_cascade.rs:54` `HhtlAction`, `:137` `cascade_block`, `:194` `cascade_blocks` | a working precedent for cheap-reject-before-expensive-compute over tiered blocks |
| The rail carving | `lance-graph-contract/src/facet.rs:367-369` `CascadeShape::{G6D2, G4D3, G3D4}` | 6 × 2 rails is a ClassView-selected reading of the 12-byte register; a 2 × 3 rail grouping is a policy over `G6D2`, not a new carving |
| Field-mask algebra | `class_view.rs:385,395,413,426` `intersect / union / difference / is_subset_of` (D-MAR-1, #1099) | the WideFieldMask half of "which fields participate" is complete |
| The falsifier already designed | lance-graph `.claude/harvest/spatial-mask-r2il-audit-2026-08-24/REPORT.md` §6 item 5 | `DOWN[x]` bitmask over a synthetic 64-node DAG vs `is_ancestor_of`, one multi-parent exception routed to `FieldMask`, timed at N=64 and N=4096. **Never run.** |

**PRIOR ART the v1 spec failed to cite (S1) — this is the largest single correction:**

| already shipped / ruled | where | what it means for this plan |
|---|---|---|
| The two-axis model, **verbatim** | `.claude/board/EPIPHANIES.md:13403` — *"Vertical navigation is FREE (prefix arithmetic); horizontal costs a hop; the mask is the budget deciding which hops are paid"* | §2 and §5 are a RESTATEMENT, not a new claim. Now cited; the plan claims no novelty there |
| **The `mammal` sentence is PROVEN, not proposed** | `.claude/board/entries/2026-08-23-e-hhtl-compiles-hierarchy-into-mask-geometry-1.md:10,26` — *"HHTL does not execute a tree. It compiles hierarchy into mask geometry"*, `M0 ⊇ M1 ⊇ … ⊇ M5`, measured **7/7** by `PROBE-MASK-ALGEBRA-INVARIANCE-1` | D-MRL-1d as written duplicates shipped, probed work. **Retired** — see §12 L4 |
| Prefix pushdown was **dropped by operator correction** | `.claude/board/EPIPHANIES.md:13401` — three shapes rejected, because prefix containment/narrowing already IS `FacetCascade::shared_prefix_tiles` / `prefix_distance` | any prefix mechanism must LOWER ONTO those symbols or it re-opens a closed item |
| `standing_mask` (`SubscriptionTable<K>`, fires iff `dirty ∩ interest ≠ ∅`) | `.claude/board/EPIPHANIES.md:13397` | the shipped persistent-mask-keyed-by-interest surface: nearest structural precedent to the trie. Its Vec+linear-scan / shard-per-tenant conclusions must be read before 1c, not re-derived |
| `E-MASK-SELECTION-ALGEBRA-1` | `.claude/board/EPIPHANIES.md:13393` | confirms no version-keyed memo exists anywhere: §1's "verified absent" HOLDS |
| "No second set algebra: Mengenlehre = `EvidenceMask` ops" | `.claude/plans/dismech-causal-replay-v1.md:70` | owns set-algebra repo-wide. The mask RISC must be shown to BE that algebra, not a second one |
| `F-RLR-2`: *"a new carrier is proposed before `ogar_loco` is proven insufficient — automatic STOP"* | `.claude/plans/rubicon-loco-rung-cognitive-fabric-v1.md:422` | Wave 2's lowering table and Wave 3's `TernaryPattern` TRIP this gate unless the insufficiency argument is recorded. See §12 L9 |

**What does NOT exist (verified absent, S1-Q1 + S3-1 + S3-3):** any mask memo in `lance-graph-planner`,
`lance-graph-contract`, or `lgj-abi` (`NativePattern`'s three internal masks are scratch, not
a memo); any bitwise op over the 12-byte payload in `plan_eval`; any survivor-word skip in
`plan_eval`'s per-op sweep; any lowering from a loco verb to `LgjOpDesc`.

---

## §2 — The two axes and the semiring honesty fence

> **Provenance (S1):** this section restates `.claude/board/EPIPHANIES.md:13403` and
> `E-HHTL-COMPILES-HIERARCHY-INTO-MASK-GEOMETRY-1`. It is cited, not claimed.

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

⊘ **CORRECTED (S5-Q4, the council's sharpest finding).** v1 claimed
`5000× (AoS→SoA) × 200× (SoA→memo)`. The 200× does not survive the plan's own numbers: a warm
hit is *"~100 ns unless the AND result is itself a trie node"* against a cold SoA build of
~5 µs (itself an estimate), i.e. **~50×**. Reaching 200× REQUIRES memoising composed AND
results, which multiplies the key space by the number of pattern *pairs* — precisely where
reuse collapses. So:

- **The claim is now `5000× × ~50×`**, and the 50× is itself conditional on a hit rate nobody
  has measured.
- **200× is a HYPOTHESIS about composed-AND memoisation**, testable only by D-MRL-0b′
  (§12 L6), never an assumption.
- Two numbers, measured separately, and neither reported before D-MRL-0c pins the cold rung.

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
| **D-MRL-0b′** (rewritten) | Rail-group hit-rate replay **per class** (S5-Q2-RISK: a pooled stream averages a bimodal per-class distribution into a number describing no class), under 1×6 / 2×3 / 3×2, **under a real capacity bound**, reporting BOTH single-group and **composed-AND** hit rates (S5-Q4) | **G9's absolute gate**: `hit_rate × (cold − warm) > 0` per class. The relative ranking is reported, never a verdict. **Kill:** product ≤ 0 ⇒ Wave 1c dies |
| **D-MRL-0d** (new, S5-Q2-GAP) | Measure reuse on the axis the plan itself calls dominant: **tier-depth** hit rate, not only the rail axis. v1 asserted vertical masks are the most reusable objects and then measured only rails | a per-tier hit-rate curve. If tier depth dominates, the memo is populated top-down and the rail grouping is secondary |
| **D-MRL-0e** (new, S3-2) | Verify `ternlog` parity on NEON / WASM / scalar, the three backends S3 could not confirm | all five backends agree with the scalar oracle, or the "every backend" claim is struck from §1 |
| **D-MRL-0c** | Cold group-set build cost on the real `plan_eval` scalar path vs a hand `ternlog` composition, 64K rows | pins the "cold SoA" rung with a number, replacing §4's estimate |

### Wave 1 — `lgj-abi` (the RISC and the memo)

| D-id | deliverable | falsifier |
|---|---|---|
| **D-MRL-1a** | `LgjOpDesc` kinds `TERNLOG(imm, a, b, c)` and `TERNARY_MATCH(pattern[12], care[12])` over the facet register; composed inside `plan_eval` so a whole cascade is one crossing. Backend = `ndarray::simd::ternlog`; no raw intrinsic (simd-savant) | scalar oracle parity through `lgj_plan_eval_scalar` on a slab with planted hits; a disable of the care mask must flip a planted miss to a hit |
| **D-MRL-1b** | survivor-word skip in `plan_eval`: skip any 64-row block whose accumulator word is zero | 1..7-level cascade at 64K rows: cost per level after the first must be ∝ survivors. **Kill:** superlinear anywhere. **Plus (S4-6):** `exports.rs:2539` `a_bad_plan_leaves_dst_mask_untouched` and the `out_count` assertion at `:2636` assert full-population semantics and must be RE-VERIFIED deliberately, not left green by accident |
| **D-MRL-1c** | the mask trie in the registry, keyed **`(version, classid, prefix hash)`** (F2), **one writer: the `plan_eval` call that misses** (F7), entries are ordinary generation-checked mask handles; eviction is a miss, never a stale read (the version is immutable — the liveness question of `epoch-recheck-v3` does not arise here, by construction) | warm cascade ≤ 1.2× one cold level; a second identical query returns the same handle |
| **D-MRL-1d** ⊘ **RETIRED as a mechanism (S1-Q2, S5-Q1)** — the `mammal` claim is already PROVEN 7/7 (`E-HHTL-COMPILES-HIERARCHY-INTO-MASK-GEOMETRY-1`), and a prefix IS a ternary pattern (F6). What survives is a **policy**: which prefixes get memoised, populated top-down per D-MRL-0d, lowering onto `shared_prefix_tiles` | the surviving falsifier is G9's: prefix memoisation must show a positive `hit_rate × (cold − warm)`, measured by hit COUNT (G2), never by timing alone |

### Wave 2 — loco (the verbs get a producer)

| D-id | deliverable | falsifier |
|---|---|---|
| **D-MRL-2a** (AMENDED per F8) | mint **`BELNAP_JOIN` ONLY**, at the next free core slot **`0x8C`** — NOT the retracted `0x87..0x8B` — with a lowering table to Wave-1 op sequences. `BELNAP_JOIN` = OR of support planes ∥ OR of refute planes (#1129, `.claude/board/EPIPHANIES.md:438`, pinned by test per S3-5). **Mandatory co-changes (S4-7/8):** grep every repo for dangling references to the retracted mint FIRST; update `ogar-loco`'s vocabulary table and `docs/DISCOVERY-MAP.md` in the same arc | byte-exact: verb result == hand-composed `plan_eval` result on the same version. **Plus** the F-RLR-2 insufficiency argument recorded (S1-Q4), or this is an automatic STOP |
| **D-MRL-2b** | `where` / `scan` as verbs lowering to `TERNARY_MATCH` + vertical-prefix AND | same |

### Wave 3 — the Java glove (last, by E5)

| D-id | deliverable | falsifier |
|---|---|---|
| **D-MRL-3** | verbs on the facade; a 24-byte `TernaryPattern` value beside `WideFieldMask`; nothing else new | `GraphHopTest` reflective allowlist + G2 no-per-row-engine + allocation gates extended and green; `ApiSurfaceTest` unchanged |

---

## §7 — Frozen decisions (the council attacks these, not the prose)

- **F1.** No verb ever executes row-at-a-time. A verb that cannot be one `plan_eval` crossing is the signal the RISC is missing a word (root `CLAUDE.md` E5).
- **F2 (AMENDED, S5-Q3 — a correctness hole, not a tuning question).** The memo key is
  **`(Lance version, classid, prefix)`**. v1 said `(version, prefix)`; under the canon-high
  classid layout a prefix is NOT unique across classes, so two classes sharing a prefix would
  have ALIASED to one trie node. With F4 making the grouping a per-class policy, the class is
  part of the key by construction. There is no invalidation path; there is eviction. Any design that adds an epoch check to a trie read is rejected — that question was measured and closed on 2026-09-03 (`epoch-recheck-v3.md` Amendment A1, gate FAIL) and does not apply to immutable versions.
- **F3.** Belnap is data-side, two planes, per #1129. The pattern-side TCAM (`pattern`, `care`) is a separate op. They compose; they are not the same op.
- **F4.** Rail grouping is a per-class policy selected by classid, never a global constant.
- **F5 (AMENDED, S4-10).** Ship order is 0 → 1 → 2 → 3 and **Wave 0 gates the whole of Wave 1**,
  not only 1c. v1 gated only 1c, so starting 1a/1b before Wave 0 would have lost the
  kill-before-build property F5 exists to guarantee.
- **F6 (NEW, S1-Q2-GAP + S5-Q1).** **No new prefix mechanism.** A prefix IS a ternary pattern
  (`pattern` = the prefix nibbles, `care` = the prefix-length mask, don't-care tail), so
  `is_ancestor_of` is one `TERNARY_MATCH` over the key — not a second op kind. Vertical masks
  are therefore a **memoisation POLICY over 1a**, and any prefix narrowing lowers onto the
  existing `FacetCascade::shared_prefix_tiles` / `prefix_distance`. This removes a whole
  mechanism from the ABI surface and keeps the closed prefix-pushdown item closed.
- **F7 (NEW, S2-3).** The trie has **exactly one writer**, named in the spec before any code:
  the `plan_eval` call that misses. A reader never writes. Any design where two Wave-2 verb
  producers can race the same `(version, classid, prefix)` slot is rejected — that is the
  shared-mutable-sink shape the one-writer / mailbox-owner rule forbids.
- **F8 (NEW, S1-Q3 + S2-5, two lenses independently).** **The retracted slots are NOT
  reclaimed.** `ogar-loco/src/lib.rs:596-599` and `vocabulary.rs:94-97` rule *"reserve, don't
  reclaim — a future mint takes the next free slot deliberately"*. `0x87..0x8B` stay retired.
  The next free CORE slots are **`0x8C..0x8F` — four slots**, and `0x86..0x8F` is the whole
  remaining core range (`lib.rs:334,341`). Five verbs do not fit four slots: Wave 2 mints
  **`BELNAP_JOIN` only**, and the rest are a separate, deliberate decision.
- **F9 (NEW, S1-Q4).** The mask RISC must be shown to BE `EvidenceMask`'s set algebra
  (`dismech-causal-replay-v1.md:70`, "no second set algebra"), not a second one. If it cannot
  be, that is a STOP, not a footnote.

## §7b — NON-GOALS (explicit, each with why)

| out of scope | why |
|---|---|
| A general SQL surface, an optimiser, hash joins between arbitrary relations, spilling | DuckDB territory (§3). We serve one shape: fixed population per version, bit-pattern predicates, prefix reuse |
| Making the value semirings (HammingMin / SimilarityMax / Resonance / NarsTruth) mask-native | §2 fence: masks select, kernels score. Collapsing them is the exact overclaim this plan must not make |
| Any change to `RowStore`'s liveness model, `LaneProbe`, or the `closed` flag | measured and closed 2026-09-03 (Amendment A1, gate FAIL). Orthogonal: this plan's memo lives over immutable versions where the question does not arise |
| A basin/population tenant, ClassView, or 16-vs-24 dimensions for the epistemic axes | explicitly deferred by the post-#1132 teardown ("falsifier-first"); re-minting `0x87..0x8B` needs a producer, which is Wave 2, not a tenant |
| Java-side compute of any kind | root `CLAUDE.md` E1/E2: the glove never grows a compute path; scalar Java is licensed only as a test oracle |
| `ENVELOPE_LAYOUT_VERSION` bumps or any re-carving of the 12-byte register | the rail grouping (§5) is a cache policy over the existing `G6D2` reading, never a new carving |

## §7c — PRE-REGISTERED GATES (decided before any agent or worker runs)

**G1 — parity.** Every new `plan_eval` op kind agrees byte-for-byte with `lgj_plan_eval_scalar`
on the same fixture. A disable of the `care` mask must flip a planted miss into a hit
(two-sided, or the op is unfalsified).
**G2 — anti-vacuity on the memo.** A trie hit must be *observable as a hit* (hit counter), not
inferred from timing. A run where every query misses must fail the test that claims reuse.
**G3 — no per-row engine.** `GraphHopTest`'s reflective allowlist, the G2 no-per-row-engine
call-site check, and the population-independent allocation gates extend to every new surface
and stay green. `ApiSurfaceTest` unchanged (no FFM type in a public signature).
**G4 — SIMD provenance.** Every kernel line comes from `ndarray::simd`; zero `core::arch`,
`_mm*`, `#[cfg(target_arch)]`, `target_feature` in the diff (simd-savant).
**G5 — G11.** `native/lgj-abi/src` imports no `lance_graph_contract` module outside
`{canonical_node, class_view, facet, ontology}`; growing the list edits the test AND
`CLAUDE.md` AND `Cargo.toml` in one commit (`g11_contract_import_fence.rs`).
**G6 — central gates.** `cargo fmt --check`, `cargo clippy -p lgj-abi --all-targets -D warnings`,
`cargo test -p lgj-abi`, `javac` + `AllTests`, run ONCE by the orchestrator, never by a worker.
**G7 — two numbers, and the honest one.** Any reported speedup states the AoS→SoA factor and
the SoA→memo factor separately (§4), and the memo factor is **~50× unless composed-AND
memoisation is measured**, never 200× by assertion. A single fused number is a failed gate.
**G9 — the memo pays, absolutely (S5-Q4-RISK).** v1's kill for D-MRL-0b was *relative*
("no grouping beats 1×6 by ≥3×") and is satisfiable at a near-100% miss rate for every
grouping — a memo that never pays could have passed it. The gate is now ABSOLUTE and joins 0b
to 0c: replay under a REAL capacity bound and report `hit_rate × (cold − warm)` against the
measured crossing floor (21.911 ns, `bench/RESULTS.md`). If that product is not positive, the
memo does not pay and Wave 1c dies. No new probe is needed — G2's hit counter and 0c's cold
number are the apparatus (S5 PRIOR-ART-AT).
**G10 — ABI minor (S4-4/5).** Any new `LgjOpDesc` kind is additive ⇒ **bump `LGJ_ABI_MINOR`
8→9, rebuild the `.so` FIRST**, add the `OldAbiCompatTest` leg, update `docs/abi.md`. This is
a precondition of Wave 1a, not a follow-up, per the repo's own iron rule.
**G8 — board hygiene same-commit.** STATUS_BOARD row flip + AGENT_LOG entry naming the council
run + EPIPHANIES only if a finding emerged.

## §10 — PER-SAVANT QUESTION SETS (Phase 1; YES / NO / VIOLATES-with-evidence)

**S1 — prior art** *(Opus: ~100 board + knowledge docs, genuine multi-source)*
1. Does a mask memo / trie / cache keyed on a Lance version already exist or was one already
   ruled on, anywhere in lance-graph, OGAR, ndarray or lgj? Cite it.
2. Is "verbs lower to mask ops" already named under another id (E-*, D-*), and does this plan
   duplicate or contradict it?
3. Were `0x87..0x8B` torn down for reasons this plan fails to address? Quote the teardown.
4. Does `dismech-causal-replay-v1` or `rubicon-loco-rung-cognitive-fabric-v1` already own any
   wave here?
5. Any duplicate-E-id risk if this lands as written?

**S2 — iron rules** *(Sonnet)*
1. YIELDS or VIOLATES per iron rule: I-SUBSTRATE-MARKOV, I-NOISE-FLOOR-JIRAK,
   I-VSA-IDENTITIES, I-LEGACY-API-FEATURE-GATED.
2. Does any wave add a second reading of already-stored bytes without a version gate?
3. Does the trie constitute a shared-mutable sink (the one-writer / mailbox-owner rule)?
4. Does re-minting `0x87..0x8B` violate the classid canon-high or the domain-floor rule?
5. Any AP1–AP9 anti-pattern present?

**S3 — code truth** *(Sonnet)*
1. For EVERY `file:line` in §1: CODED, CLAIMED, or ABSENT? One line each.
2. Is `ternlog` genuinely present on all five backends with a parity test, or only some?
3. Does `lgj_plan_eval` actually AND-chain and actually sweep the full population per op?
4. Is the `DOWN[x]` falsifier genuinely never-run (no committed test covers it)?
5. Is the #1129 Belnap join identity (OR of both planes) actually pinned by a test?

**S4 — cascade impact** *(Sonnet)*
1. Every file / test / doc / board row that MUST change per wave; mandatory vs follow-up.
2. Does any wave force an ABI minor bump, and does §6 say so? (Q2 is the live case.)
3. Which existing tests break if `plan_eval` gains a survivor-word skip?
4. Which consumers outside lgj are affected by re-minting `0x87..0x8B`?
5. Is the wave order buildable, or does any wave depend on a later one?

**S5 — different views** *(Opus: alternative-reading synthesis)*
1. Strongest alternative reading of the two-axis model that this plan misses — WITHOUT
   redesigning it.
2. Is 2×3 rail grouping the right factoring, or is the real reuse axis something else
   (tier depth, classid, time)?
3. Second-order consequence of a per-class cache policy nobody has named.
4. What is the strongest argument that the memo will NOT pay, and does D-MRL-0b actually
   test that argument?
5. Is "photolithography" load-bearing or decorative — does any decision depend on it?

## §8 — Model allocation (operator-ruled 2026-09-03, declared here, not per spawn)

- **Planning is Opus.** The 5 council savants, the 3 reviewers, the consolidation passes,
  every wave's spec, every "does this measurement mean what it appears to mean" call, and
  the orchestrator (central gates: `cargo`/`javac`/`AllTests`, every commit and push).
- **Grindwork is Sonnet.** Bounded transcription against a written spec: port THIS kernel
  composition, write THIS probe from THIS table, thread THIS op kind through THESE call
  sites, replay THIS pattern stream. One source in, one shape out. Never a worktree, never
  its own cargo run, never a claim that it compiles.
- **Never Haiku** for anything in this plan.

## §12 — CHANGE LEDGER v1 → v2 (Phase-2 consolidation; reviewers not yet cast)

Five savants, 40 findings. Every VIOLATES amended, every GAP filled with a committed decision,
every PRIOR-ART-AT wired in. Losing findings are recorded, not deleted.

| # | finding | lens | resolution in v2 |
|---|---|---|---|
| L1 | Belnap epiphany path wrong (repo root vs `.claude/board/`) | S3-1 | §1 corrected |
| L2 | `ternlog` on NEON/WASM/scalar is CLAIMED, not verified | S3-2 | §1 regraded; **D-MRL-0e** added to verify before the claim is made |
| L3 | Two-axis model already exists verbatim | S1-Q2 | §2 now cites `EPIPHANIES.md:13403`; the plan claims no novelty |
| L4 | **The `mammal` claim is already PROVEN 7/7** | S1-Q2 | **D-MRL-1d retired as a mechanism**; what survives is a memoisation policy |
| L5 | Prefix pushdown was dropped by operator correction | S1-Q2-GAP | **F6**: no new prefix mechanism; lower onto `shared_prefix_tiles` / `prefix_distance` |
| L6 | **200× is not supported by the plan's own numbers (~50×)** | S5-Q4 | §4 corrected; 200× demoted to a hypothesis about composed-AND memoisation, testable only by 0b′ |
| L7 | 0b's kill was RELATIVE — a never-paying memo could pass | S5-Q4-RISK | **G9**: absolute `hit_rate × (cold − warm) > 0`, joining 0b to 0c. No new probe (S5 PRIOR-ART-AT) |
| L8 | **Key aliases across classes sharing a prefix** | S5-Q3 | **F2 amended**: key is `(version, classid, prefix)`. A correctness hole, not tuning |
| L9 | F-RLR-2 automatic STOP on a new carrier | S1-Q4-RISK | D-MRL-2a must record the `ogar_loco`-insufficiency argument or STOP |
| L10 | **Slot reclaim VIOLATES "reserve, don't reclaim"** (two lenses, independently) | S1-Q3, S2-5 | **F8**: `0x87..0x8B` stay retired; next free core slots are `0x8C..0x8F` — and only **four** remain, so Wave 2 mints `BELNAP_JOIN` alone |
| L11 | Trie has no named writer | S2-3 | **F7**: exactly one writer, the missing `plan_eval` call |
| L12 | No version gate shown at the ABI boundary | S2-2 | Absorbed into F2 + G10: the key carries the version, and a handle whose version does not match its key is refused at resolve. **Recorded as a gate, not waved away** |
| L13 | ABI minor bump never stated as a precondition | S4-4/5 | **G10**: bump 8→9, rebuild the `.so` first, `OldAbiCompatTest` leg, `docs/abi.md` — a precondition of 1a |
| L14 | `docs/abi.md` not a named deliverable | S4-3 | folded into G10 |
| L15 | Survivor skip breaks two full-population tests | S4-6 | named in 1b's falsifier column |
| L16 | Wave 2 named no OGAR-side co-change | S4-8 | 2a now names the vocabulary table, `DISCOVERY-MAP.md`, and a dangling-reference sweep |
| L17 | Wave 0 gated only 1c | S4-10 | **F5 amended**: Wave 0 gates all of Wave 1 |
| L18 | Per-class policy makes hit rate per-class; 0b pooled it | S5-Q2-RISK | 0b′ is per-class |
| L19 | The dominant reuse axis (tier depth) was never measured | S5-Q2-GAP | **D-MRL-0d** added |
| L20 | `standing_mask` is the nearest precedent, uncited | S1-Q1 | added to §1; 1c must read its conclusions before re-deriving them |
| L21 | "No second set algebra" (`EvidenceMask`) unreconciled | S1-Q4-RISK | **F9**: the RISC must be shown to BE that algebra, or STOP |
| L22 | Cross-class eviction contention unnamed | S5-Q3 | open question, moved to §11 Q3 with the pinning-budget owner named as the missing decision |
| L23 | Photolithography reads as evidence for reuse | S5-Q5-RISK | §0 note: the analogy is load-bearing ONLY for wafer-immutability (which licenses F2's no-invalidation rule); it predicts NOTHING about hit rate |
| L24 | AP9 does not exist (catalogue is AP1–AP8) | S2-6 | question set corrected for the next council |

**Findings recorded but NOT acted on (anti-collapse):** S2-1's four YIELDS verdicts (no
amendment needed); S4-9's acyclicity CONFIRMS; S3-3/4/5's three CONFIRMS. These are the
evidence that the un-amended parts of the spec survived review, and are kept as such.

## §11 — Open questions for the council

- **Q1.** Trie ownership: `lgj-abi` registry (consumer-local) or `lance-graph` (shared by every consumer)? The version is lance-graph's; the handles are lgj's. Substrate-first says lance-graph, but no consumer other than lgj exists yet.
- **Q2.** Does `TERNARY_MATCH` belong in `LgjOpDesc` (24 B, fixed) or does a 24-byte pattern+care force a wider descriptor and an ABI minor bump?
- **Q3 (SHARPENED, S5-Q3).** Eviction: LRU, or pinned vertical masks + LRU horizontal? And the
  part v1 never asked — **who owns the pinning budget** when policies are per-class? Per-class
  policies compete for one fixed-capacity trie, so a hot class can evict another class's pinned
  vertical masks.
- **Q4.** Which real pattern stream for D-MRL-0b, and who owns it (MedCare-rs is private).

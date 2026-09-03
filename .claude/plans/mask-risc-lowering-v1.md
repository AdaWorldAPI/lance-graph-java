# mask-risc-lowering-v1 — the API speaks database, the backend does photolithography

> **Status:** RATIFIED v3 — Phase-5 of the 5+3 council. Two BLOCKs resolved, 11 FIX applied,
> 2 external Codex P1s verified and folded in. Change ledger v2→v3 is §13.
>
> **Status (v2):** DRAFT v2 — Phase-2 consolidation of the 5+3 council (5 savants reported;
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
   horizontal-data     rail-group BELNAP sets: support/refute planes per 64 rows (#1129)
   horizontal-predicate TERNARY_MATCH(pattern, care) applied AGAINST them — a SEPARATE op (F3)
        │ over
   the version's SoA   512 × 32 × (4+12), never invalidated             V3: shape transport
```

Photolithography, literally: the version is the wafer, each cached mask a developed stencil,
each RISC op an exposure, a query a stack of exposures. **Awareness = which stencils are
already developed.** A second query sharing a prefix pays only for its undeveloped layers.

**The `mammal` sentence, made mechanical.** Walking the vertical axis down the taxonomy
(`is_a` rails, HHTL prefix) to the node `mammal` yields one mask: every row whose key carries
that prefix. That mask **would be** a trie node under §6's proposal `[S]`. Every query about anything below
`mammal` **would start** from that one cached set and expose only its residue, and the subtree
**would not have to be** re-selected — **if the memo hits, which is unmeasured** (D-MRL-0d).
⊘ v2 wrote this in the present indicative about a thing that does not exist (R1). That is why the
vertical masks are the most reusable objects in the system and go into the memo first.

---

## §1 — What exists (DOCUMENTATION register: every row cites source)

| piece | where | what it already gives us |
|---|---|---|
| Mask algebra over the population | `native/lgj-abi/src/exports.rs:518,524,680` (`lgj_mask_and/or/andnot`), `:332` (`lgj_mask_create`) | horizontal AND / OR / ANDNOT, `Box<[u64]>` per mask, generation-checked handles |
| The narrowing chain | `exports.rs:1522` `lgj_plan_eval`; `abi.rs:337` `LgjOpDesc` (24 B, `combine` at `:345`) | n predicates, ONE crossing, accumulator starts all-set, monotone `V(k+1) ⊆ V(k)`. Today's op kinds: `eq_u32` (`:755`), `gt_i32` (`:787`), `eq_classid` (`:829`) — all scalar-valued, none bitwise over the payload |
| The graph op | `exports.rs:1712` `lgj_hop` | Boolean-semiring `mxm` as mask × ClassView/WideFieldMask → mask (root `CLAUDE.md` mask-native invariant) |
| The 3-input bit op | `ndarray/src/simd.rs:570` `pub mod ternlog` (`MAJ3 = 0xE8` at `:582`); `simd_avx512.rs:4934,4972` (`U64x8`/`U32x16::ternlog::<IMM>`, native `vpternlogq`); AVX2 `simd_avx2.rs:3559`; ALL five backends **CODED** — NEON `simd_neon.rs:1882`, WASM `simd_wasm.rs:998`, scalar `simd_scalar.rs:2088,:2147` | ⊘ v2 graded three backends "CLAIMED"; R1 corrected it in the UNDERSTATING direction — they are coded. What is unverified is **cross-backend PARITY `[H]`**. And the flattening hid the asymmetry that matters: **scalar/AVX-512/AVX2 expose TWO widths, NEON and WASM expose ONE** (verified by count). D-MRL-0e establishes which widths exist per backend, not merely "agreement" |
| The minted opcode | `OGAR/crates/ogar-loco/src/lib.rs:607` `TERNLOG = 0x86`; `:596` and `vocabulary.rs:94`: **`0x87..0x8B` reserved**, previously `BELNAP_JOIN`, `INFO_GAIN`, `SIGMA_TENSION`, `ACCUMULATE`, `STANCE_ENTROPY`, torn down post-#1132 as "no producer, no basin" | the RISC op has a loco slot; the verbs have reserved slots waiting for a producer |
| The Belnap encoding | lance-graph `.claude/board/EPIPHANIES.md:438` `E-THE-STATE-LAYER-IS-A-BELNAP-BILATTICE-AND-THE-JOIN-IS-THE-ACCUMULATOR-1` (#1129) | two planes `support_mask` / `refute_mask`; `(0,0)` Neither, `(1,0)` True, `(0,1)` False, `(1,1)` Both; **knowledge-order join = bitwise OR of both planes**, proven and pinned |
| The vertical axis | `lance-graph-contract/src/hhtl.rs:56` `NiblePath`; `rail_geometry.rs:178` `is_ancestor_of` | prefix containment IS ancestry; the tier of a nibble is `n >> 2` (OGAR `CLAUDE.md`, 3×4 canon) |
| The reject-early cascade | `ndarray/src/hpc/splat3d/depth_cascade.rs:54` `HhtlAction`, `:137` `cascade_block`, `:194` `cascade_blocks` | a working precedent for cheap-reject-before-expensive-compute over tiered blocks |
| The rail carving | `lance-graph-contract/src/facet.rs:398` (the variants; `:450` `groups()`, `:462` `levels()`) — ⊘ v2 cited `:367-369`, a doc-comment table, not the item (R1) | 6 × 2 rails is a ClassView-selected reading of the 12-byte register; a 2 × 3 rail grouping is a policy over `G6D2`, not a new carving |
| Field-mask algebra | `class_view.rs:385,395,413,426` `intersect / union / difference / is_subset_of` (D-MAR-1, #1099) | the WideFieldMask half of "which fields participate" is complete |
| The falsifier already designed | lance-graph `.claude/harvest/spatial-mask-r2il-audit-2026-08-24/REPORT.md` §6 item 5 | `DOWN[x]` bitmask over a synthetic 64-node DAG vs `is_ancestor_of`, one multi-parent exception routed to `FieldMask`, timed at N=64 and N=4096. **Never run.** |

**PRIOR ART the v1 spec failed to cite (S1) — this is the largest single correction:**

| already shipped / ruled | where | what it means for this plan |
|---|---|---|
| The two-axis model, **verbatim** | `.claude/board/EPIPHANIES.md:13403` — *"Vertical navigation is FREE (prefix arithmetic); horizontal costs a hop; the mask is the budget deciding which hops are paid"* | §2 and §5 are a RESTATEMENT, not a new claim. Now cited; the plan claims no novelty there |
| The `mammal` **ALGEBRA** is proven; the **REUSE half is not** | `.claude/board/entries/2026-08-23-e-hhtl-compiles-hierarchy-into-mask-geometry-1.md:10,26` — *"HHTL does not execute a tree. It compiles hierarchy into mask geometry"*, `M0 ⊇ M1 ⊇ … ⊇ M5`, measured **7/7** by `PROBE-MASK-ALGEBRA-INVARIANCE-1` ⊘ v2 read this as proving the whole `mammal` sentence. The entry is `[MEASURED]` 7/7 for **nested restriction and indifference to meaning**, says **"Novelty explicitly NOT claimed"**, and measures an ALGEBRA, not a cache — it proves nothing about memoisation or reuse (R1, verified). D-MRL-1d's *mechanism* is duplicated; its *reuse policy* is unmeasured and survives |
| Prefix pushdown was **dropped by operator correction** | `.claude/board/EPIPHANIES.md:13401` — three shapes rejected, because prefix containment/narrowing already IS `FacetCascade::shared_prefix_tiles` / `prefix_distance` | any prefix mechanism must LOWER ONTO those symbols or it re-opens a closed item |
| `standing_mask` (`SubscriptionTable<K>`, fires iff `dirty ∩ interest ≠ ∅`) | `.claude/board/EPIPHANIES.md:13397` | the shipped persistent-mask-keyed-by-interest surface: nearest structural precedent to the trie. Its Vec+linear-scan / shard-per-tenant conclusions must be read before 1c, not re-derived |
| `E-MASK-SELECTION-ALGEBRA-1` | `.claude/board/EPIPHANIES.md:13393` | confirms no version-keyed memo exists anywhere: §1's "verified absent" HOLDS |
| "No second set algebra: Mengenlehre = `EvidenceMask` ops" | `.claude/plans/dismech-causal-replay-v1.md:70` | owns set-algebra repo-wide. The mask RISC must be shown to BE that algebra, not a second one |
| `F-RLR-2`: *"a new carrier is proposed before `ogar_loco` is proven insufficient — automatic STOP"* | `.claude/plans/rubicon-loco-rung-cognitive-fabric-v1.md:422` | Wave 2's lowering table and Wave 3's `TernaryPattern` TRIP this gate unless the insufficiency argument is recorded. See §12 L9 |

**No occurrence found by the S1/S3 sweep `[H]` — an absence claim, not a proof** (⊘ v2 said "verified absent"; a negative existence claim over three repos rests on greps this document cannot re-run. A Wave-0 worker re-runs it before 1c mints anything): any mask memo in `lance-graph-planner`,
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
| cold, AoS | row-at-a-time / full 512-B-row sweep per query | **~25 ms per group `[E — asserted, never measured]`** | 32 MB touched. Nobody has timed the `classidAt`-per-row path. **Added to D-MRL-0c's scope**, which v2 pointed only at the SoA rung |
| cold, SoA | build one 3-rail group set from the rail lane | **~5 µs per group `[E — estimate]`** | 384 KB, one AVX-512 pass. D-MRL-0c measures it |
| warm | trie hit, two cached sets, one AND | **~100 ns per group `[E — arithmetic, unmeasured]`** | ⊘ v2 headlined `~25 ns`, the very number that yields 200×, while its own provenance cell said 100 ns — a reader quoting the table re-derived the retracted claim (R1 BLOCK). The **measured** 21.911 ns crossing floor (`bench/RESULTS.md`) bounds this from BELOW; ~25 ns is floor + dispatch, **never the measured warm cost**. ~25 ns applies only if the AND result is itself a trie node — the composed-AND hypothesis |

⊘ **CORRECTED (S5-Q4, the council's sharpest finding).** v1 claimed
`5000× (AoS→SoA) × 200× (SoA→memo)`. The 200× does not survive the plan's own numbers: a warm
hit is *"~100 ns unless the AND result is itself a trie node"* against a cold SoA build of
~5 µs (itself an estimate), i.e. **~50×**. Reaching 200× REQUIRES memoising composed AND
results, which multiplies the key space by the number of pattern *pairs* — precisely where
reuse collapses. So:

- **The claim is now `[E]5000× × [E]~50×` — BOTH factors are ratios of UNMEASURED quantities**
  (R1 BLOCK: v2 demoted the 200× and left its 5000× sibling ungraded). Neither is reportable
  before D-MRL-0c pins both cold rungs, and the ~50× is additionally conditional on a hit rate
  nobody has measured.
- **200× is a HYPOTHESIS about composed-AND memoisation**, testable only by D-MRL-0b′
  (§12 L6), never an assumption.
- Two numbers, **to be measured** separately, and neither reported before D-MRL-0c pins the
  cold rungs. Only the 21.911 ns crossing floor is measured today, and it is not one of the two.

---

## §5 — Rail-group factoring (why the memo MAY hit — rail axis, pending 0b′/0d)

Group the 6 rails 2 × 3: two groups of three rails, each with a **rail-group Belnap cached set**
(support plane + refute plane per 64 rows). ⊘ v2 called this a "ternary cached set", welding the
predicate-side TCAM to the data-side Belnap planes that F3 keeps separate (R2). Query = `group_A(pattern_A) AND group_B(pattern_B)` plus a
`TERNLOG` inside a group for a partial pattern.

**Hypothesis `[H]`, not fact** (⊘ v2 stated three empirical claims about a real pattern
distribution as fact, in a section whose own last line concedes the number is unmeasured — R1):
a 6-rail pattern's 256⁶ space makes recurrence unlikely while a 3-rail 256³ space may recur,
**and the halves MAY recur independently. Independence is the load-bearing assumption and is
untested** — if it is false it kills the composed-AND memo the 200× depends on, so 0b′ carries
it as a distinct kill condition. The grouping is a
per-class cache policy resolved by the same classid lookup as the carving (§1 `CascadeShape`).
**The number that picks 2×3 vs 3×2 vs 6×1 is memo hit rate on a real pattern stream** — wave 0.

Cost per exposure, warm: 4 `u64` words per 64 rows, one AND, one TERNLOG.

---

## §6 — What is PROPOSED (PLAN register), substrate-first per the STOP rule

### Wave 0 — measure before minting (no production code)

| D-id | probe | pass / kill |
|---|---|---|
| **D-MRL-0a** | Run the audit's `DOWN[x]` falsifier **against `rail_geometry.rs:178`'s `is_ancestor_of`, NOT `hhtl.rs:176`'s** (two exist, different carriers — R1): synthetic 64-node DAG, one multi-parent exception, `DOWN[x]` as a real bitmask vs `is_ancestor_of` on every single-inheritance node; time N=64 and N=4096 | exact agreement on single-inheritance nodes; multi-parent routes to `FieldMask`, corrupts neither. **Kill:** any disagreement — the vertical axis is not a bitmask and §2 is wrong |
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
| **D-MRL-1d** ⊘ **RETIRED as a mechanism (S1-Q2, S5-Q1)** — the **nesting/indifference half** is PROVEN 7/7 (`E-HHTL-COMPILES-HIERARCHY-INTO-MASK-GEOMETRY-1`); the **reuse half is measured nowhere**, which is exactly why a policy survives rather than the question being closed, and a prefix IS a ternary pattern (F6). What survives is a **policy**: which prefixes get memoised, populated top-down per D-MRL-0d, lowering onto `shared_prefix_tiles` | **D-MRL-1d′ (the surviving policy, now with an owner and a STATUS_BOARD row — R2)**: the falsifier is G9's: prefix memoisation must show a positive `hit_rate × (cold − warm)`, measured by hit COUNT (G2), never by timing alone |

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
  part of the key by construction.
- **F2 wording (R1).** ⊘ "There is no invalidation path; there is eviction" was present
  indicative about a design that does not exist. It is a STIPULATION: **the memo MUST have no
  invalidation path, only eviction** — licensed by wafer-immutability (§0), a design constraint,
  not an observed property. And ⊘ "that question was measured and closed" conflated two objects:
  the `epoch-recheck-v3` A1 FAIL is about `RowStore`'s **cached lane descriptors**, whose residue
  (`ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW`) is still OPEN. **The trie's case is argued
  from immutability, never inherited from that measurement.** There is no invalidation path; there is eviction. Any design that adds an epoch check to a trie read is rejected — that question was measured and closed on 2026-09-03 (`epoch-recheck-v3.md` Amendment A1, gate FAIL) and does not apply to immutable versions.
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
- **F10 (NEW, R1 + R2, and it is a REQUIREMENT not a record).** A trie handle whose recorded
  version does not match the requested `(version, classid, prefix)` key is **refused at resolve**.
  ⊘ v2's ledger recorded this as "absorbed into F2 + G10"; it was in neither, and `registry.rs`
  contains **zero occurrences of `version`** (verified) — handles carry owner + generation + kind
  only. So this is a **new Wave-1c requirement with its own falsifier**, not an existing gate.
  It is a KEY-IDENTITY check, explicitly NOT an epoch re-check. This is the exact shape of
  `ISS-LGJ-G11-FENCE-WAS-PROSE`, caught before it shipped.
- **F11 (NEW, R3).** `TERNARY_MATCH`'s `pattern[12]` / `care[12]` cross the ABI as **raw
  fixed-width struct fields only** (the existing `LgjOpDesc` 24-byte fixed-layout precedent),
  never through a serialize/deserialize or JSON/text encoding. Same rigour as F1's row-at-a-time
  prohibition; closes the ambiguity §11 Q2 invited before any Wave-1a code lands.
- **F12 (NEW, external Codex P1 on #67, verified).** **A trie hit never hands a caller a handle
  the caller may close.** Java's `Mask` is its handle's sole closer and `Mask.close()` closes
  unconditionally (`Mask.java:155-161`); `registry::close` takes the entry and bumps the
  generation (`registry.rs:322-336`). So returning the cached handle means **closing the first
  result destroys the trie entry** — while G2-MEMO's hit counter still reports successful reuse.
  The cache owns its handles independently; a hit yields a fresh client handle. **G11-GATE:** a
  test must CLOSE a result and then repeat the query, asserting the second query still hits.
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
**G2-MEMO — anti-vacuity on the memo** (⊘ renamed in v3: v2 called this `G2` while G3 cited the
repo-level `G2` no-per-row-engine gate — one symbol, two gates, one page (R2)). A trie hit must be *observable as a hit* (hit counter), not
inferred from timing. A run where every query misses must fail the test that claims reuse.
**G3 — no per-row engine.** `GraphHopTest`'s reflective allowlist, the G2 no-per-row-engine
call-site check, and the population-independent allocation gates extend to every new surface
and stay green. `ApiSurfaceTest` unchanged (no FFM type in a public signature).
**G4 — SIMD provenance.** Every kernel line comes from `ndarray::simd`; zero `core::arch`,
`_mm*`, `#[cfg(target_arch)]`, `target_feature` in the diff (simd-savant).
**G5 — G11.** `native/lgj-abi/src` imports no `lance_graph_contract` module outside
`{canonical_node, class_view, facet, ontology}`; growing the list edits the test AND
`CLAUDE.md` AND `Cargo.toml` in one commit (`g11_contract_import_fence.rs`).
**G6 — central gates.** `cargo fmt --check`, `cargo clippy -p lgj-abi --all-targets -- -D warnings`
(⊘ v2 omitted the `--`; **verified empirically**: the v2 form exits `unexpected argument '-D'
found` BEFORE linting anything, so the mandatory central gate could not run — external Codex P1),
`cargo test -p lgj-abi`, `javac` + `AllTests`, run ONCE by the orchestrator, never by a worker.
**G7 — no number is reported before it is measured.** ⊘ v2 forbade asserting 200× while
MANDATING asserting ~50×, which is `5 µs ÷ 100 ns`, an estimate ÷ an arithmetic guess (R1).
Ruled: the memo factor is **unreported until D-MRL-0c measures the cold rungs**; the ~50×
arithmetic is a **bound on the hypothesis, never a result**. Reporting a single fused number,
OR reporting ~50× as measured, is a failed gate.
**G9 — the memo pays, absolutely (S5-Q4-RISK).** v1's kill for D-MRL-0b was *relative*
("no grouping beats 1×6 by ≥3×") and is satisfiable at a near-100% miss rate for every
grouping — a memo that never pays could have passed it. The gate is now ABSOLUTE and joins 0b
to 0c: replay under a REAL capacity bound and report `hit_rate × (cold − warm)` against the
measured crossing floor (21.911 ns, `bench/RESULTS.md`). If that product is not positive, the
memo does not pay and Wave 1c dies. No new probe is needed — G2's hit counter and 0c's cold
number are the apparatus (S5 PRIOR-ART-AT).
**G11-GATE — the memo survives a close (F12).** A test closes a hit's result and repeats the
query; the second query must still hit. Without it, G2-MEMO's counter certifies a cache whose
entries a caller can destroy.
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

## §8 — Worker allocation, BY ROLE (⊘ v3: tiers struck)

⊘ **BLOCK(P0), R2.** v2 named model tiers by product name. Root `CLAUDE.md`: *"**No model
identifier in any committed artifact** (chat only). This file deliberately carries NO
model-policy section; worker-tier allocation is stated by role in session briefs."* Also a
dilution — ROLE is durable, TIER is perishable, and v2 put the perishable half in the durable
artifact. The allocation is unchanged; only the identifiers are struck.

- **Accumulation roles** — the council's savants and reviewers, every consolidation pass, each
  wave's spec, every "does this measurement mean what it appears to mean" judgement, and the
  orchestrator (central gates, every commit and push).
- **Bounded-transcription roles** — port THIS kernel composition, write THIS probe from THIS
  table, thread THIS op kind through THESE call sites, replay THIS pattern stream. One source
  in, one shape out. Never a worktree, never its own cargo run, **never a claim that it
  compiles**.
- **The workspace floor applies**; the lowest tier is excluded for every role here.

Tier binding lives in the session brief, never in this file.

## §13 — CHANGE LEDGER v2 → v3 (Phase-4/5; three reviewers + one external review)

Two BLOCKs, eleven FIX, two external Codex P1s. Where R1 and R3 conflicted with R2 on §8, the
**stricter verdict won** per the harness, without an operator escalation line.

| # | verdict | resolution |
|---|---|---|
| B1 | **BLOCK(P0)** R2 §8 — model identifiers in a committed artifact | §8 rewritten BY ROLE; tier binding moved to the session brief. R1 and R3 both PASSED this section; the stricter verdict won |
| B2 | **BLOCK(P0)** R1 §4 — the warm cell contradicted its own provenance | every rung regraded `[E]`; warm headline is now ~100 ns; ~25 ns demoted to floor+dispatch; `5000×` graded `[E]` alongside its already-demoted sibling |
| F1 | FIX(P1) R1 §1 — `ternlog` mis-graded, in the UNDERSTATING direction | all five backends are CODED (verified); what is unverified is PARITY. **NEON and WASM expose one width, the others two** — 0e must establish widths, not "agreement" |
| F2 | FIX(P1) R1 §1 — `facet.rs:367-369` cites a doc table, not the item | re-cited to `:398` (+ `:450`, `:462`) |
| F3 | FIX(P1) R1 §1 — "verified absent" graded as fact | regraded `[H]`, an absence claim; a Wave-0 worker re-runs the sweep |
| F4 | FIX(P1) **R1 §12 L12 + R2 §7, two lenses** — a gate recorded that does not exist | **F10 (NEW)**. `registry.rs` has **zero** `version` occurrences (verified). This is the `ISS-LGJ-G11-FENCE-WAS-PROSE` shape caught pre-ship |
| F5 | FIX(P1) R1 §6/§1/L4 — "PROVEN" used too wide | the nesting/indifference half is proven 7/7; the **reuse half is measured nowhere**. The entry itself says "Novelty explicitly NOT claimed" |
| F6 | FIX(P1) **R2 §0 + §5** — ternary × Belnap conflated | diagram split into `horizontal-data` (Belnap planes) and `horizontal-predicate` (TERNARY_MATCH); "ternary" reserved for the predicate leg; the object renamed "rail-group Belnap cached set" |
| F7 | FIX(P1) R3 §6 — serialization ambiguity at the new op | **F11 (NEW)**: pattern/care cross as raw fixed-width struct fields only |
| F8 | FIX(P1) R1 §7c — G7 forbade one unmeasured number while mandating another | G7 now forbids reporting ANY memo factor before 0c |
| F9 | FIX(P2) R1 §0 — `mammal` in the present indicative about a non-existent trie | conditionalised and graded `[S]` |
| F10 | FIX(P2) R1 §5 — three empirical claims stated as fact | regraded `[H]`; **independence named as a distinct kill condition** in 0b′ |
| F11 | FIX(P2) R1 §7 — F2's "there is no invalidation path" | restated as a STIPULATION; the `RowStore` A1 measurement explicitly NOT inherited |
| F12 | FIX(P2) R2 §6 — the surviving policy had no owner | **D-MRL-1d′** with G9 as its gate and a STATUS_BOARD row |
| F13 | FIX(P2) R2 §7c — `G2` named two different gates on one page | renamed **G2-MEMO** |
| F14 | FIX(P2) R1 §6 — `is_ancestor_of` is ambiguous (two exist) | 0a names `rail_geometry.rs:178` |
| X1 | **external Codex P1, verified** — the mandatory central gate could not run | `cargo clippy … -- -D warnings`. The v2 form exits before linting; both forms run by me |
| X2 | **external Codex P1, verified** — close-and-requery destroys the memo | **F12 (NEW)** + **G11-GATE**: the cache owns its handles; a test closes a result and re-queries |

**Accounting (R2 + R1).** v2's header said "40 findings"; L1–L24 plus the recorded-not-acted
paragraph did not account for all of them. Corrected: the five savants returned **40 findings**
across 5 lenses; **24 produced amendments (L1–L24)**, **8 were CONFIRMS requiring no amendment**
(recorded below, not deleted), and **8 were sub-findings folded into the 24 rows they support**.
No finding was discarded.

**§9 (R2).** There is no §9 and there never was — v2's section numbering jumped from §8 to §10
when §7b/§7c were inserted. Recorded here rather than left as a silent gap, since an absent
numbered section is indistinguishable from a deleted leg.

## §11 — Open questions

- **Q1.** Trie ownership: `lgj-abi` registry (consumer-local) or `lance-graph` (shared by every consumer)? The version is lance-graph's; the handles are lgj's. Substrate-first says lance-graph, but no consumer other than lgj exists yet.
- **Q2.** Does `TERNARY_MATCH` belong in `LgjOpDesc` (24 B, fixed) or does a 24-byte pattern+care force a wider descriptor and an ABI minor bump?
- **Q3 (SHARPENED, S5-Q3).** Eviction: LRU, or pinned vertical masks + LRU horizontal? And the
  part v1 never asked — **who owns the pinning budget** when policies are per-class? Per-class
  policies compete for one fixed-capacity trie, so a hot class can evict another class's pinned
  vertical masks.
- **Q4.** Which real pattern stream for D-MRL-0b′, and who owns it (MedCare-rs is private).
- **Q5 (NEW, R2).** Which of the four deferred verbs (`INFO_GAIN`, `SIGMA_TENSION`,
  `ACCUMULATE`, `STANCE_ENTROPY`), if any, takes a slot from the **four** remaining core slots
  `0x8C..0x8F`, and on what producer evidence? Deferral without a question is how a reserved leg
  becomes a forgotten one.

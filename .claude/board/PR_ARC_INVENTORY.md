# PR Arc Inventory — per-PR Added / Locked / Deferred / Docs / Confidence
# (reverse chronological, APPEND-ONLY; only the Confidence line is
# updatable in place — corrections append as new dated lines; reversals
# get their own PR entry)

> **Hygiene lapse, owned (2026-08-17):** PRs #1-#3 merged without their
> entries landing in the same commit — the exact retroactive-hygiene
> anti-pattern the imported board rules name. Backfilled below in one
> pass rather than left stale; PR #4 onward gets its entry at merge time.

## PR #20 — D-LGJ-W8 A3 freeze (PR-0): ratified correction spec v3 + root CLAUDE.md + board storno (merged 2026-08-18, squash `c479f76`)

- **Added:** `.claude/plans/mask-native-navigation-correction-v1.md`
  (SPEC v3, council-RATIFIED — Part I: the mask-native navigation
  correction; Part II: the 64K parallel-SoA compute / deterministic
  placement / batch-publication architecture, audited at file:line
  across the lance-graph spine; full change ledger v1→v2→v2.1→v3);
  root `CLAUDE.md` (CREATED — the repo's first root policy guard:
  mask-native invariant covering query AND compute verbs, named
  import/materialise exceptions, three-axes model, the GridLake hard
  gate, missing-capability STOP rule, no model-policy section by
  explicit rule); `EPIPHANIES.md` storno
  `E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1`; `STATUS_BOARD.md`
  D-LGJ-W8 row (gate ladder AUDIT✓→SPEC✓→COUNCIL✓→FREEZE=this PR);
  `LATEST_STATE.md` correction-of-record.
- **Locked:** the operator rulings of 2026-08-18 (CORRECTION WAVE +
  RULING CLARIFICATION + A1 ARCHITECTURE RULING) as frozen decisions;
  the three/four currencies; PR #18 demoted to scalar reference oracle
  (NOT destroyed — both BFS transcriptions preserved verbatim); the
  landing-key REQUIRED PROPERTIES (never an algorithm) with the
  generic-parallel-write hard gate; W8 = mask correction only, GridLake
  specified-not-implemented.
- **Council record:** 5 savants (44 findings — incl. the
  lock_masks_ordered aliasing DEADLOCK trap, the W1a free-fn deviation
  surfaced not smuggled) + operator A2 verdict + 3 adversarial
  reviewers: 1 BLOCK(P0) resolved (model identifiers de-named to roles
  in the committed spec; the repo's pre-existing board occurrences
  flagged to the operator, deliberately NOT swept), 6 FIX(P1) applied
  (§8.4 evidence re-scoping — temporal.rs's objection is restart-only,
  answered by the durable position_base cursor; the surviving
  within-cycle arrival leak argued on its own evidence; §9 axis split
  filing the leak on PUBLICATION; F-LAND scoped to the placement leg;
  G2 respecified as a committed call-sites-only check; G11
  contract-import fence; same-commit board artifacts in W8a/W8b gate
  columns), ~20 FIX(P2). One reviewer-vs-savant factual conflict
  settled by reading the file (Graph.java storno anchor = :18-19; the
  S3-1 "correction" to :19-20 was itself wrong — stornoed in the
  ledger).
- **Deferred:** ALL implementation (PR-N ndarray → PR-W8a substrate →
  PR-W8b facade/migration, per spec §3.10); the GridLake/compute wave
  (hard-gated); decode modes 1..=3 (RESERVED, −14, promotion trigger
  named); Wide-tier WideFieldMask promotion (NG12); fused multi-hop
  traversal (NG10); mask reuse/pooling (NG11); the 125/233 ms
  reproduction/receipt PR (operator-measured priors, receipt
  out-of-tree — provenance split pinned in spec §8.3).
- **Docs:** the spec is self-contained; root CLAUDE.md points at the
  enforcing artifacts.
- **Confidence:** High — no code changed; every normative claim in the
  frozen text carries a council-verified file:line or an explicit
  grade/seam.

## PR #18 — consumers/graph: traversal facade + falsifiers (merged 2026-08-18, squash `5d3e694`)

- **Added:** `consumers/graph/` — the third and final planned consumer
  example. `Graph`/`Edge` (2 Sonnet workers, G1): immutable chaining over
  a plain `long[]` row-index frontier (no native `Mask` has a public
  constructor from Java-computed rows — checked, ruled a documented
  simplification, not a fourth substrate detour). `GraphHopTest` (G2):
  hop correctness via two independently-written pure-Java BFS
  transcriptions, crossings-∝-hops, anti-vacuity, zero-serialization,
  `Edge`'s reflection guard.
- **Locked:** dispatch happened only after the substrate was proven
  complete at all three levels (#14/#15 ABI, #16/#17 public facade) —
  the wave was checked three separate times before a real, load-bearing
  gap stopped surfacing.
- **A measured finding, caught and fixed before shipping:** G2's first
  draft assumed every hop costs an identical crossing count. Measured
  with a standalone 4-hop probe: hop 1 on a fresh store costs 2
  (`facetMatches` + a one-time `RowStore.rawLane()` resolution), hops
  2/3/4 cost exactly 1, steady-state — the setup cost is per-`RowStore`,
  not per-hop. Corrected `Graph.hop()`'s javadoc and the test's
  assertions to state this precisely across three consecutive hops with
  three different source-row counts.
- **Docs:** STATUS_BOARD D-LGJ-W5 (graph row, marking all three planned
  consumer examples DONE); LATEST_STATE dated entry; EPIPHANIES entry;
  wave file marked DONE — all landed in the PR's own commit.
- **Confidence:** High — `GraphHopTest` 43/43, core suite 204/204
  unaffected, trades (12+3) and bricks (62) unaffected, disable-run
  (target-decode offset corrupted by +4) verified red-then-green via the
  set-equality check. Codex hit usage limits, no review obtained.

## PR #16 — RowStore: public per-row payload accessors (merged 2026-08-18, squash `8e4f9aa`)

- **Added:** `RowStore.classidAt`/`payloadLow64At`/`payloadHi32At` — three
  new public, primitive-returning methods reusing `lgj_lane_describe`
  (already ABI minor 1, no new ABI surface), resolved once per store and
  cached; every read after is in-process.
- **Locked:** the third and final gap in the graph-consumer wave's
  substrate chain — `facetMatches` gives WHICH facets matched, never their
  payload; the only raw-byte reader (`internal.ffm.Engine.describeLane`)
  is off-limits to a consumer package by `ApiSurfaceTest`'s own design.
  Decision D1a's text assumed a capability that existed only internally.
- **A measured self-correction, recorded rather than smoothed over:** the
  first draft carried the closed-store guard in two places; disabling one
  was masked by the other via Java's receiver-before-argument evaluation
  order, producing a **false-negative disable-run (30/30 green under
  broken code)**. Traced rather than accepted, de-duplicated to the one
  correct location, re-verified genuinely red-then-green.
- **Deferred:** G1 (traversal facade) + G2 (falsifier tests) — the
  substrate is now proven at all three levels (Rust generator, ABI
  membrane, public core facade); dispatch is next.
- **Docs:** STATUS_BOARD D-LGJ-W7; LATEST_STATE dated entry; EPIPHANIES
  entry recording both the gap and the self-caught vacuous falsifier — all
  landed in the PR's own commit.
- **Confidence:** High — `AllTests` 204/204 (+10), `ApiSurfaceTest`
  unchanged at 3/3 (zero FFM type in any public signature), both
  disable-runs verified (one only after the redundant-guard correction
  above). Both bot reviewers at usage limits, no review obtained.

## PR #14 — lgj-abi: edge-bearing row store ABI addition (merged 2026-08-18, squash `be8fb60`)

- **Added:** `lgj_rowstore_open_with_edges` (`docs/abi.md` §12, ABI minor
  2→3) — mirrors `lgj_rowstore_open` symbol-for-symbol (same
  `LGJ_RESOURCE_ROWSTORE` kind, same lane shape, no new mask op, purely an
  alternative constructor). `registry::open_rowstore_with_edges` →
  `exports::lgj_rowstore_open_with_edges` → `Downcalls.rowstoreOpenWithEdges`
  → `Engine.openRowStoreWithEdges` (`Abi.requireMinor(3)`) →
  `RowStore.openWithEdges`. A Java-side transcription of the D1a hop
  mechanism (facet-match crossing + raw lane-0 payload decode, zero new ABI
  op) added to `RowStoreParityTest`.
- **Locked:** the graph-consumer wave's own D1b rule ("a new ABI symbol
  must go through the substrate wave process FIRST as its own W-tier PR")
  applied to itself — a row-store *constructor* needing ABI growth is the
  same shape of gap as a new hop op, and gets the same treatment: orchestrator
  work, never a consumer worker's ad hoc addition. Found before any G1/G2
  worker spawned, not discovered mid-dispatch.
- **A measured cross-language result, the strongest new evidence:** the
  Java hop transcription, run at the exact parameters already pinned as a
  Rust regression (`n=2000, seed=0xF00D_CAFE, edge_classid=0, gate_mask=0x0,
  radius=25`), reproduces the identical hop counts **to the row** (10-row
  seed → 19 at 1 hop → 29 at 2 hops) — proving the membrane carries the
  same edge *structure*, not merely the same classid stream. Two
  disable-runs, both red-then-green: a classid-not-threaded bug at the
  registry level (caught by the out-of-range-parity test); the Java hop's
  classid-match condition forced to always skip (1-hop/2-hop collapsed to
  0, anti-vacuity assertion caught it).
- **A real self-inflicted false alarm, recorded so it isn't re-diagnosed:**
  the stale top-level `target/release/liblgj_abi.so` (minor 2, pre-dating
  this pass) made every Java suite fail at class-init — `Downcalls` eagerly
  resolves every method handle including the new one — until rebuilt with
  `CARGO_TARGET_DIR=$ROOT/target cargo build --release` per the documented
  convention. Not a substrate defect.
- **Deferred:** the graph wave's G1 (traversal facade) + G2 (falsifier
  tests) — substrate is now proven at both the Rust generator level and the
  Java membrane level; dispatch is the next action, not yet executed.
- **Docs:** STATUS_BOARD D-LGJ-W6; LATEST_STATE dated entry; EPIPHANIES
  entry recording the gap and its fix — all landed in the PR's own commit.
- **Confidence:** High — `cargo test` 93/93, clippy/fmt clean, `nm -D`
  confirms the exported symbol; Java `AllTests` 194/194; both disable-runs
  verified. Both bot reviewers (Cursor Bugbot, Codex) hit usage limits, no
  review obtained.

## PR #12 — consumer example: Bricks, mask-first authorization (merged 2026-08-17, squash `8f16e8d`)

- **Added:** `consumers/bricks/` — the second consumer proof over the
  unchanged core. `Bricks`/`BricksSession`/`BricksQuery`/`Role`/`Orders`/
  `UnauthorizedQueryException`; `BricksAuthTest` 62/62. Wave
  `wave-consumer-bricks.md`, 2 Sonnet workers (K1 main / K2 test,
  disjoint), orchestrator-gated.
- **Locked:** authorization is a **predicate in the same lazy chain** as
  `where(...)`, composed before execution and fused into the same single
  crossing — not a post-filter. `Role.EU_ONLY` = `REGION.eq(EU)`;
  `DENY_ALL` = `REGION.eq(0xFFFF)`, a real impossible predicate that pays
  a real crossing and counts 0; fail-closed throws BEFORE any crossing
  (no default-allow path exists); aggregate-only egress is **structural**
  (`BricksQuery`/`long`/`Map` are the only public return types — asserted
  by reflection, so no row-shaped type can be added without breaking the
  test). Disable-run: `requireAuthorized` short-circuited → exactly the
  3 can-fire fail-closed checks red, 59 green; restored 62/62.
- **A measured correction, recorded rather than smoothed over:** a **sum
  terminal costs 2 crossings** (plan eval into the mask +
  `lgj_reduce_sum_i32`), unlike `count()`'s 1 — so `sumBy()` is **32
  crossings (16 groups × 2), IDENTICAL at 1K and 64K rows**. K1's Javadoc
  claimed one crossing per group; the measurement corrected the doc. The
  thesis assertion is the shape (crossings ∝ groups, never rows), which
  is why the same literal is pinned at both row counts.
- **Deferred:** a native grouped-aggregate kernel (one crossing, 16
  buckets) — named in the Javadoc as the W6-tier follow-up, deliberately
  not built because nothing has measured a need; W5c graph (shelved on
  the D1 ruling + the edge-bearing generator substrate change).
- **Docs:** STATUS_BOARD D-LGJ-W5 → bricks DONE; LATEST_STATE dispatch-4
  entry, which also owns that W5a (#11) shipped without one.
- **Confidence:** High — zero new membrane surface, zero core changes,
  core suite unaffected at 188/188; every falsifier disable-verified.
  Both bot reviewers at usage limits (no human or bot review obtained).

## PR #11 — consumer example: World/Trades (merged 2026-08-17, squash `db7bdf1`)

- **Added:** `consumers/trades/` — own compile unit, core consumed as a
  third party would. `Trade` (schema-not-entity, reflection-proven),
  `World.open` → the existing lazy `View` under domain names;
  `TradesParityTest` 12/12, `TradesAllocationTest` 3/3.
- **Locked:** the poster's number, measured — **240 bytes allocated per
  query, IDENTICAL at 64K and 1M rows** (row-count independence is the
  thesis assertion); 0 crossings composing / 1 at terminal, through the
  domain vocabulary; the membrane's own `LANE_KIND_MISMATCH` catches a
  misbound schema (disable-run green-red-green). Zero new membrane
  surface, zero core changes — the consumer iron rule held on its first
  real test.
- **Deferred:** QUANTITY (honestly absent — arrives with ClassView/W6);
  bricks + graph consumer waves (still shelved).
- **Docs:** STATUS_BOARD W5 row → trades DONE.
- **Confidence:** High — every falsifier two-sided or anti-vacuity
  guarded; no `java/` file changed. Bot reviewers at usage limits.

## PR #10 — third parity read path + R2IL handoff boundary (merged 2026-08-17, squash `4114c4e`)

- **Added:** `RowStoreParityTest` section reading all 32,000 classids of a
  1000-row store DIRECTLY from the raw lane-0 segment through
  `Layouts.ROW_LAYOUT.byteOffset(...)` — proving the LAYOUT's carving
  against the generator, and giving `ROW_LAYOUT` its first real consumer
  (it had been defined and size-checked but read by nothing). AllTests
  185 → 188.
- **Locked:** three independent routes to the same numbers (native
  kernels / generator transcription / structured segment read); the raw
  lane's own description pinned (n*512 bytes, contiguous). The R2IL
  handoff boundary recorded in `ghidra-integration-v1.md`: r2sleigh/ruff
  integration arrives from ANOTHER session — lift-candidate C and the
  r2dec direction are FROZEN here until it lands.
- **Deferred:** everything the handoff covers, deliberately.
- **Docs:** the plan's handoff section IS the record.
- **Confidence:** High — closes a gap the W3 worker itself flagged rather
  than one discovered by accident. Bot reviewers at usage limits.

## PR #9 — bench Component F: the boundary on the real layout (merged 2026-08-17, squash `b28bd34`)

- **Added:** `F_RowStoreFacetScan` + `RowStoreData` + two `Kernels`
  facet-match arms (Java mirrors of the Rust chunk algorithm,
  line-for-line traceable); `summarise.sh` F-table generator (old "E/F"
  section retitled "E" — real naming collision); `RESULTS.md` §F;
  `execution-boundary.md` re-measured paragraph; `RowStore.handle()`
  (package-private, bench bridge only); the r2sleigh plan addendum
  (lift candidate C + decompiler candidate, `libsla-sys` FFI fact pinned).
- **Locked:** the W4 finding — Component C's direction survives on the
  512-byte-row layout, its margin collapses: Vector API wins the 32-facet
  strided scan 2.51×/1.92×/1.14× (4K/65K/1M rows) vs C's 56×; all arms
  converge on memory bandwidth at 512 MiB. Cross-check-before-timing held
  at every row count. Native arm's per-call allocation asymmetry
  disclosed; `facetMatchesInto` named as follow-up.
- **Deferred:** consumer waves (operator-gated shelf); W6 (named).
- **Docs:** RESULTS/TABLES regenerated mechanically from the merged CSV.
- **Confidence:** High — real JMH, 9/9 combos, cross-checks green, main
  suite re-verified 185/185 against the fresh minor-2 `.so`. Bot
  reviewers at usage limits, did not run.

## PR #8 — Java RowStore facade: W3 shipped (merged 2026-08-17, squash `320808d`)

- **Added:** `RowStore`/`FacetMatchView`/`FacetId`/`NativeResource` public
  API; `Mask.source()` retyped `NativePattern → NativeResource`;
  `RowStoreParityTest`/`RowStoreLifetimeTest` (53 new checks).
- **Locked:** the wave-dispatch system works end to end — 3 disjoint
  Sonnet workers, zero merge conflicts, mutually consistent signatures
  with no coordination beyond the frozen briefs
  (`E-LGJ-WAVE-DISPATCH-VALIDATED-1`).
- **Deferred:** W4 (bench Component F) — the wave file's second dispatch.
- **Docs:** `STATUS_BOARD` D-LGJ-W3 DONE; `LATEST_STATE`; EPIPHANIES entry
  incl. an orchestrator-side false alarm (guessed env var name instead
  of reading `Abi.java`'s `ENV_LIBRARY` constant) recorded so it isn't
  repeated.
- **Confidence:** High — 185/185 (was 132), 0 new lint warnings, one real
  bug (`FacetMatchView.rowCount()` missing its closed-store guard) caught
  by the mandated tests and fixed before merge, both disable-runs
  red-then-green with the exact predicted blast radius. Bot reviewers at
  usage limits, did not run.

## PR #7 — waves calcified: dispatch maps for every plan (merged 2026-08-17, squash `68f7add`)

- **Added:** `.claude/waves/` — README (standing rules + verbatim worker
  preamble) + six dispatchable maps (substrate W3+W4 READY; three
  consumer waves DO-NOT-DISPATCH; Ghidra G1+G2 shelved; OGAR-Machine
  P-M1 BLOCKED behind a 4-condition gate incl. explicit operator go);
  `.claude/plans/ghidra-integration-v1.md` (G0 archaeology from the real
  clone: 74 P-code opcodes, `PcodeEmulator` as reference oracle, Toy as
  minimal lift target).
- **Locked:** the calcify-then-dispatch rhythm + the eight
  muscle-memory rules (`E-LGJ-CALCIFY-THEN-DISPATCH-1`); the op-set
  halt-loudly discipline; the D1a/D1b hop fork with ruling guidance.
- **Deferred:** ALL execution, by operator ruling — nothing dispatched,
  no code changed.
- **Docs:** the PR is docs; mapping-time catches recorded (graph
  consumer needs an edge-bearing generator arm — substrate change,
  flagged before it could burn a dispatch).
- **Confidence:** High for the maps (grounded in shipped code + real
  clone archaeology); execution confidence deliberately unclaimed until
  the waves run. Bot reviewers at usage limits, did not run.

## PR #6 — layout-bridge assessment, OGAR Machine plan, hydrate note (merged 2026-08-17, squash `8954e53`)

- **Added:** `.claude/knowledge/prior-art-and-the-layout-bridge-claim.md`
  (the first archived ChatGPT context assessed once: convergent
  confirmation; kept the callability-vs-shared-executable-layout
  positioning + the schema-key extractable + the baseline-dependent
  claims discipline; pinned its three errors so it is never cited
  naively); `.claude/plans/ogar-machine-v1.md` (the second context — a
  genuinely NEW workload: population emulation, one row = one machine
  state, Ghidra P-code as guest ISA, differential migration testing;
  EXPLORATORY, gated on W3 + a W5 example + archaeology + probe P-M1).
- **Locked:** W6 named in the substrate plan (schema/classid field on the
  descriptors when ClassView lands); lance-graph #957's
  `lance-graph-hydrate` recorded as the INHERITED hydration path — never
  re-derived here (#958 is its hardening fast-follow, owned elsewhere).
- **Deferred:** everything in ogar-machine-v1 (named, not scheduled).
- **Docs:** the PR IS docs; `AdaWorldAPI/ghidra` attached + shallow-cloned
  at `/workspace/ghidra` for the future P-code archaeology.
- **Confidence:** High for the assessments (checked against shipped code
  and measurements); the OGAR Machine plan is explicitly exploratory.
  Both bot reviewers hit usage limits and did not run.

## PR #5 — SoA row store: 512B rows, 32 facet lanes, ABI minor 2 (merged 2026-08-17, squash `78aa60e`)

Companion: **AdaWorldAPI/ndarray#279** (W1), merged first — `lgj-abi`'s
`kernels.rs` calls `iter_u32x16` / `eq_u32_strided_to_mask` from it.

- **Added:** `native/lgj-abi/src/rowstore.rs` (one `Arc<[u8]>`, two
  readings, zero copies, normative SplitMix64 generator);
  `LGJ_RESOURCE_ROWSTORE` + `lgj_rowstore_open` + `lgj_op_eq_classid` +
  `lgj_row_facet_match`; `docs/abi.md` §11; the W1–W5 wave plan and three
  consumer-example plans; `.claude/knowledge/soa-row-store-layout.md`.
- **Locked:** the 512 B / 32 × (4 B classid + 12 B payload) layout as
  substrate truth (Java's view may differ); facet lanes ride the
  **unchanged** `LgjLaneDesc`; masks parent onto row stores so the whole
  existing mask algebra applies with no new surface; `byte_len` is the
  exact covered span `(len-1)*stride + elem_bytes` (the old `len*stride`
  form would have let Java bound a segment past the allocation's end);
  ABI minor 1→2 and the §1/§7 symbol count corrected 14→18 (`nm -D`).
  Doctrine: `E-LGJ-THE-MIDDLE-TIER-IS-DELETED-NOT-WRAPPED-1`;
  self-correction: `E-LGJ-THE-FLAT-FIXTURE-WAS-SCAFFOLDING-NOT-THE-TARGET-1`.
- **Deferred:** `align(64)` base (stated honestly — arrives with real
  `NodeRow`); payload semantics (a ClassView concern one layer up); fused
  plans over facet lanes (W6, only if measurement asks).
- **Docs:** `abi.md` §11 + 4 plan files + 1 knowledge doc + full board.
- **Confidence:** High — 84/84, clippy/fmt clean, 18/18 symbols; both new
  kernels parity-checked against independent scalar references over
  10 row counts × 2 seeds × 4 facets × 4 needles and cross-checked a third
  way; two-sided payload-vs-classid falsifier. Both bot reviewers (cursor,
  codex) hit usage limits and did not run.

## PR #4 — Phase I synthesis docs + fusion re-run + board hygiene (merged 2026-08-17, squash `bd92c58`)

- **Added:** `docs/{architecture,panama,valhalla-lab,execution-boundary}.md`
  (D-LGJ-I DONE — synthesis, each claim tied to its proving artifact);
  the fusion-sweep 256-row re-run (`RESULTS.md` rewritten from
  `jmh-results-merged.csv`, `TABLES.md` mechanically generated from the
  same file); refreshed Valhalla lab result files (findings unchanged).
- **Locked:** the fusion self-correction — "fused ≈ unfused" was true
  only at 65,536 rows; at 256 rows × 8 predicates unfused/fused = 2.99×.
  The `MultiLaneColumn` decision
  (`E-LGJ-SIMD-SOA-IS-FOR-THE-ROW-STORE-NOT-THE-FLAT-LANES-1`): declined
  for flat lanes, earmarked for the 512-byte row-store slice; operator
  layout reference (64K × 512 B, 32 × (4 B classid + 12 B)) recorded.
- **Deferred:** `NodeRow`/`WideFieldMask` wiring (unchanged);
  `MultiLaneColumn` adoption gated on that slice.
- **Docs:** the four docs ARE the deliverable; board updated in-commit,
  incl. this file's #1-#3 backfill (lapse owned above).
- **Confidence:** High — docs-only + measured data; both bot reviewers
  (cursor, codex) hit usage limits and did not run.

## PR #3 — Vector API bench: real JMH, cross-checked (merged 2026-08-17, squash)

- **Added:** `bench/` — real JMH 1.37 suite (Components A/B/C/E:
  downcall overhead, segment access, execution boundary sweep 64→4.2M
  rows, fusion/planning), `Data.crossCheck()` gating every fork,
  `summarise.sh` mechanical table generator, `RESULTS.md`, raw
  run logs + CSV.
- **Locked:** the headline finding — Java Vector API zero-copy on the
  native segment beats the native crossing at every row count tested
  (56.4× → 1.33×); native beats Java *scalar* only past ~4K-16K rows.
  Recorded as `E-LGJ-VECTOR-API-BEATS-THE-CROSSING-1`.
- **Deferred:** fusion sweep ran at 65,536 rows only (repaid post-merge
  by the E_ re-run with a 256-row arm — see PR #4).
- **Docs:** `bench/README.md`, board updates.
- **Confidence:** High — 50/50 rows, 0 failures, two independent
  computations of the same CSV agree.

## PR #2 — Valhalla lab: three-truths, causal isolation, 3 reproducers (merged 2026-08-17, squash)

- **Added:** `valhalla-lab/` — shared/stable/valhalla trees, self-verifying
  `run.sh` (vocab-diff honesty gate + flattening-flag causal isolation),
  `docs/three-truths.md`, reproducers R1/R2/R3 with observed outputs.
- **Locked:** the 8-byte array-flattening cliff (R2, VM-confirmed);
  native-one-crossing beats hydration ~38-57× on BOTH JDKs; production
  API adopts zero Valhalla-only mechanisms — migration stays
  `record` → `value record`, one word per type.
- **Deferred:** nothing; the lab is complete for this vocabulary.
- **Docs:** lab README + three-truths; board updates.
- **Confidence:** High — one real defect (`Class::isValue()` not on
  JDK 26) found by compile failure and fixed before landing.

## PR #1 — Core vertical slice: ABI contract, native crate, Java facade (merged 2026-08-17, squash)

- **Added:** `docs/abi.md` (normative, 14 symbols / 4 repr(C) types /
  13 status codes / generation-checked handles); `native/lgj-abi`
  (72/72, clippy/fmt clean, 14/14 exported symbols via `nm -D`);
  `java/` facade + FFM membrane (132/132, reflection-enforced zero-FFM
  public surface); 5 new `ndarray::simd` primitives under the W1a
  contract (41/41); the `.claude/` ensemble + board.
- **Locked:** disable-verified generation check (exactly 2 tests red
  when broken, 70 green); the manifest cross-check rejects a real wrong
  `.so`; laziness measured (0 crossings to build, exactly 1 to
  evaluate); target-cpu=x86-64-v4 divergence recorded.
- **Deferred:** real graph types (`NodeRow`/`WideFieldMask`) — generic
  fixture first, by design (`docs/abi.md` §10).
- **Docs:** `docs/abi.md`, knowledge docs, board.
- **Confidence:** High — one real audit violation (`ndarray::hpc`
  import) found and fixed pre-merge; recorded in EPIPHANIES.

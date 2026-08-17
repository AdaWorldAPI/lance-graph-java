# PR Arc Inventory — per-PR Added / Locked / Deferred / Docs / Confidence
# (reverse chronological, APPEND-ONLY; only the Confidence line is
# updatable in place — corrections append as new dated lines; reversals
# get their own PR entry)

> **Hygiene lapse, owned (2026-08-17):** PRs #1-#3 merged without their
> entries landing in the same commit — the exact retroactive-hygiene
> anti-pattern the imported board rules name. Backfilled below in one
> pass rather than left stale; PR #4 onward gets its entry at merge time.

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

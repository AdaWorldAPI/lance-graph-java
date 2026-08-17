# PR Arc Inventory — per-PR Added / Locked / Deferred / Docs / Confidence
# (reverse chronological, APPEND-ONLY; only the Confidence line is
# updatable in place — corrections append as new dated lines; reversals
# get their own PR entry)

> **Hygiene lapse, owned (2026-08-17):** PRs #1-#3 merged without their
> entries landing in the same commit — the exact retroactive-hygiene
> anti-pattern the imported board rules name. Backfilled below in one
> pass rather than left stale; PR #4 onward gets its entry at merge time.

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

## 2026-08-17 (dispatch 1) — W3 shipped: the Java `RowStore` facade, from the calcified wave map

First real dispatch of the wave system: `wave-substrate-w3-w4.md` Dispatch 1
executed exactly as mapped — 3 Sonnet workers on disjoint file scopes (FFM
membrane extension / public facade / tests), Opus orchestrator integrated,
gated, and fixed centrally. Confirms the calcify-then-dispatch rhythm works
end to end, not just as a documentation exercise.

- **New public surface:** `RowStore` (`open`/`rowCount`/`isOpen`/
  `maskOfFacetClass`/`facetMatches`/`close`), `FacetMatchView`
  (`rowCount`/`matchesOf`/`cardinality`), `FacetId` (a 0..31-checked record).
  Zero `java.lang.foreign` types in any public signature — `ApiSurfaceTest`
  passed unmodified.
- **`Mask` generalized**: `source()` retypes `NativePattern → NativeResource`
  (new minimal interface), so a mask can parent onto EITHER a pattern or a
  row store with the existing algebra unchanged. Verified zero call-site
  breakage before the retype.
- **One real bug found by the suite itself and fixed**:
  `FacetMatchView.rowCount()` was missing the closed-store guard its sibling
  accessors both had — a caller could read a stale row count off a dead
  view. Caught by `RowStoreLifetimeTest`, fixed, re-verified 185/185.
- **Both disable-runs green-red-green**, confirming the version gate and the
  generator-transcription parity are load-bearing, not decorative (full
  detail on `STATUS_BOARD.md` D-LGJ-W3).
- Gate: `javac -Xlint:all` clean (7 pre-existing `[restricted]` warnings,
  0 new); `AllTests` 132→185 (+53); native `.so` unchanged this dispatch
  (Rust side untouched — pure Java consumer work).

**Next:** W4 (bench Component F, Vector API vs the crossing on the real
row-store layout) — the second half of the same wave file.

## 2026-08-17 (latest) — waves calcified, Ghidra plan grounded, NOTHING dispatched

Operator ruling: consumer plans are **calcified, not executed** — insights
locked in while hot, execution starts from momentum later. Rhythm now
explicit in `E-LGJ-CALCIFY-THEN-DISPATCH-1` (plan → wave map → shelf →
dispatch → gates → merge → arc).

- **`.claude/waves/`** created: README (standing rules + the verbatim
  worker preamble) + six dispatchable wave maps — substrate W3+W4 (the
  only one marked READY; W3 is still the next action), three consumer
  waves (DO-NOT-DISPATCH), Ghidra G1+G2 (shelved), OGAR-Machine P-M1
  (BLOCKED, 4-condition gate incl. explicit operator go).
- **`ghidra-integration-v1.md`** written from REAL archaeology against
  the fresh clone (`/workspace/ghidra`, 12.2 DEV, Java 25+): the true
  P-code op set is **74 opcodes** (not the sketch's ~13); Ghidra ships
  its own sequential `PcodeEmulator` — upgrading the OGAR-Machine oracle
  story to reference-implementation parity (the tesseract-rs method);
  `Toy` processor = the minimal lift target; `SymbolicSummaryZ3` = the
  in-tree precedent for the far-future branch-population direction.
- **Mapping-time discoveries** (the payoff of calcifying): the graph
  consumer needs a deliberate edge-bearing generator arm (fixture payload
  is PRNG noise) — a substrate change, flagged before anyone hits it
  mid-dispatch; the graph hop has a real design fork (D1a Java-side
  scatter via WRITABLE mask words / D1b native `lgj_hop`, minor 3) with
  ruling guidance recorded.

## 2026-08-17 (Slice 2) — the SoA row store is REAL: ABI minor 2, W1+W2 shipped

**The reframing that started it** (operator, three directives): the flat
three-lane fixture was always scaffolding; Java is meant to optimize the *SoA
layout*; serialization is abandoned outright in favor of lance-graph's 64K
zero-copy concurrency + the ndarray SIMD polyfill; Panama+Valhalla are "the
supraconductor over lance-graph ABI shaped SoA substrate". Doctrine on the
board as `E-LGJ-THE-MIDDLE-TIER-IS-DELETED-NOT-WRAPPED-1`; my own mis-scoped
"declined" verdict corrected in
`E-LGJ-THE-FLAT-FIXTURE-WAS-SCAFFOLDING-NOT-THE-TARGET-1`.

**Layout now in code** (operator-stated reference): 64K × **512 B rows, 32
facet lanes of 16 B = 4-byte LE classid + 12-byte payload**, the lance-graph
V3 content-blind facet. Java's own view may differ — these bytes are the
substrate truth. Full statement: `.claude/knowledge/soa-row-store-layout.md`.

- **W1 (ndarray PR #279, open):** `MultiLaneColumn::iter_u32x16`/`len_u32x16`
  (the u32 lane whose absence was the real blocker) + `eq_u32_strided_to_mask`
  (the AoS-facet classid scan, overflow-checked bounds). `simd_int_ops` 46/46,
  `simd_soa` 15/15, `simd` 263/263, clippy/fmt clean, both x86 arms.
- **W2 (this repo):** `rowstore.rs` + `LGJ_RESOURCE_ROWSTORE` +
  `lgj_rowstore_open` + `lgj_op_eq_classid` + `lgj_row_facet_match`; facet
  lanes ride the **unchanged** `LgjLaneDesc` (`stride_bytes` has carried this
  since minor 1). ABI **minor 1→2**, `docs/abi.md` §11 written, and the §1/§7
  "14 symbols" count corrected (its own list already enumerated 15; the real
  number is now 18, verified by `nm -D`). `cargo test` **84/84**, clippy
  `-D warnings` + fmt clean.
- **Parity, three independent ways:** each SIMD kernel vs its independent
  scalar reference over 10 row counts × 2 seeds × 4 facets × 4 needles, then
  both cross-checked against `RowStore::classid_at`. Two-sided falsifier proves
  payload bytes carrying the needle's bit pattern never satisfy a classid
  match, and that a real classid match does fire.
- **`byte_len` semantics tightened** to the exact covered span
  `(len-1)*stride + elem_bytes` — a full-stride final window would have let
  Java bound a segment past the allocation's end on a facet lane. Contiguous
  lanes unchanged.
- **Masks parent onto row stores**, so the entire existing mask algebra applies
  with no new surface (proven end-to-end through the membrane).

**Planned and written this session:** `.claude/plans/lgj-soa-substrate-v1.md`
(the W1–W5 wave plan) plus one plan per consumer example —
`consumer-world-trades-v1.md` (zero-object fluent domain API),
`consumer-bricks-analytics-v1.md` (mask-first RBAC, fail-closed, aggregates
only), `consumer-graph-traversal-v1.md` (traversal as facet addressing,
crossings ∝ hops). Iron rule in all three: **a consumer example never grows the
membrane** — a needed symbol goes back through the wave process.

**Next:** W3, the Java `RowStore` facade (structured `MemoryLayout`,
minor-≥2 gate, `FacetMatchView`, generator-transcribing parity test).

## 2026-08-17 (later) — Phase I docs written, fusion re-run merged, simd_soa question answered (PR #4)

- **All four synthesis docs shipped** (`docs/architecture.md`,
  `docs/panama.md`, `docs/valhalla-lab.md`, `docs/execution-boundary.md`)
  — D-LGJ-I DONE. Each cites the proving artifact instead of restating it.
- **Fusion sweep re-run with a 256-row arm** (`./run.sh E_`): the first
  pass's "fusion does nothing" (true at 65,536 rows, where kernel time
  dominates) is false at small rows — unfused/fused grows 0.95× → 2.99×
  at 256 rows × 8 predicates, because per-crossing overhead dominates
  there. `RESULTS.md` rewritten from `jmh-results-merged.csv` (A/B/C from
  the full sweep + E from the re-run), `TABLES.md` mechanically generated
  from the same file. Valhalla lab result files refreshed by a same-box
  re-run; findings unchanged.
- **`MultiLaneColumn` question answered** (operator: "if you use SoA,
  calling simd_soa.rs would make sense"): declined for the flat-lane
  fixture (64-byte-multiple constraint + no u32 lane — two concrete API
  mismatches), earmarked for the 512-byte row-store slice where it fits
  by construction. Operator layout reference recorded: 64K × 512 B rows,
  32 lanes × (4 B classid + 12 B), enforced everywhere in lance-graph;
  Java-side layout may differ. See
  `E-LGJ-SIMD-SOA-IS-FOR-THE-ROW-STORE-NOT-THE-FLAT-LANES-1`.
- **PR_ARC_INVENTORY backfilled** for merged PRs #1-#3 (hygiene lapse
  owned in the file itself).

## 2026-08-17 — D-LGJ-AUDIT complete, core vertical slice VERIFIED GREEN, PR #1 opened

### Current Contract Inventory — the vertical slice is real and green

- **`D-LGJ-AUDIT` ran.** Mechanical grep sweep against `no-c-ever.md` and
  `simd-provenance.md` found exactly **one** real violation:
  `native/lgj-abi/src/kernels.rs::simd_popcount` called
  `ndarray::hpc::bitwise::popcount_batch_u64` directly instead of the
  sanctioned `ndarray::simd::popcount_batch_u64` re-export. Fixed in place
  (same function, same behavior, corrected import path + doc comment).
  Everything else the grep matched (`abi.rs`'s one sanctioned
  `target_feature` cfg block for manifest self-reporting; every
  `cbindgen`/`jextract` hit in doc comments, README, and test assertion
  strings) was confirmed to be exactly what it should be — prose
  explaining the rule, or the one deliberate exception the rule itself
  names. `kernels.rs` confirmed the sole `ndarray`-importing file.
- **Rust (`native/lgj-abi`) — orchestrator-run, centrally, per
  `agent-cargo-hygiene.md`:** `cargo test` → **72/72 passed**. `cargo
  clippy --all-targets -- -D warnings` → clean. `cargo fmt --check` →
  clean. `cargo build --release` → `liblgj_abi.so`, **exactly the 14
  symbols** `docs/abi.md` §7 specifies, verified via `nm -D`.
- **Disable-verified, not just green** — the registry's core safety
  check (`registry.rs::resolve`'s `slot.generation != gen` comparison)
  was deliberately short-circuited to `if false && ...` and the suite
  re-run: exactly the two tests that should catch it
  (`a_reused_slot_invalidates_the_old_handle`,
  `fabricated_handles_are_rejected_not_dereferenced`) went **red**, all
  70 others stayed green. Restored, re-verified 72/72. This is the
  `handle-lifecycle-auditor` discipline actually applied, not merely
  read from the design doc.
- **Java (`java/`) — orchestrator-compiled and run against the real
  `.so`, JDK 26 GA:** `javac -Xlint:all` → 7 `[restricted]` warnings, all
  in `internal/ffm/*` or a test deliberately exercising the restricted
  API — the exact set the design predicts, nothing outside it.
  `AllTests` → **132/132 checks passed, 0 failed**, across
  `ApiSurfaceTest` (reflection-enforced: zero FFM types in any public
  signature), `AbiContractTest` (manifest cross-check genuinely rejects
  a wrong library), `SmokeTest`, `FixtureParityTest` (30 checks, Java
  independently recomputes expected counts from the transcribed
  SplitMix64 generator), `FusionParityTest` (fused/unfused/scalar agree
  bit-for-bit across 6 row-count shapes incl. 1/63/64/65),
  `LazinessTest` (empirically proves: building a 16-condition chain
  costs 0 crossings; a terminal op costs exactly 1, independent of row
  count up to 1,000,000 — the thesis's central claim, measured, not
  asserted), `NarrowingTest`, `LifetimeTest` (23 checks: use-after-close,
  double-close, child-outlives-parent, parent-outlives-child, all as
  clean exceptions, never a crash).
- **`.gitignore` added** (target dirs, `.class`, downloaded jars/tarballs)
  before this commit — `bench/lib/*.jar` (real JMH, fetched by the Lab
  agent) is excluded from version control by design.

### PR #1 scope — the core slice, Lab phase deliberately deferred

`claude/lance-graph-java-panama-valhalla-sus9w8` → `main`. Ships: the
frozen `docs/abi.md` contract, the `.claude/` ensemble+board, the 5
`ndarray::simd` primitives, `native/lgj-abi`, and `java/`
(D-LGJ-A/ABI/ENS/B/C/D/E). **Does NOT ship** `valhalla-lab/`/`bench/`
(D-LGJ-F/G) — still in flight at commit time (JMH jars fetched, no
source yet) — nor Phase I docs, which are sequenced to synthesize the
Lab results. Both are tracked as open `STATUS_BOARD.md` rows, not
silently dropped. Given the core slice is independently complete,
fully falsified, and green, shipping it now rather than blocking on a
slower background phase is the honest call — the alternative is
holding fully-verified, working code in an uncommitted working tree
for no safety reason.

### Toolchains pinned this session (superseded lines below kept for
### history; nothing here changed)

---

## 2026-08-17 — session 1: contract frozen, ensemble seeded, vertical-slice fan-out dispatched

### Current Contract Inventory — 1 new normative doc, 0 shipped Rust/Java code yet (in flight)

- **`docs/abi.md`** — the normative Rust↔Java ABI contract. 14 `extern "C"`
  symbols (`lgj_abi_manifest`, `lgj_pattern_open`, `lgj_close`,
  `lgj_resource_info`, `lgj_lane_describe`, `lgj_mask_create/describe/and/or/count`,
  `lgj_op_eq_u32`, `lgj_op_gt_i32`, `lgj_plan_eval`, `lgj_plan_eval_scalar`,
  `lgj_reduce_sum_i32`), 4 `#[repr(C)]` types (`LgjLaneDesc` 56B,
  `LgjResourceInfo` 32B, `LgjOpDesc` 24B, `LgjAbiManifest`), 13 status
  codes, a generation-checked `u64` handle (`generation:u32 << 32 | index:u32`).
  Written BEFORE either implementation side, so Rust and Java are being built
  independently against it — no side has authority to redefine it unilaterally
  without a doc update in the same PR (per `abi-membrane-warden`'s doctrine).
- **No C anywhere, by design, not by oversight.** `extern "C"` = SysV AMD64
  psABI, not the C language. No `.h`, no `cbindgen`, no `jextract`, no JNI.
  See `.claude/knowledge/no-c-ever.md`.
- **`.claude/agents/` (6 cards) + `.claude/knowledge/` (6 docs) + this
  board.** Sized to the repo's actual seams, not padded to lance-graph's
  26-repo scale — see `.claude/agents/BOOT.md`.

### Toolchains pinned this session (verified by direct execution, not assumed)

- **Rust: 1.97.1 stable**, installed to match `ndarray`/`lance-graph`'s pin
  (operator-directed switch from an earlier draft's 1.94 target).
- **Production JDK: `/opt/jdks/jdk-26.0.2`** (GA, downloaded this session).
  FFM (`java.lang.foreign`) is FINAL here — no `--enable-preview`, only
  `--enable-native-access`. Confirmed live: `Arena.ofConfined()` +
  `MemorySegment` set/get + `Linker.nativeLinker()` → `SysVx64Linker`, zero
  flags beyond native-access.
- **Valhalla lab JDK: `/opt/jdks/jdk-27`** (`27-jep401ea3+1-1`, the OFFICIAL
  JEP 401 early-access binary from `jdk.java.net/valhalla/` — not a source
  build). Confirmed live: `value class`/`value record` compile and run with
  `--enable-preview --release 27`; `Class.isValue()` → `true`.
- **The three local OpenJDK source forks
  (`/home/user/jdk`, `/home/user/valhalla`, `/home/user/panama-foreign`)
  are NOT used for building anything** — an archaeology pass found Valhalla's
  `lworld` fork measurably BEHIND mainline `/home/user/jdk` for value-class
  purposes (its own last commit: "things to delete from lworld just before
  integrating JEP-401"), and `panama-foreign`'s `java.lang.foreign` is
  byte-identical to mainline. See `.claude/knowledge/jdk-toolchain-facts.md`.
- Host CPU has AVX-512 (`avx512f/bw/cd/dq/ifma/vbmi/vl`) — both
  `ndarray::simd`'s AVX2 (v3, default build baseline) and AVX-512 (v4) tiers
  are exercisable in this environment.

### Active branches

Single branch: `claude/lance-graph-java-panama-valhalla-sus9w8` across all
13 in-scope repos per the mission's cross-repo branch instructions. Nothing
committed yet in `lance-graph-java` — this entry describes working-tree
state, not a merged PR (there is no PR_ARC_INVENTORY entry yet; see that
file for why).

### In flight (dispatched, not yet landed — do not cite as done)

A 4-agent fan-out (`lgj-vertical-slice-wf_23ad2110-b1e`) is running:
1. Adds `eq_u32_to_mask`/`gt_i32_to_mask`/`mask_and`/`mask_or`
   (`_assign` variants)/`masked_sum_i32` to `AdaWorldAPI/ndarray` under its
   own W1a consumer contract, re-exported via `ndarray::simd`.
2. Builds `native/lgj-abi` (the Rust ABI crate) against `docs/abi.md`.
3. Builds `java/` (FFM membrane + public facade) against `docs/abi.md`.
4. (sequenced after 3) Builds `valhalla-lab/` + `bench/` against the real
   Java types from step 3.

**None of steps 1–4 has been reviewed yet.** In particular the
`ndarray::hpc` import ban (`.claude/knowledge/simd-provenance.md`) and the
no-C rule have NOT been mechanically audited against the agents' actual
output — that audit is the very next action once the workflow completes.
Treat `native/lgj-abi/{Cargo.toml,Cargo.lock,rust-toolchain.toml,src/}`
existing on disk as "in progress," not "shipped."

### Queued work (not yet dispatched)

Phase H (falsification-test review by `handle-lifecycle-auditor`), Phase I
(docs: `architecture.md`/`panama.md`/`valhalla-lab.md`/`execution-boundary.md`),
and the post-fan-out mechanical audit for `ndarray::hpc`/C-artifact
violations across all four agents' output.

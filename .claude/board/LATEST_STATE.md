## 2026-08-27 — the simd.rs isomorphism pinned as the ENFORCEMENT LAYER; J2 closed

- **Doctrine pinned** (root `CLAUDE.md`, rules E1–E6; board entry
  `E-JAVA-IS-SIMD-RS-VALHALLA-PANAMA-IS-THE-POLYFILL-1`): Java ↔ `simd.rs`
  (facade, vocabulary only), Valhalla+Panama ↔ the cfg-dispatch polyfill,
  Rust ↔ `simd_{arch}.rs` (all machinery). Grounded by measurement, not
  analogy: 37 facade functions / 0 shipping instructions vs 488 intrinsics
  in one backend; `simd_scalar` a backend BELOW the facade; facade
  intrinsics only under `#[cfg(test)]` as oracles.
- **J2 closed under E3.** `RowStore`'s hand-written `ROW_BYTES = 512` /
  `FACET_BYTES = 16` and the literal `+ 4` / `+ 12` payload offsets are
  gone; `internal/ffm/Layouts` now DERIVES `ROW_BYTES` / `FACET_BYTES` /
  `FACET_PAYLOAD_OFFSET` / `FACET_PAYLOAD_HI32_OFFSET` from
  `ROW_LAYOUT`/`ROW_FACET` (`byteOffset(groupElement("payload"))`, the
  u64's own `byteSize()` — no literal survives), and the facade names them.
  One source, proven by the existing `SELF_CHECK`; the enforcement is the
  DELETION of the second spelling, not a tautological test.
- Gates: Java 304 core unchanged; consumer suites unchanged (no signature
  moved); Rust untouched by this commit beyond none.

## 2026-08-27 — ABI minor 9: the reduction moved to where the data is, and the placement rule is now ABI

Operator ruling, verbatim intent: *"java hands decorative where() through
Panama; Rust is doing mask ops, ONLY"* — and, on the first fix attempt,
*"java doesn't even know mask count."* Both corrections were needed, because
the violation survived one layer up from where it was first repaired.

- **The violation, three shapes of it.** `FacetMatchView.cardinality()` (1)
  popcounted a fetched segment in a Java loop, doc-commented "deliberately
  Java-side" to save a crossing; (2) after the first correction, composed 32
  per-facet `maskOfFacetClass(...).count()` calls and summed in Java — every
  OPERATION native, but the DECOMPOSITION (32 facets, a sum) still executing
  in Java. Java knowing "32" is Java holding a moving part. (3) The fix I
  first PROPOSED — "add a popcount symbol over the buffer" — was the same
  disease: asking how to reduce a buffer Java should never hold.
- **Minor 9, one symbol:** `lgj_rowstore_facet_match_count(res, needle,
  out_count)` — Σ_f popcount(class_f), computed natively with the same
  strided-equality mask the classid ops use plus the sanctioned popcount. One
  crossing, one u64 back; Java does not learn that the answer has parts.
  `cardinality()` is now a single delegation. Falsifier runs the count against
  TWO independent oracles (the `lgj_row_facet_match` buffer popcount — the
  very reduction Java used to do — and a scalar recompute), plus absent-needle
  zero and null-out rejection. 135/135 both feature configs.
- **Both gate directions proven against a REAL minor-8 library** (built from
  `main` @ c6127c5 in a worktree): `cardinality` throws `AbiMismatchException`
  naming minor 9 — never a bare missing symbol, never a silent fallback to a
  Java-side loop. 8/8 compat checks. (Worktree lesson: path deps resolve
  relative to the worktree, so it must sit beside the sibling repos, not in
  /tmp.)
- **The stale-`.so` iron rule fired for real, and caught MY OWN gap.** The
  root-invoked `cargo build --release --manifest-path ...` was silently
  REFUSED (repo root resolves the default toolchain, below the 1.97 floor;
  the MSRV error was hidden by tail-piping) — so the R1 Java runs earlier
  today loaded a PRE-R1 `.so`. Harmless there only because R1 changes no
  observable behaviour; the minor-9 `requireMinor` gate is what surfaced it,
  exactly as #26/#27 designed. Correct build: from inside `native/lgj-abi`
  (pinned toolchain) with `CARGO_TARGET_DIR` pointed at the root target Java
  loads.
- **docs/abi.md**: 25 symbols; minor-9 history entry stating the placement
  rule as ABI, not preference.

## 2026-08-27 — R1: the hop's selection is mask algebra again, and the layout is now the measured blocker

Operator ruling: *"there's no gathering — gathering is a serialization of what
is already there to begin with."* Correct, and the audit that followed found
the walk was not the only place the algebra had leaked.

- **R1 shipped, byte-identical.** `lgj_hop` selects with
  `src ∧ class_f ∧ struct_f`, word-parallel. **134/134** including the pinned
  10/19/29 regression — which is the proof the answer did not move — and
  447/447 Java unchanged.
- **F2, which no PR in the arc had caught:** `payload_hi32 != 0` was an `if`
  inside the row walk in EVERY version, PR #22's clean one included. It is a
  per-row equality against zero, i.e. the same strided primitive as the classid
  match, twelve bytes further into the facet. Closed for **one call site and
  zero new kernels** — `simd_rowstore_u32_eq_mask` takes an arbitrary offset,
  so `first_offset = f*16 + 0` is the class and `f*16 + 12` is the gate.
- **`facet_bits` / `facet_cache` / `FACET_CACHE_SLOTS` deleted.** Under the
  operator's format-string reading of the 4+12 facet (`classid -F payload`,
  PowerShell `"{0} {1}" -F $1,$2`) the memo was caching the interpolated
  string. The projection is applied at read, never stored.
- **R1 alone is a 19× REGRESSION, and that is the finding.** 65 536 rows:
  one-pass sweep 2 126 µs → mask algebra **40 632 µs**, flat in density. 32
  facets × 2 predicates = **64 full passes at stride 512** ≈ 2 GB of traffic to
  read 512 KB. At 1 024 rows it is 144 µs; 64× the rows costs 282× the time —
  cache and TLB collapsing together. The algebra is right; the LAYOUT is the
  defect, exactly as R11 (#31) priced it at 9.2× before this arc began.
- **R2 measured as a lab arm (R11 precedent, zero ABI change).** The canvas is
  the **(row × facet) plane**, not the row: same 512 bytes reordered
  field-major, so `class` and `struct` are ONE contiguous pass each with no
  stride, participation is a PERIODIC operand (64 slots per word = exactly 2
  rows × 32 facets, so it is one repeated `u64`, not a buffer), and `src`
  expands 1 row-bit → 32 slot-bits by splat.

| 65 536 rows | 0.01 % | 1 % | 25 % | 100 % |
|---|---|---|---|---|
| sweep (shipped pre-R1) | 5 147 | 3 034 | 2 969 | 5 252 |
| gather (#40, the serialization) | 1.0 | 27.7 | 1 659 | 3 880 |
| mask algebra, AoS (R1) | 41 600 | 49 667 | 48 604 | 51 329 |
| **columnar plane (R2 probe)** | **902** | **1 066** | **1 367** | **2 271** |

  Columnar is **~40×** the AoS mask shape, **2.3–5.7×** the one-pass sweep, and
  beats the gather outright at 100 %. Its cost tracks the CANVAS, not the
  frontier — 2.5× across a 10 000× density range — which is the signature the
  mask-native invariant asks for. Equivalence asserted at all 12 configurations
  per population: all four shapes byte-identical. Raw output banked at
  `.claude/board/hop-mask-algebra-vs-columnar.txt`.
- **Honest boundary:** at a sparse frontier the gather is still faster in
  absolute terms, because any whole-plane operation is O(population) and a walk
  is O(frontier). That is not a defect to fix — it is the trade the doctrine
  makes deliberately, and it is why the columnar number (flat in density)
  matters more than the sparse-density comparison.
- **Not done:** the columnar store itself. The probe builds the plane from the
  AoS store; a columnar STORE builds it at generation. That is the ABI-side
  change and it is measured-but-unlanded.

## 2026-08-27 — the REAL ClassView provider is bound, and it measures the fixture's reach

The `ClassView` provider seam (§4-NG3, "a real ontology/cache provider is a
NAMED SEAM") is no longer only named. `ogar_class_view::OgarClassView` — the
ontology-backed provider over `ogar_vocab` — is bound behind a new
`ogar-classview` feature on `native/lgj-abi`, and `edge_participation` derives
from each class's real field basis instead of the fixture's constant.

- **The provider discriminates, measured.** `examples/classview_census.rs`:
  **98 registered classes, 12 distinct participation masks** (field counts
  0–13), against the fixture's single `0xFFFF_FFFF` for all 98. An
  unregistered classid participates in **nothing** — an unknown class is not
  a licence to traverse every facet.
- **The `[patch]` was load-bearing, not cosmetic.** `ogar-class-view` pulls
  `lance-graph-contract` by git branch; this crate pulls it by path, and
  cargo does not unify a git SourceId with a path SourceId — without the
  patch the build carries two `lance-graph-contract` crates and therefore
  two incompatible `ClassView` traits. Verified: `cargo tree` shows one.
- **What binding it EXPOSED, and this is the finding.** The generated row
  store draws classids from `0..16` (`ROWSTORE_CLASS_CARDINALITY`); every
  vocabulary classid is `>= 0x0100`. The two domains are **disjoint**, so a
  generated store under the real provider hops nothing. The remaining
  fixture is the row CONTENT — Lance-loaded SoA rows are what make the bound
  provider observable end-to-end. Pinned by
  `hop_under_the_real_provider_narrows_by_class`, not left in prose.
- **Default is unchanged and proven so.** Feature OFF: 134/134 rust, 447/447
  Java (304 core + 143 consumer) — the same numbers as before. Feature ON:
  136/136. Two fixture-semantics tests are gated OFF under the feature and
  each has a paired ON twin asserting the CONTRASTING fact, so nothing was
  merely disabled. Five tests red-then-green under the disable
  (`edge_participation`'s ogar arm returns `FULL`). G11 fence green:
  `class_view`, `canonical_node`, `ontology`, `facet` only.

## 2026-08-25 — the Ghidra end of the R2IL arc: seam verified, vocabulary measured

Working the `r2il-machine-semantic-contract-v1` plan (lance-graph, PR #1027,
merged) from the GHIDRA side while a sibling session drives W0-W4.

- **The seam is an interface.** `Language.parse` -> `InstructionPrototype`
  -> `getPcode()`, both interfaces, `InstructionPrototype` with exactly two
  implementations and ONE construction site
  (`SleighLanguage.java:392`). **No Ghidra core fork required** — this was
  an open unknown gating the whole Java half.
- **R12** (`valhalla-lab/reproducers/`): Ghidra's P-code payloads do NOT
  flatten even at their optimistic lower bound (16 B / 12 B, references
  deleted); ordinals do, at VM element size 8. A real 2-input `PcodeOp` is
  **five heap objects**. Verdict: the W5 facade ADDRESSES the vocabulary
  rather than carrying it — which needs nothing new, it is what
  `LaneId`/`Ordinal`/`MaskId` already do.
- **Unanticipated:** an 8-byte `VarnodeNarrow` (u8 space, u8 size, 48-bit
  offset) also flattens — so a content-bearing descriptor is possible, not
  only a pointer. Recorded as an option for W1, deliberately not proposed
  as the design.
- Nothing swapped, nothing minted, no layout touched. Measurement + trace
  only.

## 2026-08-25 — ABI minor 8: the register groupings are DATA, and the load gate stopped requiring the whole manifest

- **The wire encoding of §14's `carving` is no longer written anywhere by
  hand.** The contract owns the set (`CascadeShape::ROTATIONS`),
  `kernels::CARVING_ORDER` (a `const`) derives the order by a RULE (group
  count, descending — never declaration position), and the manifest serves
  it in two new fields: `carving_count: u32` + `carvings: [u16; 8]`
  (`(groups << 8) | group_bytes`). No new symbol; the manifest already
  exists so Java can discover the ABI's shape rather than declare it.
  `LgjAbiManifest` is now 128 bytes.
- **Java keeps its ARITY and loses its ENCODING.** `Carving.groups()` /
  `groupBytes()` stay declared — the arity IS the constant's identity —
  while `wire()`/`ofWire()` look up the served table. `CarvingTable` holds
  the one clearly-named pre-minor-8 compatibility shim, so exactly one
  place in the build carries a literal encoding and its name says it is
  history.
- **A latent defect fixed on the way:** Java's load gate required the FULL
  manifest layout, so the FIRST growth of that struct — this one — would
  have made every older artifact fail to load, contradicting §2's additive
  promise. The gate now requires only the 104-byte BASE PREFIX
  (`Layouts.MANIFEST_BASE_BYTES`); later fields are read only when
  `size_of_manifest` covers them AND the minor is high enough.
- **Gates:** Rust 134 lib tests, fmt + clippy `-D warnings` clean; Java
  **304** checks (`AllTests`, was 288 — `CarvingTableTest` adds 16);
  `OldAbiCompatTest` green against all four historical `.so`s (minors
  1-4). Four disable-runs, each red-then-green: swapped packed axes,
  reversed sort, a mismatched Java arity (fires BOTH membership
  directions), and the restored full-layout gate (minor-4 library fails to
  load).
- **Docs:** `docs/abi.md` §17 (new), §2 (load-gate prefix), §14's table
  regraded DESCRIPTIVE rather than normative.
- Stacked on PR #30 (minor 7); board entry in `PR_ARC_INVENTORY.md`,
  finding in `EPIPHANIES.md`
  (`E-LGJ-A-CONSTANT-COPIED-THREE-TIMES-HAS-NO-FALSIFIER-1`).

## 2026-08-18 — PR-W8b (FACADE + GRAPH MIGRATION) — the mask-native correction reaches the Java surface

### Current surface changes (java/ + consumers/graph)

- NEW public: `WideFieldMask` (record; `allFacets()`/`ofFacets(int...)`
  validated/`ofMatchBits(int)` zero-extending; `EMPTY`), `RowStore.hop(int,
  WideFieldMask, Mask)` + `hop(int, Mask)`, `RowStore.importRows(long...)`
  (the ONE named import exception), `Mask.minus(Mask)`,
  `Mask.materializeRows()` (the ONE named materialiser), `Status
  UNSUPPORTED_DECODE_MODE(−14)`. `Graph` migrated: native `Mask` frontier,
  `from(Mask)` new, `minus(long...)` REMOVED, `rows()` renamed
  `materializeRows()`, real `close()`. Zero FFM types in any public
  signature (ApiSurfaceTest inside the green AllTests run).
- Crossing constants MEASURED-THEN-PINNED (ABI 0.4, release .so, JDK
  26.0.2): hop = 2 (createMask + lgj_hop), importRows = 2 (createMask +
  describeMask; per-row word writes in-process — 3-vs-29-row cost
  identical), count = 1, minus = 2, materialize-first = 1. Two predictions
  were wrong in exactly the direction the worker's own brief flagged; three
  stale "one native crossing" javadoc claims corrected at the source
  (maskOfFacetClass / hop / importRows).
- Gates: `.so` rebuilt FIRST (the root-level copy was STALE from 11:54 —
  the eager-clinit trap, caught before any suite ran); `javac -Xlint:all`
  0 new warnings; AllTests 245 + GraphHopTest 66 + TradesParity 12 +
  TradesAllocation 3 + BricksAuth 62 = 388 checks; reflective-allowlist
  disable-run red-then-green (injected `long[] rows()` fired exactly G1/G8).
- Docs: wave-consumer-graph.md + consumer-graph-traversal-v1.md carry
  dated supersession notes (spec §3.7); original text preserved.

## 2026-08-18 — D-LGJ-W8 SUBSTRATE landed: ABI minor 4 (lgj_mask_andnot + lgj_hop), contract dep live, 21 symbols

PR-N (ndarray #280) then PR-W8a, per the spec's merge order. The lgj-abi
crate now depends on `lance-graph-contract` (path, default-features
off — the RULING's one-closure contract inheritance, engine never), with
`FixtureClassView` as the late-bound law provider and `class_id_for` as
the pinned u32→u16 boundary (ISSUES.md ISS-LGJ-CLASSID-WIDTH-PIN).
`lgj_mask_andnot` implements the dedup-before-lock aliasing discipline
with a 5-branch tree (ANDNOT is non-commutative: dst==b needs a scratch
copy of a — a real structural divergence from mask_binop, found during
implementation, doc'd in place); `lgj_hop` is the composition kernel
(existing eq_u32_strided_to_mask for classid-match, scalar decode+
scatter, u64 bounds-check BEFORE cast, snapshot-then-write so dst==src
aliasing is deadlock-free by construction). Gates: 110/110; clippy -D
warnings + fmt; release .so exports 21/21; disable-runs G6(a)-(e) all
red-then-green — (e) proved the deadlock is REAL (60s timeout kill with
the ptr_eq dedup bypassed), the council's S3-4 catch now mechanically
demonstrated. abi.md: §13 added, §12's Java-layer hop composition
marked ⊘ SUPERSEDED (append-only regrade). W8b (Java facade + Graph
migration) dispatched in parallel. Separately: the LOTUS SEAL / FRACTAL
COMMIT FRONTIER research charter opened for lance-graph (operator,
2026-08-18) — research runs there, not here.

## 2026-08-18 — D-LGJ-W8 A3 FREEZE: the mask-native correction is ruled, specced, council-ratified; root CLAUDE.md created (PR-0)

The operator's CORRECTION WAVE + RULING CLARIFICATION + A1 ARCHITECTURE
RULING landed and went through the full supervision ladder: A0 six-lens
audit (drift CONFIRMED — `Graph`'s `long[]`/`TreeSet` currency is
precedent-drift; `View.where` exonerated; trades/bricks clean), A1
combined spec (mask correction + the 64K parallel-SoA compute
architecture, grounded in a 44-finding savant pass over the spec and a
37-finding audit of the lance-graph spine), operator A2 verdict, three
brutal reviewers (1 P0 — model identifiers in the to-be-committed spec
— resolved by de-naming to roles; 6 P1 applied, including the §9
axis-split at the arrival-order leak and the G2/G11 gate repairs),
v3 RATIFIED. This commit freezes: spec v3 + root `CLAUDE.md` (the
mask-native policy guard — first root CLAUDE.md this repo has) +
`E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1` storno + the
D-LGJ-W8 STATUS_BOARD row.

**Correction of record for the entry below:** Graph #18 remains a VALID
correctness fixture and becomes the preserved scalar oracle; its
`long[]`/`TreeSet` implementation is scaffolding pending D-LGJ-W8's
migration (Mask frontier, contract-governed ClassView/WideFieldMask,
`lgj_hop`/`lgj_mask_andnot` at ABI minor 4). Trades/Bricks remain valid
— their execution is already lazy/fused/mask-oriented. Implementation
order: ndarray PR-N first (mask_andnot + simd.rs re-export), then
PR-W8a (contract dep + provider + 2 ABI symbols), then PR-W8b (Java
facade + Graph migration), each with same-commit board artifacts per
the spec's §3.10 gate columns. The GridLake/compute surface is
specified, NOT implemented, and hard-gated on arrival-order-independent
landing identity.

## 2026-08-18 (final for now) — graph consumer wave landed: 2 workers, 43/43, one real crossing-cost finding caught before shipping

Dispatched G1 (`Graph`/`Edge`, the traversal facade) and G2 (`GraphHopTest`,
the falsifiers) in parallel per `wave-consumer-graph.md`, only after the
substrate was proven complete at all three levels in the two entries below
(generator, ABI membrane, public core facade). Both landed clean: `Edge` is
a proper schema-not-entity (reflection-proof), `Graph` is immutable
chaining over a plain `long[]` row-index frontier (a native `Mask` has no
public constructor from Java-computed rows — checked, ruled as a
documented simplification rather than a fourth substrate detour), one
`facetMatches` crossing per hop with everything else Java-heap.

Compiled and ran centrally: `GraphHopTest` 43/43, core suite 204/204
unaffected, trades/bricks unaffected. Mandated disable-run (corrupt the
target-decode offset by +4) went red exactly as required — the SET
equality check caught it even though the row COUNT coincidentally still
matched, which is exactly why G2 was told to check the set, not just its
size.

**One real finding, caught before it shipped wrong:** G2's crossings test
assumed every hop costs an identical number of native crossings. Measured
directly (a 4-hop probe before touching the shipped test): hop 1 on a
fresh store costs 2 — the `facetMatches` crossing plus a one-time
`RowStore.rawLane()` resolution that the first payload read anywhere on
that store triggers — while hop 2, 3, 4 each cost exactly 1, steady-state.
Corrected both `Graph.hop()`'s javadoc and the test's assertions to state
this precisely (first-hop=2, steady-state=1, confirmed across 3
consecutive hops with 3 different source-row counts) rather than leave
the wrong "identical from hop 1" assumption in either place. Full record:
`STATUS_BOARD.md` D-LGJ-W5 (graph row), `EPIPHANIES.md`.

All three planned consumer examples (trades, bricks, graph) from
`lgj-vertical-slice-v1`/`lgj-soa-substrate-v1` are now DONE.

## 2026-08-18 (even later still) — a SECOND gap, one layer up: the graph wave also had no public path to a payload

Immediately after PR #14/#15 merged, worked through concretely how G1's
`Graph.hop()` would actually decode a matched facet's target row. Answer:
it couldn't. `facetMatches` gives a per-row 32-bit BITSET of which facets
matched — never the payload bytes. The only thing that ever read raw row
bytes was `internal.ffm.Engine.describeLane`, off-limits to a consumer
package (`ApiSurfaceTest` forbids `internal.*`/`MemorySegment` in any
public signature by construction). D1a's own text ("read matched facets'
payloads via the raw lane 0 segment") assumed a capability that existed
internally but had never been surfaced publicly — a second version of the
exact gap PR #14 closed, one layer higher.

Fixed with zero new ABI surface: `RowStore.classidAt`/`payloadLow64At`/
`payloadHi32At` reuse `lgj_lane_describe` (already ABI minor 1, already a
"lifecycle" crossing per abi.md §6) — resolved once, cached, every
subsequent read is in-process. `AllTests` 204/204 (+10 over PR #14's 194).

**A redundancy I introduced and caught myself, worth recording as a
process note:** the first draft guarded the closed-store check in TWO
places; disabling one was silently masked by the other, and the
disable-run came back green under genuinely broken code — a false
negative I could have accepted and moved on. Traced it to Java's
receiver-before-argument evaluation order, de-duplicated to the one
correct location, and only THEN did the same disable-run go properly red.
Full record: `STATUS_BOARD.md` D-LGJ-W7, `EPIPHANIES.md`.

The graph-consumer wave is now dispatchable for real, proven at three
independent levels rather than one: the Rust generator, the ABI membrane,
and the public core facade a consumer package can actually compile
against. G1/G2 next.

## 2026-08-18 (later still) — the graph wave's ABI gap, found and closed before dispatch: `lgj_rowstore_open_with_edges` (minor 3)

Picked up the graph-consumer wave on "everything on track?" — it was marked
DISPATCHABLE by the entry below, and idle capacity while ruff_r2il works its
own PR2/3 track was worth using. Before spawning G1/G2, checked what Java
would actually call to reach `RowStore::generate_with_edges` — and it was
nothing: `Engine.openRowStore`/`registry::open_rowstore` only ever call plain
`RowStore::generate`; no `extern "C"` symbol for the edge-bearing generator
existed anywhere. The prior pass's STOP-condition-RESOLVED note proved the
*generator*, not the *membrane path to it* — a real gap that would have
surfaced mid-dispatch as "G1's scope requires touching a file outside your
scope" the moment a worker tried to open an edge-bearing store from Java.

Closed it as the wave's own D1b rule requires: "a new ABI symbol... must go
through the substrate wave process FIRST as its own W-tier PR" — done here as
orchestrator work (genuinely new ABI surface, not consumer scope), not
delegated. `lgj_rowstore_open_with_edges` (ABI minor 2→3, docs/abi.md §12):
byte-identical resource kind and lane shape to `lgj_rowstore_open`, purely an
alternative constructor, following the row store's own minor-2
`requireMinor` gating pattern exactly. `cargo test` 93/93 (+3), Java `AllTests`
194/194 (+6). Full record (including the two disable-runs and the strongest
result — Java independently reproducing the D1a hop mechanism's exact pinned
numbers through raw-segment reads, not just the classid stream): see
`STATUS_BOARD.md` D-LGJ-W6, `EPIPHANIES.md`.

Not yet dispatched: G1 (traversal facade) and G2 (falsifier tests) are next,
now against a genuinely complete substrate rather than one proven only at the
Rust generator level.

Meanwhile on ruff: PR #100 (upstream 1500-commit catch-up, explicitly marked
"baseline, not for merge" in its own body) merged anyway by the operator —
its own CI never completed (cancelled on every commit), but incidentally
fixed a 13-day-old `main`-CI lint failure (`prek`/shellcheck on
`.github/workflows/ci.yaml:443`) that predated this whole arc and was
unrelated to it. PR #101 (`ruff_r2il` PR2 first slice — §12 corpus profile
resolving O1, `RefinedTruthSink`) merged cleanly on top, ~1.5h turnaround,
CI green through `prek` on the resulting `main` run — the standing lint issue
stayed fixed. Neither event required action here; recorded for continuity
since both landed mid-session.

## 2026-08-18 (measured) — W5c's real blocker found + cleared: RowStore::generate_with_edges

Asked what was buildable while ruff_r2il PR2/PR3 are blocked. Was about to
dispatch the graph consumer (W5c) on the strength of D1a's mechanism
(writable masks, existing facet-match) being sufficient -- twice claimed
this in earlier turns -- before re-reading `wave-consumer-graph.md`'s own
STOP condition in full and catching a real, different blocker: plain
`RowStore::generate()`'s payload is uniform noise, so any 1-2 hop BFS over
it saturates to nearly every row regardless of decode convention --
vacuous under the wave's own anti-vacuity falsifier. A data-shape problem,
not a mechanism problem; caught before any workers spawned.

Fixed with `RowStore::generate_with_edges` (native/lgj-abi) -- additive,
`generate()` byte-identical for out-of-range `edge_classid` (pinned).
Parameters chosen from a real measurement sweep
(`examples/graph_density_probe.rs`), not guessed: at `n_rows=2000,
gate_mask=0x0, radius=25`, a 10-row seed reaches 19 rows at 1 hop, 29 at 2
hops -- pinned as a regression test. Two disable-runs (radius-wrap
formula; sparsity gate) both went red exactly where expected, including
one case (the gate disable) correctly NOT failing an orthogonal geometry
test -- verified as the right outcome, not a vacuous one.

`wave-consumer-graph.md` updated: STOP condition marked RESOLVED with the
measured numbers; the file's stale "calcify, do not dispatch" header
corrected (that gate was already lifted session-wide). **The graph
consumer is now genuinely dispatchable** -- not dispatched in this pass,
scoped to the generator only. Gates: lgj-abi 90/90 (+6), fmt/clippy clean.
Full record: `EPIPHANIES.md`, the entry above the R2IL handshake one.

## 2026-08-18 (even later) — ruff #96 is a different arm; found + read the REAL staging guide

Checked "ruff 96 merged." It's `ruff_python_spo`'s plain-Python residual
ledger (dismech/CURIE-constant harvest, ontology-shaped) — a sibling
drill-loop, but a DIFFERENT crate and consumer than `ruff_r2il`, and
unrelated to this repo's C-band/Ghidra/JavaRuntime track. No action needed
here; recorded so the two arms aren't confused later since they share
vocabulary.

The genuinely relevant find was already on `ruff` main, unrelated to #96:
`.claude/harvest/r2il/STAGED-CODEGEN-GUIDE.md`, explicitly addressed to
"the sibling session ... (the Ghidra console work)" — this repo. It
confirms PR2 (routes→V3) still hasn't landed and gives a 5-stage staging
order (S1 ledger-read → S2 ore-join → S3 additive codegen → S4 one
consumer → S5 target-profile fork) that does NOT wait on PR2 for its first
two stages. Ran S1 against the real in-tree harvest artifacts: B1
conservation PASS (dropped=0), B3 addressed-slag PASS (43 shapes,
dominant_share 0.215), B2 at 91.30% (INVESTIGATE band), and the
pre-registered 60-80%-classified prediction MISSED at a measured 14.15% —
recorded honestly, which is the point of pre-registering it. Dominant
residual is `opcode_not_in_convention`, expected: pass 1 only classifies 7
of P-code's 74 opcodes by design. Full record + the stability table
(FlatFact payload bytes and the placeholder VarnodeFacet classid are
explicitly NOT stable yet): `EPIPHANIES.md`, the entry above
`E-LGJ-GHIDRA-G1-G2-SUPERSEDED-BY-R2IL-1`.

No wave-gate change — PR2/PR3 still unmerged, `wave-ogar-machine-pm1.md`
gate #3 stands as previously repointed. Next unblocked step (not scheduled,
available when there's a reason to spend it): S2, still read-only.

## 2026-08-18 (later) — Ghidra G1/G2 waves reconciled: superseded by ruff_r2il, not built

Checked what "the other session writing the autoadapting drill-down proposer"
(ruff/r2sleigh) actually unblocks here, against the merged PR rather than the
summary. `AdaWorldAPI/ruff` PR #94 shipped `crates/ruff_r2il` — a typed
intake arm (ore/furnace/slag) reading r2sleigh's R2IL/SSA directly, with an
addressed residual ledger deliberately left non-empty for a follow-on pass.
That follow-on IS the drill-down proposer: PR2 in the R2IL plan's own wave
ladder, reading `ResidualLedger::by_address` and proposing finer convention
rows, converging pass over pass. **Not landed yet** — gated on PR1's corpus
numbers. PR3 (the classid mint in `lance-graph-contract::ogar_codebook`,
item O5) is gated on PR2. So there is nothing new to CONSUME here today.

What there IS: `wave-ghidra-g1-g2.md` (a bespoke `analyzeHeadless` lift
script + a hand-rolled LE image format) is now superseded, not merely
lower-priority — the R2IL plan's own stop condition already answers the
question those waves existed to answer ("direct r2il/r2ssa consumption
solves the upstream seam — YES, 43s"). Marked superseded in place;
`wave-ogar-machine-pm1.md`'s gate #3 repointed from "Ghidra G1+G2 merged" to
"ruff_r2il PR2+PR3 merged" so the real dependency is visible instead of a
dead one. Full record + a separately-found, pre-existing `ogar_codebook`
mirror-drift gap (flagged, not fixed): `EPIPHANIES.md`
`E-LGJ-GHIDRA-G1-G2-SUPERSEDED-BY-R2IL-1`.

No code changed; C-band ruling (`E-LGJ-THE-DOMAIN-BYTE-CARRIES-ALTITUDE-1`,
merged as OGAR PR #276) is unaffected — its `0xC4` fence is now literally
true in code rather than anticipated.

## 2026-08-18 — C-band ruling recorded: the domain byte carries ALTITUDE

Operator ruling (*"Java is an entire different layer that's why I chose
another higher level"*): the classid domain byte is **stratified by layer**,
not a flat namespace. The C-band is the stratum ABOVE the Rust substrate —
**C0** Java/Panama/Valhalla (the membrane, and the FLOOR of that layer),
**C1** ogar-bricks + Databricks (the analyst estate), **C4** Ghidra (a tenant
of C0's layer — Ghidra is itself a JVM application per this repo's G0
archaeology — and explosive, for the blast radius of turning any binary into
addressable rows).

Full entry, including the three of my own proposals it corrects and the
root-cause (I clustered by SHAPE — everything becomes `(function : value)`
calls in a 512-byte node — where the real axis is ALTITUDE):
`EPIPHANIES.md` `E-LGJ-THE-DOMAIN-BYTE-CARRIES-ALTITUDE-1`.

**Consequence for this repo, and it is the only one:** W6's schema/classid
field on `LgjResourceInfo`/`LgjLaneDesc` carries a **C0** concept. Nothing on
the current wave list is blocked by the allocation — the reservation is
OGAR-side and operator-gated (reserving costs nothing; minting is 5+3-gated).

## 2026-08-17 (dispatch 4) — W5b bricks shipped: authorization IS a mask, measured

`wave-consumer-bricks.md` executed (2 Sonnet workers K1/K2, disjoint main/test
scopes, orchestrator-gated). `consumers/bricks/` is the second consumer proof:
**RBAC as a natively-evaluated predicate in the same lazy chain as `where(...)`**
— `Role.EU_ONLY` folds `REGION.eq(EU)` into the plan, `DENY_ALL` is a real
impossible predicate (`REGION.eq(0xFFFF)`) that pays a real crossing and counts
0, and an unauthorized chain throws `UnauthorizedQueryException` BEFORE any
native crossing (fail-closed; no default-allow path exists in the package).
Aggregate-only egress is structural: every public method returns
`BricksQuery`/`long`/`Map` — no row-shaped public type exists to leak.

- **BricksAuthTest 62/62.** Parity vs the transcribed generator at 1K+64K rows;
  EU_ONLY == GLOBAL+explicit-where equivalence; crossing arithmetic measured.
- **A real finding, not just a green suite: a sum terminal costs 2 crossings**
  (plan evaluation into the mask + `lgj_reduce_sum_i32`), unlike `count()`
  whose plan eval returns the count and pays 1. `sumBy()` therefore measures
  **32 crossings (16 groups × 2) — IDENTICAL at 1K and 64K rows**, which is the
  thesis (crossings ∝ groups, never rows). K1's Javadoc claimed "one crossing
  per group"; the measurement corrected the doc, not the other way round.
- **Disable-run:** `requireAuthorized` short-circuited → exactly the 3
  can-fire fail-closed checks red (59 green), restored, 62/62. Core suite
  untouched at 188/188.
- Board-hygiene note, owned: W5a (trades, PR #11) shipped without a
  LATEST_STATE entry — STATUS_BOARD D-LGJ-W5 carried it; both consumers are
  now recorded there in full. W5c (graph) stays SHELVED on the D1 ruling +
  the edge-bearing generator substrate change.

## 2026-08-17 (dispatch 2) — W4 measured: the boundary re-asked on the REAL layout

`wave-substrate-w3-w4.md` Dispatch 2 executed: one Sonnet worker
(Component F: `F_RowStoreFacetScan` + `RowStoreData` + the two
`Kernels` facet-match arms, mirroring the Rust kernel's chunk algorithm
line-for-line incl. the `& 0x1111` classid-position mask), orchestrator-run
JMH, 9/9 combos, the cross-check green at every row count before anything
was timed.

**The finding: Component C's direction survives; its margin collapses.**
The Vector API still wins the per-row 32-facet scan at every row count —
but by **2.51× / 1.92× / 1.14×** (4K / 65K / 1M rows) against C's 56×, and
at 512 MiB traversed all three arms converge on memory bandwidth
(~6–7 GB/s on this container). More work per byte narrows the boundary
exactly as `execution-boundary.md` predicted; it now says so as
measurement. One disclosed asymmetry: the native arm allocates its output
per call (`facetMatchesInto` named as the follow-up if the small-row gap
ever matters).

Also: `summarise.sh` gained the F table (and its old "E/F" section title —
a real collision with the new component — was corrected to "E");
`TABLES.md` regenerated from the merged CSV; `RESULTS.md` §F written;
`RowStore` gained a package-private `handle()` (mirroring
`NativePattern`'s, for the bench's split-package `NativeAccess` bridge
only). Substrate wave file fully executed — both dispatches shipped.

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

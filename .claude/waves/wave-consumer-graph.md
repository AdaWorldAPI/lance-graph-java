# Wave: consumer example — graph traversal (facet edges, crossings ∝ hops)

> **DONE 2026-08-18.** `consumers/graph/` — `Graph`/`Edge` (G1) +
> `GraphHopTest` (G2), 43/43, disable-verified. See `STATUS_BOARD.md`
> D-LGJ-W5 (graph row) and `EPIPHANIES.md` for the one real finding
> caught during landing (the crossing-cost-per-hop assumption). This
> file's design rulings below are kept as the historical record of how
> the dispatch was scoped, not restated in the DONE note.

> Executes `consumer-graph-traversal-v1.md`. Gates: W3 merged (DONE) AND
> the hop-decode capability question resolved (Decision D1 below — ruled
> D1a) AND the edge-bearing generator STOP condition resolved (DONE,
> `RowStore::generate_with_edges`, see below). **DISPATCHABLE** — the
> calcify-only gate ("2026-08-17: calcified, not executed, until called")
> was lifted session-wide once autonomous dispatch was authorized (W5a/W5b
> both shipped under it); this wave's own, GENUINE extra gate (the
> generator) is what actually held it back, and is now cleared.

## Decision D1 (orchestrator resolves BEFORE any worker spawns)

A hop = facet-match (exists: `lgj_row_facet_match`) → decode target
addresses from matched facets' 12-byte payloads → scatter into the next
row set. The decode+scatter half does not exist. Two candidate shapes:

- **D1a — Java-side hop:** read matched facets' payloads via the raw
  lane 0 segment (zero-copy, no crossing), scatter into the next row set
  Java-side. Crossings per hop: 1 (the facet-match). No ABI change.
  Cost: the scatter loop runs in Java.
- **D1b — native hop:** a new `lgj_hop(res, edge_classid, src_mask,
  dst_mask)` symbol. One crossing, scatter in Rust. ABI minor bump —
  must go through `lgj-soa-substrate-v1.md`'s wave process FIRST as its
  own W-tier PR (the consumer-never-grows-the-membrane rule).

Ruling guidance: start D1a (proves the semantics with zero membrane
growth; the poster's claim "crossings ∝ hops" still holds at 1/hop), and
promote to D1b only if W4-style measurement shows the Java scatter
dominating. Record the choice + evidence in the PR body.

**D1a resolved concretely (2026-08-18) — the payload-read AND the
mask-write halves both needed new capability, and only one was built:**

- Payload read: **DONE**, as public core-facade methods (D-LGJ-W7,
  `STATUS_BOARD.md`) — `RowStore.classidAt(row, facet)`,
  `payloadLow64At(row, facet)`, `payloadHi32At(row, facet)`. Zero-copy,
  zero new ABI surface (reuses `lgj_lane_describe`, ABI minor 1),
  resolved once per store and cached. `payloadHi32At(...) == 0` is the
  structured-edge marker (`RowStore::generate_with_edges`'s own
  convention); `payloadLow64At(...)` is the target row when it fires.
- Mask WRITE from Java-computed rows: **checked, does NOT exist
  publicly.** `Mask`'s entire public surface is `count()`/`id()`/
  `source()`/`isOpen()`/`close()` — no constructor from row indices, no
  writable-words accessor. The "masks are WRITABLE through
  `lgj_mask_describe`" capability the original text above referenced is
  real but **internal-only** (used inside `View`'s fused-plan
  evaluation, never surfaced). Building `RowStore.maskOf(long... rows)`
  would be a FOURTH core-facade gap closure in the same shape as
  D-LGJ-W6/W7 — genuinely buildable (create an empty mask via the
  already-used `Engine.createMask`, describe its WRITABLE lane via the
  already-existing `lgj_mask_describe`, set bits in-process; zero new
  ABI symbols again) but deliberately NOT built this pass, to stop
  scope creep at a third consecutive orchestrator-only detour before any
  consumer worker ever ran.
- **Ruling: G1's hop currency is a Java-side row-index collection
  (`long[]`/`java.util.Set<Long>`), not a native `Mask`,** for THIS
  consumer example. This is an honest, documented simplification in the
  same spirit as the payload-reading-convention caveat the original text
  already called for — not a silent scope cut. It still satisfies every
  stated falsifier below exactly: zero serialization (a Java `long[]`/
  `Set<Long>` is not `byte[]`/JSON), crossings ∝ hops (one
  `facetMatches` crossing per hop, the scatter and `.count()`/`.minus()`
  are Java-heap operations with zero further crossings — if anything a
  STRONGER result than a Mask-based design, which would need a second
  crossing per hop to materialize the write), and hop correctness/
  anti-vacuity are representation-independent. Promoting to a real
  `Mask`-returning `Graph` (via `RowStore.maskOf`) is a named, explicit
  follow-up if a future consumer genuinely needs mask-algebra
  composition (`and`/`or` against another native mask) on the hop
  result — not needed for this wave's own falsifiers.

## Worker roster (2 Sonnet workers, disjoint — AFTER D1 is ruled)

**G1 — the traversal facade.**
YOUR SCOPE: NEW `consumers/graph/src/.../Graph.java`, `Edge.java`.
- `Edge.KNOWS` etc. = classid constants (the workspace canon: an edge's
  predicate is a classid REFERENCE; a facet whose classid == the edge
  classid IS an edge slot; its payload carries the target row address —
  document the payload reading convention used by the generated fixture
  data, and the honest caveat that a REAL lance-graph payload reading
  arrives with ClassView/W6).
- The decode convention, concretely (docs/abi.md §12,
  `RowStore::generate_with_edges`): a facet at `(row, f)` is a structured
  KNOWS edge iff `store.classidAt(row, f) == Edge.KNOWS.classid() &&
  store.payloadHi32At(row, f) == 0`; its target row is
  `store.payloadLow64At(row, f)`. These three `RowStore` methods are
  PUBLIC and already shipped (D-LGJ-W7) — do not reach for
  `internal.ffm` at all; if a symbol/type you think you need lives there,
  STOP per the standing preamble rather than importing it.
- `Graph.from(long... seedRows).hop(Edge.KNOWS).hop(...).minus(seedRows).count()`
  — row-index collections all the way (see the D1 ruling above for why
  this is a `long[]`/`Set<Long>` currency, not a native `Mask`, for this
  wave); no `Vertex`/`Edge`-instance objects, no per-row wrapper types.
  `Edge` itself is a schema of classid constants (mirroring `Trade`'s
  own "schema, not entity" shape from W5a) — no public constructor.
**G2 — falsifier tests.**
YOUR SCOPE: NEW `consumers/graph/src/test/.../GraphHopTest.java`.
- Hop correctness: 2-hop set == a transcribed plain-Java BFS over the
  same generated payload data (independent computation, no substrate) —
  `RowStoreParityTest`'s own `hop`/`publicHop` transcriptions (already
  shipped, same repo) are the reference shape to follow, not to import.
- Crossings ∝ hops: instrument downcall count via the PUBLIC
  `com.adaworldapi.lancegraph.Diagnostics.crossings()` (the same
  instrument `TradesParityTest`/`BricksAuthTest` already use — NOT
  `internal.ffm.Downcalls.crossings()`, which is off-limits to a
  consumer package) == hops, independent of row count (measure at two
  row counts, e.g. the pinned `n=2000` fixture and a second size).
- Anti-vacuity: seed / 1-hop / 2-hop sets are three different, non-empty,
  non-total sizes — assert all three inequalities. The pinned regression
  numbers (`n=2000, seed=0xF00D_CAFE, edge_classid=0, gate_mask=0x0,
  radius=25` → 10 seed rows → 19 at 1 hop → 29 at 2 hops) are a ready-
  made fixture; reuse them rather than re-deriving new ones.
- Zero serialization: no `byte[]`, no `toArray`, no JSON on the hop path
  (grep-style structural assertion in the test + the consumer has no
  such import).

### Orchestrator-only

Compile centrally; run; **disable-run:** break the payload target-decode
offset by 4 bytes → hop-correctness must go red (proves the decode is
load-bearing); restore. Board + PR per rhythm; if D1b was chosen, the
membrane PR merges FIRST and this wave's PR references it.

**STOP condition RESOLVED (2026-08-18):** the deliberate edge-bearing
generator arm this wave's STOP condition named now exists —
`RowStore::generate_with_edges(n_rows, seed, edge_classid, edge_gate_mask,
edge_radius)` (native/lgj-abi, landed as its own substrate-tier change,
NOT a consumer hack, per this file's own rule). It reuses `generate()`'s
classid stream byte-for-byte (an out-of-range `edge_classid` reproduces
`generate()` exactly — pinned by test) and, for a SPARSE, gated subset of
`edge_classid`-matching facets, writes a bounded-local-neighbourhood
target row instead of raw noise — the mechanism that keeps a 1-2 hop BFS
non-vacuous. Measured (`examples/graph_density_probe.rs`, not guessed):
plain `generate()`'s uniform-random payload saturates a 2-hop BFS to
nearly every row (the ORIGINAL problem this STOP condition named); at
`n_rows=2000, edge_classid=0, edge_gate_mask=0x0, edge_radius=25`, a
10-row seed set reaches exactly 19 rows at 1 hop and 29 at 2 hops — three
different, non-empty, non-total sizes, pinned as a regression test
(`measured_hop_counts_are_three_distinct_non_empty_non_total_sizes`).
**G1's payload-reading-convention documentation task now has real,
measured ground to document** rather than an open question.

**Substrate now complete at all three levels (2026-08-18) — genuinely
dispatchable, not just declared so:** beyond the generator (above), the
ABI membrane (`lgj_rowstore_open_with_edges`, minor 3, D-LGJ-W6) and the
public core facade (`classidAt`/`payloadLow64At`/`payloadHi32At`,
D-LGJ-W7) are both shipped and disable-verified — see the D1 ruling above
for the concrete method names G1 uses and the row-index-collection
currency decision. This wave was checked THREE times before dispatch
found a real, load-bearing gap each time (the generator, the ABI symbol,
the public accessor) — worth naming so a future re-read of this file
trusts "dispatchable" only after the concrete capability names are
present, not merely a STOP-condition-RESOLVED header.

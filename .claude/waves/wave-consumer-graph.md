# Wave: consumer example — graph traversal (facet edges, crossings ∝ hops)

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
row mask. The decode+scatter half does not exist. Two candidate shapes:

- **D1a — Java-side hop:** read matched facets' payloads via the raw
  lane 0 segment (zero-copy, no crossing), build the next mask by writing
  the EXISTING mask lane's words directly (masks are WRITABLE through
  `lgj_mask_describe` — this is what WRITABLE was for). Crossings per
  hop: 1 (the facet-match). No ABI change. Cost: the scatter loop runs in
  Java.
- **D1b — native hop:** a new `lgj_hop(res, edge_classid, src_mask,
  dst_mask)` symbol. One crossing, scatter in Rust. ABI minor 3 —
  must go through `lgj-soa-substrate-v1.md`'s wave process FIRST as its
  own W-tier PR (the consumer-never-grows-the-membrane rule).

Ruling guidance: start D1a (proves the semantics with zero membrane
growth; the poster's claim "crossings ∝ hops" still holds at 1/hop), and
promote to D1b only if W4-style measurement shows the Java scatter
dominating. Record the choice + evidence in the PR body.

## Worker roster (2 Sonnet workers, disjoint — AFTER D1 is ruled)

**G1 — the traversal facade.**
YOUR SCOPE: NEW `consumers/graph/src/.../Graph.java`, `Edge.java`.
- `Edge.KNOWS` etc. = classid constants (the workspace canon: an edge's
  predicate is a classid REFERENCE; a facet whose classid == the edge
  classid IS an edge slot; its payload carries the target row address —
  document the payload reading convention used by the generated fixture
  data, and the honest caveat that a REAL lance-graph payload reading
  arrives with ClassView/W6).
- `Graph.from(mask).hop(Edge.KNOWS).hop(...).minus(seed).count()` —
  masks all the way; no Vertex/Edge objects, no per-row types.
**G2 — falsifier tests.**
YOUR SCOPE: NEW `consumers/graph/src/test/.../GraphHopTest.java`.
- Hop correctness: 2-hop set == a transcribed plain-Java BFS over the
  same generated payload data (independent computation, no substrate).
- Crossings ∝ hops: instrument downcall count == hops (+1 terminal),
  independent of row count (measure at two row counts).
- Anti-vacuity: seed / 1-hop / 2-hop sets are three different, non-empty,
  non-total sizes — assert all three inequalities.
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
measured ground to document** rather than an open question — this wave
is dispatchable.

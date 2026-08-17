# Wave: consumer example — graph traversal (facet edges, crossings ∝ hops)

> Executes `consumer-graph-traversal-v1.md`. **DO NOT DISPATCH** —
> operator ruling 2026-08-17: calcified, not executed, until called.
> Gates: W3 merged AND the hop-decode capability question resolved (see
> Decision D1 below — this wave has a genuine open design decision the
> other two consumer waves do not).

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

**STOP conditions:** payload reading ambiguity (→ orchestrator, possibly
a generator extension in a substrate-plan PR — the fixture's payload
today is PRNG noise, so this wave NEEDS a deliberate edge-bearing
generator arm: that is a substrate change, not a consumer hack — flagged
here so nobody discovers it mid-dispatch).

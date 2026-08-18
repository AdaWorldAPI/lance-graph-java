# consumer-graph-traversal-v1 — traversal as facet addressing, no middleware

> **Status: SHIPPED 2026-08-18 (as-dispatched), then CORRECTED same day** —
> the wave landed (see `wave-consumer-graph.md`), and its `long[]`-frontier
> surface was subsequently demoted by the D-LGJ-W8 mask-native correction
> (PR-W8b: `Mask` frontier, `lgj_hop`, `materializeRows()`, `importRows`).
> This plan is the historical dispatch record; the governing spec is
> `mask-native-navigation-correction-v1.md`. (Header previously said
> PLANNED — pre-existing staleness, corrected per spec §3.7.)
> Originally: **PLANNED** (2026-08-17). W5 consumer example #3, from the
> operator's "Java Graph Stack (Heute) vs Project Panama + lance-graph
> (richtig gemacht)" poster. Gated on `lgj-soa-substrate-v1.md` W3.

## What it proves

The poster's BEFORE chain is six components and five serialization
boundaries: App → DTO/ORM → TinkerPop/Gremlin → JanusGraph → Cassandra →
Elastic/ClickHouse/Lucene. The AFTER chain is **one** explicit ABI boundary
and **zero** serialization boundaries — *"Java als low-code Oberfläche, ABI
als Wahrheit."*

Runnable shape (a 2-hop neighbourhood, entirely in masks):

```java
var g = Graph.open(store);
var friendsOfFriends = g.from(seedRows)          // a mask
    .hop(Edge.KNOWS)                             // facet-addressed, one crossing
    .hop(Edge.KNOWS)
    .minus(seedRows)
    .count();
```

No `Vertex` object, no `Edge` object, no Gremlin step compiler, no
serialization between hops — a hop is a mask transformation over the SAME
un-copied bytes.

## Design (constraints, not code)

- **An edge is an ADDRESS, not an object.** In the 512-byte row, a facet's
  4-byte classid names *what kind of relation this slot holds*; the 12-byte
  payload carries the target address. That is exactly the workspace canon
  ("a relation is a class; an edge's predicate is a classid reference" —
  MedCare-rs commitment #10 / the OGAR EdgeBlock doctrine) expressed in the
  facet register. `Edge.KNOWS` is therefore a *classid constant*, and
  "which of this row's slots are KNOWS edges" is precisely
  `lgj_row_facet_match` — already built in W2.
- **A hop is: facet-match → decode targets → build the next mask.** The
  first half exists. The second half (target decode + scatter into a mask)
  is the ONE genuinely new capability this example needs, and it is a
  *bulk* operation by construction (one crossing per hop, work ∝ rows). If
  it cannot be expressed with the existing symbols, it goes back through
  `lgj-soa-substrate-v1.md`'s wave process as a proposed W6 ABI addition —
  **not** added ad hoc from the consumer side. Naming it here is the
  design decision; building it is gated.
- **The comparison is the point.** This example carries an explicit
  boundary-count table in its output: components traversed, serialization
  boundaries crossed, objects allocated — measured, against the poster's
  BEFORE column as the stated baseline (which is cited as *architecture*,
  not benchmarked here; we do not claim measured numbers for a JanusGraph
  stack we did not run).

## Falsifiers

1. **Hop correctness**: the 2-hop neighbourhood equals a transcribed,
   plain-Java breadth-first walk over the same generated data — an
   independent computation, not a golden blob.
2. **Zero serialization**: no `byte[]`, no `toArray`, no JSON/proto on the
   hop path — enforced the way the bench enforces it (an explicit rule in
   the test, plus the API surface test for the public types).
3. **Crossings scale with HOPS, not with rows or edges**: instrument the
   downcall count and assert it equals the hop count (+1 terminal),
   independent of row count — the anti-JNI property, stated as an
   assertion rather than as prose.
4. **Anti-vacuity**: the seed set, the 1-hop set and the 2-hop set must be
   three *different, non-empty, non-total* sizes — a traversal that
   returned everything or nothing would satisfy a naive equality test while
   proving nothing.

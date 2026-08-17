# Wave: consumer example — World/Trades ("One Billion Objects. Zero Objects.")

> Executes `consumer-world-trades-v1.md`. **DO NOT DISPATCH** — operator
> ruling 2026-08-17: consumer plans are calcified, not executed, until
> called. Gate: W3 merged. Inherits `waves/README.md` standing rules.

## Shape at dispatch time

New directory `consumers/trades/` (own compile unit, classpath = `java/`
output; never merged into the core package — `ApiSurfaceTest`'s walk must
stay scoped to the core API, and a consumer is a CONSUMER).

### Worker roster (2 Sonnet workers, disjoint)

**T1 — the domain facade.**
YOUR SCOPE: NEW `consumers/trades/src/.../World.java`, `Trade.java`.
- `Trade` is a SCHEMA, not an entity: static typed field descriptors
  (`Trade.QUANTITY`, `Trade.VENUE`, `Trade.PRICE`) — each a small record
  carrying (facet index, elem kind, predicate factory). No `Trade`
  instance is ever constructible (private ctor, enforced by a test).
- `World.open(Trade.class, nRows, seed)` binds the schema onto a
  `RowStore`/`NativePattern` and returns the existing lazy `View`
  machinery under domain names. REUSE `View`/`Predicate`/`Mask` — zero
  new membrane surface, zero new query engine.
**T2 — falsifier tests.**
YOUR SCOPE: NEW `consumers/trades/src/test/.../TradesParityTest.java`,
`TradesAllocationTest.java`.
- Parity: fluent-chain count == pure-Java transcribed-generator
  recomputation (no substrate involvement in the expected value).
- Allocation: `getThreadAllocatedBytes` (the valhalla-lab instrument,
  reuse its helper) — allocated bytes for the query path stay below a
  fixed constant INDEPENDENT of row count (measure at 64K and 1M; the
  row-count-independence IS the assertion).
- Laziness: crossing count 0 while composing, 1 at terminal (reuse the
  LazinessTest counting instrument).

### Orchestrator-only

Compile centrally; run; **disable-run:** make `Trade.QUANTITY` point at
the wrong facet/lane → parity must go red (proves the schema binding is
load-bearing, not decorative); restore; board + PR per rhythm.

**STOP conditions:** any temptation to add an ABI symbol (forbidden —
back through the substrate plan); any need for a `Trade` instance
(violates the thesis; redesign the accessor instead).

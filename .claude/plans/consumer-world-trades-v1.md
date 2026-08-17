# consumer-world-trades-v1 — "One Billion Objects. Zero Objects."

> **Status: PLANNED** (2026-08-17). W5 consumer example #1, from the
> operator's "One Billion Objects in Java — Before vs After" poster.
> Gated on `lgj-soa-substrate-v1.md` W3 (the Java `RowStore` facade).

## What it proves

The poster's AFTER column, runnable:

```java
var trades = World.open(Trade.class);      // ← a RowStore, not a loadTrades()
long count = trades
    .where(Trade.QUANTITY.gt(1000))
    .where(Trade.VENUE.eq(XETRA))
    .where(Trade.PRICE.gt(threshold))
    .count();
```

Developer sees: familiar fluent Java, domain language, no serialization.
What actually happens: compose lens (0 crossings) → one fused evaluation →
packed mask → count. **Java objects allocated for N logical trades: 0** —
and that number is asserted by measurement
(`getThreadAllocatedBytes`, the valhalla-lab instrument), not claimed.

## Design (constraints, not code)

- **`World.open(Class<T>)` is a schema binding, not a loader.** The class
  is a *description*: static typed field descriptors (`Trade.QUANTITY`)
  carrying (facet index, element kind, offset-within-facet). It maps the
  domain vocabulary onto the 32-facet row; no instance of `Trade` is ever
  constructed. This is the poster's "ClassView (Semantics)" cell scaled to
  the fixture — a REAL lance-graph ClassView binding replaces it in a
  later slice without changing consumer code.
- **Field descriptors are the Valhalla-shaped vocabulary** — tiny,
  identity-free, `record`-shaped, ≤8B payload where possible (the measured
  flattening cliff), migrating to `value record` by one word when JEP 401
  ships. The three-truths lab already proved this is the ONE place
  Valhalla pays here.
- **Reuses `View`/`Predicate`/`Mask` machinery** — the fluent chain stays
  lazy (0 crossings to compose, LazinessTest discipline), fuses to one
  plan, crosses once. No new membrane surface expected; if one turns out
  to be needed (e.g. a fused plan over facet lanes), it goes through the
  substrate plan's wave process first.
- **The demo scale is honest**: 64K rows in-repo CI; the 10⁶+ row arm runs
  as a bench/example, not a unit test.

## Falsifiers

1. Count parity: the fluent chain's answer == a transcribed-generator
   recomputation in plain Java (no substrate involvement).
2. Zero-allocation: measured allocated bytes for the query path below a
   fixed small constant (the descriptors + the mask handle), regardless of
   row count — the row-count-independence IS the assertion.
3. Laziness: crossing count 0 while composing, exactly 1 at the terminal
   (the LazinessTest instrument, reused).
4. API surface: reflection test — nothing in the consumer-visible API
   mentions FFM, facet indices, or lane ids.

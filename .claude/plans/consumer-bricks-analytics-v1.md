# consumer-bricks-analytics-v1 — mask-first security over the row store

> **Status: PLANNED** (2026-08-17). W5 consumer example #2, from the
> operator's "OGAR-Bricks + lance-graph + Panama + Valhalla" poster.
> Gated on `lgj-soa-substrate-v1.md` W3.

## What it proves

The poster's structural claim: **security is a mask composed BEFORE
execution, not a post-filter on rows that already crossed the trust
boundary.** Runnable shape:

```java
var orders = Bricks.table(Orders.class);
var result = orders
    .where(Orders.REGION.eq("EU"))
    .where(Orders.YEAR.eq(2026))
    .authorize(currentRole)          // ← the mask-first clamp, BEFORE execution
    .groupBy(Orders.PRODUCT)
    .sum(Orders.REVENUE);
```

Only aggregates leave the boundary; raw rows never do.

## Design (constraints, not code)

- **`authorize(role)` composes an ADDITIONAL predicate into the SAME lazy
  chain** — it is not a separate enforcement pass over already-fetched
  data. Concretely: a role resolves to an allowed-facet-classid set (a
  small, deterministic table — no new ABI symbol, expressible as an OR of
  `Orders.<field>.eq(...)` over the existing `where` vocabulary, or as a
  precomputed mask ANDed in via the existing `lgj_mask_and`). Either
  encoding is legal; the falsifier below is what matters, not the
  mechanism.
- **RBAC clamp happens where the OTHER predicates happen** — inside the ONE
  fused crossing. There is no code path where a row's bytes are readable by
  Java before the mask that would exclude it has been applied. This is the
  operational meaning of "mask-first" and it is what the falsifier checks.
- **Reuses the row-store facet-match kernel for the "which fields visible"
  half** — a role that can see some facets of a row but not others (partial
  visibility) is a `lgj_row_facet_match`-shaped question; a role that can
  see some ROWS but not others is a mask-composition question. This example
  demonstrates the row-visibility case (simpler, no new kernel needed);
  facet-level field masking is named as a documented extension, not built
  here.

## Falsifiers

1. **No raw-row read before authorize.** Instrument (or structurally
   prove via the API surface — no accessor exists that reads a row's bytes
   before a terminal op runs) that between `.where(...)` calls and the
   terminal aggregate, zero bytes of any EXCLUDED row are ever read into a
   Java-visible value. The `LazinessTest` 0-crossings-while-composing
   proof already gives half of this "for free" — extend it to prove
   `authorize()` composes rather than executes.
2. **A caller who never calls `authorize()` gets an explicit refusal**, not
   an unauthorized default (fail-closed — mirrors the a2ui-rs/lance-graph
   RBAC doctrine already in this workspace's CLAUDE.md: "a missing/narrow
   role mask never falls back to emit everything").
3. **Two roles, same query, different counts** — a real two-sided
   discrimination test (a role that can see 0 rows of a restricted region
   must count 0; a role that can see all of it must match the unrestricted
   query) — the anti-vacuity discipline from this workspace's falsifier
   rules, applied to authorization instead of a table filter.
4. **Aggregate-only egress**: the public return type of a `Bricks` query is
   never `Row`/`Trade`-shaped when a `groupBy`/`sum` terminal was used —
   checked by the API surface test, same mechanism as `ApiSurfaceTest`.

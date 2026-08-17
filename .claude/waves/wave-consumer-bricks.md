# Wave: consumer example — Bricks (mask-first authorization)

> Executes `consumer-bricks-analytics-v1.md`. **DO NOT DISPATCH** —
> operator ruling 2026-08-17: calcified, not executed, until called.
> Gate: W3 merged. Inherits `waves/README.md` standing rules.

## Shape at dispatch time

New directory `consumers/bricks/` (own compile unit, same isolation
rationale as the trades wave).

### Worker roster (2 Sonnet workers, disjoint)

**K1 — the authorized-query facade.**
YOUR SCOPE: NEW `consumers/bricks/src/.../Bricks.java`, `Orders.java`,
`Role.java`.
- `Role` = a deterministic allowed-classid/predicate table (small,
  hand-declared for the example; a real RBAC source replaces it later
  without changing the composition point).
- `Bricks.table(Orders.class)` → the lazy chain; `.authorize(role)`
  composes the role's constraint INTO the same chain (an AND of existing
  predicate vocabulary or a precomputed mask ANDed via existing
  `mask_and`) — it must be a `Predicate`-tier citizen, not a post-pass.
- **Fail-closed:** a terminal op on a chain that never called
  `.authorize(...)` throws `UnauthorizedQueryException` — there is no
  default-allow path (mirrors the workspace RBAC doctrine: a missing
  mask never falls back to emit-everything).
- Aggregate-only egress: terminal ops after `groupBy`/`sum` return
  aggregate types only — no row-shaped public type exists in this
  consumer at all.
**K2 — falsifier tests.**
YOUR SCOPE: NEW `consumers/bricks/src/test/.../BricksAuthTest.java`.
- Two-sided discrimination: role A (sees region EU) vs role B (sees
  nothing of EU) — same query, counts differ exactly as the pure-Java
  recomputation predicts; role-that-sees-all == unauthorized-baseline
  count computed WITHOUT the authorize path (computed in the test, never
  via a bypass API — there is none).
- Fail-closed can-fire: terminal without authorize → throws. And its
  silence twin: WITH authorize, no throw, correct answer.
- Compose-not-execute: crossing count stays 0 through
  `.where().authorize().groupBy()` — 1 at the terminal (reuse the
  laziness instrument). This is the "no raw-row read before authorize"
  property in its strongest checkable form: NOTHING crosses until the
  fully-clamped plan does.

### Orchestrator-only

Compile centrally; run; **disable-run:** short-circuit the fail-closed
check (`if (false && !authorized)`) → exactly the can-fire test goes red;
restore. Board + PR per rhythm.

**STOP conditions:** any design where authorization happens AFTER bytes
are readable (violates mask-first — redesign, do not ship); any new ABI
symbol temptation (forbidden).

# CLAUDE.md — lance-graph-java

> Read first, every session. This file is the MASK-NATIVE POLICY GUARD
> for this repo — the operator-ruled architecture law that would
> otherwise reset with the session. Ratified with the D-LGJ-W8 council
> spec (`.claude/plans/mask-native-navigation-correction-v1.md`, v3);
> that spec carries the full evidence and the wave plan. Board files
> under `.claude/board/` are the durable session record (append-only;
> storno rule in each header).

## What this is

Java ergonomics over the lance-graph substrate via Panama FFM: a small
versioned C ABI (`native/lgj-abi`, `docs/abi.md` is normative), a thin
FFM membrane (`java/.../internal/ffm`, walled off by `ApiSurfaceTest`),
and a semantic facade (`View`/`Mask`/`RowStore`/consumers). The
`lance-graph-contract` crate is THE semantic law (ClassView /
FieldMask / WideFieldMask); the full lance-graph ENGINE is never a
dependency — blast-radius containment is the point of the contract
split, and it does not make the contract optional.

## The mask-native invariant (operator-ruled, 2026-08-18)

**WHERE MAY LOOK LIKE WHERE. IT MUST EXECUTE LIKE MASK.**
**HOP MAY LOOK LIKE HOP. IT MUST EXECUTE AS MASK × CLASSVIEW/WIDEFIELDMASK → MASK.**
**COMPUTE MAY LOOK LIKE A PARALLEL COLLECTION OPERATION. UNDERNEATH IT
IS BULK SoA COMPUTE.**
**LANDING MAY LOOK LIKE A WRITE. UNDERNEATH IT IS DETERMINISTIC
PLACEMENT INTO THE OPEN CYCLE IMAGE.**

Java-surface convenience never dictates substrate representation.
Zero-serialization is NOT sufficient — a `long[]` of selected row IDs
is still a materialised population.

### The currencies

| Currency | Question | Carrier |
|---|---|---|
| **ClassView** | what does this classid/facet family MEAN | contract trait; provider LATE-BOUND (fixture provider in lgj-abi; a real ontology/cache provider is a named seam) |
| **WideFieldMask / FieldMask** | which fields/facets participate | contract types; Java mirror `WideFieldMask` (Small tier; Wide promotion is a named seam) |
| **Mask** | which rows/population | native `Box<[u64]>` behind the generation-checked handle |
| **the sealed temporal horizon** | which sealed version applies (compute wave) | Java binding = the contract's clean `LanceVersion`/`TemporalPov` vocabulary — never the engine's Kanban-entangled type |

### Forbidden as normal execution state

For `where` / `hop` / `authorize` / `navigate` AND for `compute` /
`transform` / `stage` / `land` / `batch` / `commit`:

- `long[]` / `Long[]` / `Set<Long>` / `TreeSet<Long>` / any
  `java.util.Collection` as a row frontier or population;
- per-row payload extraction + Java-side filtering as a query or
  traversal engine (`RowStore.classidAt` / `payloadLow64At` /
  `payloadHi32At` / `FacetMatchView.matchesOf` are LOW-LEVEL
  INSPECTION / DIAGNOSTICS — never an execution engine);
- Mask → row IDs → Mask round-trips;
- one Java object, one `Long`, or one row-ID entry per selected row
  merely to carry a bulk result to the batch writer.

A bulk compute result stays attached to SoA coordinates, masks,
ranges, or descriptors.

### The named exceptions (visible at the call site, by NAME)

- **Import** (external row selections in): `RowStore.importRows(long...)`
  — and consumer entry points that DELEGATE to it with the
  classification in their javadoc (`Graph.from(long...)` is the one
  sanctioned example). Never the internal currency.
- **Materialisation** (row IDs out): ONLY from methods whose name
  starts with `materialize` (`Mask.materializeRows()`), O(n) cost
  stated in javadoc. No unnamed materialiser may exist.

### Enforcement (structural, not aspirational)

`GraphHopTest`'s reflective allowlist (returns AND parameters), the
committed G2 no-per-row-engine check (call sites, not javadoc
`{@link}`s), the allocation gates (`getThreadAllocatedBytes`,
population-size independent), and the G11 contract-import fence
(`native/lgj-abi` imports `lance_graph_contract::{class_view,
canonical_node, ontology}` ONLY — the cognitive modules compile
unconditionally; the fence is the only barrier). Spec §5 is the
canonical gate list.

## The compute model (inherited architecture — spec Part II)

Three axes, never collapsed: **EXECUTION** (empirical policy: Java
scalar / Vector API / Panama→ndarray SIMD / native fused / GPU) —
**PLACEMENT** (identity-derived landing, `row_of(owner)`-shaped; the
mechanism choice stays upstream) — **PUBLICATION** (canonicalization
boundary → freeze → coalesce → SOLE native writer → one
DatasetVersion; Java NEVER rebuilds the writer, never mints versions,
never keeps a second ledger).

Maxims, each with its scope leg: 64K COMPUTE WAS PARALLEL (proof-arm
in-tree; not yet the production loop). DETERMINISTIC LANDING DOES NOT
MEAN SEQUENTIAL EXECUTION. COMPLETION ORDER IS NOT SEMANTIC ORDER.
PARALLEL COMPUTATION ≠ ARRIVAL ORDER ≠ SEMANTIC PLACEMENT ≠
PUBLICATION ORDER. ONE COMPUTATION IS NOT ONE LANCE WRITE. ONE ROW IS
NOT ONE JAVA OBJECT. SEPARATION OF CONCERNS CONTAINS BLAST RADIUS; IT
DOES NOT MAKE THE CONTRACT OPTIONAL.

The generic parallel write API (GridLake) is **BLOCKED until
deterministic landing identity is independent of producer arrival
order** — the landing key's required properties are frozen in the spec
(§10); no placement algorithm enters the Java ABI.

**The kanban_actor lesson** (`E-PROGRESSION-IS-EXISTENCE-NOT-COMMAND-1`,
lance-graph): actor/message-per-write is the architecture lance-graph
itself DELETED — never port it. Progression is existence, not command:
per-owner `advance(owner)` RPCs are exactly the deleted shape.
`BatchWriter::cast()` = staging into a batch image, NOT command/ack
messaging.

## Missing-capability STOP rule

A consumer or facade that needs a capability the substrate lacks does
NOT hand-roll it one layer up. STOP; the capability lands as its own
substrate-tier change first (the consumer never grows the membrane —
`docs/abi.md` §6, and the D-LGJ-W6/W7 precedent: three real gaps were
each closed substrate-first before the consumer wave dispatched).

## Iron rules (inherited, load-bearing)

- All SIMD from `ndarray::simd` (the polyfill re-export surface) —
  never `ndarray::hpc::*`, never raw intrinsics (`abi.md` §8).
- Every ABI function is bulk (work ∝ n_rows) or lifecycle (§6);
  additive change ⇒ minor bump; rebuild the `.so` FIRST (eager clinit
  resolution fails every suite against a stale artifact — measured,
  D-LGJ-W6).
- No FFM types (`java.lang.foreign.*`, `java.lang.invoke.*`,
  `internal.*`) in any public signature (`ApiSurfaceTest`).
- Measure-then-pin: crossing counts, allocation numbers, and
  thresholds are pinned from measurement, never predicted. Disable-runs
  are red-then-green or they are not evidence.
- Board discipline: append-only, storno cites what it corrects, board
  artifacts land in the SAME commit as the change, PR-arc sha recorded
  post-merge only.
- **No model identifier in any committed artifact** (chat only). This
  file deliberately carries NO model-policy section; worker-tier
  allocation is stated by role in session briefs.

## Session start

1. This file. 2. `.claude/board/LATEST_STATE.md` +
`.claude/board/STATUS_BOARD.md` (what exists / what's in flight).
3. `.claude/plans/mask-native-navigation-correction-v1.md` if touching
navigation, masks, the contract dep, or the compute surface.
`docs/abi.md` is normative for any membrane work.

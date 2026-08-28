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

## Zero-copy + memory safety — NORMATIVE, MERGE-GATING (operator-ruled, 2026-08-27)

**Zero-copy means the canonical bytes remain owned by `lance-graph`; Java/
Panama project operations onto those bytes without duplicating canonical
state.** What crosses the membrane is identity, descriptor, layout
contract, projection hint, operation, and result identity — never a
second graph. **One-copy law**: multiple views/lanes/masks over the same
substrate are fine; multiple *authorities* are not.

**Shape may cross. Meaning may cross. Operations may cross. Ownership
does not cross.** The 512×32×(4+12) row/facet contract, offsets, stride,
alignment, endianness are legitimate contract facts Java may know —
zero-copy is about storage *ownership*, never about layout *opacity*.

Non-negotiables, each with its enforcement site (audited clean
2026-08-27 — this is confirmation of existing structure, not a new
build):

- **Pointer value is not provenance, on the handle-mediated path.** Every
  ABI *handle* (`Mask`/`RowStore`/`NativePattern` opaque `long`s) is bound
  to owner identity + generation + kind via the generation-checked handle
  registry (`registry.rs::{encode_handle, resolve, resolve_kind}`); a
  stale generation fails closed before dereference, on every resolve call.
  Falsifiers: `fabricated_handles_are_rejected_not_dereferenced`,
  `a_reused_slot_invalidates_the_old_handle` (the latter is conditionally
  vacuous under some registry-slot orderings — cite alongside
  `use_after_close_is_a_status_not_a_crash`, the falsifier that holds
  unconditionally). Generation is `u32` and wraps after 2³² closes of one
  slot (documented in-source at the wrap site; not reachable in practice).
  **Scope note — the cached-descriptor path, corrected 2026-08-28 (plan W2;
  the earlier text was written before W1.1 shipped and went stale the day
  it did).** `lgj_lane_describe`/`lgj_mask_describe` hand Java a raw `addr`,
  which Java caches (`RowStore.java`'s `lanes[]`, `Mask.java`'s `words`).
  The two halves now differ and must not be described together:
  - **`Mask`** re-validates the cached window against the generation-checked
    registry **at each top-level facade call** (`Mask.words()` re-describes
    through `lgj_mask_describe`, which resolves the mask WITH its parent, and
    compares the returned `epoch` against the stamp it holds). This is
    **not atomic with respect to a concurrent `close`** — it narrows the
    window, it does not close it.
  - **`RowStore`** does **not**: its `lanes[]` reads consult only the
    Java-side `closed` boolean, a strictly weaker, non-generation-checked
    mechanism. `ISS-LGJ-EPOCH-UNCHECKED` stays OPEN, scoped to `RowStore`.

  ⊘ Struck: the earlier claim that this path reads "with NO further registry
  call" and that `epoch` "is currently unconsulted anywhere in `src/main`".
  Both were true when written and both became false with W1.1 (#53) —
  `Mask.words()` is the counter-example to each. Do not read "fails closed
  before dereference" as covering `RowStore`'s cached lanes; for `Mask`,
  read it as re-validated per facade call and racy against a concurrent
  close, never as a guarantee that holds through a scan.
- **Bounds/overflow are checked, never wrapped, at the point n_rows is
  first derived.** `rowstore.rs` uses `checked_mul` at its two allocation
  sites (`generate_in`/`generate_with_edges_in`); overflow there fails
  closed, never truncates. `kernels.rs` does not re-derive `n_rows` — it
  operates on an already-allocated, already-length-checked slice and
  bounds itself against that slice's real `.len()` via `assert_eq!`
  (e.g. `kernels.rs:199`), not a second checked multiplication; that is a
  sound bound, not an unchecked one, but it is a different mechanism than
  the allocation-time check and should not be described as the same
  `checked_mul`/`checked_add` machinery "throughout" both files.
- **Alignment and endianness are contract fields, never inferred.**
  `LgjAbiManifest.align_of_*` are filled from `core::mem::align_of` on
  the real types (never a literal); `endianness` is verified explicit
  (`Abi.java` rejects non-zero before any projection).
- **Manifest-first handshake.** `Abi.java` reads magic → abi_major
  (exact) → minor (>=) → struct sizes/alignment → endianness, in that
  order, before any dependent layout is resolved — never a speculative
  read past the guaranteed prefix. `requireMinor(N)` gates every
  minor-5-or-later operation cleanly (an older library fails at the call,
  via the lazy nested-holder pattern). **Minors 2-4 (row store, edges,
  hop) are a tracked exception, not yet covered**: their `Downcalls`
  holders resolve `MethodHandle`s eagerly at class-init, so an ABI-0.1
  library fails at `Downcalls.<clinit>` before any `requireMinor` guard
  can report a clean error (`Downcalls.java`'s own comment documents
  this). Do not claim "fails cleanly at the call, not at load" as a
  blanket property until minors 2-4 adopt the same lazy-holder shape.
- **FFM is quarantined.** `java.lang.foreign.*`/`java.lang.invoke.*`
  never appear in a public signature (`ApiSurfaceTest`, verified by real
  reflection over compiled classes — `Class.getMethods/getFields` plus a
  `com.adaworldapi.lancegraph.internal.` package-prefix check — not a
  source-text grep); internal use in `RowStore.java`/`FacetMatchView.java`
  is private-field-only, and no other file in the public package touches
  an FFM type.
- **Mutation crosses as verbs, not writable memory — except through the
  named Import exception.** No public API exposes a writable canonical
  segment for general use; mutation happens through named ABI operations
  (`mask_and`/`apply_projection`/etc.), never ad hoc `segment.set(...)`.
  The one sanctioned exception is the **Import** path already named above
  (`RowStore.importRows`, `Graph.from(long...)`): it does call
  `Engine.setU64` → `segment.set(...)` on a mask lane the ABI marks
  `LGJ_FLAG_WRITABLE` by design, and it does run a per-row Java loop over
  the imported ids — both are the Import exception's documented cost, not
  a second, unnamed violation of this bullet.
- **Materialization is named and bounded.** Production `long[]`/`copyOf`/
  `toArray` call sites, exhaustively: `Mask.materializeRows()` (the one
  named terminal, O(n)); the manifest-name read during handshake;
  `rowLayoutProbe`'s ≤32-byte-per-call diagnostic (bounded by facet count,
  not row count — verified fixed-size on both the Rust and Java sides);
  `Abi.java`'s `readCarvings` (bounded by `CARVING_SLOTS`, a manifest
  constant, not n_rows); `Engine.facetSumResolved`'s fixed `long[2]`
  result pair. None of the five is a hidden proportional-to-n_rows
  population copy — keep this list exhaustive when a sixth site is added,
  rather than letting the enumeration silently go stale again.
  Temporary kernel scratch (SIMD scratch masks, decode buffers) is
  allowed and is NOT the same claim as a second canonical copy.
- **Layout parity is independently derived, not self-compared.**
  `AbiContractTest`: Java's own layout constants vs. the artifact's
  runtime self-description, with a deliberately-impossible-expectation
  arm proving the check can actually fail.
- **SIMD backend is diagnostic only.** `NativeRuntime.simdBackend()` is
  a manifest string for logging; no `if` branches on it anywhere in
  `src/main` — backend parity is `ndarray::simd`'s business (see E1-E6
  below), never Java's.
- **Worker topology stays substrate-private** — see §E of the
  mask-native-navigation-correction-v1.md enforcement pass: no
  `workers(`/`workerCount`/`parallelism(`/`threads(` in any production
  Java, ABI struct, or export; `EXP-KIA-A2-64K`'s worker sweep is a
  native benchmark independent variable, never a consumer-facing API.

Wording discipline (use exactly): *"lance-graph owns the only canonical
copy. lance-graph-java projects semantic operations across Panama onto
that state without row/population duplication."* Never *"Java borrows
the native database memory directly"* — that overclaims the
abstraction. Never *"FFI is memory-safe end-to-end"* — the defensible
claim is *"the Java consumer cannot directly express arbitrary native
memory access; native resources are accessed through generation-checked,
version-checked, bounds-checked ABI operations whose ownership remains
in lance-graph."*

## The simd.rs isomorphism — ENFORCEMENT LAYER (operator-ruled, 2026-08-27)

The repo's whole shape is `ndarray`'s own SIMD architecture repeated one
level up, and every layer rule below is enforced by a named gate, not by
discipline:

```
ndarray                          lance-graph-java
simd.rs      (facade)      ←→    Java (View / Mask / RowStore / consumers)
cfg dispatch (polyfill)    ←→    Valhalla + Panama (internal/ffm)
simd_{amx,avx512,avx2,           Rust: lgj-abi kernels → ndarray::simd
  neon,wasm,scalar}.rs           (the pattern NESTS — lgj's bottom is
             (backends)           ndarray's top)
```

Measured grounding (2026-08-27, in-tree): `simd.rs` is 37 functions and
ZERO shipping instructions — every raw intrinsic in it sits inside
`#[cfg(test)]` as the wrapper's oracle; `simd_avx512.rs` alone carries 488.
`simd_scalar.rs` is a BACKEND, below the facade — the fallback is never
inline in `simd.rs`.

**E1 — Java never grows a compute path.** A Java-side loop over rows,
facets, or partial results is an inline scalar fallback in the facade —
the shape ndarray forbids by architecture. Java hands the question through
Panama and receives the projection; the decomposition of an answer (how
many parts, in what order, summed how) is itself a moving part and never
crosses. Enforced by: GraphHopTest's G2 no-per-row-engine check + the
reflective allowlist; the three-strikes provenance is
`FacetMatchView.cardinality` (Java popcount loop → 32 composed counts
summed in Java → the proposed buffer-popcount symbol — each one layer up,
all three wrong; ABI minor 9 is the correct shape).

**E2 — Java scalar code is licensed in exactly one place: as a TEST
ORACLE.** Same license `simd.rs` gives raw intrinsics under `#[cfg(test)]`.
GraphHopTest / parity-suite scalar recomputes stay; any scalar path in
`src/main` is a violation regardless of how it is doc-commented ("saves a
crossing" is the recorded tell, not a defence — R8 measured bulk crossings
as costing nothing).

**E3 — the geometry has ONE spelling, owned by the polyfill.** The facade
names sizes and offsets from `internal/ffm/Layouts` (`ROW_BYTES`,
`FACET_BYTES`, `FACET_PAYLOAD_OFFSET`, `FACET_PAYLOAD_HI32_OFFSET` — all
DERIVED from `ROW_LAYOUT`/`ROW_FACET`, proven by `SELF_CHECK` at
class-init); it never hand-writes them. Same rule minor 8 established for
carvings: one source and two derivations, never three spellings. Rust's
mirror constant is `rowstore::FACET_PAYLOAD_HI32_OFFSET`.

**E4 — Vector API is permanently a lab arm.** It would be a backend INSIDE
Java, and Java has no backends. `valhalla-lab`/`bench` may measure it; it
never ships in `src/main`.

**E5 — new capability lands backend-first** (the STOP rule below, restated
as this frame's corollary): the facade only ever gains a NAME for something
a backend already does. A facade method that cannot be one delegation is
the signal the substrate is missing a word.

**E6 — consumers import only the facade.** `ApiSurfaceTest` is this repo's
`simd-savant`: no `java.lang.foreign.*`, `java.lang.invoke.*`, or
`internal.*` in any public signature — the exact analog of "all SIMD from
`ndarray::simd`, never `simd_{arch}`, never raw intrinsics".

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
  post-merge only. **`.claude/board/README.md` is the full rule set** —
  what each board file answers, the one-writer rule and its base case,
  the non-recursion clause, and what is deliberately absent.
- A PR stacked on another PR's branch is not finished when it merges:
  if the base merged FIRST, the child's content never reaches `main`.
  Verify with `git log origin/main..origin/<base>` — it must be empty.
  (`ISSUES.md` `ISS-LGJ-STACK-TAIL-STRANDED-MINOR-8`.)
- **No model identifier in any committed artifact** (chat only). This
  file deliberately carries NO model-policy section; worker-tier
  allocation is stated by role in session briefs.

## Session start

1. This file. 2. `.claude/board/LATEST_STATE.md` +
`.claude/board/STATUS_BOARD.md` (what exists / what's in flight).
3. `.claude/plans/mask-native-navigation-correction-v1.md` if touching
navigation, masks, the contract dep, or the compute surface.
`docs/abi.md` is normative for any membrane work.

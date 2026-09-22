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

## P0 — JDK 28, Valhalla AND Panama (operator-ruled, 2026-09-19)

**LGJ MUST use JDK 28 and MUST use Valhalla and Panama.** Not a preference,
not a lab arm, not "when it finalizes" — the target toolchain of this repo.

⊘ **This SUPERSEDES the "production targets a shipped GA JDK, no preview
flags" decision** recorded in `.claude/knowledge/jdk-toolchain-facts.md`
(struck there in place, not deleted). That decision framed a release
constraint as an architectural virtue, and the framing was the drift: it let
a session write *"production lgj does not depend on Valhalla at all"* — which
is false about the design and now false about the mandate. Production has
always been Valhalla-SHAPED (the four vocabulary types are written
identity-free so the same source is a `value record` with one word changed,
proven in `valhalla-lab/docs/three-truths.md`); the ruling makes that
dependency real instead of aspirational.

**The two membranes, both required, neither optional:**

| membrane | carries | what it is here |
|---|---|---|
| **Panama** (FFM) | the VERB — operations across the boundary, `MemorySegment` reach into lance-graph's canonical bytes | the computation membrane |
| **Valhalla** (JEP 401 value classes) | the NOUN — `LaneId` / `MaskId` / `Ordinal` / `FacetId` / `RowRange` / `WideFieldMask` as flattened, identity-free semantic addresses | the storage membrane |

Neither owns storage: **lance-graph owns the only canonical copy.** Valhalla
is the storage membrane, never the storage owner. Together they are what lets
zero-copy be a first-class programming model in Java rather than a trick
hidden behind an FFI call — a view cheap enough to be ergonomically
indistinguishable from owning the thing.

**What does NOT change under this ruling:**

- **E4 still holds — the Vector API is not a production backend.** JDK 28
  finalizing it does not give Java a backend; Java has no backends. Numeric
  kernels stay in `ndarray::simd` behind the ABI (`E1`, `E2`, `E6`). A
  finalized Vector API makes the *lab* faster, not `src/main` wider.
- **`--enable-preview` is still classfile-poisoning**, so while JEP 401 is
  preview on the target JDK the flag is set repo-wide and deliberately, never
  leaked selectively — one toolchain, one flag posture, no mixed classfiles.
- **Every mask-native, zero-copy and membrane rule below is unaffected.**
  Value classes change how cheaply a descriptor is carried; they never make a
  population crossable.

**Status: IMPLEMENTED 2026-09-19, gates green.** `/opt/jdks/jdk-28+16`
(Temurin `28+16-ea`). The six vocabulary types — `LaneId`, `MaskId`,
`Ordinal`, `FacetId`, `RowRange`, `WideFieldMask` — are `public value record`,
all report `isValue() == true` at runtime, and the full suite is **409 checks,
0 failures** under `--release 28 --enable-preview` against a freshly built
`liblgj_abi.so` (abi 0.11, `ndarray::simd avx512`) — byte-for-byte the same
409 as the unflipped baseline on the same JDK. `bench/run.sh` carries the pin
and the flag.

**THE CURRENT FIGURE LIVES HERE, AND ONLY HERE.** Measured 2026-09-22 on
`origin/main` (`aef2382`): the core suite is **612 checks across 17 suites,
0 failures**, against `abi 0.12, ndarray::simd avx512, profile release`; the
four consumer mains add **153** (bricks 70, graph 68, trades 3 + 12). The 409 /
16-suite / abi-0.11 figures above and below are the 2026-09-19 and 2026-09-16
measurements, true on their dates and kept as the dated evidence they are — the
Valhalla-flip comparison in particular is only meaningful against the baseline
it was taken against, so it must NOT be restated forward. ⊘ Every OTHER site in
this file that carried a live count has had the number REMOVED rather than
re-pinned: one measurement restated in eight places goes stale in eight places,
which is exactly what happened here (the count had drifted 409 -> 612 and the
file still briefed every session with 409). A session that needs the current
number runs the suite; the command is four minutes and is written out below. Measured facts and the obtain route (most JDK hosts are
egress-blocked here; GitHub release *download* paths are not) live in
`.claude/knowledge/jdk-toolchain-facts.md`.

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
(`native/lgj-abi` imports `lance_graph_contract::{canonical_node,
class_view, facet, ontology}` ONLY — the cognitive modules compile
unconditionally; the fence is the only barrier). Spec §5 is the
canonical gate list.

> ⊘ **G11 was prose until 2026-09-03.** The sentence above WAS the fence:
> no test enforced the allowlist, `class_view_provider.rs` cited "the G11
> contract-import fence" as if one existed, and the list was already false —
> `facet::CascadeShape` (the lane-carving enum) had been imported in three
> files without the list growing. Struck: the three-module list. Now:
> `native/lgj-abi/tests/g11_contract_import_fence.rs` walks `src/` recursively and
> rejects any `lance_graph_contract::` module outside the four named,
> disable-verified red-then-green (`ISS-LGJ-G11-FENCE-WAS-PROSE`). The
> allowlist has ONE spelling in two places — the test's `ALLOWED` and this
> paragraph — and the test is what keeps them from drifting again. When the
> vocabulary provider lands (`ogar_loco::Vocabulary` beside `ClassView`,
> both resolved per classid), it grows here and there in the same commit.

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
    ⊘ **2026-09-03:** the per-access probe was measured (+52.76 ± 1.49
    ns/call, pre-registered N = 40, `epoch-recheck-v3.md` Amendment A1) and
    FAILS; per §6 that half does not ship. `ISS-LGJ-EPOCH-UNCHECKED` is
    now RESOLVED for `RowStore` as measured won't-fix — the mechanism above
    is the SHIPPED state, not a pending one — and the residue is
    `ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW`, still OPEN.

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
  result pair; `View.where()`'s `List.copyOf` of the PREDICATE chain;
  `NativePattern.plan()`'s `predicates.stream()…toList()`; and
  `Engine.groupSumI32`'s `toArray` of the group totals (minor 12 — sized by
  `groups`, the key domain the caller asked for, never by rows). None of the
  eight is a hidden proportional-to-n_rows population copy — keep this list
  exhaustive when a ninth site is added, rather than letting the
  enumeration silently go stale again.

  > **⊘ RE-AUDITED 2026-09-16 and the list WAS stale — it claimed five and
  > the tree had seven.** `View.where()` and `NativePattern.plan()` were
  > never listed. Both are legitimate, and *why* is the sharper statement of
  > the rule: they are bounded by the number of predicates the developer
  > CHAINED, i.e. by the size of the query they typed — never by the data.
  > So the invariant is not "no allocation on the Java side"; it is
  > **nothing proportional to ROWS**, and a query-shaped allocation is the
  > boring front's own size, not the substrate's. The audit that found this
  > is one grep (`long[]`, `toArray`, `copyOf`, `.stream()` across
  > `java/src/main`) and it is the check to re-run before citing the list —
  > the enumeration had already gone stale once under a sentence telling the
  > next session not to let it.
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

> **⊘ THE MIDDLE ROW IS WRONG, AND IT MISLEADS (operator-corrected
> 2026-09-14).** `Valhalla + Panama` is NOT this side's analog of ndarray's
> `cfg dispatch`. There is **no analog**, because there is nothing to
> dispatch: `ndarray` IS the SIMD polyfill, there is exactly one
> implementation of every word, and it is in Rust. Reading the row as an
> analogy invites treating Panama as a dispatch or compute layer — which is
> precisely the confabulation it produced (a "T0 owns backend realization"
> tier story invented to justify a conclusion that needed no tiers; see
> lance-graph `TECH_DEBT.md`'s ternlog storno).
>
> Valhalla and Panama are **two ORTHOGONAL guarantees**, not a pair and not
> a layer:
>
> - **Panama — computation never lives in Java.** The crossing mechanism.
>   Java hands the question across and receives the projection; the
>   decomposition of an answer never crosses. This is what E1 enforces.
> - **Valhalla — storage never lives in Java.** The orthogonal axis. Value
>   classes carry SHAPE without identity or heap storage, so the Java side
>   holds names, handles and addresses — never the bytes. This is what the
>   one-copy law, the `materialize*` naming rule, and
>   `java-surface-warden`'s "no Stream-over-hydrated-elements" all enforce
>   from different directions.
>
> **What they are FOR, stated positively** (operator, same day): *Java is
> the low-code thin surface over zero-copy, with methods that look so
> natural and still compute in lance-graph — Java just thinks it's on
> steroids without knowing why.*
>
> Sharpened 2026-09-16, and this is the sentence to keep: *"java is the low
> code intake GLOVE around the lance-graph spine — lance-graph-java just
> happens to offer the MENU TO THE TABLE in a pleasing way, using masking ops,
> offering 5 star for the price of a blink."* The menu is the product. The
> kitchen is elsewhere and the diner never sees it; what makes the menu honest
> rather than a disguise is that the work and the data genuinely never cross.
> The whole allocation, same ruling:
>
> | what | lives in | membrane that keeps Java out of it |
> |---|---|---|
> | **thinking** | lance-graph | **Panama** |
> | **SIMD** | ndarray | the `ndarray::simd` facade |
> | **storage** | lance-graph | **Valhalla** |
>
> Note both membranes name the SAME home for two different things — thinking
> and storage are both lance-graph's, and Panama and Valhalla are two
> orthogonal ways of keeping Java out of them. That is why the middle row of
> the table above is wrong: they are not one layer. The fluency of `view.where(..).hop(..)
> .count()` is real; the work and the data are both elsewhere; and Java is
> never told. Zero-copy is what makes the naturalness honest rather than a
> disguise — there is no hidden hydration behind the nice method name.
>
> Corollary for any design that reaches for this table: the isomorphism
> holds at the TOP row (facade ↔ facade) and the BOTTOM row (backends ↔ the
> Rust floor). It BREAKS in the middle. Do not reason from the middle row.

## THE JAVA SURFACE IS `sql()`, NOT THE MASK ALGEBRA (operator-ruled, 2026-09-16)

**Verbatim, in four parts:**

> *"Java doesnt use masking ops. `Mask.minus()`, `RowStore.hop()`. Lance-graph
> does. Java just sees boring `sql()` handed to duckdb (Example)."*
>
> *"nobody should ever start trying to optimize Java (except making it boring
> front)."*
>
> *"the boringness is then handed to lancegraph as zero copy."*
>
> *"and handled akin to ndarray polyfill — java doesnt know why there is
> `sql()` polyfill, we just make sure there is."*

### What this corrects, by name

⊘ The sentence three paragraphs up — *"offers the menu to the table in a
pleasing way, **using masking ops**"* — is **struck on those three words**.
The menu framing survives; the mechanism named in it does not. lance-graph
uses masking ops. Java does not, must not, and is never told they exist.

⊘ The isomorphism table's top row previously read `Java (View / Mask /
RowStore / consumers)` as the facade. **`Mask` and `RowStore` are NOT the
facade** — they are the substrate's algebra standing on the Java side of the
wall. `Mask.minus()` and `RowStore.hop()` are the operator's own two examples
of the wrong shape. The facade is the boring call a Java developer already
knows how to write.

### The polyfill relation is EXACT, and it is the whole design

This is the part that makes the ruling operational rather than stylistic.
`ndarray::simd` is a facade with **37 functions and zero shipping
instructions** — a consumer crate calls `U8x64::cmpeq_mask` and has no idea
whether AVX-512, NEON, wasm or scalar answered it. The consumer does not
know why the polyfill exists. It only knows it is there.

`sql()` is that, one tier up:

| tier | the caller writes | the caller does not know |
|---|---|---|
| consumer crate → ndarray | `U8x64::cmpeq_mask(..)` | which of six backends ran |
| Java → lance-graph | `sql("select …")` | that masks, ternlog, hops or popcounts exist at all |

**"We just make sure there is one"** is the standing obligation, and it falls
on the Rust side, never on Java. A missing `sql()` capability is a
lance-graph/ABI gap to close — exactly as a missing SIMD primitive is an
`ndarray::simd` gap to close, never a licence for a consumer to write
intrinsics. This is the MISSING-CAPABILITY STOP RULE already in this file,
now with its Java-side spelling.

### The three operational consequences

1. **Nobody optimizes Java. Ever.** The only sanctioned work on the Java side
   is making it **more boring** — more ordinary, more familiar, closer to what
   a Java developer would have written without us. A PR whose stated goal is a
   faster Java path is rejected on its goal, before its diff is read. "Boring"
   is the performance strategy: the speed is elsewhere.
2. **The boringness is handed down as zero copy.** The ordinary-looking call
   is not translated, re-encoded, or marshalled. It crosses as a NAME and the
   bytes stay put — which is what makes the boring surface honest rather than
   a disguise. Zero-copy is not an optimization applied to the glove; it is
   the condition under which a boring glove is allowed to exist.
3. **A mask concept on a public Java signature is a defect**, regardless of
   how well it works. `java-surface-warden` already blocks byte positions and
   arithmetic; this ruling adds the population algebra by name — `Mask`,
   `minus`, `hop`, `ternlog`, `popcount`, lane ids, opcodes. Those are
   T1/T2 words (see `kernel-membrane-warden`), and T3 does not speak them.

4. **Java never does materialization — and the row count is what proves it**
   (operator, same day): *"Java never does materialization. A billion rows op
   is handed behind the front and handled as a masking hattrick nobody knew
   what's coming."* This is the boring front's load-bearing property, not a
   performance note. A billion-row operation crosses as a NAME and returns a
   scalar; the billion never exists on the Java side, in any form, at any
   moment. That is why the surface can afford to be boring: `sql()` looks
   identical at 10 rows and at 10^9, because Java's work is the same at both —
   none.

   **The checkable form of "never":** every public Java call must be
   **O(1) in rows**, and the ONLY exit is a method whose name begins with
   `materialize` (today: exactly one, `Mask.materializeRows()`, O(n), stated
   in its javadoc). The naming rule is not a loophole in "never" — it is the
   mechanism that makes "never" auditable, because anything proportional to
   row count that is NOT named that way is a defect by inspection, with no
   judgement call required. Keep the exhaustive materialization-site list in
   the zero-copy section current for exactly this reason: an unnamed sixth
   site is the failure this rule exists to catch.

   **The corollary that bites hardest in review:** a Java-side loop is not
   merely slow, it is *proof that something was materialized to loop over*.
   E1 forbids the loop; this forbids the thing that made a loop expressible.
   Two statements of one rule from opposite ends.

### THE ENDGAME — why "boring" is strategy, not taste (operator, same day)

> *"endgame is make Java low code **'Bring your own software'** to beat
> palantir foundry at its own game."*

Foundry's proposition is *bring your data to our platform* — and then learn
its ontology tooling, its pipeline builder, its workshop, its idioms. The
learning curve is not a cost of the product; **it IS the product**, because
every hour a customer spends learning it is an hour of lock-in that a
competitor must refund before they can switch.

BYOS inverts exactly that: **bring your own software.** The customer keeps
their Java, their SQL, their JDBC-shaped habits, their existing build — and
the substrate is underneath without them learning a new vocabulary to reach
it. Nothing to port, nothing to adopt, nothing to unlearn if they leave.

**This is what makes each of the three consequences above non-negotiable
rather than stylistic:**

- **Novel API is the enemy, not slow API.** `Mask.minus()` is something a
  customer must LEARN; `sql()` is something they already know. Every unit of
  novelty on the Java surface is a unit of Foundry-shaped lock-in-by-curve —
  built by us, against our own pitch. That is why optimizing Java loses the
  game even when it succeeds: optimization on that side produces novel API,
  and novel API is precisely the thing BYOS promises not to require.
- **"Boring" is therefore the competitive moat, measured as absence.** The
  win condition is that a Java developer writes what they would have written
  anyway and the result arrives at substrate speed. There is no demo of this
  that looks impressive — it looks like nothing happened, which is the point.
- **The advantage must live where the customer does not have to look.** Speed
  comes from lance-graph and ndarray; the customer gets it by writing ordinary
  code. A surface that has to be studied to be fast has already conceded the
  argument, however fast it then is.

**Practical test for any proposed Java-side addition:** *would a customer who
has never read our documentation write this line by accident, from habit
alone?* If yes, it is a candidate. If it needs a paragraph of explanation, it
is Foundry's business model with our name on it — reject it and close the gap
on the Rust side instead.

### Scope — what this does NOT do

It does not delete `Mask` or `RowStore` today. They exist, they are tested,
and the four leak-on-throw fixes that landed with this ruling make existing
code correct rather than expanding it. What the ruling settles is the
DIRECTION: the boring `sql()`-shaped surface is the target, the mask algebra
is not to be grown on the Java side, and no future session may cite the
struck sentence above as licence to add another `Mask.*` verb.


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

## Never skip the Java half — check FOUR places before saying "no JDK" (2026-09-16)

**Operator ruling: document this so no future session runs without Panama at
all.** This repo's history contains commits titled *"UNVERIFIED: JDK 26 not
available in this sandbox"*, and the Java half of an ABI-minor-11 change went
to PR on that basis.

> ⊘ **The first version of this section, written earlier the same day, led with
> the apt route and was itself misleading.** Re-checked: **`/opt/jdks/jdk-26.0.2`
> was present the whole time** (26.0.2.1), the suite ran `ALL PASSED (409
> checks)` on it, and `.claude/knowledge/jdk-toolchain-facts.md` had already
> named that exact path. Nothing needed installing. The session looked at
> `java -version` and `/usr/lib/jvm` — **neither of which sees `/opt/jdks`** —
> and inferred absence. So the apt routes below are a RECOVERY path, not the
> answer; the answer is `ls /opt/jdks`.

**The rungs, in order. Report "no JDK" only after all four fail.**

```sh
ls -d /opt/jdks/*/ && for d in /opt/jdks/*/; do "$d/bin/java" -version; done   # 1
ls /usr/lib/jvm/                                                              # 2
```

Rung 1 is the production path this repo pins, and it is the one that was
missed. `which java` answers "what is on PATH", never "what is installed" — it
is not evidence about the second question. Full ladder with the
verified-by-execution table: `.claude/knowledge/jdk-toolchain-facts.md`
§ ACQUISITION LADDER; the guard is `.claude/agents/jdk-toolchain-warden.md`.

Rungs 3 and 4 are apt, and matter when `/opt/jdks` is absent — which it may
well be in a rebuilt container, since **`/opt/jdks/jdk-27` (the JEP 401
Valhalla EA build) is already GONE from this one.**

**JDK 26 — the version this repo's own commits targeted — needs ONE extra apt
source, and Ubuntu's stock archive alone will never offer it.** Add Adoptium
and it is an ordinary `apt-get install`:

```sh
# the extra source — Ubuntu noble stops at openjdk-25; Adoptium carries 8..26
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo tee /etc/apt/keyrings/adoptium.asc >/dev/null
. /etc/os-release && echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] \
https://packages.adoptium.net/artifactory/deb $VERSION_CODENAME main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-26-jdk     # -> /usr/lib/jvm/temurin-26-jdk-amd64
```

Ubuntu's own archive serves **openjdk-25** (also fine — Panama is final since
22, and the suite passes on it too):

```sh
sudo apt-get update                          # NOT optional — see the trap below
sudo apt-get install -y openjdk-25-jdk-headless
/usr/lib/jvm/java-25-openjdk-amd64/bin/java -version   # openjdk 25.0.4
```

**The trap that made this look impossible.** The pre-seeded apt index pointed
at `openjdk-25 25.0.2+10-1~24.04`, a version already withdrawn from the pool,
so the install died on a bare `404 Not Found` — which reads as *"this package
does not exist"* rather than *"your index is stale"*. `apt-get update` moved
the candidate to `25.0.4+7-1~24.04` and the install succeeded immediately.
Same shape as this workspace's other freshness traps: **a cached view reporting
absence is not evidence of absence.**

### Running the Java suite — there is no build tool, and that is deliberate

`java/src/test/.../AllTests.java` is a plain `main` that runs every suite in
one JVM and exits 0 / 1 / 2 (passed / failed / **native artifact absent, so
nothing ran** — a missing library reported as a failure would send the reader
hunting a bug that is not there). So: build the `.so`, `javac`, `java`.

```sh
# 1. the native artifact
cd native/lgj-abi && cargo build --release          # -> target/release/liblgj_abi.so

# 2. compile main + test together (58 files)
J=/usr/lib/jvm/temurin-26-jdk-amd64/bin      # or java-25-openjdk-amd64
find java/src/main java/src/test -name '*.java' > /tmp/srcs.txt
$J/javac -d /tmp/jout @/tmp/srcs.txt

# 3. run. `-Dlgj.library` is an EXPLICIT request and `Abi.locateLibrary`
#    refuses to fall back to a search path if it cannot be honoured — by
#    design, so a run can never silently measure a different artifact.
$J/java --enable-native-access=ALL-UNNAMED \
        -Dlgj.library=$PWD/target/release/liblgj_abi.so \
        -cp /tmp/jout com.adaworldapi.lancegraph.AllTests
```

**Measured 2026-09-16 on BOTH `Temurin 26.0.2.1` and `openjdk 25.0.4`:
`ALL PASSED (409 checks)`** across all 16 suites, against `abi 0.11, simd
ndarray::simd avx512, profile release` — identical on the two JDKs. The
runtime line the suite prints at the end names the ABI minor, the SIMD backend
and the library path — read it, because it is what tells you the run exercised
the artifact you meant.

### What JDK 25 does and does NOT give you

| need | reachable here? | source |
|---|---|---|
| **Panama FFM** (`java.lang.foreign`, the whole `internal/ffm` membrane) | **YES** — final since 22 | Ubuntu `openjdk-25-jdk-headless`, or Adoptium `temurin-26-jdk` |
| the full `AllTests` run | **YES**, verified on 25 AND 26 | either of the above |
| `valhalla-lab/src/stable` (records, JDK 26) | **YES** | Adoptium `temurin-26-jdk` |
| `valhalla-lab/src/valhalla` (**value** records, JDK 27 EA) | not via apt | `https://jdk.java.net/valhalla/` — reachable, `HTTP 200` |

**Only the Valhalla EA arm needs a non-apt fetch, and it is not a gate.**
Adoptium's noble repo carries 8 through 26 and stops there (`apt-cache search
temurin` shows no 27+), and mainline 27 would not help anyway — `value record`
is a Valhalla EA feature, not a mainline one. `https://jdk.java.net/valhalla/`
**is reachable through this environment's proxy** (verified `HTTP 200`), so
that build is a download away rather than a blocker. `bench/` and
`valhalla-lab/` are measurement arms, NOT gates; the merge gate is the Rust
suite, the full `AllTests` run, and the four consumer mains — all three
dispatched by the `java-suites` job in `.github/workflows/lint.yml`, and all
three reachable here. (Before that job existed they were LOCAL gates and `main`
could be red unnoticed, which it was; see `ISS-LGJ-CONSUMERS-HAVE-NO-CI-LINE`.)

**Consequence for every future session:** "the Java side could not be verified"
is no longer an acceptable status line for this repo. The Rust half
(`cargo test` in `native/lgj-abi`) and the Java half (`AllTests`, plus the four
consumer mains) are BOTH runnable in this container, and a PR that claims the Java surface is unverified
is claiming something that takes about four minutes to falsify.

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

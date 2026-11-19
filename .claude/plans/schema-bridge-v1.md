# schema-bridge-v1 — how schema meets the membrane

**Status: PROPOSAL. Nothing is implemented. No ABI change is made by this
document. No D-id.**

Date: 2026-11-19. Read against `lance-graph-java` `cb48eb4`, `lance-graph`
as checked out beside it, and OGAR `main`.

## 0. The question

An outside consumer (a JanusGraph/TinkerPop adapter, described in
`fused-hop-chain-v1.md`) wants its own stack to keep its schema words —
vertex labels, edge labels, property keys — while every byte and every
evaluation stays below the membrane. It asks what the DT64 bind store and
the DR types can hand a Java caller, and what Valhalla and Panama already
give for free, so that nothing is hand-rolled on the Java side.

This plan answers three things:

1. what crosses today (§1);
2. which DR/DT types fit a Valhalla value and which do not (§2);
3. what the smallest honest bridge is, and what it is blocked on (§3–§5).

## 1. What crosses today

**No schema crosses.** There is no export, manifest field or status that
names a field, a type, a schema revision or a digest. The 31 symbols of
`abi.md` §7 take lane ids, facet indices, classids and masks.

What does exist:

| Fact | Where it lives | Who can see it |
|---|---|---|
| Row shape (512-byte row, lane kinds, strides) | native; `lgj_lane_describe`, `lgj_row_layout_probe` | Java, as `LgjLaneDesc` read in place |
| Register groupings (carvings) | manifest, `abi.md` §17 | Java, as data, no call |
| What a classid carves into | native, process-global table, `abi.md` §16 "A classid is a GLOBAL address" | not Java |
| A class's ordered field basis | `ClassView::fields` (contract); `FixtureClassView` by default, `OgarClassView` behind the `ogar-classview` feature | not Java |
| A class's basis at an older revision | `ClassView::fields_at(class, schema_rev)`; default answers `None` | not Java |
| Field names as Java words | `Pattern`, `Field`, `Predicate` in the facade; hand-written per pattern | Java only |
| "Stable for the life of a schema version" | javadoc of `Ordinal` | a promise with no check |

The last two rows are the gap. Java's vocabulary and the native basis are
two copies of one fact. Nothing fails if they disagree. `abi.md` §17
describes the same defect for carvings ("three copies of one fact is … a
correctness problem with no falsifier") and fixed it by serving the fact
as data.

## 2. DR/DT types against the Valhalla and Panama constants

The lab's pinned result (`docs/valhalla-lab.md`,
`valhalla-lab/docs/three-truths.md`): array flattening holds up to an
8-byte payload and stops past it. Panama's constants: primitives pass in
registers, a fixed `#[repr(C)]` struct is read in place through a
`MemorySegment`, and anything with a lifetime is a generation-checked
64-bit handle (`abi.md` §4).

Sorting the DT64/DR types by those two constants:

| Type (lance-graph) | Representation | Java form that costs nothing |
|---|---|---|
| `SchemaRev`, `MethodId`, `SigRev` | `u32` newtype | 4-byte value record; flattens |
| `TileId`, `RelNo` | `u32` alias | 4-byte value record; flattens |
| `RouteVersion` | `u64` newtype | 8-byte value record; flattens |
| `Scope` | `{ id: u64 }` | 8-byte value record; flattens |
| `RouteAt` | `u16` + `RouteVersion` | does not flatten; two primitive arguments |
| `CoordSpace` | 2 × `u64` | does not flatten; two primitive arguments, or a struct read in place |
| `FrameRevisions` | 3 × `u64` | struct read in place |
| `LaneAddr` | `CoordSpace` + `u16` + `Scope` | struct read in place; "An address; it carries no value" |
| `Frame` | two `Weak` pointers, no public constructor | handle only |
| `MethodRef` | contains `Stamp { issuer: Weak<()>, binding: u128 }` | handle only |
| `BasisDigest` | `u128`, `pub(crate)` | not nameable outside its crate; compare natively |
| field and class names | `String` | never cross (`abi.md` §10) |

Three consequences:

- **Seven of the types are already the shape of the existing vocabulary
  records.** `SchemaRev` as a Java value record is the same kind of thing
  as `Ordinal` and `LaneId`. No mechanism is missing.
- **`Frame` and `MethodRef` cannot be values.** They carry identity of
  the store that issued them. The existing handle table is the only
  honest carrier, and their checks (`FrameRefusal`, `BindingMismatch`)
  must run natively.
- **A digest comparison cannot be done in Java.** `BasisDigest` is
  crate-private and 128 bits wide. Java can hold a revision number and
  ask; it cannot verify.

So "what superpowers do Valhalla and Panama bring to the DR/DT bridge" has
a short answer: every DR/DT word that is an address or a revision fits a
flattened value or a register argument unchanged, and every DR/DT word
that is a proof stays native behind a handle. Nothing in between needs a
Java-side structure.

## 3. Options

### A. Change nothing

Java keeps hand-written `Pattern`/`Field` vocabularies. A consumer that
has its own schema words (labels, keys) keeps a Java map from its word to
a classid or a `FacetId` and resolves it before the first native call.
This is what the JanusGraph adapter does today.

- Fits every rule. No ABI change.
- The gap of §1 stays: no check that the Java words match the native
  basis.

### B. Serve the witness as data (manifest), no symbol

Because a classid is a global address (§16), the class basis is
process-global, and one witness for the whole class view is meaningful.
The manifest grows one field: a revision or digest of the bound
`ClassView`. A Java vocabulary generated at that revision compares at
load and refuses to proceed on mismatch. Same shape as §17: no symbol, no
status, no call.

- Fits `abi.md` §1 and §6: it is lifecycle data.
- **Blocked upstream.** The contract's `ClassView` has no whole-view
  revision or digest (searched `class_view.rs` and `ogar_codebook.rs`:
  only per-class `fields_at`, which defaults to `None`). The STOP rule
  applies: the accessor lands in `lance-graph-contract` first.
- Coarse: any class changing invalidates every vocabulary. Acceptable for
  a first version; a per-class answer is option C.

### C. A lifecycle describe call per class

`lgj_class_describe(classid, out *LgjClassDesc)`, in the shape of
`lgj_lane_describe` and `lgj_resource_info`: fixed-size struct, no
strings, lifecycle-only. Candidate fields, all already resolvable inside
the G11 fence: field count (`fields(class).len()`), cascade shape,
`ValueSchema`, and, once upstream provides it, a schema revision.

- Fits §6 as lifecycle. Adds a symbol, so it needs the full §20/§21
  argument: what was wrong before, contract, Java spelling, a disable
  table.
- Without a revision it only reports counts. A field count is a weak
  witness (two bases of equal length pass). **Not worth a symbol until
  the revision exists.**

### D. Bind through the DT64 lane socket

Open a store through a `LaneSource`, run `BindStore::bind_lanes` natively
at open, map `LaneRefusal` to status codes, and hand Java the same
`LaneId`s it has today.

This is the full bridge, and it is the one that gives revision-checked
binding, route checks and frame checks. It is blocked on three things
that are not this repo's to decide:

1. `lance-graph-dt64` is not a dependency of `lgj-abi`, and `CLAUDE.md`
   says "the full lance-graph ENGINE is never a dependency". Whether
   dt64 counts as the engine is the owner's reading. (The G11 fence is a
   different rule: it limits which `lance_graph_contract` modules may be
   imported, and does not speak about other crates.)
2. The one existing adapter, `AbiLanes`
   (`lance-graph-report/src/lane_source.rs`), states of itself: "Reader
   scope: NOT production-admitted", `I32`/`U32` lanes only, and its only
   constructor is `#[cfg(test)]`.
3. `LaneRefusal` and `Refusal` together carry at least thirty conditions.
   The membrane has 19 error status codes. Collapsing them loses the reason;
   carrying them needs a ruling on how refusals are reported.

### E. Names resolved natively (Quack `Binder`)

`lance-graph-quack` has `Draft::bind(&dyn Binder)`, which resolves a
table name, field names and literals to ids and "never mints". It is the
ready-made name resolver, and it takes `&str`.

- **Does not fit.** `abi.md` §10: "No strings across the boundary".
  Passing names in would be a new kind of crossing, not an extension of
  the Import exception (which covers row selections).
- `lance-graph-quack` is deliberately a dev-dependency here ("the
  membrane must not depend on a consumer of the IR it serves").
- Recorded as rejected unless the owner rules otherwise.

### What is not a candidate

- **VRL.** In `lance-graph` it exists as the lab crate
  `vrl-recipe-probe`: a `Recipe` holds field positions, drop terms and
  output names, "no rows, only what to read and where". It is a query
  shape, not a schema carrier, and its output names are `String`s.
- **Magic sets.** Not implemented in `lance-graph`. Not relevant to
  carrying schema.
- **A Java-side registry of DT64 bindings.** That is a second owner of
  the binding (E1, and the facade plan's statement that Java is not a
  "codebook, object-model, method-identity" dependency).

## 4. Recommendation

**A now. B when the contract grows a class-view revision. D is the
destination and is an upstream decision.**

Concretely:

1. No change in this repo.
2. Ask the `lance-graph-contract` owner for one accessor: a revision or
   digest of a `ClassView` as a whole (or per class). That is the single
   missing word; with it, B is a manifest field and a load-time
   comparison.
3. Until then, a consumer's vocabulary is generated or hand-written
   against a named OGAR commit, and the mismatch risk is stated in its
   own documentation. It is not papered over with a field-count check.
4. D is raised with the DT64 owner as a question, not as a request: is
   the lane socket meant to be the way a foreign runtime opens a store,
   and if so, what admits a reader to production.

**Late binding of names is already the design.** The row shape is fixed
by the contract and served as data before any name is known (§16, §17).
Names attach at the Java API edge and nowhere else. What is missing is
only the witness that the names were attached to the right basis.

## 5. A point the consumer must absorb

A consumer that wants "a profile per dataset" (one dataset reads its
classids as JanusGraph labels, another as something else) meets §16
directly: "the same classid means the same class in every SoA". A profile
can choose **which classids it uses** and **which of its own words map to
them**. It cannot give a classid a second meaning per dataset. The
mapping from a foreign word to a classid is the consumer's table, on the
Java side of the API, outside the membrane.

OGAR reserves the `0xC0XX` band for `JavaRuntime`. Whether foreign-stack
vocabularies belong in a band of their own is an OGAR codebook ruling.
(The band's comment reads "Panama FFM" in OGAR and "Panama FFM alone" in
`lance-graph-contract/src/ogar_codebook.rs`; `DISCOVERY-MAP.md` says
"Panama + Valhalla". One of them is stale.)

## 6. Open questions for the owner

1. Is a whole-view revision on `ClassView` acceptable upstream, or must
   the witness be per class?
2. If B lands, does a mismatch refuse the load, or only the vocabularies
   generated at another revision?
3. Is D wanted at all for this repo, given that the engine is never a
   dependency?
4. If D: how are DT64 refusals reported through 19 error status codes?
5. Should the vocabulary records for `SchemaRev`, `RouteVersion` and
   `Scope` exist in the facade before any call uses them? (This plan says
   no: a record with no call is a word with no meaning.)
6. Does the OGAR codebook want a band for foreign-stack vocabularies?

## 7. What this plan does not claim

- Nothing here was measured. No timing, no allocation figure.
- The DT64 refusal lists were read to variant names; not every variant
  was read.
- Consumers of `LocalMap` in `lance-graph` were not enumerated.
- "Blocked upstream" in B rests on a text search of two contract files
  for a revision or digest accessor, not on the owner's word.

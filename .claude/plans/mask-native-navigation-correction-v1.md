# mask-native-navigation-correction-v1 — SPEC v3 (A2-RATIFIED)

> **Status: RATIFIED v3 (2026-08-18) — full 5+3 council complete: 5
> savants (44 findings, Phase 2 consolidated), operator A2 verdict
> amendments (v2.1), 3 brutal reviewers on the combined draft (1 P0
> resolved, 6 P1 + ~20 P2 applied — ledger §7). This is the executable
> spec for wave D-LGJ-W8. A3 freeze commits it as PR-0.**
>
> **COMBINED DRAFT (operator A1 ARCHITECTURE RULING, 2026-08-18):**
> Part I (§0-§6) = the mask-native correction, consolidated v2 after the
> 5-savant pass. Part II (§8-§14) = the 64K parallel-SoA-compute /
> deterministic-placement / batch-publication half, grounded in the
> six-lens lance-graph spine audit. §7 (change ledger) closes the file.
> The A2 council (3 brutal reviewers) runs on this COMBINED draft only,
> after the operator's architecture review of the §25 15-point response.
>
> Wave id: **D-LGJ-W8**. Executes the operator's CORRECTION WAVE directive
> (2026-08-18) + the RULING CLARIFICATION (same day, verbatim quotes in §1)
> + the A1 ARCHITECTURE RULING (same day: restore mask-native navigation
> AND preserve the "proven 64K parallel SoA / batch-version compute
> model" — the operator's wording; §8 grades the halves separately:
> parallel compute PROVEN in-tree via EXP-KIA, batch-version publication
> SHIPPED-PROVISIONAL with the sole writer not yet production-wired).
> A0 audit: 6-lens fleet, verdict CONFIRMED (session record; storno lands in
> EPIPHANIES with the freeze commit). This spec is the A1 deliverable; A2 =
> the 5+3 council on this text; A3 = freeze (commit spec + root CLAUDE.md +
> board entries); A4 = dispatch implementation workers; A5 = orchestrator
> integrates + runs the §5 gates.

## 0. The correction in one sentence

`consumers/graph`'s `Graph` — and every future navigation surface — must
carry its population as a native **Mask**, its facet participation as the
contract's **FieldMask/WideFieldMask**, and its semantics as
**ClassView-governed** decode, from the `lance-graph-contract` crate; the
`long[]`/`TreeSet<Long>`/per-row-payload-loop implementation of PR #18 is
demoted to the scalar reference oracle it always should have been.

## 1. FROZEN DECISIONS (the council may flag VIOLATES with evidence; never reopen on taste)

1. **Operator ruling (2026-08-18, RULING CLARIFICATION), verbatim:**
   - *"The separation of `lance-graph-contract` from full `lance-graph` was
     intentional blast-radius containment, not semantic optionality."*
   - *"do not frame ClassView/WideFieldMask integration as an optional later
     enhancement after the mask correction. The correction arc may be
     staged, but it is one architectural closure."*
   - *"`ClassView`'s resolver/provider remains late-bound by design. The
     contract defines the law; an ontology/cache/provider supplies the
     answers. Do not pull the full `lance-graph` engine into
     `lance-graph-java` merely to obtain that law."*
   - *"The correction is not complete when `Graph` merely uses `Mask`; it
     is complete when Java ergonomics navigate the same contract-governed
     ClassView/WideFieldMask/Mask substrate without row hydration."*
2. **Operator CORRECTION WAVE §0/§26:** WHERE MAY LOOK LIKE WHERE, IT MUST
   EXECUTE LIKE MASK; HOP MUST EXECUTE AS MASK × CLASSVIEW/WIDEFIELDMASK →
   MASK. Java-surface convenience must never dictate substrate
   representation.
3. **Operator §3:** zero-serialization is NOT sufficient. Forbidden as
   normal execution state: row-population hydration, row-id frontier
   materialisation, per-row navigation loops. `long[]` of selected rows IS
   a materialised population.
4. **Operator §7:** a rows→Mask import may exist ONLY as an explicitly
   named escape hatch / external-selection import — never the internal
   currency of where/hop/authorize/navigate.
5. **Operator §10:** row IDs are produced only by an explicit terminal
   whose NAME makes materialisation visible.
6. **Operator §21:** PR #18 is preserved as the scalar/reference oracle,
   not destroyed.
7. **Operator §22:** execution placement is empirical — the invariant is
   zero-copy / mask-native / ClassView-aware, not "must execute in Rust."
8. **Operator §1 + A0 audit lens B:** `View.where(...)` is the CORRECT
   ergonomic precedent (descriptor append, 0 crossings composing, 1 fused
   crossing at terminal — `View.java:59-65`, `LazinessTest`,
   `lgj_plan_eval`). It is not touched.
9. **`wave-consumer-graph.md:24-27` (D1b's own rule):** a new ABI symbol
   goes through the substrate wave process FIRST as its own W-tier change —
   the consumer never grows the membrane.
10. **`docs/abi.md` §6:** every ABI function is bulk (work ∝ n_rows) or
    lifecycle. §8: all SIMD from `ndarray::simd`; no raw intrinsics in
    lgj-abi. §2: additive change ⇒ minor bump; older Java loads.
11. **`ApiSurfaceTest` FORBIDDEN list stands** (`ApiSurfaceTest.java:37-41`):
    no `java.lang.foreign.*`, `java.lang.invoke.*`, or
    `com.adaworldapi.lancegraph.internal.*` in any public signature.
12. **ndarray CLAUDE.md Hard Rules:** new public `pub fn` in `src/simd_*.rs`
    follows the W1a consumer contract (all backends, parity test, tail-bit
    semantics documented). `mask_and`/`mask_or` at `simd_int_ops.rs:755/787`
    are the shape precedent. **⊘ CARVE-OUT (council S2-7, ruled in §3.4):**
    the struct-method litmus is deviated for the ONE pair
    `mask_andnot`/`mask_andnot_assign` (free-fn family shape; all other
    W1a criteria in full) — see §3.4's deviation block. The rule
    otherwise stands unamended; a worker briefed off this section reads
    the carve-out here, not three sections later.
13. **lance-graph CLAUDE.md CANON:** the 512-byte row stride is canon; the
    12-byte facet payload is a content-blind register whose readings the
    classid's ClassView holds; `EdgeCodecFlavor` is resolved via
    `ClassView::edge_codec_flavor`, never assumed.
14. **I-LEGACY-API-FEATURE-GATED** (lance-graph iron rule): additive ABI
    growth must leave every existing symbol's semantics untouched; version
    gates on anything a older/newer pairing could misread.
15. **Board discipline:** EPIPHANIES/PR_ARC append-only; storno cites what
    it corrects; board updates land in the same commit as the change; the
    squash sha is recorded post-merge only.
16. **Model policy (operator, this session), stated by ROLE** (A2
    warden P0: the "no model identifier in committed artifacts" rule
    applies to this spec itself, which PR-0 commits): the grindwork
    tier for bounded implementation workers; the strongest tier for
    architecture/spec/review; the floor tier is never used for
    synthesis, drafting, review, or any file edit.

## 2. INPUT INVENTORY (verified this session — A0 audit + orchestrator spot-checks)

**The drift (to migrate):**
- `consumers/graph/src/main/java/com/adaworldapi/graph/Graph.java:70`
  `private final long[] rows;` — row-population field state.
- `Graph.java:130-148` `hop()` — 1× `facetMatches` then per-row Java loop:
  `view.matchesOf(row)` → `payloadHi32At`/`payloadLow64At` →
  `TreeSet<Long>` → `toArray`.
- `Graph.java:154` `minus(long... excludedRows)` — second external row-id
  entry point (A0 falsified "from() is the only import").
- `Graph.java:175-177` `rows()` — unnamed materialising getter.
- `Graph.java:18-19` javadoc — "a deliberate simplification … not a
  workaround" (the storno target). **Anchor history (A2):** v1 said
  :18-19; council S3-1 "corrected" it to :19-20; both A2 reviewers
  re-verified and the orchestrator read the file — the phrase opens at
  the end of :18, so the span IS :18-19. S3-1's correction is itself
  stornoed (§7); §3.9 and this line now agree.
- `GraphHopTest.java:459` — reflective allowlist hard-codes `long[]` as the
  only non-scalar egress (the drift enforced by its own guard).
- `GraphHopTest.java:321-331` — crossing literals 2/1/1 pinned to the
  current implementation's rawLane first-touch.

**The correct precedent (untouched):** `View.java:59-65`,
`NativePattern.java:173` → `Engine.evaluateFused` → `Downcalls.planEval`
(one crossing); `Mask.java` public surface = exactly
`count/id/source/isOpen/close`, package-private ctor.

**Substrate facts:**
- `native/lgj-abi/src/exports.rs` — 19 symbols, ABI 0.3. Mask lane is
  `READABLE|WRITABLE|CONTIGUOUS` (`exports.rs:397`, `abi.md:310`);
  writable path reachable only via `internal.ffm.Engine.describeMask`
  (callers today: bench/, valhalla-lab/ only).
- `native/lgj-abi/src/registry.rs:56-73` — mask words are native-owned
  `Box<[u64]>` behind `RwLock` (`Payload::Mask`).
- Mask ops: `lgj_mask_and`/`lgj_mask_or`/`lgj_mask_count` ONLY — **no
  and-not/complement anywhere in the lgj Mask/registry system or its
  ndarray backing** (exports.rs:518-539, abi.md §7,
  `ndarray/src/simd_int_ops.rs:755-841` all confirmed). Scope note
  (council S1-2): blasgraph carries the GraphBLAS complement pattern —
  `BitVec::not` (types.rs:165) composing with `and()`, and
  `DescValue::Complement`/`complement_mask` (descriptor.rs:26-32,113-114)
  — a two-op composition on a different fixed-width BitVec type;
  conceptual precedent for a&!b semantics, NOT reusable for the
  handle-based `Payload::Mask` system.
- `rowstore.rs:168-215` + `abi.md` §12 — structured-edge decode:
  `payload_hi32 == 0` marker, `payload_lo64` = LE target row.
- Status codes: −1..−13 + −99 (`abi.rs:64-97`).
- Manifest symbol count is doc-tracked in `abi.md` §1/§7 (currently 19).
- Eager downcall resolution: `Downcalls` resolves ALL handles at class
  init — a minor-N Java against a minor-(N−1) `.so` fails EVERY suite at
  clinit (measured, D-LGJ-W6). Rebuild-first discipline is mandatory.

**Contract facts (`/home/user/lance-graph/crates/lance-graph-contract`):**
- `Cargo.toml`: **zero runtime deps** (serde_yaml/glob are build.rs-only;
  criterion dev-only). Features: `guid-v3-tail` default-on →
  `guid-v2-tail`; crate compiles `--no-default-features`.
- `class_view.rs:54` `pub type ClassId = u16`; **`rbac.rs:103`
  `pub type ClassId = u32`** — two widths coexist in the contract itself.
- `class_view.rs:74-166` `FieldMask(u64)` — EMPTY/FULL/with/has/count/
  intersect/union/inherit, all `const fn`.
- `class_view.rs:221-378` `WideFieldMask(WideRepr{Small(u64),Wide(Box<[u64]>)})`
  — EMPTY/with/from_positions/has/count/is_empty at :221-338;
  `full_for` :338, `intersect` :363-368, `union` :373-378 (range per
  council S3-1, `full_for` anchor re-corrected per A2); `From<FieldMask>`.
- `class_view.rs:903+` `trait ClassView` — required: `fields(ClassId) ->
  &[FieldRef]`, `template`, `dolce_category_id`; defaulted: `project`,
  `render_rows`, `facet_rows(ClassId, FieldMask, &[u8;12])`,
  `edge_codec_flavor(ClassId) -> EdgeCodecFlavor` (default `CoarseOnly`,
  line 1109).
- `ontology.rs:467` `FieldRef { predicate_iri: String, label: String }`.
- `canonical_node.rs:671-683` `EdgeCodecFlavor::{CoarseOnly=0,
  CoarseResidue=1, Pq32x4=2}`.
- **No primitive anywhere** (lance-graph or ndarray) shaped
  `(row-mask, facet/classid predicate, payload decode) → row-mask` over the
  512B/32-facet layout — a confirmed GAP (A0 lens C6). blasgraph's
  `mxv/vxm/multi_hop` (matrix.rs:230/269, typed_graph.rs:82) prove the
  Rust spine already treats traversal as vector×semiring; the contract's
  `screens_reachable_from` (class_view.rs:701) is position×edges→mask
  (a u8 root seeding `WideFieldMask::EMPTY.with(root)`, fixpoint over UI
  nav edges — characterization corrected per council S3-1), not a full
  mask×edges→mask hop.
- lance-graph-java currently has **zero** dependency on the contract
  (lgj-abi deps: ndarray only).

**Measured placement evidence (do not re-litigate, cite):** D-LGJ-G
(Vector API wins 56.4×→1.3× on contiguous single-predicate); D-LGJ-W4 (on
the REAL 32-facet strided layout the margin collapses to 2.51×/1.92×/1.14×
and converges to memory bandwidth).

## 3. THE COMMITTED RESOLUTION

### 3.1 The three currencies (normative, repo-wide)

| Currency | Question | Carrier | Owner of the law |
|---|---|---|---|
| **ClassView** | what does this classid/facet family MEAN; how are payloads decoded | native-side provider implementing `lance_graph_contract::class_view::ClassView`; **late-bound** | `lance-graph-contract` |
| **WideFieldMask / FieldMask** | which fields/facets participate | contract `FieldMask(u64)` in kernels (32 facets fit the Small tier); Java mirror value type `WideFieldMask` | `lance-graph-contract` |
| **Mask** | which rows/population | native `Box<[u64]>` behind the existing generation-checked handle | lgj-abi (shape already canon) |

Navigation = `(Mask, ClassView-governed op, FieldMask participation) → Mask`,
zero-copy, until an **explicitly named** terminal.

### 3.2 Contract dependency (D-1)

`native/lgj-abi/Cargo.toml` adds:

```toml
lance-graph-contract = { path = "../../../lance-graph/crates/lance-graph-contract", default-features = false }
```

`default-features = false`: lgj mints no GUIDs; `class_view` +
`canonical_node` type surface is unconditional. The full `lance-graph`
engine is **never** added (frozen §1.1). Consequence acknowledged: the
contract's `build.rs` (manifest codegen) runs in lgj-abi builds; it has no
runtime footprint.

**Classid width pin:** the lgj wire keeps `u32` classids (canon 8-hex;
matches `rbac.rs:103` and the existing `lgj_op_eq_classid`). The
`class_view::ClassId = u16` conversion happens at the ClassView-consult
boundary with an explicit bounds check; the u16/u32 capacity tension is an
UPSTREAM known issue (MedCare-rs commitment #10 names it), surfaced here,
not resolved here.

### 3.3 The law provider (D-3): `FixtureClassView`

`native/lgj-abi/src/class_view_provider.rs` (new):

- `struct FixtureClassView` implementing the contract `ClassView` trait for
  the deterministic fixture domain: `fields()` = 32 `FieldRef`s
  (`predicate_iri: "lgj:facet/N"`, `label: "facetN"`) served from a
  `OnceLock`'d static; `template()` = default; `dolce_category_id()` = 0;
  `edge_codec_flavor()` = default (`CoarseOnly` — unused by mode-0 decode,
  see 3.4). Idiom precedent (council S1-3): mirror the contract's OWN
  test fixtures — `FakeClasses` (class_view.rs:1303) and `TestClasses`
  (selection.rs:473), both `HashMap<ClassId, Vec<FieldRef>>`-backed; both
  are `#[cfg(test)]`-private and non-exported, so a new impl is warranted,
  not a duplicate. OGAR's `OgarClassView` (ogar-class-view/src/lib.rs:376)
  is real/vocab-backed and NOT a substitute for a deterministic 32-facet
  test domain.
- Two provider reads the hop consults (free fns on the provider TODAY,
  because the contract trait has no `edge_slots` method — this is a **named
  contract seam**, §4-NG6): `edge_participation(classid) -> FieldMask`
  (fixture: `FieldMask::FULL` restricted to 32 bits) and
  `decode_mode(classid) -> u32` (fixture: `0` for all classids).
- Module-level provider today; a per-resource provider slot on the
  registry entry is the named seam for when a real ontology/cache provider
  arrives (§4-NG3). The LAW (trait + types) is the contract's; only the
  ANSWERS are fixture-local. This is the ruling's late-binding, literally.

### 3.4 New ABI surface (D-4) — ABI minor 3→4, `abi.md` §13

Two symbols (manifest count 19→21), one status code:

```
i32 lgj_mask_andnot(u64 a, u64 b, u64 dst)
```
`dst = a & !b`, word-wise; same parent/row-count compatibility rules as
`lgj_mask_and`; aliasing permitted (same read-before-write-per-word
property). **Tail rule:** bits past `n_rows` are re-zeroed after the
complement (a naive `!b` sets tail bits; the kernel must clear them —
pre-registered falsifier §5-G7). Kernel routes through NEW ndarray
primitives `mask_andnot` / `mask_andnot_assign` (`src/simd_int_ops.rs`,
same shape/parity/backends discipline as the existing mask-op family:
`mask_and` :755, `mask_or` :787, `mask_and_assign` :815,
`mask_or_assign` :841 — ndarray PR merges FIRST).

**Explicit W1a deviation (council S2-7 VIOLATES — surfaced, not silently
overridden):** the mask-op family is FREE FUNCTIONS re-exported through
`ndarray::simd` (src/simd.rs:694-698), a shape the W1a litmus test
("free function = reject; the surface fragments"
vertical-simd-consumer-contract.md:325-326) rejects for NEW primitives.
The new pair follows the FAMILY shape anyway: a lone struct-method member
beside four free-fn siblings would fragment the exact polyfill surface
the simd.rs re-export comment exists to protect. All other W1a criteria
(parity test vs scalar reference, tail-bit semantics documented, all
backends via the polyfill dispatch) apply in full. The deviation is
recorded in ndarray's `.claude/blackboard.md` in the PR-N commit and
flagged to the operator in the A2 response — frozen decision §1.12 is
thereby amended for this pair, not ignored.

**Re-export is mandatory (council S4):** `ndarray/src/simd.rs:694-698`'s
`pub use crate::simd_int_ops::{...}` list MUST gain `mask_andnot,
mask_andnot_assign` in the same PR-N — lgj-abi consumes `ndarray::simd::*`
ONLY (Hard Rule #12's sanctioned path); without the re-export the new
primitives are unreachable through the polyfill.

```
i32 lgj_hop(u64 store, u32 edge_classid, u64 facet_mask,
            u32 decode_mode, u64 src_mask, u64 dst_mask)
```
Bulk (∝ n_rows·facets — §6-conformant): snapshot `src_mask` words
(aliasing `dst==src` therefore permitted); OVERWRITE `dst_mask` with the
one-hop reachable set: for each set row `r`, for each facet `f` where
`facet_mask` bit `f` is set: if `classid(r,f) == edge_classid` and the
mode's decode yields a valid target `t < n_rows`, set bit `t`.
`facet_mask` is the wire form of the contract `FieldMask` (u64; bits ≥ 32
ignored for this store). The EFFECTIVE participation is
`facet_mask ∩ provider.edge_participation(classid)`.
`decode_mode 0` = the §12 fixture convention (`hi32 == 0` marker,
`lo64` target). Modes `1..=3` are RESERVED to mirror
`EdgeCodecFlavor as u32 + 1` and return the new status
`LGJ_ERR_UNSUPPORTED_DECODE_MODE = -14` until real class data lands.
**Mode-promotion trigger (council S5-5):** modes 1..=3 are implemented
in the wave that lands the per-resource ClassView provider slot (the
NG3 seam — i.e. the GridLake/compute wave or the first real-ontology
integration, whichever lands first); the RESERVED fence fails loudly
until then, unlike #18's long[] which never errored on anything.

**Kernel v1 composition (respecified per council S2-2):** the
classid-match sub-step routes through the EXISTING sanctioned primitive
— `kernels.rs::simd_rowstore_classid_mask`
(`ndarray::simd::eq_u32_strided_to_mask`, the same kernel already behind
`lgj_op_eq_classid`, exports.rs:697-703) into a reused scratch word
buffer, once per participating facet; then the set bits of
`src ∩ classid-mask` are walked SCALAR for decode + scatter only (the
scatter genuinely has no ndarray primitive — A0 lens F3 + council S2-2).
§8-clean by composition, not by going all-scalar: S2-2 found an
all-scalar kernel would duplicate work an existing `ndarray::simd`
primitive already does, in tension with §8's "it is the architecture"
framing. Wider SIMD/Vector-API optimization stays the bench-gated
follow-up (§3.8).

**Aliasing/deadlock discipline (council S3-4 — MANDATORY, normative):**
`lock_masks_ordered` (registry.rs:385-398) has NO built-in aliasing
dedup; `mask_binop` (exports.rs:406-511) avoids same-thread
double-write-lock deadlock ONLY via its explicit `Arc::ptr_eq` branches
BEFORE locking. `lgj_hop` must replicate that dedup-before-lock pattern
(or snapshot the src words under a read lock that is fully RELEASED
before taking the dst write lock). A naive two-entry
`lock_masks_ordered(&[dst, src])` under `dst == src` aliasing DEADLOCKS.
Pre-registered as gate §5-G6(e).

**Bounds-before-cast (council S3-6, normative):** `t < n_rows` is
checked with both sides as u64 BEFORE any `t as usize` bit-index cast;
the ordering is part of the spec, not an implementation detail.

Both symbols: `guard`-wrapped, write-only-on-OK, `#[no_mangle]`, doc'd in
`abi.md` §13 with the §11/§12 house style; `LGJ_ABI_MINOR = 4` with the
dated doc entry; registry fns follow `open_rowstore*`'s shape.

### 3.5 Java core facade (D-5) — the public Mask-native op surface

All new public surface is `ApiSurfaceTest`-clean (no FFM types) and gated
`Abi.requireMinor(4)` at the Engine entry points (the minor-2 precedent):

- **`WideFieldMask`** (new public value type, core package): the Java
  mirror of the contract currency. Factories `allFacets()`,
  `ofFacets(int... positions)`, and **`ofMatchBits(int matchBits)`**
  (council S5-2: the bridge from `FacetMatchView.matchesOf(row)`'s raw
  int bitset — without it the repo carries THREE facet-adjacent
  vocabularies with no stated conversion: scalar `FacetId`, raw int
  bitset, typed `WideFieldMask`); `has(int)`, `count()`. Backed by one
  `long` (the Small tier — documented as mirroring
  `lance_graph_contract::class_view::WideFieldMask`, which is itself
  `Small(u64)` until promoted; this store has 32 facets, so only the low
  32 bits are significant here and the width story is documented on the
  type). The Small→Wide promotion seam is documented ON the type's
  javadoc (NG12): this mirror carries the Small tier ONLY; >64-facet
  surfaces need the Wide promotion — the ceiling is stated, not
  discovered. Record-style, Valhalla-ready, like `FacetId`.
- **`RowStore.hop(int edgeClassid, WideFieldMask facets, Mask src) -> Mask`**
  — THE §5 conceptual op on the substrate facade: creates the dst mask,
  one `lgj_hop` crossing, returns it. Overload
  `hop(int edgeClassid, Mask src)` = `allFacets()`.
- **`RowStore.importRows(long... rows) -> Mask`** — the ONE named
  external-selection import (operator §7 classification in its javadoc:
  escape hatch / external-selection import / test utility — NEVER the
  internal currency). Implementation: `createMask(EMPTY)` + in-process
  word writes through the internal writable mask lane (zero new ABI; the
  capability `exports.rs:353` says WRITABLE was for). Named `importRows`
  so the boundary crossing is lexically visible.
- **`Mask.minus(Mask other) -> Mask`** — new mask = `this & !other`, one
  `lgj_mask_andnot` crossing. The only mask-algebra surfacing this wave
  (public and/or = non-goal §4-NG7).
- **`Mask.materializeRows() -> long[]`** — the ONE named materialising
  terminal (operator §10): reads the mask's word lane in-process
  (describe cached per Mask; zero crossings steady-state), expands set
  bits to row indices AT THE BOUNDARY ONLY. Javadoc states the O(n) cost
  and the naming rule.
- **`Status.java` mirror (council S4):** `internal.ffm.Status` gains
  `UNSUPPORTED_DECODE_MODE(-14, ...)` so the new status surfaces as a
  named exception; verified (S3-7) that an OLD Java build seeing -14
  degrades gracefully to `NativeCallException("UNKNOWN_STATUS")` — the
  additive-minor discipline holds in both directions.
- **Per-row accessors reclassified:** `RowStore.classidAt` /
  `payloadLow64At` / `payloadHi32At` and `FacetMatchView.matchesOf` stay
  public; javadoc gains the normative line: *"low-level inspection /
  diagnostics. High-level query or traversal implementations MUST NOT use
  these as their execution engine — see the root CLAUDE.md mask-native
  policy."* `FacetMatchView.cardinality` unchanged (terminal aggregate
  over an already-crossed bulk result — A0 lens E5).

### 3.6 `consumers/graph` migration (D-6)

`Graph` becomes mask-native, same fluent surface:

- State: `private final RowStore store; private final Mask frontier;`
  (frontier `null`/empty for `open()`); **no `long[]`, no `Collection`**.
- `from(long... seedRows)` → `store.importRows(...)` (the named import).
- **`from(Mask population)`** (new) — predicate-born seeding:
  `Graph.open(store).from(store.maskOfFacetClass(f, c)).hop(...)` is the
  flagship zero-row-ids-anywhere composition, tested as such.
  Verified (council S4): `RowStore.maskOfFacetClass` ALREADY EXISTS
  (RowStore.java:117) — the flagship needs zero new RowStore capability
  beyond `hop`/`importRows`.
- `hop(int edgeClassid)` / `hop(int, WideFieldMask)` →
  `store.hop(...)` — one crossing, Mask in, Mask out.
- `minus(Graph other)` → `frontier.minus(other.frontier)`;
  **`minus(Mask other)`** (new overload). The `minus(long... rows)`
  varargs overload is **REMOVED from the migrated surface** (A2
  overclaim-auditor: §2 itself named it the second unnamed row-id entry
  point; keeping the signature while delegating internally would apply
  the §1.4 naming discipline to `RowStore.importRows` but not to the
  consumer surface). A caller composes
  `.minus(store.importRows(rows))`, making the import lexically visible
  at the call site. `from(long... seedRows)` REMAINS as the ONE
  documented external-selection entry on `Graph` (external row ids are
  the legitimate import case; its javadoc carries the §1.4
  classification; it delegates to the named `importRows`); G1's
  parameter rule encodes this structurally.
- `count()` → `frontier.count()` (native popcount).
- `rows()` is **renamed `materializeRows()`** delegating to the Mask
  terminal. No unnamed materialiser survives.
- Lifecycle: each `Graph` owns its frontier Mask; `close()` closes it
  (no longer a no-op). Intermediate chain steps hold masks until their
  Graph or the store closes — allocation is words-proportional
  (`n_rows/64` per retained step), which IS the sanctioned currency;
  documented in the class javadoc. Immutable chaining is preserved.
- `Edge` unchanged (already a classid schema; its ClassView honesty caveat
  now points at the real contract wiring).

**Oracle preservation (frozen §1.6):** `GraphHopTest`'s two independent
BFS transcriptions (`bfsHopViaFacetMatches`, `bfsHopViaClassidScan`) stay
VERBATIM as the scalar reference; parity = `materializeRows()` set-equality
against them, both hops, plus the pinned 10/19/29 counts.

### 3.7 Test/gate re-pins (deliberate, listed — not silent)

- `GraphHopTest:459` allowlist becomes: returns ∈ {`Graph`, `long`,
  `void`, `Mask`, `WideFieldMask`} ∪ {`long[]` ONLY from methods whose
  name starts with `materialize`} — the §10 naming rule, encoded
  structurally.
- Crossing literals (2/1/1) are re-MEASURED under the new implementation,
  then pinned (the measure-then-pin discipline from D-LGJ-W6/W7). Expected
  shape: hop = 1 crossing flat; from/importRows = 1 (mask create; +1
  lifecycle describe amortized); count = 1; materializeRows = 0
  steady-state. Numbers are pinned from measurement, not from this
  prediction.
- `RowStoreParityTest` / `RowStoreLifetimeTest` untouched (the accessors
  they exercise remain public diagnostics).
- **Cascade additions found by council S4 (all mandatory-pre-merge in
  their respective PRs):**
  - `Downcalls.java:15-18` class-doc symbol count says "17" — ALREADY
    stale (real: 18 handles + 1 manifest getter = 19). The W8a/W8b touch
    that adds the 2 new handles corrects it to the real post-wave counts
    (20 handles, 21 symbols) rather than incrementing a wrong number.
  - `.claude/waves/wave-consumer-graph.md` — its DONE header describes
    the long[]/TreeSet implementation this wave demotes, and its D1
    ruling ("start D1a, promote to D1b only if measured") is partially
    reversed (D1b ships as the required path per §3.8). Gets a dated
    superseding note pointing at D-LGJ-W8; original text preserved
    (append-only).
  - `.claude/plans/consumer-graph-traversal-v1.md` — header still says
    PLANNED though the wave shipped 2026-08-18 (pre-existing staleness);
    same commit appends a status pointer.
  - `ndarray/.claude/blackboard.md` — PR-N MUST carry a blackboard entry
    (decision + the W1a deviation + loose ends) per ndarray CLAUDE.md
    Agent Protocol §2/§5; §3.10's PR-N gate is amended accordingly.
  - `native/lgj-abi/Cargo.toml:21` (A2 overclaim-auditor) — its comment
    "The ONLY dependency, and the ONLY source of SIMD in this crate" is
    falsified by §3.2's second dependency; PR-W8a updates it (ndarray
    stays the only SIMD source; it stops being the only dependency).
  - `GraphHopTest.java:436-438` (A2 warden) — the zero-serialization
    section's `c.that(..., true)` literal-true prose assertion is
    vacuous (cannot fail); the §3.7 re-pin deletes it or replaces it
    with a real falsifier per the falsifiability rule. The reflective
    arm at :445-466 is the real half and stays.

### 3.8 Execution placement (frozen §1.7 applied)

Shipped path: native `lgj_hop` — required anyway, because consumers cannot
reach the segment surface (`ApiSurfaceTest` walls off `internal.ffm`) and
publicly exposing raw mask-word writes would be a larger API hazard than
one ABI symbol. Pre-registered comparison (non-gating follow-up, bench
Component G, inside `bench/` which is a legal `internal.ffm` consumer):
native `lgj_hop` vs the preserved Java scalar arm vs an optional Vector-API
arm, at ≥2 row counts and ≥2 frontier densities. Promotion/demotion of the
placement follows that measurement — never taste (cite D-LGJ-G/W4 margins).

### 3.9 Policy + board (D-8/D-9) — landed at the A3 freeze, BEFORE workers

- **Root `CLAUDE.md` CREATED** (repo has none — verified): the operator's
  §15 policy text, adapted: the three-currency table of §3.1, the
  forbidden-state list, the explicit-materialisation rule (`materialize*`
  naming), the stronger-than-zero-serialization clause, the
  missing-capability STOP rule (route through the substrate wave), the
  contract-inheritance clause (this ruling: the contract is the law;
  engine stays out; provider late-bound), the mnemonics, and pointers to
  the ENFORCING artifacts (GraphHopTest structural guards, the G2
  committed check, this spec). Per the A2 warden P0 remediation: the
  file carries NO model-policy section and NO model identifier —
  see §13's explicit rule.
- **`EPIPHANIES.md`** storno `E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1`:
  corrects `Graph.java:18-19`'s "not a workaround" (valid for the bounded
  fixture; invalid as target precedent), records what #18 DID prove
  (membrane-surviving edge data, falsifiable hop semantics, measurable
  crossing cost, viable vocabulary) and did NOT prove (long[] as frontier
  currency, TreeSet as query substrate, per-row reads as target model),
  and carries the RULING (contract = the law, one closure, late-bound
  provider). Notes honestly: the drift was self-documented by the repo's
  own board hygiene at every step, and its own guard test (`:459`) had
  begun enforcing it — the sharpest evidence fixtures calcify.
- **`STATUS_BOARD.md`** row `D-LGJ-W8` with gate ladder: AUDIT ✓ → SPEC →
  COUNCIL → FREEZE → SUBSTRATE (ndarray + lgj-abi) → FACADE → GRAPH
  MIGRATION → FALSIFIERS → POLICY.
- **`LATEST_STATE.md`**: the correction note (Graph #18 = valid
  correctness fixture whose long[]/TreeSet implementation is scaffolding
  pending this wave; Trades/Bricks remain valid — their execution is
  already lazy/fused/mask-oriented, A0 lens E).
- **`PR_ARC_INVENTORY.md`**: per-PR entries post-merge (sha post-hoc), #18's
  historic entry NOT rewritten.

### 3.10 PR sequence (one closure, staged)

| # | Repo | Content | Gate |
|---|---|---|---|
| PR-0 | lance-graph-java | ratified spec v3 + root CLAUDE.md + board storno/row/state (A3 freeze) | council v3 |
| PR-N | ndarray | `mask_andnot`/`mask_andnot_assign` in simd_int_ops.rs + the `ndarray::simd` re-export (simd.rs:694-697) + parity/tail tests + blackboard entry recording the W1a family-shape deviation | ndarray suite green; merges FIRST |
| PR-W8a | lance-graph-java | contract dep + `FixtureClassView` + `lgj_mask_andnot` + `lgj_hop` + minor 4 + abi.md §13 + Cargo.toml comment fix | cargo 93+/93+, clippy/fmt, `nm -D` 21 symbols, disable-runs, G11 fence green, **same-commit board artifacts** (STATUS_BOARD SUBSTRATE flip; LATEST_STATE lines for minor 4 / 2 symbols / contract dep; ISSUES entry for the u16/u32 boundary pin) |
| PR-W8b | lance-graph-java | Java `WideFieldMask`, `RowStore.hop/importRows`, `Mask.minus/materializeRows`, `Status` −14 mirror, accessor reclassification, `Graph` migration, GraphHopTest re-pin + new falsifiers | §5 gates all green, **same-commit board artifacts** (STATUS_BOARD FACADE/GRAPH flips; LATEST_STATE lines for `WideFieldMask` + facade methods + `Graph` migration) |
| post | both | arc entries per rhythm | — |

Worker allocation (A4): grindwork-tier implementation workers on disjoint files
(ndarray kernel / lgj-abi Rust / Java core facade / Graph+tests), each
brief opening with the §25 verbatim STOP-condition preamble + the root
CLAUDE.md policy quote. Orchestrator compiles/tests/gates centrally.

## 4. NON-GOALS (each with why)

- **NG1** Full `lance-graph` engine dependency — forbidden by the ruling.
- **NG2** Re-striding the 512B/32×16 row store or any layout change — the
  stride is canon; ClassView interprets, layout stays.
- **NG3** A real ontology/cache ClassView provider — late-bound by design;
  fixture provider only; per-resource provider slot is a named seam.
- **NG4** R2IL/Ghidra views — protected by this policy, not consumed by it
  (operator §23).
- **NG5** SIMD/rayon optimization of the hop kernel BEYOND the §3.4
  composition (which already routes classid-match through the existing
  `eq_u32_strided_to_mask`; only decode+scatter is scalar — A2 sentinel:
  the pre-consolidation "scalar v1" wording here contradicted amended
  §3.4 and would have re-produced the all-scalar kernel S2-2 rejected);
  bench Component G is the pre-registered promotion trigger for
  anything wider.
- **NG6** New upstream contract surface (e.g. `ClassView::edge_slots`) —
  a lance-graph decision; recorded as a named seam, not smuggled in.
- **NG7** Public `Mask.and/or` surfacing, `maskOf` as a neutral-named
  public API, or any `View`/Trades/Bricks change — out of scope; the
  audit found them clean.
- **NG8** Resolving the contract's u16-vs-u32 ClassId split — upstream
  mint question (MedCare commitment #10); pinned at the boundary here.
- **NG9** Backporting mask-native hops into `FacetMatchView` — it remains
  the bulk-bitset diagnostic view it is.
- **NG10 (named seam, council S5-1)** Fused multi-hop traversal — a lazy
  `Traversal` descriptor mirroring `View`'s descriptor-append shape
  (View.java:59-66, NativePattern.java:162-175) that folds N hops into
  ONE crossing at a named terminal; blasgraph `mxv/vxm/multi_hop`
  (matrix.rs:230/269) is the Rust-side precedent. W8 ships eager
  per-hop (1 crossing/hop — bounded scope); the crossing re-pins in
  §3.7 are measurements of THIS shape, not an endorsement of it as
  final. Promotion trigger: bench Component G's crossing-cost
  measurements, and/or the GridLake compute wave (whose
  `parallelCompute`/cycle shape IS the fused-plan generalization).
- **NG11 (named seam, council S5-4)** Hop-into-dst / mask reuse — the
  ABI is reuse-capable BY CONSTRUCTION (`lgj_hop` takes `dst_mask` as a
  caller-provided parameter; aliasing spelled out), so a future
  reuse/pooling facade needs ZERO ABI change. The Java `hop(...)`
  allocating overload is ergonomic sugar; per-tick mask
  allocate-and-orphan in a many-parallel-frontiers loop is a real cost
  the compute (GridLake) wave addresses with a reuse surface — named
  here so §3.5's allocating shape is not read as the ceiling.
- **NG12 (named seam, A2 sentinel)** Wide-tier `WideFieldMask`
  promotion — the Java mirror carries the SMALL tier only (one `long`),
  while the contract type's whole point is the `Small(u64) →
  Wide(Box<[u64]>)` promotion (a2ui-rs adopted the retype specifically
  "so surfaces past 64 fields are covered"). The name promises a wide
  leg the Java type cannot yet carry; the promotion seam is documented
  ON the type's javadoc so the ceiling is visible rather than
  discovered at facet 65. Promotion gates on the first store with >64
  facets.

## 5. PRE-REGISTERED GATES (pass/fail decided now)

- **G1 Structural**: reflection walk over `Graph` (and `Edge`): zero
  fields typed `long[]`/`Long[]`/any `java.util.Collection`; AND (A2
  addition — the parameter half both reviewers flagged) the ONLY public
  `Graph` method with a `long[]`/`long...` PARAMETER is `from` (the
  documented external-selection entry) and the only `long[]` RETURN is
  `materializeRows()`. The OLD implementation fails both halves
  (can-fire proven by construction — it has the field, and
  `minus(long...)`/`rows()` violate the parameter/return rules).
- **G2 No-per-row-engine**: a COMMITTED test — G1's reflective family
  or a comment/javadoc-stripped source scan — never an agent-habit
  grep (A2 warden: a gate that lives only in an agent's habit is not a
  gate; precedent shape CODEX_REVIEW_CHECKLIST.md:49-51).
  `consumers/*/src/main/**` contains ZERO CALL SITES of
  `classidAt|payloadLow64At|payloadHi32At|matchesOf`. Baseline
  re-measured under that mechanism (A2 warden): a raw name-grep finds
  13 matches today — 3 real calls (Graph.java:137,142,143 — the
  can-fire baseline) plus 10 javadoc `{@link}` references (Graph.java +
  Edge.java) which are documentation pointing at diagnostics, are
  ALLOWED, and survive the migration by design. A mechanism that
  cannot tell a call from a `{@link}` cannot implement this gate; the
  pass condition applies to calls only. Test/oracle files exempt.
  Documented in root CLAUDE.md.
- **G3 Allocation**: `getThreadAllocatedBytes` (the `TradesAllocationTest`
  instrument) around `from→hop→hop→count` at two materially different
  frontier scales: Java-heap allocation independent of frontier size;
  `materializeRows` excluded (it is the explicit exception). The old
  TreeSet implementation must fail this dramatically (run once against
  the preserved oracle arm as the can-fire proof).
- **G4 Crossing**: crossings ∝ hops (+ constant per terminal), measured at
  two row counts and two frontier sizes, constants pinned from
  measurement.
- **G5 Parity**: mask-native `materializeRows()` set-equals both preserved
  BFS transcriptions at 1 and 2 hops; counts equal 19/29; anti-vacuity
  10/19/29 distinct, non-empty, non-total (unchanged fixture:
  `n=2000, seed=0xF00D_CAFE, classid 0, gate 0x0, radius 25`).
- **G6 Disable-runs** (each red-then-green): (a) hop kernel decode offset
  corrupted by +4 → G5 red; (b) facet participation forced to EMPTY →
  hop yields ∅, G5 red; (c) `mask_andnot` tail-clear removed → G7 red;
  (d) provider decode_mode misrouted → G5 red; (e) aliased `dst == src`
  hop must COMPLETE and be correct (council S3-4: removing the
  dedup-before-lock discipline deadlocks = hang = red; the test runs
  with a timeout so the hang is observable, not silent).
- **G7 Tail**: after `minus`, no bit ≥ n_rows set (word-level assert on a
  non-multiple-of-64 row count, e.g. n=70 — the existing tail-rule test
  shape).
- **G8 Surface**: `ApiSurfaceTest` 3/3; `GraphHopTest` new allowlist rule
  (long[] ⇒ name starts `materialize`); full core suite + trades + bricks
  byte-identical.
- **G9 Flagship composition**: `from(maskOfFacetClass(...)).hop(...).count()`
  end-to-end with ZERO row-id values anywhere in the test body (asserted
  structurally: no `long[]` local except the oracle comparison).
- **G10 Versioning**: minor-4 `.so` + minor-3 expectations load fine;
  `requireMinor(4)` fails loudly pre-downcall on an old artifact (the
  minor-2/3 gate precedent, re-verified).
- **G11 Contract-import fence** (A2 warden P1 — enforced at PR-W8a,
  the PR that creates the exposure): mechanical allowlist check in the
  shape of the existing `ndarray::hpc` fence
  (CODEX_REVIEW_CHECKLIST.md:49-51): `native/lgj-abi/src` may import
  from `lance_graph_contract::{class_view, canonical_node, ontology}`
  ONLY. Any use of `kanban`, `mul`, `cognition`, `collapse_gate`,
  `cognitive_shader`, `counterfactual`, `soa_view`, `scheduler`, or any
  other contract module is red. Rationale: `default-features = false`
  gates only guid-tail/codebook (contract lib.rs:65-66) — the cognitive
  modules compile UNCONDITIONALLY, so this fence is the only barrier
  between the dep and the §10 vocabulary prohibition. Can-fire: a
  scratch `use lance_graph_contract::kanban::KanbanColumn;` must turn
  the check red.

## 6. PER-SAVANT QUESTION SETS (Phase 1 — answer YES/NO/VIOLATES + file:line)

**S1 prior-art:** (1) Does any repo already ship a Java-side mirror of
FieldMask/WideFieldMask (search lance-graph-java, a2ui-rs docs) that this
spec duplicates? (2) Does any existing lgj/ndarray/lance-graph code
implement mask-andnot under another name? (3) Is there an existing
fixture/dummy ClassView impl in lance-graph or OGAR this spec should reuse
instead of `FixtureClassView`? (4) Do E-ids near
`E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1` already exist (grep both
lgj and lance-graph EPIPHANIES)? (5) Does ruff's R2IL council/impl spec
contain a falsifier or ledger pattern §5 should adopt? *(v1 cited
`r2il-roundtrip-oracle-spec-v1.md` — that path does not exist; the real
files are `r2il-behavioral-ir-v1.md` + `r2il-behavioral-ir-v1-impl-spec.md`.
Answered by S1: nothing new to adopt — their pre-registration discipline
is already mirrored by §5.)*

**S2 iron-rules:** For each: YIELDS or VIOLATES with evidence. (1) §3.4 vs
abi.md §6 bulk-or-lifecycle. (2) §3.4 vs §8 SIMD-provenance (scalar kernel
+ ndarray andnot only). (3) §3.4 vs I-LEGACY-API-FEATURE-GATED (additive
minor; existing symbols untouched; UNSUPPORTED_DECODE_MODE status). (4)
§3.5 vs ApiSurfaceTest FORBIDDEN. (5) §3.2 vs lance-graph P0 fork/dep
rules + the contract crate's own zero-dep design comment. (6) §3.9 vs
board append-only/storno discipline. (7) The ndarray `mask_andnot` spec vs
the W1a consumer contract as written in ndarray CLAUDE.md.

**S3 code-truth:** Verify REAL vs CLAIMED at file:line for: (1) every
inventory row in §2 (spot-check all lines cited); (2) `FieldRef`
construction feasibility for `FixtureClassView` (String fields — any
no_std/const constraint?); (3) the contract compiles standalone with
`default-features = false` and its build.rs does not require the OGAR
sibling; (4) `MaskWords` locking supports the §3.4 snapshot-then-write
pattern without deadlock against the ordered-locking rules
(registry.rs `lock_masks_ordered`); (5) `lgj_op_eq_classid`'s u32 classid
wire precedent; (6) row counts near `u32::MAX`/`i64` bounds in the hop
target check (`t < n_rows`) — any overflow path; (7) the −14 status code
is genuinely unused.

**S4 cascade-impact:** Enumerate EVERY file/test/doc/board row that must
change for §3, split mandatory-pre-merge vs follow-up. Must cover: abi.md
(§1 count, §2 version, §7 list, §13 new), abi.rs manifest/minor, Layouts/
Downcalls/Engine (eager-resolution rebuild order — cite the D-LGJ-W6
false-alarm), Abi.requireMinor call sites, GraphHopTest sections by line,
RowStore/Mask javadoc, bench (does summarise.sh or any bench arm reference
the 19-symbol count?), valhalla-lab (touches describeMask?), .claude/waves/
wave-consumer-graph.md status note, and both repos' board files. Flag
anything §3 forgot.

**S5 different-views (no redesigns — RISK findings only):** (1) Strongest
alternative to `RowStore.hop(...)` as the public op (e.g. a `Traversal`
descriptor mirroring `View`'s lazy-compose shape: does eager-per-hop
crossing forfeit a future fused multi-hop plan, and should the spec name
that seam?); (2) strongest alternative to Java `WideFieldMask` Small-tier
(reuse existing `Field`/`FacetId` vocabulary?); (3) second-order
consequence of `importRows` living on `RowStore` (does every future
resource type need one — should it be on `Mask`?); (4) the
64K-parallel/batch-writing consequence: does anything in §3 block the
many-rows-many-thoughts model the ruling cites (e.g. per-hop mask
allocation vs mask reuse)?; (5) does mode-0 decode hard-coded in the ABI
risk calcifying the fixture convention the way #18 calcified long[] — is
the RESERVED-modes fence strong enough?

---

# PART II — THE 64K PARALLEL SoA COMPUTE / PLACEMENT / PUBLICATION HALF

> Added per the operator's A1 ARCHITECTURE RULING (2026-08-18): A1 joins
> (A) mask-native semantic navigation and (B) parallel SoA compute +
> deterministic landing + batch version publication into ONE architecture.
> Ground truth below is the six-lens audit (wf_3de44246-471, 37 findings,
> banked in the workflow journal); every claim carries the audited
> file:line. Classifications use the ruling's own scale: CURRENT-CANON /
> SHIPPED-PROVISIONAL / EXPERIMENTAL / SUPERSEDED / ASPIRATIONAL / ABSENT.

## 8. AUDITED GROUND TRUTH (the honest §18 pass)

### 8.1 Corrected ownership map (verified against the current tree)

The operator's expected decomposition holds — all six names resolve; one
functional drift and one wiring gap found:

| Owner | Verified truth | Class |
|---|---|---|
| `lance-graph-contract` | ClassView + FieldMask (class_view.rs:70), WideFieldMask (:221), ClassView trait (:903); MailboxSoaView/MailboxSoaOwner (soa_view.rs:67/:295 — **cognitive-flavored**, see §8.7); DatasetVersion(u64) (scheduler.rs:36 — **entangled** in a KanbanMove-returning trait's module); the CLEAN version vocabulary is `LanceVersion`/`VersionRange`/`TemporalPov` (temporal_pov.rs:49/:67/:151); standing_mask dirty∩interest (:70/:143); nan_projection (:62); ComputeEdge/execute_compute_dag (class_view.rs:568/:816, ordering only); TriePlacement (rail_geometry.rs:219). NO physical SoA backing store — soa_view.rs's only Vec-backed columns are a `#[cfg(test)]` FakeSoa (:333-335); scoped per A2: other contract modules carry unrelated Vecs (high_heel.rs curves, recipe_substrate.rs codebooks), none of them SoA row storage. | CURRENT-CANON |
| `cognitive-shader-driver` | in-tree crate; `MailboxSoA<const N>` (mailbox_soa.rs:58) with the mutating column accessors (:417-801) — the physical owner the contract trait abstracts. | CURRENT-CANON |
| `lance-graph-planner` | BatchWriter (batch_writer.rs:95); owner_adapter rebind_bootstrap/emit_bootstrap_intent (:68/:92 — the #878 arm); persist_sink: `order_cycle_stably` (:133), `DetachedCycleBatch::freeze` (:377), `WalSink` (:534), `recover_and_apply` (:668), `CommitOutcome` (:227), `CommitError` (:258). | CURRENT-CANON |
| `lance-graph-supervisor` | cycle_driver.rs — `run_cycle` (:658), seal/collect/apply/recover; the #879 D-MBX-A6-P4 loop closure. | CURRENT-CANON |
| `kanban_actor` | TOMBSTONED: the RPC/actor surface was DELETED 2026-08-05 (operator ruling `E-PROGRESSION-IS-EXISTENCE-NOT-COMMAND-1`; PR_ARC:987 "No new production architecture may depend on it"). Remainder: PhaseCensus (read-only), mul_target, parse_kanban_step — pure helpers. Exactly what the ruling's §6 demands the Java design NOT port. | CURRENT-CANON (as visibility surface) |
| `lance-graph::graph::cycle_sink` | `LanceCycleWriter` (:153), `impl WalSink` (:731), head()→DatasetVersion (:436), process-local single-writer registry + RAII WriterClaim (:200-260). Sole-writer confirmed STRUCTURALLY (the only non-test, non-example WalSink impl — `MemWal`-style fakes live in tests AND in examples/ binaries, scoped per A2) — **but production wiring of LanceCycleWriter into `cycle_driver::run_cycle` does not yet exist in this tree**; every consumer runs against in-memory fakes. | SHIPPED-PROVISIONAL |

### 8.2 The 64K compute path — what is actually in-tree

- **Exactly one implementation:** `lance-graph-supervisor/examples/`
  `measure_wal_curve.rs`, arm **EXP-KIA-A2-64K** (`run_exp_kia_a2_64k`
  :2093; `FLEET_OWNERS = 65_536` :115; parallel phase :2127-2152). It is
  a standalone example binary, self-labelled *"exploratory concurrency
  (non-claiming; D-KIA-A2 untouched)"* (:2094-2096; STATUS_BOARD:211).
  **EXPERIMENTAL.**
- **How parallelism is created:** plain `std::thread::scope` (:2135) —
  NOT rayon (zero rayon anywhere in the shader/cycle path); explicit
  contiguous partitioning `partitions(n, workers)` (:2080,
  `chunk = n.div_ceil(workers)`); worker sweep {1,2,4,8,16,avail}
  (:2099-2107). **Parallelism is proven, not inferred**: an
  `AtomicUsize` high-water mark asserts `max_active_workers >= 2`
  (:2131-2165) — the in-tree precedent for the ruling's §22
  "parallelism is real" falsifier.
- **One computation** = one owner's gate evaluation (`compute_range`
  :2057-2078): `qualia_at(0)` → `mantissa_of` → `gate_decision_i4`
  (contract mul.rs:575) → `phase().advance_on_gate` →
  `PreparedIntent{owner, target, payload}`. The parallel phase is
  strictly READ-ONLY over `&fleet`; results accumulate in thread-local
  Vecs; everything after (rebind, cast into ONE BatchWriter, seal, one
  WAL commit) runs sequentially at the convergence boundary after
  `sort_by_key(|p| p.owner)` (:2167-2229).
- **The production #879 path is wholly sequential**: `cognitive_pass`
  is a `for id in owners` loop over `&mut BatchWriter`
  (cycle_driver.rs:715); `run_cognitive_work*` take `impl FnMut`
  (:769-876) — unparallelisable signatures. The module says so itself
  (:765-768): *"a sequential contract-probe adapter … It does not
  define the production execution model. Production cognition may run
  independently and concurrently over the sealed Vn."* So the RULING's
  model (autonomous parallel compute, deterministic convergence) is the
  DOCUMENTED INTENT with an experimental proof-arm — not yet the wired
  production loop. The Java spec cites it as such.
- **Reading rules (operator A2 verdict, 2026-08-18):** (a)
  "experimental / non-claiming" means NOT-YET-THE-PRODUCTION-EXECUTION-
  PATH, never "parallelism unproven" — the arm genuinely executes
  65,536 compute bodies with measured concurrent worker overlap; do not
  regress this finding to "64K parallelism hypothetical". (b) The
  probe's LANDING half is weaker than it looks: handles are collected
  in original handle order, then additionally sorted by owner before
  the BatchWriter — fully legitimate for the compute proof, but it does
  NOT demonstrate "arbitrary real completion order → identical
  persisted landing order". That stronger property is exactly F-ORD's
  job (§12), never this probe's. AND the positive leg (A2 sentinel —
  keep both): the owner-sort `sort_by_key(|p| p.owner)` (:2171) is an
  IDENTITY-DERIVED canonicalization applied before the BatchWriter —
  an in-tree exemplar already satisfying four of §10's five frozen key
  properties (identity-derived/deterministic, completion-timing
  independent, canonical before publication, sparse-compatible); what
  it lacks is only the falsifier proving it under real perturbation.
- The #879 sparse-closure record is real and executable:
  `p4b_applies_only_the_sealed_sparse_set_64k_of_17_advance_rest_byte_identical`
  (cycle_driver.rs:1548, `FLEET = 65_536`, 17 represented), arc entry
  PR_ARC:1047/:1056. Caveats that travel with any citation: the arc
  entry is marked RECONSTRUCTED (:1049) and its honesty ledger says
  "durability FAKE (contract-probe WalSink)" (:1055) — the falsifier
  proves seal/apply sparsity, not Lance durability.

### 8.3 Benchmark record — the ~125 ms / ~233 ms numbers, honestly

**The exact figures are not reproducible in-tree — the original run's
receipt lives out-of-tree.** (Wording note, A2 sentinel: "ABSENT" is
reserved for the six-grade classification scale; this is a
receipt-provenance statement, never a rejection of the measurement.)
The one in-tree mention grades them explicitly
(probes/weather-p1/SUBSTRATE_FORMULA_MATRIX.md:380 and
.claude/plans/weather-substrate-poc-v1.md:26): *"we measured 64k weather
at ~125 ms compute / 233 ms disk" … **Operator-reported, out-of-tree.**
… The board's measure-64k-axes arm is 65,536 mailbox owners with
seal/WAL timings — a different thing. Do not conflate … usable as
priors, not as citations.'* The in-tree measured record is:

- Stage A0 (commit 82394d7): per 64k cycle — scan +1.0 ms, cast/rebind
  +11.5 ms, freeze +0.6 ms, think 8.6 ms, apply 23.5 ms; temporal
  post-WAL T1 78-86 ms / T2 7.3-8.8 ms over 1,048,576 rows.
- EXP-KIA-A2-64K concurrency: **~3.2-3.5× compute overlap on 4 cores**
  (a ratio, not a duration; non-claiming).

**Provenance split (operator A2 verdict — a receipt gap, NOT a
refutation of the measurement):**

```
64K parallel compute        PROVEN in-tree
                            (EXP-KIA concurrency probe)

~125 ms compute
~233 ms batch write         OPERATOR-MEASURED
("233 ms disk" in the        (original run receipt currently
 in-tree prior's wording)     out-of-tree)
```

If these figures are ever to serve as a public LanceGraph performance
claim, a small reproduction/receipt PR upgrades them from
[operator measurement] to [artifact-backed measurement]. Not a W8
blocker; recorded so provenance never silently blurs in either
direction.

**Spec consequence:** this spec cites the 125/233 pair ONLY as
operator-measured out-of-tree priors for the compute/publish cost SHAPE
(compute and publication same order of magnitude; publication ≈ 2×
compute — a shape read from the operator's out-of-tree priors, not an
in-tree ratio; the in-tree measure-64k-axes arm is a different workload
by its own grading), never as a reproducible in-tree benchmark. Any Java-side
performance gate is pinned from ITS OWN measurements (the D-LGJ
measure-then-pin discipline), not from these priors.

### 8.4 Deterministic placement — the mechanism in force, and the leak

Three separable pieces, classified:

1. **Row placement (WHERE in the image): deterministic BY CONTRACT,
   not by construction (A2 correction — overclaim-auditor P1).**
   `row: row_of(owner)` is `impl FnMut(MailboxId) -> u64`
   (cycle_driver.rs:361, applied :387) — a `FnMut` may carry mutable
   state, so an arrival-order-dependent closure TYPECHECKS. Determinism
   is a caller obligation the type system does not enforce, no
   canonical provider ships (tests pass `u64::from`), and F-LAND (§12)
   must PIN the purity rather than assume it. The sort/coalesce
   boundary AROUND the placement is CURRENT-CANON; the purity of
   `row_of` itself is an UNVERIFIED CALLER CONTRACT. The dense-slot
   SCATTER (O(n), sort-free) is NAMED but NOT BUILT —
   persist_sink.rs:127-131: *"a stable scatter into predetermined
   positions is cheaper than a general O(n log n) sort on the 64k path
   … This general sort is the fallback form."* Classification:
   boundary CURRENT-CANON, placement-fn purity CALLER-CONTRACT
   (unverified), canonical provider ABSENT, scatter ASPIRATIONAL.
2. **Stream ordering (WHICH order in the log): shipped sort, leaky key.**
   The order key is `SweepSlot::stream_position` (u64): stable sort in
   `freeze` (persist_sink.rs:378 via `order_cycle_stably` :133), then
   per-ROW coalesce into `BTreeMap<u64, Vec<u8>>` (:379-382,
   last-in-stream-order wins), `batch_hash` folds stream_position
   (:414). **The leak (audit L3-6, evidence re-scoped per A2
   overclaim-auditor P1):** shipped code mints
   `stream_position = position_base + CastId` (cycle_driver.rs:385)
   where CastId is BatchWriter ARRIVAL order (batch_writer.rs:132-137).
   Two legs, each argued on its own evidence:
   - *Restart leg — already ANSWERED in shipped code.* temporal.rs's
     objection to CastId (:404-411 — anchor corrected; :412 is the
     bare `fn cast_seq` line) is grounded in restart-reset ("resets to
     0 on restart … different writer lifetimes collide"), and
     `position_base` is a DURABLE CURSOR built precisely against that
     (cycle_driver.rs:340-347, pinned by
     `restart_stable_stream_positions_survive_writer_reconstruction`
     :1460). Do NOT cite temporal.rs as authority against the
     within-cycle key — it addresses the restart half only, and a
     reader following the citation could wrongly retire the whole
     finding.
   - *Within-cycle leg — the SURVIVING defect, this spec's own
     inference on direct evidence.* Within one cycle, CastId is minted
     at `cast()` call order; under genuinely concurrent producers the
     landings order and the batch hash are therefore arrival-order
     dependent. The row-keyed IMAGE is protected (distinct owners →
     distinct rows); the PUBLICATION IDENTITY is what leaks. The 64K
     example bridges it with `sort_by_key(|p| p.owner)` before casting
     — also the in-tree exemplar of identity-derived canonicalization
     (§8.2 reading rule b).
   Classification: SHIPPED-PROVISIONAL — the canonicalization boundary
   is real; the canonical KEY is not yet independent of within-cycle
   arrival.
3. **Completion-order-independence testing: ABSENT as asked.** The two
   tests carrying the name (persist_sink.rs:1707, cycle_sink.rs:1638
   F15) permute only Vec order over fixtures with HARD-CODED
   stream_positions — sort-stability tests, not scheduling-permutation
   tests, by this workspace's own falsifiability rule.

**Spec consequence (the ruling's §18 warning, applied):** the Java/ABI
surface must NOT freeze the CastId-derived stream key — it is
provisional. What Java may treat as law: (a) results land at
`row_of(owner)` — a pure function of identity, never of timing; (b) one
stable canonicalization boundary orders and coalesces before the sole
writer; (c) the ordering KEY is supplied by the substrate and is opaque
to consumers. The GridLake surface (§10) binds to (a)-(c), never to the
key's current derivation.

### 8.5 temporal.rs — role

`lance-graph-planner/src/temporal.rs` (870 lines) is a **READ-path,
query-time module** by its own first line. Layer 1: causal deinterlacing
(`local_trajectories` :424 — per-owner chains sorted by cast_seq).
Layer 2: epistemic projection (`classify` :185, `deinterlace` :346,
sorted by `(hlc_tick ?? lance_version, lance_version)`). It is
explicitly NOT the write-path orderer (persist_sink.rs:33-35: cycle
ordering *"lives here — NOT in temporal.rs, which owns query-time
reading"*). It is also **UNWIRED in production** by the repo's own
STATUS block (batch_writer.rs:12-21: read side unwired, no production
`DeinterlaceRow` implementor, `cast()` zero production call sites;
ledger TD-DOC-COMMENTS-CLAIM-UNWIRED-BEHAVIOUR). The
E-MARKOV-TEMPORAL-STREAM-1 ruling (temporal.rs sorted stream supersedes
the VSA ±5 window) stands as the intended read model.
Classification: SHIPPED-PROVISIONAL (declared contract, unwired).

### 8.6 Inverse-pyramid / self-organising placement — status

**CONCEPTUAL-HISTORY / RESEARCH-PROBE for this spine; not shipped, not
currently planned production.** The evidence chain:

- The literal term exists in code twice, neither on this spine:
  verb_table.rs:339 (Morton-cell addressing for the 144-cell GRAMMAR
  table) and `bgz-tensor::morton_cascade` (mod.rs:1-40) — the latter
  operator-DOWNGRADED to CONJECTURE (EPIPHANIES:5891) and never
  consumed by cognitive-shader-driver (zero grep hits).
- The one genuine placement FINDING: `E-BASIN-IS-A-NODE`
  (EPIPHANIES:10136, graded [G]/[H]) — basin-as-node with
  perturbation-learnable mailbox distribution, measured 75.8%
  hop-distance win — lives entirely in `perturbation-sim`, a
  workspace-EXCLUDED standalone research crate.
- The V3 le-contract L4 tenant never describes itself as
  Morton/pyramid/4×4 — that framing is an EPIPHANIES-layer synthesis
  (E-MARKOV-TEMPORAL-STREAM-1 :7232) atop L4, same lineage by intent,
  unratified as L4's own reading.
- "marble": zero hits repo-wide.

**Spec consequence:** per the ruling ("do not choose among these from
this prompt"), the Java contract stops ABOVE placement mechanism: it
assumes only *deterministic-by-identity landing* (§8.4a). Pseudo-hash
vs temporal-stream vs inverse-pyramid remains an upstream lance-graph
decision; nothing in W8 or GridLake binds to any of the three.

### 8.7 Generic vs cognitive — the contract split as it stands

**Generic AND already in the contract (a Java consumer can bind without
cognitive vocabulary):** ClassId/FieldMask/WideFieldMask/ClassView/
ClassProjection/RenderRow/ValueRow (class_view.rs);
LanceVersion/VersionRange/TemporalPov (temporal_pov.rs — the clean
version half); standing_mask `fires(dirty, interest)` +
SubscriptionTable; NanReport; ComputeEdge + execute_compute_dag
(ordering only, ≤64 positions); TriePlacement.

**Cognitive vocabulary that leaks from the contract (a consumer SEES
these):** the whole kanban module (KanbanColumn/KanbanMove/ExecTarget/
RubiconTransitionError, lib.rs:205); MailboxId + GateDecision
(collapse_gate.rs:121); `MailboxSoaView`/`MailboxSoaOwner` — required
methods return MailboxId and KanbanColumn (soa_view.rs:21/:67/:77), so
the contract's ONLY SoA lens is **unusable generically**;
VersionScheduler returns `Option<KanbanMove>` and its module owns
DatasetVersion (scheduler.rs:4/:36/:45); mul.rs; KanbanTenant;
CounterfactualMailbox; MailboxRow. Honest negatives: "Heckhausen" and
"watermark" do NOT leak (zero contract hits; recovery-watermark
vocabulary is planner-confined).

**Engine-side only — the candidates for a future smaller generic
contract:** the whole freeze/coalesce/commit slice (CycleId, CycleFrame,
SweepSlot, LandedSlot, FrameMeta, **CommitOutcome**, CommitError,
DetachedCycleBatch, WalSink, order_cycle_stably, Recovered/
recover_and_apply — all planner persist_sink); the cycle orchestration
types (SealedTransition, SealedCycle, AppliedCycle, MailboxFleet,
CycleOutcome, CycleError, FleetRecovery — supervisor cycle_driver);
LanceCycleWriter + VersionedGraph (graph). No
ComputeKernel/CyclePort/BatchSink-style generic trait exists — ABSENT.

**The specific named gaps** (upstream lance-graph decisions, recorded
here as seams — same discipline as NG6, never smuggled in from lgj):
(a) no contract commit-outcome type; (b) no first-class dirty-population
/ represented-rows type (dirty exists only as a `&WideFieldMask`
parameter); (c) no cycle/batch identity in the contract; (d) no generic
`SoaView`/`SoaOwner` split free of MailboxId/KanbanColumn; (e) no
conversion between the engine's `DatasetVersion` (scheduler.rs:36 — the
type `CommitOutcome::Committed { version }` actually carries,
persist_sink.rs:115) and the clean `LanceVersion` vocabulary
(temporal_pov.rs:49, a bare u64 alias, no `From` impl) — the
version-projection seam any read-only Java publication view needs
(A2 overclaim-auditor find).

## 9. THE THREE AXES AND FOUR CURRENCIES (normative for all Java surfaces)

Mechanically separate, per the ruling — Java may never collapse them:

| Axis | Question | Owner today |
|---|---|---|
| EXECUTION | which computations run, where | policy axis: Java scalar / Java Vector API / Panama→ndarray SIMD / native fused / future GPU — empirical, per D-LGJ-G/W4 |
| PLACEMENT | where each completed result belongs | `row_of(owner)` identity-derived landing ONLY — a caller contract F-LAND must pin (§8.4.1); mechanism choice stays upstream |
| PUBLICATION | how the cycle becomes durable/versioned + the cycle's IDENTITY | the canonicalization boundary — ordering key + coalesce + batch hash (§8.4.2, where the within-cycle arrival leak LIVES) → freeze → sole writer → NoChange/Committed/Reconciled → `DatasetVersion` as the engine-side OUTPUT type (§8.1, #878/#911→#912/#913 spine) |

**Axis-split correction (A2 sentinel P1):** the ordering key and batch
hash belong to PUBLICATION, not PLACEMENT — §8.4 separates the safe
row-keyed image (placement) from the arrival-contaminated publication
identity (`batch_hash` folding `stream_position`), and this table now
files the leak on the axis where it lives. A wave must never read
"placement axis green" as "arrival-order Rubicon cleared."

The working set is the four-currency slice — **the sealed temporal
horizon × Mask × ClassView × WideFieldMask** (the operator's concept
name for the fourth currency is DatasetVersion). The Java BINDING for
that currency is the contract's CLEAN vocabulary —
`LanceVersion`/`VersionRange`/`TemporalPov` (temporal_pov.rs) — never
`scheduler.rs`'s KanbanMove-entangled `DatasetVersion` TYPE. Both facts
are kept: the engine's `CommitOutcome::Committed { version }` IS typed
with scheduler's `DatasetVersion` (persist_sink.rs:115), so a read-only
publication projection needs the `DatasetVersion` ↔ `LanceVersion`
conversion (both u64-backed, no `From` impl today) — a named seam,
recorded as gap (e) in §8.7. W8 implements the
Mask/ClassView/WideFieldMask triple; the version currency binds in the
GridLake wave.

Non-drift maxims (pinned, from the ruling): 64K COMPUTE WAS PARALLEL.
DETERMINISTIC LANDING DOES NOT MEAN SEQUENTIAL EXECUTION. COMPLETION
ORDER IS NOT SEMANTIC ORDER. THE PLANES FLY AUTONOMOUSLY; THEIR LANDING
SLOTS ARE PREDETERMINED. ONE COMPUTATION IS NOT ONE LANCE WRITE. ONE
ROW IS NOT ONE JAVA OBJECT. SEPARATION OF CONCERNS CONTAINS BLAST
RADIUS; IT DOES NOT MAKE THE CONTRACT OPTIONAL. And the four-way
inequality the A2 verdict ratified as the "airport law": **PARALLEL
COMPUTATION ≠ ARRIVAL ORDER ≠ SEMANTIC PLACEMENT ≠ PUBLICATION
ORDER.**

## 10. GRIDLAKE (named future wave — specified, NOT implemented in W8)

The generic mass-availability Java surface over Version × Mask ×
ClassView/WideFieldMask, per the ruling's §11 shape
(`grid.atVersion(v).where(...).fields(...).parallelCompute(kernel)`
`.landInto(cycle); cycle.commit()`). Binding rules fixed NOW so W8
cannot block it:

- `.atVersion(v)` binds to `LanceVersion`/`TemporalPov` (contract,
  clean). `.where(...)` = Mask composition (W8's substrate).
  `.fields(...)` = ClassView × WideFieldMask projection (W8's
  substrate). `.parallelCompute(...)` = bulk kernel over the slice —
  execution-policy axis, never one-object-per-row.
  `.landInto(cycle)` = deterministic staging: result attached to SoA
  coordinates + dirty descriptors, marked into the open cycle image —
  **NOT a write** (the ruling's §12). `.commit()` = freeze/coalesce/
  sole-writer/version — projecting NoChange/Committed/Reconciled.
- **HARD GATE (operator A2 verdict, 2026-08-18):**

  ```
  GENERIC PARALLEL WRITE API
            BLOCKED
               │
               ▼
  until deterministic landing identity
  is independent of producer arrival order
  ```

  Once Java may hand truly parallel producers into the cycle, CastId /
  arrival order may never become semantically relevant. What A2/A3
  freezes is the REQUIRED PROPERTIES of the landing key — never the
  final algorithm (pseudo-hash, Morton, temporal deinterlacing, and
  inverse-pyramid all stay OUT of the Java ABI):
  - identity-derived or otherwise deterministic;
  - restart-stable where durability requires it;
  - independent of completion timing;
  - canonical BEFORE batch hash / publication;
  - compatible with sparse SoA placement.
- **Gates before this wave can start** (all upstream lance-graph
  decisions, from §8.7's named gaps): contract-side commit-outcome +
  cycle-identity + dirty-population vocabulary; a generic
  SoaView/SoaOwner split; and the placement-key hardening (§8.4's
  stream_position leak) — a GridLake `commit()` must never durably fold
  an arrival-order-derived key.
- **Layering (operator A2 verdict):** Java/Panama `parallelCompute()` →
  shared SoA → `landInto(cycle)` → native generic cycle contract →
  SOLE publication writer → Lance. **Java never rebuilds
  `LanceCycleWriter`** — the sole-writer stays native, one per store,
  behind the generic cycle contract.
- lgj-side prerequisites already provided by W8 (A2 correction — stated
  precisely): Mask algebra AS SHIPPED — `minus`/andnot at the ABI plus
  the pre-existing native and/or symbols; public Java `Mask.and/or`
  remains NG7-withheld — plus hop, the WideFieldMask mirror, the
  mask-native policy, and the NG10/NG11 seams (fused plans; mask reuse
  — the per-tick allocation answer for many-parallel-frontiers).
- Cognitive vocabulary (MailboxId, KanbanColumn, Rubicon, MUL,
  Heckhausen, watermark) NEVER appears on this surface — cognition
  consumes the generic substrate, not vice versa.
- `RowStore` remains the lower-level primitive; GridLake composes over
  it (or its successor resource) rather than replacing it.

## 11. PUBLICATION/VERSION SEMANTICS — what Java eventually projects

Preserved semantics (audited in §8.1/§8.4, enforced at
persist_sink.rs:618-630 + cycle_sink.rs:738-745): a cycle with no
artifact-backed semantic change mints NO version (`NoChange`); a fresh
publication is `Committed { version, cycle, batch_hash }`; a
lost-ack resubmit reconciles on the durable `(cycle, batch_hash)`
identity as `Reconciled` — never a double append. `publication_version`
vs `observed_head` stay distinct (#913). Producers are fire-and-forget
(no per-result ack coupling — batch_writer.rs:92-94, verified L5-7).
**W8 exposes NONE of this** (mask correction only). The GridLake wave
projects these states read-only after the upstream contract extraction;
Java NEVER gets its own version writer, second ledger, or
version-per-row (ruling §19). The publication COPY SEAM is recorded
honestly: end-to-end zero-copy publication DOES NOT EXIST — three copy
sites (freeze's `payload.clone()` persist_sink.rs:381; Arrow builders
cycle_sink.rs:532-554; read-back `to_vec` :1138); #912 explicitly
defers true zero-copy. Java ergonomics must not paper over this seam.

## 12. COMPUTE-MODEL FALSIFIERS (pre-registered for the compute wave;
###    W8 carries only those marked [W8])

- **F-PAR parallelism is real** — high-water-mark concurrency assert
  (≥2 active workers), adopting measure_wal_curve.rs:2131-2165's
  in-tree shape; never inferred from API names.
- **F-ORD completion-order independence** — operator-sharpened
  (A2 verdict): *"Do not test permutation after the order key already
  exists. Perturb the process that creates the key."* The required
  shape: real concurrent completion (queue/channel) → deliberately
  shuffled worker timing → canonical landing-key derivation → SAME
  frozen image AND SAME semantic commit/batch hash across runs. Merely
  shuffling a Vec over pre-existing stable stream positions is a
  sort-stability test (the audit found both existing F15-named tests
  are exactly that) and does NOT satisfy this falsifier.
- **F-ONE one version, not N** — N completions → exactly 0 or 1
  DatasetVersion per cycle (`wal_writes() == 1` shape,
  cycle_driver.rs:1610 precedent).
- **F-SPARSE** — only dirty/represented locations contribute durable
  rows; unchanged population byte-identical (the 64k/17 shape with
  anti-vacuity, cycle_driver.rs:1548-1607 precedent).
- **F-HYDR no row hydration [W8: gate G3]** — allocation independent of
  population size; no object/Long/row-id per selected row.
- **F-ACK no per-result acknowledgement coupling** — a producer's
  completion never awaits its own durability
  (casting_is_fire_and_forget precedent, batch_writer.rs:188-199).
- **F-LAND deterministic landing** — permuting worker completion order
  never permutes semantic placement. **Scope (A2, both reviewers):**
  this is the PLACEMENT leg ONLY and never discharges F-ORD's
  PUBLICATION leg — a run can pass F-LAND today with the CastId leak
  fully intact, because the image is row-keyed. And it must PIN
  `row_of` purity, not assume it: the `FnMut` signature permits impure
  closures (§8.4.1), so the falsifier supplies an identity-derived
  placement fn and VERIFIES identical landing across perturbed
  schedules; "row_of is identity-pure" is the property under test,
  never a premise.
- **F-PARITY backend parity [W8 seeds the HARNESS only: bench
  Component G, which §3.8 declares non-gating — the falsifier itself
  gates the COMPUTE wave, not W8]** — Java Vector API and native SIMD
  arms produce identical semantic output; performance chooses
  placement, correctness never differs.

## 13. ROOT CLAUDE.md — compute-coverage additions (lands at A3 freeze)

Beyond Part I §3.9's mask-native policy, the root CLAUDE.md carries:

- The three-axes table (§9) and the four-currency slice.
- The forbidden-state list EXTENDED to compute: `where`/`hop`/
  `authorize`/`navigate` AND `compute`/`transform`/`stage`/`land`/
  `batch`/`commit` — a bulk compute result stays attached to SoA
  coordinates, masks, ranges, or descriptors; never one Java result
  object or row-ID per selected row merely to carry a result to the
  batch writer.
- The maxims: WHERE MAY LOOK LIKE WHERE. UNDERNEATH IT IS MASK. /
  COMPUTE MAY LOOK LIKE A PARALLEL COLLECTION OPERATION. UNDERNEATH IT
  IS BULK SoA COMPUTE. / LANDING MAY LOOK LIKE A WRITE. UNDERNEATH IT
  IS DETERMINISTIC PLACEMENT INTO THE OPEN CYCLE IMAGE. / plus §9's
  non-drift maxims. **Export rule (A2 sentinel): each maxim travels
  with its scope leg** — "64K COMPUTE WAS PARALLEL" carries
  "(proof-arm in-tree; not yet the production loop)", because the root
  file's readers cannot see measure_wal_curve.rs and a one-legged
  maxim re-creates the exact blur the operator forbade.
- One computation ≠ one Lance write; one row ≠ one Java object; no
  Java-side version writer or second ledger, ever.
- The kanban_actor lesson (§8.1), carried under its ruling name
  `E-PROGRESSION-IS-EXISTENCE-NOT-COMMAND-1` with BOTH legs (A2
  sentinel): actor/message-per-write is the architecture lance-graph
  itself DELETED — never port it — AND *progression is existence, not
  command* (per-owner advance RPCs were replaced by one read-only
  PhaseCensus pass; `advance(owner)` is the obvious Java ergonomic and
  is exactly the deleted shape). Vocabulary guard: `BatchWriter::cast()`
  = staging into a batch image, NOT the deleted command/ack messaging —
  sharing the word "cast" with the tombstoned surface does not make it
  that surface.
- **The root CLAUDE.md carries NO model-policy section** (A2 warden
  P0 remediation): worker-tier allocation is stated by role in session
  briefs, never as product names in a committed policy file. The
  repo's boards carry pre-existing model-name occurrences — an
  operator-level cleanup call on append-only files, explicitly NOT
  swept by this wave and NOT propagated into the new file.

## 14. COMBINED WAVE SEQUENCE

```
A0   drift audit                                DONE (CONFIRMED)
A1   this combined spec (Parts I + II)          THIS DOCUMENT
A2   council/adversarial review                 3 reviewers on the
                                                COMBINED draft, after
                                                operator architecture
                                                review of the §25 reply
A3   contract freeze                            spec v3 committed (PR-0)
A3.5 root CLAUDE.md + board guardrails +        same PR-0
     mechanical architecture tests (G1/G2
     shapes)
W8   mask-native correction                     PR-N → PR-W8a → PR-W8b
     (contract dep, substrate primitives,       (Part I §3.10; gates §5)
     graph migration)
THEN separate measured wave                     GridLake surface (§10),
                                                gated on the upstream
                                                contract extraction
                                                (§8.7 gaps) + placement-
                                                key hardening (§8.4)
```

The compute option must not delay the long[] correction; W8 must not
block the compute option — enforced structurally: W8's only compute-
adjacent commitments are the NG10/NG11 seams and gate G3, all of which
the GridLake wave consumes rather than reverses.

## 7. CHANGE LEDGER (v1 → v2 → v3)

### v1 → v2 (Phase 2 consolidation, 2026-08-18 — 5 savants, 44 findings:
S1×6, S2×7, S3×10, S4×16, S5×5; raw output banked at the wf_a899e5c2-9ad
workflow journal)

**VIOLATES (1) — amended:**
- S2-7: "W1a-conformant" label on `mask_andnot` was false (mask_and is a
  free fn; W1a litmus rejects free fns for NEW primitives). §3.4 now
  carries an explicit deviation record (family-shape-over-litmus, with
  why), lands in ndarray's blackboard at PR-N, and is flagged to the
  operator in the A2 response. Frozen §1.12 amended for this pair, not
  silently overridden.

**GAPs (9) — each filled with a committed decision (count corrected
per A2 sentinel; the raw savant output is banked in the session
workflow journal, which is session-local — the CONFIRMS bucket below is
summarized, not enumerated, and this ledger says so rather than
claiming full in-repo provenance for it):**
- S3-4 (the implementation trap): dedup-before-lock aliasing discipline
  made normative in §3.4 + new gate G6(e). A naive ordered-lock under
  dst==src deadlocks.
- S3-1 (×3 citation fixes): class_view.rs ops range :221-378;
  Graph.java quote :19-20; screens_reachable_from = position×edges→mask
  fixpoint. §2 corrected.
- S3-6: bounds-before-cast ordering made normative in §3.4.
- S4 Downcalls doc-count: pre-existing "17" drift corrected in the same
  touch (→ real post-wave counts). §3.7 cascade list.
- S4 wave-doc + plan-doc staleness: superseding notes for
  wave-consumer-graph.md (D1 ruling partially reversed — D1b ships) and
  consumer-graph-traversal-v1.md. §3.7 cascade list.
- S4 ndarray blackboard: PR-N board hygiene mandatory per ndarray Agent
  Protocol. §3.7 + §3.10.
- S4 simd.rs re-export: mask_andnot/mask_andnot_assign must join the
  `ndarray::simd` pub-use list — the only sanctioned consumer path.
  §3.4 + §3.10.
- S4 Status.java: UNSUPPORTED_DECODE_MODE(-14) mirror entry added to
  §3.5; old-build graceful degradation verified (S3-7).
- S1-5: phantom citation (r2il-roundtrip-oracle-spec-v1.md does not
  exist) corrected in §6; the real R2IL docs' pre-registration
  discipline already mirrored by §5 — nothing further adopted.

**RISKs (4+1) — absorbed as spec changes or named seams:**
- S2-2: hop kernel respecified as composition (existing
  eq_u32_strided_to_mask for classid-match; scalar only for
  decode+scatter). "Trivially §8-clean" overclaim removed.
- S5-1: fused multi-hop Traversal = NG10 named seam with promotion
  trigger (bench Component G / GridLake wave). Eager-per-hop crossing
  pins are measurements of the v1 shape, not an endorsement.
- S5-2: WideFieldMask.ofMatchBits(int) bridges the three facet
  vocabularies; width story documented on the type. §3.5.
- S5-4: hop-into-dst/mask reuse = NG11 named seam; ABI reuse-capable by
  construction, facade sugar allocates, pooling belongs to the compute
  wave.
- S5-5: decode-mode promotion trigger named (per-resource provider slot
  landing / GridLake wave). §3.4.

**PRIOR-ART-AT (2) — reuse wired or scoped:**
- S1-2: blasgraph BitVec::not / DescValue::Complement noted in §2 as
  conceptual precedent; NOT reusable (different type system). The §2
  "no and-not anywhere" claim re-scoped to the lgj Mask/registry system.
- S1-3: FixtureClassView mirrors the contract's own FakeClasses /
  TestClasses idiom (#[cfg(test)]-private, hence a new impl is
  legitimate); OgarClassView rejected as substitute. §3.3.

**CONFIRMS highlights banked (no change needed):** contract compiles
--no-default-features with build.rs never touching OGAR (S3-3, run
live); -14 genuinely free on both sides (S3-7); u32 classid wire
precedent (S3-5); path dep legitimate — the contract's zero-dep comment
constrains its OWN deps, and lgj-abi is a standalone crate with the
identical ndarray path-dep precedent (S2-5); ApiSurfaceTest needs zero
edits — reflection-over-directory auto-discovers WideFieldMask (S4);
maskOfFacetClass already exists at RowStore.java:117 so the flagship
composition needs no new capability (S4); requireMinor has exactly 4
uniform call sites (S4); board-file plans match each file's own
discipline (S2-6); storno E-id is novel (S1-4).

**Losing/parked findings (recorded, not deleted):** none rejected
outright; every finding either amended the spec, became a named seam, or
was banked as CONFIRMS. The one judgment call: S2-7's strict reading
(make mask_andnot a struct method) LOST to family-shape consistency —
the reasoning is in §3.4's deviation block and travels to the operator
via the A2 response.

### v2 → v2-combined (DONE 2026-08-18)

Part II (§8-§14) added per the operator A1 ARCHITECTURE RULING, grounded
in the six-lens spine audit (wf_3de44246-471, 37 findings — L1×7 L2×6
L3×8 L4×4 L5×7 L6×5, raw output banked in the workflow journal). The
three honest-audit corrections it carries: (1) the ~125/233 ms pair is
in-tree ONLY as operator-reported out-of-tree priors — the in-tree
measured record is Stage A0 (82394d7) + the ~3.2-3.5× overlap ratio;
(2) the shipped stream-ordering key derives from BatchWriter arrival
order (CastId), which temporal.rs itself disqualifies — the
canonicalization boundary is canon, the key is provisional, and the
Java surface binds to neither; (3) the named completion-order tests are
sort-stability tests, not scheduling-permutation tests (F-ORD names the
differentiator). The A2 council runs on THIS combined draft.

### v2-combined → v2.1 (operator A2 verdict amendments, 2026-08-18)

A1 accepted as the review draft. Five refinements ruled in before the
reviewers cast: (1) §8.2 reading rules — "experimental/non-claiming" ≠
"parallelism unproven", and the probe's landing half (handle-order
collect + owner sort) proves compute, not completion-order landing;
(2) §8.3 provenance split table — 64K parallelism PROVEN in-tree,
125/233 OPERATOR-MEASURED with receipt currently out-of-tree, upgrade
path = a future reproduction/receipt PR, no W8 blocker; (3) §10 HARD
GATE — generic parallel write API BLOCKED until landing identity is
independent of producer arrival order, with the five REQUIRED
PROPERTIES of the key frozen instead of any algorithm, plus the
Java-never-rebuilds-LanceCycleWriter layering; (4) §12 F-ORD sharpened
— "perturb the process that creates the key", same frozen image + same
publication identity; (5) §9 the four-way inequality maxim (PARALLEL
COMPUTATION ≠ ARRIVAL ORDER ≠ SEMANTIC PLACEMENT ≠ PUBLICATION ORDER).
A2 review foci set by the operator: (i) W8 currency repair, (ii)
landing-key Rubicon, (iii) generic extraction boundary. W8 is NOT
expanded into GridLake implementation.

### v2.1 → v3 (Phase 4 fixes applied; RATIFIED 2026-08-18)

**The council:** 5 savants (Phase 1, 44 findings) + 3 reviewers
(Phase 3, on the combined v2.1 only): overclaim-auditor (0 BLOCK /
1 P1 / 12 P2 / 15 PASS), dilution-collapse-sentinel (0 / 2 / 7 / 7),
firewall-warden (1 BLOCK / 3 P1 / 3 P2 / 12 PASS). Every verdict
applied or recorded below; stricter-wins used nowhere (no reviewer
conflicts — the one factual disagreement was settled by reading the
file, see the storno below).

**BLOCK(P0) resolved (warden — model identifiers in a to-be-committed
artifact):** §1.16 and §3.10's worker line restated by ROLE; §3.9/§13
now explicitly exclude a model-policy section from the new root
CLAUDE.md. **Operator flag (not swept):** the repo's boards already
carry ~42 pre-existing model-name occurrences across 16 committed
files — an operator-level cleanup decision on append-only files,
recorded here, deliberately not edited by this wave.

**FIX(P1) applied (6):**
- §8.4 evidence repair (overclaim): "deterministic by construction" →
  BY CONTRACT (`FnMut` permits impure closures; purity is an
  unverified caller obligation F-LAND must pin); the temporal.rs
  citation re-anchored (:404-411) and re-scoped — its restart
  objection is ANSWERED by the durable `position_base` cursor
  (cycle_driver.rs:340-347 + the :1460 restart falsifier); the
  surviving within-cycle arrival leak is argued on its own evidence.
- §9 axis split (sentinel): ordering key/coalesce/batch-hash moved to
  PUBLICATION where the leak lives; PLACEMENT keeps only
  `row_of(owner)`; the fourth currency keeps the operator's concept
  name with the Java binding named as the CLEAN vocabulary and the
  DatasetVersion↔LanceVersion conversion recorded as new gap §8.7(e).
- §12 F-LAND scoped (sentinel + overclaim): placement leg only, never
  discharges F-ORD's publication leg; pins purity instead of assuming
  it. F-PARITY tag corrected (W8 seeds the non-gating harness only).
- §3.10 same-commit board artifacts added to PR-W8a/W8b gate columns
  (warden — the retroactive-hygiene shape §1.15 forbids).
- §5 G2 respecified (warden): committed test, call-sites-only
  semantics, honest 13-match baseline (3 calls + 10 allowed `{@link}`s
  that survive the migration); the as-written gate was unreachable.
- §5 G11 added (warden): contract-import allowlist fence at PR-W8a
  ({class_view, canonical_node, ontology} only) — default-features
  gates nothing cognitive; the fence is the only barrier.

**FIX(P2) applied (headlines):** §1.12 in-place ⊘ carve-out pointer;
header "proven" scope-split (operator's wording quoted; halves graded);
Graph.minus(long...) REMOVED from the migrated surface + G1 parameter
rule (overclaim — the second unnamed row-id entry); NG5 aligned with
the §3.4 composition; NG12 Wide-promotion seam + on-type ceiling doc;
§8.1 absolutes scoped (FakeSoa = soa_view-module claim; WalSink =
non-test/non-example); §8.2 positive leg added (owner-sort as the
in-tree exemplar of 4/5 frozen key properties); §8.3 "ABSENT" wording
replaced + "disk" source label + prior-derived shape marked; §10
and/or claim corrected to NG7 reality; §13 export legs (two-legged
maxims; the ruling name E-PROGRESSION-IS-EXISTENCE-NOT-COMMAND-1 with
the cast-vs-command vocabulary guard); cascade additions (lgj-abi
Cargo.toml:21 comment; GraphHopTest:436-438 vacuous literal-true
assertion); anchor fixes (full_for :338, soa_view :77,
simd.rs:694-698).

**Storno inside the ledger (append-only — the v1→v2 entry above is NOT
edited):** S3-1's "citation fix" moving the Graph.java storno-target
anchor to :19-20 was ITSELF WRONG — both A2 reviewers re-verified and
the orchestrator read the file: the span is :18-19 (v1 had it right).
§2 and §3.9 now agree on :18-19. Likewise the v2-combined entry's
phrase "which temporal.rs itself disqualifies" is superseded by the
two-leg scoping in §8.4.2 — temporal.rs addresses the restart half
only.

**v3 is the executable spec.** A3 freeze = PR-0 (this file + root
CLAUDE.md + board storno/row/state).

# Architecture

> Read `../README.md` first for the one-page picture. This document is the
> "why," not a restatement of the "what" — each section links to the real
> artifact that proves its claim rather than re-describing it.

## The thesis, restated precisely

> 64,000 logical entities do not become 64,000 Java objects. They become
> 1 native lane set + 1 packed mask + a handful of tiny typed descriptors
> + one bulk operation.

This is not a performance slogan — it is a falsifiable claim, and every
layer of this project exists to make it either provably true or provably
false at the point where it would break. See
`.claude/knowledge/john-doe-migration-thesis.md` for the full framing and
the migration story ("yesterday's object-heavy Java... gets a
generated/schema-fed API that still feels like Java") that motivates it.

## The four layers, and what each one is actually responsible for

```
Java semantic plane   →   Panama FFM membrane   →   Rust ABI crate   →   ndarray::simd
```

### 1. The Java semantic plane (`java/`)

**Responsible for:** looking like ordinary, boring Java. `NativePattern` /
`View` / `Predicate` / `Pattern` / `Mask` / `Lens`. Nothing here may mention
`MemorySegment`, `Arena`, a lane id, an opcode, or which SIMD backend ran —
`ApiSurfaceTest` enforces this by *reflection*, not by convention (walks
every public member of every public type in
`com.adaworldapi.lancegraph`, fails if any signature mentions
`java.lang.foreign.*`, `java.lang.invoke.*`, or `internal.*`).

**Not responsible for:** deciding when to cross the membrane. `View.where()`
composes pure data (a `Predicate` list) and crosses **zero** times —
proven by `LazinessTest`, which counts actual downcalls before and after
building a 16-condition chain and asserts the count didn't move. A terminal
operation (`count()`, `sumOf()`) is the only thing that ever crosses, and it
crosses **exactly once**, independent of predicate count (`FusionParityTest`)
and independent of row count up to 1,048,576+ (measured directly in
`bench/RESULTS.md`'s `planConstructionOnly` row, which scales with predicate
count and is flat across every row count tested).

### 2. The Panama FFM membrane (`java/.../internal/ffm/`)

**Responsible for:** turning the ABI contract (`docs/abi.md`) into real
`MethodHandle`s, `MemoryLayout`s, and a runtime **manifest cross-check**
that proves the loaded `.so` actually matches what this Java build was
compiled against — not by convention, by comparing two independently
computed numbers (`Layouts.java`'s `MemoryLayout.byteSize()` against the
manifest's own `size_of` fields) and refusing to proceed on any mismatch.
`AbiContractTest` proves this is a real check, not a formality: a real
shared library that happens to load fine (`libz.so.1`) is still rejected,
because it exports no `lgj_abi_manifest` symbol.

**Not responsible for:** any policy about what a "resource" or "mask"
*means* semantically — that's the layer above. This layer only knows about
bytes, handles, and status codes.

### 3. The Rust ABI crate (`native/lgj-abi`)

**Responsible for:** the 14-symbol `extern "C"` surface in `docs/abi.md`
§7, the generation-checked handle registry (`.claude/knowledge/
abi-ownership-and-handles.md`), and the generic SoA fixture. Bulk-only —
every function's cost scales with `n_rows`, or is lifecycle. No strings, no
callbacks, no per-element crossings (`docs/abi.md` §6, the anti-JNI rule).

**Provably safe, not just tested-safe:** the registry's core invariant
(a stale handle can never dereference freed memory) was
**disable-verified** — the generation check was deliberately broken and
the suite re-run to confirm exactly the two tests that should catch it went
red, and only those two. See `.claude/board/EPIPHANIES.md`
`E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1` for the exact procedure and
numbers.

### 4. `ndarray::simd` (a sibling repo, consumed not re-implemented)

**Responsible for:** every bulk kernel. `native/lgj-abi/src/kernels.rs` is
the *only* file in this crate allowed to import from `ndarray`, and it
reaches everything exclusively through `ndarray::simd::*` — never
`ndarray::hpc::*` (the internal implementation namespace) directly. This
project added five primitives to `ndarray` under that repo's own W1a
consumer contract (`eq_u32_to_mask`, `gt_i32_to_mask`, `mask_and`/`mask_or`
(`_assign`), `masked_sum_i32`) rather than reimplementing anything locally.
See `.claude/knowledge/simd-provenance.md`.

**Measured payoff:** SIMD vs. the crate's own independent scalar reference
kernel is 10.8×–31.1× faster on the fused multi-predicate path, growing
with predicate count (`bench/RESULTS.md`, Component E). This is the
single largest measured lever in the whole project — larger than the
membrane-crossing cost itself.

## What the measurements actually say about where the boundaries pay off

This is the part a pure architecture diagram cannot show, and it's the
reason `bench/` and `valhalla-lab/` exist as first-class deliverables
rather than an afterthought:

- **The Rust↔Java membrane pays off for composed, multi-predicate work** —
  where SIMD fusion (10.8×–31.1×) and the one-crossing guarantee for an
  arbitrarily long `View` chain matter. It does **not** pay off for a
  single predicate read off one lane, where the Java Vector API reading the
  same native memory zero-copy is measurably *faster* at every scale tested
  (see `docs/execution-boundary.md`).
- **Valhalla pays off for the tiny descriptor vocabulary** (`LaneId`,
  `Ordinal`, `MaskId` — single-field types, ≤8 bytes, genuinely flatten) —
  and does **not** rescue per-entity materialization (`Row`, 16 bytes,
  measured `NOT-FLAT` even under Valhalla). See `docs/valhalla-lab.md`.

Both of these are measured surprises relative to a naive "the native side
always wins" assumption, and both are load-bearing for how this project's
API is actually shaped: the fluent `View` stays lazy and fuses because
fusion is where the payoff is real, and the semantic vocabulary stays
`record`-shaped (one-word migration to `value record` when JEP 401 ships)
because that's exactly the shape Valhalla rewards.

## Where a real graph slice would attach (not built yet, by design)

`docs/abi.md` §10 names this explicitly: `WideFieldMask` (already has
`intersect`/`union`/`count` in `lance-graph-contract`) and `NodeRow`
(`#[repr(C, align(64))]`, 16|16|480 bytes, already size-locked) are the
existing Rust-side types this ABI's `MASK_WORD` lane and lane-descriptor
shapes are already compatible with. Operator-stated layout reference
(2026-08-17): the lance-graph substrate enforces **64K rows × 512 bytes
per row, read as 32 lanes of 16 bytes each (4-byte classid + 12-byte
payload — the V3 content-blind facet)** everywhere; the Java-side layout
may legitimately differ, but that 512-byte, 64-byte-aligned row is the
shape a real slice inherits — and it is exactly the shape
`ndarray::simd_soa::MultiLaneColumn` (64-byte-chunk iteration over an
`Arc<[u8]>`) was built for, which is why that type is earmarked for the
row-store slice and deliberately NOT used by today's flat-lane fixture
kernels (see `.claude/board/EPIPHANIES.md`
`E-LGJ-SIMD-SOA-IS-FOR-THE-ROW-STORE-NOT-THE-FLAT-LANES-1`) — see the lance-graph archaeology
findings in `.claude/board/AGENT_LOG.md`. The generic fixture in this
first slice was deliberately chosen over wiring the real graph types
immediately, so the membrane's physics could be proven independent of graph
semantics. Wiring `ClassView`/`WideFieldMask` is the natural next slice,
not a redesign.

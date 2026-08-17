# The execution boundary — measured, and what the measurements imply

> Companion to `bench/RESULTS.md` (the raw numbers + reproduction commands).
> This document is the synthesis: what the numbers mean for where work should
> execute, and the three structural facts about the hot path that the numbers
> only make sense in light of.

## The question, as the mission posed it

> "Where is the cheapest and cleanest execution boundary? Not: how can we
> maximize the amount of Java code?"

Measured answer, from real JMH over identical data with a cross-checked
answer on every arm (`Data.crossCheck()`): **it depends on composition, and
the dependence is now quantified.**

| workload shape | winner | evidence |
|---|---|---|
| one predicate, one lane, any row count 64→4M | **Java Vector API, zero-copy on the native segment** — 1.3×–56× faster than the crossing | `bench/RESULTS.md` Component C |
| one predicate vs a plain Java *scalar* loop | native wins only past ~4,096–16,384 rows | same sweep, scalar column |
| N predicates fused, 65,536 rows | native SIMD, 10.8×–31.1× over its own scalar reference | Component E |
| the crossing itself, empty | ~22 ns bare, ~118 ns with two args + out-pointer | Component A |
| reading native memory from Java at all | free — segment scalar ≈ heap array (within noise); segment *vector* 1.55× faster than both | Component B |

The two headline implications:

1. **The membrane's cost is real but small and fixed** (~0.6 µs including
   wrapper overhead) — it is repaid by *work*, not by *data volume alone*.
   A single predicate never generates enough work per byte to repay it,
   because the Vector API can do that same predicate on the same bytes
   without crossing at all.
2. **SIMD fusion is the largest lever measured anywhere in this project**
   (10.8×–31.1×) — larger than any crossing cost. The crossing is how you
   *reach* `ndarray::simd`'s fused kernels; that, not the crossing itself,
   is what the Java side is buying.

## Three structural facts the numbers rest on

### 1. Zero-copy is precise language here, not marketing

The project's invariant (per the mission brief): **crossing the boundary
must not itself require serialization or copying.** It does not claim no
allocation ever happens — a mask result is a legitimate, semantically
required native allocation. What is eliminated is *boundary-induced*
copying, and this is checkable in the code:

- The Vector API arm reads the native lane via
  `IntVector.fromMemorySegment(species, segment, offset, nativeOrder())` —
  no `byte[]`, no `int[]` staging, no `MemorySegment.toArray`. The bench's
  own README states the rule and why a copy anywhere would make the
  comparison dishonest in *both* directions.
- On the native side, lanes are allocated once and never relocated
  (`docs/abi.md` §4), so a `MemorySegment` view stays valid for the
  resource's whole lifetime — the precondition for Java reading it in place.

**This is also exactly the shape a real lance-graph slice inherits.** The
lance-graph side's `SoaEnvelope::{as_le_bytes, row_le, column_le}` are
already zero-copy `&[u8]` views over LE-resident backing bytes (that repo's
own doctrine: "every SoA envelope is zero-copy from creation to Lance
tombstone" — nothing is serialized between mailboxes). When those replace
the generic fixture, no serialization step *exists anywhere in the stack*:
Lance's columnar bytes are the wire format, the membrane hands Java a
bounded view of them, and both execution engines — `ndarray::simd` on one
side, the Vector API on the other — operate on the same un-copied bytes.
That is the whole point, and it is why the Vector-API finding below is a
*feature* of the design rather than an embarrassment to it.

### 2. There is no thread pool in the hot path — the JVM's threads ARE the parallelism

Checked, not assumed (2026-08-17):

- `lgj-abi` has exactly one dependency: `ndarray` with
  `default-features = false, features = ["std"]`. **Rayon is not in the
  tree** (it is an optional ndarray feature, not enabled here).
- The only `thread::spawn` in the crate is `#[cfg(test)]`-only — two tests
  proving the concurrency *shape* (8 threads on distinct resources: no
  deadlock, no cross-talk, independently correct answers; and
  opposite-order mask binops that would deadlock without address-ordered
  locking, run 2,000 times each way).

The design instead makes the *caller's* threads the unit of parallelism:
the registry takes a short read-lock only to resolve `handle →
Arc<ResourceEntry>`, drops it, then locks only that entry
(`.claude/knowledge/abi-ownership-and-handles.md`). So N Java threads
driving N distinct resources — the "64K thoughts as many mailboxes, each
owned by its caller" model — run concurrently through the membrane with no
Rust-side scheduler, no fork-join pool, no rayon. Parallelism is implicit
in ownership, exactly as the sibling lance-graph substrate's
one-writer-per-mailbox doctrine intends.

**Honest boundary:** the shape is proven (the two tests above); throughput
under real contention is NOT yet benchmarked — filed as
`TD-LGJ-REGISTRY-CONCURRENCY-UNMEASURED` in `.claude/board/TECH_DEBT.md`,
to be paid when a concurrent caller actually exists rather than
speculatively.

### 3. The kernels chunk by direct lane-group indexing, not via `array_windows`/`array_chunks`

Checked precisely (2026-08-17), not assumed either way: `ndarray::simd_ops`
exports `array_windows`/`array_chunks` as opt-in, generic const-N staging
helpers. A full trace of this project's call graph —
`eq_u32_to_mask`/`gt_i32_to_mask` → `load_u32x16`/`load_i32x16` →
`copy_from_slice(&src[..16])` — shows neither is invoked, at any input
size; nothing about data volume triggers them, since they only run if a
caller literally writes `array_chunks::<T, 16>(slice)`, which this call
graph never does. A repo-wide grep confirms the same is true of
`ndarray`'s own internals: `simd_soa.rs`'s `MultiLaneColumn` doc comment
*cross-references* `array_chunks` as living in `simd_ops.rs`, but does not
call it either.

This is a considered choice for the mask kernels specifically, not an
omission: `array_windows` is a *sliding* window (every element visited N
times — right for stencils/filters, wrong for a linear scan, the same
reasoning the sibling tesseract-rs repo recorded when it evaluated and
declined `array_windows` for its own integral-image kernels). The mask
kernels instead stride directly: 16 elements per group through
`U32x16::eq_bitmask` / `I32x16::gt_bitmask`, ORing each group's 16-bit
result into position `(g % 4) * 16` of word `g / 4`, with a scalar tail —
zero iterator overhead, and the "trailing bits are zero" guarantee made
structural by zeroing the output first. This achieves the same *effect*
`array_chunks` exists to provide (fixed-width grouping), through each
primitive's own indexing rather than the shared utility — a legitimate
alternative, not a gap, though routing through `array_chunks` for
uniformity across `ndarray::simd`'s kernels would be a reasonable future
refactor if consistency across primitives becomes a goal in its own right.

## The resulting execution model (the synthesis)

Not "Rust executes, Java orchestrates" — the measured picture is finer:

```
                     the same un-serialized native bytes
                    ┌───────────────────────────────────┐
                    │        lance / lane storage        │
                    └───────────────┬───────────────────┘
              borrowed segment      │        one fused crossing
             ┌──────────────────────┴─────────────────────┐
             ▼                                            ▼
   Java Vector API                              Rust ndarray::simd
   — single-predicate reads                     — multi-predicate fused plans
   — small/any row counts                       — the 10.8-31.1x SIMD kernels
   — anything the JIT can see whole             — anything worth ONE crossing
             │                                            │
             └──────────────────────┬─────────────────────┘
                                    ▼
                     tiny results (a count, a sum, a mask handle)
```

**Re-measured on the real substrate layout (W4, Component F, 2026-08-17):**
the single-predicate finding survives *directionally* on the 512-byte-row /
32-facet store — the Vector API still wins the per-row facet scan at every
row count measured — but the margin collapses from Component C's 56× to
**2.5× at 4K rows, 1.9× at 65K, 1.14× at 1M**, where all three arms converge
on memory bandwidth (512 MiB traversed, ~6–7 GB/s, the native arm's CI
bracketing much of the residual gap). More work per byte narrows the
boundary exactly as this document predicted; now it is measured rather than
predicted, with one disclosed asymmetry (the native arm allocates its output
per call where the Java arms reuse a buffer — `bench/RESULTS.md` §F).

A future planner could even choose the side per-operation using exactly the
crossover tables in `bench/RESULTS.md` — the data to make that choice
mechanically now exists, on both the flat and the row-store layouts. What keeps the model honest is the invariant both
sides share: **the bytes never serialize, never bounce, never mirror into
the Java heap as N objects.** Which side loops over them is an
implementation decision the measurements can now drive; that they are the
same bytes is the architecture.

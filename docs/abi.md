# The ABI — a machine membrane, not a product API

> **Status:** normative. This document is the contract. The Rust side and the
> Java side are implemented *independently* against it, and a runtime manifest
> check proves they agree. If this document and the code disagree, the code is
> wrong.

## 0. There is no C here. Ever.

This is the most misunderstood property of the design, so it is stated first.

`extern "C"` in Rust and `Linker.nativeLinker()` in Java **do not involve the C
language**. They name a *machine* calling convention:

| Thing | What it actually is | What it is not |
|---|---|---|
| `extern "C"` (Rust) | "use this target's standard C-family calling convention" — on this box, the **System V AMD64 psABI**; on ARM64, **AAPCS64** | C source, a C compiler, a C runtime |
| `#[repr(C)]` (Rust) | "use this target's standard aggregate layout rule" (field order, padding, alignment) | a C struct declaration |
| `Linker.nativeLinker()` (Java) | a JVM-internal implementation of that same psABI (`SysVx64Linker`) | a C bridge, a JNI shim |

Consequences, all load-bearing:

- **No `.h` header exists anywhere in this project**, and none will.
- **No C toolchain** is required to build or consume this. `cargo` and `javac`
  are the entire toolchain.
- **No `cbindgen`.** Its output is a C header; we have no consumer for one.
- **No `jextract`.** jextract's *only* input is a C header. With no header there
  is nothing to extract. This is not "we chose not to use the generated
  bindings" (though that would also be true) — the tool has no input.
- **No JNI**, and no JNI-shaped use of Panama (see §6).

### What replaces the header: a self-describing manifest

A C header is a *text file that claims* what the compiled artifact looks like.
It can drift from the artifact silently — the classic ABI break. We do the
opposite: the compiled artifact **describes itself at runtime**, and Java
verifies that description against its own compiled-in expectations before the
first real call.

```
   Rust cdylib                                     Java
   ───────────                                     ────
   lgj_abi_manifest()  ──── returns ptr ───▶  read LgjAbiManifest
   (a static, versioned                            │
    #[repr(C)] struct                              ▼
    describing sizeof/                       cross-check EVERY
    alignof of every                         size & align against
    ABI type, endianness,                    this Java build's own
    the compiled SIMD                        MemoryLayout constants
    backend)                                       │
                                                   ▼
                                         mismatch ⇒ hard fail at load
                                         (never silent corruption)
```

The manifest is strictly stronger than a header: a header describes what someone
*intended* to compile; the manifest is emitted *by* the compiled artifact, so it
cannot disagree with itself.

## 1. Scope: what the ABI is allowed to be

The ABI is a **machine membrane**. It is not the product. The product is the Java
semantic API (see `architecture.md`). Therefore:

- It is **small** — currently 21 symbols (minor 4; the "14" this line carried
  at minor 1 was arithmetic drift — the §7 list it referred to already
  enumerated 15). Growth is a design smell to be argued for, not a default;
  minor 2's three additions are argued in §11, minor 3's one addition in
  §12, minor 4's two additions in §13.
- It is **bulk-only**. Every call must be capable of doing work proportional to
  `n_rows` (see §6 — the anti-JNI rule).
- It speaks **resource, lane, view, mask, operation, descriptor, status,
  epoch** — not `Node`, not `Edge`, not `Person`.
- It is **versioned** and refuses to operate across a version mismatch.
- It never allocates on the Java side's behalf without a paired release, and
  never hands out a pointer whose lifetime it cannot state.

## 2. Versioning

```
LGJ_ABI_MAJOR = 0    // incompatible change ⇒ bump; Java refuses to load
LGJ_ABI_MINOR = 4    // additive change ⇒ bump; older Java may still load
LGJ_MAGIC     = 0x4C_47_4A_5F_41_42_49_00   // "LGJ_ABI\0" big-endian-read
```

Rule: **Java requires `major` to match exactly and `minor` to be `>=` what it
was compiled against.** A `major` mismatch is a hard failure, not a warning.

The magic doubles as an endianness probe: read as a `u64` little-endian it yields
a known constant; anything else means the library was built for a different byte
order and every subsequent read would be garbage.

### Minor version history

- **Minor 2** (2026-08-17) — the SoA row store (§11).
- **Minor 3** (2026-08-18) — the edge-bearing row store (§12).
- **Minor 4** (2026-08-18, D-LGJ-W8) — `lgj_mask_andnot` (mask complement)
  and `lgj_hop` (one-hop graph traversal, gated by the
  `lance-graph-contract` `ClassView`/`FieldMask` LAW — §13).

## 3. Status codes

Every function returns `i32`. `0` is success; all failures are negative. There
are no error strings across the membrane and no `errno` dependence.

| Value | Name | Meaning |
|---:|---|---|
| `0` | `OK` | success |
| `-1` | `NULL_ARGUMENT` | a required out-pointer was null |
| `-2` | `INVALID_HANDLE` | handle malformed, closed, or generation-stale |
| `-3` | `WRONG_RESOURCE_KIND` | e.g. a mask handle passed where a pattern was required |
| `-4` | `INVALID_LANE` | `lane_id` out of range for this resource |
| `-5` | `LANE_KIND_MISMATCH` | op's element type ≠ lane's element type |
| `-6` | `MASK_LENGTH_MISMATCH` | mask row-count ≠ resource row-count |
| `-7` | `PARENT_CLOSED` | child (mask) outlived its parent resource |
| `-8` | `VERSION_MISMATCH` | caller's ABI version incompatible |
| `-9` | `LENGTH_OVERFLOW` | requested size overflows `usize`/allocation limit |
| `-10` | `UNKNOWN_OPCODE` | plan contained an opcode this build does not implement |
| `-11` | `EMPTY_PLAN` | a plan with zero ops was submitted |
| `-12` | `ALLOCATION_FAILED` | the allocator refused |
| `-13` | `READ_ONLY` | write attempted against a read-only lane |
| `-14` | `UNSUPPORTED_DECODE_MODE` | `lgj_hop` called with a `decode_mode` this build does not yet implement (§13, ABI minor ≥ 4) |

`INVALID_HANDLE` is deliberately the response to *use-after-close*, not a crash.
See §4.

## 4. Ownership, lifetime, and the generation-checked handle

This is the part of the design that Java cannot get from Rust for free. Inside
Rust, `&self` borrows make a view-outliving-its-owner a *compile error*. Across
the membrane there is no borrow checker, so the invariant must be enforced at
runtime.

**A handle is not a pointer.** It is an opaque `u64`:

```
  63                    32 31                     0
 ┌────────────────────────┬────────────────────────┐
 │      generation        │        index           │
 └────────────────────────┴────────────────────────┘
```

- `index` selects a slot in a Rust-side registry.
- `generation` is bumped **every time a slot is freed**.

A lookup validates `generation` against the slot's current generation. So:

| Java does | Result |
|---|---|
| uses a live handle | works |
| uses a handle after `lgj_close` | `INVALID_HANDLE` — *not* use-after-free |
| closes twice | second returns `INVALID_HANDLE` |
| fabricates a handle (`0`, `0xDEADBEEF`, …) | `INVALID_HANDLE` |
| uses a mask whose parent was closed | `PARENT_CLOSED` |

There is **no code path in which a stale handle dereferences freed memory.** That
is the single most important safety property of this ABI, and §H of the test
plan falsifies it directly rather than assuming it.

### Answers to the mandated ownership questions

| Question | Answer |
|---|---|
| Who owns this memory? | Rust. Always. Java never allocates a lane. |
| How long does a `MemorySegment` remain valid? | Until `lgj_close` on the owning handle. Java models this with an `Arena` whose lifetime is *nested inside* the resource's, so closing the resource is what ends the segment's usefulness — and the `epoch` field lets Java detect a stale segment it still holds. |
| What invalidates a view? | Closing the owner, or closing the owner's parent. |
| Can native storage relocate? | **No.** Lanes are allocated once at `lgj_pattern_open` and never reallocated, resized, or moved while the resource is alive. This is a hard ABI guarantee; any future growable lane requires a `major` bump. |
| Can Java mutate it? | Only where `LGJ_FLAG_WRITABLE` is set on the descriptor. Pattern lanes are **read-only**; mask words are writable. |
| What happens when the resource closes? | Its lanes are freed, its generation is bumped, its children fail with `PARENT_CLOSED`. |
| Can a child view outlive the parent? | It can *exist* but not *work* — every operation on it returns `PARENT_CLOSED`. |
| How are errors represented? | Negative `i32` status. Never a panic across the boundary (§9). |

### Concurrency

The registry is a `RwLock` holding `Arc<ResourceEntry>` values; each entry has its
own inner lock over its payload. A call takes a **short** read lock on the
registry to resolve and clone the `Arc`, releases it, then locks only the entry.
So bulk ops on distinct resources run concurrently, and only
open/close serialize globally. Stated honestly: this has not been benchmarked
under contention, and the POC's Java layer is single-threaded.

## 5. Descriptors describe lanes, not pointers

Java's *public* API never sees an address. Internally, `LgjLaneDesc` is the
bounded description that the FFM layer turns into a `MemorySegment`:

```rust
#[repr(C)]                    // 56 bytes, align 8
pub struct LgjLaneDesc {
    pub addr:         u64,    // physics — never surfaced in the public Java API
    pub len_elems:    u64,
    pub byte_len:     u64,    // exact covered span: (len-1)*stride + elem_bytes; 0 when empty
    pub owner:        u64,    // owning resource handle
    pub epoch:        u64,    // liveness stamp; Java re-checks before use
    pub elem_kind:    u32,    // LgjElemKind
    pub elem_bytes:   u32,
    pub stride_bytes: u32,    // == elem_bytes when contiguous
    pub flags:        u32,    // bitfield, below
}
```

Flags: `READABLE = 1<<0`, `WRITABLE = 1<<1`, `CONTIGUOUS = 1<<2`.

Element kinds start at `1`, so a zeroed struct is detectably invalid rather than
silently meaning `U8`:

```
U8=1  I8=2  U16=3  I16=4  U32=5  I32=6  U64=7  I64=8  F32=9  F64=10  MASK_WORD=11
```

`MASK_WORD` is a `u64` of 64 packed row bits, LSB = lowest row index.

```rust
#[repr(C)]                    // 32 bytes
pub struct LgjResourceInfo {
    pub kind:       u32,      // 1 = Pattern, 2 = Mask
    pub lane_count: u32,
    pub n_rows:     u64,
    pub epoch:      u64,
    pub parent:     u64,      // 0 = none
}
```

```rust
#[repr(C)]                    // 24 bytes, align 8
pub struct LgjOpDesc {
    pub op:       u32,        // LgjOpCode
    pub lane_id:  u32,
    pub operand:  i64,        // needle / threshold, sign-extended
    pub combine:  u32,        // 0 = AND (narrow), 1 = OR (widen)
    pub _reserved:u32,        // must be 0
}
```

```rust
#[repr(C)]
pub struct LgjAbiManifest {
    pub magic:                  u64,
    pub abi_major:              u32,
    pub abi_minor:              u32,
    pub size_of_manifest:       u32,
    pub size_of_lane_desc:      u32,
    pub size_of_op_desc:        u32,
    pub size_of_resource_info:  u32,
    pub align_of_lane_desc:     u32,
    pub align_of_op_desc:       u32,
    pub align_of_resource_info: u32,
    pub pointer_bytes:          u32,
    pub endianness:             u32,   // 0 = little
    pub simd_backend:           u32,   // LgjSimdBackend
    pub simd_backend_name:      [u8; 32],  // NUL-terminated, human-readable
    pub build_profile:          [u8; 16],  // "release" | "debug"
}
```

`simd_backend`: `SCALAR=0  AVX2=1  AVX512=2  NEON=3  WASM=4`. It is reported, not
negotiated — Java does not select a backend. Which backend is compiled in is
`ndarray`'s business (§7).

## 6. The anti-JNI rule

Panama makes it *easy* to write JNI-shaped code: one downcall per element. That
is forbidden here. The rule:

> **Every ABI function must do work proportional to `n_rows`, or be lifecycle.**

Explicitly prohibited, and each of these would be a design failure rather than a
performance nit:

- one call per node / per edge / per element
- one upcall per element
- Java-side hydration of a `Node`/`Edge`/`Person` object per row
- any serialization: no JSON, no protobuf, no `byte[]` bounce buffer
- a Java-owned mirror of the native data

The fused-plan call (§7, `lgj_plan_eval`) exists precisely so that
`.where(...).where(...).count()` is **one** crossing regardless of how many
predicates or rows are involved. The unfused per-predicate ops are retained only
so the fused path can be benchmarked *against* something and so parity can be
checked predicate-by-predicate.

## 7. The function surface (22 symbols)

All symbols are prefixed `lgj_`. All return `i32` status except the manifest
getter. `out_*` parameters are written only on `OK`.

### Manifest

```
const LgjAbiManifest* lgj_abi_manifest(void)
```
Never fails, never allocates, returns a pointer to a `'static`. The one symbol
that does not return a status, because there is no failure mode.

### Lifecycle

```
i32 lgj_pattern_open(u64 n_rows, u64 seed, u64* out_handle)
i32 lgj_close(u64 handle)
i32 lgj_resource_info(u64 handle, LgjResourceInfo* out)
```

`lgj_pattern_open` builds the generic SoA fixture deterministically from `seed`
(see `architecture.md` §fixture). Deterministic generation is what lets the Java
test assert exact counts without shipping a data file.

### Lanes

```
i32 lgj_lane_describe(u64 handle, u32 lane_id, LgjLaneDesc* out)
```

Pattern lanes: `0 = ids (U64)`, `1 = classes (U32)`, `2 = values (I32)`.
All `READABLE | CONTIGUOUS`, never `WRITABLE`.

### Masks

```
i32 lgj_mask_create(u64 parent, u32 initial, u64* out_handle)   // initial: 0=empty, 1=all
i32 lgj_mask_describe(u64 mask, LgjLaneDesc* out)               // MASK_WORD lane, WRITABLE
i32 lgj_mask_and(u64 a, u64 b, u64 dst)
i32 lgj_mask_or(u64 a, u64 b, u64 dst)
i32 lgj_mask_andnot(u64 a, u64 b, u64 dst)                      // ABI minor ≥ 4, see §13
i32 lgj_mask_count(u64 mask, u64* out_count)
```

`dst` may alias `a` or `b` for `lgj_mask_and`/`lgj_mask_or`. All three must
share the same parent and row count. `lgj_mask_andnot` permits the same
aliasing but is NOT commutative (`a & !b ≠ b & !a`) — its full aliasing and
tail-clearing rules are §13's, not repeated here.

### Bulk predicates (unfused — one predicate per crossing)

```
i32 lgj_op_eq_u32(u64 res, u32 lane_id, u32 needle,    u64 dst_mask)
i32 lgj_op_gt_i32(u64 res, u32 lane_id, i32 threshold, u64 dst_mask)
```

Each *overwrites* `dst_mask` with the predicate's result. Composition is the
caller's job via `lgj_mask_and`.

### Fused plan (N predicates — ONE crossing)

```
i32 lgj_plan_eval(u64 res, const LgjOpDesc* ops, u32 n_ops,
                  u64 dst_mask, u64* out_count)
```

Semantics: accumulator starts as **all rows set**; each op is evaluated and
combined into the accumulator per its `combine` field; the result lands in
`dst_mask` and its popcount is written to `out_count`. With every `combine = AND`
the result is monotonically narrowing by construction:

```
  V0 = all
  V1 = V0 ∩ op0        V1 ⊆ V0
  V2 = V1 ∩ op1        V2 ⊆ V1 ⊆ V0
```

This single call is what makes the Java fluent chain cost one crossing.

### Reduction

```
i32 lgj_reduce_sum_i32(u64 res, u32 lane_id, u64 mask, i64* out_sum)
i32 lgj_reduce_facet_sum(u64 res, u32 facet, u32 carving,
                         u64 mask, i64* out_sum)      // ABI minor >= 5, see §14
```

Sums the `I32` lane over set mask bits into a widened `i64` (no overflow for
`n_rows ≤ 2^32` on `i32` inputs).

### Parity escape hatch

```
i32 lgj_plan_eval_scalar(u64 res, const LgjOpDesc* ops, u32 n_ops,
                         u64 dst_mask, u64* out_count)
```

Identical semantics to `lgj_plan_eval` but forced down the scalar reference path.
Exists **only** so SIMD-vs-scalar parity is falsifiable *through the membrane*,
which is where the Java tests live. Not for production use.

### Graph traversal (ABI minor ≥ 4)

```
i32 lgj_hop(u64 store, u32 edge_classid, u64 facet_mask, u32 decode_mode,
            u64 src_mask, u64 dst_mask)
```

Overwrites `dst_mask` with the one-hop reachable set from `src_mask` over
`store`'s `edge_classid`-matching facets, gated by the `lance-graph-contract`
`ClassView`/`FieldMask` LAW. §13 is the full normative statement (effective
participation, decode modes, the snapshot-then-write aliasing discipline,
bounds-before-cast).

## 8. SIMD provenance

Every kernel behind these symbols routes through **`ndarray::simd`** and nothing
else. Specifically prohibited in this crate:

- `core::arch::*` intrinsics, `_mm*`, `vld1q_*`, any raw platform intrinsic
- `#[cfg(target_feature)]` / `#[cfg(target_arch)]` SIMD selection
- `core::simd` / `std::simd` / portable-simd (nightly)
- `pulp`, `SimSIMD`, `wide`, or any other SIMD crate
- a locally-written SIMD abstraction layer

If a primitive is missing, it is **added to `ndarray::simd`** under that repo's
W1a consumer contract (all backends + scalar + parity test) and consumed from
here. That is not a workaround; it is the architecture. `ndarray::simd` is the
permanent hardware membrane for the whole Ada stack, and this project is one more
consumer of it — not an exception to it.

## 9. Panics never cross the membrane

Every `extern "C"` function wraps its body in `catch_unwind`. A panic becomes a
negative status, never an unwind into JVM frames (which would be UB). The
`Cargo.toml` additionally does **not** set `panic = "abort"`, because
`catch_unwind` requires unwinding to be available.

## 10. What is deliberately absent

Named so their absence is a decision on record rather than an oversight:

- **No strings across the boundary** except the two fixed-size NUL-terminated
  name fields in the manifest. No `char*` in, ever.
- **No callbacks / upcalls.** An upcall per element is the JNI anti-pattern in
  disguise; a bulk op needs no callback.
- **No variadics.**
- **No `errno` / `captureCallState`.** Nothing here is a syscall wrapper.
- **No growable or relocatable lanes** (§4).
- **No `lgj_lane_read_element`.** There is intentionally no way to read one row
  across the membrane. If Java wants element access it reads the
  `MemorySegment` directly, in-process, with no crossing at all.
- **No `ClassView` / `WideFieldMask` yet.** The first slice is the generic
  fixture on purpose (see `architecture.md` §"generic is not toy"). Wiring the
  real `lance-graph` types is the next slice, and their shapes are already
  ABI-compatible: `WideFieldMask`'s canonical `[u64]` chunks *are* this ABI's
  `MASK_WORD` lane, and `NodeRow`'s `16|16|480` `#[repr(C, align(64))]` layout is
  already a legal lane description.

## 11. The SoA row store (ABI minor ≥ 2)

The substrate layout the whole stack converges on — operator-stated reference
(2026-08-17): **64K rows × 512 bytes per row, 32 facet lanes of 16 bytes each
(4-byte little-endian classid + 12-byte payload)**, the lance-graph V3
content-blind facet shape, enforced everywhere on the Rust side. The Java
side's *view* may differ; these bytes are the substrate truth. One buffer,
zero serialization: every access — Rust kernel or Java segment read — is a
*reading* of the same bytes.

### Resource

```
LGJ_RESOURCE_ROWSTORE = 3
i32 lgj_rowstore_open(u64 n_rows, u64 seed, u64* out_handle)
```

Deterministic generator (normative; two SplitMix64 draws per facet, `a` then
`b`, 64 draws per row — full statement in `rowstore.rs`'s doc):

```
classid = (a >>> 33) & 0xF            // same recipe as the fixture's class lane
payload = le64(b) ++ le32(a & 0xFFFFFFFF)
```

### Lanes (described through the UNCHANGED `LgjLaneDesc` — `stride_bytes`
anticipated this since minor 1)

| lane id | what | kind | stride | flags |
|---|---|---|---|---|
| `0` | the raw buffer, `n_rows * 512` bytes | `U8` | 1 | `READABLE \| CONTIGUOUS` |
| `1 + f` (f in `0..32`) | facet `f`'s classid column | `U32` | 512 | `READABLE` |

`byte_len` is the **exact covered span** `(len_elems - 1) * stride_bytes +
elem_bytes` (0 when empty) — for a strided facet lane this deliberately does
NOT round up to `len * stride`, because the lane's base sits `f*16` into the
buffer and a full-stride final window would let Java bound a segment past the
allocation's end. For contiguous lanes the formula reduces to
`len * elem_bytes`, unchanged from minor 1.

### Operations

```
i32 lgj_op_eq_classid(u64 res, u32 facet, u32 needle, u64 dst_mask)
i32 lgj_row_facet_match(u64 res, u32 needle, u32* out, u64 out_len_elems)
```

- `lgj_op_eq_classid` overwrites `dst_mask` with the row mask
  `classid(facet, row) == needle`. `facet` is a facet index `0..32`, not a
  lane id. The result is an ordinary mask: `lgj_mask_create` accepts a row
  store as parent (masks may parent onto a pattern OR a row store — both are
  read-only row-shaped resources), and the whole §7 mask algebra
  (`and`/`or`/`count`/`describe`) applies unchanged.
- `lgj_row_facet_match` writes, for every row, a `u32` bitset of which of its
  32 facets carry `needle` as classid — into the **caller's** buffer (a
  Java-arena segment; zero-copy out, nothing serialized). Capacity is checked
  BEFORE anything is written (`MASK_LENGTH_MISMATCH` on a short buffer).

### SIMD provenance (unchanged §8 rule, applied)

`lgj_op_eq_classid` routes through `ndarray::simd::eq_u32_strided_to_mask`
(scalar strided loads — at stride 512 each element is on its own cache line,
so the walk is memory-bound and SIMD earns its keep in the 16-wide compare).
`lgj_row_facet_match` wraps the store's bytes in
`ndarray::simd::MultiLaneColumn` (an `Arc` refcount bump, no copy) and answers
four facets per 64-byte chunk with one `U32x16::eq_bitmask`.

### Alignment (stated honestly)

The buffer base is `u8`-aligned (`Arc<[u8]>`; stable Rust promises no more).
Rows are 512-byte strided within it. Nothing in this slice needs more — Java
reads via `JAVA_INT_UNALIGNED`-class layouts, and every `ndarray::simd` load
is a register fill. The 64-byte-aligned base guarantee arrives with the real
`NodeRow` (`#[repr(C, align(64))]`) wiring.

## 12. The edge-bearing row store (ABI minor ≥ 3)

`consumer-graph-traversal-v1.md`'s falsifiers need a 1-2 hop BFS that is
*non-vacuous* — plain `lgj_rowstore_open`'s payload is uniform noise, so any
hop over it saturates to nearly every row within one or two steps
(`.claude/harvest/graph_density_probe.rs`'s own measurement). This is an
alternative **constructor**, not a new resource kind, new lane shape, or new
mask op — `LGJ_RESOURCE_ROWSTORE` and the whole §11 lane/operation surface
apply to its output unchanged.

### Resource

```
i32 lgj_rowstore_open_with_edges(u64 n_rows, u64 seed,
                                 u32 edge_classid, u64 edge_gate_mask,
                                 u32 edge_radius, u64* out_handle)
```

Reuses §11's classid stream **byte-for-byte** — same two SplitMix64 draws per
facet, same `classid = (a >>> 33) & 0xF`. For a facet whose classid equals
`edge_classid` AND whose draw clears the sparsity gate (`a & edge_gate_mask
== 0`), the 12-byte payload instead carries a **structured target row**:

```
span   = 2 * edge_radius + 1
offset = (b % span) - edge_radius              // signed, |offset| <= edge_radius
target = (row + offset) mod n_rows              // bounded-local-neighbourhood
payload_lo64 = target as u64                    // hi32 forced to 0 (the flag
payload_hi32 = 0                                //  a hop reads to know "structured")
```

Every other facet (classid mismatch, or gate not cleared) is byte-identical
to `lgj_rowstore_open`'s plain draw (`payload = le64(b) ++ le32(a &
0xFFFFFFFF)`) — so an **out-of-range `edge_classid`** (one that never occurs
in the classid stream, e.g. `16`) reproduces `lgj_rowstore_open` exactly,
which is how `RowStore::generate_with_edges`'s own test suite proves the two
generators share one code path rather than drifting apart.

Density is governed by `edge_gate_mask`: the gate probability is
`1 / (16 * (edge_gate_mask + 1))` (16 candidate classids × the mask's extra
selectivity), so `edge_gate_mask = 0` is the densest edge-bearing setting.
`edge_radius` must be `< n_rows` (an unsatisfiable bound is
`LGJ_ERR_LENGTH_OVERFLOW`, matching every other overflow-shaped rejection in
this generator family).

### A hop, at the Java layer (no new op — composition of existing symbols)

> **⊘ SUPERSEDED (2026-08-18, D-LGJ-W8).** The Java-side composition
> described below is exactly the row-population-hydration shape the
> mask-native navigation correction (`.claude/plans/mask-native-navigation-correction-v1.md`)
> demotes: per-row `payload_lo64` decode + a Java-side scatter loop is a
> materialised-population execution path, not a mask-native one. The
> shipped path — required, not merely preferred (spec §3.8: consumers
> cannot reach the segment surface at all; `ApiSurfaceTest` walls off
> `internal.ffm`) — is the native **§13** `lgj_hop`, ABI minor ≥ 4. This
> subsection is kept, not deleted, as the record of the D1a composition it
> replaces.

A hop is `lgj_row_facet_match(edge_classid)` (§11, unchanged) to find which
of a row's facets carry the edge classid, followed by a **Java-side** decode
of each matched facet's `payload_lo64` as the target row and a scatter into
the next mask via `lgj_mask_describe`'s WRITABLE lane (§7) — one crossing per
hop, zero new ABI surface. See `consumer-graph-traversal-v1.md` Decision D1a.
A native `lgj_hop` symbol (D1b) remains a future ABI minor if Java-side
scatter measurably dominates; it is not part of this minor.

### SIMD provenance (unchanged §8 rule, applied)

The generator's per-facet loop is scalar (SplitMix64 is inherently
sequential per draw); the sparsity gate and target-row arithmetic are cheap
integer ops on that same sequential stream — no new SIMD kernel, matching
`RowStore::generate`'s own scalar generation loop.

## 13. Mask complement + one-hop graph traversal (ABI minor ≥ 4)

Two additions, both consuming the `class_view_provider::edge_participation`
seam (`.claude/plans/mask-native-navigation-correction-v1.md`, D-LGJ-W8,
§3.3): `lgj_mask_andnot` closes a real gap in the mask algebra — no
and-not/complement op existed anywhere in the mask/registry system before
this minor — and `lgj_hop` is the FIRST symbol in this ABI whose semantics
are governed by the `lance-graph-contract` crate rather than by this
crate's fixture alone: a facet only participates in a hop if BOTH the
caller's `facet_mask` and the class's `ClassView`-resolved
`edge_participation` agree it should.

Both are **bulk** (§6): `lgj_mask_andnot` does work ∝ `n_rows / 64` words;
`lgj_hop` does work ∝ `n_rows · popcount(effective participation)` — at
most `n_rows · 32` facet-classid compares, plus one scalar decode+scatter
per matched `(row, facet)` pair.

### Mask complement

```
i32 lgj_mask_andnot(u64 a, u64 b, u64 dst)
```

`dst = a & !b`, word-wise. Same parent/row-count compatibility rule as
`lgj_mask_and`/`lgj_mask_or` (§7): all three masks must share the same
parent and row count, or `MASK_LENGTH_MISMATCH`.

`dst` may alias `a`, `b`, or both — **unlike AND/OR, ANDNOT is not
commutative**, so aliasing `dst == b` is NOT the same case as `dst == a`
with the roles swapped: the kernel snapshots `b`'s value into a scratch
buffer before it is overwritten whenever `dst` aliases `b`, so the result
is always `a & !b` as evaluated BEFORE the call, regardless of which
argument `dst` aliases.

**Tail rule (normative).** Bits at row index `>= n_rows` in the final word
are always zero on return, re-established as a *distinct, defensive* step
after the complement — never merely inherited from well-formed inputs. A
corrupted operand's tail bits (bits set past `n_rows` by an out-of-band
write) are silently REPAIRED, not merely preserved: `!b`'s tail naturally
sets bits wherever `b`'s own tail is zero (the well-formed case), so a
version of this kernel without the explicit clear would leak a corrupted
`a`'s stray tail bits straight into `dst`.

Kernel: `ndarray::simd::{mask_andnot, mask_andnot_assign}` (§8 SIMD
provenance, unchanged rule) — never `ndarray::simd_int_ops` directly.

### One-hop graph traversal

```
i32 lgj_hop(u64 store, u32 edge_classid, u64 facet_mask, u32 decode_mode,
            u64 src_mask, u64 dst_mask)
```

`store` must be a `LGJ_RESOURCE_ROWSTORE` (§11); `src_mask` and `dst_mask`
must be row-count-compatible with it — the same row-count-only reading
`lgj_op_eq_classid` already uses (a mask from an equally sized but
distinct resource is accepted; `MASK_LENGTH_MISMATCH` otherwise).

**Semantics.** `dst_mask` is OVERWRITTEN with the one-hop reachable set:
for every row `r` set in `src_mask`, for every facet `f` in the *effective
participation* where `classid(r, f) == edge_classid`, if the selected
decode mode yields a valid target `t < n_rows`, bit `t` is set in the
result.

**Effective participation** is `facet_mask ∩
edge_participation(edge_classid)` — the caller's requested facets narrowed
by what the class's `ClassView` provider
(`class_view_provider::edge_participation`, D-LGJ-W8 §3.3) says this
class's edges actually occupy. `facet_mask` is the wire form of the
contract's `FieldMask`: a `u64` whose bits `>= 32` are ignored (this store
has 32 facets).

**Decode modes.** `decode_mode = 0` is the §12 fixture convention:
`payload_hi32 == 0` marks a structured edge, `payload_lo64` is the LE
target row. Modes `1..=3` are RESERVED — mirroring `EdgeCodecFlavor as u32
+ 1` (`canonical_node.rs`) — and return the new status
`LGJ_ERR_UNSUPPORTED_DECODE_MODE = -14` until real class data lands. This
check runs FIRST, before `store`/`src_mask`/`dst_mask` are even resolved,
so `dst_mask` is provably untouched (`out_*` write-only-on-OK, §7) on a
rejected call regardless of whether the other arguments would themselves
have been valid.

**Aliasing.** `src_mask` is snapshotted (its words copied into an owned
buffer) under a READ lock that is fully RELEASED before `dst_mask`'s
WRITE lock is taken. This is a DIFFERENT discipline from
`lgj_mask_and`/`lgj_mask_or`/`lgj_mask_andnot`'s dedup-before-lock scheme
(registry.rs `lock_masks_ordered`, needed there because those calls hold
two or three mask locks simultaneously): `lgj_hop` never holds more than
one mask lock at a time, so `dst_mask == src_mask` aliasing carries zero
deadlock risk by construction, not by case analysis.

**Bounds-before-cast (normative).** The decoded target `t` is compared
against `n_rows` as a `u64` BEFORE any `t as usize` cast — the ordering is
part of the contract, not an implementation detail, so an out-of-range
`u64` target can never reach an indexing operation.

**Kernel composition.** The classid-match sub-step for each participating
facet routes through the EXISTING sanctioned primitive
(`kernels::simd_rowstore_classid_mask`, `ndarray::simd::eq_u32_strided_to_mask`
— the same kernel `lgj_op_eq_classid` uses, §11) into a scratch word
buffer that is REUSED across every participating facet, never reallocated
per facet. Only the resulting set-bit walk + payload decode + scatter is
scalar: there is no `ndarray::simd` primitive for gather-decode-scatter,
and duplicating the classid compare in scalar Rust would be exactly the
polyfill bypass §8 forbids.

### Bulk-rule conformance (§6, applied)

Neither symbol is lifecycle, and neither is a fixed-cost call: a caller
that doubles `n_rows` observes roughly double the work in both — for
`lgj_mask_andnot`, twice the mask words; for `lgj_hop`, twice the rows
scanned per participating facet. Both therefore satisfy §6's anti-JNI rule
by the same test every existing symbol satisfies it by: work proportional
to `n_rows`, never one crossing per element.

## 14. The mask-native sweep (ABI minor ≥ 5)

One symbol, and it is the **execution half of a mask path whose build half has
existed since minor 2**. That framing matters more than the addition: before
this minor the membrane could already turn a classid column into a mask
(`lgj_op_eq_classid` → `ndarray::simd::eq_u32_strided_to_mask`, §11) and could
already compose masks (`and`/`or`/`andnot`/`count`, §7/§13) — what it could not
do was *consume* a mask against the 12-byte facet register. That gap is why a
consumer wanting the shape had to leave the membrane.

```
i32 lgj_reduce_facet_sum(u64 res, u32 facet, u32 carving,
                         u64 mask, i64* out_sum)
```

Sums every group of `facet`'s 12-byte register, under `carving`, over the rows
`mask` selects, widened to `i64`. Work is proportional to the mask's
**popcount**, so an empty mask is O(words) and §6's bulk-or-lifecycle rule
holds by construction.

### The carving is a caller-supplied, validated parameter — not a ClassView consult

`carving` selects one of `le-contract.md` §3's three readings of the *same* 12
bytes:

| wire | reading | groups × bytes |
|---|---|---|
| `0` | rails | `6 × (u8:u8)`, LE `u16` |
| `1` | SPO triplets | `4 × (u8:u8:u8)`, LE `u24` zero-extended |
| `2` | odoo quads | `3 × (u8:u8:u8:u8)`, LE `u32` zero-extended |

Anything else is `LGJ_ERR_UNSUPPORTED_CARVING` (`-15`), checked **first**,
before the store or mask are resolved, so `out_sum` is provably untouched on a
rejected call. An unknown reading must never alias a known one.

This follows `lgj_hop`'s `decode_mode` precedent (§13, spec §3.4) rather than
`edge_participation`'s ClassView consult, and the reason is the point of the
symbol: **the reading is what the caller already resolved from the ClassView
before crossing.** Re-resolving it per row inside the sweep would put the
question back in the hot loop — exactly what this symbol exists to take out of
it. A per-row ClassView consult here would be the mask-native law's own defect,
one layer down.

### Mask parentage

The mask must belong to **this** store, not merely match its row count — an
equal-length mask over a different resource is a different population wearing
the right size. Rejected with `LGJ_ERR_MASK_LENGTH_MISMATCH`, matching the mask
algebra's own parent check (`lgj_mask_and`) rather than minting a second
spelling for the same rejection.

### SIMD provenance (§8), stated honestly

**This kernel is scalar, deliberately, and the vector form is a NAMED GAP.**
`ndarray::simd` has no primitive for "gather a sub-word group out of a
512-byte-strided register under a runtime grouping and widen-accumulate":
`masked_sum_i32` is contiguous `i32`, and `eq_u32_strided_to_mask` reads one
aligned `u32` per row, not six unaligned `u16`s. Writing raw intrinsics here
would create precisely the second SIMD surface §8 exists to prevent, so the
scalar form is the in-bounds implementation and the vector form belongs in
`ndarray::simd` under the W1a consumer contract — added **there** and consumed
here, never re-implemented at this layer.

Sub-word loads are byte-wise rather than `u16`/`u32` reads because a group's
offset is `facet*16 + 4 + g*group_bytes`, which is not guaranteed aligned for
the 3-byte reading, and an unaligned wide read is UB in Rust even where the
hardware tolerates it.

### Why this shape, measured

`valhalla-lab/reproducers/R8_EntropyBoundary.java` (merged PR #24) measured the
alternatives on identical bytes with checksum-identical results. Under a
*random* classid distribution, a generic sweep that re-derives the carving per
row collapses, while both a materialised index-list partition and a mask-driven
sweep recover ~4.8×. The mask is the lawful of the two — an index list is a
materialised population, which the root `CLAUDE.md` forbids as internal
currency — and it is also the cheaper: building it via
`eq_u32_strided_to_mask` was an order of magnitude cheaper than a scalar
partition scan, moving break-even from ~120 passes to ~10. Obeying the law is
the fast path, not a tax on it.

The control leg is equally load-bearing: under a *predictable* classid pattern
the same measurement shows specialization buying nothing. This symbol is worth
calling when there is entropy to remove, and not otherwise.

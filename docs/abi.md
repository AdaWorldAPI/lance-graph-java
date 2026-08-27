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

- It is **small** — currently 24 symbols (unchanged at minor 8, which adds
  manifest FIELDS and no symbol; the "14" this line carried
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
LGJ_ABI_MINOR = 8    // additive change ⇒ bump; older Java may still load
LGJ_MAGIC     = 0x4C_47_4A_5F_41_42_49_00   // "LGJ_ABI\0" big-endian-read
```

Rule: **Java requires `major` to match exactly and `minor` to be `>=` what it
was compiled against.** A `major` mismatch is a hard failure, not a warning.

The magic doubles as an endianness probe: read as a `u64` little-endian it yields
a known constant; anything else means the library was built for a different byte
order and every subsequent read would be garbage.

### Backward compatibility is enforced, not merely promised

§2's additive-minor promise ("an older Java build against a newer `.so` always
loads") has a second direction the promise itself does not cover: a **newer Java
against an older `.so`**. That case is governed by `Abi.requireMinor(N)`, whose
contract is to fail before the feature's downcall is attempted, naming the
minor.

The **load gate itself** requires only the manifest's BASE PREFIX — everything
through `build_profile`, 104 bytes, the field set minor 1 defined
(`Layouts.MANIFEST_BASE_BYTES`). Requiring the full layout this Java build knows
about would make every future manifest field a hard incompatibility with every
older artifact, in flat contradiction of the promise above. It was written that
way until minor 8 grew the struct and the contradiction became reachable;
measured, restoring the full-layout gate makes all four historical libraries
fail to load outright. Fields past the base prefix are read only when the
library's own `size_of_manifest` covers them AND its minor is high enough — both
conditions, because a manifest that claims a minor it is too short to carry is a
broken artifact and reading it would produce plausible garbage.

**That guard was defeated by eager class initialization until 2026-08-25.**
Every downcall handle was resolved in `Downcalls.<clinit>`, so a single absent
symbol broke the whole class and the guard never ran. Measured with the Java of
that day against real libraries built from this repo's own history:

| library | `SmokeTest` (uses nothing newer than minor 1) died on |
|---|---|
| minor 1 | `lgj_rowstore_open` — a **minor-2** symbol |
| minor 2 | `lgj_rowstore_open_with_edges` — minor 3 |
| minor 3 | `lgj_mask_andnot` — minor 4 |
| minor 4 | `lgj_reduce_facet_sum` — minor 5 |

Note the severity: against the minor-1 library, **minor-1 operations could not
run either.**

The fix is one lazy holder class per minor (`Minor2`/`Minor3`/`Minor4`/`Minor5`
in `Downcalls`), initialised on first *access*. The minor-1 base surface stays
eager deliberately — a library missing any of it is not an older library, it is
a wrong one, and that failure should be immediate and total.

`OldAbiCompatTest` is the falsifier. It takes
`-Dlgj.oldlibrary=…` and checks each minor **in whichever direction the loaded
library calls for**: available ⇒ the feature must actually work; absent ⇒
`AbiMismatchException` naming that minor, never a bare missing-symbol failure
and never a failure of some *other* minor's feature. Both directions are
required — a gate that rejected everything would satisfy a rejection-only test.

### Minor version history

- **Minor 2** (2026-08-17) — the SoA row store (§11).
- **Minor 3** (2026-08-18) — the edge-bearing row store (§12).
- **Minor 4** (2026-08-18, D-LGJ-W8) — `lgj_mask_andnot` (mask complement)
  and `lgj_hop` (one-hop graph traversal, gated by the
  `lance-graph-contract` `ClassView`/`FieldMask` LAW — §13).
- **Minor 8** (2026-08-25) — the manifest carries the register groupings as
  DATA (§17): `carving_count` + `carvings`. **No new symbol** and no new
  status; it is the first growth of the manifest STRUCT, which is why it is
  also the change that made Java's load gate require only the base 104-byte
  prefix rather than the full layout.
- **Minor 7** (2026-08-25) — `lgj_row_layout_probe` (§16): the whole-row
  alignment answer, all 32 facets in one crossing. No new status.
- **Minor 6** (2026-08-25) — `lgj_reduce_facet_sum_resolved` (§15): the same
  sweep, but under the grouping the POPULATION resolves to via
  `ClassView::cascade_shape`, rather than one the caller asserts. One new
  status: `UNRESOLVED_CARVING` (-17).
- **Minor 5** (2026-08-25) — `lgj_reduce_facet_sum` (§14): the mask path's
  missing EXECUTION half. The build half (`lgj_op_eq_classid`) has existed
  since minor 2; nothing could consume a mask against the 12-byte facet
  register until now. Two new statuses: `UNSUPPORTED_CARVING` (-15) and
  `SUM_OVERFLOW` (-16).

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
| `-15` | `UNSUPPORTED_CARVING` | `lgj_reduce_facet_sum` called with a `carving` outside `0..=2` (§14, ABI minor ≥ 5) |
| `-17` | `UNRESOLVED_CARVING` | `lgj_reduce_facet_sum_resolved`'s population does not resolve to one grouping — mixed classes, an unanswerable classid, or empty (§15, ABI minor ≥ 6) |
| `-16` | `SUM_OVERFLOW` | `lgj_reduce_facet_sum`'s accumulator exceeded `i64`; `out_sum` is NOT written (§14, ABI minor ≥ 5) |

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
    // ── minor 8; everything above is the 104-byte BASE PREFIX the load gate
    //    requires, and all a pre-minor-8 artifact carries (§17) ──
    pub carving_count:          u32,
    pub carvings:               [u16; 8],  // (groups << 8) | group_bytes, wire order
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

## 7. The function surface (24 symbols)

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
i32 lgj_reduce_facet_sum_resolved(u64 res, u32 facet, u64 mask,
                                  i64* out_sum, u32* out_carving)  // minor >= 6, §15
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

**Kernel composition.** Selection is mask algebra —
`src ∧ class_f ∧ struct_f` per participating facet, accumulated into `dst`:

| operand | how it is produced |
|---|---|
| `class_f` | facet `f` carries the edge class |
| `struct_f` | facet `f`'s `payload_hi32 == 0`, i.e. a structured edge |
| `src` | the caller's frontier, snapshotted under a read lock |

Both predicates are the SAME sanctioned primitive at two offsets into the
16-byte facet — `kernels::simd_rowstore_u32_eq_mask`,
`ndarray::simd::eq_u32_strided_to_mask` (the kernel `lgj_op_eq_classid` also
uses, §11) — with `first_offset = f*16 + 0, needle = classid` for the class
and `first_offset = f*16 + 12, needle = 0` for the gate. The ANDs are
`ndarray::simd::mask_and_assign`, word-parallel over 64 rows at a time. Two
scratch word buffers are REUSED across every participating facet, never
reallocated per facet.

**No row is examined to decide whether it participates.** Only the
resulting set-bit walk + payload decode + scatter is scalar, and only
because the destination row index is DECODED from the selected row's
payload: that is the operand of a permutation, not a decision about
membership. There is no `ndarray::simd` primitive for decode-scatter, and
duplicating either predicate in scalar Rust would be exactly the polyfill
bypass §8 forbids.

> The structured-edge gate was an `if` inside the row walk until 2026-08-27
> — in every earlier shape of this function, including the first mask-shaped
> one. It was always this call; the kernel already took an arbitrary
> `first_offset`.

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
`mask` selects. Cost is **`O(mask_words + popcount × groups)`** — the mask-word
scan is unconditional, so an empty mask costs one pass over the mask rather than
nothing. §6's bulk-or-lifecycle rule holds either way: no term is
per-crossing-per-element.

### Overflow is reported, never wrapped

`i64` is **not closed** under this reduction. Under the quads reading one row
contributes up to `3 × (2³² − 1) = 12 884 901 885`, so `i64::MAX` is exceeded
after ~715 827 882 maximum-valued selected rows — about 341 GiB of 512-byte
rows, which is inside the scale this substrate contemplates rather than safely
beyond it. The kernel accumulates in `i128` (which cannot overflow: the per-row
bound × `u64::MAX` rows still fits) and range-checks once, returning
`LGJ_ERR_SUM_OVERFLOW` with `out_sum` untouched. A silent wrap would be exactly
the plausible-but-wrong answer this ABI otherwise works to prevent.

### The carving is a caller-supplied, validated parameter — not a ClassView consult

`carving` selects one of `le-contract.md` §3's three readings of the *same* 12
bytes:

| wire | reading | groups × bytes |
|---|---|---|
| `0` | rails | `6 × (u8:u8)`, LE `u16` |
| `1` | SPO triplets | `4 × (u8:u8:u8)`, LE `u24` zero-extended |
| `2` | odoo quads | `3 × (u8:u8:u8:u8)`, LE `u32` zero-extended |

**Since minor 8 this table is DESCRIPTIVE, not normative** — see §17. The
encoding is derived from the contract's `CascadeShape::ROTATIONS` (group count,
descending) and SERVED in the manifest; a reader that needs the authoritative
answer reads `carvings`, and this row set is what that derivation currently
produces. Before minor 8 it was one of three hand-written copies, which is the
problem §17 exists to remove.

Anything else is `LGJ_ERR_UNSUPPORTED_CARVING` (`-15`), checked **first**,
before the store or mask are resolved, so `out_sum` is provably untouched on a
rejected call. An unknown reading must never alias a known one.

This follows `lgj_hop`'s `decode_mode` precedent (§13, spec §3.4) rather than
`edge_participation`'s ClassView consult. Re-resolving the reading per row
inside the sweep would put the question back in the hot loop — exactly what this
symbol exists to take out of it, and a per-row ClassView consult here would be
the mask-native law's own defect one layer down.

**But be precise about what that does and does not establish.** This symbol is a
RAW REINTERPRETATION primitive. It applies the carving it is handed to every
selected row; it does not — and cannot — verify that this is the reading those
rows' classes specify. A mask is an opaque population (it may be an
`lgj_mask_or`/import union spanning several classids), and the fixture
`ClassView` carries no carving resolver at all today. The Java surface is named
`facetSumAs` for exactly this reason: the caller supplies the reading and owns
its correctness.

The stronger shape binds the answer to the population ONCE, so the sweep
receives an answer rather than a promise:

```
classid → ClassView → ResolvedCarving → (population + its carving) → sum
```

That keeps the property this path exists for (the ALU gets the answer, not the
question) while making the binding checkable. It needs a real ClassView carving
resolver upstream, which does not exist yet, so it is recorded here as the next
rung — deliberately not faked with a per-row consult.

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

## 15. The resolved sweep (ABI minor ≥ 6)

§14 shipped `lgj_reduce_facet_sum` with an honest caveat: it applies a grouping
the caller *asserts*, and cannot check it, because a mask is an opaque
population and nothing on `ClassView` returned a grouping. **That second half is
now false**, and this section is the consequence.

```
i32 lgj_reduce_facet_sum_resolved(u64 res, u32 facet, u64 mask,
                                  i64* out_sum, u32* out_carving)
```

For every selected row it resolves `facet classid → ClassId →
ClassView::cascade_shape` and requires every row to agree, then sweeps
monomorphically under that answer, reporting the grouping back through
`out_carving`. The shape the §14 note named as the next rung, built:

```
classid → ClassView → ResolvedCarving → (population + its grouping) → sum
```

**The question is asked once at the population's edge and never inside the
sweep.** Resolution is `O(mask_words + popcount)`; the sweep that follows carries
no per-row dispatch. Resolving per row would have been the defect this whole
path exists to avoid — the fix for "unverified" was never "consult more often".

### The resolver was upstream all along

`CascadeShape` (`lance_graph_contract::facet`) has carried the three groupings
— `G6D2` rails, `G4D3` triplets, `G3D4` quads, each `G·D = 12` — together with
the full algebra and its own statement that the grouping is *"class-conditioned:
`classid` selects it from the inherited schema"*. What did not exist was a
`ClassView` method RETURNING one, which is why §14 minted a local `Carving`
enum and documented that it claimed no authority.

`ClassView::cascade_shape` (contract, 2026-08-25) is that missing accessor,
following the exact registry-resolution pattern of its four siblings
(`edge_codec_flavor` / `rail_carving` / `band_reading` / `value_schema`), with
`G3D4` — `CascadeShape`'s own "canonical GUID shape" — as the zero-fallback.
The local enum is now a `pub type Carving = CascadeShape` alias; only the u32
**wire encoding** stays local, because a `#[repr]` discriminant is not part of
the contract's promise. That mapping is pinned **by group count**, so a variant
reorder upstream cannot silently re-map the wire.

This also required widening the G11 contract-import fence by one module —
`facet`, alongside `class_view`/`canonical_node`/`ontology`. Deliberate and
recorded, not incidental.

### Failure is the interesting case

`LGJ_ERR_UNRESOLVED_CARVING` (-17) covers three causes and one fact — the
population spans classes that read the register differently, a row's classid has
no `ClassView` answer, or the population is **empty**. Zero rows carry zero
classes, so reporting the zero-fallback there would be inventing an answer.
Neither output is written.

### Why the fixture provider varies its answer

`FixtureClassView::cascade_shape` returns `class % 3` rather than the trait's
constant zero-fallback. That is a fixture choice, stated as one: a constant
answer makes every population trivially homogeneous, so the "does this resolve
to ONE grouping" guard could never fire and a test for it would pass for an
implementation that never checked. Varying makes both outcomes reachable on real
fixture data — a `lgj_op_eq_classid` mask is single-class and resolves; a union
across classids with different groupings is refused; and a union across classids
that *share* a grouping still resolves, which is the paired half that stops the
refusal from being "reject everything".

### Which of the two symbols to call

**`lgj_reduce_facet_sum_resolved` is the one to reach for.** §14's
`lgj_reduce_facet_sum` remains as the deliberate reinterpretation escape hatch —
for a caller that means to read the register under a grouping the class does not
sanction — and its Java name (`facetSumAs`) says so.

### On whether `sum` earns permanent ABI vocabulary

Recorded because §1 makes symbol growth a design smell that needs justification,
and §14's justification was weak: the operation came from a benchmark's checksum.
It is retained, with a better reason and a falsifiable condition to revisit.

**The reason:** it is the only mask-CONSUMING operation over the register. Without
it the mask path has a build half and no execution half, and a consumer wanting
that shape must leave the membrane — which is the pressure §6's anti-JNI rule and
the Missing-capability STOP rule both exist to relieve. `sum` is additionally the
cheapest operation that cannot be faked from the outside: any correct
implementation must visit exactly the selected rows and decode exactly the
resolved grouping, which is why it doubles as the parity oracle for both.

**The condition to revisit, stated so it can actually fire:** if a second
reduction is ever needed (min/max/count-distinct/histogram), do NOT add a second
symbol. That is the point at which the operation should be generalised — an
op-code parameter on one reduce symbol, mirroring how `lgj_plan_eval`'s
`LgjOpDesc` already generalises predicates — and `sum` becomes op-code 0. Two
reduction symbols would be the smell §1 warns about; one parameterised symbol is
the shape this ABI already uses elsewhere.

### Two memos, at two lifetimes

Resolution is a per-CLASS fact consulted over a population, so it is cacheable at
two different lifetimes and both are wired.

**Per dataset — `classid → grouping`.** `RowStore` carries a `OnceLock` table
built on first resolved sweep: `class_id_for` narrows a `u32` classid to `u16`,
so the table is 65 536 one-byte entries (`0` = no `ClassView` answer, else the
wire value plus one). 64 KiB, 65 536 `ClassView` calls once, then never again.
That trade is the right way round — the table is bounded and one-off, while the
per-row consult it replaces is unbounded in sweeps. A `OnceLock` rather than a
`LazyLock` because the resolver is supplied by the caller; the first caller wins
and every later one reads.

**Per population — the resolved grouping itself.** The memo lives *inside*
`MaskWords`, so it is read under the same lock that guards the words: a
resolution can never be observed against a population it was not computed from.

Three properties make it safe rather than merely fast:

- **Keyed by facet, not merely cached.** Different facets of the same rows carry
  different classids and can resolve differently. A memo holding only "the
  grouping" would answer a question about facet 3 with facet 7's answer — wrong,
  not stale.
- **Invalidation cannot be forgotten.** It happens in `write_mask()` and
  `lock_masks_ordered()` — the only two ways to obtain the right to mutate a
  mask. Any writer invalidates whether or not it changes a bit: conservative,
  and impossible for a new mask-mutating op to skip.
- **Filled under the READ guard.** The obvious alternative — compute under read,
  upgrade to write, store — is wrong twice: it would clear the very memo it is
  storing (that is what a write guard means here), and between dropping the read
  and taking the write another thread could change the population, so the stored
  value would describe rows that no longer match. Holding the read guard is
  exactly the interval in which the population is stable, so the fill belongs
  there. Hence an atomic field rather than a plain `Option`.

The encoding carries an explicit present bit, so `facet 0, grouping 0` is a real
answer rather than reading as an empty memo.

A test-only counter (`RESOLUTIONS`) makes the memo's behaviour observable rather
than asserted: the first sweep resolves, five repeats over the same population do
not, a different facet does, and a rewritten population does.

## 16. The whole-row layout probe (ABI minor ≥ 7)

```
i32 lgj_row_layout_probe(u64 res, u64 mask, u8* out, u64 out_len)
```

For **every** facet, the SET of register groupings its selected rows carry. One
crossing covers all 32 — asking per facet would be 32 crossings, and is how a
consumer drifts into the per-element loop §6 forbids.

### Alignment as arithmetic, not a scan

Each output byte is a 3-bit set (bit `w` = some row resolves to grouping `w`)
plus bit 3 for "some row's classid has no `ClassView` answer". Then:

```
aligned(facet)  ⟺  popcount(byte) == 1  &&  byte & UNANSWERABLE == 0
```

One `or` per (row, facet), no comparison and no early exit, so cost does not
depend on the data. **An OR-accumulated set is exact where cheaper accumulators
are not:** a sum of wire values cannot tell `{0,2}` from `{1,1}`, and an XOR
cannot tell `{1,1}` from `{}`. The set forgets multiplicity, which is precisely
the information the question does not need.

`0` means the EMPTY set — no row selected — and is deliberately distinguishable
from disagreement. Conflating them would report "misaligned" for a population
that simply is not there.

### What it measured, immediately

A mask from `lgj_op_eq_classid(facet 3, …)` constrains **facet 3 only**; the
other 31 facets of those rows carry whatever classids the generator gave them.
Measured on the fixture: **1 of 32 facets aligned.** A test asserting
`isFullyAligned()` there failed, and the expectation was wrong rather than the
code — which is exactly the confusion a whole-row probe exists to remove.

Note what this says about **placement**: the canon makes `classid` the key's
prefix precisely so key-ordered placement clusters a class into a range. The
fixture generates classids uniformly at random instead — measured mean run
length **1.07**, mean gap ~17 rows. Clustering is worth doing, and measured at
this stride it is worth ~2× (12.6 vs 25.6 ns/row for the same population size,
contiguous vs every-16th-row) — not from cache-line count, which is one line per
row either way at a 512-byte stride, but from stride-predictable prefetch and TLB
locality.

### A classid is a GLOBAL address

The `classid → grouping` table is process-global (`LazyLock`, 64 KiB, built
once), not per dataset: the same classid means the same class in every SoA, so
the resolution is dataset-independent. An earlier version put it on `RowStore`,
which was wrong in shape rather than output — the answers were right, but the
placement implied two datasets could disagree about what a classid carves into,
which the address space does not permit.

And the table captures **layout only**. Meaning, RBAC, ontology category and
render template are separate resolutions off the same address; none belong in it
and none can be inferred from it.

---

## 17. The register groupings, served as data (ABI minor ≥ 8)

The manifest grew two fields. No symbol, no status, no call:

```
u32  carving_count      // populated entries in `carvings`
u16  carvings[8]        // entry w = wire value w, packed (groups << 8) | group_bytes
```

Entries past `carving_count` are zero, so a reader that trusts the count and one
that scans for a terminator agree. The struct is 128 bytes (108 + 16 = 124,
rounded to its 8-byte alignment).

### Why this exists

The wire encoding of §14's `carving` parameter was hand-written in **three**
places — a Rust `match`, a Java `enum`, and §14's own table — with nothing that
would fail if they disagreed. Three copies of one fact is not a documentation
problem; it is a correctness problem with no falsifier, and the specific failure
it invites is silent: a grouping added or reordered upstream re-maps one copy and
not the others, and a sweep then reads the same 12 bytes under the wrong reading
and returns a plausible number.

So the fact now has one source and two derivations:

1. **The contract owns the SET.** `lance_graph_contract::facet::CascadeShape::ROTATIONS`.
2. **This ABI derives the ENCODING from it** — group count, descending
   (`kernels::CARVING_ORDER`, a `const`). A variant REORDER upstream cannot
   re-map the wire, because position is computed from `groups()` rather than
   from declaration order. A variant ADDED upstream appears automatically, in
   its group-count place, with no edit.
3. **The manifest SERVES the result**, and Java reads it rather than restating
   it.

`CARVING_ORDER` is deliberately a `const` and not a `LazyLock`: the manifest is
const-initialised, and a runtime-initialised order could not be reached from it.

### Why the manifest rather than a new symbol

The manifest already exists so Java can discover the ABI's SHAPE instead of
declaring it — sizes, alignments, pointer width, byte order. A wire encoding is
exactly such a shape. Serving it here costs no symbol, no crossing at call time,
and no lifetime question (a fixed `u16[8]` rather than a pointer), and it arrives
on the same read Java already performs at load.

### What is still declared on the Java side, and why that is correct

`Carving`'s ARITY stays declared: `RAILS_6X2` named anything other than `6 × 2`
would be a lie in its own name. What is no longer declared is its wire value —
that is looked up in the served table by arity. Meaning is declared; encoding is
served.

### The falsifiers

- Rust, `the_manifest_serves_exactly_the_derived_carving_order` — the served
  bytes against the derived order, including that each entry decodes back to its
  own shape, so a table that is internally consistent but wrongly ORDERED fails.
- Rust, `the_derived_order_is_strictly_descending_by_group_count` — a future
  variant that TIED on group count would make the sort order-dependent again,
  which is the property the derivation exists to remove.
- Java, `CarvingTableTest` — membership **both** ways. A grouping served that
  Java cannot name (an addition upstream) and a grouping Java names that is not
  served (a removal, or a locally invented constant). Neither direction is
  redundant: without them, either mismatch would surface only when a particular
  row happened to resolve to it — on someone's data, not in the build.

Verified red-then-green: swapping the packed axes fails the Rust serve test;
reversing the sort direction fails the order test and two others; changing one
Java constant's arity fails both membership directions.

A library predating minor 8 serves no table. Java falls back to the encoding
those artifacts actually used, in one clearly-named compatibility shim
(`CarvingTable.PRE_MINOR_8`) rather than back in the enum — so exactly one place
in the build carries a literal encoding, and its name says it is history rather
than the current answer.

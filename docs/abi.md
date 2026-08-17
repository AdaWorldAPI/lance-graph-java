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

- It is **small** — currently 14 symbols. Growth is a design smell to be argued
  for, not a default.
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
LGJ_ABI_MINOR = 1    // additive change ⇒ bump; older Java may still load
LGJ_MAGIC     = 0x4C_47_4A_5F_41_42_49_00   // "LGJ_ABI\0" big-endian-read
```

Rule: **Java requires `major` to match exactly and `minor` to be `>=` what it
was compiled against.** A `major` mismatch is a hard failure, not a warning.

The magic doubles as an endianness probe: read as a `u64` little-endian it yields
a known constant; anything else means the library was built for a different byte
order and every subsequent read would be garbage.

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
    pub byte_len:     u64,
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

## 7. The function surface (14 symbols)

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
i32 lgj_mask_count(u64 mask, u64* out_count)
```

`dst` may alias `a` or `b`. All three must share the same parent and row count.

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

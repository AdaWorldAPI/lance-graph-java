# Ownership Across the Membrane — The Generation-Checked Handle

> READ BY: handle-lifecycle-auditor, panama-bridge-engineer, and anyone
> touching native/lgj-abi/src/registry.rs or java/internal/ffm/*

## Status: FINDING (the design's answer to "who owns this memory")

## The Problem This Solves

Inside Rust, `&self` borrows make a view-outliving-its-owner a *compile
error*. Panama has no borrow checker — a Java `MemorySegment` obtained from a
now-freed Rust allocation is a live footgun unless the membrane itself makes
use-after-free structurally impossible.

## The Design

A handle is **not a pointer**. It is an opaque `u64`:

```
 63                    32 31                     0
┌────────────────────────┬────────────────────────┐
│      generation        │        index           │
└────────────────────────┴────────────────────────┘
```

- `index` selects a slot in a Rust-side registry (`RwLock<Vec<Slot>>`).
- `generation` is bumped every time a slot is freed.
- A lookup validates `generation` against the slot's *current* generation.

Consequence table (all four are correctness properties this repo's tests
must falsify, not just assume — see `docs/abi.md` §4 and the Phase H
falsification tasks):

| Java does | Rust returns | NOT what happens |
|---|---|---|
| uses a live handle | success | — |
| uses it after `lgj_close` | `INVALID_HANDLE` | dereference of freed memory |
| closes twice | `INVALID_HANDLE` on 2nd | double-free |
| fabricates a handle | `INVALID_HANDLE` | arbitrary memory read |
| operates on a mask whose parent closed | `PARENT_CLOSED` | dangling parent access |

## Why This Beats a Naive `Box::into_raw` Handle

A raw pointer handle has no way to detect staleness — the memory it points at
may have been freed *and reallocated* for something else, so a
use-after-free doesn't even reliably crash; it silently corrupts. The
generation counter turns "is this handle still meaningful" into an O(1)
integer comparison that cannot be fooled by reallocation, because the slot
index is reused but the generation is not (until it wraps, which at `u32`
range is not a near-term concern for a research POC).

## Concurrency Shape

Registry lock is held only long enough to resolve `index → Arc<ResourceEntry>`
and clone the `Arc`; it is dropped before the entry's own inner lock is
taken. So two calls against *different* resources do not serialize on each
other — only `open`/`close` contend on the registry itself. This has not
been benchmarked under real contention; the POC's Java layer is
single-threaded, so this is a stated design intent, not yet a measured
property.

## Cross-reference

This is the Rust-side half of `docs/abi.md` §4. The Java-side half is: model
the `Arena`/segment lifetime as nested *inside* the resource's own lifetime,
so Java's own bookkeeping fails fast on a use-after-close even before the
call reaches Rust (belt-and-braces, not a substitute for the Rust-side
check).

## Rust 1.99 (2026-10-10): what the C-ABI changes do and do not buy here

MEASURED on 1.99.0 / LLVM 23. lgj-abi is clippy-clean with `-D warnings`. All
four Java suites pass (`AllTests` 612, consumers 70 + 68 + 3 + 12) against a
`liblgj_abi.so` built by 1.99. lgj-abi's graph contains no lance, lancedb or
arrow, so the lance 13 move does not reach this crate.

- **`UnsafeCell` contents may be accessed without `get` (newly guaranteed).**
  This does NOT change the soundness of the writable mask lane.
  - `lgj_mask_describe` hands Java the address of `MaskWords.words`. That is
    a boxed heap buffer, not the inside of the lock's `UnsafeCell`. Java writes
    to it through the segment, outside the lock.
  - It is sound today because no Rust reference to those words is live while
    Java writes. The one production writer, `RowStore.importRows`, writes a
    mask whose handle has not been returned to anyone yet. Every Rust op
    re-borrows through the lock.
  - A concurrent Java writer would still be a data race, and the guarantee
    does not make a race defined. That needs `AtomicU64` words plus a
    documented protocol, which this ABI deliberately does not define.
  - Possible hardening, not done: take the address under the write guard
    (`as_mut_ptr`), so it carries write provenance. Not done because
    `write_mask` also invalidates `resolved_carving`, so every describe would
    drop the carving cache. That is a behaviour change for no reachable
    defect.
- **C-variadic definitions / `VaList` (stabilized):** not applicable. The ABI
  is fixed-arity and bulk-only (`docs/abi.md` §6), and `no-c-ever.md` holds.
- **`no_mangle` on generic items is now a hard error:** the 30 `#[no_mangle]`
  exports are all non-generic.
- **Extern statics no longer promoted:** none are referenced.
- **`Box::into_non_null` / `Vec::from_parts`:** no site here. Handles are
  registry ids, never raw boxes.

REVISIT WHEN: the ABI grows a concurrent-writer protocol (then: atomic word
lanes), or a Java writer other than `importRows` appears.

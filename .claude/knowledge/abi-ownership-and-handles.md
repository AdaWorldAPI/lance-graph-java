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

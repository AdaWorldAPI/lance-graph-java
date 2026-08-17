# SIMD Provenance — `ndarray::simd::*` Only, Never `ndarray::hpc`

> READ BY: all agents touching native/lgj-abi/src/kernels.rs, or proposing
> any new Rust-side numeric primitive

## Status: FINDING (operator-corrected 2026, locked)

The user's own words, verbatim: **"Never use ndarray::hpc, trampoline to
ndarray::simd::* instead."**

## The One-Line Rule

`ndarray::hpc::*` is the **internal implementation** namespace. `ndarray::simd::*`
is the **sanctioned consumer re-export surface**. Every internal module that
ships a consumer-facing primitive (bitwise popcount, fingerprint ops, GEMM
tiles, quantization, cascade search, AMX) is re-exported *up* into
`ndarray::simd` by `ndarray/src/simd.rs`. ndarray's own CLAUDE.md states this
as the "matryoshka" invariant: *"Consumer writes `crate::simd::F32x16`.
Period."* This repo is exactly one more consumer of that invariant, not an
exception to it.

## Consequence for this repo specifically

`native/lgj-abi/src/kernels.rs` — the ONLY file in this crate allowed to
`use ndarray::*` at all (see `abi-membrane-warden` and the crate layout in
`docs/abi.md` §8) — must import exclusively through the `ndarray::simd::`
path:

```rust
// CORRECT
use ndarray::simd::{eq_u32_to_mask, gt_i32_to_mask, mask_and, mask_or, masked_sum_i32};
use ndarray::simd::popcount_batch_u64;   // even though it's *implemented* in hpc::bitwise

// WRONG — never do this, even though it resolves and compiles
use ndarray::hpc::bitwise::popcount_batch_u64;
```

Both may point at the same function today. That is not the point. The point
is: if the internal module ever moves, gets renamed, or gets an
arch-specialized replacement, the `ndarray::simd` re-export is the contract
that does not change under a consumer's feet. Importing the `hpc` path
directly is importing an implementation detail as if it were an API.

## Falsifier

`grep -rn "ndarray::hpc" native/` returning any hit outside of a doc comment
explaining *why* a symbol lives there is a block. The fix is always
"re-import through `ndarray::simd`," never "leave the hpc import, it works."

## Cross-reference

Same-family rule as `no-c-ever.md`: both are about refusing to reach past a
sanctioned boundary because the thing behind it happens to be reachable.

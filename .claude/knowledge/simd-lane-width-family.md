# The Lane-Width Family — 64x8 / 32x16 / 16x32 / 8x64, and Why AMX Is Different

> READ BY: simd-savant, panama-bridge-engineer, and anyone writing a doc
> comment that names an `ndarray::simd` type

## Status: FINDING (verified against `ndarray/src/simd_avx512.rs` source)

The user's own words, verbatim: **"The usual polyfill 64x8, 32x16, 16x32.
Amx and gemm tiles and i8 i16 i32 and u8 U16 u32 follow slightly different
multipliers matching the max available."**

## The Rule

At the AVX-512 tier, every `ndarray::simd` typed-wrapper name satisfies:

```
lane_count(width_bits) × width_bits = 512   (the register width)
```

Verified directly against `simd_avx512.rs`:

| element width | lane count | type names | total bits |
|---|---|---|---|
| 8-bit  | 64 | `U8x64`, `I8x64` | 512 |
| 16-bit | 32 | `U16x32`, `I16x32` | 512 |
| 32-bit | 16 | `U32x16`, `I32x16`, `F32x16` | 512 |
| 64-bit | 8  | `U64x8`, `I64x8`, `F64x8` | 512 |

"The multiplier matching the max available" = `512 / (8 × elem_bytes)` — the
AVX-512 register width divided by the element size. The AVX2 tier is the
same rule at 256 bits (halved lane counts: `x32/x16/x8/x4`), and NEON/WASM at
128 bits (further halved: `x16/x8/x4/x2` where applicable) — each backend
picks the lane count that fills *its own* native register, not a fixed
count. `F32x8`/`F64x4` etc. exist as the 256-bit-native companions
re-exported alongside the 512-bit set.

## Why AMX Is a Separate Module, Not Another Width Variant

AMX (`ndarray::simd::{amx_available, matmul_i8_to_i32}`, backed by
`ndarray::hpc::amx_matmul` internally — accessed only via the `simd`
re-export per `simd-provenance.md`) is a **2-D tile register file**
(`TMM0..TMM7`), not a 1-D SIMD lane vector. Its "multiplier" is a tile shape
(rows × columns × element width, e.g. a `16×64` int8 tile), not a lane count
along one axis. This is why it is its own module (`amx_matmul.rs`,
`bf16_tile_gemm.rs`) rather than a `U8x1024`-shaped extension of the lane
family — the underlying hardware primitive is structurally different (matrix
multiply-accumulate across two tiles into a third), not just "more lanes."

## Consequence for lgj-abi

`docs/abi.md`'s `LgjElemKind` enum (`U8, I8, U16, I16, U32, I32, U64, I64,
F32, F64, MASK_WORD`) deliberately does not encode a lane width — that is a
compiled-in backend fact (reported via `LgjAbiManifest::simd_backend`), not
a per-value property. A Java caller never chooses "give me the x16
variant" — it asks for an element kind and a bulk op; which lane width
executes it is `ndarray::simd`'s dispatch decision, invisible above the
membrane. This is the same "physics vs vocabulary" split the mission brief
draws between raw addresses and `LaneId`/`Range`/`Shape`.

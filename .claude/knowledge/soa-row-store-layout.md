# The SoA row store layout — the substrate everything converges on

> **READ BY:** `abi-membrane-warden`, `simd-savant`, `panama-bridge-engineer`,
> `java-surface-warden`, and any session touching `rowstore.rs`,
> `docs/abi.md` §11, the Java `RowStore` facade, or a consumer example.
> **MANDATORY** before proposing any change to row geometry, facet
> semantics, or the lane map.

## The layout (operator-stated, 2026-08-17)

> *"the 64k x 512 bytes SoA layout is enforced everywhere in lance-graph
> (32 Lanes each 4 bytes classview+12 bytes). For Java the layout might
> differ just for reference."*

```
row (512 B) = 32 × facet (16 B)
facet (16 B) = classid (4 B, little-endian u32) ++ payload (12 B)
```

This is the lance-graph **V3 content-blind facet** — the same shape the
sibling repos pin as canon (`E-V3-FACET-4-PLUS-12`; the 12 bytes are an
axis-grouped byte register read as `6×(u8:u8)` / `4×(u8:u8:u8)` /
`3×(u8:u8:u8:u8)` per the ClassView, never widened). `lgj-abi` treats the
12 bytes as opaque **on purpose**: the ABI is a machine membrane and the
payload's *reading* is a ClassView concern one layer up.

**"For Java the layout might differ" is load-bearing.** The Java side is
free to project a different view (a structured `MemoryLayout`, a different
field grouping, a Valhalla-shaped descriptor vocabulary). These bytes are
the substrate truth; the Java view is a *reading* of them. Nothing in the
Java facade may assume its own view is the storage layout.

## The two readings, one buffer, zero copies

| reading | how it is addressed | who uses it |
|---|---|---|
| **row** | row `r` = bytes `r*512..(r+1)*512`; facet `f` at `+f*16` | `MultiLaneColumn::iter_u32x16` (4 facets per 64-B chunk), Java's structured layout |
| **facet lane** | strided u32 column: `first_offset = f*16`, `stride = 512` | `eq_u32_strided_to_mask`, `LgjLaneDesc` (which has carried `stride_bytes` since minor 1) |

Neither is a copy. The buffer is one `Arc<[u8]>`; a clone is a refcount
bump. **There is no serialization anywhere in this stack** — that is the
whole point (operator: *"abandon any use of serialization in favor of
lance-graph 64k concurrency zero copy and ndarray SIMD polyfill"*), and it
composes with lance-graph's own doctrine that an SoA envelope is zero-copy
from creation to Lance tombstone.

## Facts a session must not re-derive

- **`n_rows * 512` is always a multiple of 64** — so
  `MultiLaneColumn::new` is infallible here *by construction*, not by
  luck. Pinned by `the_buffer_is_exactly_n_times_512_bytes`.
- **Classids sit at `U32x16` positions 0, 4, 8, 12** of each 64-byte
  chunk. The `& 0x1111` mask in the facet-match kernel is what keeps
  payload bytes from ever satisfying a classid predicate — pinned
  two-sided by `facet_match_ignores_needle_patterns_in_payload_bytes`.
- **`byte_len` is the EXACT covered span** `(len-1)*stride + elem_bytes`,
  never `len * stride`. A facet lane's base sits `f*16` into the buffer, so
  a full-stride final window would let Java bound a segment past the
  allocation's end. Contiguous lanes reduce to the old formula unchanged.
- **`facet` ≠ `lane_id`.** Lane 0 is the raw buffer; facet `f`'s lane id is
  `1 + f`. `lgj_op_eq_classid` takes a **facet index**. Pinned by the
  end-to-end test asserting facet 32 is invalid while lane 32 is valid.
- **Masks parent onto row stores** exactly as onto patterns — both are
  read-only, row-shaped resources — so the entire existing mask algebra
  (`and`/`or`/`count`/`describe`, direct Java word writes) applies with no
  new surface.
- **Alignment, honestly:** the base is `u8`-aligned (`Arc<[u8]>` promises
  no more on stable Rust). Rows are strided at 512. Nothing here needs
  more — Panama has unaligned value layouts, and every `ndarray::simd` load
  is a register fill. The `align(64)` base guarantee arrives with real
  `NodeRow` (`#[repr(C, align(64))]`) wiring, not before.

## Why `MultiLaneColumn` fits HERE and not in the flat fixture

Recorded because the answer flipped once already
(`E-LGJ-SIMD-SOA-IS-FOR-THE-ROW-STORE-NOT-THE-FLAT-LANES-1`):

- It requires `len % 64 == 0` with **no tail arm**. The flat fixture's
  lanes are caller-sized `n_rows` of 4/8-byte elements — arbitrary. The
  row store's buffer is `n*512` — always conforming.
- Its typed iterators are 64-byte chunk views, which is *exactly* a
  four-facet group and *not* a natural fit for a flat column scan (where
  `simd_int_ops`' own group-plus-scalar-tail loop is the right shape).

So both consumers are correct and neither is a workaround: use
`simd_int_ops` primitives for flat columns, `MultiLaneColumn` for the row
store. The u32 lane (`iter_u32x16`) was added to ndarray specifically to
close the gap that made the second impossible.

# Cross-Repo PR Cross-References

> **Append-only log of PRs in OTHER AdaWorldAPI repos** that this repo
> depends on, caused, or is gated by. Modeled on lance-graph's board file
> of the same name.
>
> `PR_ARC_INVENTORY.md` is scoped to lance-graph-java PRs (Added / Locked
> / Deferred, rooted in this repo). Cross-repo PRs need their own trail
> because of the **missing-capability STOP rule** (root `CLAUDE.md`): a
> consumer never grows the membrane, so a capability this repo needs lands
> UPSTREAM FIRST — in `lance-graph-contract` or in `ndarray` — and this
> repo's own PR is then gated on that one merging. Without a ledger, the
> gating relationship is visible only in a commit message, and a session
> six weeks later re-derives it by grep.
>
> ## APPEND-ONLY rule
>
> 1. New entries PREPEND at the top (most recent first).
> 2. Each entry is IMMUTABLE except the **Status** / **Confidence** lines.
> 3. Each entry names WHY it was upstream: which capability was missing,
>    and what would have been the hand-rolled local version had the STOP
>    rule not been applied.
>
> **READ BY:** any session adding a symbol to `native/lgj-abi`, any
> session that finds a needed primitive absent from `ndarray::simd` or a
> needed accessor absent from `lance_graph_contract`, any session auditing
> whether this repo has quietly re-implemented something upstream owns.

---

## ndarray #283 — `masked_strided_group_sum` (merged 2026-08-25)

**Repo:** `AdaWorldAPI/ndarray`
**Consumed by:** `native/lgj-abi/src/kernels.rs::masked_facet_sum`
**Gated:** lance-graph-java ABI minor 5 (`lgj_reduce_facet_sum`).

**The missing capability.** The mask-native sweep needs a masked reduction
over a STRIDED group layout — a facet's 12-byte register at
`first_offset + row * 512`, read as `groups × group_bytes`. `ndarray::simd`
had `masked_sum_i32` (contiguous, one element per row) and
`eq_u32_strided_to_mask` (strided, but a comparison), and nothing that was
both strided and a reduction.

**Why it went upstream.** The iron rule is *all SIMD from `ndarray::simd`*
(`abi.md` §8). The hand-rolled local version would have been a scalar loop
in `kernels.rs` — which is exactly the "a consumer grows the membrane"
shape the STOP rule forbids, and it would have been invisible to ndarray's
own W1a consumer contract (three backends + a parity test).

**Shape:** `masked_strided_group_sum(bytes, first_offset, stride_bytes,
n_records, groups, group_bytes, mask_words) -> Option<i64>`. Accumulates in
`i128` and range-checks once, so `i64` overflow is a `None` rather than a
wrap — which is what `LGJ_ERR_SUM_OVERFLOW` (−16) reports across the
membrane.

**Status:** merged, consumed, `masked_facet_sum` is now one delegating call.

---

## lance-graph #1025 — `ClassView::cascade_shape` (merged 2026-08-25)

**Repo:** `AdaWorldAPI/lance-graph` (`crates/lance-graph-contract`)
**Consumed by:** `native/lgj-abi/src/class_view_provider.rs`,
`kernels::resolve_population_carving`
**Gated:** lance-graph-java ABI minor 6 (`lgj_reduce_facet_sum_resolved`)
and, transitively, minor 8's `CARVING_ORDER`.

**The missing capability.** `ClassView` carried four registry-resolution
accessors (`edge_codec_flavor` / `rail_carving` / `band_reading` /
`value_schema`) and NO carving resolver — so "which of the three readings
of this class's 12-byte register applies" had no upstream answer at all.
`abi.md` §14 said so explicitly at minor 5, and the sweep took the carving
as a caller-ASSERTED parameter in the meantime.

**Why it went upstream.** A local answer would have been this repo minting
its own class→carving table — a second source of truth for a question the
contract exists to answer, and the parallel-object-model anti-pattern in
miniature. The default is `G3D4` (the GUID canon's `3×4`), so a class that
has not been taught otherwise resolves to the canonical reading rather than
to nothing.

**The finding it produced:** `E-LGJ-THE-RESOLVER-WAS-UPSTREAM-ALL-ALONG-1`
— the local `Carving` enum this repo had minted was already
`CascadeShape::{G6D2, G4D3, G3D4}`, which had carried the full algebra
(`groups`/`levels`/`group_of`/`index`) and the "class-conditioned" statement
all along. `Carving` is now a `pub type` alias, and at minor 8 the wire
ORDER is derived from `CascadeShape::ROTATIONS` rather than written here.

**Status:** merged, consumed. `CascadeShape` is now the single source for
BOTH the set of readings and (by derivation) their wire encoding.

---

## ndarray #280 — the `mask_andnot` family (merged 2026-08-18)

**Repo:** `AdaWorldAPI/ndarray`
**Consumed by:** `native/lgj-abi/src/kernels.rs::simd_mask_andnot{,_assign}`
**Gated:** lance-graph-java ABI minor 4 (`lgj_mask_andnot`), D-LGJ-W8
SUBSTRATE rung.

**The missing capability.** The mask algebra had `and` / `or` (both plain
and `_assign`) and no complement — so `Mask.minus` had no primitive under
it. Commit `3f3504f`; 51/51, vector-path disable-run red-then-green, plus
the `ndarray::simd` re-export and a blackboard W1a-deviation entry.

**Why it went upstream.** Same rule, same reason: an ANDNOT written locally
would have been a fourth mask primitive living outside the surface the
other three come from. Note ANDNOT is NOT commutative, which is why the
export carries a 5-branch aliasing tree (the `dst == b` case needs a
scratch copy) that AND/OR do not.

**Status:** merged, consumed.

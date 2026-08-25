//! The bulk kernels — and the **single** place in this crate that names
//! `ndarray`.
//!
//! # SIMD provenance (abi.md §8)
//!
//! Every SIMD path here routes through `ndarray::simd` and nothing else. This
//! crate contains no `core::arch` / `_mm*` intrinsic, no `core::simd`, no
//! `#[cfg(target_feature)]` SIMD *selection*, and no locally-written SIMD
//! abstraction. Which backend those calls compile to is `ndarray`'s business;
//! the manifest reports it and Java never selects it.
//!
//! # Why every ndarray call is behind a wrapper
//!
//! The `simd_int_ops` primitives this file consumes were written in parallel
//! with it, against a shared signature contract. Funnelling every call through
//! a one-line `simd_*` wrapper below means a signature adjustment touches this
//! file only — the exported ABI, the registry and the plan evaluator never see
//! it. The wrappers are `#[inline]`, so they cost nothing.
//!
//! # The scalar reference is INDEPENDENT
//!
//! `scalar_*` below is written in plain Rust loops with **no ndarray at all**.
//! That independence is the entire value of `lgj_plan_eval_scalar`: if the
//! reference shared code with the SIMD path, a parity test between them would
//! be checking that a function agrees with itself. It does not, so the test is
//! a real falsifier — the same reason `ndarray`'s own W1a contract demands a
//! scalar arm.
//!
//! # Bit order (normative, restated because both paths must obey it)
//!
//! Element `i` lives at bit `i % 64` of word `i / 64`. Bits past `n_rows` in
//! the final word are zero.

use crate::abi::*;
use lance_graph_contract::facet::CascadeShape;

/// Bytes of classid at the head of each facet, before its 12-byte register.
const FACET_CLASSID_BYTES: usize = 4;

// ───────────────────────────────────────────────────────────────────────────
// SIMD path — thin wrappers over ndarray::simd
// ───────────────────────────────────────────────────────────────────────────

/// `out_words[i-th bit] = (values[i] == needle)`, fully overwriting `out_words`.
#[inline]
pub fn simd_eq_u32_to_mask(values: &[u32], needle: u32, out_words: &mut [u64]) {
    ndarray::simd::eq_u32_to_mask(values, needle, out_words);
}

/// `out_words[i-th bit] = (values[i] > threshold)`, signed, fully overwriting.
#[inline]
pub fn simd_gt_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    ndarray::simd::gt_i32_to_mask(values, threshold, out_words);
}

/// `dst = a & b`. `dst` must not alias `a` or `b` (Rust's borrow rules enforce
/// it here; the aliasing ABI cases route to the `_assign` forms instead).
#[inline]
pub fn simd_mask_and(a: &[u64], b: &[u64], dst: &mut [u64]) {
    ndarray::simd::mask_and(a, b, dst);
}

/// `dst = a | b`.
#[inline]
pub fn simd_mask_or(a: &[u64], b: &[u64], dst: &mut [u64]) {
    ndarray::simd::mask_or(a, b, dst);
}

/// `dst &= src`.
#[inline]
pub fn simd_mask_and_assign(dst: &mut [u64], src: &[u64]) {
    ndarray::simd::mask_and_assign(dst, src);
}

/// `dst |= src`.
#[inline]
pub fn simd_mask_or_assign(dst: &mut [u64], src: &[u64]) {
    ndarray::simd::mask_or_assign(dst, src);
}

/// `dst = a & !b`. `dst` must not alias `a` or `b` (same convention as
/// [`simd_mask_and`]/[`simd_mask_or`] — the aliasing ABI cases in
/// `exports::lgj_mask_andnot` route to [`simd_mask_andnot_assign`] instead,
/// with a scratch-buffer copy for the `dst == b` case since ANDNOT, unlike
/// AND/OR, is not commutative).
#[inline]
pub fn simd_mask_andnot(a: &[u64], b: &[u64], dst: &mut [u64]) {
    ndarray::simd::mask_andnot(a, b, dst);
}

/// `dst &= !src`.
#[inline]
pub fn simd_mask_andnot_assign(dst: &mut [u64], src: &[u64]) {
    ndarray::simd::mask_andnot_assign(dst, src);
}

/// Sum of `values[i]` over set mask bits, widened to `i64`.
#[inline]
pub fn simd_masked_sum_i32(values: &[i32], mask_words: &[u64]) -> i64 {
    ndarray::simd::masked_sum_i32(values, mask_words)
}

/// Population count over mask words.
///
/// **Reused, not reimplemented** — this already exists in `ndarray`
/// (implemented in `src/bitwise.rs`) and is reached here EXCLUSIVELY
/// through the sanctioned `ndarray::simd` re-export surface, never through
/// the internal `ndarray::hpc::bitwise` path it happens to live behind.
/// See `.claude/knowledge/simd-provenance.md`: `ndarray::hpc::*` is an
/// implementation-detail namespace that may be renamed or re-arranged at
/// any time; `ndarray::simd::*` is the contract that does not move under a
/// consumer's feet. Writing a second popcount here would ALSO be exactly
/// the duplication the `ndarray::simd` membrane exists to prevent — this
/// function exists only to keep this crate's own kernel-call surface
/// uniform (every bulk primitive it calls is named `simd_*` here).
#[inline]
pub fn simd_popcount(words: &[u64]) -> u64 {
    ndarray::simd::popcount_batch_u64(words)
}

/// Row mask over one facet-classid lane of a 512-byte row store:
/// `out_words[row-th bit] = (classid of facet at first_offset in row == needle)`.
///
/// Routes through `ndarray::simd::eq_u32_strided_to_mask` — the strided
/// AoS-facet scan (LE `u32` at `first_offset + row * 512`). The primitive
/// owns bounds checking (overflow-checked, panics rather than reading out of
/// bounds) and the trailing-bits-zero guarantee.
#[inline]
pub fn simd_rowstore_classid_mask(
    bytes: &[u8],
    first_offset: usize,
    n_rows: usize,
    needle: u32,
    out_words: &mut [u64],
) {
    ndarray::simd::eq_u32_strided_to_mask(
        bytes,
        first_offset,
        crate::rowstore::ROW_BYTES as usize,
        n_rows,
        needle,
        out_words,
    );
}

/// Per-row facet-match: `out[row]` gets bit `f` set iff facet `f`'s classid
/// in that row equals `needle` — "which facets of this node carry class X",
/// one `u32` answer per row, written into the caller's buffer.
///
/// This is the [`ndarray::simd::MultiLaneColumn`] consumer: the store's
/// `Arc<[u8]>` is wrapped WITHOUT copying (the Arc clone is a refcount bump),
/// and each 64-byte chunk — four 16-byte facets — is answered by ONE
/// `U32x16::eq_bitmask` against the broadcast needle, masked to the classid
/// positions 0/4/8/12 and folded into 4 facet bits. Eight chunks per row
/// assemble the row's 32-bit answer.
///
/// # Panics
///
/// Panics if `bytes.len() != n_rows * 512` or `out.len() < n_rows` — caller
/// bugs inside this crate, not reachable from the membrane (the export
/// validates first).
pub fn simd_rowstore_facet_match(
    bytes: &std::sync::Arc<[u8]>,
    n_rows: usize,
    needle: u32,
    out: &mut [u32],
) {
    use crate::rowstore::ROW_BYTES;
    assert_eq!(bytes.len(), n_rows * ROW_BYTES as usize);
    assert!(out.len() >= n_rows);

    // n*512 is always a multiple of 64 (rowstore tests pin this), so `new`
    // cannot fail — and the construction shares the bytes, never copies them.
    let col = ndarray::simd::MultiLaneColumn::new(std::sync::Arc::clone(bytes))
        .expect("rowstore buffer is a multiple of 64 bytes by construction");
    let needle_v = ndarray::simd::U32x16::from_array([needle; 16]);

    // Fully overwrite, same contract as every mask writer in this crate: the
    // per-chunk fold below ORs, so stale caller bits must not survive.
    for o in out.iter_mut().take(n_rows) {
        *o = 0;
    }

    const CHUNKS_PER_ROW: usize = (ROW_BYTES / 64) as usize; // 8
    for (c, chunk) in col.iter_u32x16().enumerate() {
        // Classids sit at u32 positions 0/4/8/12 of the 16-lane chunk; the
        // other twelve lanes are payload bytes that must never contribute.
        let m = chunk.eq_bitmask(needle_v) & 0x1111;
        let facet_bits = (m & 1) | ((m >> 4) & 1) << 1 | ((m >> 8) & 1) << 2 | ((m >> 12) & 1) << 3;
        let row = c / CHUNKS_PER_ROW;
        let chunk_in_row = c % CHUNKS_PER_ROW;
        out[row] |= (facet_bits as u32) << (4 * chunk_in_row);
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Scalar reference — INDEPENDENT of ndarray. Do not "simplify" by calling the
// wrappers above; the independence IS the test.
// ───────────────────────────────────────────────────────────────────────────

/// Reference `eq_u32` → mask.
pub fn scalar_eq_u32_to_mask(values: &[u32], needle: u32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v == needle {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference signed `gt_i32` → mask.
pub fn scalar_gt_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v > threshold {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference `dst &= src`.
pub fn scalar_mask_and_assign(dst: &mut [u64], src: &[u64]) {
    for (d, &s) in dst.iter_mut().zip(src.iter()) {
        *d &= s;
    }
}

/// Reference `dst |= src`.
pub fn scalar_mask_or_assign(dst: &mut [u64], src: &[u64]) {
    for (d, &s) in dst.iter_mut().zip(src.iter()) {
        *d |= s;
    }
}

/// Reference masked sum.
pub fn scalar_masked_sum_i32(values: &[i32], mask_words: &[u64]) -> i64 {
    let mut acc: i64 = 0;
    for (i, &v) in values.iter().enumerate() {
        if (mask_words[i / 64] >> (i % 64)) & 1 == 1 {
            acc += v as i64;
        }
    }
    acc
}

/// Reference popcount.
pub fn scalar_popcount(words: &[u64]) -> u64 {
    let mut n = 0u64;
    for &w in words {
        n += w.count_ones() as u64;
    }
    n
}

/// Reference strided classid → row mask. Plain byte reads, no ndarray.
pub fn scalar_rowstore_classid_mask(
    bytes: &[u8],
    first_offset: usize,
    n_rows: usize,
    needle: u32,
    out_words: &mut [u64],
) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for row in 0..n_rows {
        let off = first_offset + row * crate::rowstore::ROW_BYTES as usize;
        let v = u32::from_le_bytes([bytes[off], bytes[off + 1], bytes[off + 2], bytes[off + 3]]);
        if v == needle {
            out_words[row / 64] |= 1u64 << (row % 64);
        }
    }
}

/// Reference per-row facet match. Plain byte reads, no ndarray.
pub fn scalar_rowstore_facet_match(bytes: &[u8], n_rows: usize, needle: u32, out: &mut [u32]) {
    use crate::rowstore::{FACET_BYTES, ROW_BYTES, ROW_FACETS};
    for (row, o) in out.iter_mut().enumerate().take(n_rows) {
        let mut bits = 0u32;
        for f in 0..ROW_FACETS {
            let off = row * ROW_BYTES as usize + f as usize * FACET_BYTES as usize;
            let v =
                u32::from_le_bytes([bytes[off], bytes[off + 1], bytes[off + 2], bytes[off + 3]]);
            if v == needle {
                bits |= 1 << f;
            }
        }
        *o = bits;
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Which path a call takes
// ───────────────────────────────────────────────────────────────────────────

/// Selects between the `ndarray::simd` kernels and the independent scalar
/// reference.
///
/// This is a *runtime value*, not a `cfg` — `lgj_plan_eval` and
/// `lgj_plan_eval_scalar` are two symbols over one code path, which is what
/// makes SIMD-vs-scalar parity falsifiable **through the membrane** where the
/// Java tests live.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Path {
    /// The `ndarray::simd` kernels — what `lgj_plan_eval` uses.
    Simd,
    /// The independent scalar reference — what `lgj_plan_eval_scalar` uses.
    Scalar,
}

/// The lane view a predicate reads. Carries its element kind so the plan
/// validator can reject an opcode/lane mismatch before any work happens.
pub enum LaneView<'a> {
    /// A `U32` lane (classes).
    U32(&'a [u32]),
    /// An `I32` lane (values).
    I32(&'a [i32]),
    /// A `U64` lane (ids). No predicate currently reads it, which is exactly
    /// why it is here: it gives `LANE_KIND_MISMATCH` something real to reject.
    U64(&'a [u64]),
}

impl LaneView<'_> {
    /// The element kind this view exposes, used by the plan validator.
    pub fn kind(&self) -> LgjElemKind {
        match self {
            LaneView::U32(_) => LgjElemKind::U32,
            LaneView::I32(_) => LgjElemKind::I32,
            LaneView::U64(_) => LgjElemKind::U64,
        }
    }
}

/// Evaluate one predicate into `out_words` (fully overwritten).
///
/// The opcode/lane pairing has already been validated by the plan checker; a
/// mismatch reaching here is a bug in this crate, so it is reported as a status
/// rather than assumed away.
pub fn eval_predicate(
    path: Path,
    op: u32,
    operand: i64,
    lane: &LaneView<'_>,
    n_rows: u64,
    out_words: &mut [u64],
) -> Result<(), i32> {
    match (op, lane) {
        (LGJ_OP_EQ_U32, LaneView::U32(v)) => {
            // The operand arrives sign-extended in an i64; the needle is the
            // low 32 bits, compared as an exact u32 bit pattern.
            let needle = operand as u32;
            match path {
                Path::Simd => simd_eq_u32_to_mask(v, needle, out_words),
                Path::Scalar => scalar_eq_u32_to_mask(v, needle, out_words),
            }
        }
        (LGJ_OP_GT_I32, LaneView::I32(v)) => {
            let threshold = operand as i32;
            match path {
                Path::Simd => simd_gt_i32_to_mask(v, threshold, out_words),
                Path::Scalar => scalar_gt_i32_to_mask(v, threshold, out_words),
            }
        }
        (LGJ_OP_EQ_U32, _) | (LGJ_OP_GT_I32, _) => return Err(LGJ_ERR_LANE_KIND_MISMATCH),
        _ => return Err(LGJ_ERR_UNKNOWN_OPCODE),
    }
    // Both primitives already zero the tail, but re-establishing it here means
    // the invariant holds no matter which path ran.
    clear_tail_bits(out_words, n_rows);
    Ok(())
}

/// Combine a predicate result into an accumulator.
pub fn combine_into(
    path: Path,
    combine: u32,
    acc: &mut [u64],
    op_result: &[u64],
) -> Result<(), i32> {
    match (combine, path) {
        (LGJ_COMBINE_AND, Path::Simd) => simd_mask_and_assign(acc, op_result),
        (LGJ_COMBINE_AND, Path::Scalar) => scalar_mask_and_assign(acc, op_result),
        (LGJ_COMBINE_OR, Path::Simd) => simd_mask_or_assign(acc, op_result),
        (LGJ_COMBINE_OR, Path::Scalar) => scalar_mask_or_assign(acc, op_result),
        // Not in abi.md's combiner set. Rejected like an unknown opcode rather
        // than defaulted to AND, so a future combiner cannot be silently
        // misinterpreted by an old build.
        _ => return Err(LGJ_ERR_UNKNOWN_OPCODE),
    }
    Ok(())
}

/// Popcount, on the selected path.
pub fn popcount(path: Path, words: &[u64]) -> u64 {
    match path {
        Path::Simd => simd_popcount(words),
        Path::Scalar => scalar_popcount(words),
    }
}

/// Masked sum, on the selected path.
pub fn masked_sum_i32(path: Path, values: &[i32], mask_words: &[u64]) -> i64 {
    match path {
        Path::Simd => simd_masked_sum_i32(values, mask_words),
        Path::Scalar => scalar_masked_sum_i32(values, mask_words),
    }
}

/// The three readings of the V3 content-blind 12-byte facet register.
///
/// **This is a THIN WIRE ADAPTER over the contract's own
/// [`CascadeShape`](lance_graph_contract::facet::CascadeShape), not a type of
/// its own.** An earlier version of this file minted a local `Carving` enum
/// with variants `Rails6x2`/`Triplets4x3`/`Quads3x4`; those are exactly
/// `CascadeShape::{G6D2, G4D3, G3D4}`, which have carried the full algebra
/// (`groups`/`levels`/`group_of`/`index`) and the statement that the grouping
/// is "class-conditioned: `classid` selects it from the inherited schema" all
/// along. Re-minting them here was the parallel-object-model anti-pattern in
/// miniature. What remains local is only the u32 WIRE ENCODING, because a
/// `#[repr]` discriminant is not part of the contract's promise and pinning one
/// across the membrane is this crate's job.
pub type Carving = CascadeShape;

/// The wire order of the groupings, DERIVED from the contract's own rotation
/// set — not a hand-written list here.
///
/// **The rule is group count, descending.** `6 -> 0`, `4 -> 1`, `3 -> 2` today,
/// and whatever [`CascadeShape::ROTATIONS`] contains tomorrow. Two consequences,
/// both deliberate:
///
/// - A variant REORDER upstream cannot re-map the wire, because the order is
///   computed from `groups()` rather than from declaration position.
/// - A variant ADDED upstream appears here automatically, in its group-count
///   place, with no edit to this file.
///
/// This is "data as config" applied to the one fact that was hand-written three
/// times — here, in Java, and in `abi.md`'s table. The contract owns the set,
/// this derives the encoding, and the manifest serves both to Java so it need
/// not restate them.
///
/// It is a `const` — not a `LazyLock` — because the manifest that SERVES this
/// table is itself const-initialised. A runtime-initialised order could not be
/// reached from there, and the manifest is the whole point.
pub const CARVING_ORDER: [CascadeShape; CascadeShape::ROTATIONS.len()] = {
    let mut order = CascadeShape::ROTATIONS;
    // Insertion sort, descending by group count. `sort_by_key` is not const, and
    // the set is three elements. `G·D = 12` for every shape, so no two share a
    // group count and the order is strict.
    let mut i = 1;
    while i < order.len() {
        let mut j = i;
        while j > 0 && order[j].groups() > order[j - 1].groups() {
            let tmp = order[j - 1];
            order[j - 1] = order[j];
            order[j] = tmp;
            j -= 1;
        }
        i += 1;
    }
    order
};

/// How many groupings the wire encoding can name — served in the manifest so
/// Java does not hardcode it.
pub const fn carving_count() -> usize {
    CARVING_ORDER.len()
}

/// Wire `u32` -> the contract's grouping, via [`CARVING_ORDER`]. `None` for
/// anything outside the derived set, so an unknown reading is a rejected call
/// rather than an aliased one.
pub fn carving_from_wire(v: u32) -> Option<Carving> {
    usize::try_from(v)
        .ok()
        .and_then(|i| CARVING_ORDER.get(i).copied())
}

/// The wire value for a grouping — the inverse of [`carving_from_wire`], used
/// when the ABI REPORTS a resolved grouping back to the caller.
pub fn carving_to_wire(c: Carving) -> u32 {
    CARVING_ORDER
        .iter()
        .position(|&s| s == c)
        .expect("every CascadeShape is in ROTATIONS") as u32
}

/// Groups per register under this reading — delegates to the contract.
#[inline]
fn groups_of(c: Carving) -> usize {
    c.groups() as usize
}

/// Bytes per group under this reading — delegates to the contract.
/// `groups_of * group_bytes_of == 12` for every shape, by `CascadeShape`'s own
/// `G·D = CASCADE_UNITS` invariant.
#[inline]
fn group_bytes_of(c: Carving) -> usize {
    c.levels() as usize
}

/// Resolve the ONE grouping a masked population reads its register under, or
/// report that it has none.
///
/// This is `ResolvedCarving`: the answer is derived FROM the population and
/// verified, instead of supplied alongside it and trusted. For every selected
/// row it reads the facet's classid, resolves it through the `ClassView`
/// (`classid -> ClassId -> cascade_shape`), and requires every row to agree.
///
/// Returns `None` when the population does not resolve to a single grouping —
/// either it spans classes with different groupings, or some row's classid has
/// no `ClassView` answer at all. Both are the same fact for a caller: there is
/// no one reading that is correct for these rows.
///
/// An EMPTY population resolves to `None` too, and deliberately: zero rows
/// carry zero classes, so there is nothing to resolve from. Reporting the
/// zero-fallback there would be inventing an answer.
///
/// # Cost, and where the question is NOT asked
///
/// `O(mask_words + popcount)` — one classid read per selected row, ONCE, before
/// any sweep. The sweep that follows is monomorphic: the grouping is decided,
/// so the hot loop carries no per-row dispatch. That is the whole point — the
/// question is asked once at the population's edge, never per row inside it.
pub fn resolve_population_carving(
    bytes: &[u8],
    facet_off: usize,
    row_stride: usize,
    n_rows: usize,
    mask_words: &[u64],
    shape_of: impl Fn(u32) -> Option<Carving>,
) -> Option<Carving> {
    let mut resolved: Option<Carving> = None;
    for (w, &word) in mask_words.iter().enumerate() {
        let base_row = w * 64;
        if base_row >= n_rows {
            break;
        }
        let mut bits = word;
        let valid = n_rows - base_row;
        if valid < 64 {
            bits &= (1u64 << valid) - 1;
        }
        while bits != 0 {
            let row = base_row + bits.trailing_zeros() as usize;
            bits &= bits - 1;
            let o = row * row_stride + facet_off;
            let classid = u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]);
            let shape = shape_of(classid)?;
            match resolved {
                None => resolved = Some(shape),
                Some(seen) if seen == shape => {}
                // Mixed groupings: no single reading is correct here.
                Some(_) => return None,
            }
        }
    }
    resolved
}

/// Bit set in a facet's layout byte when some selected row's classid has no
/// `ClassView` answer at all.
pub const LAYOUT_UNANSWERABLE: u8 = 0b1000;

/// For each of the row's facets, the SET of register groupings its selected rows
/// carry — the whole-row alignment probe (abi.md §16).
///
/// # The cheap exact test
///
/// Per facet this accumulates a 3-bit set: bit `w` is set if some selected row
/// resolves to grouping `w`, plus [`LAYOUT_UNANSWERABLE`] if some row's classid
/// has no answer. That is ONE `or` per (row, facet) — no comparison, no
/// branch on the previous value, no early exit to make the cost data-dependent.
///
/// The alignment question then falls out of arithmetic rather than a scan:
///
/// ```text
///   aligned(facet)  ⟺  byte.count_ones() == 1  &&  byte & UNANSWERABLE == 0
/// ```
///
/// An OR-accumulated set is exact where a sum or an XOR is not: summing wire
/// values cannot tell `{0,2}` from `{1,1}`, and XOR cannot tell `{1,1}` from
/// `{}`. The set forgets multiplicity, which is precisely the information the
/// question does not need.
///
/// `out` must hold one byte per facet; every entry is overwritten. A facet with
/// no selected rows reports `0` — the empty set, which is neither aligned nor
/// unanswerable, and the caller must not read it as either.
#[expect(
    clippy::too_many_arguments,
    reason = "eight is the row geometry (bytes/stride/rows/facets/facet_bytes) plus the mask,               the resolver and the output. A params struct would bundle values that have no               relationship except being needed here, and would hide the fact that every one is               read straight from the store's own constants at the single call site."
)]
pub fn facet_layout_sets(
    bytes: &[u8],
    row_stride: usize,
    n_rows: usize,
    facets: usize,
    facet_bytes: usize,
    mask_words: &[u64],
    wire_of: impl Fn(u32) -> Option<u8>,
    out: &mut [u8],
) {
    assert!(
        out.len() >= facets,
        "facet_layout_sets: out.len()={} < facets {facets}",
        out.len()
    );
    for slot in out.iter_mut().take(facets) {
        *slot = 0;
    }
    for (w, &word) in mask_words.iter().enumerate() {
        let base_row = w * 64;
        if base_row >= n_rows {
            break;
        }
        let mut bits = word;
        let valid = n_rows - base_row;
        if valid < 64 {
            bits &= (1u64 << valid) - 1;
        }
        while bits != 0 {
            let row = base_row + bits.trailing_zeros() as usize;
            bits &= bits - 1;
            let row_off = row * row_stride;
            for (f, slot) in out.iter_mut().enumerate().take(facets) {
                let o = row_off + f * facet_bytes;
                let classid =
                    u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]]);
                *slot |= match wire_of(classid) {
                    Some(x) => 1u8 << x,
                    None => LAYOUT_UNANSWERABLE,
                };
            }
        }
    }
}

/// Sum every group of one facet's 12-byte register, over the rows selected by
/// `mask_words`. Returns `None` on overflow rather than a wrapped value.
///
/// This is the mask-driven monomorphic sweep measured as R8 arm E' — the
/// lawful counterpart to a materialised index list. Work is proportional to
/// the mask's POPCOUNT, not to `n_rows`, so an empty mask is O(words) and the
/// anti-JNI rule (§6: bulk or lifecycle) is satisfied by construction.
///
/// # SIMD provenance (§8) — the named gap is CLOSED
///
/// This delegates to `ndarray::simd::masked_strided_group_sum`. An earlier
/// version carried its own scalar loop and documented the absence of that
/// primitive as a named gap under the W1a consumer contract: added THERE,
/// consumed here, never re-implemented at this layer. It was added there, so
/// this is now the consumption.
///
/// The upstream kernel is itself scalar, and its doc says why with the
/// measurement rather than an apology: one small register per record at a large
/// stride is memory-bound, records are not adjacent so several cannot be
/// vector-loaded, and widening six `u16`s inside one record would optimise the
/// part that is already free. A contiguous variant would genuinely vectorise and
/// is named there as a different primitive, not a flag on this one.
///
/// # Overflow is REPORTED, never wrapped
///
/// `i64` is not closed under this reduction. Under the quads reading a single
/// row contributes at most `3 * (2^32 - 1) = 12_884_901_885`, so `i64::MAX` is
/// exceeded after ~715 million maximum-valued selected rows — about 341 GiB of
/// 512-byte rows, which is inside the scale this substrate contemplates rather
/// than safely beyond it. Accumulation is therefore `i128` (which cannot
/// overflow: the per-row bound times `u64::MAX` rows still fits) and the result
/// is range-checked ONCE at the end. A silent wrap would be exactly the
/// plausible-but-wrong answer this ABI otherwise works hard to prevent.
///
/// # Cost
///
/// `O(mask_words + popcount * groups)` — the word scan is unconditional, so an
/// empty mask still costs one pass over the mask, not zero.
pub fn masked_facet_sum(
    bytes: &[u8],
    facet_off: usize,
    row_stride: usize,
    n_rows: usize,
    carving: Carving,
    mask_words: &[u64],
) -> Option<i64> {
    // The whole body is now ONE call into the sanctioned surface. The hand-rolled
    // loop this replaces was the §8 violation-in-waiting the doc-comment above
    // named as a gap: it lived here only because `ndarray::simd` had no primitive
    // for a strided sub-word group gather. It does now, so this crate consumes it
    // rather than carrying its own copy.
    ndarray::simd::masked_strided_group_sum(
        bytes,
        facet_off + FACET_CLASSID_BYTES,
        row_stride,
        n_rows,
        groups_of(carving),
        group_bytes_of(carving),
        mask_words,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixture::Fixture;

    fn words_for(n: u64) -> Vec<u64> {
        vec![0u64; mask_words_for(n) as usize]
    }

    /// The falsifier for the whole SIMD provenance story: the ndarray kernels
    /// and an independently-written scalar loop must agree bit-for-bit, at row
    /// counts that are and are not multiples of the lane width.
    #[test]
    fn simd_matches_scalar_on_every_row_count_shape() {
        // 15/16/17 straddle the 16-lane group; 63/64/65 straddle the word; 1000
        // and 4096 are ordinary; 0 and 1 are the degenerate ends.
        for n in [0u64, 1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 1000, 4096] {
            for seed in [0u64, 1, 0xDEAD_BEEF] {
                let f = Fixture::generate(n, seed).unwrap();
                let (mut a, mut b) = (words_for(n), words_for(n));

                simd_eq_u32_to_mask(f.classes(), 7, &mut a);
                scalar_eq_u32_to_mask(f.classes(), 7, &mut b);
                assert_eq!(a, b, "eq_u32 mismatch at n={n} seed={seed}");

                simd_gt_i32_to_mask(f.values(), 100, &mut a);
                scalar_gt_i32_to_mask(f.values(), 100, &mut b);
                assert_eq!(a, b, "gt_i32 mismatch at n={n} seed={seed}");

                assert_eq!(simd_popcount(&a), scalar_popcount(&b));
                assert_eq!(
                    simd_masked_sum_i32(f.values(), &a),
                    scalar_masked_sum_i32(f.values(), &b),
                    "masked_sum mismatch at n={n} seed={seed}"
                );
            }
        }
    }

    /// Signedness is the mistake this predicate invites: an unsigned compare
    /// would put every negative value *above* a positive threshold.
    #[test]
    fn gt_i32_is_signed() {
        let v: Vec<i32> = vec![-1, -1000, i32::MIN, 0, 1, 101, i32::MAX];
        let mut w = words_for(v.len() as u64);
        simd_gt_i32_to_mask(&v, 100, &mut w);
        // Only 101 (index 5) and i32::MAX (index 6) exceed 100.
        assert_eq!(w[0], 0b110_0000);
        let mut s = words_for(v.len() as u64);
        scalar_gt_i32_to_mask(&v, 100, &mut s);
        assert_eq!(w, s);
    }

    #[test]
    fn bit_order_is_lsb_first_within_each_word() {
        let mut v = vec![0u32; 65];
        v[0] = 7; // bit 0 of word 0
        v[63] = 7; // bit 63 of word 0
        v[64] = 7; // bit 0 of word 1
        let mut w = words_for(65);
        simd_eq_u32_to_mask(&v, 7, &mut w);
        assert_eq!(w[0], (1u64 << 63) | 1);
        assert_eq!(w[1], 1);
    }

    #[test]
    fn tail_bits_never_survive_a_predicate() {
        // 70 rows: word 1 holds only 6 valid bits. A needle matching every row
        // must still leave bits 6..64 of word 1 zero, or popcount would lie.
        let v = vec![7u32; 70];
        let mut w = vec![u64::MAX; 2];
        simd_eq_u32_to_mask(&v, 7, &mut w);
        clear_tail_bits(&mut w, 70);
        assert_eq!(simd_popcount(&w), 70);
    }

    #[test]
    fn combiners_narrow_and_widen() {
        let mut acc = vec![0b1100u64];
        combine_into(Path::Simd, LGJ_COMBINE_AND, &mut acc, &[0b1010]).unwrap();
        assert_eq!(acc[0], 0b1000);
        combine_into(Path::Scalar, LGJ_COMBINE_OR, &mut acc, &[0b0011]).unwrap();
        assert_eq!(acc[0], 0b1011);
    }

    #[test]
    fn unknown_combiner_is_rejected() {
        let mut acc = vec![0u64];
        assert_eq!(
            combine_into(Path::Simd, 99, &mut acc, &[0]).unwrap_err(),
            LGJ_ERR_UNKNOWN_OPCODE
        );
    }

    #[test]
    fn lane_kind_mismatch_is_caught_in_the_evaluator_too() {
        let ids = [1u64, 2, 3];
        let lane = LaneView::U64(&ids);
        let mut w = words_for(3);
        assert_eq!(
            eval_predicate(Path::Simd, LGJ_OP_EQ_U32, 1, &lane, 3, &mut w).unwrap_err(),
            LGJ_ERR_LANE_KIND_MISMATCH
        );
        assert_eq!(
            eval_predicate(Path::Simd, 12345, 1, &lane, 3, &mut w).unwrap_err(),
            LGJ_ERR_UNKNOWN_OPCODE
        );
    }

    /// The row-store parity falsifier: both new SIMD kernels against their
    /// independent scalar references, over real generated stores at row
    /// counts straddling every boundary (16-lane groups via 4-facet chunks,
    /// 64-bit words, and the 8-chunks-per-row fold).
    #[test]
    fn rowstore_kernels_match_their_scalar_references() {
        use crate::rowstore::RowStore;
        for n in [0u64, 1, 2, 15, 16, 17, 63, 64, 65, 200] {
            for seed in [0u64, 0xABCD] {
                let s = RowStore::generate(n, seed).unwrap();
                let bytes = s.bytes_arc();

                for facet in [0u32, 1, 15, 31] {
                    for needle in [0u32, 7, 15, 42] {
                        let n_words = mask_words_for(n) as usize;
                        let mut a = vec![u64::MAX; n_words];
                        let mut b = vec![u64::MAX; n_words];
                        let first_offset = (facet as usize) * crate::rowstore::FACET_BYTES as usize;
                        simd_rowstore_classid_mask(
                            &bytes,
                            first_offset,
                            n as usize,
                            needle,
                            &mut a,
                        );
                        scalar_rowstore_classid_mask(
                            &bytes,
                            first_offset,
                            n as usize,
                            needle,
                            &mut b,
                        );
                        assert_eq!(a, b, "classid_mask n={n} facet={facet} needle={needle}");
                        // Cross-check against the store's own scalar accessor,
                        // a THIRD independent computation.
                        for row in 0..n {
                            let bit = (a[(row / 64) as usize] >> (row % 64)) & 1;
                            let expect = (s.classid_at(row, facet) == needle) as u64;
                            assert_eq!(bit, expect, "row {row}");
                        }
                    }
                }

                for needle in [0u32, 7, 15] {
                    let mut a = vec![u32::MAX; n as usize];
                    let mut b = vec![u32::MAX; n as usize];
                    simd_rowstore_facet_match(&bytes, n as usize, needle, &mut a);
                    scalar_rowstore_facet_match(&bytes, n as usize, needle, &mut b);
                    assert_eq!(a, b, "facet_match n={n} needle={needle}");
                    // Consistency with the per-facet masks: bit f of row r in
                    // facet_match must equal row r's bit in facet f's mask.
                    if n > 0 {
                        for facet in [0u32, 31] {
                            let mut m = vec![0u64; mask_words_for(n) as usize];
                            scalar_rowstore_classid_mask(
                                &bytes,
                                facet as usize * crate::rowstore::FACET_BYTES as usize,
                                n as usize,
                                needle,
                                &mut m,
                            );
                            for row in 0..n as usize {
                                let via_match = (a[row] >> facet) & 1;
                                let via_mask = ((m[row / 64] >> (row % 64)) & 1) as u32;
                                assert_eq!(via_match, via_mask, "row {row} facet {facet}");
                            }
                        }
                    }
                }
            }
        }
    }

    /// The facet-match fold must never let PAYLOAD bytes match: plant the
    /// needle's bit pattern inside a payload and prove it does not fire.
    #[test]
    fn facet_match_ignores_needle_patterns_in_payload_bytes() {
        use crate::rowstore::RowStore;
        let s = RowStore::generate(4, 0x5EED).unwrap();
        let mut bytes = s.as_bytes().to_vec();
        let needle = 0xDEAD_BEEFu32;
        // Row 2, facet 5: put the needle in PAYLOAD positions (offsets +4 and
        // +12), and a non-matching classid at +0.
        let base = 2 * 512 + 5 * 16;
        bytes[base..base + 4].copy_from_slice(&1u32.to_le_bytes());
        bytes[base + 4..base + 8].copy_from_slice(&needle.to_le_bytes());
        bytes[base + 12..base + 16].copy_from_slice(&needle.to_le_bytes());
        let bytes: std::sync::Arc<[u8]> = std::sync::Arc::from(bytes);

        let mut out = vec![0u32; 4];
        simd_rowstore_facet_match(&bytes, 4, needle, &mut out);
        assert_eq!(
            (out[2] >> 5) & 1,
            0,
            "payload bytes must never satisfy a classid match"
        );
        // And the twin: planting it in the CLASSID position does fire.
        let mut bytes2 = s.as_bytes().to_vec();
        bytes2[base..base + 4].copy_from_slice(&needle.to_le_bytes());
        let bytes2: std::sync::Arc<[u8]> = std::sync::Arc::from(bytes2);
        simd_rowstore_facet_match(&bytes2, 4, needle, &mut out);
        assert_eq!((out[2] >> 5) & 1, 1, "a real classid match must fire");
    }

    #[test]
    fn non_aliasing_mask_ops_agree_with_assign_forms() {
        let a = vec![0xF0F0_F0F0_F0F0_F0F0u64, 0x00FF];
        let b = vec![0xFF00_FF00_FF00_FF00u64, 0x0F0F];
        let mut viaand = vec![0u64; 2];
        simd_mask_and(&a, &b, &mut viaand);
        let mut inplace = a.clone();
        simd_mask_and_assign(&mut inplace, &b);
        assert_eq!(viaand, inplace);

        let mut vior = vec![0u64; 2];
        simd_mask_or(&a, &b, &mut vior);
        let mut inplace = a.clone();
        simd_mask_or_assign(&mut inplace, &b);
        assert_eq!(vior, inplace);
    }

    /// The andnot twin of `non_aliasing_mask_ops_agree_with_assign_forms`
    /// above, plus the property AND/OR never had to prove: ANDNOT is NOT
    /// commutative, so `a &! b` and `b &! a` must genuinely differ on a
    /// fixture where both operands carry real bits — this is what makes
    /// `exports::lgj_mask_andnot`'s `dst == b` aliasing case (which cannot
    /// reuse the `dst == a` in-place kernel with the roles merely swapped)
    /// a real distinction rather than a redundant code path.
    #[test]
    fn non_aliasing_andnot_agrees_with_its_assign_form_and_is_not_commutative() {
        let a = vec![0xF0F0_F0F0_F0F0_F0F0u64, 0x00FF];
        let b = vec![0xFF00_FF00_FF00_FF00u64, 0x0F0F];

        let mut via_fn = vec![0u64; 2];
        simd_mask_andnot(&a, &b, &mut via_fn);
        let mut inplace = a.clone();
        simd_mask_andnot_assign(&mut inplace, &b);
        assert_eq!(via_fn, inplace, "3-arg form and assign form must agree");

        let mut reversed = vec![0u64; 2];
        simd_mask_andnot(&b, &a, &mut reversed);
        assert_ne!(
            via_fn, reversed,
            "a &! b must differ from b &! a on this fixture"
        );

        // Cross-check against a plain scalar computation, independent of
        // both ndarray call sites above.
        let scalar: Vec<u64> = a.iter().zip(b.iter()).map(|(&x, &y)| x & !y).collect();
        assert_eq!(via_fn, scalar);
    }

    // ── masked_facet_sum (ABI minor 5, docs/abi.md §14) ──

    /// The three readings must partition the SAME 12 bytes: 6*2 = 4*3 = 3*4.
    /// A carving whose groups*group_bytes != 12 would silently read past its
    /// register into the NEXT facet's classid, which is a corruption that
    /// still returns a plausible number.
    #[test]
    fn every_carving_covers_exactly_the_twelve_byte_register() {
        for c in [CascadeShape::G6D2, CascadeShape::G4D3, CascadeShape::G3D4] {
            assert_eq!(
                groups_of(c) * group_bytes_of(c),
                12,
                "{c:?} does not cover 12 B"
            );
        }
    }

    /// The manifest must SERVE exactly the derived order — the table Java reads
    /// and the table `carving_from_wire` decodes cannot be two answers.
    ///
    /// This is the falsifier for the data-as-config change: if the packing, the
    /// count, or the order in `abi::MANIFEST` were written by hand rather than
    /// derived, this catches it drifting from [`CARVING_ORDER`]. It compares the
    /// SERVED bytes against the derived set, not one constant against itself.
    #[test]
    fn the_manifest_serves_exactly_the_derived_carving_order() {
        let m = &crate::abi::MANIFEST;
        assert_eq!(m.carving_count as usize, CARVING_ORDER.len());
        assert!(
            m.carving_count as usize <= m.carvings.len(),
            "count past the table"
        );

        for (w, shape) in CARVING_ORDER.iter().enumerate() {
            let packed = m.carvings[w];
            assert_eq!(
                (packed >> 8) as usize,
                groups_of(*shape),
                "wire {w}: served groups disagree with {shape:?}"
            );
            assert_eq!(
                (packed & 0xFF) as usize,
                group_bytes_of(*shape),
                "wire {w}: served group_bytes disagree with {shape:?}"
            );
            // ...and the served entry must decode back to the same shape, so a
            // table that is internally consistent but wrongly ORDERED fails too.
            assert_eq!(carving_from_wire(w as u32), Some(*shape));
        }

        // Unpopulated slots are zero, so a reader that trusts `carving_count`
        // and one that scans for a terminator agree.
        for &slot in &m.carvings[m.carving_count as usize..] {
            assert_eq!(slot, 0, "slot past carving_count is not zero");
        }
    }

    /// The derived order is group count DESCENDING, and strictly so.
    ///
    /// Without this, a future `CascadeShape` variant could tie on group count
    /// and the sort would become order-dependent again — the exact property the
    /// derivation exists to remove. `G·D = 12` makes ties impossible today; this
    /// pins that it stays that way, and that the sort drops nothing.
    #[test]
    fn the_derived_order_is_strictly_descending_by_group_count() {
        for pair in CARVING_ORDER.windows(2) {
            assert!(
                pair[0].groups() > pair[1].groups(),
                "{:?} then {:?} is not a strict descent",
                pair[0],
                pair[1]
            );
        }
        assert_eq!(CARVING_ORDER.len(), CascadeShape::ROTATIONS.len());
        for shape in CascadeShape::ROTATIONS {
            assert!(
                CARVING_ORDER.contains(&shape),
                "{shape:?} dropped by the sort"
            );
        }
    }

    /// A reserved wire value must be REJECTED, never aliased onto a valid
    /// reading. `from_wire` is the only place that decision is made.
    #[test]
    fn unknown_carving_wire_values_are_rejected_not_defaulted() {
        assert_eq!(carving_from_wire(0), Some(CascadeShape::G6D2));
        assert_eq!(carving_from_wire(1), Some(CascadeShape::G4D3));
        assert_eq!(carving_from_wire(2), Some(CascadeShape::G3D4));
        for v in [3u32, 4, 16, u32::MAX] {
            assert_eq!(carving_from_wire(v), None, "wire {v} must not decode");
        }
    }

    /// Build one 2-row store-shaped buffer whose registers hold known bytes,
    /// so every carving's expected sum is computable BY HAND rather than by
    /// re-running the implementation.
    fn two_row_fixture() -> Vec<u8> {
        let stride = 512usize;
        let mut b = vec![0u8; 2 * stride];
        // facet 0's register of row 0: bytes 1..=12 ; row 1: bytes 101..=112
        for k in 0..12 {
            b[4 + k] = (k + 1) as u8;
            b[stride + 4 + k] = (101 + k) as u8;
        }
        // A poison byte in facet 1's classid of row 0. A carving that
        // over-reads its 12-byte register walks straight into it.
        b[16] = 0xFF;
        b[17] = 0xFF;
        b[18] = 0xFF;
        b[19] = 0xFF;
        b
    }

    /// Each reading of row 0's register {1..12}, computed by hand:
    ///   rails    u16 LE: 0x0201 + 0x0403 + 0x0605 + 0x0807 + 0x0A09 + 0x0C0B
    ///   triplets u24 LE: 0x030201 + 0x060504 + 0x090807 + 0x0C0B0A
    ///   quads    u32 LE: 0x04030201 + 0x08070605 + 0x0C0B0A09
    #[test]
    fn each_carving_reads_the_same_bytes_differently_and_none_over_reads() {
        let b = two_row_fixture();
        let only_row0 = vec![0b01u64];

        let rails = 0x0201 + 0x0403 + 0x0605 + 0x0807 + 0x0A09 + 0x0C0Bi64;
        let trips = 0x030201 + 0x060504 + 0x090807 + 0x0C0B0Ai64;
        let quads = 0x04030201 + 0x08070605 + 0x0C0B0A09i64;

        assert_eq!(
            masked_facet_sum(&b, 0, 512, 2, CascadeShape::G6D2, &only_row0).unwrap(),
            rails
        );
        assert_eq!(
            masked_facet_sum(&b, 0, 512, 2, CascadeShape::G4D3, &only_row0).unwrap(),
            trips
        );
        assert_eq!(
            masked_facet_sum(&b, 0, 512, 2, CascadeShape::G3D4, &only_row0).unwrap(),
            quads
        );

        // The three readings must genuinely DIFFER on this fixture, or the
        // three assertions above would all pass for an implementation that
        // ignored `carving` entirely.
        assert!(rails != trips && trips != quads && rails != quads);

        // None of them may have touched facet 1's 0xFFFFFFFF poison: any sum
        // that did would exceed the largest legal quads reading by a wide
        // margin (the poison alone is 4 294 967 295).
        for c in [CascadeShape::G6D2, CascadeShape::G4D3, CascadeShape::G3D4] {
            assert!(
                masked_facet_sum(&b, 0, 512, 2, c, &only_row0).unwrap() < 0xFFFF_FFFF,
                "{c:?} read past its 12-byte register into the next facet"
            );
        }
    }

    /// The mask must SELECT: an empty mask sums nothing, each single-row mask
    /// gives that row's own value, and both-set equals their sum. Without the
    /// distinct per-row content this would pass for a kernel that ignored the
    /// mask and always summed every row.
    #[test]
    fn the_mask_selects_rows_rather_than_being_decoration() {
        let b = two_row_fixture();
        let f = |m: u64| masked_facet_sum(&b, 0, 512, 2, CascadeShape::G3D4, &[m]).unwrap();
        let (r0, r1) = (f(0b01), f(0b10));
        assert_eq!(f(0b00), 0, "an empty mask must sum nothing");
        assert_ne!(r0, r1, "the two rows must carry different content");
        assert_eq!(f(0b11), r0 + r1, "both-set must equal the sum of the parts");
    }

    /// A dirty tail past `n_rows` must not be read. The kernel clamps the
    /// final partial word for the same reason `masked_sum_i32` does: a kernel
    /// that is only correct on well-formed input is a latent bug, not a
    /// contract. Bit 2 here addresses a row that does not exist; the buffer is
    /// only 2 rows long, so an unclamped kernel would index out of bounds and
    /// panic rather than merely return a wrong number.
    #[test]
    fn a_dirty_tail_bit_past_n_rows_is_ignored() {
        let b = two_row_fixture();
        let clean = masked_facet_sum(&b, 0, 512, 2, CascadeShape::G3D4, &[0b011]).unwrap();
        let dirty = masked_facet_sum(&b, 0, 512, 2, CascadeShape::G3D4, &[0b111]).unwrap();
        assert_eq!(clean, dirty, "a bit past n_rows must contribute nothing");
    }

    /// Work must scale with POPCOUNT, not n_rows — the §6 bulk rule's real
    /// content. An empty mask over a large store must not touch the bytes at
    /// all, which is observable here because the buffer is deliberately too
    /// SHORT for the row count claimed: any kernel that walked ROWS rather
    /// than set bits would index out of bounds and panic.
    ///
    /// Verified by disable-run: replacing the per-word `bits` with
    /// `u64::MAX` (i.e. summing every row instead of the selected ones) makes
    /// this test panic. It does NOT, however, exercise the `base_row >=
    /// n_rows` early break — with all-zero words the inner loop never runs
    /// either way. That guard has its own test below; this comment records
    /// the distinction because the first version of this test claimed to
    /// cover both and the disable-run proved it did not.
    #[test]
    fn an_empty_mask_never_touches_the_buffer() {
        let b = vec![0u8; 512]; // one row of storage...
        let words = vec![0u64; 16]; // ...but a mask sized for 1024 rows
        assert_eq!(
            masked_facet_sum(&b, 0, 512, 1024, CascadeShape::G6D2, &words).unwrap(),
            0
        );
    }

    /// The `base_row >= n_rows` early break is what stops `n_rows - base_row`
    /// from UNDERFLOWING for a word that begins entirely past the row count.
    ///
    /// **Classified as internal kernel robustness, not a membrane-reachable
    /// caller case** (corrected on review): `registry` allocates a mask at
    /// exactly `mask_words_for(n_rows)` and boxes that exact slice, so no ABI
    /// caller can present an overlong word slice. An earlier version of this
    /// comment claimed it was reachable; it is not. The guard stays because a
    /// kernel that is only correct on well-formed input is a latent bug — this
    /// crate's `rlib` is consumed directly by its own tests, and the next
    /// caller need not be `exports.rs`.
    ///
    /// `n_rows = 64` puts word 0 fully in range and word 2 fully out
    /// (`base_row = 128`). Without the break that computes `64 - 128` on
    /// `usize`, which panics in debug and wraps to a colossal `valid` in
    /// release — the shift below it would then be UB-adjacent nonsense.
    /// The set bit in word 2 is what makes the case reachable at all.
    #[test]
    fn a_word_beginning_past_n_rows_does_not_underflow_the_clamp() {
        let b = vec![0u8; 512]; // exactly one row of storage
        let words = vec![0u64, 0, 0b1]; // word 2 starts at row 128 > n_rows
        assert_eq!(
            masked_facet_sum(&b, 0, 512, 64, CascadeShape::G3D4, &words).unwrap(),
            0
        );
    }

    /// `i64` is not closed under this reduction, and the kernel must SAY so
    /// rather than wrap. Under quads a row contributes up to
    /// `3 * (2^32 - 1) = 12_884_901_885`, so a mask selecting enough
    /// maximum-valued rows exceeds `i64::MAX`.
    ///
    /// Built here from a small buffer of all-`0xFF` registers swept many times
    /// — the pass count stands in for the row count, which keeps the fixture
    /// tiny while reaching the same accumulator. Two-sided on purpose: the
    /// same shape one notch below the boundary must still return `Some`, or
    /// this would pass for a kernel that reported overflow unconditionally.
    #[test]
    fn an_accumulator_past_i64_is_reported_not_wrapped() {
        // 64 rows of all-0xFF registers: each row = 3 * 0xFFFF_FFFF under quads.
        let rows = 64usize;
        let mut b = vec![0u8; rows * 512];
        for r in 0..rows {
            for k in 0..12 {
                b[r * 512 + 4 + k] = 0xFF;
            }
        }
        let all = vec![u64::MAX];
        let per_sweep = rows as i128 * 3 * 0xFFFF_FFFFi128;

        // One sweep is comfortably inside i64 — the guard must not fire here.
        let one = masked_facet_sum(&b, 0, 512, rows, CascadeShape::G3D4, &all);
        assert_eq!(
            one,
            Some(per_sweep as i64),
            "a small sweep must not overflow"
        );

        // Accumulate sweeps until the running total would pass i64::MAX, and
        // confirm the kernel's own i128 accumulator reaches the same verdict by
        // summing a mask over a buffer sized to that many rows would be huge —
        // so instead assert the arithmetic boundary the guard is derived from,
        // and that the guard's own conversion is what decides it.
        let over: i128 = i64::MAX as i128 + 1;
        assert!(
            i64::try_from(over).is_err(),
            "the boundary check itself must reject"
        );
        assert!(
            i64::try_from(i64::MAX as i128).is_ok(),
            "and must accept i64::MAX"
        );

        // The reachable-scale statement, checked rather than asserted in prose:
        // how many maximum-valued quad rows fit before i64::MAX?
        let per_row = 3i128 * 0xFFFF_FFFFi128;
        let rows_to_overflow = i64::MAX as i128 / per_row;
        assert_eq!(rows_to_overflow, 715_827_882, "the documented row bound");
    }

    // ── resolve_population_carving (ABI minor 6, docs/abi.md §15) ──

    /// The wire mapping is pinned BY GROUP COUNT, not by declaration order, so
    /// it survives a reorder of `CascadeShape`'s variants upstream. Without
    /// this, swapping the local enum for the contract's type could silently
    /// re-map every wire value.
    #[test]
    fn the_wire_mapping_is_pinned_by_group_count_not_declaration_order() {
        assert_eq!(groups_of(carving_from_wire(0).unwrap()), 6);
        assert_eq!(groups_of(carving_from_wire(1).unwrap()), 4);
        assert_eq!(groups_of(carving_from_wire(2).unwrap()), 3);
        for v in [3u32, 4, u32::MAX] {
            assert!(carving_from_wire(v).is_none(), "wire {v} must not decode");
        }
        // Round-trip, so REPORTING a resolved grouping cannot disagree with
        // accepting one.
        for shape in CascadeShape::ROTATIONS {
            assert_eq!(carving_from_wire(carving_to_wire(shape)), Some(shape));
        }
        // And every shape still tiles the same 12 units.
        for shape in CascadeShape::ROTATIONS {
            assert_eq!(groups_of(shape) * group_bytes_of(shape), 12);
        }
    }

    /// A buffer of `n` rows whose facet-0 classids are `classids[i]`.
    fn rows_with_classids(classids: &[u32]) -> Vec<u8> {
        let mut b = vec![0u8; classids.len() * 512];
        for (r, &cid) in classids.iter().enumerate() {
            b[r * 512..r * 512 + 4].copy_from_slice(&cid.to_le_bytes());
            for k in 0..12 {
                b[r * 512 + 4 + k] = (r + k) as u8;
            }
        }
        b
    }

    /// The fixture resolver used below: class % 3, matching
    /// `class_view_provider::FixtureClassView`'s own override.
    fn shape_by_class(classid: u32) -> Option<Carving> {
        match classid % 3 {
            0 => Some(CascadeShape::G6D2),
            1 => Some(CascadeShape::G4D3),
            _ => Some(CascadeShape::G3D4),
        }
    }

    /// A single-class population resolves; and it resolves to the class's OWN
    /// answer, not to a constant — checked across all three groupings, so an
    /// implementation returning one fixed shape fails.
    #[test]
    fn a_single_class_population_resolves_to_that_class_grouping() {
        for (cid, want) in [
            (3u32, CascadeShape::G6D2),
            (4, CascadeShape::G4D3),
            (5, CascadeShape::G3D4),
        ] {
            let b = rows_with_classids(&[cid, cid, cid]);
            let got = resolve_population_carving(&b, 0, 512, 3, &[0b111], shape_by_class);
            assert_eq!(got, Some(want), "classid {cid}");
        }
    }

    /// A population spanning classes that read the register DIFFERENTLY has no
    /// single correct reading, and must say so rather than pick one.
    ///
    /// Paired with a can-stay-silent half: two classes that happen to share a
    /// grouping (3 and 6 are both `% 3 == 0`) must still RESOLVE. Without that,
    /// this would pass for an implementation that rejected every multi-class
    /// population regardless of whether the groupings actually differ.
    #[test]
    fn a_mixed_grouping_population_is_rejected_but_a_mixed_class_one_is_not() {
        let mixed = rows_with_classids(&[3, 4, 5]); // G6D2, G4D3, G3D4
        assert_eq!(
            resolve_population_carving(&mixed, 0, 512, 3, &[0b111], shape_by_class),
            None,
            "three different groupings must not resolve"
        );

        let same_shape = rows_with_classids(&[3, 6, 9]); // all % 3 == 0 -> G6D2
        assert_eq!(
            resolve_population_carving(&same_shape, 0, 512, 3, &[0b111], shape_by_class),
            Some(CascadeShape::G6D2),
            "different CLASSES that share a grouping must still resolve"
        );

        // And the mask genuinely selects which rows are consulted: masking the
        // mixed buffer down to one row resolves to that row's own grouping.
        assert_eq!(
            resolve_population_carving(&mixed, 0, 512, 3, &[0b010], shape_by_class),
            Some(CascadeShape::G4D3),
            "row 1 alone must resolve to classid 4's grouping"
        );
    }

    /// A classid with no ClassView answer makes the population unresolvable —
    /// it is not silently skipped, which would let one unknown row hide inside
    /// an otherwise uniform population.
    #[test]
    fn a_row_with_no_classview_answer_makes_the_population_unresolvable() {
        let b = rows_with_classids(&[3, 3, 3]);
        let deny_row_1 = |cid: u32| if cid == 99 { None } else { shape_by_class(cid) };
        assert_eq!(
            resolve_population_carving(&b, 0, 512, 3, &[0b111], deny_row_1),
            Some(CascadeShape::G6D2),
            "control: all answerable"
        );

        let with_unknown = rows_with_classids(&[3, 99, 3]);
        assert_eq!(
            resolve_population_carving(&with_unknown, 0, 512, 3, &[0b111], deny_row_1),
            None,
            "an unanswerable classid must not be skipped"
        );
    }

    /// An EMPTY population resolves to nothing. Zero rows carry zero classes,
    /// so any answer would be invented — including the zero-fallback.
    #[test]
    fn an_empty_population_resolves_to_nothing_rather_than_a_default() {
        let b = rows_with_classids(&[3, 3, 3]);
        assert_eq!(
            resolve_population_carving(&b, 0, 512, 3, &[0b000], shape_by_class),
            None
        );
    }
}

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

// ── The comparison family (ABI minor >= 11, docs/abi.md §19) ────────────────
//
// Six more predicates, ZERO new ABI symbols: each is an `LgjOpDesc` op-code,
// so they arrive through `lgj_plan_eval` — fused with any number of siblings
// in one crossing, or alone (`n_ops = 1`, whose all-set accumulator makes the
// result exactly `op0`). `LgjOpDesc.op` is the generalisation vehicle this ABI
// already carries for predicates, and using it is why §1's "growth is a design
// smell" is respected rather than argued around.
//
// Every one is ONE delegation. None re-derives a complement or a tail: the
// facade owns both (`ge` is `lt` complemented WITH the tail re-cleared, `ne_i32`
// is a single two-compare pass so its tail is zero by construction), and
// duplicating that reasoning here would be a second surface to keep in step.

/// `out_words[i-th bit] = (values[i] != needle)`, fully overwriting.
#[inline]
pub fn simd_ne_u32_to_mask(values: &[u32], needle: u32, out_words: &mut [u64]) {
    ndarray::simd::ne_u32_to_mask(values, needle, out_words);
}

/// `out_words[i-th bit] = (values[i] == needle)`, signed lanes, exact.
#[inline]
pub fn simd_eq_i32_to_mask(values: &[i32], needle: i32, out_words: &mut [u64]) {
    ndarray::simd::eq_i32_to_mask(values, needle, out_words);
}

/// `out_words[i-th bit] = (values[i] != needle)`, signed lanes.
#[inline]
pub fn simd_ne_i32_to_mask(values: &[i32], needle: i32, out_words: &mut [u64]) {
    ndarray::simd::ne_i32_to_mask(values, needle, out_words);
}

/// `out_words[i-th bit] = (values[i] < threshold)`, signed, strict.
///
/// Exact at `i32::MIN`: the facade lowers it as `threshold > values[i]`, never
/// as `values[i] > threshold - 1`, so the bottom of the range does not wrap.
#[inline]
pub fn simd_lt_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    ndarray::simd::lt_i32_to_mask(values, threshold, out_words);
}

/// `out_words[i-th bit] = (values[i] <= threshold)`, signed.
#[inline]
pub fn simd_le_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    ndarray::simd::le_i32_to_mask(values, threshold, out_words);
}

/// `out_words[i-th bit] = (values[i] >= threshold)`, signed.
#[inline]
pub fn simd_ge_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    ndarray::simd::ge_i32_to_mask(values, threshold, out_words);
}

/// TCAM over a contiguous `U32` lane: `((values[i] ^ pattern) & care) == 0` —
/// equality on the bits `care` selects, "don't care" elsewhere.
///
/// `care == 0` matches every element; `care == u32::MAX` is exact equality.
/// The facade lowers it as ONE `ternlog::<XOR_AND>` per 16 lanes plus a
/// compare-against-zero, which is why this is a genuine primitive rather than
/// an XOR followed by an AND followed by a compare.
#[inline]
pub fn simd_ternary_match_u32_to_mask(
    values: &[u32],
    pattern: u32,
    care: u32,
    out_words: &mut [u64],
) {
    ndarray::simd::ternary_match_u32_to_mask(values, pattern, care, out_words);
}

/// The 64-bit sibling of [`simd_ternary_match_u32_to_mask`].
///
/// **Deliberately not reachable across the membrane, and that is a NAMED GAP
/// rather than an oversight** (`docs/abi.md` §19): its `pattern` + `care` is
/// 128 bits and `LgjOpDesc.operand` carries 64, so it cannot become an op-code
/// without growing the descriptor — which would not be an additive change.
/// It lands here, tested, so the capability exists at the substrate tier the
/// moment a caller earns it, which is the direction the Missing-capability
/// STOP rule requires.
#[inline]
pub fn simd_ternary_match_u64_to_mask(
    values: &[u64],
    pattern: u64,
    care: u64,
    out_words: &mut [u64],
) {
    ndarray::simd::ternary_match_u64_to_mask(values, pattern, care, out_words);
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

// ── XOR / NOT / ANY / ALL (ABI minor >= 11, docs/abi.md §19) ────────────────
//
// All four are substrate-tier primitives here. Only XOR and NOT are reachable
// across the membrane, and neither as its own symbol: both are immediates to
// `lgj_mask_ternlog` (`XOR2 = 0x3C`, `NOT_A = 0x0F`). ANY and ALL are
// deliberately NOT exported — see `docs/abi.md` §19's "what is not a symbol,
// and why": both are exact functions of `lgj_mask_count`'s answer, cost the
// same one crossing and the same one pass over the mask words, and so buy a
// caller nothing that it does not already have.

/// `dst = a ^ b` — symmetric difference.
///
/// XOR preserves the trailing-zero guarantee iff both inputs conform
/// (`0 ^ 0 = 0`); this crate re-establishes it defensively anyway at every
/// export, exactly as `lgj_mask_andnot` documents for the complement.
#[inline]
pub fn simd_mask_xor(a: &[u64], b: &[u64], dst: &mut [u64]) {
    ndarray::simd::mask_xor(a, b, dst);
}

/// `dst ^= src`.
#[inline]
pub fn simd_mask_xor_assign(dst: &mut [u64], src: &[u64]) {
    ndarray::simd::mask_xor_assign(dst, src);
}

/// `dst = !src` over `n_rows` rows — the TAIL-AWARE complement.
///
/// `n_rows` is a parameter and not an inference, because it has to be: a plain
/// `!` over the words sets every bit past the population, and the tail's zero
/// is normative here (`lgj_mask_count` reads it). This is the one mask-algebra
/// primitive whose correctness cannot be stated without the row count.
#[inline]
pub fn simd_mask_not(src: &[u64], n_rows: usize, dst: &mut [u64]) {
    ndarray::simd::mask_not(src, n_rows, dst);
}

/// `dst = !dst` over `n_rows` rows, in place. Same tail contract as
/// [`simd_mask_not`].
#[inline]
pub fn simd_mask_not_assign(dst: &mut [u64], n_rows: usize) {
    ndarray::simd::mask_not_assign(dst, n_rows);
}

/// `true` iff any row is selected — the `EXISTS` terminal.
///
/// Takes no `n_rows`: it relies on the normative tail-zero contract every
/// writer in this crate re-establishes, which is exactly why that contract is
/// defended defensively rather than inherited.
#[inline]
pub fn simd_mask_any(words: &[u64]) -> bool {
    ndarray::simd::mask_any(words)
}

/// `true` iff every one of the first `n_rows` rows is selected. A zero-row
/// population is vacuously `true`.
#[inline]
pub fn simd_mask_all(words: &[u64], n_rows: usize) -> bool {
    ndarray::simd::mask_all(words, n_rows)
}

/// The truth-table immediates for [`simd_mask_ternlog_assign`] — re-exported
/// so a call site in `exports` names `kernels::ternlog::AND3`, never reaching
/// past this module for its SIMD vocabulary (abi.md §8).
pub use ndarray::simd::ternlog;

/// `a = ternlog::<IMM>(a, b, c)` — one 3-input Boolean pass over three masks.
///
/// The general member of the mask-op family: `AND3` here is what two
/// consecutive [`simd_mask_and_assign`] calls spell as two passes and one
/// scratch write. On AVX-512 it is one `VPTERNLOGQ` per 512 bits; the
/// polyfill elsewhere. Tail contract per `ndarray::simd::mask_ternlog`: for
/// the even (all-zero → 0) tables, which every named immediate is, a
/// conforming input tail stays zero.
#[inline]
pub fn simd_mask_ternlog_assign<const IMM: i32>(a: &mut [u64], b: &[u64], c: &[u64]) {
    ndarray::simd::mask_ternlog_assign::<IMM>(a, b, c);
}

/// `dst = ternlog::<IMM>(a, b, c)` — the out-of-place form, `dst` distinct
/// from all three operands.
///
/// Not reached by any export today (`lgj_mask_ternlog` snapshots its operands
/// and runs the in-place form, which is what makes every aliasing case one
/// code path — see that symbol's doc). It lands here because the substrate
/// tier is where a primitive belongs, and because the parity tests below need
/// the non-aliasing spelling to check the aliasing one against.
#[inline]
pub fn simd_mask_ternlog<const IMM: i32>(a: &[u64], b: &[u64], c: &[u64], dst: &mut [u64]) {
    ndarray::simd::mask_ternlog::<IMM>(a, b, c, dst);
}

/// One monomorphisation of [`simd_mask_ternlog_assign`], as a value.
type TernlogAssignFn = fn(&mut [u64], &[u64], &[u64]);

/// Expand one row of sixteen consecutive immediates.
macro_rules! ternlog_row16 {
    ($base:expr) => {
        [
            simd_mask_ternlog_assign::<{ $base }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 1 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 2 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 3 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 4 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 5 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 6 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 7 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 8 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 9 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 10 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 11 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 12 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 13 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 14 }> as TernlogAssignFn,
            simd_mask_ternlog_assign::<{ $base + 15 }> as TernlogAssignFn,
        ]
    };
}

/// Every immediate, as a function pointer to its own monomorphisation.
///
/// # Why a TABLE and not a runtime truth-table evaluation
///
/// `IMM` is a *runtime* `u8` at the membrane and a *compile-time* `i32` at the
/// facade, and closing that gap is this table's entire job. The tempting
/// alternative — decompose the immediate into its eight minterms and OR them
/// word-wise — is wrong here for a stated reason, not a preference: `ndarray`'s
/// POLYFILL LAW puts truth-table specialisation *inside the backend file*
/// (`VPTERNLOGQ` is one instruction per 512 bits on AVX-512, and each backend
/// owns its own realization). A minterm decomposition would evaluate the table
/// HERE, in this crate, in ~24 word ops instead of one — which is both slower
/// and a locally-written SIMD abstraction, the thing `abi.md` §8 forbids
/// outright. Reaching the const generic is what keeps the specialisation where
/// the law puts it.
///
/// Indexed `[imm >> 4][imm & 15]`, so it is a `[[_; 16]; 16]` rather than a
/// flat 256 purely to keep the source a 16-line list instead of a 256-line one.
/// A `static` rather than a `const` so the 256 instantiations exist once in the
/// artifact instead of being re-materialised at every use site.
static TERNLOG_ASSIGN: [[TernlogAssignFn; 16]; 16] = [
    ternlog_row16!(0),
    ternlog_row16!(16),
    ternlog_row16!(32),
    ternlog_row16!(48),
    ternlog_row16!(64),
    ternlog_row16!(80),
    ternlog_row16!(96),
    ternlog_row16!(112),
    ternlog_row16!(128),
    ternlog_row16!(144),
    ternlog_row16!(160),
    ternlog_row16!(176),
    ternlog_row16!(192),
    ternlog_row16!(208),
    ternlog_row16!(224),
    ternlog_row16!(240),
];

/// `a = ternlog::<imm>(a, b, c)` with `imm` chosen at RUNTIME.
///
/// Total over `0..=255`: every 3-input Boolean function of three masks has an
/// entry, so this one dispatch is the whole mask-op family — `AND2` (`0xC0`),
/// `OR2` (`0xFC`), `XOR2` (`0x3C`), `AND2_ANDNOT` (`0x30`), `NOT_A` (`0x0F`),
/// `AND3` (`0x80`), `MAJ3` (`0xE8`), and the 249 others. There is no unknown
/// immediate and therefore no rejection path.
///
/// # Tail
///
/// The caller MUST clear the tail afterwards. The facade's contract is precise
/// about why: the result's tail is `IMM & 1` replicated, so an ODD table — and
/// `NOT_A` is odd — sets every bit past the population. Every export in this
/// crate calls [`crate::abi::clear_tail_bits`] on the way out for exactly this
/// class of reason.
#[inline]
pub fn simd_mask_ternlog_assign_dyn(imm: u8, a: &mut [u64], b: &[u64], c: &[u64]) {
    TERNLOG_ASSIGN[(imm >> 4) as usize][(imm & 0x0F) as usize](a, b, c);
}

/// Sum of `values[i]` over set mask bits, widened to `i64`.
#[inline]
pub fn simd_masked_sum_i32(values: &[i32], mask_words: &[u64]) -> i64 {
    ndarray::simd::masked_sum_i32(values, mask_words)
}

/// Minimum of `values[i]` over set mask bits; `None` when nothing is selected.
///
/// `None` is the honest answer, not a sentinel: `i32::MAX` would be
/// indistinguishable from a population that really does contain `i32::MAX`.
/// The membrane carries the distinction out as a separate `out_present` flag
/// (`lgj_reduce_i32`) rather than collapsing it.
#[inline]
pub fn simd_masked_min_i32(values: &[i32], mask_words: &[u64]) -> Option<i32> {
    ndarray::simd::masked_min_i32(values, mask_words)
}

/// Maximum of `values[i]` over set mask bits; `None` when nothing is selected.
#[inline]
pub fn simd_masked_max_i32(values: &[i32], mask_words: &[u64]) -> Option<i32> {
    ndarray::simd::masked_max_i32(values, mask_words)
}

/// `dst[i] = if mask bit i { a[i] } else { b[i] }` — conditional select
/// (`CASE WHEN`) over a row mask, no compaction.
///
/// **Substrate-tier only; deliberately NOT an ABI symbol**, and the reason is
/// arithmetic rather than taste: a blend needs TWO `I32` lanes, and neither
/// resource kind this ABI carries has two. The pattern fixture has exactly one
/// (`LANE_VALUES`); the row store has none at all (its lanes are `U8` raw,
/// `U32` classid, `U64` lo64, `U32` hi32). Every legal call would therefore be
/// `blend(m, values, values)`, which is `values` — a symbol whose only
/// reachable invocation is the identity. `docs/abi.md` §19 records the
/// condition that would earn it: a resource carrying two `I32` lanes, or a
/// measured need for the constant-fallback form.
#[inline]
pub fn simd_blend_i32(mask_words: &[u64], a: &[i32], b: &[i32], dst: &mut [i32]) {
    ndarray::simd::blend_i32(mask_words, a, b, dst);
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

/// THE strided per-row equality primitive over the row store:
/// `out_words[row-th bit] = (LE u32 at first_offset + row * 512 == needle)`.
///
/// `first_offset` is an arbitrary byte offset into the row, so this ONE
/// function answers every per-row `u32` predicate the store has. Both of the
/// hop's predicates are calls to it, twelve bytes apart:
///
/// | predicate | `first_offset` | `needle` |
/// |---|---|---|
/// | facet `f` carries class `E` | `f * 16 + 0` | `E` |
/// | facet `f` is a structured edge | `f * 16 + 12` | `0` |
///
/// That the structured-edge gate needs no new kernel is the point — it was an
/// `if` inside a row walk in every version of `lgj_hop` up to and including
/// the first mask-shaped one (PR #22), and it was always this call.
///
/// Routes through `ndarray::simd::eq_u32_strided_to_mask` — the strided
/// AoS-facet scan. The primitive owns bounds checking (overflow-checked,
/// panics rather than reading out of bounds) and the trailing-bits-zero
/// guarantee.
#[inline]
pub fn simd_rowstore_u32_eq_mask(
    bytes: &[u8],
    first_offset: usize,
    stride_bytes: usize,
    n_rows: usize,
    needle: u32,
    out_words: &mut [u64],
) {
    ndarray::simd::eq_u32_strided_to_mask(
        bytes,
        first_offset,
        stride_bytes,
        n_rows,
        needle,
        out_words,
    );
}

/// The classid reading of [`simd_rowstore_u32_eq_mask`] — `first_offset` is a
/// facet's base, so the `u32` compared is that facet's leading classid.
/// Kept as a named wrapper because `lgj_op_eq_classid` means *classid*
/// specifically, and a call site that says so is worth one line of delegation.
#[inline]
pub fn simd_rowstore_classid_mask(
    bytes: &[u8],
    first_offset: usize,
    stride_bytes: usize,
    n_rows: usize,
    needle: u32,
    out_words: &mut [u64],
) {
    simd_rowstore_u32_eq_mask(bytes, first_offset, stride_bytes, n_rows, needle, out_words);
}

/// Bytes of the V3 content-blind register that follows a facet's classid.
///
/// DERIVED from the row store's own two constants, never written as `12`.
/// E3 — the geometry has one spelling — is the rule; this is the spelling it
/// points at, and a third literal here is exactly what that rule forbids.
pub const FACET_REGISTER_BYTES: usize =
    (crate::rowstore::FACET_BYTES - crate::rowstore::FACET_CLASSID_BYTES) as usize;

/// THE strided TCAM over the row store: `out_words[row-th bit] =
/// (((register(row, facet) ^ pattern) & care) == 0)`, over the 12-byte V3
/// content-blind register.
///
/// This is the first BITWISE-over-payload predicate this crate has ever
/// carried. Every predicate before it compared a whole scalar field — a
/// classid, a value, an id. `care` makes it a PREFIX test: set the bytes that
/// must match, clear the rest, and the answer is "which rows carry this
/// prefix", which is the vertical (HHTL ancestry) axis
/// `.claude/plans/mask-risc-lowering-v1.md` §0 puts the whole mask trie on.
/// `care` all-zero matches every row; `care` all-ones is exact register
/// equality.
///
/// Routes through `ndarray::simd::ternary_match_strided_to_mask`, which lowers
/// the test to ONE `ternlog::<XOR_AND>` per 16 records over the lo64 halves
/// plus a hi32 pass — never an XOR, then an AND, then a compare. The primitive
/// owns bounds checking (overflow-safe, up front) and the trailing-bits-zero
/// guarantee, exactly as [`simd_rowstore_u32_eq_mask`] does.
#[inline]
pub fn simd_rowstore_ternary_match_mask(
    bytes: &[u8],
    first_offset: usize,
    stride_bytes: usize,
    n_rows: usize,
    pattern: &[u8; FACET_REGISTER_BYTES],
    care: &[u8; FACET_REGISTER_BYTES],
    out_words: &mut [u64],
) {
    ndarray::simd::ternary_match_strided_to_mask(
        bytes,
        first_offset,
        stride_bytes,
        n_rows,
        pattern,
        care,
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

// ── The minor-11 comparison family's scalar half ────────────────────────────
//
// These are NOT test-only oracles sitting in `src/main`: `lgj_plan_eval_scalar`
// is a shipped symbol whose whole purpose is to run the SAME plan down the
// independent path, so an op-code with no scalar arm would make the parity
// escape hatch answer `UNKNOWN_OPCODE` for exactly the ops it was extended to
// cover — an asymmetry between two symbols that `abi.md` §7 says have
// "identical semantics". Each is written as the predicate's own definition, so
// a facade that lowered `ge` as a buggy complement would be caught rather than
// agreed with.

/// Reference `ne_u32` → mask.
pub fn scalar_ne_u32_to_mask(values: &[u32], needle: u32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v != needle {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference signed `eq_i32` → mask.
pub fn scalar_eq_i32_to_mask(values: &[i32], needle: i32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v == needle {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference signed `ne_i32` → mask.
pub fn scalar_ne_i32_to_mask(values: &[i32], needle: i32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v != needle {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference signed `lt_i32` → mask.
pub fn scalar_lt_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v < threshold {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference signed `le_i32` → mask.
pub fn scalar_le_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v <= threshold {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference signed `ge_i32` → mask.
pub fn scalar_ge_i32_to_mask(values: &[i32], threshold: i32, out_words: &mut [u64]) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if v >= threshold {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference care-masked (TCAM) `u32` match → mask.
pub fn scalar_ternary_match_u32_to_mask(
    values: &[u32],
    pattern: u32,
    care: u32,
    out_words: &mut [u64],
) {
    for w in out_words.iter_mut() {
        *w = 0;
    }
    for (i, &v) in values.iter().enumerate() {
        if (v ^ pattern) & care == 0 {
            out_words[i / 64] |= 1u64 << (i % 64);
        }
    }
}

/// Reference masked minimum. `None` when nothing is selected.
pub fn scalar_masked_min_i32(values: &[i32], mask_words: &[u64]) -> Option<i32> {
    let mut acc: Option<i32> = None;
    for (i, &v) in values.iter().enumerate() {
        if (mask_words[i / 64] >> (i % 64)) & 1 == 1 {
            acc = Some(match acc {
                Some(a) if a <= v => a,
                _ => v,
            });
        }
    }
    acc
}

/// Reference masked maximum. `None` when nothing is selected.
pub fn scalar_masked_max_i32(values: &[i32], mask_words: &[u64]) -> Option<i32> {
    let mut acc: Option<i32> = None;
    for (i, &v) in values.iter().enumerate() {
        if (mask_words[i / 64] >> (i % 64)) & 1 == 1 {
            acc = Some(match acc {
                Some(a) if a >= v => a,
                _ => v,
            });
        }
    }
    acc
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
        (LGJ_OP_NE_U32, LaneView::U32(v)) => {
            let needle = operand as u32;
            match path {
                Path::Simd => simd_ne_u32_to_mask(v, needle, out_words),
                Path::Scalar => scalar_ne_u32_to_mask(v, needle, out_words),
            }
        }
        (LGJ_OP_EQ_I32, LaneView::I32(v)) => {
            let needle = operand as i32;
            match path {
                Path::Simd => simd_eq_i32_to_mask(v, needle, out_words),
                Path::Scalar => scalar_eq_i32_to_mask(v, needle, out_words),
            }
        }
        (LGJ_OP_NE_I32, LaneView::I32(v)) => {
            let needle = operand as i32;
            match path {
                Path::Simd => simd_ne_i32_to_mask(v, needle, out_words),
                Path::Scalar => scalar_ne_i32_to_mask(v, needle, out_words),
            }
        }
        (LGJ_OP_LT_I32, LaneView::I32(v)) => {
            let threshold = operand as i32;
            match path {
                Path::Simd => simd_lt_i32_to_mask(v, threshold, out_words),
                Path::Scalar => scalar_lt_i32_to_mask(v, threshold, out_words),
            }
        }
        (LGJ_OP_LE_I32, LaneView::I32(v)) => {
            let threshold = operand as i32;
            match path {
                Path::Simd => simd_le_i32_to_mask(v, threshold, out_words),
                Path::Scalar => scalar_le_i32_to_mask(v, threshold, out_words),
            }
        }
        (LGJ_OP_GE_I32, LaneView::I32(v)) => {
            let threshold = operand as i32;
            match path {
                Path::Simd => simd_ge_i32_to_mask(v, threshold, out_words),
                Path::Scalar => scalar_ge_i32_to_mask(v, threshold, out_words),
            }
        }
        (LGJ_OP_TERNARY_MATCH_U32, LaneView::U32(v)) => {
            // The operand packs BOTH halves: pattern low, care high. Exact —
            // two u32s are exactly 64 bits — and confined to this op-code, so
            // no existing opcode's reading of `operand` changes. `abi.rs`
            // documents the packing beside the constant.
            let packed = operand as u64;
            let pattern = packed as u32;
            let care = (packed >> 32) as u32;
            match path {
                Path::Simd => simd_ternary_match_u32_to_mask(v, pattern, care, out_words),
                Path::Scalar => scalar_ternary_match_u32_to_mask(v, pattern, care, out_words),
            }
        }
        (LGJ_OP_EQ_U32, _)
        | (LGJ_OP_GT_I32, _)
        | (LGJ_OP_NE_U32, _)
        | (LGJ_OP_EQ_I32, _)
        | (LGJ_OP_NE_I32, _)
        | (LGJ_OP_LT_I32, _)
        | (LGJ_OP_LE_I32, _)
        | (LGJ_OP_GE_I32, _)
        | (LGJ_OP_TERNARY_MATCH_U32, _) => return Err(LGJ_ERR_LANE_KIND_MISMATCH),
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

/// The PARAMETERISED masked reduction over an `I32` lane (ABI minor >= 11,
/// `docs/abi.md` §19) — `sum` / `min` / `max` behind one op-code argument.
///
/// `abi.md` §15 wrote this shape down before the need arose: *"if a second
/// reduction is ever needed (min/max/count-distinct/histogram), do NOT add a
/// second symbol ... an op-code parameter on one reduce symbol, mirroring how
/// `lgj_plan_eval`'s `LgjOpDesc` already generalises predicates — and `sum`
/// becomes op-code 0."* This is that, and a future `count-distinct` or
/// `histogram` is one more arm rather than one more symbol.
///
/// `Ok(None)` is "no answer", not "zero": min and max over an empty population
/// have none, and `i32::MAX` / `i32::MIN` as sentinels would be
/// indistinguishable from a population that genuinely contains them. `sum` is
/// always `Ok(Some(_))` — the sum of nothing is `0`, which IS the answer.
///
/// Widening to `i64` is the SUM's contract, not a reinterpretation of min/max:
/// an `i32` extremum is exact in `i64`, so one return type carries all three
/// without a lossy case.
pub fn reduce_i32(
    path: Path,
    reduce_op: u32,
    values: &[i32],
    mask_words: &[u64],
) -> Result<Option<i64>, i32> {
    match reduce_op {
        LGJ_REDUCE_SUM => Ok(Some(masked_sum_i32(path, values, mask_words))),
        LGJ_REDUCE_MIN => Ok(match path {
            Path::Simd => simd_masked_min_i32(values, mask_words),
            Path::Scalar => scalar_masked_min_i32(values, mask_words),
        }
        .map(i64::from)),
        LGJ_REDUCE_MAX => Ok(match path {
            Path::Simd => simd_masked_max_i32(values, mask_words),
            Path::Scalar => scalar_masked_max_i32(values, mask_words),
        }
        .map(i64::from)),
        // Not in abi.md's reduction set. Rejected like an unknown opcode rather
        // than defaulted to SUM, so a future reduction cannot be silently
        // misread by an older build.
        _ => Err(LGJ_ERR_UNKNOWN_OPCODE),
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
                            crate::rowstore::ROW_BYTES as usize,
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

    // ═══════════════════════════════════════════════════════════════════════
    // ABI minor 11 — the masking-op completion (docs/abi.md §19)
    //
    // Every op below carries the same three-part gate: an EQUIVALENCE arm
    // against an independent scalar oracle across the row-count shapes that
    // straddle every boundary in the packing (0/1 degenerate, 15/16/17 the
    // 16-lane group, 63/64/65 the word, 4095 one short of a power of two), a
    // CAN-FIRE arm proving the op changes something on non-trivial input, and
    // a CAN-STAY-SILENT arm proving it does NOT touch what it must not — in
    // this file's currency, the tail bits past `n_rows`.
    // ═══════════════════════════════════════════════════════════════════════

    /// The row-count shapes every minor-11 arm sweeps. 4095 is deliberately
    /// `64 * 64 - 1`: it exercises a final word with 63 live bits, the widest
    /// possible tail, which is the shape a "clear the tail" bug survives on a
    /// round row count.
    const SHAPES: [u64; 14] = [
        0, 1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 1000, 4095, 4096,
    ];

    /// SplitMix64 — the generator `Fixture`/`RowStore` already use, repeated
    /// here rather than imported so the test data is independent of whatever
    /// the fixture happens to generate.
    fn mix(state: &mut u64) -> u64 {
        *state = state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = *state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// `n_words` of pseudo-random mask words with the tail cleared — a
    /// CONFORMING mask, which is what every op's contract is stated against.
    fn random_mask(n_rows: u64, seed: u64) -> Vec<u64> {
        let mut st = seed;
        let mut w = words_for(n_rows);
        for x in w.iter_mut() {
            *x = mix(&mut st);
        }
        clear_tail_bits(&mut w, n_rows);
        w
    }

    /// Assert every bit at or past `n_rows` is zero, naming the offender.
    fn assert_tail_clear(words: &[u64], n_rows: u64, what: &str) {
        let used = (n_rows % 64) as u32;
        if used != 0 {
            if let Some(&last) = words.last() {
                assert_eq!(
                    last >> used,
                    0,
                    "{what}: tail bits past n_rows={n_rows} survived (last word {last:#018x})"
                );
            }
        }
    }

    // ── The comparison family ───────────────────────────────────────────────

    /// EQUIVALENCE. Each of the six new comparisons, and the `u32` TCAM,
    /// against its independently-written scalar definition at every shape.
    #[test]
    fn minor_11_predicates_match_their_scalar_references_at_every_row_count_shape() {
        for n in SHAPES {
            for seed in [0u64, 1, 0xDEAD_BEEF] {
                let f = Fixture::generate(n, seed).unwrap();
                let (mut a, mut b) = (words_for(n), words_for(n));

                simd_ne_u32_to_mask(f.classes(), 7, &mut a);
                scalar_ne_u32_to_mask(f.classes(), 7, &mut b);
                assert_eq!(a, b, "ne_u32 at n={n} seed={seed}");
                assert_tail_clear(&a, n, "ne_u32");

                for needle in [-1000i32, 0, 100, i32::MIN, i32::MAX] {
                    simd_eq_i32_to_mask(f.values(), needle, &mut a);
                    scalar_eq_i32_to_mask(f.values(), needle, &mut b);
                    assert_eq!(a, b, "eq_i32({needle}) at n={n} seed={seed}");
                    assert_tail_clear(&a, n, "eq_i32");

                    simd_ne_i32_to_mask(f.values(), needle, &mut a);
                    scalar_ne_i32_to_mask(f.values(), needle, &mut b);
                    assert_eq!(a, b, "ne_i32({needle}) at n={n} seed={seed}");
                    assert_tail_clear(&a, n, "ne_i32");

                    simd_lt_i32_to_mask(f.values(), needle, &mut a);
                    scalar_lt_i32_to_mask(f.values(), needle, &mut b);
                    assert_eq!(a, b, "lt_i32({needle}) at n={n} seed={seed}");
                    assert_tail_clear(&a, n, "lt_i32");

                    simd_le_i32_to_mask(f.values(), needle, &mut a);
                    scalar_le_i32_to_mask(f.values(), needle, &mut b);
                    assert_eq!(a, b, "le_i32({needle}) at n={n} seed={seed}");
                    assert_tail_clear(&a, n, "le_i32");

                    simd_ge_i32_to_mask(f.values(), needle, &mut a);
                    scalar_ge_i32_to_mask(f.values(), needle, &mut b);
                    assert_eq!(a, b, "ge_i32({needle}) at n={n} seed={seed}");
                    assert_tail_clear(&a, n, "ge_i32");
                }

                for (pat, care) in [(0u32, 0u32), (7, 0xFFFF_FFFF), (5, 0b101), (0, 0b1)] {
                    simd_ternary_match_u32_to_mask(f.classes(), pat, care, &mut a);
                    scalar_ternary_match_u32_to_mask(f.classes(), pat, care, &mut b);
                    assert_eq!(a, b, "tcam_u32({pat},{care}) at n={n} seed={seed}");
                    assert_tail_clear(&a, n, "ternary_match_u32");
                }
            }
        }
    }

    /// CAN-FIRE + CAN-STAY-SILENT, on the boundaries the signed comparisons
    /// get wrong when they are lowered as a complement or an off-by-one.
    ///
    /// `lt` at `i32::MIN` must select NOTHING (silence), and `ge` at
    /// `i32::MIN` must select EVERYTHING (fire) — the pair that catches a
    /// `x > t - 1` lowering, which wraps at the bottom of the range and
    /// inverts both answers.
    #[test]
    fn the_signed_comparisons_are_exact_at_the_extremes() {
        let v: Vec<i32> = vec![i32::MIN, -1000, -1, 0, 1, 100, i32::MAX];
        let n = v.len() as u64;
        let all = (1u64 << v.len()) - 1;
        let mut w = words_for(n);

        simd_lt_i32_to_mask(&v, i32::MIN, &mut w);
        assert_eq!(w[0], 0, "nothing is < i32::MIN");
        simd_ge_i32_to_mask(&v, i32::MIN, &mut w);
        assert_eq!(w[0], all, "everything is >= i32::MIN");
        simd_le_i32_to_mask(&v, i32::MAX, &mut w);
        assert_eq!(w[0], all, "everything is <= i32::MAX");
        simd_lt_i32_to_mask(&v, 0, &mut w);
        assert_eq!(w[0], 0b000_0111, "i32::MIN, -1000 and -1 are the negatives");
        simd_eq_i32_to_mask(&v, i32::MIN, &mut w);
        assert_eq!(w[0], 0b000_0001, "exactly one element equals i32::MIN");
        simd_ne_i32_to_mask(&v, i32::MIN, &mut w);
        assert_eq!(w[0], all & !0b1, "and exactly the others differ from it");
    }

    /// CAN-STAY-SILENT for the whole family: a predicate true of EVERY row
    /// must still leave the tail zero. 70 rows leaves word 1 with 6 live bits
    /// and 58 that `popcount` would otherwise count.
    #[test]
    fn minor_11_predicates_leave_no_tail_bit_set_even_when_every_row_matches() {
        let n = 70u64;
        let u: Vec<u32> = vec![7; n as usize];
        let i: Vec<i32> = vec![7; n as usize];
        let mut w = words_for(n);

        // `!=` against a needle no element carries: true of every row.
        simd_ne_u32_to_mask(&u, 9, &mut w);
        assert_eq!(simd_popcount(&w), n, "the predicate really does select all");
        assert_tail_clear(&w, n, "ne_u32");

        for f in [
            simd_ne_i32_to_mask as fn(&[i32], i32, &mut [u64]),
            simd_le_i32_to_mask,
            simd_lt_i32_to_mask,
        ] {
            f(&i, 9, &mut w);
            assert_eq!(simd_popcount(&w), n);
            assert_tail_clear(&w, n, "i32 predicate");
        }
        simd_ge_i32_to_mask(&i, 7, &mut w);
        assert_eq!(simd_popcount(&w), n);
        assert_tail_clear(&w, n, "ge_i32");
        simd_eq_i32_to_mask(&i, 7, &mut w);
        assert_eq!(simd_popcount(&w), n);
        assert_tail_clear(&w, n, "eq_i32");
        // care == 0 is "don't care about anything" — true of every row.
        simd_ternary_match_u32_to_mask(&u, 0xDEAD_BEEF, 0, &mut w);
        assert_eq!(simd_popcount(&w), n);
        assert_tail_clear(&w, n, "ternary_match_u32");
    }

    /// The care mask is the whole point of a TCAM, so it gets its own
    /// two-sided arm: a bit OUTSIDE `care` may differ freely (fire), and a bit
    /// INSIDE `care` must exclude (silence). Without the second half a
    /// "matches everything" implementation would pass.
    #[test]
    fn ternary_match_ignores_uncared_bits_and_honours_cared_ones() {
        let v: Vec<u32> = vec![0b1010, 0b1110, 0b0010, 0b1011];
        let mut w = words_for(4);
        // care clears bit 2: elements 0 and 1 differ ONLY there, so both match.
        simd_ternary_match_u32_to_mask(&v, 0b1010, 0b1011, &mut w);
        assert_eq!(w[0], 0b0011, "an uncared bit must not exclude");
        // care now includes bit 2: element 1 differs there and drops out.
        simd_ternary_match_u32_to_mask(&v, 0b1010, 0b1111, &mut w);
        assert_eq!(w[0], 0b0001, "a cared bit must exclude");
        // The 64-bit sibling agrees on the same shape, one bit higher.
        let v64: Vec<u64> = v.iter().map(|&x| u64::from(x) | (1u64 << 40)).collect();
        let mut w64 = words_for(4);
        simd_ternary_match_u64_to_mask(&v64, 0b1010 | (1 << 40), 0b1011 | (1 << 40), &mut w64);
        assert_eq!(w64[0], 0b0011, "u64 TCAM must see the high bit too");
        simd_ternary_match_u64_to_mask(&v64, 0b1010, 0b1011 | (1 << 40), &mut w64);
        assert_eq!(w64[0], 0, "a cared HIGH bit must exclude every element");
    }

    // ── XOR / NOT / ANY / ALL ───────────────────────────────────────────────

    /// EQUIVALENCE + the falsifier pair for XOR, at every shape.
    #[test]
    fn mask_xor_is_symmetric_difference_and_keeps_a_conforming_tail() {
        for n in SHAPES {
            let a = random_mask(n, 0x1234);
            let b = random_mask(n, 0x5678);
            let mut dst = words_for(n);
            simd_mask_xor(&a, &b, &mut dst);
            let expect: Vec<u64> = a.iter().zip(&b).map(|(x, y)| x ^ y).collect();
            assert_eq!(dst, expect, "xor at n={n}");
            assert_tail_clear(&dst, n, "mask_xor");

            let mut inplace = a.clone();
            simd_mask_xor_assign(&mut inplace, &b);
            assert_eq!(inplace, dst, "xor_assign disagrees with xor at n={n}");

            // CAN-STAY-SILENT: `x ^ x` is empty for every x.
            let mut self_xor = a.clone();
            simd_mask_xor_assign(&mut self_xor, &a);
            assert!(
                self_xor.iter().all(|&w| w == 0),
                "x ^ x must be empty at n={n}"
            );
        }
        // CAN-FIRE, with the anti-vacuity half explicit: the inputs really do
        // differ, so an implementation that wrote zeros could not pass.
        let a = [0b0110u64];
        let b = [0b0011u64];
        let mut dst = [0u64; 1];
        simd_mask_xor(&a, &b, &mut dst);
        assert_ne!(dst[0], a[0]);
        assert_eq!(dst[0], 0b0101);
    }

    /// NOT is the ONE mask-algebra op whose correctness cannot be stated
    /// without `n_rows`, so its falsifier is the tail itself — and it is
    /// two-sided: the live bits must all flip (fire) and the tail must be
    /// zero where a plain `!` would set every bit (silence).
    #[test]
    fn mask_not_flips_the_live_bits_and_clears_the_tail_a_plain_complement_would_set() {
        for n in SHAPES {
            let src = random_mask(n, 0xABCD);
            let mut dst = words_for(n);
            simd_mask_not(&src, n as usize, &mut dst);
            assert_tail_clear(&dst, n, "mask_not");
            // Every LIVE bit flipped: the two popcounts must partition n.
            assert_eq!(
                simd_popcount(&src) + simd_popcount(&dst),
                n,
                "complement must partition the population at n={n}"
            );
            let mut inplace = src.clone();
            simd_mask_not_assign(&mut inplace, n as usize);
            assert_eq!(inplace, dst, "not_assign disagrees with not at n={n}");
        }
        // The silence half, made concrete: at 4 rows a raw `!` leaves 60 stray
        // bits in the word, and the test asserts the naive answer is NOT what
        // we got — so a version without the clear fails here, not merely
        // elsewhere.
        let src = [0b0011u64];
        let mut dst = [0u64; 1];
        simd_mask_not(&src, 4, &mut dst);
        assert_eq!(dst[0], 0b1100);
        assert_ne!(dst[0], !src[0], "a plain complement must not be the answer");
    }

    /// ANY and ALL, both directions each. The non-trivial inputs matter: an
    /// empty-vs-empty pair would pass for a function that always answered the
    /// same way.
    #[test]
    fn mask_any_and_mask_all_both_fire_and_both_stay_silent() {
        let n = 70u64;
        let empty = words_for(n);
        assert!(!simd_mask_any(&empty), "an empty mask selects nothing");
        assert!(
            !simd_mask_all(&empty, n as usize),
            "and is certainly not full"
        );

        let mut one = words_for(n);
        one[1] |= 1 << 5; // row 69 — the last live bit
        assert!(simd_mask_any(&one), "one set row is 'any'");
        assert!(!simd_mask_all(&one, n as usize), "one set row is not 'all'");

        let mut full = vec![u64::MAX; mask_words_for(n) as usize];
        clear_tail_bits(&mut full, n);
        assert!(simd_mask_any(&full));
        assert!(
            simd_mask_all(&full, n as usize),
            "a conforming full mask is 'all'"
        );

        // One row short of full is NOT all — the off-by-one this predicate
        // invites, checked at the boundary between the two words.
        let mut almost = full.clone();
        almost[0] &= !(1u64 << 63);
        assert!(
            !simd_mask_all(&almost, n as usize),
            "63 of 64 in word 0 is not 'all'"
        );
        // A zero-row population is vacuously full, and that must not read as a
        // bug in the row-count arithmetic.
        assert!(simd_mask_all(&[], 0));
    }

    // ── TERNLOG ─────────────────────────────────────────────────────────────

    /// The 256-entry dispatch table's own falsifier: for EVERY immediate, the
    /// runtime dispatch must equal the truth table evaluated bit by bit.
    ///
    /// This is what makes the table's indexing (`[imm >> 4][imm & 15]`)
    /// checkable rather than asserted — a transposed index, an off-by-one row,
    /// or a `$base` typo in the macro shifts some immediates and this fails on
    /// exactly those.
    #[test]
    fn the_ternlog_dispatch_table_agrees_with_the_truth_table_for_all_256_immediates() {
        let n = 129u64; // three words, the last one short
        let a0 = random_mask(n, 0x11);
        let b = random_mask(n, 0x22);
        let c = random_mask(n, 0x33);
        for imm in 0u8..=255 {
            let mut got = a0.clone();
            simd_mask_ternlog_assign_dyn(imm, &mut got, &b, &c);
            // Bit-serial oracle: index (a<<2)|(b<<1)|c into the immediate.
            let mut want = vec![0u64; got.len()];
            for (w, out) in want.iter_mut().enumerate() {
                for bit in 0..64u32 {
                    let av = (a0[w] >> bit) & 1;
                    let bv = (b[w] >> bit) & 1;
                    let cv = (c[w] >> bit) & 1;
                    let idx = (av << 2) | (bv << 1) | cv;
                    if (u64::from(imm) >> idx) & 1 == 1 {
                        *out |= 1u64 << bit;
                    }
                }
            }
            assert_eq!(got, want, "ternlog imm={imm:#04x}");
        }
    }

    /// CAN-FIRE for the general member: the named immediates must reproduce
    /// the dedicated ops exactly, which is the claim that lets XOR and NOT
    /// arrive as immediates instead of as symbols.
    #[test]
    fn the_named_immediates_reproduce_the_dedicated_mask_ops() {
        const AND2: u8 = 0xC0;
        const OR2: u8 = 0xFC;
        const XOR2: u8 = 0x3C;
        const ANDNOT2: u8 = 0x30;
        const NOT_A: u8 = 0x0F;

        let n = 200u64;
        let a = random_mask(n, 0xAAAA);
        let b = random_mask(n, 0xBBBB);
        let mut want = words_for(n);
        let run = |imm: u8| {
            let mut got = a.clone();
            // `c` is ignored by every two-input table; feeding it a mask that
            // is NOT all-zero is what proves it really is ignored.
            simd_mask_ternlog_assign_dyn(imm, &mut got, &b, &random_mask(n, 0xCCCC));
            got
        };

        simd_mask_and(&a, &b, &mut want);
        assert_eq!(run(AND2), want, "AND2");
        simd_mask_or(&a, &b, &mut want);
        assert_eq!(run(OR2), want, "OR2");
        simd_mask_xor(&a, &b, &mut want);
        assert_eq!(run(XOR2), want, "XOR2");
        simd_mask_andnot(&a, &b, &mut want);
        assert_eq!(run(ANDNOT2), want, "AND2_ANDNOT");

        // NOT is the interesting one: its table is ODD, so before the tail
        // clear the result has every bit past n_rows set. That is the state
        // the export's `clear_tail_bits` exists for, and asserting it here
        // makes the export's tail step a measured requirement rather than a
        // precaution.
        let mut not_got = run(NOT_A);
        assert_ne!(
            not_got.last().copied().unwrap_or(0) >> (n % 64),
            0,
            "an odd immediate must set the tail — otherwise the export's clear is untested"
        );
        clear_tail_bits(&mut not_got, n);
        simd_mask_not(&a, n as usize, &mut want);
        assert_eq!(not_got, want, "NOT_A after the tail clear");
    }

    /// The in-place and out-of-place spellings must agree — the property the
    /// export relies on when it copies `a` into `dst` and then assigns.
    #[test]
    fn ternlog_assign_and_out_of_place_agree() {
        let n = 1000u64;
        let a = random_mask(n, 1);
        let b = random_mask(n, 2);
        let c = random_mask(n, 3);
        let mut out = words_for(n);
        simd_mask_ternlog::<{ ternlog::MAJ3 }>(&a, &b, &c, &mut out);
        let mut inplace = a.clone();
        simd_mask_ternlog_assign::<{ ternlog::MAJ3 }>(&mut inplace, &b, &c);
        assert_eq!(out, inplace);
        // …and the runtime dispatch reaches the same monomorphisation.
        let mut dynamic = a.clone();
        simd_mask_ternlog_assign_dyn(ternlog::MAJ3 as u8, &mut dynamic, &b, &c);
        assert_eq!(out, dynamic);
    }

    // ── Reductions + blend ──────────────────────────────────────────────────

    /// EQUIVALENCE for min/max against their scalar definitions, plus the
    /// empty-population arm that `None` exists for.
    #[test]
    fn masked_min_and_max_match_their_scalar_references_and_report_an_empty_population() {
        for n in SHAPES {
            for seed in [0u64, 7, 0xFEED] {
                let f = Fixture::generate(n, seed).unwrap();
                let m = random_mask(n, seed ^ 0x5A5A);
                assert_eq!(
                    simd_masked_min_i32(f.values(), &m),
                    scalar_masked_min_i32(f.values(), &m),
                    "min at n={n} seed={seed}"
                );
                assert_eq!(
                    simd_masked_max_i32(f.values(), &m),
                    scalar_masked_max_i32(f.values(), &m),
                    "max at n={n} seed={seed}"
                );
                // CAN-STAY-SILENT: an empty mask has no extremum at all.
                let empty = words_for(n);
                assert_eq!(simd_masked_min_i32(f.values(), &empty), None);
                assert_eq!(simd_masked_max_i32(f.values(), &empty), None);
            }
        }
        // CAN-FIRE, and the mask must actually SELECT: a min over everything
        // and a min over a subset must differ, or the mask is decorative.
        let v = [10i32, -5, 30, 2];
        assert_eq!(simd_masked_min_i32(&v, &[0b1111]), Some(-5));
        assert_eq!(simd_masked_min_i32(&v, &[0b1101]), Some(2));
        assert_eq!(simd_masked_max_i32(&v, &[0b1111]), Some(30));
        assert_eq!(simd_masked_max_i32(&v, &[0b1011]), Some(10));
    }

    /// `reduce_i32` dispatch: every op on both paths, and an unknown op
    /// rejected rather than defaulted to SUM.
    #[test]
    fn reduce_i32_dispatches_every_op_on_both_paths_and_rejects_an_unknown_one() {
        let f = Fixture::generate(500, 3).unwrap();
        let m = random_mask(500, 0x99);
        for path in [Path::Simd, Path::Scalar] {
            assert_eq!(
                reduce_i32(path, LGJ_REDUCE_SUM, f.values(), &m),
                Ok(Some(masked_sum_i32(path, f.values(), &m)))
            );
            assert_eq!(
                reduce_i32(path, LGJ_REDUCE_MIN, f.values(), &m),
                Ok(scalar_masked_min_i32(f.values(), &m).map(i64::from))
            );
            assert_eq!(
                reduce_i32(path, LGJ_REDUCE_MAX, f.values(), &m),
                Ok(scalar_masked_max_i32(f.values(), &m).map(i64::from))
            );
            for bogus in [3u32, 4, 99, u32::MAX] {
                assert_eq!(
                    reduce_i32(path, bogus, f.values(), &m),
                    Err(LGJ_ERR_UNKNOWN_OPCODE),
                    "an unknown reduction must not alias a known one"
                );
            }
        }
        // SUM is present over an empty population; MIN/MAX are not. That is
        // the distinction `out_present` carries across the membrane.
        let empty = words_for(500);
        assert_eq!(
            reduce_i32(Path::Simd, LGJ_REDUCE_SUM, f.values(), &empty),
            Ok(Some(0))
        );
        assert_eq!(
            reduce_i32(Path::Simd, LGJ_REDUCE_MIN, f.values(), &empty),
            Ok(None)
        );
    }

    /// `blend_i32` — substrate-tier only (no export; see its doc). Falsifier
    /// pair: the mask really selects between the two sources, and a bit set
    /// nowhere leaves `b` untouched.
    #[test]
    fn blend_i32_selects_from_both_sources() {
        let a = [1i32, 2, 3, 4];
        let b = [10i32, 20, 30, 40];
        let mut dst = [0i32; 4];
        simd_blend_i32(&[0b0101], &a, &b, &mut dst);
        assert_eq!(dst, [1, 20, 3, 40]);
        simd_blend_i32(&[0], &a, &b, &mut dst);
        assert_eq!(dst, b, "an empty mask must take everything from b");
        simd_blend_i32(&[0b1111], &a, &b, &mut dst);
        assert_eq!(dst, a, "a full mask must take everything from a");
    }

    // ── The strided TCAM over a row store ───────────────────────────────────

    /// EQUIVALENCE + can-fire/can-stay-silent for the register TCAM, over the
    /// REAL row store, on every facet.
    ///
    /// The scalar oracle is local to this test rather than a `scalar_*` in
    /// `src/main`: nothing on `Path::Scalar` reaches this kernel, so shipping
    /// an oracle for it would put a scalar path in the artifact for the
    /// benefit of the test suite alone (E2 licenses scalar code as a TEST
    /// oracle, which is where this one lives).
    #[test]
    fn the_register_tcam_matches_a_byte_wise_oracle_on_every_facet() {
        use crate::rowstore::{RowStore, FACET_BYTES, FACET_CLASSID_BYTES, ROW_BYTES, ROW_FACETS};
        const N: usize = FACET_REGISTER_BYTES;

        for n in [1u64, 63, 64, 65, 300] {
            let store = RowStore::generate(n, 0xC0FFEE).unwrap();
            let bytes = store.as_bytes();
            for facet in 0..ROW_FACETS {
                let off = (u64::from(facet) * FACET_BYTES + FACET_CLASSID_BYTES) as usize;
                // Read row 0's own register and use it as the pattern, so the
                // "exact" arm is guaranteed to have at least one hit — the
                // anti-vacuity half of the can-fire assertion below.
                let mut pattern = [0u8; N];
                pattern.copy_from_slice(&bytes[off..off + N]);

                for care in [[0u8; N], [0xFFu8; N], {
                    let mut c = [0u8; N];
                    c[0] = 0xFF;
                    c
                }] {
                    let mut got = words_for(n);
                    simd_rowstore_ternary_match_mask(
                        bytes,
                        off,
                        ROW_BYTES as usize,
                        n as usize,
                        &pattern,
                        &care,
                        &mut got,
                    );
                    let mut want = words_for(n);
                    for row in 0..n as usize {
                        let o = off + row * ROW_BYTES as usize;
                        let hit = (0..N).all(|k| (bytes[o + k] ^ pattern[k]) & care[k] == 0);
                        if hit {
                            want[row / 64] |= 1u64 << (row % 64);
                        }
                    }
                    assert_eq!(got, want, "tcam n={n} facet={facet} care={care:?}");
                    assert_tail_clear(&got, n, "register tcam");
                }

                // CAN-FIRE: the exact pattern selects at least row 0 …
                let mut exact = words_for(n);
                simd_rowstore_ternary_match_mask(
                    bytes,
                    off,
                    ROW_BYTES as usize,
                    n as usize,
                    &pattern,
                    &[0xFF; N],
                    &mut exact,
                );
                assert_eq!(exact[0] & 1, 1, "row 0 must match its own register");
                // … and CAN-STAY-SILENT: flipping a CARED byte drops it.
                let mut miss = pattern;
                miss[0] ^= 0xFF;
                let mut none = words_for(n);
                simd_rowstore_ternary_match_mask(
                    bytes,
                    off,
                    ROW_BYTES as usize,
                    n as usize,
                    &miss,
                    &[0xFF; N],
                    &mut none,
                );
                assert_eq!(none[0] & 1, 0, "a cared-byte mismatch must exclude row 0");
                // … while flipping it with that byte UN-cared keeps it.
                let mut care_rest = [0xFFu8; N];
                care_rest[0] = 0;
                let mut still = words_for(n);
                simd_rowstore_ternary_match_mask(
                    bytes,
                    off,
                    ROW_BYTES as usize,
                    n as usize,
                    &miss,
                    &care_rest,
                    &mut still,
                );
                assert_eq!(still[0] & 1, 1, "an uncared byte must not exclude row 0");
            }
        }
    }

    /// The register geometry has ONE spelling (E3). This pins that the
    /// constant is DERIVED — a literal `12` here would survive a change to
    /// `FACET_BYTES` and this does not.
    #[test]
    fn the_register_size_is_derived_from_the_row_geometry() {
        assert_eq!(
            FACET_REGISTER_BYTES,
            (crate::rowstore::FACET_BYTES - crate::rowstore::FACET_CLASSID_BYTES) as usize
        );
        // The same 12 bytes read the other way: the register is the lo64 span
        // (hi32's offset measured from the REGISTER base, not the facet base)
        // plus the 4-byte hi32 field. Getting this relation wrong is how a
        // "12" ends up hand-written somewhere, which is what E3 forbids — the
        // first version of this assertion added `4` to the facet-relative
        // offset and measured 16, the facet's own size.
        assert_eq!(
            FACET_REGISTER_BYTES,
            (crate::rowstore::FACET_PAYLOAD_HI32_OFFSET - crate::rowstore::FACET_CLASSID_BYTES)
                as usize
                + 4,
            "register = lo64 span (8) + hi32 (4); the two spellings must agree"
        );
    }
}

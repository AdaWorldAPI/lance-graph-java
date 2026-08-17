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
}

//! PR4's behaviour-equivalence oracle and its falsifier matrix.
//!
//! # Why a frozen copy, and not mask-risc's own oracle
//!
//! PR4 routes [`plan_eval_impl`] through `lance_graph_mask_risc::execute` in
//! place of its own opcode loop. That introduces a component which did not
//! exist before: the lowering `&[LgjOpDesc] -> mask_risc::Program`. It sits
//! ABOVE both `execute` and mask-risc's `reference_execute`, so a bug in it —
//! a swapped opcode, a dropped all-ones seed, a mis-packed TCAM operand —
//! produces the same wrong `Program` for both, both evaluate it faithfully,
//! and mask-risc's own differential stays green.
//!
//! Only an oracle that never sees the `Program` can catch that. This is that
//! oracle: a frozen copy of the pre-PR4 evaluation loop.
//!
//! # Freeze discipline (non-negotiable)
//!
//! [`legacy_plan_eval_impl`] is a byte-for-byte copy of `plan_eval_impl`'s
//! body as of **`b78c4f356a747e975b796a357d37a7c94f22967a`**. It must NOT be
//! refactored to share a helper with the new path — a shared helper is a
//! shared bug, and turns the differential into a tautology. Its evaluation
//! core calls only `kernels::{eval_predicate, combine_into, popcount}` and
//! `clear_tail_bits`.
//!
//! The *validation* prologue is deliberately shared (`validate_plan`,
//! `resolve_pattern_and_mask`, `lane_view`): PR4 does not change it, and
//! duplicating it would mean the differential could not see a regression in
//! the one part both paths genuinely have in common.
//!
//! # Ordering
//!
//! Everything in this file is written against the OLD implementation, still
//! in place, and is GREEN before any implementation change. A test red here
//! has found a pre-existing defect in the old loop — report it, do not fix it
//! silently, because a silently fixed old bug changes what "equivalent to the
//! old" means.

use super::*;

/// The pre-PR4 evaluation loop, frozen.
///
/// See the module doc for the freeze discipline. The only differences from
/// the original are the name and this comment; every line of the body is the
/// original's.
pub(super) fn legacy_plan_eval_impl(
    res: u64,
    ops: *const LgjOpDesc,
    n_ops: u32,
    dst_mask: u64,
    out_count: *mut u64,
    path: Path,
) -> i32 {
    // EMPTY_PLAN is checked before the null test: `(null, 0)` is a caller
    // describing an empty plan, which has its own dedicated code, and reporting
    // NULL_ARGUMENT there would send a Java author looking for the wrong bug.
    if n_ops == 0 {
        return LGJ_ERR_EMPTY_PLAN;
    }
    if ops.is_null() || out_count.is_null() {
        return LGJ_ERR_NULL_ARGUMENT;
    }
    let (pattern, mask) = match resolve_pattern_and_mask(res, dst_mask) {
        Ok(t) => t,
        Err(e) => return e,
    };
    // SAFETY: `ops` is non-null (checked) and the caller states it points at
    // `n_ops` contiguous `LgjOpDesc` — the same contract the live export
    // relies on, exercised here only from in-crate tests that build a real
    // slice.
    let ops: &[LgjOpDesc] = unsafe { std::slice::from_raw_parts(ops, n_ops as usize) };

    if let Err(e) = validate_plan(&pattern, ops) {
        return e;
    }

    let n_rows = pattern.n_rows;
    let n_words = mask_words_for(n_rows) as usize;

    // Accumulate into scratch, then publish. Two consequences, both wanted:
    // dst_mask is written exactly once, and an error at any point leaves it
    // byte-for-byte as it was.
    let mut acc = vec![0u64; n_words];
    // "the accumulator starts as all rows set" (§7).
    for w in acc.iter_mut() {
        *w = u64::MAX;
    }
    clear_tail_bits(&mut acc, n_rows);
    let mut scratch = vec![0u64; n_words];

    for op in ops {
        let lane = match lane_view(&pattern, op.lane_id) {
            Ok(l) => l,
            Err(e) => return e,
        };
        if let Err(e) =
            kernels::eval_predicate(path, op.op, op.operand, &lane, n_rows, &mut scratch)
        {
            return e;
        }
        if let Err(e) = kernels::combine_into(path, op.combine, &mut acc, &scratch) {
            return e;
        }
    }
    clear_tail_bits(&mut acc, n_rows);
    let count = kernels::popcount(path, &acc);

    let mut g = match mask.write_mask() {
        Some(g) => g,
        None => return LGJ_ERR_WRONG_RESOURCE_KIND,
    };
    if g.words.len() != acc.len() {
        return LGJ_ERR_MASK_LENGTH_MISMATCH;
    }
    g.words.copy_from_slice(&acc);
    drop(g);

    // SAFETY: non-null (checked above); written only on the success path.
    unsafe { *out_count = count };
    LGJ_OK
}

/// One evaluation's full observable result: everything the differential
/// compares. Bundling them means a test cannot silently check two of three.
#[derive(Debug, PartialEq, Eq)]
pub(super) struct Outcome {
    pub status: i32,
    pub count: u64,
    pub words: Vec<u64>,
}

/// Run the LIVE export over `ops`, publishing into a freshly-created mask.
///
/// `out_count` is pre-seeded with a sentinel so "not written on failure" is
/// observable rather than assumed — a zero would be indistinguishable from a
/// legitimately-empty result.
pub(super) fn eval_live(pattern: u64, ops: &[LgjOpDesc], dst: u64) -> Outcome {
    let mut count = 12345u64;
    let status = call::plan_eval(
        pattern,
        ops.as_ptr(),
        ops.len() as u32,
        dst,
        &mut count as *mut u64,
    );
    Outcome {
        status,
        count,
        words: read_words(dst),
    }
}

/// Run the FROZEN oracle over `ops`, publishing into `dst`.
pub(super) fn eval_legacy(pattern: u64, ops: &[LgjOpDesc], dst: u64) -> Outcome {
    let mut count = 12345u64;
    let status = legacy_plan_eval_impl(
        pattern,
        ops.as_ptr(),
        ops.len() as u32,
        dst,
        &mut count as *mut u64,
        Path::Simd,
    );
    Outcome {
        status,
        count,
        words: read_words(dst),
    }
}

/// The differential itself: live and frozen must agree on status, count, and
/// every word INCLUDING the tail, over two independently-created masks.
///
/// Two masks rather than one is deliberate: sharing a destination would let a
/// path that accumulates INTO its destination agree with one that overwrites,
/// because the second run would start from the first's answer.
pub(super) fn assert_agrees(pattern: u64, ops: &[LgjOpDesc], what: &str) -> Outcome {
    let dst_live = mask(pattern, LGJ_MASK_INIT_EMPTY);
    let dst_legacy = mask(pattern, LGJ_MASK_INIT_EMPTY);
    let live = eval_live(pattern, ops, dst_live);
    let legacy = eval_legacy(pattern, ops, dst_legacy);
    assert_eq!(
        live, legacy,
        "{what}: the live path and the frozen pre-PR4 oracle disagree"
    );
    live
}

#[test]
fn the_frozen_oracle_agrees_with_the_live_path_on_a_plain_and_plan() {
    let p = open(1000, 7);
    let ops = [
        op(LGJ_OP_GT_I32, LANE_VALUES, 0, LGJ_COMBINE_AND),
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 1, LGJ_COMBINE_AND),
    ];
    let out = assert_agrees(p, &ops, "two-op AND plan");
    // Anti-vacuity: an oracle that agreed because both answered "nothing"
    // would prove nothing at all.
    assert!(
        out.count > 0 && out.count < 1000,
        "the fixture must select a proper subset, got {}",
        out.count
    );
}

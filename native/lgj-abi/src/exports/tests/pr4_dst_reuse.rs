//! F-PR4-DST and F-PR4-REUSE: destination-state and scratch-reuse
//! falsifiers for PR4's fused-plan evaluator.
//!
//! `pr4_equivalence.rs` and `pr4_seed.rs` both run every plan into a FRESH,
//! empty destination created moments before the call — so neither file, nor
//! anything in the pre-existing suite, has ever asked what happens when
//! `dst_mask` already holds something, or what happens to a scratch buffer
//! that outlives one call. Two properties follow directly from `abi.md`'s
//! own contract ("dst_mask is written exactly once... an error at any point
//! leaves it byte-for-byte as it was") that no existing test can see fail:
//!
//! - **F-PR4-DST** — the published result must not depend on `dst_mask`'s
//!   PRIOR content, whether that prior content is a fresh all-ones mask, a
//!   genuinely different population left there by an earlier call, or raw
//!   poison written straight through the described segment. A dirty prior
//!   tail must never be read as an input plane. And a rejected plan must
//!   leave that prior content untouched at EVERY position an invalid op
//!   could occupy, not only the one position the pre-existing error-path
//!   test happens to use.
//! - **F-PR4-REUSE** — PR4's whole allocation win comes from reusing a
//!   scratch buffer across calls instead of allocating one per call. A
//!   scratch buffer left dense (or sparse) by one call must not leak into
//!   the next, unrelated call's answer, in either order, and repeating the
//!   identical call must be idempotent.
//!
//! # Freeze discipline (same rule as `pr4_equivalence.rs`)
//!
//! Every test below is written against the OLD implementation, still in
//! place, and must be GREEN before any implementation change. A red result
//! here has found either a pre-existing defect in the old loop or a bug in
//! this test — either way, report it rather than silently reconciling it,
//! because a silent fix on either side changes what "equivalent to the old"
//! means.

use super::pr4_equivalence::{assert_agrees, eval_legacy, eval_live, Outcome};
use super::*;

/// Overwrite mask `m`'s own words with `fill`, through the exact segment
/// [`read_words`] reads back — the write-side mirror of that helper, needed
/// only in this file because F-PR4-DST specifically needs a destination
/// that already held *something* before the call, and there is no ABI call
/// that seeds a fresh mask with arbitrary content directly.
fn poison_words(m: u64, fill: u64) {
    let mut d = LgjLaneDesc::default();
    assert_eq!(call::mask_describe(m, &mut d), LGJ_OK);
    assert_eq!(d.elem_kind, LgjElemKind::MaskWord as u32);
    assert_ne!(d.flags & LGJ_FLAG_WRITABLE, 0);
    // SAFETY: the descriptor is exactly the contract Java relies on to build
    // a writable `MemorySegment` over this lane (confirmed writable by the
    // flag check above); the lane is alive because `m` has not been closed
    // in this scope; and this call is the sole writer of the slice for the
    // duration of the write below, so there is no aliasing with any other
    // live reference to it.
    unsafe {
        let words = std::slice::from_raw_parts_mut(d.addr as *mut u64, d.len_elems as usize);
        words.fill(fill);
    }
}

/// Create a fresh mask over `pattern` and poison every one of its words to
/// `fill`, returning the handle. Several tests below need a destination
/// that already holds deliberate garbage rather than nothing; this pairs
/// [`mask`] with [`poison_words`] at every call site that needs one.
fn poisoned_mask(pattern: u64, fill: u64) -> u64 {
    let m = mask(pattern, LGJ_MASK_INIT_EMPTY);
    poison_words(m, fill);
    m
}

/// True iff no bit at row index `>= n_rows` survives in the last word.
///
/// Copied from `pr4_seed.rs` rather than shared as a common helper (that
/// file's own doc comment explains why it is written as its own
/// independent formula rather than delegating to `clear_tail_bits`, and the
/// same reasoning applies here: this must be able to catch a bug at the
/// call site that matters, not merely re-confirm what a shared helper
/// always computes).
///
/// `n_rows % 64 == 0` needs its own branch: when the last word is exactly
/// full there is no tail region at all, and shifting by zero would
/// (wrongly) demand the whole word be zero.
fn tail_is_clean(words: &[u64], n_rows: u64) -> bool {
    let rem = n_rows % 64;
    if rem == 0 {
        return true;
    }
    match words.last() {
        Some(&last) => last >> rem == 0,
        None => true,
    }
}

/// FAILS IF: `dst_mask`'s prior content changes the published result. Every
/// arm below runs the IDENTICAL plan into a destination that started in a
/// different state — fresh-empty, fresh-saturated, holding a genuinely
/// different prior population, or holding raw poison — and all four must
/// publish byte-for-byte identically. This is what catches an
/// implementation that ACCUMULATES into its destination (reading
/// `dst_mask`'s own words as part of the computation) rather than
/// overwriting it outright: three of the four priors below are non-empty,
/// and one has every bit set, so an accumulate bug has ample non-trivial
/// state to leak through.
#[test]
fn the_same_plan_lands_identically_whatever_the_destination_held() {
    let n = 1000u64;
    let p = open(n, 33);
    let plan = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
    ];

    let fresh_empty = mask(p, LGJ_MASK_INIT_EMPTY);
    let fresh_all = mask(p, LGJ_MASK_INIT_ALL);

    // A genuinely different, non-trivial prior: `class == 7 AND value <=
    // 100` is disjoint from `class == 7 AND value > 100` (the real plan
    // below) BY CONSTRUCTION — `LE_I32` and `GT_I32` at the same threshold
    // partition every value exactly — so this prior can never coincide
    // with the real plan's own answer, whatever the fixture's seed happens
    // to draw.
    let re_used = mask(p, LGJ_MASK_INIT_EMPTY);
    let prior = eval_live(
        p,
        &[
            op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
            op(LGJ_OP_LE_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
        ],
        re_used,
    );
    assert!(
        prior.count > 0 && prior.count < n,
        "the re-used destination's prior population must itself be \
         non-trivial, or this arm cannot tell an accumulate bug apart from \
         a genuine overwrite (got {})",
        prior.count
    );

    let poisoned = poisoned_mask(p, u64::MAX);

    let out_empty = eval_live(p, &plan, fresh_empty);
    let out_all = eval_live(p, &plan, fresh_all);
    let out_reused = eval_live(p, &plan, re_used);
    let out_poisoned = eval_live(p, &plan, poisoned);

    assert_eq!(out_empty.status, LGJ_OK);
    assert!(
        out_empty.count > 0 && out_empty.count < n,
        "the plan under test must itself select a proper subset, or none of \
         these four arms can be told apart (got {})",
        out_empty.count
    );

    assert_eq!(
        out_empty, out_all,
        "a fresh-empty and a fresh-saturated destination must publish \
         identically"
    );
    assert_eq!(
        out_empty, out_reused,
        "a fresh-empty destination and one already holding a disjoint prior \
         population must publish identically"
    );
    assert_eq!(
        out_empty, out_poisoned,
        "a fresh-empty destination and one poisoned to all-ones (including \
         a dirty tail) must publish identically"
    );

    assert_eq!(lgj_close(p), LGJ_OK);
}

/// FAILS IF: the FINAL `clear_tail_bits` before publish is skipped, is
/// applied to the wrong buffer, or only clears a freshly-zeroed word rather
/// than genuinely masking off whatever bits were already there. Every `n`
/// below except `128` has a real tail region (bits at index `>= n % 64` in
/// the last word); the destination is poisoned to `u64::MAX` — dirtying
/// EXACTLY that region — before the call, so survival of even one of those
/// bits is directly attributable to the publish step, not to some other
/// source of garbage.
#[test]
fn a_poisoned_tail_is_published_clean() {
    for n in [1u64, 63, 65, 127, 128, 999, 4097] {
        let p = open(n, 0xDA57_0000 | n);
        let m = poisoned_mask(p, u64::MAX);

        let rem = n % 64;
        if rem != 0 {
            // Anti-vacuity: prove the poison actually reached the tail
            // region BEFORE the call runs, so a `poison_words` that
            // silently no-oped (or wrote the wrong lane) could not make
            // this test pass by leaving nothing to clean up. Skipped only
            // at `rem == 0` (n = 128 here): a whole final word has no tail
            // bits at all, so there is nothing above `n_rows` to dirty.
            let before = read_words(m);
            assert!(
                before.last().copied().unwrap_or(0) >> rem != 0,
                "n={n}: poison_words did not actually dirty the tail region \
                 above row {n} (last word = {:#x})",
                before.last().copied().unwrap_or(0)
            );
        }

        let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)];
        let out = eval_live(p, &ops, m);
        assert_eq!(
            out.status, LGJ_OK,
            "n={n}: a well-formed single-op plan must validate"
        );
        assert!(
            tail_is_clean(&out.words, n),
            "n={n}: a poisoned prior tail survived publish (last word = \
             {:#x})",
            out.words.last().copied().unwrap_or(0)
        );

        assert_eq!(lgj_close(p), LGJ_OK);
    }
}

/// FAILS IF: a future lowering hands `dst_mask` itself to `mask_risc`'s
/// `Planes::masks` — the input-plane list. `validate` refuses ANY plane
/// whose tail is dirty (`ExecError::PlaneTail`), and `Planes::masks` must
/// be `&[]` for `plan_eval`. If `dst_mask` were ever fed back in as an
/// input plane, a poisoned prior tail would turn what should be an
/// ordinary, successful overwrite into a spurious validation error. This
/// poisons the WHOLE destination (interior and tail alike) and asserts the
/// call still succeeds, which distinguishes "this mask is merely where the
/// answer will be written" from "this mask is also read as input" — the
/// exact seam a future lowering could get wrong.
#[test]
fn a_dirty_destination_is_not_read_as_an_input_plane() {
    let n = 777u64; // not a multiple of 64: a real tail region exists to dirty
    let p = open(n, 0xD1A7_D57A);
    let ops = [
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_OR),
    ];

    let dirty = poisoned_mask(p, u64::MAX);
    let out_dirty = eval_live(p, &ops, dirty);
    assert_eq!(
        out_dirty.status, LGJ_OK,
        "a destination whose tail was poisoned before the call must still \
         evaluate successfully — its dirty tail describes only what will be \
         overwritten, and must never be validated as if it were an input \
         plane"
    );

    let fresh = mask(p, LGJ_MASK_INIT_EMPTY);
    let out_fresh = eval_live(p, &ops, fresh);
    assert_eq!(
        out_dirty, out_fresh,
        "the same plan into a poisoned-tail destination and a fresh \
         destination must publish identically"
    );

    assert_eq!(lgj_close(p), LGJ_OK);
}

/// FAILS IF: validation and evaluation are interleaved rather than fully
/// separated — the OQ-1 ordering concern. Today `validate_plan` walks the
/// WHOLE plan before any op is evaluated, so a bad op anywhere leaves
/// `dst_mask` untouched regardless of its position. A future lowering that
/// validates op-by-op WHILE evaluating (rather than validating the whole
/// plan up front) could let earlier, valid ops' effects reach `dst_mask`
/// before a later op's invalidity is discovered. Sweeping every position of
/// a 4-op plan, with the destination pre-poisoned to a value neither
/// `LGJ_MASK_INIT_EMPTY` nor `LGJ_MASK_INIT_ALL` could ever produce, makes
/// "unchanged" an OBSERVATION rather than "still zero, which a half-applied
/// write could also produce by accident."
#[test]
fn an_error_at_any_position_in_a_plan_leaves_the_destination_untouched() {
    const POISON: u64 = 0xDEAD_BEEF_DEAD_BEEF;
    let n_rows = 500u64;
    let p = open(n_rows, 0xBAD0_5EED);
    let expected_poison = vec![POISON; mask_words_for(n_rows) as usize];

    let good = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_OR),
        op(LGJ_OP_LT_I32, LANE_VALUES, 200, LGJ_COMBINE_AND),
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 3, LGJ_COMBINE_OR),
    ];
    // Out of range on ANY pattern: `PATTERN_LANE_COUNT` is 3, so 9999 is
    // rejected by `validate_plan`'s own lane check regardless of `n_rows`.
    let bad = op(LGJ_OP_GT_I32, 9999, 0, LGJ_COMBINE_AND);

    for k in 0..good.len() {
        let mut plan = good;
        plan[k] = bad;

        let out_live = eval_live(p, &plan, poisoned_mask(p, POISON));
        let out_legacy = eval_legacy(p, &plan, poisoned_mask(p, POISON));

        assert_eq!(
            out_live.status, LGJ_ERR_INVALID_LANE,
            "k={k}: a plan carrying an out-of-range lane_id must be \
             rejected with LGJ_ERR_INVALID_LANE"
        );
        assert_eq!(
            out_live.status, out_legacy.status,
            "k={k}: the live path and the frozen oracle must reject the \
             same malformed plan the same way"
        );
        assert_eq!(
            out_live.words, expected_poison,
            "k={k}: a rejected plan must leave the poisoned destination \
             byte-for-byte untouched, not merely zeroed (live path)"
        );
        assert_eq!(
            out_legacy.words, expected_poison,
            "k={k}: a rejected plan must leave the poisoned destination \
             byte-for-byte untouched, not merely zeroed (oracle side)"
        );
        assert_eq!(
            out_live.count, 12345,
            "k={k}: out_count must not be written on a rejected plan (live \
             path)"
        );
        assert_eq!(
            out_legacy.count, 12345,
            "k={k}: out_count must not be written on a rejected plan \
             (oracle side)"
        );
    }

    assert_eq!(lgj_close(p), LGJ_OK);
}

/// FAILS IF: a scratch buffer shared or reused across separate `plan_eval`
/// calls (PR4's whole allocation-avoidance strategy) is left DENSE or
/// SPARSE by one call and that residue leaks into a LATER, unrelated
/// call's answer instead of being fully re-established by that later call.
///
/// [`assert_agrees`] is used for every measurement below, not only the
/// final one, and that is deliberate: its two sub-calls run on the SAME
/// thread as everything before them, so its LIVE half inherits whatever
/// the shared scratch buffer looked like when the PREVIOUS `assert_agrees`
/// call finished, while its LEGACY half allocates its own fresh scratch on
/// every call (the frozen copy's own `let mut scratch = vec![0u64;
/// n_words];`) and is therefore immune to this leak by construction —
/// exactly what makes it a valid oracle for detecting one. Each call also
/// creates its own brand-new destination internally, which is what gives
/// every measurement below "a DIFFERENT destination" for free.
#[test]
fn scratch_left_dense_by_one_call_does_not_leak_into_the_next() {
    let everything = [op(LGJ_OP_EQ_U32, LANE_CLASSES, 999, LGJ_COMBINE_OR)];
    let selective = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
    ];

    // Round A: a call whose scratch ends DENSE (a leading OR saturates the
    // whole accumulator to all-ones), immediately followed by a selective
    // call — and then the identical selective call again, to pin
    // idempotence on the same shared state.
    let n1 = 1000u64;
    let p1 = open(n1, 33);

    let dense_a: Outcome = assert_agrees(p1, &everything, "round A: dense seeding call");
    assert_eq!(
        dense_a.count, n1,
        "round A's seeding call must actually saturate every row, or it \
         never leaves scratch dense in the first place"
    );

    let selective_a: Outcome = assert_agrees(
        p1,
        &selective,
        "round A: selective call right after a dense-leaving one",
    );
    assert!(
        selective_a.count > 0 && selective_a.count < n1,
        "round A: the selective plan must select a proper subset, or a \
         stale dense bit leaking in from the seeding call would be \
         invisible (got {})",
        selective_a.count
    );

    // Idempotence: the identical selective plan, run again right after, on
    // the same pattern (so it shares whatever scratch state the two calls
    // above already left behind), must publish identically.
    let selective_a2: Outcome = assert_agrees(p1, &selective, "round A: idempotence re-run");
    assert_eq!(
        selective_a, selective_a2,
        "idempotence: the identical selective plan, run twice in a row, \
         must publish identically"
    );

    assert_eq!(lgj_close(p1), LGJ_OK);

    // Round B, INVERTED order and a second (n_rows, seed) pair: the
    // selective call runs FIRST (leaving scratch mostly zero), the
    // saturating one SECOND.
    let n2 = 2500u64;
    let p2 = open(n2, 0x51DE_5EED);

    let selective_b: Outcome = assert_agrees(p2, &selective, "round B: selective seeding call");
    assert!(
        selective_b.count > 0 && selective_b.count < n2,
        "round B's seeding call must itself be a proper subset, or it \
         never leaves scratch sparse in the first place (got {})",
        selective_b.count
    );

    let dense_b: Outcome = assert_agrees(
        p2,
        &everything,
        "round B: saturating call right after a sparse-leaving one",
    );
    assert_eq!(
        dense_b.count, n2,
        "round B: a leading-OR plan must still select every row, whatever \
         scratch state the preceding selective call left behind"
    );

    assert_eq!(lgj_close(p2), LGJ_OK);
}

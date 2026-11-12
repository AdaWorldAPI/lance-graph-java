//! Minor 13 — `lgj_plan_scalar`, a plan folded to one number with no mask.
//!
//! Three oracles, none of them the code under test:
//!
//! 1. **The mask path it stands beside.** `lgj_plan_eval` into a mask, then
//!    the returned count, or `lgj_reduce_sum_i32` over that mask. Different
//!    terminal (`Keep`), different kernel for the sum, same numbers.
//! 2. **`mask_risc`'s row-at-a-time reference executor** on the SAME lowered
//!    program, with a tile narrow enough that the population spans several.
//! 3. **A hand walk over the fixture arrays**: the accumulator starts true
//!    and each op is folded in with `&&` or `||`, one row at a time. No
//!    lowering, no kernel, no mask.
//!
//! And every refusal, each pinned to leave `out_value` untouched.

use super::*;
use lance_graph_mask_risc::reference_execute_into;

fn open(n: u64, seed: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(call::pattern_open(n, seed, &mut h), LGJ_OK);
    h
}

/// A value no count or sum in these fixtures produces.
const SENTINEL: i64 = i64::MIN + 0x5CA1;

fn scalar(res: u64, ops: &[LgjOpDesc], fold: u32, val_lane: u32) -> (i32, i64) {
    let mut out = SENTINEL;
    let ptr = if ops.is_empty() {
        std::ptr::null()
    } else {
        ops.as_ptr()
    };
    let st = call13::plan_scalar(res, ptr, ops.len() as u32, fold, val_lane, &mut out);
    (st, out)
}

/// Oracle 1: `lgj_plan_eval` into a mask, then its count and the sum over it.
/// An empty plan is refused by `lgj_plan_eval`, so the caller-filled all-rows
/// mask stands in for it, exactly as Java's `sumOf` does today.
fn mask_path_oracle(res: u64, ops: &[LgjOpDesc]) -> (i64, i64) {
    let mut mask = 0u64;
    let mut count = 0u64;
    if ops.is_empty() {
        assert_eq!(call::mask_create(res, LGJ_MASK_INIT_ALL, &mut mask), LGJ_OK);
        assert_eq!(call::mask_count(mask, &mut count), LGJ_OK);
    } else {
        assert_eq!(
            call::mask_create(res, LGJ_MASK_INIT_EMPTY, &mut mask),
            LGJ_OK
        );
        assert_eq!(
            call::plan_eval(res, ops.as_ptr(), ops.len() as u32, mask, &mut count),
            LGJ_OK
        );
    }
    let mut sum = 0i64;
    assert_eq!(
        call::reduce_sum_i32(res, LANE_VALUES, mask, &mut sum),
        LGJ_OK
    );
    assert_eq!(lgj_close(mask), LGJ_OK);
    (count as i64, sum)
}

/// Oracle 3: one row at a time, straight off the fixture arrays.
fn hand_walk(f: &Fixture, ops: &[LgjOpDesc]) -> (i64, i64) {
    let (mut count, mut sum) = (0i64, 0i64);
    for i in 0..f.classes().len() {
        let mut acc = true;
        for o in ops {
            let p = match (o.op, o.lane_id) {
                (LGJ_OP_EQ_U32, LANE_CLASSES) => f.classes()[i] == o.operand as u32,
                (LGJ_OP_GT_I32, LANE_VALUES) => f.values()[i] > o.operand as i32,
                (LGJ_OP_LT_I32, LANE_VALUES) => f.values()[i] < o.operand as i32,
                other => panic!("hand_walk has no reading for {other:?}"),
            };
            acc = if o.combine == LGJ_COMBINE_AND {
                acc && p
            } else {
                acc || p
            };
        }
        if acc {
            count += 1;
            sum += i64::from(f.values()[i]);
        }
    }
    (count, sum)
}

const TWO_OP: [(u32, u32, i64); 2] = [
    (LGJ_OP_EQ_U32, LANE_CLASSES, 7),
    (LGJ_OP_GT_I32, LANE_VALUES, 100),
];
const THREE_OP: [(u32, u32, i64); 3] = [
    (LGJ_OP_GT_I32, LANE_VALUES, 100),
    (LGJ_OP_EQ_U32, LANE_CLASSES, 3),
    (LGJ_OP_LT_I32, LANE_VALUES, 300),
];

/// `{AND, OR}^n` over both op sets — 4 + 8 = 12 vectors, plus the empty plan.
fn for_each_plan(mut visit: impl FnMut(String, Vec<LgjOpDesc>)) {
    visit("n=0 []".into(), Vec::new());
    for (base, arity) in [(&TWO_OP[..], 2u32), (&THREE_OP[..], 3u32)] {
        for bits in 0u32..(1 << arity) {
            let ops: Vec<LgjOpDesc> = base
                .iter()
                .enumerate()
                .map(|(i, &(opcode, lane, operand))| {
                    let combine = if bits & (1 << i) == 0 {
                        LGJ_COMBINE_AND
                    } else {
                        LGJ_COMBINE_OR
                    };
                    op(opcode, lane, operand, combine)
                })
                .collect();
            visit(format!("n={arity} bits={bits:03b}"), ops);
        }
    }
}

/// Parity with the mask path and with the hand walk, on a single-tile and a
/// multi-tile population, over the whole combine sweep.
#[test]
fn count_and_sum_match_the_mask_path_and_the_hand_walk() {
    for (n, seed) in [(1000u64, 33u64), (65_536, 7)] {
        let res = open(n, seed);
        let f = Fixture::generate(n, seed).unwrap();
        let mut distinct = std::collections::HashSet::new();
        let mut plans = 0usize;
        for_each_plan(|label, ops| {
            let (st_c, count) = scalar(res, &ops, LGJ_FOLD_COUNT, 0);
            let (st_s, sum) = scalar(res, &ops, LGJ_FOLD_SUM_I32, LANE_VALUES);
            assert_eq!((st_c, st_s), (LGJ_OK, LGJ_OK), "n={n} {label}");
            assert_eq!(
                (count, sum),
                mask_path_oracle(res, &ops),
                "n={n} {label}: scalar terminal vs the mask path"
            );
            assert_eq!(
                (count, sum),
                hand_walk(&f, &ops),
                "n={n} {label}: scalar terminal vs the hand walk"
            );
            distinct.insert((count, sum));
            plans += 1;
        });
        assert_eq!(plans, 13);
        // Anti-vacuity: the sweep is not one answer repeated, and it reaches
        // both an every-row plan and a selective one.
        assert!(
            distinct.len() >= 6,
            "n={n}: only {} distinct answers across 13 plans",
            distinct.len()
        );
        assert!(distinct.iter().any(|&(c, _)| c == n as i64));
        assert!(distinct.iter().any(|&(c, _)| c > 0 && c * 8 < n as i64));
        assert_eq!(lgj_close(res), LGJ_OK);
    }
}

/// Oracle 2: the tiled executor against the row-at-a-time reference on the
/// same lowered program, with a tile that forces several tiles.
#[test]
fn the_tiled_executor_and_the_scalar_reference_agree_on_the_lowered_program() {
    let (n, seed) = (5000u64, 0xBEEF_u64);
    let f = Fixture::generate(n, seed).unwrap();
    let lanes = [
        LaneRef::U64(f.ids()),
        LaneRef::U32(f.classes()),
        LaneRef::I32(f.values()),
    ];
    let planes = Planes {
        n_rows: n as usize,
        masks: &[],
        lanes: &lanes,
    };
    let folds = [
        plan_lower::ScalarFold::Count,
        plan_lower::ScalarFold::SumI32 {
            val: LANE_VALUES as u16,
            n_rows: n as u32,
        },
    ];
    let (mut programs, mut shortcuts) = (0usize, 0usize);
    for_each_plan(|label, ops| {
        for fold in folds {
            let program = match plan_lower::lower_scalar(&ops, fold).unwrap() {
                plan_lower::LoweredScalar::AllRowsCount => {
                    shortcuts += 1;
                    continue;
                }
                plan_lower::LoweredScalar::Program(p) => p,
            };
            // An explicit narrow tile: the default is a performance setting
            // and must not decide whether this fixture spans several tiles.
            let tile = 8usize;
            assert!(tile < n.div_ceil(64) as usize);
            let need = scratch_words_for(tile, plan_lower::SLOTS as usize).unwrap();
            let mut buf = vec![0u64; need];
            let mut scratch = Scratch::over(&mut buf, tile, plan_lower::SLOTS as usize).unwrap();
            let tiled =
                execute_into(&program, &planes, &Foreign::NONE, &mut scratch, Out::None).unwrap();
            let reference =
                reference_execute_into(&program, &planes, &Foreign::NONE, Out::None).unwrap();
            assert_eq!(tiled, reference, "{label} {fold:?}");
            assert!(
                matches!(tiled, Value::Count(_) | Value::SumI64(_)),
                "{label}: a scalar terminal answers a scalar"
            );
            programs += 1;
        }
    });
    // 13 plans × 2 folds; the count shortcut takes the empty plan and the two
    // all-OR plans.
    assert_eq!((programs, shortcuts), (23, 3));
}

/// The empty plan and an all-OR plan both mean every row. The count is the
/// row count; the sum is the whole lane, hand-added.
#[test]
fn an_empty_plan_and_an_all_or_plan_fold_every_row() {
    let (n, seed) = (4096u64, 0xC0FFEE_u64);
    let f = Fixture::generate(n, seed).unwrap();
    let whole: i64 = f.values().iter().map(|&v| i64::from(v)).sum();
    assert_ne!(whole, 0, "anti-vacuity: the whole-lane sum is not zero");
    let res = open(n, seed);
    let all_or = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_OR),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_OR),
    ];
    for ops in [&[][..], &all_or[..]] {
        assert_eq!(scalar(res, ops, LGJ_FOLD_COUNT, 0), (LGJ_OK, n as i64));
        assert_eq!(
            scalar(res, ops, LGJ_FOLD_SUM_I32, LANE_VALUES),
            (LGJ_OK, whole)
        );
    }
    assert_eq!(lgj_close(res), LGJ_OK);
}

/// A plan that selects nothing counts zero and sums zero, and both are
/// written (the sentinel does not survive a success).
#[test]
fn an_empty_selection_is_zero_not_absent() {
    let res = open(2048, 5);
    let none = [op(LGJ_OP_EQ_U32, LANE_CLASSES, 0xFFFF, LGJ_COMBINE_AND)];
    assert_eq!(scalar(res, &none, LGJ_FOLD_COUNT, 0), (LGJ_OK, 0));
    assert_eq!(
        scalar(res, &none, LGJ_FOLD_SUM_I32, LANE_VALUES),
        (LGJ_OK, 0)
    );
    assert_eq!(lgj_close(res), LGJ_OK);
}

/// `lgj_plan_eval` is unchanged by this symbol: after a scalar call a mask the
/// caller holds is byte-for-byte what it was, and `lgj_plan_eval` still lands
/// its mask and its count.
#[test]
fn the_scalar_call_touches_no_mask_and_plan_eval_still_lands_one() {
    let res = open(4096, 11);
    let ops = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
    ];
    let mut mask = 0u64;
    assert_eq!(call::mask_create(res, LGJ_MASK_INIT_ALL, &mut mask), LGJ_OK);
    let mut before = 0u64;
    assert_eq!(call::mask_count(mask, &mut before), LGJ_OK);
    assert_eq!(before, 4096);

    let (st, count) = scalar(res, &ops, LGJ_FOLD_COUNT, 0);
    assert_eq!(st, LGJ_OK);
    let mut after = 0u64;
    assert_eq!(call::mask_count(mask, &mut after), LGJ_OK);
    assert_eq!(after, before, "the scalar call wrote no mask");

    let mut landed = 0u64;
    assert_eq!(
        call::plan_eval(res, ops.as_ptr(), ops.len() as u32, mask, &mut landed),
        LGJ_OK
    );
    let mut now = 0u64;
    assert_eq!(call::mask_count(mask, &mut now), LGJ_OK);
    assert_eq!(landed as i64, count);
    assert_eq!(now, landed, "lgj_plan_eval still lands its mask");
    assert!(now > 0 && now < before, "anti-vacuity: a selective plan");
    assert_eq!(lgj_close(mask), LGJ_OK);
    assert_eq!(lgj_close(res), LGJ_OK);
}

/// Every refusal is a status reached before any write.
#[test]
fn every_refusal_is_a_status_and_leaves_out_value_untouched() {
    let res = open(512, 1);
    let ok_ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 0, LGJ_COMBINE_AND)];
    let refusals: Vec<(&str, i32, (i32, i64))> = vec![
        (
            "unknown fold",
            LGJ_ERR_UNKNOWN_OPCODE,
            scalar(res, &ok_ops, 2, LANE_VALUES),
        ),
        (
            "sum lane is U32",
            LGJ_ERR_LANE_KIND_MISMATCH,
            scalar(res, &ok_ops, LGJ_FOLD_SUM_I32, LANE_CLASSES),
        ),
        (
            "sum lane is U64",
            LGJ_ERR_LANE_KIND_MISMATCH,
            scalar(res, &ok_ops, LGJ_FOLD_SUM_I32, LANE_IDS),
        ),
        (
            "sum lane out of range",
            LGJ_ERR_INVALID_LANE,
            scalar(res, &ok_ops, LGJ_FOLD_SUM_I32, 9),
        ),
        (
            "unknown opcode in the plan",
            LGJ_ERR_UNKNOWN_OPCODE,
            scalar(
                res,
                &[op(77, LANE_VALUES, 0, LGJ_COMBINE_AND)],
                LGJ_FOLD_COUNT,
                0,
            ),
        ),
        (
            "plan lane kind mismatch",
            LGJ_ERR_LANE_KIND_MISMATCH,
            scalar(
                res,
                &[op(LGJ_OP_GT_I32, LANE_CLASSES, 0, LGJ_COMBINE_AND)],
                LGJ_FOLD_COUNT,
                0,
            ),
        ),
        (
            "a bad op after a good one",
            LGJ_ERR_UNKNOWN_OPCODE,
            scalar(
                res,
                &[ok_ops[0], op(77, LANE_VALUES, 0, LGJ_COMBINE_OR)],
                LGJ_FOLD_SUM_I32,
                LANE_VALUES,
            ),
        ),
        (
            "res is fabricated",
            LGJ_ERR_INVALID_HANDLE,
            scalar(0xDEAD_BEEF, &ok_ops, LGJ_FOLD_COUNT, 0),
        ),
    ];
    for (what, want, (st, out)) in refusals {
        assert_eq!(st, want, "{what}");
        assert_eq!(out, SENTINEL, "{what}: out_value was written on a refusal");
    }

    // A count ignores `val_lane`, even an out-of-range one.
    let (st, count) = scalar(res, &ok_ops, LGJ_FOLD_COUNT, 9);
    assert_eq!(st, LGJ_OK);
    assert_ne!(count, SENTINEL);

    // Null pointers.
    let mut out = SENTINEL;
    assert_eq!(
        call13::plan_scalar(res, std::ptr::null(), 1, LGJ_FOLD_COUNT, 0, &mut out),
        LGJ_ERR_NULL_ARGUMENT
    );
    assert_eq!(out, SENTINEL);
    assert_eq!(
        call13::plan_scalar(
            res,
            ok_ops.as_ptr(),
            1,
            LGJ_FOLD_COUNT,
            0,
            std::ptr::null_mut()
        ),
        LGJ_ERR_NULL_ARGUMENT
    );

    // A mask or a row store is the wrong kind of resource.
    let mut mask = 0u64;
    assert_eq!(call::mask_create(res, LGJ_MASK_INIT_ALL, &mut mask), LGJ_OK);
    assert_eq!(
        scalar(mask, &ok_ops, LGJ_FOLD_COUNT, 0),
        (LGJ_ERR_WRONG_RESOURCE_KIND, SENTINEL)
    );
    assert_eq!(lgj_close(mask), LGJ_OK);

    // After close, the generation-checked handle refuses.
    assert_eq!(lgj_close(res), LGJ_OK);
    assert_eq!(
        scalar(res, &ok_ops, LGJ_FOLD_COUNT, 0),
        (LGJ_ERR_INVALID_HANDLE, SENTINEL)
    );
}

/// Every plan defect is reported exactly as `lgj_plan_eval` reports it. The
/// executor would refuse most of these on its own, but not with the same
/// status: a lane index past `u16` and an undefined combine are caught only
/// by the shared plan validation.
#[test]
fn plan_defects_are_reported_as_lgj_plan_eval_reports_them() {
    let res = open(512, 1);
    let good = op(LGJ_OP_GT_I32, LANE_VALUES, 0, LGJ_COMBINE_AND);
    let defects = [
        ("unknown opcode", op(77, LANE_VALUES, 0, LGJ_COMBINE_AND)),
        (
            "lane out of range",
            op(LGJ_OP_GT_I32, 9, 0, LGJ_COMBINE_AND),
        ),
        (
            "lane index past u16",
            op(LGJ_OP_GT_I32, 70_000, 0, LGJ_COMBINE_AND),
        ),
        (
            "lane kind mismatch",
            op(LGJ_OP_GT_I32, LANE_CLASSES, 0, LGJ_COMBINE_AND),
        ),
        ("undefined combine", op(LGJ_OP_GT_I32, LANE_VALUES, 0, 7)),
    ];
    let mut mask = 0u64;
    assert_eq!(call::mask_create(res, LGJ_MASK_INIT_ALL, &mut mask), LGJ_OK);
    let mut statuses = std::collections::HashSet::new();
    for (what, bad) in defects {
        for plan in [vec![bad], vec![good, bad], vec![bad, good]] {
            let mut count = 0u64;
            let want = call::plan_eval(res, plan.as_ptr(), plan.len() as u32, mask, &mut count);
            assert_ne!(want, LGJ_OK, "{what}: lgj_plan_eval refuses it");
            for (fold, lane) in [(LGJ_FOLD_COUNT, 0), (LGJ_FOLD_SUM_I32, LANE_VALUES)] {
                assert_eq!(
                    scalar(res, &plan, fold, lane),
                    (want, SENTINEL),
                    "{what}, fold {fold}"
                );
            }
            statuses.insert(want);
        }
    }
    // Anti-vacuity: the defects are not all one status.
    assert!(statuses.len() >= 3, "only {statuses:?}");
    assert_eq!(lgj_close(mask), LGJ_OK);
    assert_eq!(lgj_close(res), LGJ_OK);
}

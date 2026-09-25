//! Minor 12 — `lgj_plan_group_sum_i32`, the grouped sum that never builds a
//! selection.
//!
//! Three oracles, none of them the code under test:
//!
//! 1. **The two-crossing path it replaces.** `lgj_plan_eval` into a mask,
//!    then `lgj_reduce_sum_i32` over it, once per group with `class == g`
//!    appended as an AND. That is exactly what `bricks`' `sumBy()` measured
//!    at 32 crossings for 16 groups; the new symbol must answer identically
//!    in one. Different kernels (`masked_sum_i32` vs `masked_group_sum_i32`),
//!    different lowering (`Keep` vs a sum terminal), same numbers.
//! 2. **`mask_risc`'s row-at-a-time reference executor**, run on the SAME
//!    lowered program — whole-evaluator-against-whole-evaluator, the
//!    discipline `lgj_plan_eval_scalar` gives the `Keep` path.
//! 3. **A hand walk over the fixture arrays** with a hand-evaluated
//!    predicate, for the fk-keyed form: no kernel, no lowering, no mask —
//!    `for i: if value[i] > 100 { out[b.class[a.class[i]]] += value[i] }`.
//!
//! And every refusal, each pinned to leave `out_sums` byte-for-byte alone.

use super::*;
use lance_graph_mask_risc::reference_execute_into;

fn open(n: u64, seed: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(call::pattern_open(n, seed, &mut h), LGJ_OK);
    h
}

/// Every element pre-filled with a sentinel no real sum produces, so a
/// success can be checked for having written EVERY group (zero included) and
/// a refusal for having written none.
const SENTINEL: i64 = i64::MIN + 0x5EED;

/// Run the symbol; `ops` empty passes a NULL pointer, the way a caller with
/// no predicate would.
fn group_sum(
    res: u64,
    ops: &[LgjOpDesc],
    group_lane: u32,
    val_lane: u32,
    via: (u64, u32),
    groups: usize,
) -> (i32, Vec<i64>) {
    let mut out = vec![SENTINEL; groups];
    let ptr = if ops.is_empty() {
        std::ptr::null()
    } else {
        ops.as_ptr()
    };
    let st = call12::plan_group_sum_i32(
        res,
        ptr,
        ops.len() as u32,
        group_lane,
        val_lane,
        via.0,
        via.1,
        out.as_mut_ptr(),
        groups as u64,
    );
    (st, out)
}

/// Oracle 1: the two-crossing path, per group.
fn two_crossing_oracle(res: u64, ops: &[LgjOpDesc], groups: usize) -> Vec<i64> {
    (0..groups as u32)
        .map(|g| {
            let mut plan = ops.to_vec();
            plan.push(op(
                LGJ_OP_EQ_U32,
                LANE_CLASSES,
                i64::from(g),
                LGJ_COMBINE_AND,
            ));
            let mut mask = 0u64;
            assert_eq!(
                call::mask_create(res, LGJ_MASK_INIT_EMPTY, &mut mask),
                LGJ_OK
            );
            let mut count = 0u64;
            assert_eq!(
                call::plan_eval(res, plan.as_ptr(), plan.len() as u32, mask, &mut count),
                LGJ_OK
            );
            let mut sum = 0i64;
            assert_eq!(
                call::reduce_sum_i32(res, LANE_VALUES, mask, &mut sum),
                LGJ_OK
            );
            assert_eq!(lgj_close(mask), LGJ_OK);
            sum
        })
        .collect()
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
            let names: Vec<&str> = (0..arity)
                .map(|i| if bits & (1 << i) == 0 { "AND" } else { "OR" })
                .collect();
            visit(format!("n={arity} [{}]", names.join(", ")), ops);
        }
    }
}

/// CAN-FIRE + parity: one crossing answers what sixteen pairs of crossings
/// answered, on a single-tile and a multi-tile population.
#[test]
fn grouped_sum_matches_the_two_crossing_path_over_the_combine_sweep() {
    for (n, seed) in [(1000u64, 33u64), (65_536, 7)] {
        let res = open(n, seed);
        let mut distinct_answers = std::collections::HashSet::new();
        for_each_plan(|label, ops| {
            let (st, got) = group_sum(res, &ops, LANE_CLASSES, LANE_VALUES, (0, 0), 16);
            assert_eq!(st, LGJ_OK, "n={n} {label}");
            let want = two_crossing_oracle(res, &ops, 16);
            assert_eq!(
                got, want,
                "n={n} {label}: one crossing vs the two-crossing path"
            );
            assert!(
                got.iter().all(|&v| v != SENTINEL),
                "{label}: every group is written on success, zero included"
            );
            distinct_answers.insert(got);
        });
        // Anti-vacuity: the sweep is not one answer repeated. AND-heavy and
        // OR-heavy vectors select different populations and must sum
        // differently.
        assert!(
            distinct_answers.len() >= 4,
            "n={n}: only {} distinct grouped answers across 13 plans",
            distinct_answers.len()
        );
        assert_eq!(lgj_close(res), LGJ_OK);
    }
}

/// The empty plan is legal here (unlike `lgj_plan_eval`) and means every
/// row; so does a plan whose every combine is OR. Both equal the per-class
/// sum over the whole fixture, hand-walked.
#[test]
fn an_empty_plan_and_an_all_or_plan_both_group_every_row() {
    let (n, seed) = (4096u64, 0xC0FFEE_u64);
    let f = Fixture::generate(n, seed).unwrap();
    let mut want = vec![0i64; 16];
    for (c, v) in f.classes().iter().zip(f.values()) {
        want[*c as usize] += i64::from(*v);
    }
    let res = open(n, seed);
    let (st, empty) = group_sum(res, &[], LANE_CLASSES, LANE_VALUES, (0, 0), 16);
    assert_eq!(st, LGJ_OK);
    assert_eq!(empty, want, "n_ops == 0 groups every row");
    let all_or = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_OR),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_OR),
    ];
    let (st, ored) = group_sum(res, &all_or, LANE_CLASSES, LANE_VALUES, (0, 0), 16);
    assert_eq!(st, LGJ_OK);
    assert_eq!(ored, want, "an all-OR plan is dead and groups every row");
    assert!(
        want.iter().filter(|&&v| v != 0).count() == 16,
        "anti-vacuity: 16 live groups"
    );
    assert_eq!(lgj_close(res), LGJ_OK);
}

/// `n_groups` bounds the SINK, not the keys: a selected row whose key is past
/// it is dropped, silently, and the first `n_groups` totals are unchanged.
#[test]
fn keys_past_n_groups_are_dropped_not_errors() {
    let res = open(2048, 5);
    let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 0, LGJ_COMBINE_AND)];
    let (st16, full) = group_sum(res, &ops, LANE_CLASSES, LANE_VALUES, (0, 0), 16);
    let (st8, eight) = group_sum(res, &ops, LANE_CLASSES, LANE_VALUES, (0, 0), 8);
    let (st20, twenty) = group_sum(res, &ops, LANE_CLASSES, LANE_VALUES, (0, 0), 20);
    assert_eq!((st16, st8, st20), (LGJ_OK, LGJ_OK, LGJ_OK));
    assert_eq!(
        eight,
        full[..8],
        "a narrower sink keeps the groups it can hold"
    );
    assert_eq!(
        twenty[..16],
        full[..],
        "a wider sink changes nothing below 16"
    );
    assert!(
        twenty[16..].iter().all(|&v| v == 0),
        "groups no key names are zero, not sentinel"
    );
    assert!(
        full[8..].iter().any(|&v| v != 0),
        "anti-vacuity: rows WERE dropped at n_groups=8"
    );
    assert_eq!(lgj_close(res), LGJ_OK);
}

/// Oracle 2: the row-at-a-time reference executor on the same lowered
/// program, multi-tile, both key shapes.
#[test]
fn the_tiled_executor_and_the_scalar_reference_agree_on_the_lowered_program() {
    let (n, seed) = (5000u64, 0xBEEF_u64);
    let a = Fixture::generate(n, seed).unwrap();
    let b = Fixture::generate(64, 0xB0B).unwrap();
    let lanes = [
        LaneRef::U64(a.ids()),
        LaneRef::U32(a.classes()),
        LaneRef::I32(a.values()),
    ];
    let planes = Planes {
        n_rows: n as usize,
        masks: &[],
        lanes: &lanes,
    };
    let via_lanes = [LaneRef::U32(b.classes())];
    let foreign = Foreign {
        planes: &[],
        lanes: &via_lanes,
    };
    let keys = [
        plan_lower::GroupKey::Local(LANE_CLASSES as u16),
        plan_lower::GroupKey::Via {
            fk: LANE_CLASSES as u16,
            key: 0,
        },
    ];
    let mut checked = 0usize;
    for_each_plan(|label, ops| {
        for key in keys {
            let program =
                plan_lower::lower_group_sum(&ops, n as u32, key, LANE_VALUES as u16).unwrap();
            // An explicit narrow tile, not `tile_words_for`: the default tile
            // is a performance setting (256 words since lance-graph #1281) and
            // must not decide whether this fixture spans several tiles.
            let tile = (n.div_ceil(64) as usize).min(8);
            let need = scratch_words_for(tile, plan_lower::SLOTS as usize).unwrap();
            let mut buf = vec![0u64; need];
            let mut scratch = Scratch::over(&mut buf, tile, plan_lower::SLOTS as usize).unwrap();
            assert!(
                tile < n.div_ceil(64) as usize,
                "the population spans several tiles"
            );
            let mut simd = vec![0i64; 16];
            let mut scalar = vec![0i64; 16];
            let v = execute_into(
                &program,
                &planes,
                &foreign,
                &mut scratch,
                Out::I64(&mut simd),
            )
            .unwrap();
            assert!(matches!(v, Value::GroupSummed), "{label}");
            let r =
                reference_execute_into(&program, &planes, &foreign, Out::I64(&mut scalar)).unwrap();
            assert!(matches!(r, Value::GroupSummed), "{label}");
            assert_eq!(
                simd, scalar,
                "{label} {key:?}: tiled executor vs scalar reference"
            );
            checked += 1;
        }
    });
    assert_eq!(checked, 26);
}

/// Oracle 3: the fk-keyed form against a hand walk — `group of row i` is
/// `b.class[a.class[i]]`, with the fixture's own arrays, a hand-evaluated
/// predicate, and no kernel anywhere. Also pins the zero fallback: a `via`
/// table shorter than the key range drops exactly the rows whose key names
/// no row of it.
#[test]
fn via_reads_the_second_table_through_the_key_lane() {
    let (n, seed_a) = (4096u64, 0xA11CE_u64);
    let a = Fixture::generate(n, seed_a).unwrap();
    let res = open(n, seed_a);
    let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)];
    for (b_rows, seed_b) in [(64u64, 0xB0B_u64), (8, 0xB0B)] {
        let b = Fixture::generate(b_rows, seed_b).unwrap();
        let via = open(b_rows, seed_b);
        let mut want = vec![0i64; 16];
        let mut dropped = 0usize;
        for i in 0..n as usize {
            if a.values()[i] <= 100 {
                continue;
            }
            let k = a.classes()[i] as usize;
            if k >= b.classes().len() {
                dropped += 1;
                continue;
            }
            want[b.classes()[k] as usize] += i64::from(a.values()[i]);
        }
        let (st, got) = group_sum(
            res,
            &ops,
            LANE_CLASSES,
            LANE_VALUES,
            (via, LANE_CLASSES),
            16,
        );
        assert_eq!(st, LGJ_OK, "via rows={b_rows}");
        assert_eq!(
            got, want,
            "via rows={b_rows}: fk-keyed grouped sum vs the hand walk"
        );
        // The join genuinely regrouped: the local answer must differ.
        let (_, local) = group_sum(res, &ops, LANE_CLASSES, LANE_VALUES, (0, 0), 16);
        assert_ne!(
            got, local,
            "via rows={b_rows}: the fk-keyed form is not the local form"
        );
        if b_rows == 8 {
            assert!(
                dropped > 0,
                "anti-vacuity: keys past the via table were dropped"
            );
        } else {
            assert_eq!(
                dropped, 0,
                "16 classes all name a row of a 64-row via table"
            );
            // With no drops, the totals are a regrouping of the local totals.
            assert_eq!(got.iter().sum::<i64>(), local.iter().sum::<i64>());
        }
        assert_eq!(lgj_close(via), LGJ_OK);
    }
    // `via_res == res` is a legal self-join.
    let (st, selfjoin) = group_sum(
        res,
        &ops,
        LANE_CLASSES,
        LANE_VALUES,
        (res, LANE_CLASSES),
        16,
    );
    assert_eq!(st, LGJ_OK);
    let mut want = vec![0i64; 16];
    for i in 0..n as usize {
        if a.values()[i] > 100 {
            want[a.classes()[a.classes()[i] as usize] as usize] += i64::from(a.values()[i]);
        }
    }
    assert_eq!(selfjoin, want);
    assert_eq!(lgj_close(res), LGJ_OK);
}

/// Every refusal is a status, reached before any write: the sentinel survives
/// in every element.
#[test]
fn every_refusal_is_a_status_and_leaves_out_sums_untouched() {
    let res = open(512, 1);
    let ok_ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 0, LGJ_COMBINE_AND)];
    type Refusal<'a> = (&'a str, i32, (i32, Vec<i64>));
    let refusals: Vec<Refusal<'_>> = vec![
        (
            "n_groups == 0",
            LGJ_ERR_NULL_ARGUMENT,
            group_sum(res, &ok_ops, LANE_CLASSES, LANE_VALUES, (0, 0), 0),
        ),
        (
            "group lane is I32",
            LGJ_ERR_LANE_KIND_MISMATCH,
            group_sum(res, &ok_ops, LANE_VALUES, LANE_VALUES, (0, 0), 16),
        ),
        (
            "group lane is U64",
            LGJ_ERR_LANE_KIND_MISMATCH,
            group_sum(res, &ok_ops, LANE_IDS, LANE_VALUES, (0, 0), 16),
        ),
        (
            "value lane is U32",
            LGJ_ERR_LANE_KIND_MISMATCH,
            group_sum(res, &ok_ops, LANE_CLASSES, LANE_CLASSES, (0, 0), 16),
        ),
        (
            "group lane out of range",
            LGJ_ERR_INVALID_LANE,
            group_sum(res, &ok_ops, 9, LANE_VALUES, (0, 0), 16),
        ),
        (
            "value lane out of range",
            LGJ_ERR_INVALID_LANE,
            group_sum(res, &ok_ops, LANE_CLASSES, 9, (0, 0), 16),
        ),
        (
            "unknown opcode in the plan",
            LGJ_ERR_UNKNOWN_OPCODE,
            group_sum(
                res,
                &[op(77, LANE_VALUES, 0, LGJ_COMBINE_AND)],
                LANE_CLASSES,
                LANE_VALUES,
                (0, 0),
                16,
            ),
        ),
        (
            "plan lane kind mismatch",
            LGJ_ERR_LANE_KIND_MISMATCH,
            group_sum(
                res,
                &[op(LGJ_OP_GT_I32, LANE_CLASSES, 0, LGJ_COMBINE_AND)],
                LANE_CLASSES,
                LANE_VALUES,
                (0, 0),
                16,
            ),
        ),
        (
            "via handle is fabricated",
            LGJ_ERR_INVALID_HANDLE,
            group_sum(
                res,
                &ok_ops,
                LANE_CLASSES,
                LANE_VALUES,
                (0xDEAD_BEEF, LANE_CLASSES),
                16,
            ),
        ),
        (
            "via lane is I32",
            LGJ_ERR_LANE_KIND_MISMATCH,
            group_sum(
                res,
                &ok_ops,
                LANE_CLASSES,
                LANE_VALUES,
                (res, LANE_VALUES),
                16,
            ),
        ),
        (
            "via lane out of range",
            LGJ_ERR_INVALID_LANE,
            group_sum(res, &ok_ops, LANE_CLASSES, LANE_VALUES, (res, 9), 16),
        ),
        (
            "res is fabricated",
            LGJ_ERR_INVALID_HANDLE,
            group_sum(0xDEAD_BEEF, &ok_ops, LANE_CLASSES, LANE_VALUES, (0, 0), 16),
        ),
    ];
    for (what, want, (st, out)) in refusals {
        assert_eq!(st, want, "{what}");
        assert!(
            out.iter().all(|&v| v == SENTINEL),
            "{what}: out_sums was written on a refusal"
        );
    }

    // A mask handle where a pattern is expected.
    let mut mask = 0u64;
    assert_eq!(call::mask_create(res, LGJ_MASK_INIT_ALL, &mut mask), LGJ_OK);
    let (st, out) = group_sum(mask, &ok_ops, LANE_CLASSES, LANE_VALUES, (0, 0), 16);
    assert_eq!(st, LGJ_ERR_WRONG_RESOURCE_KIND);
    assert!(out.iter().all(|&v| v == SENTINEL));
    let (st, out) = group_sum(
        res,
        &ok_ops,
        LANE_CLASSES,
        LANE_VALUES,
        (mask, LANE_CLASSES),
        16,
    );
    assert_eq!(
        st, LGJ_ERR_WRONG_RESOURCE_KIND,
        "a mask is not a via table either"
    );
    assert!(out.iter().all(|&v| v == SENTINEL));
    assert_eq!(lgj_close(mask), LGJ_OK);

    // Null pointers are statuses, not UB.
    let mut sink = [SENTINEL; 4];
    assert_eq!(
        call12::plan_group_sum_i32(
            res,
            ok_ops.as_ptr(),
            1,
            LANE_CLASSES,
            LANE_VALUES,
            0,
            0,
            std::ptr::null_mut(),
            4
        ),
        LGJ_ERR_NULL_ARGUMENT
    );
    assert_eq!(
        call12::plan_group_sum_i32(
            res,
            std::ptr::null(),
            1,
            LANE_CLASSES,
            LANE_VALUES,
            0,
            0,
            sink.as_mut_ptr(),
            4
        ),
        LGJ_ERR_NULL_ARGUMENT,
        "a null plan with n_ops > 0"
    );
    assert!(sink.iter().all(|&v| v == SENTINEL));

    // And after close, the generation-checked handle refuses.
    assert_eq!(lgj_close(res), LGJ_OK);
    let (st, out) = group_sum(res, &ok_ops, LANE_CLASSES, LANE_VALUES, (0, 0), 16);
    assert_eq!(st, LGJ_ERR_INVALID_HANDLE);
    assert!(out.iter().all(|&v| v == SENTINEL));
}

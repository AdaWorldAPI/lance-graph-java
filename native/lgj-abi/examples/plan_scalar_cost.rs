//! Cost of a count and a sum through `lgj_plan_scalar` (ABI minor 13), beside
//! the mask path they stand next to (`lgj_plan_eval`, then
//! `lgj_reduce_sum_i32`). Native calls only: no JVM and no FFM crossing is in
//! these numbers. Each row first checks that both paths give the same answer.
//!
//! `cargo run --release --example plan_scalar_cost`

use std::time::Instant;

use lgj_abi::abi::*;
use lgj_abi::exports::{
    lgj_close, lgj_mask_create, lgj_pattern_open, lgj_plan_eval, lgj_plan_scalar,
    lgj_reduce_sum_i32,
};
use lgj_abi::fixture::{LANE_CLASSES, LANE_VALUES};

const REPS: usize = 201;

fn desc(op: u32, lane_id: u32, operand: i64, combine: u32) -> LgjOpDesc {
    LgjOpDesc {
        op,
        lane_id,
        operand,
        combine,
        _reserved: 0,
    }
}

fn median_ns(mut f: impl FnMut()) -> u128 {
    for _ in 0..20 {
        f();
    }
    let mut t: Vec<u128> = (0..REPS)
        .map(|_| {
            let s = Instant::now();
            f();
            s.elapsed().as_nanos()
        })
        .collect();
    t.sort_unstable();
    t[REPS / 2]
}

fn main() {
    let plans: [(&str, Vec<LgjOpDesc>); 2] = [
        (
            "class==7 AND value>100",
            vec![
                desc(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
                desc(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
            ],
        ),
        (
            "value>100 (about half the rows)",
            vec![desc(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)],
        ),
    ];
    println!("medians of {REPS}, ns; native calls only");
    println!(
        "{:>9} | {:<32} | {:>9} | {:>11} {:>11} | {:>13} {:>11} | {:>10}",
        "rows",
        "plan",
        "selected",
        "count:mask",
        "count:fold",
        "sum:mask(2)",
        "sum:fold",
        "mask bytes"
    );
    for n in [1_024u64, 65_536, 1_048_576] {
        let mut res = 0u64;
        assert_eq!(unsafe { lgj_pattern_open(n, 0xC0DE, &mut res) }, LGJ_OK);
        let mut mask = 0u64;
        assert_eq!(
            unsafe { lgj_mask_create(res, LGJ_MASK_INIT_EMPTY, &mut mask) },
            LGJ_OK
        );
        for (label, ops) in &plans {
            let (p, k) = (ops.as_ptr(), ops.len() as u32);
            let (mut c_mask, mut s_mask, mut c_fold, mut s_fold) = (0u64, 0i64, 0i64, 0i64);
            unsafe {
                assert_eq!(lgj_plan_eval(res, p, k, mask, &mut c_mask), LGJ_OK);
                assert_eq!(
                    lgj_reduce_sum_i32(res, LANE_VALUES, mask, &mut s_mask),
                    LGJ_OK
                );
                assert_eq!(
                    lgj_plan_scalar(res, p, k, LGJ_FOLD_COUNT, 0, &mut c_fold),
                    LGJ_OK
                );
                assert_eq!(
                    lgj_plan_scalar(res, p, k, LGJ_FOLD_SUM_I32, LANE_VALUES, &mut s_fold),
                    LGJ_OK
                );
            }
            assert_eq!((c_mask as i64, s_mask), (c_fold, s_fold), "answers differ");

            let count_mask = median_ns(|| unsafe {
                let mut c = 0u64;
                lgj_plan_eval(res, p, k, mask, &mut c);
            });
            let count_fold = median_ns(|| unsafe {
                let mut v = 0i64;
                lgj_plan_scalar(res, p, k, LGJ_FOLD_COUNT, 0, &mut v);
            });
            let sum_mask = median_ns(|| unsafe {
                let (mut c, mut s) = (0u64, 0i64);
                lgj_plan_eval(res, p, k, mask, &mut c);
                lgj_reduce_sum_i32(res, LANE_VALUES, mask, &mut s);
            });
            let sum_fold = median_ns(|| unsafe {
                let mut v = 0i64;
                lgj_plan_scalar(res, p, k, LGJ_FOLD_SUM_I32, LANE_VALUES, &mut v);
            });
            println!(
                "{:>9} | {:<32} | {:>9} | {:>11} {:>11} | {:>13} {:>11} | {:>10}",
                n,
                label,
                c_fold,
                count_mask,
                count_fold,
                sum_mask,
                sum_fold,
                n.div_ceil(64) * 8
            );
        }
        assert_eq!(lgj_close(mask), LGJ_OK);
        assert_eq!(lgj_close(res), LGJ_OK);
    }
}

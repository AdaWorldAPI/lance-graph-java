//! What `lgj_plan_scalar` allocates per call, measured.
//!
//! Same instrument and the same rules as `plan_eval_no_alloc.rs`: its own
//! binary with exactly one `#[test]` (the counter is process-global), one
//! global warm-up at the largest row count, no per-measurement warm-up, and an
//! unseen row count measured after it.
//!
//! The property: **per-call allocation is independent of `n_rows`**, for the
//! count and for the sum. What remains is the lowering's own `Vec<MaskOp>`,
//! which is proportional to the number of ops, so the second half scales the
//! op count and requires the bytes to move.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use lgj_abi::abi::*;
use lgj_abi::exports::{lgj_pattern_open, lgj_plan_scalar};
use lgj_abi::fixture::{LANE_CLASSES, LANE_VALUES};

struct Counting;

static BYTES: AtomicUsize = AtomicUsize::new(0);

// SAFETY: a pure pass-through to `System`; the counter is the only addition.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        // SAFETY: same layout, same contract as the caller's.
        unsafe { System.alloc(layout) }
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: `ptr` came from `alloc` above with this `layout`.
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static A: Counting = Counting;

const SWEEP: [u64; 4] = [64, 1_000, 8_192, 65_536];
const UNSEEN: u64 = 999;
const REPS: usize = 100;

fn open(n: u64, seed: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(unsafe { lgj_pattern_open(n, seed, &mut h) }, LGJ_OK);
    h
}

fn desc(op: u32, lane_id: u32, operand: i64, combine: u32) -> LgjOpDesc {
    LgjOpDesc {
        op,
        lane_id,
        operand,
        combine,
        _reserved: 0,
    }
}

fn three_ops() -> Vec<LgjOpDesc> {
    vec![
        desc(LGJ_OP_GT_I32, LANE_VALUES, -1000, LGJ_COMBINE_AND),
        desc(LGJ_OP_LT_I32, LANE_VALUES, 1000, LGJ_COMBINE_AND),
        desc(LGJ_OP_EQ_U32, LANE_CLASSES, 3, LGJ_COMBINE_OR),
    ]
}

fn call(p: u64, ops: &[LgjOpDesc], fold: u32) {
    let mut v = 0i64;
    let s =
        unsafe { lgj_plan_scalar(p, ops.as_ptr(), ops.len() as u32, fold, LANE_VALUES, &mut v) };
    assert_eq!(s, LGJ_OK);
}

/// Bytes allocated by `REPS` calls. No warm-up inside: see the module doc of
/// `plan_eval_no_alloc.rs` for why that would hide the property.
fn measure(p: u64, ops: &[LgjOpDesc], fold: u32) -> usize {
    let before = BYTES.load(Ordering::Relaxed);
    for _ in 0..REPS {
        call(p, ops, fold);
    }
    BYTES.load(Ordering::Relaxed) - before
}

#[test]
fn per_call_allocation_does_not_depend_on_the_row_count() {
    let handles: Vec<(u64, u64)> = SWEEP.iter().map(|&n| (n, open(n, 0xC0FFEE ^ n))).collect();
    let unseen = (UNSEEN, open(UNSEEN, 0xBEEF));
    let ops = three_ops();

    // One global warm-up at the largest row count grows the thread-local
    // arena to its high-water mark, outside every measured region.
    let largest = handles.iter().max_by_key(|&&(n, _)| n).unwrap();
    call(largest.1, &ops, LGJ_FOLD_COUNT);

    for fold in [LGJ_FOLD_COUNT, LGJ_FOLD_SUM_I32] {
        let mut per_call = Vec::new();
        for &(n, p) in handles.iter().chain(std::iter::once(&unseen)) {
            let bytes = measure(p, &ops, fold);
            assert_eq!(bytes % REPS, 0, "fold {fold} n={n}: {bytes} bytes");
            per_call.push((n, bytes / REPS));
        }
        let first = per_call[0].1;
        assert!(
            per_call.iter().all(|&(_, b)| b == first),
            "fold {fold}: per-call bytes vary with the row count: {per_call:?}"
        );
        // Anti-vacuity, half one: the counter is live — a plan allocates its
        // op list, so the figure is not zero.
        assert!(first > 0, "fold {fold}: the counter measured nothing");

        // Half two: the bytes move with the OP count, so "identical across
        // row counts" is not passing because nothing is counted.
        let mut six = three_ops();
        six.extend(three_ops());
        let six_bytes = measure(handles[1].1, &six, fold) / REPS;
        assert!(
            six_bytes > first,
            "fold {fold}: six ops allocate {six_bytes}, three allocate {first}"
        );
        println!("fold {fold}: {first} bytes per call (3 ops), {six_bytes} (6 ops), rows {SWEEP:?} + {UNSEEN}");
    }
}

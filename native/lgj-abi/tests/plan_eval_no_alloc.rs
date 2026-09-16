//! C-ALLOC — what `lgj_plan_eval` allocates per call, measured rather than
//! asserted.
//!
//! # Why this is its own binary, with exactly one `#[test]`
//!
//! The counter is a process-global `#[global_allocator]`. A second test
//! running concurrently adds its own bytes and the gate flakes. The fix for a
//! flake here is never to relax the bound — that is how the gate dies — it is
//! to keep this binary at one test. A separate integration binary also keeps
//! the counting allocator out of the in-crate suite, which it would otherwise
//! perturb.
//!
//! `lgj-abi` is `["cdylib", "rlib"]`, so the rlib links here and the exported
//! symbols are callable directly.
//!
//! # The instrument measures Rust, and the existing gates do not
//!
//! This repo's other allocation gates use `getThreadAllocatedBytes`, which
//! measures the JAVA heap. It cannot see a Rust `vec!` at all. Citing those
//! gates as evidence for this property would be an overclaim, so this is a
//! Rust-side instrument: a pass-through `GlobalAlloc` over `System`
//! incrementing an `AtomicUsize`, the same shape
//! `lance-graph-mask-risc/tests/no_alloc.rs` uses.
//!
//! # The property, stated exactly
//!
//! The old loop allocated `2 * n_words * 8` bytes per call — an accumulator
//! and a predicate buffer, both sized by the ROW COUNT. At 65,536 rows that
//! is 16 KiB per call, and it grew without bound as the population did.
//!
//! PR4 removes that. What it does NOT remove is the lowering's own
//! `Vec<MaskOp>`: `Program` owns its op list, so building one allocates
//! bytes proportional to the number of OPS. That is a different quantity
//! entirely — a handful of ops against arbitrarily many rows — and the
//! honest claim is the one this test pins:
//!
//! **per-call allocation is independent of `n_rows`.**
//!
//! Which is why the two halves below are not decoration. The first measures
//! the same plan at row counts spanning three orders of magnitude and
//! requires the per-call bytes to be IDENTICAL, not merely small: a
//! row-proportional allocation cannot survive that, however cheap it looks at
//! one size. The second scales the OP count and requires the bytes to move,
//! so "identical across row counts" cannot be passing because the counter is
//! inert.
//!
//! # The unseen-row-count arm
//!
//! A thread-local arena grown monotonically means a SMALLER call carves a
//! prefix of a buffer that already exists. A gate that warms up over a fixed
//! sweep and then measures that same sweep is green while the property is
//! false — it would pass for a cache keyed by row count, which allocates the
//! first time it sees each distinct size and therefore depends on the
//! population's HISTORY, the exact thing C-ALLOC denies. `UNSEEN` is a row
//! count no earlier call in this test has used, measured after the warm-up.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};

use lgj_abi::abi::*;
use lgj_abi::exports::{lgj_close, lgj_mask_create, lgj_pattern_open, lgj_plan_eval};
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

/// Row counts the sweep measures. `UNSEEN` is deliberately not among them and
/// deliberately smaller than the largest — see the module doc.
const SWEEP: [u64; 4] = [64, 1_000, 8_192, 65_536];
const UNSEEN: u64 = 999;
const REPS: usize = 100;

fn open(n: u64, seed: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(unsafe { lgj_pattern_open(n, seed, &mut h) }, LGJ_OK);
    h
}

fn mask_of(p: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(
        unsafe { lgj_mask_create(p, LGJ_MASK_INIT_EMPTY, &mut h) },
        LGJ_OK
    );
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

/// A three-op plan: one AND-seeded predicate, one gated AND, one OR.
fn three_ops() -> Vec<LgjOpDesc> {
    vec![
        desc(LGJ_OP_GT_I32, LANE_VALUES, -1000, LGJ_COMBINE_AND),
        desc(LGJ_OP_LT_I32, LANE_VALUES, 1000, LGJ_COMBINE_AND),
        desc(LGJ_OP_EQ_U32, LANE_CLASSES, 3, LGJ_COMBINE_OR),
    ]
}

/// Run `ops` against `(p, m)` `REPS` times and return the bytes allocated,
/// with the status checked so a call that silently failed cannot be measured
/// as a cheap one.
///
/// **There is deliberately no warm-up call inside this function**, and that
/// is load-bearing rather than tidy. A per-measurement warm-up absorbs the
/// FIRST call at each row count — which is exactly and only where a cache
/// keyed by row count allocates. Measured: with one un-measured call here,
/// replacing the monotonic `resize` with a per-size `*buf = vec![…]` left the
/// whole gate GREEN. The warm-up was hiding the property the gate exists to
/// check, so every arm below is COLD at its own row count and the arena is
/// warmed exactly once, globally, at the largest size in the sweep.
fn measure(p: u64, m: u64, ops: &[LgjOpDesc]) -> usize {
    let before = BYTES.load(Ordering::Relaxed);
    for _ in 0..REPS {
        let mut c = 0u64;
        let s = unsafe { lgj_plan_eval(p, ops.as_ptr(), ops.len() as u32, m, &mut c) };
        assert_eq!(s, LGJ_OK);
    }
    BYTES.load(Ordering::Relaxed) - before
}

/// Grow the thread-local arena to its high-water mark, un-measured.
///
/// The very first plan a thread evaluates allocates the arena. That is the
/// allocation this test BOUNDS (once per thread, at the largest row count it
/// ever sees) rather than the per-call one it forbids, so it happens here,
/// outside every measured region, and exactly once.
fn warm_the_arena(p: u64, m: u64, ops: &[LgjOpDesc]) {
    let mut c = 0u64;
    assert_eq!(
        unsafe { lgj_plan_eval(p, ops.as_ptr(), ops.len() as u32, m, &mut c) },
        LGJ_OK
    );
}

/// FAILS IF: per-call allocation depends on the ROW COUNT — the property the
/// old loop's `2 * n_words * 8` bytes per call violated by construction — or
/// if the counter is inert, which would make the first half pass for the
/// wrong reason.
#[test]
fn per_call_allocation_does_not_depend_on_the_row_count() {
    // Everything that legitimately allocates happens before any measurement:
    // opening a pattern builds its fixture lanes, and creating a mask
    // allocates its words. Neither is per-call work.
    let handles: Vec<(u64, u64, u64)> = SWEEP
        .iter()
        .map(|&n| {
            let p = open(n, 0xC0FFEE ^ n);
            (n, p, mask_of(p))
        })
        .collect();
    let unseen = {
        let p = open(UNSEEN, 0xBEEF);
        (UNSEEN, p, mask_of(p))
    };
    let ops = three_ops();

    // ONE global warm-up, at the LARGEST row count in the sweep, so the arena
    // is already at its high-water mark before anything is measured. Every
    // later call — including `UNSEEN`, which is smaller — must therefore
    // carve a prefix of a buffer that already exists.
    let largest = handles
        .iter()
        .max_by_key(|&&(n, _, _)| n)
        .expect("the sweep is not empty");
    warm_the_arena(largest.1, largest.2, &ops);

    let mut per_call = Vec::new();
    for &(n, p, m) in &handles {
        let bytes = measure(p, m, &ops);
        assert_eq!(
            bytes % REPS,
            0,
            "n={n}: {bytes} bytes is not a clean per-call figure over {REPS} reps"
        );
        per_call.push((n, bytes / REPS));
    }

    // The unseen arm runs AFTER the sweep, at a row count no earlier call
    // used and smaller than the largest already seen. A monotonically grown
    // arena carves a prefix and allocates nothing extra; a cache keyed by row
    // count allocates here and only here.
    //
    // The divisibility check below is not decoration — skipping it (as an
    // earlier version of this arm did, dividing straight into `unseen_bytes`)
    // is a real gap: a ONE-OFF allocation smaller than `REPS` bytes on the
    // very first `UNSEEN` call — exactly what a row-count-keyed cache would
    // produce, populating its entry once and never again — truncates to zero
    // under plain integer division and reads as identical to `baseline`,
    // hiding precisely the defect this arm exists to catch.
    let (_, up, um) = unseen;
    let unseen_raw_bytes = measure(up, um, &ops);
    assert_eq!(
        unseen_raw_bytes % REPS,
        0,
        "n={UNSEEN}: {unseen_raw_bytes} bytes is not a clean per-call figure \
         over {REPS} reps"
    );
    let unseen_bytes = unseen_raw_bytes / REPS;

    let baseline = per_call[0].1;
    for &(n, bytes) in &per_call {
        assert_eq!(
            bytes, baseline,
            "per-call allocation moved with the row count: {per_call:?} — \
             {bytes} B at n={n} against {baseline} B at n={}. The old loop \
             allocated 2 * n_words * 8 per call and this is what forbids it",
            per_call[0].0
        );
    }
    assert_eq!(
        unseen_bytes, baseline,
        "n={UNSEEN} was never seen before and cost {unseen_bytes} B against \
         {baseline} B for every warmed size — allocation is following the \
         population's HISTORY, which is a cache keyed by row count, not a \
         monotonically grown arena"
    );

    // Can-it-fire. Scaling the OP count must move the figure, or "identical
    // across row counts" above would hold just as well for a counter that
    // never increments — the failure mode that makes an allocation gate
    // decorative.
    let (_, p, m) = handles[0];
    let mut wide = three_ops();
    for i in 0..29 {
        wide.push(desc(LGJ_OP_NE_I32, LANE_VALUES, i, LGJ_COMBINE_AND));
    }
    let wide_bytes = measure(p, m, &wide) / REPS;
    assert!(
        wide_bytes > baseline,
        "a 32-op plan allocated {wide_bytes} B against {baseline} B for a \
         3-op one: the instrument cannot see the lowering's own Vec<MaskOp>, \
         so the row-count invariance above proves nothing"
    );

    // What remains is proportional to the OP count, not the row count, and it
    // is small. The bound is measured, not chosen: re-measure before moving
    // it. Its purpose is to catch a future change that reintroduces a
    // row-sized buffer on a path this sweep happens not to reach.
    assert!(
        baseline <= 512,
        "a 3-op plan allocates {baseline} B per call; the lowering's op list \
         is the only thing that should be there"
    );

    // Printed, never silently pinned: the numbers above are the evidence for
    // every claim in this file, and `--nocapture` is how a future session
    // re-reads them instead of trusting this comment.
    println!("per-call bytes by row count: {per_call:?}");
    println!("n={UNSEEN} (never seen before): {unseen_bytes} B");
    println!("32-op plan: {wide_bytes} B (3-op: {baseline} B)");

    for &(_, p, m) in &handles {
        lgj_close(m);
        lgj_close(p);
    }
    lgj_close(unseen.2);
    lgj_close(unseen.1);
}

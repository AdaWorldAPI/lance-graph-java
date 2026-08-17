//! `lgj-abi` — the Rust half of the lance-graph-java **machine membrane**.
//!
//! The normative contract is `docs/abi.md`. This crate and the Java layer are
//! implemented *independently* against it; a runtime manifest check proves they
//! agree before the first real call.
//!
//! # There is no C in this project
//!
//! `extern "C"` and `#[repr(C)]` name a *machine* calling convention and a
//! *machine* aggregate layout rule — the SysV AMD64 psABI on this box. They do
//! not involve the C language. There is no `.h` file anywhere, no C toolchain,
//! no `cbindgen` (its output is a header nobody consumes), no `jextract` (its
//! only input is a header that does not exist), and no JNI. `cargo` and `javac`
//! are the entire toolchain.
//!
//! What replaces a header is [`abi::LgjAbiManifest`]: the compiled artifact
//! describing *itself* at runtime, from `size_of` / `align_of` on the real
//! types. A header can drift from the binary silently; a self-report cannot.
//!
//! # The three properties this crate exists to hold
//!
//! 1. **Bulk only** (`abi.md` §6). Every symbol either does work proportional to
//!    `n_rows` or is lifecycle. There is deliberately no `lgj_lane_read_element`
//!    — 64,000 logical entities cost one lane set, one packed mask and one
//!    crossing, not 64,000 of anything.
//! 2. **No stale handle ever dereferences freed memory** (§4). Handles are
//!    generation-checked opaque `u64`s, not pointers; use-after-close is
//!    `INVALID_HANDLE`, a status. [`registry`] is where that is enforced and
//!    where the tests attack it.
//! 3. **All SIMD comes from `ndarray::simd`** (§8). No `core::arch`, no
//!    `_mm*`, no `core::simd`, no `pulp`/`wide`/`SimSIMD`, no nightly, no local
//!    SIMD abstraction. [`kernels`] is the single module that names `ndarray`,
//!    and it also carries an *independently written* scalar reference so
//!    SIMD-vs-scalar parity is a real falsifier rather than a tautology.
//!
//! # Module map
//!
//! | module | role |
//! |---|---|
//! | [`abi`] | `#[repr(C)]` types, status codes, opcodes, the self-describing manifest |
//! | [`registry`] | generation-checked handles, ownership, lock discipline |
//! | [`fixture`] | the deterministic generic SoA fixture (three lanes) |
//! | [`kernels`] | bulk kernels via `ndarray::simd` + the independent scalar reference |
//! | [`exports`] | the `extern "C"` symbols themselves |

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(missing_docs)]

pub mod abi;
pub mod exports;
pub mod fixture;
pub mod kernels;
pub mod registry;

// Re-export the ABI vocabulary at the crate root for convenience in tests and
// for any Rust consumer that links the `rlib` rather than the `cdylib`.
pub use abi::*;
pub use exports::*;

#[cfg(test)]
mod integration_tests {
    //! Cross-module properties that belong to no single module.

    use crate::abi::*;
    use crate::exports::*;
    use crate::fixture::{Fixture, LANE_CLASSES, LANE_VALUES};

    /// Safe wrappers over the pointer-taking exports — see the identical note in
    /// `exports::tests`: one place holds the `unsafe`, so the assertions read as
    /// the ABI contract rather than as pointer plumbing.
    mod call {
        use super::*;

        pub fn pattern_open(n: u64, seed: u64, out: *mut u64) -> i32 {
            unsafe { lgj_pattern_open(n, seed, out) }
        }
        pub fn mask_create(p: u64, initial: u32, out: *mut u64) -> i32 {
            unsafe { lgj_mask_create(p, initial, out) }
        }
        pub fn mask_describe(m: u64, out: *mut LgjLaneDesc) -> i32 {
            unsafe { lgj_mask_describe(m, out) }
        }
        pub fn mask_count(m: u64, out: *mut u64) -> i32 {
            unsafe { lgj_mask_count(m, out) }
        }
        pub fn plan_eval(r: u64, ops: *const LgjOpDesc, n: u32, dst: u64, out: *mut u64) -> i32 {
            unsafe { lgj_plan_eval(r, ops, n, dst, out) }
        }
        pub fn reduce_sum_i32(r: u64, lane: u32, m: u64, out: *mut i64) -> i32 {
            unsafe { lgj_reduce_sum_i32(r, lane, m, out) }
        }
    }

    fn open(n: u64, seed: u64) -> u64 {
        let mut h = 0u64;
        assert_eq!(call::pattern_open(n, seed, &mut h), LGJ_OK);
        h
    }

    fn mask(parent: u64, initial: u32) -> u64 {
        let mut h = 0u64;
        assert_eq!(call::mask_create(parent, initial, &mut h), LGJ_OK);
        h
    }

    /// The thesis, end to end: 64,000 logical entities become one lane set, one
    /// packed mask, and **one** crossing that answers the whole question.
    ///
    /// Also the arithmetic that makes the thesis concrete — the mask is
    /// `n/8` bytes, not `n` objects.
    #[test]
    fn sixty_four_thousand_entities_cost_one_crossing() {
        let n = 64_000u64;
        let p = open(n, 2026);
        let m = mask(p, LGJ_MASK_INIT_EMPTY);

        let ops = [
            LgjOpDesc {
                op: LGJ_OP_EQ_U32,
                lane_id: LANE_CLASSES,
                operand: 7,
                combine: LGJ_COMBINE_AND,
                _reserved: 0,
            },
            LgjOpDesc {
                op: LGJ_OP_GT_I32,
                lane_id: LANE_VALUES,
                operand: 100,
                combine: LGJ_COMBINE_AND,
                _reserved: 0,
            },
        ];
        let mut count = 0u64;
        assert_eq!(call::plan_eval(p, ops.as_ptr(), 2, m, &mut count), LGJ_OK);

        // Independently computed from the fixture's documented generator — the
        // same computation the Java test performs from `abi.md` alone.
        let f = Fixture::generate(n, 2026).unwrap();
        let want = f
            .classes()
            .iter()
            .zip(f.values())
            .filter(|(&c, &v)| c == 7 && v > 100)
            .count() as u64;
        assert_eq!(count, want);
        assert!(count > 0 && count < n, "must not be a vacuous selection");

        // The whole selection is 1000 u64 words = 8000 bytes, for 64,000 rows.
        let mut d = LgjLaneDesc::default();
        assert_eq!(call::mask_describe(m, &mut d), LGJ_OK);
        assert_eq!(d.len_elems, 1000);
        assert_eq!(d.byte_len, 8000);

        let mut sum = 0i64;
        assert_eq!(call::reduce_sum_i32(p, LANE_VALUES, m, &mut sum), LGJ_OK);
        let want_sum: i64 = f
            .classes()
            .iter()
            .zip(f.values())
            .filter(|(&c, &v)| c == 7 && v > 100)
            .map(|(_, &v)| v as i64)
            .sum();
        assert_eq!(sum, want_sum);

        lgj_close(m);
        lgj_close(p);
    }

    /// Many live resources at once, closed out of order — the registry must not
    /// confuse them, and every handle must stay valid until *its own* close.
    #[test]
    fn interleaved_lifetimes_do_not_cross_talk() {
        let mut patterns = Vec::new();
        let mut masks = Vec::new();
        for i in 0..16u64 {
            let p = open(100 + i, i);
            masks.push(mask(p, LGJ_MASK_INIT_ALL));
            patterns.push(p);
        }
        // Close every other pattern; the survivors must be unaffected.
        for i in (0..16).step_by(2) {
            assert_eq!(lgj_close(masks[i]), LGJ_OK);
            assert_eq!(lgj_close(patterns[i]), LGJ_OK);
        }
        for i in (1..16).step_by(2) {
            let mut c = 0u64;
            assert_eq!(call::mask_count(masks[i], &mut c), LGJ_OK);
            assert_eq!(c, 100 + i as u64);
        }
        for i in (0..16).step_by(2) {
            let mut c = 0u64;
            assert_eq!(call::mask_count(masks[i], &mut c), LGJ_ERR_INVALID_HANDLE);
        }
        for i in (1..16).step_by(2) {
            assert_eq!(lgj_close(masks[i]), LGJ_OK);
            assert_eq!(lgj_close(patterns[i]), LGJ_OK);
        }
    }

    /// Bulk ops on distinct resources are meant to run concurrently (§4). This
    /// does not benchmark contention — `abi.md` is explicit that nothing here
    /// has been — it proves the *shape* is sound: no deadlock, no cross-talk,
    /// each thread's answer independently correct.
    #[test]
    fn concurrent_bulk_ops_on_distinct_resources_agree_with_serial_ones() {
        use std::thread;
        let handles: Vec<_> = (0..8u64)
            .map(|t| {
                thread::spawn(move || {
                    let n = 5000 + t;
                    let p = open(n, t);
                    let m = mask(p, LGJ_MASK_INIT_EMPTY);
                    let ops = [LgjOpDesc {
                        op: LGJ_OP_EQ_U32,
                        lane_id: LANE_CLASSES,
                        operand: 7,
                        combine: LGJ_COMBINE_AND,
                        _reserved: 0,
                    }];
                    let mut c = 0u64;
                    assert_eq!(call::plan_eval(p, ops.as_ptr(), 1, m, &mut c), LGJ_OK);
                    lgj_close(m);
                    lgj_close(p);
                    (n, t, c)
                })
            })
            .collect();
        for h in handles {
            let (n, seed, got) = h.join().unwrap();
            let f = Fixture::generate(n, seed).unwrap();
            let want = f.classes().iter().filter(|&&c| c == 7).count() as u64;
            assert_eq!(got, want, "thread for seed {seed} disagreed");
        }
    }

    /// Concurrent mask binops that name the same masks in *opposite* orders —
    /// the shape that would deadlock without address-ordered locking.
    #[test]
    fn opposite_order_mask_binops_do_not_deadlock() {
        use std::sync::Arc;
        use std::thread;
        let p = open(4096, 1);
        let a = mask(p, LGJ_MASK_INIT_ALL);
        let b = mask(p, LGJ_MASK_INIT_ALL);
        let c = mask(p, LGJ_MASK_INIT_EMPTY);
        let barrier = Arc::new(std::sync::Barrier::new(2));

        let b1 = Arc::clone(&barrier);
        let t1 = thread::spawn(move || {
            b1.wait();
            for _ in 0..2000 {
                assert_eq!(lgj_mask_and(a, b, c), LGJ_OK);
            }
        });
        let b2 = Arc::clone(&barrier);
        let t2 = thread::spawn(move || {
            b2.wait();
            for _ in 0..2000 {
                assert_eq!(lgj_mask_or(c, b, a), LGJ_OK);
            }
        });
        t1.join().unwrap();
        t2.join().unwrap();

        for h in [c, b, a, p] {
            lgj_close(h);
        }
    }
}

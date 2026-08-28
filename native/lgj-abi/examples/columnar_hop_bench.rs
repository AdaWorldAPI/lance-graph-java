//! The landing measurement: `lgj_hop` through the REAL ABI on the SAME
//! logical content under both layouts — the shipped export, both store
//! constructors, equivalence asserted before timing.
//!
//! Frontier arms are built through the ABI itself (no test back door):
//! a classid-predicate frontier (~1/16 of rows), the full population, and
//! the second hop of a chain (a REAL BFS frontier shape).
//!
//! `cargo run --release --example columnar_hop_bench`

use lgj_abi::exports::*;
use std::time::Instant;

const LGJ_MASK_INIT_ALL: u32 = 1;
const LGJ_MASK_INIT_EMPTY: u32 = 0;

fn median(mut xs: Vec<f64>) -> f64 {
    xs.sort_by(|a, b| a.partial_cmp(b).unwrap());
    xs[xs.len() / 2]
}

fn mask_of(store: u64, init: u32) -> u64 {
    let mut h = 0u64;
    unsafe { assert_eq!(lgj_mask_create(store, init, &mut h), 0) };
    h
}

fn count_of(m: u64) -> u64 {
    let mut c = 0u64;
    unsafe { assert_eq!(lgj_mask_count(m, &mut c), 0) };
    c
}

/// Build the named frontier, time `reps` hops out of it, return (µs, |dst|, |src|).
fn run(store: u64, arm: &str, reps: usize) -> (f64, u64, u64) {
    let src = match arm {
        "classid" => {
            let m = mask_of(store, LGJ_MASK_INIT_EMPTY);
            assert_eq!(lgj_op_eq_classid(store, 0, 9, m), 0);
            m
        }
        "all" => mask_of(store, LGJ_MASK_INIT_ALL),
        "hop2" => {
            let seed = mask_of(store, LGJ_MASK_INIT_EMPTY);
            assert_eq!(lgj_op_eq_classid(store, 0, 9, seed), 0);
            let first = mask_of(store, LGJ_MASK_INIT_EMPTY);
            assert_eq!(lgj_hop(store, 0, 0xFFFF_FFFF, 0, seed, first), 0);
            lgj_close(seed);
            first
        }
        _ => unreachable!(),
    };
    let dst = mask_of(store, LGJ_MASK_INIT_EMPTY);
    let mut times = Vec::new();
    for _ in 0..reps {
        let t0 = Instant::now();
        assert_eq!(lgj_hop(store, 0, 0xFFFF_FFFF, 0, src, dst), 0);
        times.push(t0.elapsed().as_secs_f64() * 1e6);
    }
    let out = (median(times), count_of(dst), count_of(src));
    lgj_close(dst);
    lgj_close(src);
    out
}

fn main() {
    let n: u64 = 65_536;
    let reps = 7;

    let mut aos = 0u64;
    let mut col = 0u64;
    unsafe {
        assert_eq!(
            lgj_rowstore_open_with_edges(n, 0xF00D_CAFE, 0, 0x0, 25, &mut aos),
            0
        );
        assert_eq!(
            lgj_rowstore_open_columnar(n, 0xF00D_CAFE, 0, 0x0, 25, &mut col),
            0
        );
    }

    println!("lgj_hop through the ABI, n_rows={n}, all 32 facets, median of {reps}");
    println!(
        "{:>9} {:>9} {:>12} {:>12} {:>8}",
        "arm", "|src|", "aos_us", "columnar_us", "speedup"
    );
    for arm in ["classid", "hop2", "all"] {
        let (ta, ca, sa) = run(aos, arm, reps);
        let (tc, cc, sc) = run(col, arm, reps);
        assert_eq!(sa, sc, "{arm}: src populations differ");
        assert_eq!(ca, cc, "{arm}: layouts disagree");
        assert!(ca > 0, "{arm}: vacuous hop");
        println!(
            "{:>9} {:>9} {:>12.1} {:>12.1} {:>7.1}x",
            arm,
            sa,
            ta,
            tc,
            ta / tc
        );
    }
    lgj_close(col);
    lgj_close(aos);
}

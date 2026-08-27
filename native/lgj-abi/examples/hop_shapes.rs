//! Measures the GATHER-vs-SWEEP density crossover for `lgj_hop`'s inner shape.
//!
//! Opened by `ISS-LGJ-HOP-SWEEPS-FULL-POPULATION`, which bench Component G
//! resolved IN PART: the one-pass rewrite removed the catastrophic term (32
//! full-width sweeps per hop -> 1) but left a structural one. The compare half
//! still answers EVERY row before intersecting with `src`, so at a 1 % frontier
//! the native hop was still ~43x the best scalar arm.
//!
//! The named next rung was a different shape entirely:
//!
//!   SWEEP  (shipped)  one `simd_rowstore_facet_match` pass over all n rows,
//!                     then walk src's set bits.            O(population)
//!   GATHER (candidate) for each set row in src, read that row's 32 facets
//!                     directly and decode.                 O(frontier)
//!
//! Gather is NOT obviously better and that is the whole point: at a dense
//! frontier the sweep's sequential, vectorised access should beat a scattered
//! per-row gather. So there is a real crossover, and the repo's rule is that a
//! placement follows a measurement rather than taste (spec §3.8). This probe
//! finds where it sits instead of guessing.
//!
//! Deliberately measured in Rust rather than through JMH: the question is which
//! inner loop is faster, and routing it through the FFM crossing plus a mask
//! allocation adds noise that belongs to a different question.
//!
//! `cargo run --release --manifest-path native/lgj-abi/Cargo.toml \
//!      --example hop_gather_vs_sweep`

use lgj_abi::kernels;
use lgj_abi::rowstore::{RowStore, FACET_BYTES, ROW_BYTES, ROW_FACETS};
use std::time::Instant;

const EDGE_CLASSID: u32 = 0;
const SEED: u64 = 0xF00D_CAFE;
const GATE_MASK: u64 = 0x0;
const RADIUS: u32 = 25;

/// Reps per configuration; the median is reported. Enough to step over a
/// scheduler hiccup on a shared container without making the sweep take
/// minutes.
const REPS: usize = 7;

/// Decode one facet of one row into `out`, shared by BOTH shapes so the two
/// arms cannot drift in what they consider an edge.
#[inline]
fn decode_into(bytes: &[u8], n_rows: u64, row: u64, facet: u32, out: &mut [u64]) {
    let base = (row * ROW_BYTES + u64::from(facet) * FACET_BYTES) as usize;
    let payload_hi32 = u32::from_le_bytes(bytes[base + 12..base + 16].try_into().unwrap());
    if payload_hi32 != 0 {
        return; // not a structured edge
    }
    let target = u64::from_le_bytes(bytes[base + 4..base + 12].try_into().unwrap());
    if target < n_rows {
        let t = target as usize;
        out[t / 64] |= 1u64 << (t % 64);
    }
}

/// SWEEP — the shipped shape (`lgj_hop` as of the one-pass rewrite).
fn hop_sweep(store: &RowStore, src: &[u64], effective: u32, n_words: usize) -> Vec<u64> {
    let n = store.n_rows as usize;
    let bytes = store.as_bytes();
    let mut out = vec![0u64; n_words];

    let mut facet_bits = vec![0u32; n];
    kernels::simd_rowstore_facet_match(&store.bytes_arc(), n, EDGE_CLASSID, &mut facet_bits);

    for (w, &sw) in src.iter().enumerate() {
        let mut bits = sw;
        while bits != 0 {
            let bit = bits.trailing_zeros();
            bits &= bits - 1;
            let row = (w as u64) * 64 + u64::from(bit);
            if row >= store.n_rows {
                continue;
            }
            let mut fb = facet_bits[row as usize] & effective;
            while fb != 0 {
                let facet = fb.trailing_zeros();
                fb &= fb - 1;
                decode_into(bytes, store.n_rows, row, facet, &mut out);
            }
        }
    }
    out
}

/// GATHER — the candidate. Never touches a row `src` does not name.
fn hop_gather(store: &RowStore, src: &[u64], effective: u32, n_words: usize) -> Vec<u64> {
    let bytes = store.as_bytes();
    let mut out = vec![0u64; n_words];

    for (w, &sw) in src.iter().enumerate() {
        let mut bits = sw;
        while bits != 0 {
            let bit = bits.trailing_zeros();
            bits &= bits - 1;
            let row = (w as u64) * 64 + u64::from(bit);
            if row >= store.n_rows {
                continue;
            }
            let row_base = (row * ROW_BYTES) as usize;
            for facet in 0..ROW_FACETS {
                if (effective >> facet) & 1 == 0 {
                    continue;
                }
                let fbase = row_base + facet as usize * FACET_BYTES as usize;
                let classid = u32::from_le_bytes(bytes[fbase..fbase + 4].try_into().unwrap());
                if classid != EDGE_CLASSID {
                    continue;
                }
                decode_into(bytes, store.n_rows, row, facet, &mut out);
            }
        }
    }
    out
}

/// MASK ALGEBRA — the shipped shape (R1). Selection is
/// `src ∧ class_f ∧ struct_f`, word-parallel; both predicates are the SAME
/// strided-equality primitive at two offsets into the facet. Nothing decides
/// per row whether a row takes part — the walk only EMITS from the result.
fn hop_mask_algebra(store: &RowStore, src: &[u64], effective: u32, n_words: usize) -> Vec<u64> {
    let bytes = store.as_bytes();
    let n = store.n_rows as usize;
    let mut out = vec![0u64; n_words];
    let mut selected = vec![0u64; n_words];
    let mut structured = vec![0u64; n_words];

    for facet in 0..lgj_abi::rowstore::ROW_FACETS {
        if (effective >> facet) & 1 == 0 {
            continue;
        }
        let off = facet as usize * lgj_abi::rowstore::FACET_BYTES as usize;
        kernels::simd_rowstore_u32_eq_mask(bytes, off, n, EDGE_CLASSID, &mut selected);
        kernels::simd_mask_and_assign(&mut selected, src);
        kernels::simd_rowstore_u32_eq_mask(
            bytes,
            off + lgj_abi::rowstore::FACET_PAYLOAD_HI32_OFFSET as usize,
            n,
            0,
            &mut structured,
        );
        kernels::simd_mask_and_assign(&mut selected, &structured);

        for (w, &sw) in selected.iter().enumerate() {
            let mut bits = sw;
            while bits != 0 {
                let bit = bits.trailing_zeros();
                bits &= bits - 1;
                let row = (w as u64) * 64 + u64::from(bit);
                if row >= store.n_rows {
                    continue;
                }
                decode_into(bytes, store.n_rows, row, facet, &mut out);
            }
        }
    }
    out
}

/// COLUMNAR — R2 as a lab measurement (R11's precedent: measure the layout
/// before changing the store).
///
/// Same bytes, different ORDER. A row's 512 bytes are three fields × 32
/// facets; laying them out field-major makes the whole `(row × facet)` plane
/// one contiguous canvas per field. Then every predicate is ONE pass over a
/// contiguous `u32` column — no stride at all — instead of 32 strided passes
/// per predicate.
///
/// Built once here because a columnar STORE would build it at generation.
/// The build is timed separately; it is not per-hop work.
struct Columnar {
    classid: Vec<u32>, // [row * 32 + facet]
    hi32: Vec<u32>,
    lo64: Vec<u64>,
}

impl Columnar {
    fn of(store: &RowStore) -> Self {
        let n = store.n_rows as usize;
        let f = ROW_FACETS as usize;
        let bytes = store.as_bytes();
        let mut classid = vec![0u32; n * f];
        let mut hi32 = vec![0u32; n * f];
        let mut lo64 = vec![0u64; n * f];
        for row in 0..n {
            for facet in 0..f {
                let b = row * ROW_BYTES as usize + facet * FACET_BYTES as usize;
                let i = row * f + facet;
                classid[i] = u32::from_le_bytes(bytes[b..b + 4].try_into().unwrap());
                lo64[i] = u64::from_le_bytes(bytes[b + 4..b + 12].try_into().unwrap());
                hi32[i] = u32::from_le_bytes(bytes[b + 12..b + 16].try_into().unwrap());
            }
        }
        Self {
            classid,
            hi32,
            lo64,
        }
    }
}

/// The hop over the `(row × facet)` bit-plane. Four mask operands, three ANDs,
/// one walk of the RESULT — and not one of the operands is built by looking at
/// which rows were selected.
fn hop_columnar(
    col: &Columnar,
    store: &RowStore,
    src: &[u64],
    effective: u32,
    n_words: usize,
) -> Vec<u64> {
    let n_rows = store.n_rows;
    let slots = col.classid.len(); // n_rows * 32
    let slot_words = slots.div_ceil(64);

    let mut selected = vec![0u64; slot_words];
    let mut structured = vec![0u64; slot_words];

    // class — ONE contiguous pass over the whole plane.
    kernels::simd_eq_u32_to_mask(&col.classid, EDGE_CLASSID, &mut selected);
    // struct — ONE more.
    kernels::simd_eq_u32_to_mask(&col.hi32, 0, &mut structured);
    kernels::simd_mask_and_assign(&mut selected, &structured);

    // participation — PERIODIC, so the operand is one repeated word rather
    // than a buffer: 64 slots per word = exactly 2 rows at 32 facets.
    let part_word = (effective as u64) | ((effective as u64) << 32);
    // src, expanded 1 row-bit -> 32 slot-bits. Word w covers rows 2w, 2w+1.
    for (w, sel) in selected.iter_mut().enumerate() {
        let r0 = 2 * w as u64;
        let r1 = r0 + 1;
        let lo = if r0 < n_rows && (src[(r0 / 64) as usize] >> (r0 % 64)) & 1 == 1 {
            0xFFFF_FFFFu64
        } else {
            0
        };
        let hi = if r1 < n_rows && (src[(r1 / 64) as usize] >> (r1 % 64)) & 1 == 1 {
            0xFFFF_FFFF_0000_0000u64
        } else {
            0
        };
        *sel &= part_word & (lo | hi);
    }

    // Emit. Every set bit is a (row, facet) edge slot that already satisfied
    // every predicate; the walk decides nothing.
    let mut out = vec![0u64; n_words];
    for (w, &sw) in selected.iter().enumerate() {
        let mut bits = sw;
        while bits != 0 {
            let bit = bits.trailing_zeros();
            bits &= bits - 1;
            let slot = w * 64 + bit as usize;
            if slot >= slots {
                continue;
            }
            let target = col.lo64[slot];
            if target < n_rows {
                let t = target as usize;
                out[t / 64] |= 1u64 << (t % 64);
            }
        }
    }
    out
}

/// A frontier of `count` rows spread across the whole population by stride —
/// deliberately scattered, because a real BFS frontier after one hop is. A
/// contiguous prefix would hand `gather` an unrepresentatively cache-friendly
/// access order and move the crossover in its favour.
fn spread_frontier(n_rows: u64, count: u64, n_words: usize) -> Vec<u64> {
    let mut src = vec![0u64; n_words];
    let stride = (n_rows / count).max(1);
    for i in 0..count {
        let row = (i * stride) % n_rows;
        src[(row / 64) as usize] |= 1u64 << (row % 64);
    }
    src
}

fn median(mut xs: Vec<f64>) -> f64 {
    xs.sort_by(|a, b| a.partial_cmp(b).unwrap());
    xs[xs.len() / 2]
}

fn time_us(mut f: impl FnMut()) -> f64 {
    // One untimed warm-up: first touch faults the scratch pages in, and that
    // cost belongs to neither shape.
    f();
    let mut runs = Vec::with_capacity(REPS);
    for _ in 0..REPS {
        let t0 = Instant::now();
        f();
        runs.push(t0.elapsed().as_secs_f64() * 1e6);
    }
    median(runs)
}

fn main() {
    let effective: u32 = 0xFFFF_FFFF; // all 32 facets participate
    println!(
        "hop shapes — spread frontier, edge_classid={EDGE_CLASSID}, \
         gate=0x{GATE_MASK:x}, radius={RADIUS}, reps={REPS} (median)\n"
    );

    for &n_rows in &[1_024u64, 4_096, 16_384, 65_536, 262_144] {
        let store = RowStore::generate_with_edges(n_rows, SEED, EDGE_CLASSID, GATE_MASK, RADIUS)
            .expect("fixture generation");
        let n_words = (n_rows as usize).div_ceil(64);
        let col = Columnar::of(&store);

        println!("== n_rows = {n_rows} ==");
        println!(
            "{:>10} {:>9} {:>12} {:>12} {:>12} {:>12}",
            "frontier", "density", "sweep_us", "gather_us", "mask_us", "colmn_us"
        );

        for &pct in &[
            0.01f64, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0,
        ] {
            let count = (((n_rows as f64) * pct / 100.0) as u64).max(1);
            let src = spread_frontier(n_rows, count, n_words);

            // EQUIVALENCE FIRST. A benchmark comparing two shapes that compute
            // different answers measures nothing, so this is asserted at every
            // configuration rather than spot-checked once.
            let a = hop_sweep(&store, &src, effective, n_words);
            let b = hop_gather(&store, &src, effective, n_words);
            assert_eq!(a, b, "shapes disagree at n_rows={n_rows} pct={pct}");
            // Anti-vacuity: an empty hop would let both agree on nothing and
            // would time two empty loops.
            let popcount: u32 = a.iter().map(|w| w.count_ones()).sum();
            assert!(popcount > 0, "empty hop at n_rows={n_rows} pct={pct}");

            let sweep = time_us(|| {
                std::hint::black_box(hop_sweep(&store, &src, effective, n_words));
            });
            let gather = time_us(|| {
                std::hint::black_box(hop_gather(&store, &src, effective, n_words));
            });

            let mask = time_us(|| {
                std::hint::black_box(hop_mask_algebra(&store, &src, effective, n_words));
            });
            assert_eq!(
                hop_mask_algebra(&store, &src, effective, n_words),
                a,
                "mask algebra disagrees at n_rows={n_rows} pct={pct}"
            );

            let colmn = time_us(|| {
                std::hint::black_box(hop_columnar(&col, &store, &src, effective, n_words));
            });
            assert_eq!(
                hop_columnar(&col, &store, &src, effective, n_words),
                a,
                "columnar disagrees at n_rows={n_rows} pct={pct}"
            );

            println!(
                "{:>10} {:>8.2}% {:>12.1} {:>12.1} {:>12.1} {:>12.1}",
                count, pct, sweep, gather, mask, colmn
            );
        }
        println!();
    }
}

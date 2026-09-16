//! CACHED mask vs GATHER — the hop's selection at each shape's best layout.
//!
//! Three shapes answer `dst = ⋁_f scatter(src ∧ class_f ∧ struct_f)`:
//!
//! | arm | per hop | layout |
//! |---|---|---|
//! | `recompute` | the shipped `lgj_hop` body: per facet, two contiguous `eq_u32` passes + one `ternlog<AND3>` + scatter | FacetMajor |
//! | `cached` | `sel_f = class_f ∧ struct_f` computed ONCE per store generation (32 masks × 8 KiB = 256 KiB at 65 536 rows); per hop one `mask_and` + scatter | FacetMajor |
//! | `gather` | walk `src`'s set bits; per row read its 32 facets in place (PR #40's shape, the population serialization R1 ruled out) | AosRows (its best case) |
//!
//! The cache is the M1b tile (`gemm-ternlog-mask-consolidation-v1.md` §9): keyed
//! on `(store generation, edge_classid)`, invalidated wholesale like
//! `cached_carving`. Its build is timed separately and reported as a break-even
//! hop count against `recompute`, because a cache that pays off only after more
//! hops than a store generation ever sees is a memo, not an amortization.
//!
//! Every arm is asserted bit-identical on every frontier before timing.
//!
//! # Measured 2026-09-16 — Xeon @ 2.10 GHz, `avx512f=true`, release, median of 7, 2 runs
//!
//! Every output buffer is `black_box`ed inside its timed closure (Codex P1 on
//! #79: with `()`-returning closures and outputs never read, LLVM may drop the
//! scatter stores; re-measured with them observed — the numbers held).
//!
//! Cache build (32 × `sel_f`): **843–930 µs**, 256 KiB resident.
//!
//! | frontier | src | dst | recompute µs | **cached µs** | gather µs (scalar walk) | break-even |
//! |---|---:|---:|---:|---:|---:|---:|
//! | rand7 | 7 | 11 | 728–782 | **14.4–15.0** | 0.8 | 1.2 hops |
//! | rand655 | 655 | 1 279 | 855–864 | **31.8–33.0** | 34.7–36.1 | 1.0–1.1 hops |
//! | classid | 3 933 | 6 943 | 947–954 | **96–100** | 462–632 | 1.0–1.1 hops |
//! | hop2 | 6 943 | 12 125 | 1 019–1 102 | **159–196** | 705–758 | 0.9–1.1 hops |
//! | all | 65 536 | 56 841 | 1 630–1 779 | **903–1 056** | 3 583–3 863 | 1.2–1.3 hops |
//!
//! **The cached tile pays for itself on the second hop** (break-even 1.0–1.4
//! hops at every frontier): a store generation's predicates do not change
//! between hops, and the shipped body re-derives them with 64 contiguous
//! 256 KiB passes per hop. What remains per hop is one 8 KiB `mask_and` per
//! facet (≤ 5 µs) plus the scatter, which is `O(N/64 + selected bits)` per
//! facet — it walks every word of `selected` before it can know which are
//! empty (the same word-walk floor `ternlogq_sparse_reapply_probe` measured,
//! ~360 ns at 1 024 words), then one payload read per set bit — and is the
//! whole cost above ~1 %.
//!
//! The `gather` column is NOT a competitor to the mask (R1 ruled the
//! population serialization out); it is the baseline for the *access shape*
//! the scatter walk has — random, one row at a time, 64 scalar loads and 64
//! compares per visited row. The open question it sets up (operator,
//! 2026-09-16): inside that random-access walk, use the op that matches the
//! access granularity — one zmm `mask_cmpeq_epi32` per 4 facets to decide all
//! 32 facets of a visited row in 8 loads, or one xmm compare per facet —
//! rather than a scalar loop. Arms `gather_xmm` / `gather_zmm_row` are the
//! next measurement; not in this file yet.
//!
//! `cargo run --release --example hop_cached_vs_gather`

use lgj_abi::kernels;
use lgj_abi::rowstore::{RowLayout, RowStore, FACET_BYTES, ROW_BYTES, ROW_FACETS};
use std::hint::black_box;
use std::time::Instant;

const N: u64 = 65_536;
const SEED: u64 = 0xF00D_CAFE;
const EDGE_CLASSID: u32 = 0;
const RADIUS: u32 = 25;
const REPS: usize = 7;

fn median(mut xs: Vec<f64>) -> f64 {
    xs.sort_by(|a, b| a.partial_cmp(b).unwrap());
    xs[xs.len() / 2]
}

fn time_us(mut f: impl FnMut()) -> f64 {
    let mut ts = Vec::with_capacity(REPS);
    for _ in 0..REPS {
        let t0 = Instant::now();
        f();
        ts.push(t0.elapsed().as_secs_f64() * 1e6);
    }
    median(ts)
}

fn splitmix(s: &mut u64) -> u64 {
    *s = s.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut z = *s;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

#[inline]
fn emit(bytes: &[u8], layout: RowLayout, row: u64, facet: u32, out: &mut [u64]) {
    let lo = layout.lo64_offset(N, row, facet);
    let target = u64::from_le_bytes(bytes[lo..lo + 8].try_into().unwrap());
    if target < N {
        out[(target / 64) as usize] |= 1u64 << (target % 64);
    }
}

fn scatter(bytes: &[u8], layout: RowLayout, selected: &[u64], facet: u32, out: &mut [u64]) {
    for (w, &sw) in selected.iter().enumerate() {
        let mut bits = sw;
        while bits != 0 {
            let b = bits.trailing_zeros();
            bits &= bits - 1;
            emit(bytes, layout, (w as u64) * 64 + u64::from(b), facet, out);
        }
    }
}

/// The shipped body of `lgj_hop` (FacetMajor): recompute both predicates per hop.
fn hop_recompute(fm: &RowStore, src: &[u64], out: &mut [u64], sel: &mut [u64], st: &mut [u64]) {
    let bytes = fm.as_bytes();
    let n = N as usize;
    out.fill(0);
    for facet in 0..ROW_FACETS {
        let (c_off, c_stride) = fm.layout.classid_lane(N, facet);
        let (h_off, h_stride) = fm.layout.hi32_lane(N, facet);
        kernels::simd_rowstore_u32_eq_mask(bytes, c_off, c_stride, n, EDGE_CLASSID, sel);
        kernels::simd_rowstore_u32_eq_mask(bytes, h_off, h_stride, n, 0, st);
        kernels::simd_mask_ternlog_assign::<{ kernels::ternlog::AND3 }>(sel, src, st);
        scatter(bytes, fm.layout, sel, facet, out);
    }
}

/// The M1b tile: `sel_f = class_f ∧ struct_f`, once per store generation.
fn build_cache(fm: &RowStore, n_words: usize) -> Vec<Vec<u64>> {
    let bytes = fm.as_bytes();
    let n = N as usize;
    let mut st = vec![0u64; n_words];
    (0..ROW_FACETS)
        .map(|facet| {
            let mut sel = vec![0u64; n_words];
            let (c_off, c_stride) = fm.layout.classid_lane(N, facet);
            let (h_off, h_stride) = fm.layout.hi32_lane(N, facet);
            kernels::simd_rowstore_u32_eq_mask(bytes, c_off, c_stride, n, EDGE_CLASSID, &mut sel);
            kernels::simd_rowstore_u32_eq_mask(bytes, h_off, h_stride, n, 0, &mut st);
            kernels::simd_mask_and_assign(&mut sel, &st);
            sel
        })
        .collect()
}

fn hop_cached(fm: &RowStore, cache: &[Vec<u64>], src: &[u64], out: &mut [u64], sel: &mut [u64]) {
    let bytes = fm.as_bytes();
    out.fill(0);
    for (facet, sel_f) in cache.iter().enumerate() {
        kernels::simd_mask_and(src, sel_f, sel);
        scatter(bytes, fm.layout, sel, facet as u32, out);
    }
}

/// PR #40's shape on its natural layout: walk src, read the row's facets in place.
fn hop_gather(aos: &RowStore, src: &[u64], out: &mut [u64]) {
    let bytes = aos.as_bytes();
    out.fill(0);
    for (w, &sw) in src.iter().enumerate() {
        let mut bits = sw;
        while bits != 0 {
            let b = bits.trailing_zeros();
            bits &= bits - 1;
            let row = (w as u64) * 64 + u64::from(b);
            let rb = (row * ROW_BYTES) as usize;
            for facet in 0..ROW_FACETS {
                let fb = rb + facet as usize * FACET_BYTES as usize;
                if u32::from_le_bytes(bytes[fb..fb + 4].try_into().unwrap()) != EDGE_CLASSID {
                    continue;
                }
                if u32::from_le_bytes(bytes[fb + 12..fb + 16].try_into().unwrap()) != 0 {
                    continue;
                }
                emit(bytes, aos.layout, row, facet, out);
            }
        }
    }
}

fn main() {
    let n_words = (N / 64) as usize;
    let fm =
        RowStore::generate_with_edges_in(N, SEED, EDGE_CLASSID, 0x0, RADIUS, RowLayout::FacetMajor)
            .expect("store");
    let aos =
        RowStore::generate_with_edges_in(N, SEED, EDGE_CLASSID, 0x0, RADIUS, RowLayout::AosRows)
            .expect("store");
    assert_eq!(fm.layout, RowLayout::FacetMajor);

    println!(
        "realization: avx512f={}  n_rows={N} facets={ROW_FACETS} median of {REPS}",
        cfg!(target_feature = "avx512f")
    );

    // Cache build, once per store generation.
    let t_build = time_us(|| {
        black_box(build_cache(black_box(&fm), n_words));
    });
    let cache = build_cache(&fm, n_words);
    println!(
        "cache build (32 × sel_f): {t_build:.1} µs, {} KiB resident",
        cache.len() * n_words * 8 / 1024
    );

    // Frontiers: sparse random, 1 %, the classid predicate (~1/16), hop2, all.
    let mut seed = 0x51EDu64;
    let mut frontiers: Vec<(String, Vec<u64>)> = Vec::new();
    for &live in &[7usize, 655] {
        let mut m = vec![0u64; n_words];
        let mut placed = 0;
        while placed < live {
            let r = splitmix(&mut seed) as usize;
            let (w, b) = (r % n_words, (r >> 20) % 64);
            if m[w] >> b & 1 == 0 {
                m[w] |= 1 << b;
                placed += 1;
            }
        }
        frontiers.push((format!("rand{live}"), m));
    }
    let mut classid9 = vec![0u64; n_words];
    let (c0, s0) = fm.layout.classid_lane(N, 0);
    kernels::simd_rowstore_u32_eq_mask(fm.as_bytes(), c0, s0, N as usize, 9, &mut classid9);
    let mut hop2 = vec![0u64; n_words];
    {
        let (mut s, mut t) = (vec![0u64; n_words], vec![0u64; n_words]);
        hop_recompute(&fm, &classid9, &mut hop2, &mut s, &mut t);
    }
    frontiers.push(("classid".into(), classid9));
    frontiers.push(("hop2".into(), hop2));
    frontiers.push(("all".into(), vec![u64::MAX; n_words]));

    println!(
        "\n{:<9} {:>7} {:>7} | {:>11} {:>10} {:>10} | {:<28}",
        "frontier",
        "|src|",
        "|dst|",
        "recompute_us",
        "cached_us",
        "gather_us",
        "cached vs (recompute, gather)"
    );
    let (mut out_r, mut out_c, mut out_g) = (
        vec![0u64; n_words],
        vec![0u64; n_words],
        vec![0u64; n_words],
    );
    let (mut sel, mut st) = (vec![0u64; n_words], vec![0u64; n_words]);
    for (name, src) in &frontiers {
        hop_recompute(&fm, src, &mut out_r, &mut sel, &mut st);
        hop_cached(&fm, &cache, src, &mut out_c, &mut sel);
        hop_gather(&aos, src, &mut out_g);
        assert_eq!(out_r, out_c, "{name}: cached diverges");
        assert_eq!(out_r, out_g, "{name}: gather diverges");
        let n_src = kernels::simd_popcount(src);
        let n_dst = kernels::simd_popcount(&out_r);
        assert!(n_dst > 0, "{name}: vacuous hop");
        let t_r = time_us(|| {
            hop_recompute(
                black_box(&fm),
                black_box(src),
                &mut out_r,
                &mut sel,
                &mut st,
            );
            black_box(&out_r);
        });
        let t_c = time_us(|| {
            hop_cached(
                black_box(&fm),
                black_box(&cache),
                black_box(src),
                &mut out_c,
                &mut sel,
            );
            black_box(&out_c);
        });
        let t_g = time_us(|| {
            hop_gather(black_box(&aos), black_box(src), &mut out_g);
            black_box(&out_g);
        });
        println!(
            "{:<9} {:>7} {:>7} | {:>11.1} {:>10.1} {:>10.1} | {:.1}x, {:.2}x   break-even {:.1} hops",
            name, n_src, n_dst, t_r, t_c, t_g, t_r / t_c, t_g / t_c, t_build / (t_r - t_c).max(1e-9)
        );
    }
}

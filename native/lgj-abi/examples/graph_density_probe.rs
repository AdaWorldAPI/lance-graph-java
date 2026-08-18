//! Measures real 1-hop/2-hop set sizes for `RowStore::generate_with_edges` over
//! candidate `(edge_gate_mask, edge_radius)` combinations, so W5c's fixture
//! parameters are picked from numbers, not a guess.
//!
//! `cargo run --release --manifest-path native/lgj-abi/Cargo.toml --example graph_density_probe`

use lgj_abi::rowstore::{RowStore, ROW_FACETS};

const EDGE_CLASSID: u32 = 0;
const SEED: u64 = 0xF00D_CAFE;

fn hop(store: &RowStore, from: &[u32], edge_classid: u32) -> Vec<u32> {
    let n = store.n_rows as usize;
    let mut seen = vec![false; n];
    let mut out = Vec::new();
    for &row in from {
        let base = row as usize * 512;
        for facet in 0..ROW_FACETS as usize {
            let fbase = base + facet * 16;
            let classid =
                u32::from_le_bytes(store.as_bytes()[fbase..fbase + 4].try_into().unwrap());
            if classid != edge_classid {
                continue;
            }
            let target =
                u64::from_le_bytes(store.as_bytes()[fbase + 4..fbase + 12].try_into().unwrap())
                    as usize;
            if target < n && !seen[target] {
                seen[target] = true;
                out.push(target as u32);
            }
        }
    }
    out
}

fn main() {
    let n_rows = 20_000u64;
    let seed_set: Vec<u32> = (0..20).map(|i| i * 37 + 5).collect();

    println!("n_rows={n_rows} seed_set.len()={}\n", seed_set.len());
    println!(
        "{:>12} {:>8} {:>10} {:>10} {:>10}",
        "gate_mask", "radius", "avg_deg", "1hop", "2hop"
    );

    for &gate_mask in &[0x0u64, 0x1u64, 0x3u64, 0x7u64, 0xFu64, 0x1Fu64] {
        for &radius in &[25u32, 100, 500] {
            let store =
                RowStore::generate_with_edges(n_rows, SEED, EDGE_CLASSID, gate_mask, radius)
                    .expect("generation should succeed for these params");

            // average out-degree: total classid==EDGE_CLASSID facets that
            // actually carry a structured target, sampled directly.
            let mut edge_facets = 0u64;
            for row in 0..n_rows as usize {
                let base = row * 512;
                for facet in 0..ROW_FACETS as usize {
                    let fbase = base + facet * 16;
                    let classid =
                        u32::from_le_bytes(store.as_bytes()[fbase..fbase + 4].try_into().unwrap());
                    let hi32 = u32::from_le_bytes(
                        store.as_bytes()[fbase + 12..fbase + 16].try_into().unwrap(),
                    );
                    if classid == EDGE_CLASSID && hi32 == 0 {
                        edge_facets += 1;
                    }
                }
            }
            let avg_deg = edge_facets as f64 / n_rows as f64;

            let one_hop = hop(&store, &seed_set, EDGE_CLASSID);
            let two_hop = hop(&store, &one_hop, EDGE_CLASSID);

            println!(
                "{:>#12x} {:>8} {:>10.3} {:>10} {:>10}",
                gate_mask,
                radius,
                avg_deg,
                one_hop.len(),
                two_hop.len()
            );
        }
    }
}

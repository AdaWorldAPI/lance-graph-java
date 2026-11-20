//! The two SHAPE CONTRACTS and where each applies (docs/abi.md §18, "Three
//! alignment facts, and two shape contracts").
//!
//! - ndarray owns the FACET STRIDE positional rule: a 128-bit facet is sixteen
//!   byte positions read by the load that brought it in
//!   (`ternary_match_strided16_to_mask` is the kernel its CLAUDE.md names as
//!   the precedent). It also owns the DR512 `lut9` presence/polarity rule,
//!   which this crate does not use (see `tests/dr512_not_adopted.rs`).
//! - lgj owns the layout of 32 x 16-byte facets and the row-major /
//!   facet-major addressing.
//!
//! This file exercises, TEST-ONLY (no production call site, no export):
//!
//! 1. the all-sixteen-positions match over lgj's ROW-MAJOR store, against a
//!    byte-wise oracle, with the 12-byte register kernel as a cross-check;
//! 2. why that rule has no facet-major form, and the cross-layout statement
//!    (three per-field passes conjoined == the row-major sixteen-position mask);
//! 3. the contract's cascade ladder (`FacetCascade::tier_bytes`) against lgj's
//!    carving byte order, which are NOT the same reading.
//!
//! The byte-wise oracles are TEST ORACLES (E2): nothing here is a production
//! scalar path.

use crate::kernels::{
    masked_facet_sum, simd_mask_and, simd_rowstore_ternary_match_mask,
    simd_ternary_match_u32_to_mask, simd_ternary_match_u64_to_mask, FACET_REGISTER_BYTES,
};
use crate::rowstore::{RowLayout, RowStore, FACET_BYTES, ROW_BYTES, ROW_FACETS};

const SEED: u64 = 0x1A7E;

fn words(n: u64) -> Vec<u64> {
    vec![0u64; n.div_ceil(64) as usize]
}

/// Bits at positions `>= n` of the last mask word are zero.
fn tail_is_clear(w: &[u64], n: u64) -> bool {
    let used = (n % 64) as u32;
    w.len() == n.div_ceil(64) as usize && (used == 0 || w[w.len() - 1] >> used == 0)
}

fn pop(w: &[u64]) -> u64 {
    w.iter().map(|x| u64::from(x.count_ones())).sum()
}

/// The (pattern, care) pairs, built from row 0's own facet bytes so every pair
/// has at least one hit (row 0), and so the cared bytes are real data.
fn pairs(row0: &[u8; 16]) -> Vec<(&'static str, [u8; 16], [u8; 16])> {
    let mut p0 = [0xAAu8; 16]; // care none: matches every row whatever the pattern
    p0[0] = 0x55;
    let mut c2 = [0u8; 16]; // classid byte 0 (low nibble) + bytes 1..4, and hi32's top byte
    c2[0] = 0x0F;
    c2[1..4].fill(0xFF);
    c2[15] = 0x80;
    let mut c3 = [0u8; 16]; // register bytes 4..16 only
    c3[4] = 0xC0;
    c3[15] = 0xC0;
    vec![
        ("care-none", p0, [0u8; 16]),
        ("exact-16", *row0, [0xFFu8; 16]),
        ("classid+hi32", *row0, c2),
        ("register-only", *row0, c3),
    ]
}

/// B1a. Each of the sixteen positions in isolation. Care is 0xFF on exactly one
/// byte k. The classid's bytes 1..3 are zero in every generated row, so the
/// other fixtures never constrain one of them alone; here each position is
/// the only cared byte. For every facet and every k: the kernel equals the
/// byte-wise oracle and row 0 matches; flipping a cared bit of the pattern
/// stops row 0 matching (and still equals the oracle); flipping a bit of an
/// uncared byte leaves the mask unchanged.
#[test]
fn each_of_the_sixteen_positions_is_matched_in_isolation() {
    let n = 257u64;
    let store = RowStore::generate_in(n, SEED, RowLayout::AosRows).unwrap();
    let bytes = store.as_bytes();
    let run = |first: usize, pattern: &[u8; 16], care: &[u8; 16]| {
        let mut got = words(n);
        ndarray::simd::ternary_match_strided16_to_mask(
            bytes,
            first,
            ROW_BYTES as usize,
            n as usize,
            pattern,
            care,
            &mut got,
        );
        let mut want = words(n);
        for row in 0..n as usize {
            let o = first + row * ROW_BYTES as usize;
            if (0..16).all(|j| (bytes[o + j] ^ pattern[j]) & care[j] == 0) {
                want[row / 64] |= 1u64 << (row % 64);
            }
        }
        assert_eq!(got, want, "first={first} care={care:?}: kernel vs oracle");
        got
    };
    for f in 0..ROW_FACETS {
        let first = (u64::from(f) * FACET_BYTES) as usize;
        let row0: [u8; 16] = bytes[first..first + 16].try_into().unwrap();
        for k in 0..16usize {
            let mut care = [0u8; 16];
            care[k] = 0xFF;
            let base = run(first, &row0, &care);
            assert_eq!(
                base[0] & 1,
                1,
                "facet={f} k={k}: row 0 matches its own byte"
            );
            let mut flipped = row0;
            flipped[k] ^= 0x01;
            let miss = run(first, &flipped, &care);
            assert_eq!(
                miss[0] & 1,
                0,
                "facet={f} k={k}: a cared bit flip unmatches row 0"
            );
            let mut uncared = row0;
            uncared[(k + 1) % 16] ^= 0x80;
            assert_eq!(
                run(first, &uncared, &care),
                base,
                "facet={f} k={k}: an uncared bit flip changes nothing"
            );
        }
    }
}

/// B1. `ternary_match_strided16_to_mask` over the ROW-MAJOR store: every facet
/// (`first_offset = f*16`, `stride = 512`), n in {16, 257, 4096}, four
/// (pattern, care) pairs, against a byte-wise oracle over bytes 0..16. One
/// pair's care touches classid bytes 0..4 AND the hi32 bytes 12..16; one has
/// care only on bytes 4..16 and must equal the 12-byte register kernel's mask.
/// Tails are asserted clear; the n = 257 run is repeated on odd-based copies.
#[test]
fn the_sixteen_position_match_equals_a_byte_wise_oracle_over_the_row_major_store() {
    for n in [16u64, 257, 4096] {
        let store = RowStore::generate_in(n, SEED, RowLayout::AosRows).unwrap();
        let bytes = store.as_bytes();
        let mut sees_classid_total = 0u64;
        let mut differs_from_12 = 0u32;
        for f in 0..ROW_FACETS {
            let first = (u64::from(f) * FACET_BYTES) as usize;
            let row0: [u8; 16] = bytes[first..first + 16].try_into().unwrap();
            for (name, pattern, care) in pairs(&row0) {
                let ctx = format!("n={n} facet={f} {name}");
                let mut got = words(n);
                ndarray::simd::ternary_match_strided16_to_mask(
                    bytes,
                    first,
                    ROW_BYTES as usize,
                    n as usize,
                    &pattern,
                    &care,
                    &mut got,
                );
                let mut want = words(n);
                for row in 0..n as usize {
                    let o = first + row * ROW_BYTES as usize;
                    if (0..16).all(|k| (bytes[o + k] ^ pattern[k]) & care[k] == 0) {
                        want[row / 64] |= 1u64 << (row % 64);
                    }
                }
                assert_eq!(got, want, "{ctx}: kernel vs byte-wise oracle");
                assert!(tail_is_clear(&got, n), "{ctx}: tail bits >= n must be zero");
                match name {
                    "care-none" => assert_eq!(pop(&got), n, "{ctx}: care none matches all"),
                    _ => assert!(
                        pop(&got) >= 1 && pop(&got) < n,
                        "{ctx}: neither vacuous nor total, pop={}",
                        pop(&got)
                    ),
                }
                // The 12-byte register kernel sees only bytes 4..16.
                let mut reg_p = [0u8; FACET_REGISTER_BYTES];
                let mut reg_c = [0u8; FACET_REGISTER_BYTES];
                reg_p.copy_from_slice(&pattern[4..16]);
                reg_c.copy_from_slice(&care[4..16]);
                let mut reg = words(n);
                simd_rowstore_ternary_match_mask(
                    bytes,
                    first + 4,
                    ROW_BYTES as usize,
                    n as usize,
                    &reg_p,
                    &reg_c,
                    &mut reg,
                );
                assert!(tail_is_clear(&reg, n), "{ctx}: 12-byte tail");
                if name == "register-only" {
                    assert_eq!(
                        got, reg,
                        "{ctx}: care only on bytes 4..16 == the 12-byte kernel"
                    );
                }
                if name == "classid+hi32" {
                    assert!(
                        care[..4].iter().any(|&c| c != 0) && care[12..].iter().any(|&c| c != 0)
                    );
                    sees_classid_total += pop(&got);
                    if got != reg {
                        differs_from_12 += 1;
                    }
                }
                // Same masks on odd-based copies (n=257 only; unaligned loads).
                if n == 257 {
                    for off in [1usize, 3, 7] {
                        let mut big = vec![0xEEu8; bytes.len() + 16];
                        big[off..off + bytes.len()].copy_from_slice(bytes);
                        let mut at = words(n);
                        ndarray::simd::ternary_match_strided16_to_mask(
                            &big[off..off + bytes.len()],
                            first,
                            ROW_BYTES as usize,
                            n as usize,
                            &pattern,
                            &care,
                            &mut at,
                        );
                        assert_eq!(at, want, "{ctx}: odd base offset {off}");
                        assert!(tail_is_clear(&at, n), "{ctx}: odd-base tail");
                    }
                }
            }
        }
        // Anti-vacuity: constraining classid bytes + hi32 really changes the
        // answer relative to what the 12-byte kernel (which cannot see the
        // classid) says for the same register constraint.
        assert!(sees_classid_total > 0, "n={n}");
        assert!(
            differs_from_12 > 0,
            "n={n}: the classid bytes must matter for some facet"
        );
    }
}

/// B2. NOT APPLICABLE on facet-major, and why. On the facet-major store the
/// sixteen bytes of facet `f` of row `r` are not contiguous: the classid,
/// lo64 and hi32 offsets (from `RowLayout`) are never `o, o+4, o+12`, and in
/// fact never fall in one 16-byte window. So the positional rule has no
/// facet-major form. The facet-major EQUIVALENT is three per-field passes
/// conjoined, and that conjunction equals the row-major sixteen-position mask
/// for the same (pattern, care).
#[test]
fn the_sixteen_position_rule_has_no_facet_major_form_and_its_equivalent_is_three_passes_conjoined()
{
    let l = RowLayout::FacetMajor;
    for n in [16u64, 257, 4096] {
        let mut contiguous = 0u64;
        for f in 0..ROW_FACETS {
            for r in 0..n {
                let c = l.classid_offset(n, r, f);
                let lo = l.lo64_offset(n, r, f);
                let hi = l.hi32_offset(n, r, f);
                assert_ne!(lo, c + 4, "n={n} r={r} f={f}: lo64 is not at classid+4");
                assert_ne!(hi, c + 12, "n={n} r={r} f={f}: hi32 is not at classid+12");
                if lo.abs_diff(c) < 16 || hi.abs_diff(c) < 16 {
                    contiguous += 1;
                }
            }
        }
        assert_eq!(
            contiguous, 0,
            "n={n}: no facet has its fields inside one 16-byte window"
        );
    }

    let n = 257u64;
    let rm = RowStore::generate_in(n, SEED, RowLayout::AosRows).unwrap();
    let fm = RowStore::generate_in(n, SEED, RowLayout::FacetMajor).unwrap();
    assert_ne!(rm.as_bytes(), fm.as_bytes(), "the two arrangements differ");
    let (rb, fb) = (rm.as_bytes(), fm.as_bytes());
    let le32 = |b: &[u8]| u32::from_le_bytes(b.try_into().unwrap());
    let le64 = |b: &[u8]| u64::from_le_bytes(b.try_into().unwrap());
    let mut checked = 0u32;
    for f in 0..ROW_FACETS {
        let first = (u64::from(f) * FACET_BYTES) as usize;
        let row0: [u8; 16] = rb[first..first + 16].try_into().unwrap();
        // facet-major lane values for facet f, decoded little-endian
        let (co, cs) = l.classid_lane(n, f);
        let (lo, ls) = l.lo64_lane(n, f);
        let (ho, hs) = l.hi32_lane(n, f);
        let cls: Vec<u32> = (0..n as usize)
            .map(|r| le32(&fb[co + r * cs..co + r * cs + 4]))
            .collect();
        let los: Vec<u64> = (0..n as usize)
            .map(|r| le64(&fb[lo + r * ls..lo + r * ls + 8]))
            .collect();
        let his: Vec<u32> = (0..n as usize)
            .map(|r| le32(&fb[ho + r * hs..ho + r * hs + 4]))
            .collect();
        for (name, pattern, care) in pairs(&row0) {
            let ctx = format!("facet={f} {name}");
            let mut rowmajor16 = words(n);
            ndarray::simd::ternary_match_strided16_to_mask(
                rb,
                first,
                ROW_BYTES as usize,
                n as usize,
                &pattern,
                &care,
                &mut rowmajor16,
            );
            let (mut m1, mut m2, mut m3, mut t, mut conj) =
                (words(n), words(n), words(n), words(n), words(n));
            simd_ternary_match_u32_to_mask(&cls, le32(&pattern[0..4]), le32(&care[0..4]), &mut m1);
            simd_ternary_match_u64_to_mask(
                &los,
                le64(&pattern[4..12]),
                le64(&care[4..12]),
                &mut m2,
            );
            simd_ternary_match_u32_to_mask(
                &his,
                le32(&pattern[12..16]),
                le32(&care[12..16]),
                &mut m3,
            );
            simd_mask_and(&m1, &m2, &mut t);
            simd_mask_and(&t, &m3, &mut conj);
            for (w, m) in [
                (&m1, "classid pass"),
                (&m2, "lo64 pass"),
                (&m3, "hi32 pass"),
                (&conj, "conjunction"),
            ] {
                assert!(
                    tail_is_clear(w, n),
                    "{ctx}: {m} tail bits >= n must be zero"
                );
            }
            assert_eq!(
                conj, rowmajor16,
                "{ctx}: facet-major conjunction == row-major 16-position mask"
            );
            assert!(tail_is_clear(&rowmajor16, n), "{ctx}: row-major tail");
            checked += 1;
        }
    }
    assert_eq!(checked, 32 * 4);
}

/// B4. The contract's cascade ladder versus lgj's carving byte order.
///
/// `lance_graph_contract::facet::FacetCascade::from_bytes` reads tier `t` as
/// `lo` at facet byte `4 + 2t`, `hi` at `5 + 2t`; `tier_bytes()` returns the
/// ladder hi-before-lo, and `cascade_byte(shape, g, l)` indexes that ladder.
/// lgj's carvings (`masked_facet_sum`) read groups of the 12 register bytes in
/// storage order. THEY ARE NOT THE SAME READING.
///
/// lgj's side is derived from the kernel's actual results with
/// single-byte-significant fixtures (register byte `k` = 1, all else 0): the
/// sum is `256^level`, which names byte `k`'s LEVEL within its group. A sum
/// cannot name a group (it is commutative over groups), so the group of byte
/// `k` is INFERRED from where the level resets to 0 (groups are consecutive
/// runs); that inference is stated, not hidden.
///
/// OBSERVED RELATIONSHIP, asserted below for all three shapes, every
/// (group, level): the register byte the contract's `cascade_byte` returns is
/// lgj's register byte for the same (group, level) XOR 1. For G6D2 and G3D4
/// that only swaps bytes within each group (same byte SETS per group); for
/// G4D3 the groups themselves differ (contract group 0 is bytes {0,1,3}, lgj's
/// is {0,1,2}). Neither side is changed here; this is an open finding.
#[test]
fn contract_cascade_byte_is_lgj_carving_register_index_xor_1_and_g4d3_group_sets_differ() {
    use lance_graph_contract::facet::{CascadeShape, FacetCascade};

    // 12 DISTINCT register bytes: register byte k = 0xA0 + k.
    let mut facet = [0u8; 16];
    facet[..4].copy_from_slice(&[0x01, 0x00, 0x00, 0x00]);
    for k in 0..12usize {
        facet[4 + k] = 0xA0 + k as u8;
    }
    let fc = FacetCascade::from_bytes(&facet);
    let ladder = fc.tier_bytes();
    for (i, &b) in ladder.iter().enumerate() {
        assert_eq!(
            b,
            facet[4 + (i ^ 1)],
            "tier_bytes()[{i}] is register byte {}",
            i ^ 1
        );
    }

    for shape in CascadeShape::ROTATIONS {
        let (gn, d) = (shape.groups() as usize, shape.levels() as usize);
        // lgj: the level (weight) of every register byte, from the kernel.
        let mut level = [0usize; 12];
        for (k, lv) in level.iter_mut().enumerate() {
            let mut one = [0u8; 16];
            one[4 + k] = 1;
            let sum = masked_facet_sum(&one, 0, 16, 1, shape, &[1]).unwrap() as u64;
            assert!(
                sum.is_power_of_two() && sum.trailing_zeros().is_multiple_of(8),
                "{shape:?} byte {k}: {sum}"
            );
            *lv = (sum.trailing_zeros() / 8) as usize;
        }
        // Group of byte k, inferred: the count of level resets (level 0) up to k.
        let group: Vec<usize> = (0..12)
            .map(|k| (0..=k).filter(|&j| level[j] == 0).count() - 1)
            .collect();
        assert_eq!(
            *group.last().unwrap(),
            gn - 1,
            "{shape:?}: inferred group count"
        );

        println!("{shape:?} ({gn} groups x {d} levels): (group, level) -> contract register byte | lgj register byte");
        let (mut contract_sets, mut lgj_sets) = (vec![vec![]; gn], vec![vec![]; gn]);
        for g in 0..gn {
            for l in 0..d {
                let lgj: Vec<usize> = (0..12)
                    .filter(|&k| group[k] == g && level[k] == l)
                    .collect();
                assert_eq!(lgj.len(), 1, "{shape:?} (g{g}, l{l}): exactly one lgj byte");
                let lgj = lgj[0];
                let v = fc.cascade_byte(shape, g as u8, l as u8);
                let contract = usize::from(v - 0xA0);
                println!("  (g{g}, l{l}) -> contract byte {contract:>2} | lgj byte {lgj:>2}");
                assert_eq!(
                    lgj,
                    g * d + l,
                    "{shape:?}: lgj reads byte g*D+l in storage order"
                );
                assert_eq!(
                    contract,
                    lgj ^ 1,
                    "{shape:?} (g{g}, l{l}): contract = lgj XOR 1"
                );
                contract_sets[g].push(contract);
                lgj_sets[g].push(lgj);
            }
        }
        let mut differing = 0;
        for g in 0..gn {
            contract_sets[g].sort_unstable();
            lgj_sets[g].sort_unstable();
            if contract_sets[g] != lgj_sets[g] {
                differing += 1;
            }
        }
        match shape {
            CascadeShape::G4D3 => {
                assert_eq!(differing, 4, "G4D3: every group's byte SET differs");
                assert_eq!(contract_sets[0], vec![0, 1, 3]);
                assert_eq!(lgj_sets[0], vec![0, 1, 2]);
            }
            _ => assert_eq!(
                differing, 0,
                "{shape:?}: same byte sets per group, order within group swapped"
            ),
        }
    }
}

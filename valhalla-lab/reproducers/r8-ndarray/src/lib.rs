//! R8 arm E — the MASK-NATIVE sweep, with every SIMD-shaped step consumed from
//! `ndarray::simd` (the abi.md §8 invariant: never `hpc::*`, never raw intrinsics).
//!
//! Arm D' proved that Java-side partitioning (a materialized index list per carving) beats
//! the generic per-row-dispatch sweep ~5x under a random classid distribution. But an index
//! list is exactly the "materialized population" this workspace's mask-native law exists to
//! forbid as internal currency. Arm E asks: does the LAWFUL shape -- classid -> per-carving
//! BITMASK built by `eq_u32_strided_to_mask`, sweep driven by mask-bit iteration -- keep
//! D''s win, and what does the mask build cost compared to the 35 ms Java partition scan?

use ndarray::simd::{eq_u32_strided_to_mask, mask_or};

const FACET_BYTES: usize = 16;
const REGISTER_OFF: usize = 4;

#[inline(always)]
unsafe fn rail(ptr: *const u8, row: u64, g: u32) -> i32 {
    let b = ptr.add(row as usize * FACET_BYTES + REGISTER_OFF + g as usize * 2);
    (*b as i32) | ((*b.add(1) as i32) << 8)
}
#[inline(always)]
unsafe fn triplet(ptr: *const u8, row: u64, g: u32) -> i32 {
    let b = ptr.add(row as usize * FACET_BYTES + REGISTER_OFF + g as usize * 3);
    (*b as i32) | ((*b.add(1) as i32) << 8) | ((*b.add(2) as i32) << 16)
}
#[inline(always)]
unsafe fn quad(ptr: *const u8, row: u64, g: u32) -> i32 {
    let b = ptr.add(row as usize * FACET_BYTES + REGISTER_OFF + g as usize * 4);
    i32::from_le_bytes([*b, *b.add(1), *b.add(2), *b.add(3)])
}

/// Build the three carving masks from the strided classid column, entirely through
/// `ndarray::simd`: rails = eq(0), triplets = eq(1), quads = eq(2) | eq(3).
/// Each out buffer must hold ceil(rows/64) u64 words.
///
/// # Safety
/// `ptr` must point at `rows * 16` readable bytes; the three outs at `words` u64 each.
#[no_mangle]
pub unsafe extern "C" fn r8e_masks_build(
    ptr: *const u8, rows: u64,
    out_rails: *mut u64, out_triplets: *mut u64, out_quads: *mut u64, words: u64,
) {
    let bytes = core::slice::from_raw_parts(ptr, rows as usize * FACET_BYTES);
    let rails = core::slice::from_raw_parts_mut(out_rails, words as usize);
    let trips = core::slice::from_raw_parts_mut(out_triplets, words as usize);
    let quads = core::slice::from_raw_parts_mut(out_quads, words as usize);
    eq_u32_strided_to_mask(bytes, 0, FACET_BYTES, rows as usize, 0, rails);
    eq_u32_strided_to_mask(bytes, 0, FACET_BYTES, rows as usize, 1, trips);
    let mut tmp = vec![0u64; words as usize];
    eq_u32_strided_to_mask(bytes, 0, FACET_BYTES, rows as usize, 2, quads);
    eq_u32_strided_to_mask(bytes, 0, FACET_BYTES, rows as usize, 3, &mut tmp);
    mask_or(&quads.to_vec(), &tmp, quads);
}

/// Monomorphic sweep driven by a MASK (the lawful currency), not an index list: walk set
/// bits with trailing_zeros, project every group of each selected row, repeat `passes`.
macro_rules! mask_sweep {
    ($name:ident, $proj:ident, $n:expr) => {
        /// # Safety
        /// `ptr` spans all rows the mask can select; `mask` holds `words` u64.
        #[no_mangle]
        pub unsafe extern "C" fn $name(
            ptr: *const u8, mask: *const u64, words: u64, passes: u64,
        ) -> i64 {
            let mask = core::slice::from_raw_parts(mask, words as usize);
            let mut acc = 0i64;
            for _ in 0..passes {
                for (w, &word) in mask.iter().enumerate() {
                    let base = (w * 64) as u64;
                    let mut bits = word;
                    while bits != 0 {
                        let row = base + bits.trailing_zeros() as u64;
                        let mut g = 0u32;
                        while g < $n { acc += $proj(ptr, row, g) as i64; g += 1; }
                        bits &= bits - 1;
                    }
                }
            }
            acc
        }
    };
}
mask_sweep!(r8e_mask_sweep_rails, rail, 6);
mask_sweep!(r8e_mask_sweep_triplets, triplet, 4);
mask_sweep!(r8e_mask_sweep_quads, quad, 3);

/// Popcount over a mask so the Java side can compute pass accounting from the masks alone.
///
/// # Safety
/// `mask` holds `words` u64.
#[no_mangle]
pub unsafe extern "C" fn r8e_mask_count(mask: *const u64, words: u64) -> u64 {
    core::slice::from_raw_parts(mask, words as usize).iter().map(|w| w.count_ones() as u64).sum()
}

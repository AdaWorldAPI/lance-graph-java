// R8 native side — the Rust arms of the four-arm entropy-boundary benchmark.
// Built as a cdylib for arms B/C/D (called from R8_EntropyBoundary.java) and included by
// r8_standalone.rs for the no-JVM baseline. Layout, fill pattern, op accounting and
// checksum arithmetic MIRROR the Java arm exactly -- checksum equality across arms is the
// symmetry check that proves all arms did the same work on the same bytes.
// (no inner attributes -- this file is include!-ed by r8_standalone.rs)

pub const FACET_BYTES: usize = 16;
pub const REGISTER_OFF: usize = 4;

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
    i32::from_le_bytes([*b, *b.add(1), *b.add(2), *b.add(3)]) // Java JAVA_INT_UNALIGNED: signed
}
#[inline(always)]
unsafe fn classid(ptr: *const u8, row: u64) -> u32 {
    let b = ptr.add(row as usize * FACET_BYTES);
    u32::from_le_bytes([*b, *b.add(1), *b.add(2), *b.add(3)])
}

/// Arm C: ONE projection per call -- the per-op FFI shape the anti-JNI rule forbids.
#[no_mangle]
pub unsafe extern "C" fn r8_project_one(ptr: *const u8, row: u64, cid: u32, g: u32) -> i32 {
    match cid & 3 { 0 => rail(ptr, row, g), 1 => triplet(ptr, row, g), _ => quad(ptr, row, g) }
}

/// Arm B: the GENERIC bulk sweep -- Rust re-derives the carving from the classid per row,
/// exactly as a schema-generic engine must. Mirrors the Java sweep loop op-for-op.
#[no_mangle]
pub unsafe extern "C" fn r8_sweep_generic(ptr: *const u8, rows: u64, target: u64) -> i64 {
    let (mut acc, mut done, mut row) = (0i64, 0u64, 0u64);
    while done < target {
        let cid = classid(ptr, row) & 3;
        match cid {
            0 => { let mut g = 0; while g < 6 && done < target { acc += rail(ptr, row, g) as i64; g += 1; done += 1; } }
            1 => { let mut g = 0; while g < 4 && done < target { acc += triplet(ptr, row, g) as i64; g += 1; done += 1; } }
            _ => { let mut g = 0; while g < 3 && done < target { acc += quad(ptr, row, g) as i64; g += 1; done += 1; } }
        }
        row = (row + 1) % rows;
    }
    acc
}

/// Arm D kernels: MONOMORPHIC sweeps. The caller (Java, having resolved the ClassView
/// preset) supplies the carving as the choice of ENTRY POINT, so the inner loop carries no
/// per-row dispatch at all. start/stride select the row subpopulation; ops must be a
/// multiple of the groups-per-row so no row is left mid-visit.
macro_rules! mono_sweep {
    ($name:ident, $proj:ident, $n:expr) => {
        #[no_mangle]
        pub unsafe extern "C" fn $name(ptr: *const u8, rows: u64, start: u64, stride: u64, ops: u64) -> i64 {
            let (mut acc, mut done, mut row) = (0i64, 0u64, start);
            while done < ops {
                let mut g = 0u32;
                while g < $n { acc += $proj(ptr, row, g) as i64; g += 1; }
                done += $n as u64;
                row += stride;
                if row >= rows { row = start; }
            }
            acc
        }
    };
}
mono_sweep!(r8_sweep_rails, rail, 6);
mono_sweep!(r8_sweep_triplets, triplet, 4);
mono_sweep!(r8_sweep_quads, quad, 3);

/// Identical fill to the Java arm: classid = r & 3 (LE u32), payload byte b = (r + b) as u8.
#[no_mangle]
pub unsafe extern "C" fn r8_fill(ptr: *mut u8, rows: u64) {
    for r in 0..rows {
        let base = r as usize * FACET_BYTES;
        let cid = (r & 3) as u32;
        ptr.add(base).copy_from(cid.to_le_bytes().as_ptr(), 4);
        for b in 0..12u64 { *ptr.add(base + REGISTER_OFF + b as usize) = (r + b) as u8; }
    }
}

/// Random-distribution fill: classid drawn from SplitMix64(seed) & 3 per row (payload fill
/// unchanged). This is the arm where per-row dispatch actually COSTS something: the period-4
/// pattern of r8_fill is perfectly branch-predictable, which hands the generic sweep free
/// specialization via the predictor. Random classids take that away.
#[no_mangle]
pub unsafe extern "C" fn r8_fill_random(ptr: *mut u8, rows: u64, mut seed: u64) {
    for r in 0..rows {
        seed = seed.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = seed;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        let cid = ((z ^ (z >> 31)) & 3) as u32;
        let base = r as usize * FACET_BYTES;
        ptr.add(base).copy_from(cid.to_le_bytes().as_ptr(), 4);
        for b in 0..12u64 { *ptr.add(base + REGISTER_OFF + b as usize) = (r + b) as u8; }
    }
}

/// Arm D' kernels: monomorphic sweep over a Java-supplied row-index PARTITION (the
/// materialized form of a per-carving mask). The partition is computed ONCE by the caller
/// -- that single scan IS the entropy-reduction step being priced.
macro_rules! idx_sweep {
    ($name:ident, $proj:ident, $n:expr) => {
        #[no_mangle]
        pub unsafe extern "C" fn $name(ptr: *const u8, idx: *const u32, count: u64, passes: u64) -> i64 {
            let mut acc = 0i64;
            for _ in 0..passes {
                for i in 0..count as usize {
                    let row = *idx.add(i) as u64;
                    let mut g = 0u32;
                    while g < $n { acc += $proj(ptr, row, g) as i64; g += 1; }
                }
            }
            acc
        }
    };
}
idx_sweep!(r8_idx_rails, rail, 6);
idx_sweep!(r8_idx_triplets, triplet, 4);
idx_sweep!(r8_idx_quads, quad, 3);

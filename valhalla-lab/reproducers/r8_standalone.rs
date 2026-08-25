// R8 arm B' — the no-JVM baseline: the identical generic sweep, pure Rust process.
include!("r8_native.rs");
fn main() {
    const ROWS: u64 = 65_536;
    const TARGET: u64 = 1_000_000_000;
    let mut buf = vec![0u8; ROWS as usize * FACET_BYTES];
    unsafe {
        r8_fill(buf.as_mut_ptr(), ROWS);
        for _ in 0..3 { std::hint::black_box(r8_sweep_generic(buf.as_ptr(), ROWS, 50_000_000)); } // warm
        for _ in 0..3 {
            let t0 = std::time::Instant::now();
            let acc = r8_sweep_generic(buf.as_ptr(), ROWS, TARGET);
            let s = t0.elapsed().as_secs_f64();
            println!("rust-standalone generic: {:.2} s  {:.1} M ops/s  checksum {}", s, TARGET as f64 / s / 1e6, acc);
        }
    }
}

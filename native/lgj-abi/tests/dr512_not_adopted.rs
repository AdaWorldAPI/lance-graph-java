//! DR512 / `lut9` parity is NOT APPLICABLE to lance-graph-java today, stated
//! mechanically.
//!
//! ndarray's `simd_stride` module (`lut_u8`, `lut9`) and `hpc::codec9` own the
//! DR512 presence/polarity rule: a magnitude byte lane plus ONE presence bit in
//! a separate u64 bit-plane (P=0,M=0 is "never touched"). lgj's row store has
//! no DR512 lane and no presence plane, and calls none of those. If production
//! code under `src/` ever starts to, this test FAILS and forces the parity work
//! (the lane on Lance-backed memory against ndarray's
//! `all_512_codes_agree_in_codec9_and_lut9`) to be written in the same change.
//!
//! The scan covers non-test production code: files under a `tests` directory
//! are skipped, each file is cut at its first `#[cfg(test)]` that opens a
//! `mod`, and comment-only lines are ignored (the documentation may name these
//! words; only code may not use them). The anti-vacuity half asserts the scan
//! really saw `ndarray::simd` in use.

use std::fs;
use std::path::{Path, PathBuf};

const NOT_ADOPTED: &[&str] = &["simd_stride", "lut9", "lut_u8", "codec9"];

fn rust_files(dir: &Path, out: &mut Vec<PathBuf>) {
    for e in fs::read_dir(dir).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            if p.file_name().is_some_and(|n| n == "tests") {
                continue;
            }
            rust_files(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

/// Production code lines of `text`: cut at the first `#[cfg(test)]` directly
/// followed by a `mod`, drop comment-only lines.
fn production_code(text: &str) -> Vec<&str> {
    let lines: Vec<&str> = text.lines().collect();
    let mut end = lines.len();
    for i in 0..lines.len() {
        if lines[i].trim() == "#[cfg(test)]"
            && lines
                .get(i + 1)
                .is_some_and(|n| n.trim_start().starts_with("mod "))
        {
            end = i;
            break;
        }
    }
    lines[..end]
        .iter()
        .copied()
        .filter(|l| !l.trim_start().starts_with("//"))
        .collect()
}

fn scan() -> (Vec<String>, usize) {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut files = Vec::new();
    rust_files(&src, &mut files);
    files.sort();
    let (mut violations, mut simd_paths) = (Vec::new(), 0usize);
    for f in &files {
        let text = fs::read_to_string(f).unwrap();
        for (i, line) in production_code(&text).iter().enumerate() {
            simd_paths += line.matches("ndarray::simd::").count();
            for word in NOT_ADOPTED {
                if line.contains(word) {
                    violations.push(format!(
                        "{}: `{word}` in `{}` (code line {})",
                        f.display(),
                        line.trim(),
                        i + 1
                    ));
                }
            }
        }
    }
    (violations, simd_paths)
}

#[test]
fn lgj_calls_no_simd_stride_lut9_lut_u8_or_codec9_so_dr512_parity_is_not_applicable_until_it_does()
{
    let (violations, simd_paths) = scan();
    assert!(
        violations.is_empty(),
        "lgj now uses a DR512 / simd_stride path; the lut9 presence/polarity parity test must be written: {violations:#?}"
    );
    // Anti-vacuity: the scan reached production code that really calls ndarray.
    assert!(
        simd_paths >= 20,
        "scan saw only {simd_paths} `ndarray::simd::` paths; wrong directory?"
    );
    let kernels =
        fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("src/kernels.rs")).unwrap();
    assert!(production_code(&kernels)
        .iter()
        .any(|l| l.contains("ndarray::simd::eq_u32_strided_to_mask")));
}

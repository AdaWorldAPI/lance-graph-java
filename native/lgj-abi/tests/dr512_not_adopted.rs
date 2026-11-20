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
//! The scan covers EVERY Rust file under `src/`, test modules and test
//! directories included, and ignores only comment-only lines (documentation
//! may name these words; code may not use them). It deliberately does not try
//! to tell test code from production code: that needs a Rust lexer (braces in
//! string literals, trailing comments), and no code under `src/` uses these
//! names today, so the stricter rule costs nothing. A test that later needs
//! one of them is the moment to write the parity work, too. The anti-vacuity
//! half asserts the scan really saw `ndarray::simd` in use.

use std::fs;
use std::path::{Path, PathBuf};

const NOT_ADOPTED: &[&str] = &["simd_stride", "lut9", "lut_u8", "codec9"];

fn rust_files(dir: &Path, out: &mut Vec<PathBuf>) {
    for e in fs::read_dir(dir).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            rust_files(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

/// Code lines of `text`: everything except comment-only lines.
fn code_lines(text: &str) -> Vec<&str> {
    text.lines()
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
        for (i, line) in code_lines(&text).iter().enumerate() {
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
    assert!(code_lines(&kernels)
        .iter()
        .any(|l| l.contains("ndarray::simd::eq_u32_strided_to_mask")));
}

fn uses_not_adopted(text: &str) -> bool {
    code_lines(text)
        .iter()
        .any(|l| NOT_ADOPTED.iter().any(|w| l.contains(w)))
}

#[test]
fn a_use_anywhere_in_code_is_reported_whatever_surrounds_it() {
    // Below an inline test module.
    assert!(uses_not_adopted(
        "#[cfg(test)]\nmod tests {\n    fn t() {}\n}\nuse ndarray::simd_stride;\n"
    ));
    // Below a test module whose body holds a brace in a string literal.
    assert!(uses_not_adopted(
        "#[cfg(test)]\nmod tests {\n    fn t() { let _ = \"{\"; }\n}\nuse ndarray::simd_stride;\n"
    ));
    // Below an out-of-line declaration with a trailing comment.
    assert!(uses_not_adopted(
        "#[cfg(test)]\nmod tests; // unit tests\nuse ndarray::simd_stride::lut9;\n"
    ));
    // Inside a test module: also reported, by design.
    assert!(uses_not_adopted(
        "#[cfg(test)]\nmod tests {\n    fn t() { lut_u8(); }\n}\n"
    ));
    // After code on the same line as a trailing comment.
    assert!(uses_not_adopted("let x = codec9::f(); // note\n"));
}

#[test]
fn a_name_in_a_comment_only_line_is_not_a_violation() {
    assert!(!uses_not_adopted(
        "// codec9 is named in prose only\n    /// lut9 too\n//! simd_stride\nfn a() {}\n"
    ));
    assert_eq!(
        code_lines("// c\nfn a() {}\n    // d\nfn b() {}\n"),
        vec!["fn a() {}", "fn b() {}"]
    );
}

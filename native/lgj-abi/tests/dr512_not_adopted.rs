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
//! are skipped, every `#[cfg(test)] mod` is removed up to its closing brace
//! (scanning resumes after it), and comment-only lines are ignored (the documentation may name these
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

/// Production code lines of `text`: every `#[cfg(test)]` directly followed by
/// a `mod` is removed up to the brace that closes that module (or only its
/// own line for `mod name;`), and scanning RESUMES after it, so production
/// code placed below a test module is still seen. Comment-only lines are
/// dropped. Braces are counted textually, which is exact for this crate's
/// formatted sources (a test module containing an unbalanced brace inside a
/// string literal would end the skip early or late; the first makes the fence
/// stricter, and the regression tests below pin the ordinary shapes).
fn production_code(text: &str) -> Vec<&str> {
    let lines: Vec<&str> = text.lines().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < lines.len() {
        let opens_test_mod = lines[i].trim() == "#[cfg(test)]"
            && lines
                .get(i + 1)
                .is_some_and(|n| n.trim_start().starts_with("mod "));
        if opens_test_mod {
            let mut depth = 0i64;
            let mut opened = false;
            i += 1;
            while i < lines.len() {
                for ch in lines[i].chars() {
                    match ch {
                        '{' => {
                            depth += 1;
                            opened = true;
                        }
                        '}' => depth -= 1,
                        _ => {}
                    }
                }
                let declaration_only = !opened && lines[i].trim_end().ends_with(';');
                i += 1;
                if (opened && depth <= 0) || declaration_only {
                    break;
                }
            }
            continue;
        }
        if !lines[i].trim_start().starts_with("//") {
            out.push(lines[i]);
        }
        i += 1;
    }
    out
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

fn uses_not_adopted(text: &str) -> bool {
    production_code(text)
        .iter()
        .any(|l| NOT_ADOPTED.iter().any(|w| l.contains(w)))
}

#[test]
fn production_code_below_a_test_module_is_still_scanned() {
    let text = "fn a() {}\n#[cfg(test)]\nmod tests {\n    fn t() {\n        let _ = 1;\n    }\n}\nuse ndarray::simd_stride;\n";
    assert!(uses_not_adopted(text));
    // Same shape with an out-of-line test module declaration.
    let text = "#[cfg(test)]\nmod tests;\nuse ndarray::simd_stride::lut9;\n";
    assert!(uses_not_adopted(text));
    // And with two test modules, the forbidden use between them.
    let text = "#[cfg(test)]\nmod a {\n}\nfn f() { lut_u8(); }\n#[cfg(test)]\nmod b {\n}\n";
    assert!(uses_not_adopted(text));
}

#[test]
fn an_identifier_only_inside_a_test_module_or_a_comment_is_not_a_violation() {
    let text = "fn a() {}\n#[cfg(test)]\nmod tests {\n    use ndarray::simd_stride::lut9;\n    fn t() {\n        lut9();\n    }\n}\nfn b() {}\n";
    assert!(!uses_not_adopted(text));
    assert_eq!(production_code(text), vec!["fn a() {}", "fn b() {}"]);
    assert!(!uses_not_adopted(
        "// codec9 is named in prose only\nfn a() {}\n"
    ));
}

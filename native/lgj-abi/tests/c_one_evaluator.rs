//! C-ONE — there is exactly one plan evaluator, and this is the structural
//! guard that says so.
//!
//! # What "one evaluator" means here, precisely
//!
//! Before PR4, `plan_eval_impl` held its own loop over `&[LgjOpDesc]`:
//! resolve the lane, dispatch the opcode, combine into an accumulator. That
//! is an evaluator, and it stood beside the one in `lance-graph-mask-risc`,
//! each free to drift. PR4 deletes it — the plan is lowered to a
//! `mask_risc::Program` and run there.
//!
//! Nothing stops a future change from growing a second one back. It would not
//! look like a mistake at the time: a "fast path for two-op plans", a
//! "specialised loop for the all-AND case", a "scalar fallback" — each is one
//! small loop over `&[LgjOpDesc]` that answers the same question a different
//! way, which is the whole failure mode.
//!
//! # The invariant this actually checks, and why it is this one
//!
//! **Only an allowlisted set of production modules may name `LgjOpDesc` in
//! CODE.** Naming the type is the minimum any second evaluator must do: it
//! cannot loop over plan ops without a plan op in scope. Comments are
//! stripped first — see [`without_comments`] for the file that forced that
//! and why allowlisting it instead would have been the wrong repair.
//!
//! Three narrower rules were considered and rejected, each for a measured
//! reason:
//!
//! - *"only `plan_lower` may call `kernels::eval_predicate`"* — wrong, and
//!   the tree says so: `lgj_op_eq_u32` and `lgj_op_gt_i32` call it, and
//!   correctly. Those are the UNFUSED single-predicate exports that exist so
//!   the fused plan has something to be benchmarked against; each passes a
//!   compile-time-constant opcode and evaluates exactly one predicate. One
//!   predicate is not an evaluator.
//! - *"the opcode argument must be a literal"* — would catch those two, but
//!   `lgj_mask_combine` passes a RUNTIME `combine` to `kernels::combine_into`
//!   and is a legitimate single bulk op. The rule would fire on a
//!   non-violation.
//! - *"no production function may iterate `&[LgjOpDesc]`"* — the right
//!   property, and not textually checkable: `for op in ops` carries no type
//!   in its text, so a scanner would have to be a type checker.
//!
//! So the allowlist is over MODULES, in the same shape as the G11
//! contract-import fence: widening it is a deliberate act, in one commit, and
//! a new module holding plan ops is exactly the event that deserves a human
//! reading the diff.
//!
//! # Scope, stated rather than implied
//!
//! This guard is structural. It cannot tell a second evaluator from a second
//! legitimate USE of the type inside an already-allowed module — `exports.rs`
//! is allowlisted, so a new loop added there passes this test. What catches
//! that is the differential in `pr4_equivalence.rs`, which compares whatever
//! the live path does against a frozen copy of the old loop. The two are
//! complementary and neither subsumes the other: the differential cannot see
//! a second evaluator that happens to agree, and this cannot see one hidden
//! in an allowed file.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

/// Production modules permitted to name `LgjOpDesc`, each with the reason.
///
/// - `abi.rs` — declares the type and its `LGJ_OP_*` / `LGJ_COMBINE_*`
///   constants. The definition site.
/// - `exports.rs` — the `extern "C"` signatures that receive a plan, plus
///   `validate_plan`, which reads every field to REJECT a bad plan. A
///   validator is not an evaluator: it computes no mask and returns no rows.
/// - `plan_lower.rs` — the one lowering, `&[LgjOpDesc]` to one
///   `mask_risc::Program`. The only production place a runtime opcode is
///   dispatched.
const ALLOWED: &[&str] = &["abi.rs", "exports.rs", "plan_lower.rs"];

const TYPE: &str = "LgjOpDesc";

/// Every `.rs` file under `dir`, recursively.
///
/// Recursive rather than a flat `read_dir`: the G11 fence shipped with a flat
/// scan and codex found the nested-directory bypass standing beside the path
/// its disable run had walked. Same scanner, same bypass, so the same fix.
fn rust_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let entries = fs::read_dir(dir).unwrap_or_else(|e| panic!("read_dir {dir:?}: {e}"));
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            rust_files(&path, out);
        } else if path.extension().is_some_and(|e| e == "rs") {
            out.push(path);
        }
    }
}

/// `text` with `//`-comments removed, line by line.
///
/// The invariant is about naming the type in CODE. `kernels.rs` mentions
/// `LgjOpDesc` four times in prose — explaining that its six extra predicates
/// are op-codes rather than new ABI symbols — and never touches the type. A
/// first version scanned raw text and flagged it, which would have forced a
/// choice between a false failure and allowlisting `kernels.rs`. The second
/// is the worse of the two: kernels is precisely where a second evaluator
/// would most plausibly grow, so exempting it because of a doc comment would
/// hollow the guard out at exactly the point it matters.
///
/// Limitation, stated because it is real: a `//` inside a string literal
/// truncates the rest of that line, so an occurrence AFTER such a literal on
/// the SAME line would be missed. No line in this crate has that shape, and
/// the anti-vacuity assertion catches a scanner that stops seeing the type
/// altogether — but a full lexer is what would make this airtight.
fn without_comments(text: &str) -> String {
    text.lines()
        .map(|line| match line.find("//") {
            Some(i) => &line[..i],
            None => line,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// `text` with its trailing `#[cfg(test)] mod` removed.
///
/// The split anchors on the test MODULE, not on the attribute. A first
/// version split at the first `#[cfg(test)]` and was immediately red on two
/// files, correctly: `registry.rs` and `exports.rs` both carry test-only
/// ITEMS — a `slot_count()` helper, a `RESOLUTIONS` counter, and one
/// statement-level attribute inside a production function — long before their
/// test module. Splitting there would have silently discarded most of
/// `exports.rs` as "test code", which for this scanner means a violation in
/// the discarded region reads as compliance.
///
/// Keeping those items on the PRODUCTION side is the conservative direction:
/// a test-only helper that names the type can only ADD a holder, never hide
/// one, so the guard errs toward firing rather than toward silence.
///
/// Returns `None` when the module is not the file's last item, which the
/// brace match establishes rather than assumes — the caller fails loudly
/// instead of scanning less than it claims to.
fn production_half(text: &str) -> Option<&str> {
    let Some(rel) = text.rfind("#[cfg(test)]\nmod ") else {
        return Some(text);
    };
    let open = text[rel..].find('{')? + rel;
    let mut depth = 0usize;
    let mut close = None;
    let mut chars = text[open..].char_indices();
    for (off, ch) in &mut chars {
        match ch {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    close = Some(open + off);
                    break;
                }
            }
            _ => {}
        }
    }
    // Nothing but whitespace may follow: that is what makes "the module is
    // last" a fact rather than a convention this scanner relies on.
    if !text[close? + 1..].trim().is_empty() {
        return None;
    }
    Some(&text[..rel])
}

/// FAILS IF: a production module outside the allowlist names `LgjOpDesc` —
/// the minimum any second plan evaluator must do — or if the scan found
/// nothing, which would make the first half pass for a scanner pointed at the
/// wrong directory.
#[test]
fn only_the_lowering_and_the_validator_hold_plan_ops() {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut files = Vec::new();
    rust_files(&src, &mut files);
    assert!(
        files.len() >= 5,
        "found {} files under {src:?} — the scanner is pointed at the wrong tree",
        files.len()
    );

    let mut holders = BTreeSet::new();
    let mut interleaved = Vec::new();
    for path in &files {
        // The PR4 falsifier files live under `src/exports/tests/` and are
        // test code by location; the frozen oracle names the type by
        // necessity, which is the point of freezing it.
        if path.components().any(|c| c.as_os_str() == "tests") {
            continue;
        }
        let text = fs::read_to_string(path).unwrap_or_else(|e| panic!("read {path:?}: {e}"));
        let Some(prod) = production_half(&text) else {
            interleaved.push(path.clone());
            continue;
        };
        if without_comments(prod).contains(TYPE) {
            let name = path
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or_default()
                .to_string();
            holders.insert(name);
        }
    }
    assert!(
        interleaved.is_empty(),
        "in these files the `#[cfg(test)] mod` is not the last item, so the \
         production/test split cannot be made by brace-matching it: \
         {interleaved:?}. Move the test module to the end of the file, or \
         teach this scanner to handle what follows it"
    );

    // Anti-vacuity: the type must actually have been SEEN. A typo in `TYPE`,
    // or a rename, would otherwise make an empty holder set look like
    // perfect compliance.
    assert!(
        !holders.is_empty(),
        "no production file names `{TYPE}` — either the type was renamed (in \
         which case rename it here too) or this scanner reads nothing"
    );

    let allowed: BTreeSet<String> = ALLOWED.iter().map(|s| (*s).to_string()).collect();
    assert_eq!(
        holders, allowed,
        "the set of production modules holding plan ops moved.\n  found:   \
         {holders:?}\n  allowed: {allowed:?}\nA NEW module here is how a \
         second plan evaluator arrives — the one PR4 deleted was a loop over \
         these exact ops. If it is legitimate, widen ALLOWED and say in its \
         doc comment why that module needs plan ops, in the same commit."
    );
}

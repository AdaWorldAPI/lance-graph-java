//! G11 — the contract-import fence, as a test rather than a sentence.
//!
//! Root `CLAUDE.md` § Enforcement names "the G11 contract-import fence" and
//! `Cargo.toml` says this crate's `src/` "may import ONLY
//! `lance_graph_contract::{…}`". Until 2026-09-03 nothing enforced that: the
//! sentence was the whole fence, `class_view_provider.rs` cited it as if it
//! existed, and the allowlist it named was already false — three files had
//! imported `lance_graph_contract::facet::CascadeShape` (the lane-carving
//! enum, a legitimate member) without the list ever growing. A stale
//! allowlist with no enforcer is exactly the gate that lets the cognitive
//! modules (`kanban`, `mul`, `cognition`, `soa_view`, `scheduler`, …) in
//! silently, which is the one thing G11 exists to stop: they compile
//! unconditionally, so `default-features = false` is no barrier at all.
//!
//! **Falsifier (disable-verified 2026-09-03):** adding
//! `use lance_graph_contract::kanban::KanbanMove;` to any `src/*.rs` turns
//! this red naming the file and the module; removing it turns it green.
//! The anti-vacuity half asserts the scan actually saw the contract in use,
//! so an empty `src/` — or a scanner pointed at the wrong directory — cannot
//! pass by finding nothing.

use std::collections::BTreeSet;
use std::fs;
use std::path::Path;

/// The G11 allowlist. Grows only with a `CLAUDE.md` § Enforcement edit in the
/// same commit — the two are one spelling in two places, and this test is
/// what keeps them from drifting apart again.
const ALLOWED: &[&str] = &["canonical_node", "class_view", "facet", "ontology"];

const CRATE_PATH: &str = "lance_graph_contract::";

/// Every top-level `lance_graph_contract` module a source text reaches, with
/// the byte offset of each reference so a violation names its line.
///
/// Handles the three spellings that occur in `src/`: a plain path
/// (`lance_graph_contract::facet::CascadeShape`), a brace group
/// (`lance_graph_contract::{class_view, ontology::X}`), and the same paths
/// inside doc-comment links. Nested braces below the first module segment are
/// irrelevant — only the FIRST segment after the crate name is the module.
fn referenced_modules(text: &str) -> Vec<(usize, String)> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(rel) = text[from..].find(CRATE_PATH) {
        let at = from + rel;
        let rest = &text[at + CRATE_PATH.len()..];
        if let Some(group) = rest.strip_prefix('{') {
            // Top-level comma-separated entries of the brace group; each
            // entry's first identifier is a module.
            let mut depth = 0usize;
            let mut entry = String::new();
            let mut entries = Vec::new();
            for ch in group.chars() {
                match ch {
                    '{' => {
                        depth += 1;
                        entry.push(ch);
                    }
                    '}' if depth == 0 => break,
                    '}' => {
                        depth -= 1;
                        entry.push(ch);
                    }
                    ',' if depth == 0 => entries.push(std::mem::take(&mut entry)),
                    _ => entry.push(ch),
                }
            }
            entries.push(entry);
            for e in entries {
                if let Some(m) = leading_ident(e.trim()) {
                    out.push((at, m));
                }
            }
        } else if let Some(m) = leading_ident(rest) {
            out.push((at, m));
        }
        from = at + CRATE_PATH.len();
    }
    out
}

fn leading_ident(s: &str) -> Option<String> {
    let id: String = s
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
        .collect();
    (!id.is_empty()).then_some(id)
}

fn line_of(text: &str, at: usize) -> usize {
    text[..at].matches('\n').count() + 1
}

#[test]
fn g11_src_imports_only_the_allowlisted_contract_modules() {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut files_scanned = 0usize;
    let mut files_referencing = 0usize;
    let mut seen = BTreeSet::new();
    let mut violations = Vec::new();

    for entry in fs::read_dir(&src).expect("src/ is readable") {
        let path = entry.expect("dir entry").path();
        if path.extension().and_then(|e| e.to_str()) != Some("rs") {
            continue;
        }
        files_scanned += 1;
        let text = fs::read_to_string(&path).expect("source is UTF-8");
        let refs = referenced_modules(&text);
        if !refs.is_empty() {
            files_referencing += 1;
        }
        for (at, module) in refs {
            seen.insert(module.clone());
            if !ALLOWED.contains(&module.as_str()) {
                violations.push(format!(
                    "{}:{}: `lance_graph_contract::{module}` is outside the G11 allowlist {ALLOWED:?}",
                    path.file_name().unwrap().to_string_lossy(),
                    line_of(&text, at),
                ));
            }
        }
    }

    // Anti-vacuity: the fence must have looked at real code that really uses
    // the contract. A scan that sees nothing proves nothing.
    assert!(
        files_scanned >= 5,
        "scanned only {files_scanned} files under {src:?}"
    );
    assert!(
        files_referencing >= 3,
        "only {files_referencing} file(s) reference lance_graph_contract — wrong directory?"
    );
    assert!(
        seen.contains("class_view") && seen.contains("facet"),
        "expected the known live imports (class_view, facet); saw {seen:?}"
    );

    assert!(
        violations.is_empty(),
        "G11 contract-import fence breached:\n  {}\n\
         The cognitive modules compile unconditionally; this list is the only barrier. \
         Widen ALLOWED here AND in CLAUDE.md § Enforcement in the same commit, or drop the import.",
        violations.join("\n  ")
    );
}

/// The parser half, pinned on its own so a fence failure can be told apart
/// from a scanner bug.
#[test]
fn the_scanner_reads_all_three_spellings() {
    let text = "use lance_graph_contract::facet::CascadeShape;\n\
                use lance_graph_contract::{class_view::{ClassView, FieldMask}, ontology};\n\
                /// see [`X`](lance_graph_contract::kanban::KanbanMove)\n";
    let mods: Vec<String> = referenced_modules(text)
        .into_iter()
        .map(|(_, m)| m)
        .collect();
    assert_eq!(mods, ["facet", "class_view", "ontology", "kanban"]);
    let (at, _) = referenced_modules(text)[3].clone();
    assert_eq!(line_of(text, at), 3);
}

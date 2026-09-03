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
//! # What the fence closes, and how each closure was verified
//!
//! The first version scanned only top-level `src/*.rs`, matched only the
//! literal `lance_graph_contract::`, and carried its own copy of the
//! allowlist. Codex (PR #63) found three bypasses standing beside the one
//! path the disable run had walked — the fourteenth instance of
//! `ISS-LGJ-SECOND-VERDICT-BESIDE-THE-FIRST`. Every arm below was then
//! disable-verified red-then-green on its OWN path, not inferred from a
//! neighbour's:
//!
//! | bypass | how it would have passed | closed by | disable |
//! |---|---|---|---|
//! | forbidden module, direct | — | module allowlist | `use …::kanban::KanbanMove;` in `fixture.rs` |
//! | nested module dir | `read_dir` skipped directories | recursive walk | same line in `src/g11_probe/x.rs` |
//! | crate alias | `use lance_graph_contract as c; use c::kanban::…` has no `::` after the crate name | any `lance_graph_contract` NOT followed by `::` is itself a violation | `use lance_graph_contract as c;` |
//! | glob / `self` | `::*` and `{self}` start with no identifier, so the first scanner yielded nothing | a non-identifier continuation is a violation | `use lance_graph_contract::*;` |
//! | doc/code drift | the test never read the list it claims to keep in sync | `CLAUDE.md` + `Cargo.toml` lists are parsed and must equal `ALLOWED` | add `kanban` to the `CLAUDE.md` list |
//!
//! The anti-vacuity half asserts the scan actually saw the contract in use,
//! so an empty `src/` — or a scanner pointed at the wrong directory — cannot
//! pass by finding nothing.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

/// The G11 allowlist — the one the fence enforces. It must equal the lists
/// documented in `CLAUDE.md` § Enforcement and `Cargo.toml`; a test below
/// reads both and fails on any difference, in either direction.
const ALLOWED: &[&str] = &["canonical_node", "class_view", "facet", "ontology"];

const CRATE: &str = "lance_graph_contract";

/// One reference to the contract crate in a source text.
#[derive(Debug, PartialEq, Eq)]
enum Reference {
    /// `lance_graph_contract::<module>…` — the module is the first segment.
    Module(String),
    /// `lance_graph_contract::*`, `lance_graph_contract::{self, …}`, or any
    /// other continuation that is not a plain identifier: it names the whole
    /// crate at once, so nothing can allowlist it.
    Wildcard(String),
    /// `lance_graph_contract` not followed by `::` — an alias (`as c`), a
    /// bare `use lance_graph_contract;`, or `extern crate …`. Any of these
    /// lets a later path reach the crate under a name this scanner does not
    /// see, so the alias itself is the violation.
    Alias,
}

/// Every reference to the contract crate in `text`, with the byte offset of
/// each so a violation names its line.
///
/// Handles the spellings that occur (or could occur) in `src/`: a plain path,
/// a brace group (`lance_graph_contract::{class_view, ontology::X}`, each
/// top-level entry's first identifier is a module), a doc-comment link, a
/// glob, `self`, and a crate alias. Nested braces below the first module
/// segment are irrelevant — only the FIRST segment after the crate name is
/// the module.
fn references(text: &str) -> Vec<(usize, Reference)> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(rel) = text[from..].find(CRATE) {
        let at = from + rel;
        from = at + CRATE.len();
        // Reject a longer identifier that merely contains the crate name.
        let prev_is_ident = at > 0
            && text[..at]
                .chars()
                .next_back()
                .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_');
        let rest = &text[from..];
        let next_is_ident = rest
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_');
        if prev_is_ident || next_is_ident {
            continue;
        }
        let Some(after) = rest.strip_prefix("::") else {
            out.push((at, Reference::Alias));
            continue;
        };
        if let Some(group) = after.strip_prefix('{') {
            for entry in top_level_entries(group) {
                out.push((at, classify(entry.trim())));
            }
        } else {
            out.push((at, classify(after)));
        }
    }
    out
}

fn classify(segment: &str) -> Reference {
    match leading_ident(segment) {
        Some(id) if id != "self" => Reference::Module(id),
        _ => Reference::Wildcard(
            segment
                .chars()
                .take_while(|c| !c.is_whitespace() && !matches!(c, ';' | ',' | '}' | ')'))
                .take(12)
                .collect(),
        ),
    }
}

/// Comma-separated entries of a brace group, stopping at its closing brace.
fn top_level_entries(group: &str) -> Vec<String> {
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
    if !entry.trim().is_empty() {
        entries.push(entry);
    }
    entries
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

/// Every `.rs` file under `dir`, recursively — Cargo compiles nested module
/// directories, so the fence must read them too.
fn rust_sources(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        for entry in fs::read_dir(&d).unwrap_or_else(|e| panic!("{d:?}: {e}")) {
            let path = entry.expect("dir entry").path();
            if path.is_dir() {
                stack.push(path);
            } else if path.extension().and_then(|e| e.to_str()) == Some("rs") {
                out.push(path);
            }
        }
    }
    out.sort();
    out
}

fn crate_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

/// The module set a documented allowlist names: the FIRST
/// `lance_graph_contract::{…}` brace group after `anchor` in `text`.
fn documented_allowlist(text: &str, anchor: &str, what: &str) -> BTreeSet<String> {
    let start = text
        .find(anchor)
        .unwrap_or_else(|| panic!("{what}: anchor {anchor:?} not found"));
    let needle = format!("{CRATE}::{{");
    let at = text[start..]
        .find(&needle)
        .map(|i| start + i + needle.len())
        .unwrap_or_else(|| panic!("{what}: no `{needle}` after the anchor"));
    // Markdown/TOML wrap the list across lines with `#` or `>` furniture;
    // strip that before parsing so a wrapped list reads as one group.
    let flat: String = text[at..]
        .lines()
        .map(|l| l.trim_start_matches(['#', '>', ' ']))
        .collect::<Vec<_>>()
        .join(" ");
    top_level_entries(&flat)
        .iter()
        .filter_map(|e| leading_ident(e.trim()))
        .collect()
}

#[test]
fn g11_src_imports_only_the_allowlisted_contract_modules() {
    let src = crate_root().join("src");
    let files = rust_sources(&src);
    let mut files_referencing = 0usize;
    let mut seen = BTreeSet::new();
    let mut violations = Vec::new();

    for path in &files {
        let text = fs::read_to_string(path).expect("source is UTF-8");
        let refs = references(&text);
        if !refs.is_empty() {
            files_referencing += 1;
        }
        let name = path.strip_prefix(&src).unwrap_or(path).display();
        for (at, r) in refs {
            let line = line_of(&text, at);
            match r {
                Reference::Module(m) if ALLOWED.contains(&m.as_str()) => {
                    seen.insert(m);
                }
                Reference::Module(m) => violations.push(format!(
                    "{name}:{line}: `{CRATE}::{m}` is outside the G11 allowlist {ALLOWED:?}"
                )),
                Reference::Wildcard(s) => violations.push(format!(
                    "{name}:{line}: `{CRATE}::{s}…` — a glob or `self` import names the whole crate; \
                     import the allowlisted module by name"
                )),
                Reference::Alias => violations.push(format!(
                    "{name}:{line}: `{CRATE}` is used without `::` — an alias or bare crate import \
                     would let later paths bypass this fence; name the module at the use site"
                )),
            }
        }
    }

    // Anti-vacuity: the fence must have looked at real code that really uses
    // the contract. A scan that sees nothing proves nothing.
    assert!(
        files.len() >= 5,
        "scanned only {} files under {src:?}",
        files.len()
    );
    assert!(
        files_referencing >= 3,
        "only {files_referencing} file(s) reference {CRATE} — wrong directory?"
    );
    assert!(
        seen.contains("class_view") && seen.contains("facet"),
        "expected the known live imports (class_view, facet); saw {seen:?}"
    );

    assert!(
        violations.is_empty(),
        "G11 contract-import fence breached:\n  {}\n\
         The cognitive modules compile unconditionally; this list is the only barrier. \
         Widen ALLOWED here AND in CLAUDE.md § Enforcement AND Cargo.toml in the same commit, \
         or drop the import.",
        violations.join("\n  ")
    );
}

/// The documented lists ARE the enforced list. Adding a module to `ALLOWED`
/// without the promised same-commit doc edit — or the reverse — fails here,
/// in both directions.
#[test]
fn g11_documented_allowlists_equal_the_enforced_one() {
    let enforced: BTreeSet<String> = ALLOWED.iter().map(|s| s.to_string()).collect();
    let root = crate_root();

    let claude_md = fs::read_to_string(root.join("../../CLAUDE.md")).expect("root CLAUDE.md");
    let documented = documented_allowlist(&claude_md, "G11 contract-import fence", "CLAUDE.md");
    assert_eq!(
        documented, enforced,
        "CLAUDE.md § Enforcement lists {documented:?}; the fence enforces {enforced:?}"
    );

    let cargo_toml = fs::read_to_string(root.join("Cargo.toml")).expect("Cargo.toml");
    let documented = documented_allowlist(&cargo_toml, "may import ONLY", "Cargo.toml");
    assert_eq!(
        documented, enforced,
        "Cargo.toml's comment lists {documented:?}; the fence enforces {enforced:?}"
    );
}

/// The parser half, pinned on its own so a fence failure can be told apart
/// from a scanner bug. Every spelling the fence must see, including the
/// three bypasses Codex found on the first version.
#[test]
fn the_scanner_reads_every_spelling() {
    use Reference::*;
    let text = "use lance_graph_contract::facet::CascadeShape;\n\
                use lance_graph_contract::{class_view::{ClassView, FieldMask}, ontology};\n\
                /// see [`X`](lance_graph_contract::kanban::KanbanMove)\n\
                use lance_graph_contract as contract;\n\
                use lance_graph_contract::*;\n\
                use lance_graph_contract::{self, mul};\n\
                let not_the_crate = my_lance_graph_contract_thing;\n";
    let refs: Vec<(usize, Reference)> = references(text)
        .into_iter()
        .map(|(at, r)| (line_of(text, at), r))
        .collect();
    assert_eq!(
        refs,
        [
            (1, Module("facet".into())),
            (2, Module("class_view".into())),
            (2, Module("ontology".into())),
            (3, Module("kanban".into())),
            (4, Alias),
            (5, Wildcard("*".into())),
            (6, Wildcard("self".into())),
            (6, Module("mul".into())),
        ]
    );
}

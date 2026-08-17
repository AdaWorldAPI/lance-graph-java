# Wave: Ghidra G1 (lift proof) + G2 (program image format)

> Executes `ghidra-integration-v1.md` G1 then G2. **DO NOT DISPATCH** —
> shelved with the consumer waves per the operator's calcify-first ruling;
> G1 is technically independent of W3 but momentum stays on the substrate
> waves until called. Cross-repo: G1's script lands in `AdaWorldAPI/ghidra`
> (cloned at `/workspace/ghidra`, 12.2 DEV, Java 25+); G2's loader lands
> here. One PR per repo; ghidra PR merges first.

---

## Dispatch 1 — G1: the lift proof (ghidra repo)

**Decision D0 (orchestrator, before spawning):** run `analyzeHeadless`
from a RELEASED Ghidra distribution vs building the fork (Gradle ≥ 9.1 +
JDK 25 — available in-container but a full Ghidra build is heavy).
Guidance: try the release binary first; the fork stays source-of-truth
for op semantics and HOSTS the script either way. Record the choice.

### Worker roster (2 Sonnet workers, disjoint)

**GH1 — the export post-script.**
YOUR SCOPE: NEW `/workspace/ghidra/ghidra_scripts/ExportPcodeImage.java`
(GhidraScript subclass).
- Walk ONE named function of the current program; for each instruction,
  emit `getPcode()` ops in a deterministic text form:
  `SEQ<TAB>OPCODE_MNEMONIC<TAB>out(space,offset,size)<TAB>in0(...)…` —
  one op per line, nothing locale- or address-format-dependent (fixed
  hex widths).
- Read `PcodeOp.java`'s mnemonic surface for naming; NO custom opcode
  numbering — emit Ghidra's own `getOpcode()` int AND mnemonic (both, so
  G2 can bind by number and humans can diff by name).
**GH2 — the Toy fixture + expected listing.**
YOUR SCOPE: NEW `/workspace/ghidra/ghidra_scripts/lgj_fixtures/`
(a tiny Toy-ISA assembly source + build notes + the hand-derived
expected op sequence for its one function, from reading the Toy SLEIGH
spec — this is the independent half of the falsifier).

### Orchestrator-only

Run `analyzeHeadless <proj> -import <toy binary> -postScript
ExportPcodeImage.java -deleteProject` centrally. **Falsifier:** the
dump matches GH2's hand-derived expectation AND the decompiler listing
of the same function (two Ghidra surfaces + one human derivation).
PR to `AdaWorldAPI/ghidra` per its house rules (check for a CLAUDE.md /
contribution constraints in the fork before opening).

---

## Dispatch 2 — G2: the image format + Rust loader (this repo)

**Precondition:** G1 merged; a real dump file exists to design against.

### Worker roster (2 Sonnet workers, disjoint)

**GI1 — format + loader.**
YOUR SCOPE: NEW crate `native/lgj-pcode-image/` (NOT `lgj-abi` — the
membrane stays lean; this is consumer-tier), `src/lib.rs`, `src/format.rs`.
- Versioned LE binary format: header (magic, version u32, op_count u64)
  + op records (`opcode u32`, varnode triples `(space u32, offset u64,
  size u32)` — out then inputs, input_count u32). A text→binary
  converter from G1's dump format.
- Loader: refuse (typed error, never partial) on bad magic / version /
  truncation / trailing bytes.
**GI2 — tests.**
YOUR SCOPE: NEW `native/lgj-pcode-image/src/tests.rs` (or `#[cfg(test)]`
in-file per repo style — match `lgj-abi`).
- Round-trip: emit → load → re-emit byte-identical.
- Hand-built image of known ops decodes to exactly those ops.
- Refusal matrix: truncated at every field boundary of the FIRST op +
  garbled magic + wrong version + trailing garbage — each a distinct
  typed error, each a `should_panic`-free `Err` (loaders return, never
  panic).

### Orchestrator-only

Central gates (`cargo test`/`clippy -D warnings`/`fmt` scoped `-p
lgj-pcode-image`); **disable-run:** remove the trailing-bytes check →
exactly the trailing-garbage test goes red; restore. Board + PR + arc
entry per rhythm.

**STOP conditions:** G1's real dump reveals varnode features the format
sketch can't carry (e.g. CALLOTHER user-op indices) → format design
returns to the plan, not improvised in a worker.

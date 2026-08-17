# ghidra-integration-v1 — the P-code front end and the parity oracle

> **Status: PLANNED** (2026-08-17). Companion to `ogar-machine-v1.md` —
> that plan owns the population-execution side; this one owns the Ghidra
> side: how a legacy binary becomes a normalized program image, and how
> Ghidra's own emulator becomes the parity oracle. G0 (archaeology) ran
> against the real clone before this plan was written; every path and
> number below is verified, not sketched.

## G0 — archaeology (DONE 2026-08-17, against `/workspace/ghidra` @ `52bb03d`)

`AdaWorldAPI/ghidra` is a fork of upstream at **12.2 DEV**
(`Ghidra/application.properties`), minimum **Java 25**, Gradle ≥ 9.1 —
our JDK 26 toolchain covers it.

| fact | where | why it matters |
|---|---|---|
| **74 real P-code opcodes** (`CPUI_COPY = 1` … `CPUI_MAX = 75`) | `Ghidra/Features/Decompiler/src/decompile/cpp/opcodes.hh:37-131` | The discussion's ~13-op sketch was a fraction; the real normalized ISA is 74 ops (arithmetic incl. signed/unsigned compares, zext/sext, FLOAT_* family, MULTIEQUAL/INDIRECT decompiler ops). The population interpreter implements a **subset** and must halt loudly on the rest — see op-set discipline below |
| Java-side opcode mirror | `Ghidra/Framework/SoftwareModeling/src/main/java/ghidra/program/model/pcode/PcodeOp.java` | The lift script reads ops through this surface (`Instruction.getPcode()`) |
| Headless entry point | `Ghidra/RuntimeScripts/support/analyzeHeadless` (+ `analyzeHeadlessREADME.md`) | Lifting is a batch job: import binary → analyze → post-script → exit. No GUI anywhere in the loop |
| **In-tree P-code emulator** | `Ghidra/Framework/Emulation/src/main/java/ghidra/pcode/emu/` (`PcodeEmulator`, `PcodeMachine`, `BytesPcodeThread`, `DefaultPcodeThread`…) | **The parity oracle.** Ghidra ships a sequential reference execution of exactly the IR we will population-execute — the same role libtesseract played for tesseract-rs. We never have to *invent* ground truth |
| `Toy` processor | `Ghidra/Processors/Toy` | A teaching ISA with full SLEIGH spec — the smallest possible lift target for G1, before any real x86 binary |
| ~39 processor modules incl. x86, AARCH64, RISCV, JVM, Z80, 68000 | `Ghidra/Processors/` | The "don't implement x86" promise is real: every guest ISA arrives through SLEIGH, and the OGAR Machine sees only the one 74-op IR |
| Symbolic extension precedent | `Ghidra/Extensions/SymbolicSummaryZ3` | The "carry both branch populations" far-future direction has an in-tree symbolic P-code precedent to study first — do not design that from scratch |

License note: Ghidra core is Apache-2.0 (compatible with everything in
this stack); the `GPL/` subtree (demangler etc.) is not needed for
lifting and stays untouched.

## The integration shape — two roles, both offline

```
legacy binary ──analyzeHeadless + post-script──▶ normalized program image (LE file)
                                                       │
                              ┌────────────────────────┤
                              ▼                        ▼
                    Ghidra PcodeEmulator      OGAR Machine population path
                    (sequential ORACLE)       (rows = machine states)
                              │                        │
                              └──────── parity ────────┘
```

1. **Ghidra as lift-time compiler.** Runs ONCE per binary, offline. Never
   at OGAR-Machine runtime — the same footing tesseract-rs gives its C++
   oracles ("only the oracle's link deps, never in the Rust path").
2. **Ghidra as parity oracle.** `PcodeEmulator` executes the same program
   sequentially; the population path must match it bit-for-bit per world.
   This is the tesseract-rs byte-parity method transplanted: we diff
   against the reference *implementation*, not against our own scalar
   rewrite alone (which stays as the second, independent check).

## Waves

| wave | deliverable | falsifier |
|---|---|---|
| **G1 — lift proof** | An `analyzeHeadless` post-script (lands in the ghidra fork, `ghidra_scripts/`) that walks one function of a **Toy**-ISA test binary and dumps every instruction's `getPcode()` sequence to a deterministic text form | The dump's opcode mnemonics/order for that function match the decompiler's own listing view of the same function — two independent Ghidra surfaces agreeing, not one surface trusted |
| **G2 — the image format** | A versioned LE program-image format (header + op records: `opcode u32` + varnode triples `(space u32, offset u64, size u32)` for output/inputs) + a Rust loader in a NEW crate (NOT `lgj-abi` — the membrane stays lean; the loader is a consumer-tier crate) | Round-trip (emit → load → re-emit byte-identical); a hand-built image of known ops decodes to exactly those ops; a truncated/garbled image is refused with a status, never partially loaded |
| **G3 — probe P-M1** (shared gate with `ogar-machine-v1.md`) | Population interpreter over a deliberately small op subset (COPY, INT_ADD/SUB/AND/OR/XOR, INT_EQUAL/LESS, CBRANCH, BRANCH, RETURN), one hand-written program, 64K input worlds over the row store | Bit-identical final states vs an independent scalar Rust interpreter; planted-divergence two-sided test (one known behavioral difference between two program variants → the divergence mask finds exactly that world set) |
| **G4 — oracle parity** | The same image through Ghidra's `PcodeEmulator` (JVM side, sequential, N sampled worlds) vs the population path | Final machine state bit-identical per sampled world; a deliberately mis-implemented op (disable-run) must be CAUGHT by the oracle diff |

Sequencing: G1 is independently startable (pure Ghidra-side, no OGAR
Machine code). G2 depends on G1's real dump shape. G3/G4 are gated
exactly as `ogar-machine-v1.md` gates them (W3 + one W5 example shipped
first). One wave = one PR, gates central, per house style.

## Op-set discipline (the falsifiability rule applied to an ISA)

74 opcodes exist; the interpreter implements a declared subset. Any op
outside the subset must **halt that world loudly** — a per-row status
lane recording "unimplemented op X at cycle N" — never skip, never
best-effort. A world that halted is excluded from parity comparison *and
counted*, so "we handled the corpus" can never silently mean "we skipped
the hard ops." (Same family as tesseract-rs's "a guard that cannot fire
is the defect one level up.")

## Boundaries (so drift is visible)

- **No Ghidra at OGAR-Machine runtime.** Lift-time + oracle-time only.
- **No second object model.** The image loader emits ops + varnodes into
  lanes; it does not grow a Rust AST of P-code (the Core-first lesson —
  the substrate is the model).
- **Guest RAM stays dense** (ogar-machine-v1 rule 3); varnode SPACE ids
  are how register-lane vs memory-page routing is decided at execute
  time.
- **Fork discipline:** the lift script and any exporter live in the
  `AdaWorldAPI/ghidra` fork; whether G1 *runs* against a fork build or a
  released Ghidra distribution is a cost decision made at G1 time (the
  fork build needs JDK 25 + Gradle 9.1 — available here, but a release
  binary may be cheaper; the fork remains the source of truth either
  way).

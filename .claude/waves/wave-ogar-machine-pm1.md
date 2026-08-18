# Wave: OGAR Machine probe P-M1 (population interpreter, first falsifiable step)

> Executes `ogar-machine-v1.md`'s probe P-M1 (== `ghidra-integration-v1.md`
> G3, with G4 oracle parity as its second dispatch). **BLOCKED — do not
> dispatch.** Gate list, all required, none waivable:
>
> 1. W3 merged (Java facade — the consumer workflow exists).
> 2. At least ONE W5 consumer example shipped (the
>    consumer-never-grows-the-membrane rule has a proven round-trip).
> 3. **[RECONCILED 2026-08-18 — was "Ghidra G1+G2 merged"]** `ruff_r2il`
>    PR2 (route→V3 projection + codebook wiring, `AdaWorldAPI/ruff`) AND
>    PR3 (the classid mint for the R2IL container concept in
>    `lance-graph-contract::ogar_codebook`, O5) merged — a real,
>    already-typed program image exists on THAT path. `wave-ghidra-g1-g2.md`
>    is superseded and will never satisfy this gate; do not dispatch it to
>    try. PR1 (ore/furnace/slag intake + the addressed residual ledger) is
>    already merged; PR2 is explicitly gated on PR1's corpus numbers and is
>    the in-flight "drill-down proposer" work — check
>    `AdaWorldAPI/ruff/.claude/plans/r2il-behavioral-ir-v1.md`'s Wave plan
>    section before re-deriving this gate's status.
> 4. An explicit operator go (this direction is exploratory by ruling).

## What P-M1 is (and is deliberately not)

ONE hand-written P-code program (~a dozen ops from the declared subset:
COPY, INT_ADD/SUB/AND/OR/XOR, INT_EQUAL, INT_LESS, CBRANCH, BRANCH,
RETURN), serialized in the G2 image format, executed over **64K input
worlds** two independent ways. It is NOT an emulator, NOT x86, NOT
memory-paged — registers-only machine state, exactly enough to falsify
the population-execution premise.

## Worker roster at dispatch time (3 Sonnet workers, disjoint)

**M1 — scalar reference interpreter.**
YOUR SCOPE: NEW crate `native/lgj-machine-probe/`, file `src/scalar.rs`.
Plain-Rust sequential interpreter over the G2 image: one world in, final
register state out. No ndarray anywhere in this file — its independence
IS the falsifier (the kernels.rs scalar-reference doctrine).
**M2 — population path.**
YOUR SCOPE: `src/population.rs` (+ `src/state.rs` shared layout consts —
M2 owns both). Machine-state lanes over the row store shape (PC lane,
register lanes, halted/status lane — the op-set-discipline lane from the
plan); per-cycle: opcode classify → population masks → masked bulk ops
via `ndarray::simd` primitives ONLY (provenance rule §8 applies here
unchanged; missing primitives go to ndarray under W1a, never local).
**M3 — falsifier tests.**
YOUR SCOPE: `src/tests.rs`.
- Parity: all 64K worlds' final states bit-identical, scalar vs
  population, across 3 seeds.
- Planted divergence (two-sided): two program variants differing in ONE
  op; the divergence mask (outcome XOR) must select EXACTLY the world
  set the scalar reference says diverges — no more, no fewer; and the
  identical-programs control must yield an EMPTY divergence mask.
- Halt discipline: a program containing one out-of-subset op → every
  world that reaches it halts loudly with the op recorded; worlds that
  branch around it complete; the counts are asserted, not eyeballed.

## Orchestrator-only

Central gates scoped `-p lgj-machine-probe`; **disable-run:** break one
population op's semantics (e.g. INT_ADD → wrapping SUB) → parity red;
restore. Then Dispatch 2 (G4): the same image through Ghidra's own
`PcodeEmulator` (JVM side) for N sampled worlds — reference-
implementation parity, the tesseract-rs method; its disable-run: a
deliberately mis-implemented op must be CAUGHT by the oracle diff.

Board + PR + arc per rhythm. Every claim in the eventual PR body phrases
performance per the baseline-dependent claims discipline
(`prior-art-and-the-layout-bridge-claim.md` §4) — P-M1 makes NO speed
claims at all; it exists to prove correctness of the execution model.

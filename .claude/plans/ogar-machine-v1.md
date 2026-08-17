# ogar-machine-v1 — the population emulator (EXPLORATORY, named not scheduled)

> **Status: NAMED** (2026-08-17). Source: the operator's second archived
> ChatGPT context, assessed here once so it is not re-mined. Unlike the
> first archived discussion (convergent confirmation —
> `.claude/knowledge/prior-art-and-the-layout-bridge-claim.md`), this one
> proposes a **genuinely new workload** for the shipped substrate, and the
> operator has attached `AdaWorldAPI/ghidra` to the session for it.
> Nothing here reorders the wave plan: W3 (Java `RowStore` facade) remains
> the next action; this plan is the shelf the idea sits on, with its
> strong/weak claims already separated.

## The inversion, in one sentence

A VM is `state + transition → new state`; instead of one machine executing
instructions sequentially, **one row is one machine STATE**, and the
substrate executes each *operation* across the whole population at once:

```
65,536 execution contexts (rows)
  → classify current opcode        (a classid-style scan → population masks)
  → per-population bulk execution  (SIMD ADD over the ADD mask, gathers
                                    over the LOAD mask, mask updates for
                                    BRANCH)
  → next state
```

The VM never asks *"what does machine 17 execute?"* — it asks *"which
machines currently execute ADD?"* **Control flow becomes population
masks.** That is not an analogy to the shipped substrate; it is literally
its op set: rows, classid scans, mask algebra, masked bulk ops, survivors.

## The claim discipline (the discussion's own, kept sharp)

- **Weak claim, explicitly rejected**: "emulate a Pentium faster than
  QEMU." One interactive instance is branchy and sequential; SIMD is not
  fairy dust for `A then B then C`. Do not build toward this and do not
  let a demo imply it.
- **Strong claim, the actual target**: *from Java, explore 65,536
  executions of an unsupported binary simultaneously, time-travel them,
  XOR them against the replacement, and return only the worlds where
  behavior diverges.* Every element of that sentence maps to something
  this stack already does well (population masks / Lance versioning /
  mask XOR / survivors-only inspection).

## Ghidra's role — front-end compiler, never a peer emulator

Do NOT implement x86/ARM/68k. Ghidra lifts the legacy binary **once** to
P-code (a small normalized op set: COPY, LOAD, STORE, BRANCH, CBRANCH,
CALL, RETURN, INT_ADD/SUB/MULT/AND/OR/XOR, …); the OGAR Machine executes
P-code only. `legacy.exe → Ghidra (once) → normalized program image →
population execution`. This is the same shape as the workspace's
ruff→OGAR harvest arms: an existing analyzer becomes the transcoder
front-end, and the substrate executes the normalized IR. First concrete
archaeology step in the fresh clone: locate the P-code opcode enum and
SLEIGH lifting surface, and size the *real* op set (the list above is the
discussion's sketch, not a verified inventory).

## The four load-bearing design rules captured from the discussion

1. **Differential migration testing is the killer demo.** Same 65,536
   input worlds through the legacy machine and the replacement;
   `legacy_output[] XOR new_output[] → divergence_mask`; show the 15 of
   65,536 worlds the rewrite broke, click into one, see the concrete
   state. (Note for a future warden pass: outcome-XOR here is a
   *comparison*, not a state-transition kernel — it does not touch
   lance-graph's `I-SUBSTRATE-MARKOV` XOR restriction, which governs
   transition bundling.)
2. **Lance is the time machine.** Per-cycle sealed diffs (changed
   registers / changed pages), `machine.at(cycle)`, `machine.diff(a, b)` —
   deterministic rewind as a *consequence* of the substrate, matching the
   workspace's episodic-=-Lance-versions doctrine. No bespoke snapshot
   format.
3. **Memory purity resistance** (the discussion's own best guard): guest
   RAM does NOT go into 512-byte rows. Semantic machine state (PC, regs,
   flags, device state, identities, relations) → SoA lanes; the memory
   image (pages) → a dense backing store the lens points at. This mirrors
   the substrate's own key/value split — meaning and addressing in the
   graph, bulk bytes dense and compressible.
4. **Semantic shims erode the emulator.** Recognize stable external
   surfaces (USER/GDI/KERNEL/ODBC/filesystem/registry) and progressively
   replace instruction execution with semantic operations — 90/10 →
   40/60 → 5/95. The endpoint is not `binary → reconstructed source` but
   `binary → normalized behavior machine`, which is the OGAR transpile
   doctrine's 85/15 split arrived at by *running* the program instead of
   parsing it.

Also named, further out: carrying BOTH branch populations at a CBRANCH
(state forking) drifts toward symbolic execution / abstract interpretation
done as masks over concrete states — file under "unthinkable until the
plain version works."

## Mapping onto shipped primitives (why this is not science fiction)

| OGAR Machine need | shipped today |
|---|---|
| 65,536 contexts as rows | the row store (64K × 512 B was the design point) |
| opcode classification → masks | `eq_u32`-family scans + `lgj_op_eq_classid` shape |
| population dispatch | mask algebra (`and`/`or`/`count`), masks-parent-on-rowstore |
| divergence mask | mask XOR (a small W-tier ABI addition when needed — goes through the wave process, never ad hoc) |
| survivors-only inspection | the whole thesis (PR #1's laziness/mask discipline) |
| time travel | Lance versioning (not yet wired here; lance-graph owns it) |

## Gate — what must exist before ANY OGAR Machine code

1. W3 (Java facade) and at least one W5 consumer example shipped, so the
   consumer-never-grows-the-membrane rule has a proven workflow.
2. Ghidra archaeology: the real P-code op inventory + how a lifted
   program image serializes (sized, not sketched).
3. **Probe P-M1** (the first falsifiable step, deliberately tiny): ONE
   hand-written P-code program (a dozen ops, one CBRANCH), 64K input
   worlds, executed by (a) a scalar reference interpreter and (b) the
   population path over the row store — bit-identical final states
   required. Two-sided divergence falsifier: plant one known behavioral
   difference between two program variants and require the divergence
   mask to find exactly that world set, no more, no fewer.

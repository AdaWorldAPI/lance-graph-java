# Execution waves — agent-dispatch maps, one per plan

> A **plan** says what and why; a **wave file** says exactly who edits
> which file, in what order, under which guardrails, and which command
> gates it. A wave file must be dispatchable as-is: the orchestrator
> copies worker briefs out of it verbatim.

| wave file | plan it executes | dispatch state |
|---|---|---|
| `wave-substrate-w3-w4.md` | `lgj-soa-substrate-v1.md` (W3 Java facade, W4 bench) | **W3 READY** — next action |
| `wave-consumer-trades.md` | `consumer-world-trades-v1.md` | gated on W3 |
| `wave-consumer-bricks.md` | `consumer-bricks-analytics-v1.md` | gated on W3 |
| `wave-consumer-graph.md` | `consumer-graph-traversal-v1.md` | gated on W3 (+ possible W6 ABI wave) |
| `wave-ghidra-g1-g2.md` | `ghidra-integration-v1.md` (G1 lift proof, G2 image format) | G1 independently READY |
| `wave-ogar-machine-pm1.md` | `ogar-machine-v1.md` (probe P-M1 / G3+G4) | BLOCKED (gate list inside) |

## Standing rules, inherited by every wave (do not restate per file)

1. **Model policy:** orchestrator + anything synthesizing across sources =
   Opus-tier; bounded one-source-in/one-shape-out work = Sonnet workers.
   Never Haiku.
2. **Workers never run build tools or git.** No `cargo`, no `javac`, no
   `java`, no `git`, no worktrees. Edit-only. The orchestrator compiles,
   tests, lints, commits — centrally, once (`agent-cargo-hygiene.md`).
3. **Disjoint file ownership.** Two workers in one file is a lost-write
   race. Shared files (module wires, `AllTests`, build scripts, board
   files) are ORCHESTRATOR-ONLY and are edited after workers land.
4. **Every worker brief carries the verbatim preamble** (below) plus its
   file scope and STOP triggers. A spawn without it is a protocol
   violation.
5. **A wave is done when its gate table is green AND its disable-runs ran
   red-then-green** — never when workers report success ("completed" is a
   process status, not a quality status).
6. **One wave = one PR** (cross-repo waves: one PR per repo, merge order
   stated in the wave file). Board updates land in the same commit.

## The verbatim worker preamble (copy into every brief)

```
You are an edit-only worker. HARD RULES:
- Do NOT run cargo, javac, java, git, gradle, or any build/VCS command
  — not once. The orchestrator compiles and tests centrally.
- Do NOT create worktrees, branches, or commits.
- Edit ONLY the files listed under YOUR SCOPE. Files under OTHER AGENTS
  or ORCHESTRATOR-ONLY must not be touched, even for a one-line import.
- Do not claim code compiles or tests pass — you did not run them.
  Report what you WROTE, plus anything you could not resolve.
- Read .claude/board/LATEST_STATE.md and the knowledge docs named in
  your brief BEFORE writing. Do not write to any board file.
- STOP and report instead of improvising if: a symbol/type you need does
  not exist; the contract (docs/abi.md) seems to disagree with code; or
  your task requires touching a file outside your scope.
```

# The Executor Role Contract — Capability Belongs To The Role, Not The Tier

> READ BY: every agent dispatched as a worker, and every agent writing a worker
> brief. Read together with `agent-cargo-hygiene.md`, which owns the cargo
> prohibition and the resource numbers.

## Status: CONTRACT (2026-08-26)

## Scope — what this file does and does not own

`agent-cargo-hygiene.md` owns the cargo prohibition, the target-residue
arithmetic, the session ceiling, and the build profile. **It is not restated
here.** This file owns three things that were previously unwritten:

1. why the limit attaches to the role rather than the model tier,
2. what an executor may and may not do *besides* compiling,
3. the boundary between reporting and concluding.

## The One-Line Rule

An executor **reads, greps, edits named files, and reports**. It does not compile,
does not clean, does not create branches, does not touch manifests, does not
append to the board, and does not conclude.

## Why role, not tier

A capability limit belongs to the **role**, not to whoever fills it. A delegated
worker may not spend wall clock and disk it does not own — true of any tier
occupying the role, and it stays true when tier availability changes.

| | lives in | why |
|---|---|---|
| **the contract** | this repo | enforceable, greppable, stable across sessions |
| **the tier assignment** | the session brief | variable, availability-dependent, a session decision |

The practical payoff: a tier that becomes unavailable — or returns — is swapped by
changing one line in a brief, because the guardrails that made the assignment safe
were never attached to the tier's name.

This also resolves an otherwise real tension. A repo-level model policy that names
tiers by hand ages badly and collides with the rule that **no model identifier
belongs in any committed artifact**. Naming the *contract* in the repo and the
*tier* in the brief satisfies both.

## Forbidden to an executor (beyond cargo)

| forbidden | why | who does it instead |
|---|---|---|
| `rm -rf target` / any disk reclamation | destroys a build the orchestrator may be mid-way through | the orchestrator, between batches |
| branch creation, checkout, rebase, force-push | one tree, one writer | the orchestrator |
| editing `Cargo.toml`, `rust-toolchain.toml`, a `[patch]` section, CI files | a one-character divergence in a `[patch]` URL resolves two copies of the same crate — and therefore two incompatible copies of every type it exports, in one binary | the orchestrator |
| appending to `board/` | one-writer rule (`board/README.md`) | the orchestrator |
| declaring anything ABSENT | see below | reported upward as *not found by this search* |

## Permitted

- `Read` (whole files), `Grep`, `Glob`
- `Edit` / `Write` within the files named in its brief
- reporting findings upward in the brief's output shape

## Reporting, not concluding

An executor reports **what it searched and what it found**, never a verdict about
what exists.

- finding: `searched crates/*/src/** for Signed360 — 0 hits`
- conclusion the executor may not draw: `Signed360 does not exist`

A consumer-side search cannot show that a substrate lacks something; it shows only
that this search did not find it. In a multi-crate workspace that gap is wide. The
upgrade from the first form to the second is the most expensive habit observed
across these repos — a sibling repo retracted four absence claims in a single day,
one of which concerned a type that had shipped natively and exactly the whole time.

The orchestrator may promote a finding to a conclusion **only** with a
substrate-side `file:line` citation, or by keeping the honest form: *not found,
searched `<scope>`*.

## Grep is discovery, never comprehension

Inherited, and mechanically injected in a sibling repo by a PreToolUse hook:
`Grep`/`rg`/`sed`/`tail`/`head` locate a symbol or a file. Acting on a match —
editing, deleting, judging, or claiming to understand a file — before a **full
read** of that file is forbidden. The rule exists because code was once deleted
having been only pattern-matched, never read.

## Falsifier

- Any `cargo` invocation in an executor transcript is a contract breach; the brief
  that permitted it is the defect, not the worker.
- Any session brief that names a model tier **and** grants build permission has
  conflated contract with assignment — split it.
- Any committed artifact naming a model tier is a block.
- Any worker report containing "does not exist" without a searched-scope
  qualifier is a block on the report, before its content is trusted.

## Cross-reference

`agent-cargo-hygiene.md` (cargo, residue, ceiling, profile),
`board/README.md` (one-writer rule),
`.claude/GOLDSTANDARD.md` Part V.

# Agent Cargo Hygiene — one target dir, no N× build residue

> READ BY: every agent spawn touching native/lgj-abi or ndarray; MANDATORY
> in every worker brief before it runs, per the operator directive below

## Status: RULE (operator directive, 2026-08-17: "Block agents from using
## cargo to avoid target residue overflow")

## The problem, measured

`/home/user/ndarray/target` alone is already **2.7 GB** and
`/home/user/lance-graph-java/target` **602 MB**, from a single 4-agent fan-out
that ran this session. Each spawned agent that runs its own `cargo
build`/`check`/`test` against a fresh or divergent `target/` state multiplies
that residue — the exact failure mode `lance-graph`'s own
`.claude/rules/agent-cargo-hygiene.md` names: N agents × N target dirs, cold
compiles competing for the same cores, disk exhaustion.

## The rule

- **The orchestrating main thread (Opus) runs cargo freely** and is the ONLY
  actor that compiles/lints/tests. One build, not N.
- **Spawned agents do NOT run `cargo build`/`check`/`test`/`clippy` at all**,
  full stop — no "targeted `cargo test` is fine" carve-out here (unlike
  `lance-graph`'s own version of this rule, which allows a scoped test
  against the shared `target/`). This repo is small enough, and young enough,
  that the operator's directive is read as the stricter reading: agents edit
  and reason; the orchestrator verifies.
- **No `isolation: "worktree"`** on any agent spawn touching `native/lgj-abi`
  or `/home/user/ndarray` — a worktree mints its own `target/` and is exactly
  the multiplication this rule exists to prevent.
- **Every worker brief for this repo's Rust code MUST state explicitly**:
  *"Do not run cargo in any form. Write and reason about the code; the
  orchestrator compiles, tests, and lints centrally after your edits land."*

## What this retroactively flags

The first vertical-slice fan-out (`wf_23ad2110-b1e`, dispatched before this
rule was stated) told its Rust-touching agents they **may** run cargo,
scoped to `CARGO_TARGET_DIR=/home/user/lance-graph-java/target`. That
predates this directive and is not itself a violation of a rule that didn't
exist yet — but it is exactly why the rule now exists, and it is why
`STATUS_BOARD.md`'s `D-LGJ-AUDIT` entry includes checking `target/` size
after that wave lands, before dispatching anything further. No agent
spawned AFTER this doc exists gets cargo permission again.

## Why not a settings.json deny instead

A blanket `Bash` permission deny on `cargo build`/`test`/etc. would also
block the orchestrator's own centralized verification — the harness has no
clean mechanism to say "deny for spawned subagents, allow for the top-level
session" short of per-agent-type tool restriction, and `Bash` cannot be
scoped to "all commands except cargo" that granularly without also risking
blocking legitimate orchestrator work. So enforcement here is the same
mechanism `lance-graph`'s own hygiene rule uses successfully: **explicit,
mandatory brief language**, checked by whoever reads the agent's returned
report for a cargo invocation that shouldn't be there.

## Falsifier

Before trusting any agent report that claims code "compiles" or "tests
pass," check the report for evidence it actually ran cargo — if it did, and
this doc predates its spawn, that is a hygiene violation to flag, not a
bonus. The orchestrator re-verifies centrally regardless of what the agent
claims.

---

## Extension 2026-08-26 — the session ceiling and the build profile

Two measured facts that make the rule above binding rather than advisory, and one
consequence.

### The reported disk is not the ceiling

The environment reports ~250 GB free. The **real limit is ~38 GB per session,
shared across every branch and worktree**. Plan against 38. This is why the
no-worktree clause above is load-bearing and not merely tidy: a worktree does not
get its own budget, it consumes the same one.

### Reclaim `target/debug/`, not just `target/`

`target/` alone is not enough — `target/debug/` is where the weight sits and must
go with it. Old branches accumulate their own trees; prune them **on a schedule**,
not when the session dies.

### `debug = 0` — measured

| profile | disk | wall clock |
|---|---|---|
| default dev (`debug = 2`) | 14–18 GB | 18–40 min |
| `debug = 0` | 3–4 GB | 6–9 min |

Roughly 4× the disk and 3× the time, for debuginfo that a compile-check pass never
reads. Pin it:

```toml
[profile.dev]
debug = 0
```

Raise it deliberately and locally when someone is actually attaching a debugger,
and lower it again in the same session.

### Consequence: one build per batch

The orchestrator builds **once, after a batch of workers reports** — not once per
worker. At the default profile, two workers each triggering a build breach the
38 GB ceiling on the second one, and the session dies with the work unreported.

### Falsifier (mechanical)

```sh
grep -rn "debug = 2" .          # outside a dated, documented, local exception → block
du -sh target target/debug      # before dispatching a batch, not after
```

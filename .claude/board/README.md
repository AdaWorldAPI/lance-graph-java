# The board — what each file is for, and the rules that keep it honest

The board is **live session state, not reference**. Updating it after the
work, as cleanup, is the tell that it was being treated as a stale
artifact. Board artifacts land in the **same commit** as the change they
describe.

Modeled on lance-graph's board, deliberately smaller: this repo is one
ABI, one membrane and one facade, not a 22-crate workspace. Files are
added when a real gap shows up, not to mirror a larger repo's shape.

## The file set

| File | Answers | Updated when |
|---|---|---|
| `LATEST_STATE.md` | **What exists right now.** The current surface, newest first. | any shipped change |
| `PR_ARC_INVENTORY.md` | **Why it exists.** Per-PR Added / Locked / Deferred / Docs / Gates / Confidence. | every PR, at open |
| `STATUS_BOARD.md` | **Where each deliverable stands.** D-ids and their gate ladders. | a D-id changes rung |
| `EPIPHANIES.md` | **What we learned.** Findings, corrections, "aha" moments — the reusable half. | a finding generalises past its own PR |
| `ISSUES.md` | **What is open.** Double-entry: found and resolved both get written. | a blocker is found OR closed |
| `TECH_DEBT.md` | **What we owe.** Double-entry, same rule. | debt incurred OR paid |
| `AGENT_LOG.md` | **Who did what.** One entry per agent run; the orchestrating main thread is the SOLE writer. | a run completes |
| `CROSS_REPO_PRS.md` | **What we are gated on upstream.** The missing-capability STOP rule's paper trail. | an upstream PR lands that this repo needed |
| `INTEGRATION_PLANS.md` | **What is planned.** Versioned plan index; the plan itself lives in `.claude/plans/`. | a plan is written or superseded |
| `CODEX_REVIEW_CHECKLIST.md` | **What review looks for.** The recurring anti-patterns, with the incident each came from. | a review finds a NEW recurring shape |

## The rules

**Append-only.** Prepend new entries at the top. Never edit a past entry
except its `**Status:**` / `**Confidence:**` line. A correction gets its
own new dated entry citing what it corrects — the **storno** rule. The
history of being wrong is the most useful thing on the board; deleting it
costs a future session the same mistake.

**One writer per file.** The orchestrating main thread is the sole writer
of every board file. A sub-agent that must leave a record writes its OWN
tag-file and the orchestrator consolidates. Concurrent append to one file
is a lost-write race — and a shared append-log is the exact
shared-mutable-sink the substrate eliminated, re-created one layer up.
**Base case:** the consolidation is not itself an agent run and gets no
entry.

**The rule does not recurse.** A PR whose ENTIRE content is board hygiene
for prior PRs generates none of the obligations above — it is discharged
by the entries it wrote. A *mixed* PR still gets its entry: whatever it
landed besides hygiene is what the entry is for. Without this the rule is
an infinite chain.

**Measure, then pin.** Crossing counts, allocation numbers and thresholds
are recorded from measurement, never prediction. A disable-run is
red-then-green or it is not evidence — and a knob that does not BIND on
the fixture is not a disable, which is a mistake this repo has made more
than once and written down each time.

**No model identifier** in any committed artifact. Chat only.

## What is deliberately NOT here

lance-graph's board carries `sprint-log-*/`, `agent-tags/`, `exec-runs/`,
`AGENT_ORCHESTRATION_LOG.md`, `ARCHITECTURE_ENTROPY_LEDGER.md` and more.
Those exist because that repo runs large parallel worker fleets and
tracks a many-crate entropy collapse. This repo's sessions have been
main-thread or small fan-out. Add the fleet machinery when a fleet
actually runs — an empty ledger read as an authoritative one is worse
than an absent file.

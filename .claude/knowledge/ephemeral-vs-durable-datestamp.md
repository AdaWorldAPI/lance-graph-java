# A Datestamp In The Filename Is The Ephemerality Marker

> READ BY: any agent creating a file under `.claude/`, and whoever runs the
> archive sweep

## Status: FINDING (2026-08-26 — codifies a convention already in de-facto use)

## The One-Line Rule

**Dated filename ⇒ ephemeral. Undated filename ⇒ durable.** No dated file is
durable; no undated file is archived. The filename decides, so the archive sweep
is a glob and never a judgment call.

## Why a datestamp and not a timestamp

A **date** is session-grained: several artifacts from one working day share it,
which is the correct resolution for "this belonged to that session." A
**timestamp** claims an ordering precision that git already provides more
reliably, and invites the reader to infer sequence from a string instead of from
history. Use `YYYY-MM-DD`. Nothing finer.

## The convention already exists — this only names it

Across these workspaces the split is already visible in the file listings, it was
simply never stated as a rule:

| dated (ephemeral) | undated (durable) |
|---|---|
| `ATTENTION_MASK_AUDIT_2026_08_21.md` | `CODING_PRACTICES.md` |
| `CALIBRATION_REPORT_2026_04_03.md` | `BOOT.md` |
| `SESSION_2026_03_25_CROSS_REFERENCE.md` | `patterns.md` |
| `CROSS_REPO_AUDIT_2026_04_01.md` | `no-c-ever.md` |

Codifying it costs nothing and turns a habit into something a script can act on.

## What is durable by construction

Never datestamp these; they are corrected in place or by an appended entry, never
superseded by a newer file:

- `knowledge/*.md` — laws
- `agents/*.md` — role cards
- `board/README.md`, `board/LATEST_STATE.md`, `board/PR_ARC_INVENTORY.md`
- `CLAUDE.md`, `BOOT.md`

A law that needed a date in its name would be an admission that it is not a law.

## What is ephemeral by construction

Always datestamp these:

- audits, calibration reports, cross-repo sweeps
- session handovers and session notes
- probe results and wave reports
- anything whose title contains SESSION, AUDIT, REPORT, HANDOVER, SNAPSHOT, or
  STATUS-as-of

## Archive, not delete

```
.claude/archive/<YYYY>/<original-filename>
```

The sweep **moves**; it never deletes. Three obligations come with the move:

1. **The index is updated in the same commit.** `.claude/archive/README.md` is a
   table: original path, archived path, date, one line on what it covered. An
   archived file that nothing points at is a deleted file with extra steps.
2. **Inbound links are fixed in the same commit.** A retrieval footer pointing
   into `board/` must follow the file into `archive/`, or the footer has silently
   become a dead reference — which is exactly the failure the footer exists to
   prevent.
3. **Anything still open is extracted first.** See below. This is the step the
   sweep exists for; the moving is the cheap part.

Suggested cadence: monthly, or whenever a directory passes ~40 files. Not "when
someone notices."

## The sweep is an extraction, not a cleanup

Before a dated file is archived, it is read for **open items** — decisions never
taken, deferrals never revisited, `TODO`/`PENDING`/`DEFERRED`/`BLOCKED` markers,
and any claim that was never retracted and never confirmed.

Each open item goes to exactly one of:

| destination | when |
|---|---|
| `board/LATEST_STATE.md` | still live, still true |
| a `plans/` entry | still intended, needs its own specification |
| `knowledge/<law>.md` | it recurred, and now deserves a falsifier |
| the sweep's own gap list | genuinely unresolved, and nobody currently owns it |
| dropped | superseded — and the archived file is cited as the supersession |

The gap list is the honest half. **An archive sweep without a gap list pretends
the old boards contained nothing that mattered** — and the reason to sweep at all
is that they usually did.

## Falsifier

```sh
# a dated file still sitting in a hot directory past the cadence
ls .claude/board/*20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]*

# an undated file that got archived — miscategorised, or it was never durable
ls .claude/archive/**/ | grep -v '20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]'

# a datestamped law — a contradiction in terms
ls .claude/knowledge/ | grep '20[0-9][0-9]'
```

Any hit is a finding. An archive commit without a matching index row and without
a gap list is a block.

## Cross-reference

`board/README.md` (append-only, storno, one writer),
`.claude/GOLDSTANDARD.md` Part III.d (table for state, prose for lesson) —
a datestamped file is by definition state, so its durable residue is a **row**,
not a paragraph carried forward.

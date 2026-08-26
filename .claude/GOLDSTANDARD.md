# The `.claude/` Goldstandard — AdaWorldAPI workspaces

> Version 3, 2026-08-26. Supersedes v1 and v2 (both parked alongside, not
> deleted — the storno rule applies to this document too).
>
> Status: PROPOSAL, derived from the three active workspaces and one outside
> framework. Not operator-ruled. Nothing here overrides a repo's own locked rules.

## ⊘ Corrections to earlier versions

**v2 → v3 (2026-08-26).** v2 named a workspace that is no longer worked in as the
reference and built several sections around it. Reference removed. The rules that
originated there are restated on their own merit — each stands or falls on its
argument, not on where it was first seen. Scope is now the three active
workspaces: `lance-graph`, `lance-graph-java`, `ada-rs`.

**v1 → v2.** v1 proposed language subdirectories under `knowledge/`
(`PYTHON/`, `RUST/`). That arrangement is only needed where two runtimes coexist
in one repo. It is not the pattern. Removed. Related: `knowledge/` and **English**
are the default.

---

## The finding that shapes this document

The three workspaces are not three quality levels of one thing. They are
complementary halves.

| | structure | mechanics | evidence discipline |
|---|---|---|---|
| `lance-graph-java` | **best** — 5 dirs, 10 laws with falsifiers | none — no hooks, no drift tool | prose |
| `lance-graph` | drowned — ~100 top-level files, 86 KB `CLAUDE.md` | **best** — hooks, drift tool, BOOT, harvest | `⊘ SUPERSEDED` |
| `ada-rs` | absent — `plans/` only | none | `⊘` inline, dated |
| *Plumbline (outside)* | heavy — 87 agents, 214 config files | model-mediated, not mechanical | **evidence classes** |

**The Goldstandard is lgj's shape carrying lance-graph's mechanics, with
Plumbline's evidence vocabulary.**

One structural asymmetry is worth stating plainly, because it explains a class of
failure: **ada-rs has history without grounding.** Ephemeral containers, agents
pushing to branches, PRs merging in minutes, nobody's hands on a machine. Result:
four blockers hiding behind each other, six DTO fields drifted unnoticed, ~32
tests accumulated unrun.

The counterpart failure is grounding without history — one authoritative machine,
a human in the terminal, snapshots instead of version control. There the drift
class does not occur at all; but a correction cites a prose description rather
than a SHA, and "since when did this break" has no answer.

The standard is **grounded claims, addressably recorded.**

---

## Part I — The linking structure

`CLAUDE.md` is not documentation. It is the **policy guard** — the operator-ruled
law that would otherwise reset with the session. Everything else is reachable
*from* it.

### The size rule

`CLAUDE.md` stays under ~10 KB and links out. It never absorbs state.

```
lance-graph-java/CLAUDE.md      7 881 bytes
ada-rs/CLAUDE.md               10 677 bytes
lance-graph/CLAUDE.md          86 204 bytes      ← 11× lgj
```

At 86 KB it has stopped being a policy guard and become an archive: build
commands, dependency tables, a status section dated March, session notes. None of
it is law; all of it must be paged through to reach the parts that are.

### The five directories

```
.claude/
├── knowledge/   laws          — WHAT IS TRUE, and how to falsify it
├── agents/      roles         — WHO reads which law, and what they may do
├── plans/       intent        — WHAT WE INTEND, versioned, one topic each
├── waves/       present       — WHAT WE ARE DOING NOW, one per executable slice
└── board/       history       — WHAT HAPPENED, append-only
```

The separation that matters most: **`plans/` is intent, `board/` is history,
`waves/` is the present.** When a repo has only `plans/`, the plan file becomes
all three — which is exactly what happened to ada-rs's personality charter: 603
lines, eight commits, carrying its own wave results, an architecture correction,
and two retractions inside the specification.

### `CLAUDE.md`'s closing section is always the same shape

```markdown
## Session start

1. This file.
2. `.claude/knowledge/*.md` — the laws (each with its own falsifier).
3. `.claude/board/LATEST_STATE.md` — what exists, what is in flight.
4. `.claude/plans/<active-plan>.md` if touching <the domain it governs>.
`<normative-doc>` is normative for any <boundary> work.
```

Ordered, numbered, conditional on what is being touched. Not a bibliography.

---

## Part II — The knowledge file format (from lgj)

The single most transplantable artifact. Ten files, ~34 KB total — smaller than
one ada-rs charter, and load-bearing.

```markdown
# <Title — the rule as a sentence, not a topic>

> READ BY: <the exact roles that must load this>

## Status: FINDING | METHOD | CONTRACT  (locked by <authority> <date>)

The operator's own words, verbatim: **"<quote>"**    ← only if operator-ruled

## The One-Line Rule
## Consequences (all load-bearing, all checked mechanically)
## Falsifier          ← a grep, a build, or a diff check. Machine-runnable.
## Cross-reference
```

Four properties make it work:

1. **The title is the rule**, not the topic.
2. **`READ BY:`** routes the law to a role. A law nobody is told to read is a
   comment.
3. **Operator-set truth is quoted verbatim and marked `locked`** — the clean line
   between *derived* truth (an agent may revise it) and *set* truth (it may not).
4. **Every law ends in a falsifier a machine can run.** Not "be careful about".

### Two structural patterns worth stealing verbatim

**Forbidden-list + named exceptions.** List what may never be normal execution
state, then name the exceptions *by method-name prefix* — only methods starting
with `materialize` may emit row IDs, O(n) cost stated in the doc comment. "No
unnamed materialiser may exist." The exception is enforceable because it is
visible at the call site.

**Missing-capability STOP.** A consumer needing something the substrate lacks does
not hand-roll it one layer up. It stops; the capability lands as a substrate-tier
change first. This rule alone would have prevented three of ada-rs's four blockers.

---

## Part III — Evidence discipline

### III.a Evidence classes

Every claim carries the class of evidence behind it, so mock evidence cannot
silently become "done":

| class | means |
|---|---|
| `asserted` | someone wrote it down |
| `mock` | passes against a fixture or stub |
| `integration` | passes against the real neighbouring component |
| `real-boundary` | crossed the actual boundary (network, disk, foreign process) |
| `observed` | seen in the environment that matters, in the state that matters |

This already happens informally — the `#66` caveat retirement was a class change
from "verified out-of-tree" to "verified in-tree." Naming the classes turns a
caveat someone must remember to write into a field that is visibly empty.

### III.b The "does not prove" column

Beside every evidence artifact, its ceiling — in the same row, not three screens
below:

| date | artifact | supports | does **not** prove |
|---|---|---|---|

Same discipline as lgj's "Success criterion — nothing stronger", better
ergonomics: the ceiling sits next to the claim instead of at the end of the
document.

### III.c Named environment, addressable state

A behavioural claim needs **a named environment and a named state within it**,
and the state must be addressable — a SHA, or a snapshot with a stable identifier
a receipt can point at.

Two rules follow:

- **Reproduce the environment that actually runs the code, not an idealised one.**
  When a gate and the real runtime disagree, the default is to align the gate,
  not to reshape the code to suit the gate. The gate exists to reproduce reality;
  a gate adjusted away from reality has stopped being evidence.
- **A gate that is green on its first run has proven nothing.** It has not yet
  demonstrated that it can fail. Disable-runs are red-then-green or they are not
  evidence. (lgj states the same as *measure-then-pin*.)

Corollary: **evidence in an unaddressable state is one class weaker than it
looks.** A snapshot without an identifier cannot be re-invoked, so no receipt can
point at it.

### III.d Table for state, prose for lesson

The reason is a type argument. **A column is a type; a cell is either filled or
visibly empty.** Prose has no empty cells — it smooths gaps away, absorbs hedges
and repetition, and grows without anyone noticing. A table stays the same size and
changes cells: an upsert, not a narrative accretion.

Applied: a wave result is a **row whose status cell changes**, not a new section
appended to a plan. Had ada-rs's falsifier block been a table — one row each,
columns for status, evidence class, and does-not-prove — W2 would have been a
cell edit and the four retractions four status changes, instead of 603 lines.

But a table cannot hold *why* something was learned, only *that* it holds. Hence:

- **Table** for state — what holds now, on what evidence, with what ceiling
- **Prose** for lesson — how we got here, what fooled us; a paragraph or three,
  in your own words, never copy-paste from code

Same cut as `board/` versus `plans/`, one level down: format instead of directory.

### III.e The retrieval footer

Every lesson entry ends with the concrete paths, files, and PR numbers that lead
back to the case. A falsifier says how to disprove the rule; this says where to
find the evidence again. Anchor it to something immutable — a PR number beats a
path that may since have moved.

---

## Part IV — Mechanical enforcement (from lance-graph)

**`hooks/session-start.sh`** — emits the mandatory-read list as
`additionalContext` on the first turn. The reading order is not a request in a
file the model may skip; it is injected. Carries the line that matters most: do
not propose a new type, module, or convention without grepping `LATEST_STATE`
first.

**`hooks/anti-pattern-matching.sh`** — a PreToolUse guard on
`Grep`/`rg`/`sed`/`tail`/`head`. It does not block; it injects the rule at the
moment a partial-read tool is reached for: those tools locate, they do not
comprehend, and acting on a match before a full read is forbidden.

Its header records the incident that caused it — code deleted having been only
pattern-matched, never read. **A guard whose comment cites its own founding
incident is the highest form of this documentation.**

**`tools/preflight_drift.rs`** — compares board claims against `Cargo.toml`
workspace state, exits 1 on divergence, wired into the SessionStart hook, never
blocking. This is the artifact ada-rs most needs and does not have: six
`WorldModelDto` fields drifted unnoticed and ~32 tests accumulated unrun because
nothing compared claim to reality.

**`.claude/BOOT.md`** — a one-page entry above `CLAUDE.md`, naming the mandatory
reads, for the case where someone lands in `CLAUDE.md` directly.

**`.claude/harvest/`** — the escape hatch for work that cannot reach its home
repo: a verified patch plus a README explaining why it is banked. The discipline
that makes it real: *a banked patch that does not apply is not insurance* —
`git am` and the test run are checked before it lands.

**The read-economy policy** — one zipball per repo per session to a local path,
then local grep. Zero context cost until output. This is a budget rule and
belongs in the standard.

---

## Part V — The executor role contract

Two files, split by ownership: `knowledge/agent-cargo-hygiene.md` (operator
directive, owns the cargo prohibition, target residue, session ceiling and build
profile) and `knowledge/executor-role-contract.md` (owns role-vs-tier,
non-cargo permissions, and reporting-vs-concluding). The principle:

**A capability limit belongs to the role, not to whoever fills it.** Compilation,
disk reclamation, branch and manifest edits, and board appends are forbidden to a
delegated worker because a worker cannot be trusted with wall clock and disk it
does not own — true of any tier occupying the role.

Model tiers are named in the session brief and **nowhere in a committed
artifact**. The payoff is concrete: a tier that becomes unavailable can be swapped
out by changing one line in a brief, because the guardrails that made the
assignment safe were never attached to the tier's name.

Reporting, not concluding, is part of the same contract: a worker reports what it
searched and what it found. The upgrade from "0 hits" to "does not exist" is the
one that cost four retractions in a day.

### Resource discipline (main thread only, measured)

| | disk | wall clock |
|---|---|---|
| default dev (`debug = 2`) | 14–18 GB | 18–40 min |
| `debug = 0` | 3–4 GB | 6–9 min |

Roughly 4× the disk and 3× the time, for debuginfo a compile-check pass never
reads. The environment reports ~250 GB free; **the real ceiling is ~38 GB per
session across all branches and worktrees.** Plan against 38, reclaim `target/`
*and* `target/debug/`, prune stale branches on a schedule, and run **one build per
batch** — not per worker. Two workers each triggering a default build breach the
ceiling on the second one.

---

## Part VI — Porting: inventory before transcription

**Before a port, a machine inventory of the source, plus a named gap list.**

Use an AST tool, not a reader: enumerate what exists, how often, with what
dependencies. The output is not an opinion about the code, it is a work list. A
human reading 30k lines produces an assessment; the tool produces a set.

The ratio is the point. One-to-one is transcription — the same monolith in a
faster language. Collapsing 700 routes into 70 modular ones means someone found
the actual shape, and that is only possible *after* the enumeration, because
without it the repetition is invisible.

Two consequences:

- **An inventory is the direct antidote to false-absence claims.** With a list you
  look it up; without one you guess. ada-consciousness — 578k lines, superseded
  three times — was never enumerated, which is why "what became of `qualia`,
  `presence`, `persona`" is still open.
- **A gap list is the honest half.** What the inventory did *not* reach. An
  inventory without one pretends to be complete.

---

## Part VII — The standard

A workspace is at standard when all thirteen hold:

1. **`CLAUDE.md` ≤ ~10 KB**, is the policy guard, links out, absorbs no state.
2. **Five directories** — `knowledge/`, `agents/`, `plans/`, `waves/`, `board/`.
3. **`board/README.md` carries the full board rule set** — what each file
   answers, the one-writer rule and its base case, the storno rule, and what is
   deliberately absent.
4. **Every knowledge file ends in a machine-runnable falsifier.**
5. **Operator-set truth is quoted verbatim and marked `locked`**, visibly
   distinct from derived truth.
6. **An executor role contract exists**, capability-scoped, tier-agnostic.
7. **A SessionStart hook injects the mandatory reads.**
8. **A drift tool compares board claims to build reality**, wired in,
   non-blocking.
9. **Claims carry an evidence class and a does-not-prove note.**
10. **State is tabular; lessons are prose** — and lessons carry a retrieval
    footer.
11. **Append-only with storno**; retractions use `⊘`, dated, citing what they
    correct, and stay visible.
12. **Dated filename means ephemeral, undated means durable** — and the archive
    sweep extracts open items before it moves anything.
13. **A harvest path exists** — both senses: the escape hatch for work that
    cannot reach its home repo (verified before banking), and the inventory
    that precedes a port (with its gap list).

### Deliberately NOT in the standard

- **No model identifier in any committed artifact.** Tiers go in session briefs.
- **No language subdirectories** under `knowledge/`. Only needed where two
  runtimes coexist in one repo.
- **No `agents/` requirement below a size threshold.** ada-rs does not need 19
  specialist cards. Add it when `READ BY:` has distinct roles to point at.
- **No plan index below ~5 plans.**
- **No 87-agent roster.** Past a certain count nobody reads which agent does
  what, and the roster becomes the same problem as 965 files under `.claude/`.
- **No model-mediated value gate as a primary mechanism.** A gate that asks a
  model whether work was worthwhile has no red run to show. Where the value
  question is real, the grounded form is an environment that answers, not a model
  that judges.

---

## Part VIII — Migration order

**Cheapest first, and each step pays for the next.**

1. `board/` + `board/README.md` — stops plans from absorbing state.
2. `knowledge/` with the rules the repo has **already paid for in incidents**.
   Never invent a law. No incident, no law.
3. The executor role contract + `[profile.dev] debug = 0` — **before** any
   multi-worker pass, not after the first one blows the disk ceiling.
4. SessionStart hook — the reads become real.
5. `waves/` — once a plan has more than one executable slice.
6. Drift tool — once `board/` has claims worth checking.
7. `agents/` — last, and only if `READ BY:` has distinct roles to name.

The ordering principle: **a law without a falsifier is a comment, and a falsifier
without a hook is a hope.**

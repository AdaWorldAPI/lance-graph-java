# Issues Log — Open + Resolved (double-entry, append-only)

## ISS-LGJ-HOP-SWEEPS-FULL-POPULATION (2026-08-27) — OPEN

**Found.** By running bench Component G — the F-PARITY harness — for the
first time. `lgj_hop` is the slowest of three arms at every configuration in
the §3.8-mandated sweep (2 row counts × 2 frontier densities), by 2.6× to
165× against the best scalar oracle.

**Root cause, localized.** `exports.rs:1589-1599` builds the classid mask
across the WHOLE population, once per participating facet — 32 full-width
sweeps per hop — and only then intersects with `src`:

```rust
for facet in 0..ROW_FACETS {
    if (effective >> facet) & 1 == 0 { continue }
    kernels::simd_rowstore_classid_mask(bytes, …, n, …)   // n = ALL rows
    for (w, (&sw, &cw)) in src_snapshot.iter().zip(classid_scratch.iter()) { … }
}
```

The decode/scatter half IS frontier-bounded; the sweep preceding it is not.

**The measurement's shape is the evidence, not just its ranking.** Native cost
is flat in frontier density (479 → 521 µs at 4096 rows; 24 798 → 23 634 µs at
65 536 — a 25× larger frontier costs nothing) and ~linear in population
(16× the rows → ~52× the time). A hop whose cost tracks the population it
ignores rather than the frontier it starts from is doing full-population work.

**Remedy shape (NOT implemented here).** `src_snapshot` is known before the
loop, so the sweep can be bounded to the words where `src` has bits, or
skipped for a facet whose intersection is empty. That is a kernel change and
W8's F-PARITY scope is "seeds the HARNESS only" (§12) on a component §3.8
declares non-gating — landing a kernel rewrite in a bench commit would widen
the PR past what the measurement authorises.

**Not a verdict on mask-native execution.** Allocation independence is pinned
separately and is unaffected (`GraphHopTest` G3, flat 10-vs-500 rows); the
no-row-id guarantees are structural. This is the throughput-placement axis
§3.8 says a measurement decides — and it now has one.

**Caveats carried, not buried.** The per-call `Engine.createMask` + close has
no scalar analogue and was NOT isolated (implausible as the story at 470 µs on
4096 rows, and it would not scale with `rows`; named anyway). Native absolutes
are noisy — ±12 844 on 24 798 µs — on a shared 4-vCPU container; the ordering
is robust, the absolutes are not. One machine, one run.

Data: `bench/results/jmh-results-G.csv`; narrative: `bench/RESULTS.md` § G.


## ISS-LGJ-STACK-TAIL-STRANDED-MINOR-8 (2026-08-25) — RESOLVED same day

**Found.** PR #32 (ABI minor 8) was opened against
`claude/layout-probe` — PR #30's branch — because #30 (minor 7) was still
open and a same-numbered minor in two PRs would have been a real
collision. Correct at the time. But #30 merged to `main` FIRST, and #32
then merged into a branch that had already been absorbed. Net effect:
`main` sat at `LGJ_ABI_MINOR = 7` while the minor-8 commit, the
`CarvingTable`/`CarvingTableTest` pair, the load-gate prefix fix,
`abi.md` §17 and three board entries lived only on `claude/layout-probe`,
two commits ahead of main.

**Nothing was lost and nothing was broken** — `main` was a strict
ancestor with no divergence — but the work was reviewed, merged, and
still absent from the branch anyone would build.

**Why it is worth an entry rather than a quiet fix.** The failure is
generic to STACKED PRs and has no signal of its own: both PRs report
"merged", both are green, and GitHub says nothing. It surfaced only
because a wake event prompted a `git log origin/main` check rather than
trusting the merge notification. A session that trusted the notification
would have moved on believing minor 8 shipped.

**Rule adopted:** when a PR's base is another PR's branch, the merge of
the CHILD is not the end of the arc — verify the content reached `main`
(`git log origin/main..origin/<base>` must be empty), and if the base
merged first, land the tail explicitly.

**Resolved** by the same PR that carries this entry: `claude/board-hygiene`
→ `main`, carrying the stranded minor-8 commits plus this board work.

---

## ISS-LGJ-FACETSCHEMA-PAIR48 (2026-08-25) — OPEN, upstream-owned

`lance_graph_contract::facet_schema::FacetSchema`'s third reading is
`Pair48` (`2 × 48-bit`, two 6-byte codes — `facet_schema.rs:34`), NOT the
operator-ruled L6 quads (`3 × (u8:u8:u8:u8)`) that
`CascadeShape::G3D4` carries and that `le-contract.md` §3 names. The
contract itself flags the tension at `class_view.rs:1168`.

Both are legal readings of the same 12 bytes and both have real consumers
(`Pair48` serves `helix` `Signed360` and `cam_pq` `[u8; 6]`, which are
genuinely 48-bit), so this is **not** obviously a defect — it may be two
different axes that happen to partition the same register. What is
unresolved is whether `FacetSchema` and `CascadeShape` are meant to be the
same question asked twice, or two questions that must both be answerable
per class.

**Deliberately NOT resolved here.** This repo consumes the contract; it
does not arbitrate it (root `CLAUDE.md`: the contract is THE semantic
law). Flagged during the minor-6 work, carried through minors 7 and 8
untouched, and named in every PR body of the arc rather than left silent.

Closes when upstream rules the relationship — or when a lance-graph-java
consumer actually needs `Pair48` across the membrane, at which point the
question stops being academic.

---

## ISS-LGJ-CLASSID-WIDTH-PIN (2026-08-18) — OPEN, upstream-owned

The lgj wire carries u32 classids (canon 8-hex; `lgj_op_eq_classid`,
`lgj_hop`); the contract's `class_view::ClassId` is u16 while
`rbac::ClassId` is u32 — two widths coexist UPSTREAM in
lance-graph-contract itself. PR-W8a pins the boundary locally:
`class_view_provider::class_id_for` does the explicit u32→u16
bounds-checked conversion (None past `u16::MAX`), with a falsifiable
test pinning the behavior. The capacity tension is an upstream mint
question (named in MedCare-rs commitment #10 and spec NG8) — surfaced
here, deliberately NOT resolved here. Closes when upstream unifies the
width or rules the split permanent.

**REGRADED 2026-08-18 (operator correction, verified at file:line): the
SPLIT is ratified design, not drift.** `rbac::ClassId = u32` targets the
FULL composed classid — rbac.rs:98-103's own doc: "the NodeGuid classid
(its canon half is the codebook id; compose via render_classid)" — i.e.
`domain::appid`, both halves, so an authorization can target one app's
class instead of blocking a whole domain (and a domain-wide policy stays
expressible by matching the canon half). `class_view::ClassId = u16` is
a DIFFERENT KIND, not a narrower address: "Per-row class discriminator —
the Cognitive-RISC class_id/shape_id... OD-CLASSID-WIDTH ratified...
reuses the width of MailboxSoaView::class_id" (class_view.rs:48-54) — a
compact per-row shape-family discriminator, width-matched to the SoA
accessor. What REMAINS open here is only the u16 shape-space capacity
ceiling for relation minting (MedCare #10's "8 of 280 ids over the
ceiling") — not the existence of two widths. Follow-up for the
provider-slot wave: `class_view_provider::class_id_for`'s doc should say
a real provider keys ClassView by the shape discriminator (or the render
half via render_classid decomposition), never by bounds-truncating the
full u32 — the fixture ignores the value, so behavior is unaffected
today.

## ISS-LGJ-FANOUT-UNREVIEWED (2026-08-17) — PARTIALLY RESOLVED

**Resolution for the core (Rust ABI + Java facade), same day:** all three
closing conditions this entry names ran for real. (1) D-LGJ-AUDIT ran, one
violation found and fixed (see `EPIPHANIES.md`
`E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1`). (2) The registry's safety
claims were disable-verified, not just read — see the same epiphany entry.
(3) Central `cargo test`/`clippy`/`fmt`/`build --release` and Java
`javac`/`AllTests` all ran orchestrator-side and are green. Folded into
`LATEST_STATE.md`'s 2026-08-17 entry and `STATUS_BOARD.md`'s D-LGJ-B/C/D/E/H
rows.

**Still open for the Lab phase (D-LGJ-F/G):** `valhalla-lab/` and `bench/`
had not produced source at the time PR #1 was cut — real JMH jars were
fetched but no `.java` written yet. This half of the original concern
remains OPEN and will close when the Lab agent's output gets the same
audit + central-verification treatment as the core got here, in its own
follow-up PR.

**Original text, kept for context:**

The 4-agent vertical-slice fan-out (`wf_23ad2110-b1e`: ndarray primitives,
Rust ABI crate, Java FFM+facade, Valhalla lab+bench) was still running as
of this board's initial population. Nothing it produces should be cited as
"shipped" or "done" until: (1) the mechanical audit for the `ndarray::hpc`
ban and the no-C rule runs clean (`D-LGJ-AUDIT` on `STATUS_BOARD.md`), (2)
`handle-lifecycle-auditor` has adversarially exercised the registry's
safety claims per its own card (not just read the design doc), (3) a
central `cargo build`/`test`/`clippy` run by the orchestrator — not by any
spawned agent, per `TECH_DEBT.md`'s cargo-hygiene entry — actually passes.
Closes when all three have run and their results are folded into
`LATEST_STATE.md`.

## ISS-LGJ-TARGET-DIR-SIZE-WATCH (2026-08-17) — OPEN

`/home/user/ndarray/target` measured 2.7 GB and
`/home/user/lance-graph-java/target` measured 602 MB partway through the
first fan-out, from agents that were (at the time) permitted to run cargo
directly. Per `.claude/knowledge/agent-cargo-hygiene.md`, no further agent
gets that permission — but the two `target/` dirs from THIS wave already
exist and should be checked against disk headroom (`df -h /` was 43% used
at last check, 22G free) before any further large parallel work is
dispatched. Not urgent at last measurement; filed so it's watched rather
than rediscovered as a surprise "no space left on device" failure.

## ISS-LGJ-DEV-BRANCH-STILL-UNCOMMITTED (2026-08-17) — OPEN

`claude/lance-graph-java-panama-valhalla-sus9w8` (the designated dev
branch per the mission's cross-repo branch instructions) has zero commits
as of this board's initial population — everything from `docs/abi.md`
through the `.claude/` ensemble through the fan-out's in-progress output
exists only in the working tree. `main` was separately bootstrapped (see
`LATEST_STATE.md`) with a minimal README+`.gitignore` commit specifically
so it wouldn't be blocked on the dev branch's review status. The dev
branch's first commit should happen once the fan-out is reviewed
(`ISS-LGJ-FANOUT-UNREVIEWED`) — committing unreviewed, potentially rule-
violating code first and fixing it in a second commit is avoidable by
sequencing the audit first.

**RESOLVED same day.** Audit ran first, as planned; the one real
violation was fixed BEFORE this commit rather than after. First commit on
the dev branch lands in the same action as this board update, containing
already-audited, already-green code.

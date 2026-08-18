# Issues Log — Open + Resolved (double-entry, append-only)

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

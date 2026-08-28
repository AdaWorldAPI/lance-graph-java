# epoch-recheck-phase0-v1 — the W1.1 council SPEC (Phase 0, 5+3 protocol)

**Status: SPEC READY (2026-08-28) — Phase 1 (the 5 savants) not yet cast.**
This is the Phase-0 specification for resolving `ISS-LGJ-EPOCH-UNCHECKED`
(`.claude/board/ISSUES.md`), referenced by
`mask-membrane-valhalla-integration-v1.md` W1.1. It was drafted in-session
on 2026-08-28 under the `/5plus3` protocol and is committed here so the
reference is durable and the question sets are auditable — a spec that
exists only in a session transcript is not a spec
(the falsifiability rule's "a doc-comment claim is not a behaviour",
applied to plans).

## Scope finding (measured before this spec was written)

The epoch-recheck machinery already exists on BOTH sides, more than the
issue implied: `registry.rs` stamps a *global* monotonic `epoch: u64`
(`next_epoch()`) on every resource at creation — separate from the
per-slot `generation` the handle registry checks — so two different
resources, even in the same slot, are always distinguishable
(`epochs_are_never_reused`, `registry.rs`). `Engine.java` already has
`Engine.epoch(long handle)`, which calls `lgj_resource_info` fresh and
returns the live epoch. The gap is narrower than "wire epoch checking
from scratch": **no call site in `RowStore.java`/`Mask.java` ever calls
`Engine.epoch(handle)` again and compares it against the `epoch` cached
in `LaneWindow` before trusting a cached `lanes[]`/`words` segment.**

## 1. FROZEN DECISIONS

- D1. `ISS-LGJ-EPOCH-UNCHECKED` — the problem statement; not re-litigated.
- D2. `CLAUDE.md`'s corrected "Pointer value is not provenance" bullet
  (PR #46) — scopes the generation-registry guarantee to handle-mediated
  ops and explicitly excludes the cached-descriptor path; this spec's job
  is to close that exclusion or formally ratify it.
- D3. The zero-copy law: temporary scratch allowed, a second canonical
  copy is not — a fix must not introduce a copy to solve staleness.
- D4. `LifetimeTest`/`RowStoreLifetimeTest`'s existing falsifiers are
  frozen baselines — no regression.

## 2. INPUT INVENTORY

- `native/lgj-abi/src/registry.rs`: `epoch: u64` field (~:139-141),
  `next_epoch()` (:233), stamped at each creation site
  (:352/:365/:417/:456), tests `lane_epoch_matches_resource_epoch` +
  `epochs_are_never_reused` (both green).
- `native/lgj-abi/src/exports.rs`: `epoch: entry.epoch` copied into
  `LgjLaneDesc`/`LgjMaskDesc` on describe (:304, :393).
- `java/.../internal/ffm/Engine.java`: `rowCount`/`epoch(long handle)`
  (:77-90, the live re-fetch primitive), `lane(...)` construction caching
  `epoch` into `LaneWindow` once (:362-390), `LaneWindow` record (:405,
  `epoch` field with no consumer).
- `java/.../internal/ffm/Layouts.java`: `LANE_EPOCH` (:209),
  `INFO_EPOCH` (:228) — wired for reads, neither consumed.
- `java/.../RowStore.java`: `lane(int laneId)` lazy resolve + cache; the
  per-row accessors reading through it; `checkedRow(long)`.
- `java/.../Mask.java`: the cached `words` lane, same shape.
- `java/.../RowStoreLifetimeTest.java`, `LifetimeTest.java` — falsifiers
  to extend, not replace.

## 3. THE PROPOSED RESOLUTION

Two candidate resolutions; the spec commits to attempting (a) first, (b)
only if (a) is measured to be wrong:

**(a) Wire the check.** On each access through a cached
`LaneWindow`/`words` segment, compare the cached `epoch` against a
freshly-fetched live epoch (`Engine.epoch(handle)` exists; a lighter
epoch-only export is the fallback IF measurement shows the full 32-byte
`lgj_resource_info` read is too costly per access). Mismatch → the same
`ClosedResourceException`-shaped failure the `closed` boolean already
produces — a strictly-additive second guard, not a new exception type.
**If the epoch-only export is minted, it is a full ABI citizen** (a
CodeRabbit review addition, PR #47): its own ABI minor bump, manifest +
`abi.md` entry, `requireMinor(N)` gating at the Java call site, and an
old-library rejection leg in `OldAbiCompatTest` — otherwise the fallback
recreates the missing-symbol failure class W1 exists to remove.

**(b) Prove permanent unreachability, formally, and downgrade the
doctrine wording** — only if (a)'s cost is measured unacceptable AND a
falsifier-backed proof (a test that tries to construct the bad sequence
and fails, not prose) shows the facade's lifecycle discipline makes
slot-reuse-while-cached structurally impossible. **Outcome (b) is a
DOWNGRADE, never "resolved"** (CodeRabbit, PR #47): the doctrine's
scope-note stays live, `ISS-LGJ-EPOCH-UNCHECKED` closes as
DOWNGRADED-DOCUMENTED (a distinct status), and the unconditional-wording
restoration in `CLAUDE.md` never happens under (b).

## 4. NON-GOALS

- Not touching the native `epoch`/`generation` machinery (already
  correct and tested — the Scope finding above).
- Not redesigning `LaneWindow`/`RowStore` caching wholesale — the fix is
  a recheck at the access boundary.
- Not resolving the doctrine-text PRs — separate, already handled.

## 5. PRE-REGISTERED GATES

- A disable-run: with the recheck removed, a test constructing
  same-slot-resource-reuse-while-cached must go RED (the check is
  load-bearing, not decorative) — this test is itself a deliverable.
- With the check active: same test GREEN; every existing
  `LifetimeTest`/`RowStoreLifetimeTest` falsifier stays green.
- **The overhead measurement targets the cached-descriptor accessors
  THEMSELVES** (`classidAt`/`payloadLow64At`/`payloadHi32At` through
  `lane()`, `Mask`'s cached-`words` reads) — a Codex review correction
  (PR #47): the banked hop/columnar benches run entirely through native
  operations, never touch this path, and are structurally blind to
  per-access overhead. Protocol: the 65,536-row fixture, ns/accessor-call
  over ≥1M calls, median of 5 runs, before/after, banked.
- Threshold (the reproducible units CodeRabbit asked for): if the
  re-check cannot be brought under 2× the accessor's measured baseline
  cost — including via the epoch-only export — resolution (b)'s track
  opens; below 2×, (a) ships without further debate.
- `cargo test -p lgj-abi` + full Java suite green; clippy `-D warnings`.
- Board: `ISS-LGJ-EPOCH-UNCHECKED` flips OPEN → RESOLVED under (a), or
  OPEN → DOWNGRADED-DOCUMENTED under (b), same commit as the code.

## 6. PER-SAVANT QUESTION SETS (Phase 1, when cast)

| # | lens | card | questions |
|---|---|---|---|
| 1 | handle safety | `handle-lifecycle-auditor` | Is `Engine.epoch(handle)` itself generation-checked (does its `lgj_resource_info` call go through `resolve`)? Does the recheck introduce a new TOCTOU between the epoch fetch and the subsequent read? |
| 2 | ABI membrane | `abi-membrane-warden` | Does an epoch-only export need a minor bump (yes per the resolution text — verify nothing cheaper suffices)? Does it fit the bulk/lifecycle taxonomy (`abi.md` §6)? |
| 3 | zero-copy law | `zero-copy-warden` | Is the recheck O(1) per access with zero row-proportional allocation? Does caching the comparison RESULT risk becoming a second staleness authority? |
| 4 | Java surface | `java-surface-warden` | Does the fix stay entirely inside `internal.ffm`/`RowStore`/`Mask` — no new public API, no freshness method leaking implementation physics? |
| 5 | measurement | `measurement-skeptic` | Is the disable-run's red genuinely caused by the removed check? Is the per-accessor measurement isolated and comparable (the protocol in §5), not an isolated-stage percentage? |

Output contract: ≤10 findings each, verdict vocab
`CONFIRMS / VIOLATES / GAP / PRIOR-ART-AT / RISK`, file:line evidence,
≤2 sentences. Phase 2 consolidates before any reviewer exists; Phase 3
reviewers see draft v2 only.

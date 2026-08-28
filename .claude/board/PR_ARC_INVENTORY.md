# PR Arc Inventory — per-PR Added / Locked / Deferred / Docs / Confidence
# (reverse chronological, APPEND-ONLY; only the Confidence line is
# updatable in place — corrections append as new dated lines; reversals
# get their own PR entry)

> **Hygiene lapse, owned (2026-08-17):** PRs #1-#3 merged without their
> entries landing in the same commit — the exact retroactive-hygiene
> anti-pattern the imported board rules name. Backfilled below in one
> pass rather than left stale; PR #4 onward gets its entry at merge time.

## PR #60 — W1+W2 built, and the fence that had to be fixed twice (merged 2026-08-28, `97f25c5` — 4 commits, head `c6d8a4c`)

- **Opened because a board entry hid two obligations behind two words.** #57's
  entry said arm (ii) "finally landed"; W1 and W2 had **no implementation at
  all**. Codex found it on #59, *after* #59 merged.
- **W1 built:** the thread-safety scope on `RowStore` and `Mask` — `happens-before`
  appeared **nowhere in `java/src`** before this — plus the once-per-scan note on
  `materializeRows()`.
- **W2 built, and the stale doctrine is the part that mattered.** Root
  `CLAUDE.md` had said the cached path takes "NO further registry call" and that
  `epoch` "is unconsulted anywhere in `src/main`" — **both false for `Mask`
  since #53**, in the file every session reads first. A future session would
  have read a description of a gap already half-closed. Now split per half,
  struck in place.
- **Fence 1b was partly vacuous, and my own disable run contained the proof.**
  It scanned the whole FILE; `Mask.materializeRows()` independently names the
  literal, so deleting the class block — the regression the leg exists to catch
  — left it green. Turning it red had taken stripping **two** occurrences; I
  noticed the second, removed it, and never asked why two were needed. **The
  rule this adds:** *a disable that removes more than the regression does not
  demonstrate the regression is caught; if it must be widened to go red, the
  widening IS the finding.* Instance **eleven**.
- **Three reviewer roles, worth distinguishing.** Codex found the scoping hole;
  **CodeRabbit found it independently** (third convergence of the day) *and*
  added what Codex's did not — require the obligation to name `close()`, since
  W1's duty is happens-before *between `close()` and every access*, not a
  mention of the relation.
- **The repair nearly failed the other way**, which is its own keeper: scoping
  to the class javadoc immediately failed four arms on prose that plainly said
  the right thing, because **javadoc wraps** and a phrase split across lines
  matches no literal. The region is normalized or the fence enforces where an
  author pressed return.
- **A residual review claim, tested rather than accepted — and my test was too
  narrow.** ⊘ This bullet first read: *"Checked by deleting that bullet: fence
  1c fires on two arms. The concern does not hold."* **Struck.** Deleting the
  bullet removed the two required phrases along with the mechanism, so it
  proved only that DELETION is caught. The real case is a **rewrite** that
  keeps both phrases and guts what they describe — measured, that passes 38/38.
  CodeRabbit filed it **44 seconds before this PR merged**, so it landed
  against an already-merged head; fixed in #62. **The claim held, and I
  reported it disproven on a test that could not have seen it.** Instance
  twelve.
- **Locked:** W1 and W2 discharged, **scoped to those two** — the plan's status
  note explicitly does not claim W3-W6. Fence 1c discharges W2's own
  *"this precedent must be built, not merely cited."*
- **Deferred, and it is the arc's real open question:** **enforcement.**
  Everything here checks *wording*. 1c can prove `CLAUDE.md` says the right
  thing about `Engine.close(long)`; nothing can prove a caller honours it.
- **Scope:** javadoc + doctrine + two fence legs + board/plan. No ABI symbol, no
  public signature, no behaviour change. Gate 338 → **359**.
- **Confidence:** high on the fences (both disable-verified, 1b twice — once
  wrongly, once correctly). High on the doctrine now matching the code, since a
  fence checks it. **Low on the doctrine staying matched in places no fence
  reads** — `CLAUDE.md` went stale for weeks and only a reviewer noticed.

## PR #58 — Q4: the halves differ by lifetime ownership, not frequency (merged 2026-08-28, `a7e4ed5` — 3 commits, head `7c9ce3e`)

- **A PROPOSAL, deliberately not acted on.** `ISS-LGJ-EPOCH-UNCHECKED` stays
  OPEN. Adopting a structural rejection is an amendment the plan does not
  currently define, and closing it on an argument written the same day would
  have repeated instance eight one PR after a reviewer caught it.
- **The finding:** §5 frames the `Mask`/`RowStore` asymmetry as **frequency**
  (per-scan vs per-call) and therefore makes the `RowStore` half a *cost*
  question. Verified in source, it is **lifetime ownership**: a `Mask` has a
  parent it does not own, so closing that parent is in-contract and still
  invalidates its cached words; a `RowStore` is only ever a parent, its bytes
  are allocate-once `Arc<[u8]>` with no realloc surface, and its one closer
  sets the flag `requireOpen` reads.
- **Four review findings, all correct.** Two reviewers converged independently
  on the conclusion being unconditional. CodeRabbit's sharpest line —
  *"the private constructor and fresh handles do not prove sole-closer
  behavior"* — was right: that was **a fact about construction used as a fact
  about closure**. `Engine.close(long)` needs no facade at all.
- **So the claim is weaker and honest:** a stale lane with `closed == false` is
  **out of contract**, never *impossible*. The contract landed in #57 as
  documentation, not enforcement, and Q4 inherits exactly that.
- **A scope error caught late, and it had teeth.** Q4 said the close-after-cache
  scenario was reachable only by violating the contract. False — it is an
  ordinary single-thread ordering that `RowStoreLifetimeTest` exercises, and
  read literally the sentence implied `requireOpen()` guards nothing in
  contract, **an invitation to delete it**. Instance **ten**: a *neighbouring*
  sentence left asserting the stronger claim after its neighbour was weakened.
- **Locked:** the two scenarios are tabulated apart, and the per-access
  `requireOpen()` requirement is stated as **required, not optional**.
- **Deferred:** the `RowStore` half itself — settled in neither direction.
- **Confidence:** high on the four facts (each read in source). Medium on the
  framing surviving contact with enforcement: if a concurrent-access protocol
  ever lands, Q4's "out of contract" becomes a much stronger claim and should
  be re-derived rather than promoted.

## PR #57 — the sole-closer contract, and the leak two reviewers disagreed their way onto (merged 2026-08-28, `d6d21132` — 3 commits, head `e77ed59`)

- **Opened as documentation; found a bug, so the title changed mid-flight.**
  `Mask.close()` guarded its native close with `if (parent.isOpen())`, commented
  *"the selection was freed with it… Nothing leaks either way."* Both halves
  false: `registry::close` takes only the handle's OWN slot and never cascades,
  and `create_mask` gives a mask its own `Box<[u64]>`. **An orphaned selection
  leaked its words and its registry slot for the life of the process.**
- **How it surfaced is the transferable part.** Codex (P2) said an orphan's
  `lgj_close` returns `OK`; CodeRabbit said `INVALID_HANDLE`, citing that very
  comment, and asked for it to be *documented*. They cannot both be right, so it
  went to source: `a_mask_whose_parent_closed_reports_parent_closed` ends
  `assert_eq!(lgj_close(m), LGJ_OK)`. Codex had the fact, CodeRabbit had the
  inconsistency, **neither was arguing about a leak — and the leak was what the
  disagreement was standing on.** Deferring to the more confident reviewer, or
  splitting the difference, would have shipped the bug as documented behaviour.
  **When two reviewers contradict each other, the disagreement is the signal.**
- **Falsified via the complement, because the defect is invisible.** No
  observable distinguishes a leaked slot from a live one, so
  `orphanCloseActuallyReleases` asserts a **second** close is REJECTED — which
  can only happen if the first one ran. Red-then-green verified; 337 → **338**.
- **Arm (ii)'s written contract, PARTLY landed.** `docs/abi.md` "Concurrency"
  gains *The sole-closer contract (normative for callers; NOT enforced)*,
  carried at all three `close()` sites, stated **with the three facts that make
  it unenforceable** rather than as a bare rule.
  - ⊘ **Corrected on #60 (Codex P2, raised on #59 after it merged).** This line
    read *"finally landed"*, which **hid two ratified obligations**: W1 (a
    thread-safety block on `RowStore` and `Mask` naming the caller's
    *happens-before* duty, plus a `DoctrineFenceTest` leg asserting the literal)
    and W2 (root `CLAUDE.md`'s wording matching the mechanism, plus a fence leg
    that actually READS the doctrine file — W2's own words: *"this precedent
    must be built, not merely cited"*). Neither existed. Worse, `CLAUDE.md` had
    gone **stale the day W1.1 shipped**: it still said the cached path takes
    "NO further registry call" and that `epoch` "is unconsulted anywhere in
    `src/main`" — both false for `Mask` since #53, in the file every session
    reads first. All four landed in #60 with two disable-runs red-then-green.
- **Three doc overclaims corrected, each verified in source first.** (1) *"every
  operation on an orphaned child returns `PARENT_CLOSED`"* — this PR's own first
  correction narrowed it to *"every **handle-mediated** operation"* and that was
  **still false**; now scoped to operations resolving the child WITH its parent,
  naming both exclusions. (2) the `Arena`-nested-lifetime claim — false for lane
  windows; the facade's arenas back output scratch. (3) *"the `epoch` field lets
  Java detect a stale segment"* — it does not; that is `ISS-LGJ-EPOCH-UNCHECKED`.
- **Overclaim 1 is instance NINE** of `ISS-LGJ-SECOND-VERDICT-BESIDE-THE-FIRST`
  and the purest the ledger has: eight and nine are not the same claim, they are
  the **same sentence**, narrowed once and still wrong — so no diff could show
  the repair and the residue apart. **Narrowing a quantifier reads like
  diligence and yields only a smaller overclaim;** what catches it is
  enumerating the call sites.
- **Locked:** `ISS-LGJ-ORPHAN-MASK-CLOSE-LEAKED` opened and RESOLVED here.
  `ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW` stays **OPEN** — arm (ii)'s
  deliverable is discharged, and writing a contract down is not enforcing it.
- **Deferred:** enforcement. Every close-side guarantee in this PR is a promise
  to callers, not a mechanism; the ABI defines no concurrent-access protocol.
- **Scope:** one behaviour change, one falsifier, doc + javadoc. No ABI symbol,
  no public signature. Gate 338/338, clippy/fmt clean.
- **Confidence:** high on the leak and its fix (registry read directly, falsifier
  disable-verified). High on the contract's *wording*; **low on its force** — it
  is unenforceable by construction and says so. Medium on the doc surface being
  free of further overclaims: three were found in one pass, two by reviewers,
  and nothing systematically checks a doc claim against the code it describes.

## PR #55 — Component H: one crossing measured, and a verdict withdrawn (merged 2026-08-28, `b55c1e8` — 5 commits, head `b101ba3`)

- **Opened claiming a verdict, and the claim was wrong.** Title and body read
  *"the RowStore per-access gate, measured; answer is 'not at all'"*. **Both
  halves withdrawn** after two P1s. The entry, the PR body, and the source
  javadoc are struck in place, never deleted — the withdrawn claims and their
  reasons are both readable.
- **Added, and this survives:** bench Component H (`H_CachedAccessorProbe`,
  5 forks × 8 iters, `rows=65536`, release `.so`) measuring a bare
  `lgj_lane_describe` crossing on a hot read loop at **+35.5 ns** over a cached
  read — `cached` 9.398 ±0.362, `probed` 44.932 ±0.931, delta CI
  **[34.54, 36.53]**, both arms clearing per-arm acceptance. Real, reproducible,
  and an order-of-magnitude input to a future `N`. Results banked at
  `bench/results/jmh-results-H.json`.
- **Why it decides nothing — two independent grounds, and that independence is
  the point.** (1) No `N` was pre-registered, so §5.2 voids it. (2) Both arms
  call `Engine.describeLane` directly, so neither is `RowStore.classidAt` —
  they bypass the cached `lanes[]` lookup, `requireOpen`, the `FacetId`
  null-check and bounds, where §5.4 says the accessor "is an inlining/compile
  barrier… That total is the right thing to gate on." **A pre-registered `N`
  alone would not have rescued the closure**, which is what makes ground 2
  worth stating separately rather than folding into ground 1.
- **The eighth instance of the arc's own pattern, and its worst shape.** The
  entry declared its run void in one paragraph and acted on it in the next —
  two verdicts in adjacent paragraphs of one document. Codex and CodeRabbit
  found it **independently**, which is the strongest external signal the
  `ISS-LGJ-SECOND-VERDICT-BESIDE-THE-FIRST` ledger has yet drawn.
- **A correction that was itself wrong, retracted.** #55 claimed to correct
  `ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT`'s "forces TWO BUILDS". It follows
  from ground 2 that two *production* variants cannot share one classpath
  without the hoistable branch §5.5 forbids. **The original finding was right;
  the correction was not.** Restored unamended.
- **Three board obligations discharged that #55 did not set out to touch**, each
  found by checking this PR's own unwind against the rules rather than by
  review: `ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW` opened (W4 requires it
  in the same commit that scopes `ISS-LGJ-EPOCH-UNCHECKED` to `RowStore` —
  overdue since #53, its three facts re-verified in-tree because the plan's line
  numbers had shifted); #54's arc entry backfilled (mixed PR, so the hygiene-only
  termination clause does not reach it); two `STATUS_BOARD` rows corrected, one
  claiming "implementation Queued" ten days after the `Mask` half shipped and one
  showing no trace that the §5 gate had been attempted at all — the second being
  the costlier, since the next session would have rebuilt Component H to learn
  what this PR already measured.
- **Locked:** `ISS-LGJ-EPOCH-UNCHECKED` OPEN, scoped to `RowStore`. A valid gate
  needs all three in order — amendment naming `N > 0` committed first,
  before/after variants of the **production** accessor in two builds of `java/`,
  a results commit citing that amendment's sha.
- **Deferred:** the `RowStore` half itself. Nothing about it is settled by this
  PR in either direction.
- **Scope:** `bench/` + board only. **No change to `java/src/main`, no ABI
  symbol, no public signature.** Gate: 337 checks, 0 failures.
- **Review coverage, stated rather than assumed:** CodeRabbit reviewed through
  `ec5dc18` and then hit its hourly allowance, returning "No actionable comments"
  and Merge Risk *Minimal*. The last three commits (`e0e1a84`, `130b302`,
  `b101ba3`) are **board-only and went unreviewed**. Bugbot did not run on any
  commit — a Cursor usage/spend limit, on every PR this session, not a drafting
  artifact.
- **Confidence:** high on the measurement (banked JSON, both arms cross-checked
  in `@Setup`) and on the scoping (two reviewers, converging). Medium on the
  board being *complete* — three missing obligations surfaced here only because
  the unwind forced a re-read of W4, and nothing systematically checks that a
  paired obligation was discharged.

## PR #54 — the fifth instance, and making `Engine.epoch`'s javadoc true (merged 2026-08-28, `cc53c8c` — 4 commits, head `fd1df59`)

> ⊘ **Entry backfilled on #55 (2026-08-28).** #54 merged without it. #54 is a
> *mixed* PR — it changed `Engine.java` and struck two plan rules, so the
> termination clause's hygiene-only exemption does not reach it and the entry
> was owed at merge time. Recorded late rather than left absent.

- **Added:** `Engine.epoch`'s javadoc rewritten to be **true**. It had claimed
  "Java re-checks this before trusting a cached lane segment"; #53's fix routes
  through `Engine.describeMask`, so `epoch` retains exactly **zero** callers.
  The javadoc now states that, says why `lgj_resource_info` is the wrong
  question for a cached lane (it resolves the child's own slot, which outlives
  its parent), and why the symbol is retained anyway — the `RowStore` half
  would need a lane's *owning* resource. That rewrite is what actually
  discharges the W3 obligation; #53's board line claiming the javadoc "is no
  longer false" would have hidden it.
- **Struck (plan §5), instances five through seven of the pattern:** the fifth
  was found by review; the sixth and seventh were each **introduced by the fix
  for the one before it**. "Void in that case" implied the struck rule still
  applied elsewhere; the label was then attached on the sign where it belongs
  on the interval. Each struck in place, never deleted.
- **Confidence:** high on the javadoc (it now describes observable reality —
  zero callers, verifiable by grep). Medium on §5 itself, and the reason is
  §5's own history: seven repairs to one rule, three of them self-inflicted.
  The eighth arrived on #55.

## PR #53 — W1.1: the §5 adversarial read, and the Mask half wired to the substrate (merged 2026-08-28, `3b4bc03` — 5 commits, head `8e2654d`)

- **Added:** the `Mask` half of W1.1 — `Mask.words()` re-authorises its
  cached word lane with the substrate on every use of that cache (one
  `lgj_mask_describe` per whole scan, never per word), with a two-sided
  disable-verified falsifier. Plus the §5 read against its own premises.
- **Building it corrected the plan.** v3 §6 named `lgj_resource_info` as
  the probe because it "already reads live". It does — but it resolves the
  mask's OWN slot, and **that slot outlives its parent**. Measured: closing
  the parent natively left the probe silent, `count()` correctly reported
  `PARENT_CLOSED`, and `materializeRows()` **read freed bytes without
  crashing**. Two keepers: *an absent segfault is not evidence of safety*
  (Codex's #49 P1 hazard, reproduced deliberately), and *handle liveness is
  not lane liveness*. `lgj_mask_describe` resolves WITH the parent, so the
  fix needed **no new ABI symbol** — v3's ruling survives with its reason
  corrected, not its conclusion.
- **§5, six defects** — two verified numerically: a standalone
  `delta_ns ≤ 0` auto-pass that **contradicted** the delta table beside it,
  and per-arm-only run acceptance that made PASS **unreachable for any
  `N ≤ 14 ns`** even for a free probe. Now one verdict function and an
  ex-ante `hw_delta < N/2` power precondition.
- **Then committed the defect it had just named** (row 24, found by
  CodeRabbit and Codex independently): declared the table the whole verdict
  function while leaving three `Ship if` / `both must pass` statements
  standing. Struck in place. **Naming a failure mode does not confer
  immunity to it** — the sharpest evidence the ledger has for its own
  thesis.
- **Two reviewer nitpicks, one of whose remedies would not have worked:**
  narrowing the `count()` assertion to "the concrete ABI exception type"
  cannot discriminate, because BOTH paths raise `ClosedResourceException`.
  The message discriminates, and now does. The other was right in substance
  (a cached scan is no longer free) and wrong in arithmetic (it is one
  describe per cached scan, not two); both foreclosed by naming the number.
- **Gates:** release `.so` rebuilt first; central runs at 335 then 337
  checks, ALL PASSED; disable-run red-then-green at every stage. No new ABI
  symbol, no public-signature change. Cursor Bugbot did not run — usage
  limit, fifth consecutive PR.
- **Confidence:** high on the `Mask` half (implemented, falsified, measured);
  the §5 rule is now internally consistent as far as three passes and two
  reviewers can establish, and its numeric `N` remains UNMEASURED.

## PR #51 — plan: CI overlap labels a verdict, it never produces one (merged 2026-08-28, `6530e2f` — 3 commits, head `7acb7fd`)

- **Why it exists:** two findings landed on #49 **~2 minutes AFTER it
  merged**, so no gate on that PR could have caught them and the merge
  could not have waited. One was a real defect in the ratified plan's own
  decision rule. The generalizable lesson, recorded in `ISSUES.md`: **a
  subscription is not finished at merge — the last review can land after
  it.** Both #49 and #50 merged with a review still in flight.
- **The defect chain (three rounds on ONE rule, each subsuming the last):**
  §5 first auto-passed **any** CI-overlapping run, reasoning an
  unresolvable cost cannot exceed a positive budget — false, and the
  counterexample is ordinary: 100 ns vs 118 ns at 9% half-widths overlap
  while `delta_ns = 18`, so an `N = 10 ns` budget ships a probe at ~2×.
  The repair demoted overlap to a *label*; **that was still unsound**
  (Codex P2) — overlapping CIs for two means are not a CI for their
  difference, so the label asserted unsupported noise AND let a
  point-estimate failure read as definitively "too costly" on a run that
  could not tell. Final shape: uncertainty is computed on `delta_ns`
  itself and its interval compared to `0` and `N`, with a straddling
  interval classified **UNDERPOWERED** — neither pass nor fail, remedy a
  better-powered run. Arm-vs-arm comparison no longer appears in the rule.
- **Also locked:** `N > 0` required at both the non-positive clause and
  the amendment procedure (an `N ≤ 0` amendment voids the run) — the
  premise the `delta_ns ≤ 0` auto-pass silently rested on; and the
  two-delivery-paths correction to #49's headline (`Mask` unconditional,
  `RowStore` benchmark-gated), recorded as a **dated append-only
  correction line** after Codex caught that editing it into the Headline
  broke this file's own lines 2-4 rule. `ISSUES.md` got a storno, not an
  edit, for the same reason.
- **Gates:** doc-only. CodeRabbit clean on `f9e6938` and on `7acb7fd`
  (⚪ Minimal); Codex 2×P2, both fixed; all three threads answered then
  resolved. Cursor Bugbot did not run — usage limit, third consecutive PR.
- **Confidence:** high on the final rule's *form* (it now compares the
  right interval against the right quantities); the numeric `N` is still
  UNMEASURED and pre-registration-gated, unchanged.
- **Standing recommendation, NOT yet done:** four consecutive findings in
  §5, each a rule resting on an unstated premise, and the third subsumed
  the second's fix. The cheap move before the benchmark amendment is
  written is ONE adversarial read of §5 against its own premises rather
  than another round-trip — each of which now also costs money
  (CodeRabbit is on usage-based billing at $0.25/file; this PR drew $1.00
  across its runs).

## PR #47 — plan: mask-membrane-valhalla-integration-v1, the layered consolidation (merged 2026-08-28, `9f6e9a2` — 6 commits, head `586d081`)

- **Added:** `.claude/plans/mask-membrane-valhalla-integration-v1.md` —
  the PR #44→#46 arc consolidated under the layer model (masking
  underneath / Panama the membrane / Valhalla cheap addresses /
  everything else substrate-private): frozen decisions F1–F7 (incl. the
  measured F7 convergence-tail ground truth that keeps the W4.2 write
  seam blocked and D-id-less), the honesty-gap layer table (G-A..G-E),
  waves W0–W4.1, pre-registered falsification conditions.
  `.claude/plans/epoch-recheck-phase0-v1.md` — the committed W1.1
  Phase-0 council spec (frozen decisions, input inventory, resolutions
  (a)/(b), gates, five per-savant question sets).
  `.claude/knowledge/github-access-paths.md` — the measured
  three-paths/two-identities GitHub access map (session-proxy credential
  vs the shared user-200276742 identity; secondary-limit signature;
  thread resolution as the one no-fallback operation).
  Board: `INTEGRATION_PLANS.md` prepend + `STATUS_BOARD.md`
  D-LGJ-MMV-0..4 rows.
- **Locked (review-hardened, 9 findings across Codex + CodeRabbit, all
  verified-then-fixed):** the ABI-citizen requirement on any epoch-only
  export; DOWNGRADED-DOCUMENTED (never "resolved") for fallback (b); the
  requireMinor-before-lazy-holder invariant for W1.2; the W1/W2 flip
  condition in reproducible units (ns/accessor-call, 2× threshold),
  measured ON THE CACHED-DESCRIPTOR ACCESSORS (the banked benches are
  structurally blind to them); the check-then-read ATOMICITY constraint
  as a mandatory W1.1 council output (serialize/lease + interleaving
  falsifier, or a written scoped contract — wording never exceeds the
  chosen arm); the measurement runs UNDER the chosen arm; W0 scope
  honesty (lexical fences are tripwires, not proofs) and the standing
  gate-observability question for every future gate.
- **Confidence:** high on the frozen decisions (each cites its ruling);
  the plan itself is PROPOSED→ratified-by-merge; wave outcomes are
  gated, not promised.

## PR #49 — W1.1: the epoch-recheck 5+3 council, ratified (v3) (merged 2026-08-28, `0efb757` — 7 commits, head `490b71d`)

- **Merge-time addendum (post-entry):** the external round landed **8
  findings — Codex 2 (one P1, one P2), CodeRabbit 6** — every one
  verified against source before it was fixed, and every one fixed
  (`d2393fa`..`490b71d`). The P1 is the one that changed a deliverable
  rather than prose: asserting only that the accessor *throws* stopped
  the test *looking* at freed bytes but did not stop it *reading* them,
  so W5 now mandates invalidate-without-free via a test-only ABI export
  (queued as its own row) **and** populate-the-lane-cache-first ordering
  — neither reviewer's literal fix works alone (CodeRabbit's ends in
  `Engine.close`, the very UAF Codex flagged; Codex's alone leaves the
  cache unpopulated, so `classidAt` re-resolves and throws on its own,
  passing with the probe disabled). The P2 forced the benchmark
  *statistics* to be pre-registered, not just its acceptance form.
  **Honesty note:** the merge (13:09Z) preceded CodeRabbit's re-review of
  `490b71d` completing — its last posted finding is 13:01:50Z, i.e. it
  had reviewed nothing newer than `9a0e058`; and Cursor Bugbot never ran
  at all on this PR (usage limit). The eight threads are answered by
  reply but left **unresolved** — thread resolution is GraphQL-only and
  the account-wide secondary limit blocked it (see
  `.claude/knowledge/github-access-paths.md`: the one operation with no
  fallback).
- **Added:** `.claude/plans/epoch-recheck-v3.md` (RATIFIED — implement
  from this) and `epoch-recheck-v2-draft.md` (SUPERSEDED, retained: the
  Phase-3 findings are only auditable against the draft they attacked).
  Full 5+3 for `mask-membrane-valhalla-integration-v1` W1.1 — five
  savants, consolidation, three reviewers on the draft only, ratified v3
  with an audit ledger (§8).
- **Headline:** an epoch MISMATCH is unreachable through
  `lgj_resource_info` **short of a `u32` generation wrap on one slot**
  (close bumps the slot generation, insert hands the advanced generation
  out, the export opens with `resolve`), so what W1.1 ships is a **native
  generation-checked liveness probe replacing a Java boolean** — not
  "epoch checking". Derived independently three times from source, and
  pinned by a committed test as a W1.1 deliverable (source reading
  establishes today's path; only a test keeps a refactor honest).
- **Locked:** atomicity arm (ii) scoped contract on three verified
  reasons (arm (i)'s mandatory falsifier cannot report red — no threads
  in the test tree); six obligations W1-W6 with W1/W2 given mechanical
  fence legs; no new ABI symbol **on the production path** (the one the
  wave admits is test-only, and a full ABI citizen — v3 §8 row 13);
  the `Mask` half ships regardless, the
  `RowStore` half is per-access-or-not-at-all and gated on a benchmark
  that does not yet exist.
- **Reviewers changed the deliverable, not just its prose:** one BLOCKING
  defect (the draft's own falsifier asserted on use-after-free bytes —
  the evidence class it disqualified arm (i) for), one open question
  closed on falsifiability, a wrongly-routed falsifier, an invented
  closure status, two normative corrections rescued from under a "CLEAN"
  heading, five factual errors — including a storno on the merged plan's
  false "benches are structurally blind" claim, and a live `main` defect
  nobody had noticed (`Engine.epoch`'s javadoc claims a re-check with
  zero callers).
- **Gates:** doc-only — no Rust, Java, `abi.md`, or public-API change; the
  suite is untouched at 331/331 from PR #48. Every file:line in v3 was
  either given with evidence by a savant or verified by the consolidation;
  the load-bearing ones were re-verified a third time by a reviewer.
- **Confidence:** high on the headline (three independent source
  derivations) and on the arm decision; the implementation's cost
  question is explicitly UNRESOLVED and gated, not assumed.
- **Correction 2026-08-28 (post-merge, from #51):** the Headline above
  reads "what W1.1 ships is a native generation-checked liveness probe"
  without its condition, which would mark the `RowStore` half delivered
  before its gate closes. **Two delivery paths, never one:** the `Mask`
  half ships UNCONDITIONALLY (one downcall per whole scan); the
  `RowStore` half ships ONLY if the per-access benchmark passes §5's
  gate, and otherwise does not ship at all. Recorded here rather than
  edited into the Headline — this file's own rule is that only the
  Confidence line is updatable in place and corrections append as dated
  lines, so an in-place headline edit (as first attempted in #51) erases
  the distinction between what was recorded at merge and what was learned
  after it.

## PR #48 — W0: the three doctrine fences, each proven able to fire (merged 2026-08-28, `42fba54` — 7 commits, head `fc281e0`)

- **Merge-time addendum (rounds 4-5, post-entry):** the review cycle ran
  FIVE bot rounds total; beyond what the entry below records, the last
  two added: the loud unreadable-file rule (`readLines` rethrows —
  an unreadable `abi.rs` can no longer scan as empty), split-line
  materialization matching (filtered lines re-joined so `\s+` spans
  breaks), the resilient no-native suite loop, `for(`/`while(` branch
  markers, loud partial reflective discovery, and the Rust raw-string
  TRIPWIRE (which corrected this PR's own "fails loud" doc claim — a
  raw string mis-lexed SILENTLY; the opener now trips fence 2 outright
  rather than growing a Rust lexer for two files with zero raw strings).
  Every fix plant- or suite-verified; final suite 331/331.

- **Added:** `DoctrineFenceTest` (17 checks) — the plan
  `mask-membrane-valhalla-integration-v1.md` W0 / D-LGJ-MMV-0 deliverable,
  turning the PR #46-corrected doctrine's three prose claims into
  executable fences: **fence 1** pins the exact per-file occurrence count
  of every materialization-shaped pattern in `java/src/main` against the
  doctrine's exhaustive five-site list (an unfenced site, new file or
  old, fails until list AND pin move together); **fence 2** asserts zero
  worker-topology tokens on the consumer surface — all of `java/src/main`
  plus the ABI's consumer-facing files only (`abi.rs` + `exports.rs`;
  substrate-internal scheduling deliberately out of scope, per §E's own
  ownership logic); **fence 3** confines backend tokens to their three
  define/relay homes and rejects any code line that both carries a
  backend token and branches; **fence 2b** (added in review) re-checks
  §E REFLECTIVELY — no public facade method may be topology-named — the
  spelling-immune arm no lexical scan can equal (`ApiSurfaceTest`'s own
  footing applied to §E). All fences read ONE shared comment filter
  (`codeLines`), so prose in a javadoc can neither move a pinned count
  nor report a leak; fence 3's branch markers match the canonicalized
  line and cover ternaries/boolean operators, closing the in-place
  ternary rewrite that kept the carrier census unchanged. Registered in
  `AllTests` including the no-native path (source fences need no `.so`,
  same rationale as `ApiSurfaceTest`).
- **Locked:** each fence arm observed red-then-green on a planted
  violation before landing — unfenced `Arrays.copyOf`+`new long[` in
  `View.java` (fence 1, both patterns), `public View workers(int)`
  (fence 2), the SAME method with its name and paren split across two
  lines (invisible to any per-line lexical scan — ONLY fence 2b fired,
  proving the reflective arm adds coverage rather than redundancy),
  `if (simdBackend().equals("avx512"))` (fence 3, both halves), a stray
  `SIMD_AVX2` reference in `View.java` (fence 3 homes half), and a
  pinned `Layouts.java` carrier line rewritten IN PLACE to a ternary —
  census count unchanged, caught by the widened marker half alone.
  Stay-silent twin: a planted comment line carrying `Arrays.copyOf` +
  `new long[64]` + `workers(8)` moves nothing (prose is filtered by the
  shared `codeLines`). A second round closed the shared-line hole
  (`/* c */ new long[4];` was blanked whole-line by the prefix skip —
  the filter now removes comment SPANS, honoring string/char literals
  so a `//` inside a URL neither starts a comment nor truncates code).
  Anti-vacuity guards pin the corpus as real (≥20 files scanned, ≥20
  compiled public types against the measured 29, ≥5 backend-token code
  lines) so an empty-scan pass cannot masquerade as clean.
- **Deliberately source-text where the property lives only in source**
  (call sites, branches), reflective where it lives in the compiled
  surface (topology-named methods, fence 2b) — and honest about the
  split: lexical fences are TRIPWIRES, not proofs; the census pins are
  what force a determined evasion to touch the pin table in the same
  diff, making it reviewable.
- **Gates:** full Java suite 331/331 (314 prior + 17 new), zero
  regressions; no Rust/`abi.md`/public-API change of any kind.
- **Board note:** the `STATUS_BOARD` D-LGJ-MMV-0 row lives on PR #47 (the
  plan, in flight when this opened); it flips Queued → Shipped once both
  are on `main`.
- **Confidence:** high — every claim is a pinned count, a red-then-green
  disable-run, or the 331-check suite.

## PR #45 — board: PR #44 arc entry + EXP-KIA-A2-64K fresh measurement + zero-copy/memory-safety doctrine pin (merged 2026-08-28, `b2956d3` — 2 commits, head `22f3293`)

- **Added:** the PR #44 backfill entry below (this file); a fresh
  in-tree `measure_wal_curve` run answering "measure the 64k execution
  end first" (`.claude/board/exp-kia-a2-64k-fresh-run.txt`,
  `E-EXP-KIA-A2-64K-CONVERGENCE-TAIL-DOMINATES-1`); a 9-bullet "Zero-copy
  + memory safety" NORMATIVE, MERGE-GATING section in root `CLAUDE.md`
  transcribing the operator's 32-point addendum, each bullet cited to a
  real enforcement site.
- **Locked (at merge, before the correction below):** compute
  parallelizes (3.27× @ workers=8) but the cast/collect/wal/apply tail is
  flat across worker count and dominates the cycle (~90% at workers=8) —
  evidence for prioritizing the GridLake deterministic-landing-identity
  gate over a parallel-compute seam.
- **Post-merge correction, same-day (2026-08-28):** PR #45 was the ONLY
  review layer this doctrine got — CodeRabbit posted only its
  auto-summary (PR merged before its review cycle ran), Bugbot hit its
  usage limit twice and never ran. A 5+3 council (5 savants incl. this
  repo's own `abi-membrane-warden`/`zero-copy-warden`/`handle-lifecycle-
  auditor`/`java-surface-warden` cards, 3 reviewers) found 5 real
  overclaims in the doctrine text and fixed them in a follow-up commit
  (see `E-ZERO-COPY-MEMORY-SAFETY-OVERCLAIM-CORRECTION-1`): the
  generation-registry claim didn't cover the cached-descriptor path;
  `requireMinor`'s "fails cleanly at the call" claim didn't hold for
  minors 2-4; the bounds-checking claim overclaimed `kernels.rs`
  coverage; "never `segment.set(...)`" was contradicted by the
  already-named Import exception; the materialization list was missing
  two bounded call sites. Also caught: this PR's own missing arc entry
  (this row, backfilled in the correction commit).
- **Deferred, named:** `ISS-LGJ-EPOCH-UNCHECKED` (the `epoch`
  re-validation field exists in the ABI but is never consulted by the
  Java facade — currently unreachable through the public API, not yet a
  live gap).
- **Docs:** `CLAUDE.md` doctrine section corrected in place (5 bullets
  reworded); `ISSUES.md` gains `ISS-LGJ-EPOCH-UNCHECKED`; this file
  backfilled for both #44 and #45.
- **Confidence:** the doctrine text is now council-ratified (5+3, full
  sequencing) rather than single-pass-audited; the 64K measurement entry
  is unaffected by the correction (a different EPIPHANIES entry, not
  reviewed by this council).

## PR #44 — R1→minor 10: mask algebra restored, the reduction repatriated, the doctrine pinned, the columnar store landed (merged 2026-08-27, merge — 5 commits, head `bd6f666`)

- **Added, as one arc** (the PR body predates its own last three commits —
  it says "R2 measured, not landed", which commit 5 then landed; this entry
  is the current record):
  1. **R1** (`0385269`) — `lgj_hop` selects with `src ∧ class_f ∧ struct_f`,
     word-parallel; the walk only EMITS. F2 closed: the structured-edge gate
     was an `if` in EVERY prior version incl. #22's — it is the same strided
     primitive at `+12`, one call site, zero new kernels.
     `facet_bits`/`facet_cache` deleted (a stored projection). Byte-identical
     (pinned 10/19/29), and honestly a 19× regression on AoS — the layout
     named as the defect, per R11's prior 9.2× pricing.
  2. **§13 de-staled** (`ba377b3`) — abi.md described the pre-R1
     composition; the #39-shaped prose-lag caught by adversarial re-read.
  3. **Minor 9** (`c3ecf37`) — `lgj_rowstore_facet_match_count`: the
     facet-match reduction computed where the data is, after THREE Java-side
     shapes of it (segment popcount loop; 32 composed counts summed in
     Java; a proposed buffer-popcount symbol). Two independent oracles;
     both compat directions vs a real minor-8 library. Exposed en route:
     root-invoked release builds were silently REFUSED (toolchain floor,
     error hidden by tail-piping) — earlier R1 Java runs had loaded a
     pre-R1 `.so`; harmless (no observable change) and caught by the
     minor-9 gate itself.
  4. **Doctrine** (`824996d`) — the simd.rs isomorphism as root CLAUDE.md's
     ENFORCEMENT LAYER E1–E6 (Java=facade/37-fns-0-instructions,
     Valhalla+Panama=polyfill, Rust=backends/488-intrinsics; scalar is a
     backend BELOW the facade; facade intrinsics only as cfg(test) oracles).
     J2 closed: `Layouts` derives the geometry, the facade names it.
     CODEX_REVIEW_CHECKLIST §8 added (the "saves a crossing" tell).
  5. **Minor 10** (`bd6f666`) — `lgj_rowstore_open_columnar`: facet-major
     over the (row × facet) plane, same 512n bytes/draws/content (pinned,
     bytes-differ anti-vacuity). Lane table 33→97 (lo64/hi32 lanes join
     classid) so EVERY field is descriptor-served; Java proven LAYOUT-BLIND
     (accessors read only via descriptors; `rowOffset` + last facade
     geometry constants deleted; disable-run red at row 1 facet 0 —
     the first address divergence — AoS green). Register-sweep family
     refuses facet-major with new `UNSUPPORTED_LAYOUT` (−18), two-sided.
- **Locked:** HOP EXECUTES AS MASK × CLASSVIEW → MASK, at every layer with
  a named gate; a layout is a SCHEMA (constructor + descriptors, never a
  resource kind); carving groups ≤ 4 B and 64-alignment of 512/regions/
  blocks pinned as tests (`carving_groups_fit_the_flattening_budget…`) —
  the substrate half of R4/R10's Valhalla measurements.
- **Measured, banked on the board:** AoS mask algebra 19× WORSE than the
  old sweep (`hop-mask-algebra-vs-columnar.txt` — the finding that forced
  minor 10); through the REAL ABI, columnar hop **4.7×/5.9×/3.8×** over AoS
  at classid/2-hop/full arms, equivalence asserted before timing
  (`columnar-store-abi-bench.txt`).
- **Deferred, named:** the fused single-plane pass (~10× further, per the
  lab arm); the register-sweep family on facet-major (an honest −18, not a
  gap); Vector API permanently lab (E4).
- **Docs:** abi.md §13 rewrite, §18 new, symbol count 25→26, status −18,
  minor 9+10 history; root CLAUDE.md E1–E6.
- **Gates:** Rust 138/139 (both feature configs) · clippy `-D warnings` +
  fmt · Java **314 core** (ColumnarStoreTest 10 new) **+ 143 consumer** ·
  runtime-confirmed `abi 0.10` · OldAbiCompatTest both directions vs REAL
  minor-8 AND minor-9 libraries built from prior commits in worktrees
  (path deps resolve relative to the worktree — it must sit beside the
  sibling repos, not in /tmp).
- **Confidence:** high — every claim above is a pinned test, a banked
  measurement, or a disable-run observed red-then-green; the one narrative
  caveat is that the PR BODY describes only commit 1's state.

## PR #42 — lgj-abi: the REAL OGAR ClassView provider, bound behind a feature (merged 2026-08-27, `507cc93`)

- **Added:** `ogar-classview` feature on `native/lgj-abi` binding
  `ogar_class_view::OgarClassView` — the ontology-backed provider over
  `ogar_vocab` — so `edge_participation` derives from each class's real
  field basis instead of the fixture's `FieldMask::FULL` constant. New
  `examples/classview_census.rs`. A `[patch."…/lance-graph"]` collapsing
  the git-vs-path SourceId split (ogar-class-view pulls the contract by
  git branch, this crate by path; cargo does NOT unify the two, so
  without it the build carries two `lance-graph-contract` crates and two
  incompatible `ClassView` traits — the trap recorded in tesseract-rs for
  `ogar-doc-ir`). Verified: `cargo tree` shows one.
- **Locked:** participation is a per-class fact, not a constant. Measured
  over the vocabulary: **98 registered classes, 12 distinct participation
  masks** (field counts 0-13) against the fixture's single answer for all
  98. An unregistered classid participates in NOTHING — an unknown class
  is not a licence to traverse every facet.
- **Deferred:** the row CONTENT. Binding the provider EXPOSED that the
  generated store draws classids from `0..16`
  (`ROWSTORE_CLASS_CARDINALITY`) while every vocabulary classid is
  `>= 0x0100` — **disjoint**, so a generated store under the real
  provider hops nothing. Lance-loaded SoA rows are what make the bound
  provider observable end-to-end. Pinned by
  `hop_under_the_real_provider_narrows_by_class`, not left in prose.
  Also deferred: narrowing participation to associations-only (the
  `fields()` answer flattens attributes + associations, so it is a
  SUPERSET — safe direction; the split needs an OGAR-side ask).
- **Docs:** `class_view_provider`'s module docs rewritten (fixture is now
  the DEFAULT provider, not "the ONLY one this crate needs"); the census
  numbers and the disjoint-domain gap stated there rather than only in a
  commit message.
- **Gates:** default (feature OFF) Rust **134/134** and Java **447/447**
  (304 core + 143 consumer) — unchanged, the point of defaulting off;
  feature ON **136/136**; `clippy --all-targets -- -D warnings` + `fmt
  --check` clean in BOTH configurations; G11 contract-import fence green
  (`class_view`, `canonical_node`, `ontology`, `facet` only).
- **Disable-run, red-then-green:** `edge_participation`'s ogar arm
  returns `FieldMask::FULL` → **5 tests red** (4 provider + the hop),
  green on restore.
- **Two gated tests, each with a contrasting twin** — neither deleted nor
  suppressed. `edge_participation_covers_exactly_the_low_32_bits` (all
  32) pairs with `the_real_provider_narrows_rather_than_widens`
  (unregistered → 0; richest class → exactly its low-13 prefix);
  `hop_matches_the_pinned_rowstore_regression_10_19_29` (19/29) pairs
  with the hop twin above (empty — two-sided, since the provider still
  answers 13 for `0x0103`, so emptiness is the store's domain and not a
  dead provider).
- **Confidence:** high. Both halves are measured numbers with a
  red-then-green disable behind them. The finding — that a fixture
  answering `FULL` for every input cannot be falsified in place, and that
  binding a real provider is what measures the fixture's reach — is
  recorded as `E-BINDING-A-REAL-PROVIDER-MEASURES-THE-FIXTURE-1`.

## PR #41 — [DO NOT MERGE AS-IS] hop as AND over a memoised per-facet mask — measured, conditional (merged 2026-08-27, merge `9cd8a65`)

- **Merged despite its own title, and the conditionality is the record.** The PR opens *"Not recommended for merge as-is… it is a **2 500× regression cold**"* and exists so the work and its numbers are not stranded while the choice is made on data. It landed on `main` anyway; a future session reading this must not read the merge as an endorsement.
- **Added:** `RowStore::facet_bits(classid) -> Arc<[u32]>` (`native/lgj-abi/src/rowstore.rs`) — one 32-bit per-row mask of which facets carry that class, built once through `simd_rowstore_facet_match` and shared by refcount, behind an `RwLock<Vec<(u32, Arc<[u32]>)>>` bounded at `FACET_CACHE_SLOTS = 4` (`n_rows × 4` bytes each, 1 MiB at 262 144 rows), oldest-first eviction. `lgj_hop` (`exports.rs`) becomes `facet_bits[row] & effective_facets` plus a `trailing_zeros` walk — an AND — replacing #40's inline per-facet `u32::from_le_bytes` classid compare. `examples/hop_gather_vs_sweep.rs` extended to four shapes with equivalence asserted at every point; raw output banked at `.claude/board/hop-memoisation-cold-vs-warm.txt` (five populations × twelve densities).
- **What it says #40 got wrong:** not the numbers — the STRUCTURE. #40's gather "traded the algebra away: the classid predicate stopped being a mask at all." The PR decomposes the hop four ways (classid compare / `payload_hi32 == 0` / ∩ `src` / scatter) and finds only the scatter genuinely outside mask algebra — the destination index is decoded from the row's payload, so it is data-dependent. The contract had held under #40 (no `java/`, no `abi.md`, no minor bump, allowlist + G2 + G3 green at the identical 384-byte floor); the mask-native currency had not.
- **The regression it introduces, as the PR measured it (65 536 rows, Component G):** warm the AND wins everywhere, up to **13.5×** (0.01 % 2.7 → 2.8 µs; 1 % 46.8 → **10.1 µs**; 25 % 3 673.3 → **271.5 µs**; 100 % 6 070.2 → **1 952.3 µs**). Cold it is flat at ~6 900 µs at every density because the O(n) build dominates — at 0.01 % that is **2 500× worse than #40's gather**. Break-even ~**190 hops** at a 1 % frontier, ~**2 hops** at 25 %+.
- **No invalidation, by construction:** `RowStore` exposes no `&mut self` method, so the buffer is immutable for the store's life. The read lock is dropped before any build, so two threads racing a cold classid may both build — a wasted pass, never a wrong answer.
- **Deferred — the three options, undecided:** (1) this, unconditional (worst case 2 500×); (2) keep #40's gather (no regression anywhere, leaves 13.5× on the table for repeated dense hops); (3) a **lazily-filled mask** — fill each row's `u32` during the gather that first touches it, with a per-row filled bit: cold ≈ gather, warm = the AND, no O(n) build, no tuning knob — **the PR's own recommendation, pending a measurement of whether rows are re-hopped, which is not measured.**
- **A separate finding, one variable at a time:** the first run was on JDK 27 and looked like a further win. The SAME binary re-run on JDK 26 lands within ~5 % and marginally *faster* (1 %/65 536: 10.856 vs 10.713; 25 %/65 536: 250.0 vs 262.5). **The JDK contributes nothing** — confirmed against the source: `grep` finds **zero** `value record` / `value class` in `java/src/main/java`, so JDK 27 is a different JIT and no Valhalla flattening. Consequence recorded: every Component G number in this repo, past and present, includes ordinary heap allocation for the per-hop `Mask` / `WideFieldMask` wrappers — Panama is real and measured, Valhalla has never run outside `valhalla-lab`.
- **Docs:** `lgj_hop`'s kernel-composition comment rewritten to the three-try arc (32 sweeps 24.8 ms → gather 34 µs → memoised AND) with each superseded shape named; a doc comment displaced onto `bytes_arc` by inserting `facet_bits` above it, fixed — caught by clippy's missing-docs gate, not by reading.
- **Gates (as the PR claimed them):** lgj-abi **134/134**; AllTests 304, GraphHopTest 66 (G3 unchanged), TradesParity 12, TradesAllocation 3, BricksAuth 62 = **447 Java checks**; `clippy -D warnings` + `fmt` clean. No ABI change, no minor bump.
- **Confidence:** medium-high on the measurements (two instruments, equivalence asserted at every configuration), but the DISPOSITION is unresolved — the branch is merged while its own body recommends against it, and option 3's upside is explicitly unmeasured. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #40 — lgj-abi: hop gathers instead of sweeping — the predicted crossover does not exist (merged 2026-08-27, merge `2758730`)

- **Added:** `lgj_hop` (`native/lgj-abi/src/exports.rs`) rewritten to GATHER — touching only the rows `src` names and reading each row's participating facets in place out of that row's own 512 bytes, no per-row intermediate at all. New `native/lgj-abi/examples/hop_gather_vs_sweep.rs` (202 lines): both shapes over five populations × twelve densities, **byte-identical output asserted at every configuration** plus an anti-vacuity guard against an empty hop (which would let both shapes "agree" on nothing and time two empty loops). Raw output banked at `.claude/board/hop-gather-vs-sweep-crossover.txt`.
- **What it corrects in #39:** #39 left the compare half sweeping the whole population and predicted a **density crossover** — sparse frontiers favouring a gather, dense ones favouring the sweep's sequential vectorised access — and said the merit of the next rung depended on it. **Measured: no crossover. Gather wins all 60 configurations**, 2 754× at 0.01 % density down to **1.73× at 100 %** (1 024 rows: 361×/113×/7.3×/1.73×; 262 144 rows: 2 754×/90×/2.6×/1.73×).
- **The mechanism is the correction, not just the number:** a sweep MATERIALISES an `n`-element per-row intermediate that each row reads exactly once, so it is never amortised — at full density it does everything the gather does PLUS allocate, zero, write and re-read `n` u32s. Strictly more work at every density. The prediction reasoned about access PATTERN and missed that one shape simply does MORE.
- **Locked:** the absence of a crossover makes the change **unconditional** — no threshold, no dispatch, no heuristic gate, and none of the two-sided evidence such a gate would have demanded. Also locked: a scalar gather beats a vectorised `ndarray::simd` sweep — the win is in not doing the work, not in the vector width. No SIMD primitive is orphaned by this (checked, not assumed): `simd_rowstore_facet_match` remains behind `lgj_row_facet_match`, `simd_rowstore_classid_mask` behind `lgj_op_eq_classid`.
- **End to end through Component G, the independent instrument:** `native_hop` 1 %/65 536 **24 798 → 7 120 (#39) → 34.4 µs** (**720×** vs original); 1 %/4096 479.0 → 1.9 µs (246×); 25 %/4096 8.5×; 25 %/65 536 6.7×. **The ordering the component was built to test has inverted** — native is now fastest at every configuration, 2.0×–13× ahead of the best scalar arm, having begun the arc slowest at every configuration by 2.6×–165×. The two instruments agree: the Rust probe measures the sweep at 6 754–8 118 µs at 1 %/65 536 against JMH's 7 120 µs.
- **Honesty on the JMH re-run, as the PR states it:** noisier than the previous one — `classidScan` at 1 %/4096 reports 25.6 ± 61.9 µs, an error bar larger than the score — so the SCALAR absolutes in that table are weak. Native's own errors are tight (1.9 ± 0.3, 34.4 ± 8.1) and a 720× change is far outside container noise.
- **Deferred:** REUSE — memoising a per-row mask across many hops on the same `(store, classid)`. Named as "a caching design with its own invalidation questions, and deliberately not this function's". PR #41 attempts exactly this the same day.
- **Docs:** `lgj_hop`'s "Kernel composition" block still described the **pre-#39** design (one full-width classid sweep per facet, a scratch buffer "REUSED across every participating facet") — #39 changed the code and left the prose. Rewritten to the gather with both superseded shapes named. The overflow guard kept as an explicit check rather than dropped: the gather no longer needs `n`, but row indices are still cast to `usize`. `ISSUES.md` `ISS-LGJ-HOP-SWEEPS-FULL-POPULATION` closed; `bench/RESULTS.md` § G.
- **Gates (as the PR claimed them):** lgj-abi **134/134**; AllTests 304, GraphHopTest 66 (incl. G3 at its unchanged allocation floor), TradesParity 12, TradesAllocation 3, BricksAuth 62 = **447 Java checks**; `clippy -D warnings` + `fmt` clean. No ABI change, no signature change, no minor bump.
- **Confidence:** high — the 60-configuration probe asserts byte-identical output at every point and is independently corroborated by Component G, and the falsified prediction is recorded with its mechanism rather than quietly dropped. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #39 — lgj-abi: hop sweeps the store ONCE, not 32 times — 3.5× at scale (merged 2026-08-27, merge `7be27be`)

- **Added:** `lgj_hop` (`native/lgj-abi/src/exports.rs`, +78/−46) no longer loops `for facet in 0..32 { sweep(all n rows) }`. It calls `simd_rowstore_facet_match` **once** — all 32 facets per row, one `U32x16::eq_bitmask` per 64-byte chunk via `MultiLaneColumn` — then walks **src's set rows** rather than every row × facet, taking only facets that both matched and participate (`facet_bits[row] & effective`).
- **What it acts on:** `ISS-LGJ-HOP-SWEEPS-FULL-POPULATION`, opened hours earlier by #38's Component G. The PR is explicit that the measurement authorises it and nothing here is taste.
- **The arithmetic is the argument, and it is not "too many rows":** 32 × 65 536 strided `u32` compares is ~2 M operations, nowhere near the 24 ms observed — but **32 passes over a 33 MB store**, each re-reading every 512-byte row to look at 4 bytes of it, is ~1 GB of memory traffic. **The loop ORDER was the cost, not the row count** — which is what makes this a re-ordering rather than a new kernel.
- **Locked:** no new kernel. `simd_rowstore_facet_match` already existed and is the same sanctioned `ndarray::simd` surface (`abi.md` §8) — it was being consumed the wrong way round. No ABI change, no signature change, **no minor bump**: an internal re-ordering behind an unchanged contract.
- **Measured, same instrument, same command:** `native_hop` 479.0 → **374.7 µs** (1 %/4096, 1.28×), 24 798.3 → **7 120.3 µs** (1 %/65 536, **3.48×**), 521.2 → 375.8 µs (25 %/4096, 1.39×), 23 633.9 → **8 076.9 µs** (25 %/65 536, **2.93×**). **The control matters:** the two scalar arms are untouched code and moved <9 %, slightly *slower* (`classidScan` 150.6 → 164.2 µs; `facetMatches` 7 142.3 → 7 698.8 µs at 1 %/65 536) — so the native gain is not a faster host, and if anything is understated. JMH's banner reports a different CPU string between runs, which is why the control arms are quoted rather than the box assumed identical.
- **Deferred — and this is what #40 falsifies:** at 1 %/65 536 native is still **43×** the best scalar arm (7 120 vs 164 µs). `simd_rowstore_facet_match` still sweeps the whole population: the DECODE half is now frontier-bounded, the COMPARE half is not. The issue was regraded **RESOLVED IN PART**, not closed. The next rung — gather per src row, O(frontier) — was called "**not obviously better**, because a dense frontier should favour the sweep's sequential access", a real density crossover to be measured rather than judged. **#40 measured it and found no crossover at any of 60 configurations.**
- **Why not both at once (the PR's own reasoning, and it holds):** the one-pass change is bounded, needs no new kernel, has no crossover, and is strictly less work at every point in the measured space; the gather rewrite is none of those. Landing them together would make a regression in either impossible to attribute.
- **Docs:** `ISSUES.md` regraded with what remains open and why the split; `bench/RESULTS.md` § G carries the before/after table, the control-arm reasoning, and the named remaining gap — same commit.
- **Gates (as the PR claimed them):** lgj-abi **134/134** (the hop's own aliasing and semantics tests among them); AllTests 304, GraphHopTest **66** including **G3 at the identical 384-byte allocation floor**, TradesParity 12, TradesAllocation 3, BricksAuth 62 = **447 Java checks**; `clippy -D warnings` + `fmt` clean.
- **Confidence:** high on the direction and the control-arm reasoning; the absolutes inherit #38's container noise caveat. The PR left its own `lgj_hop` doc comment describing the pre-change design — caught and fixed one PR later by #40, not by this one. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #38 — bench Component G — the F-PARITY harness, measured; W8 FALSIFIERS + POLICY closed (merged 2026-08-27, merge `b572457`)

- **Added:** `bench/.../G_HopExecutionBoundary.java` (254 lines) — native `lgj_hop` (mask in, mask out, no row id ever produced) against the two scalar oracles **preserved verbatim** from `GraphHopTest`, reused rather than rewritten precisely because they are already cross-checked against each other AND against native on every change, so a divergence here is real rather than a fresh transcription bug. Swept on **both** axes §3.8 requires: rows {4096, 65536} × frontier {1 %, 25 %}; all three arms must agree on the destination set in `@Setup` before anything is timed, plus an anti-vacuity guard refusing a fixture whose hop reaches nothing. Own `bench/results/jmh-results-G.csv` — different fixture, different axes, deliberately not merged into the A–F tables. No production code touched.
- **Scoping first, because it changed the work:** §12 marks exactly **two** of eight `F-*` as [W8]; the other six (`F-PAR`/`F-ORD`/`F-ONE`/`F-SPARSE`/`F-ACK`/`F-LAND`) are pre-registered for the COMPUTE wave, where the board's "spec §12 F-* pre-registrations" wording had implied all eight. **F-HYDR was already shipped** (GraphHopTest's G3 gate, allocation flat at a 384-byte floor across 10-vs-500 rows). **POLICY was discharged at the A3 freeze (PR #20), not last** — every §3.9 artifact exists dated 2026-08-18 (root `CLAUDE.md` incl. §13, `EPIPHANIES.md:683` storno, the STATUS_BOARD row, LATEST_STATE, PR_ARC_INVENTORY); the ladder's `… → FALSIFIERS → POLICY` ordering is misleading and the board carried POLICY as remaining for nine days.
- **The finding this arc exists for.** Native is slowest at **every** configuration, **2.6×–165×**: `java_scalar_classidScan` 8.5 / 150.6 / 204.0 / 7 173.6 µs, `java_scalar_facetMatches` 394.9 / 7 142.3 / 433.8 / 10 071.5 µs, `native_hop` **479.0 / 24 798.3 / 521.2 / 23 633.9 µs**. **The ranking is the less interesting half — the SHAPE is the finding:** flat in frontier density (479 → 521 at 4096; 24 798 → 23 634 at 65 536 — a 25× bigger frontier costs *nothing*) and linear in population (16× the rows → ~52× the time). A hop whose cost tracks the population it ignores rather than the frontier it starts from is doing full-population work.
- **Root cause localized, in the PR, not deferred to a later reader:** `exports.rs:1589-1599` builds the classid mask across the whole population once per participating facet — 32 full-width sweeps per hop, ~2.1 M strided classid reads at 65 536 rows before one edge is decoded — and only then intersects with `src`. Filed as `ISS-LGJ-HOP-SWEEPS-FULL-POPULATION`.
- **Deferred deliberately, with the reason stated:** the remedy is a KERNEL change, and §12 scopes W8's F-PARITY to "seeds the HARNESS only" on a component §3.8 declares non-gating — a kernel rewrite inside a bench commit widens the PR past what the measurement authorises. Acted on the same day by #39. Also absent deliberately: the Vector-API arm §3.8 calls "optional" — it would be a second implementation of the kernel, and building one before the scalar arms had been measured is the taste-before-measurement §3.8 forbids.
- **What it does NOT say:** not a verdict on mask-native execution. Allocation independence is pinned separately (G3) and unaffected; the no-row-id guarantees are structural. This is the throughput-placement axis §3.8 says a measurement decides.
- **Caveats carried, not buried** (in `RESULTS.md` and `ISSUES.md` both): the per-call `Engine.createMask` + close has no scalar analogue and was **not** isolated — implausible as the story at 470 µs on 4096 rows and it would not scale with `rows`, named anyway. Native absolutes are noisy (±12 844 on 24 798) on a shared 4-vCPU container: the ordering is robust, the absolutes are not. One machine, one run. The first run overwrote `jmh-results.csv`; caught by `git status` and restored.
- **Also corrects a stale cross-repo claim, in the file that exists to prevent them:** `WAVE_STATUS_CHECKLIST.md` said `ruff_r2il` PR1/PR2 were unmerged and PR2 was a "drill-down proposer". Measured against the repo: PR1 **DONE** (`CORPUS-PROFILE-RESULT.md`, 100.00 % inline fit on all four binaries), PR2 **DONE** (`ORACLE-RESULT.md`, **zero mismatches over 35,946 matched op sites**), and PR2 was never a drill-down proposer — its gate deliverable is the round-trip oracle. The real open item is **O6** (the `MemorySpace` schema-widening decision), which scopes O5/PR3's `ogar_codebook` mint. Wrong text quoted rather than deleted, per append-only.
- **Docs / board hygiene, same commit:** `STATUS_BOARD` flipped to **D-LGJ-W8 COMPLETE**, the `ISSUES` entry, the checklist correction, `bench/RESULTS.md` § G, `bench/README.md`.
- **Gates (as the PR claimed them):** bench compiles clean under `run.sh`'s exact command line (`javac -proc:full`, JDK 26.0.2); Component G's `@Setup` cross-check passed at all four configurations — the harness gating itself; no production code touched.
- **Confidence:** high on the shape (flat-in-density, linear-in-population is a structural signature, not a noise artifact) and on the localized root cause, which the next three PRs each act on; low on the absolutes by the PR's own caveat — one machine, one run, ±12 844 on 24 798. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #37 — board: pin the wave-status checklist (the LGJ wave is done; ghidra-g1-g2 is superseded) (merged 2026-08-27, merge `8da1dae`)

- **Added:** `.claude/board/WAVE_STATUS_CHECKLIST.md` (new, 142 lines, the only file touched) as the durable answer to a recurring cross-session conflation, operator-corrected 2026-08-27: sessions kept restating that "one lance-graph-java wave is ~90 % done" and attributing the remainder to `wave-ghidra-g1-g2`. **Wrong on both halves.** The substrate+facade arc (`D-LGJ-A…AUDIT` 12 rows, `W1`–`W7`, the mask-native sweep `SWEEP-5`…`SWEEP-8` = ABI minors 5–8) is COMPLETE; `wave-ghidra-g1-g2` is SUPERSEDED (2026-08-18), its own file saying "do not dispatch under any circumstance", the capability having shipped upstream as `AdaWorldAPI/ruff` PR #94 (`crates/ruff_r2il`). The genuine ~90 % item is **D-LGJ-W8**: AUDIT→SPEC→COUNCIL→FREEZE→SUBSTRATE→FACADE→GRAPH MIGRATION all done, **FALSIFIERS** and **POLICY** remaining — 7 of 9 rungs, entirely in-repo, nothing upstream blocking it.
- **Locked:** the two `G1`/`G2` name collisions that fed the confusion are named and separated in the doc — the D-LGJ-W5 graph-consumer workers G1/G2 (both DONE, PR #18) and the D-LGJ-W8 spec gates G1/G2/G6/G9/G11 — neither of which is `wave-ghidra-g1-g2`. The real Ghidra/R2IL critical path is placed upstream (`ruff_r2il` PR2 → PR3, also gate #3 of the blocked `wave-ogar-machine-pm1`, repointed 2026-08-18 from the old unsatisfiable "Ghidra G1+G2 merged" wording).
- **The measured negative, recorded because the opposite reads as in-flight:** `native/lgj-abi/Cargo.toml` `[dependencies]` is exactly two local path deps — `ndarray`, `lance-graph-contract`; no `r2sleigh`, no `ogar-r2il`, no `r2conc`. Every apparent Ghidra hit across Rust, Java and `docs/` is the substring `opcode`, naming this crate's own `LgjOpCode` ABI vocabulary. The only real P-code content is `valhalla-lab/reproducers/R12_GhidraPcodeVocabularyVsCliff.java`, wired into nothing. The seam is verified and the vocabulary measured (PR #34), but **no binding exists.**
- **Method, as the PR states it:** verified against three independent reads not allowed to see each other's sources — the seven `.claude/waves/*.md` files, the four board files, and a git + source census that trusts no doc; all three agree. Statuses are copied from `STATUS_BOARD.md` / `LATEST_STATE.md`, not re-derived. The census the file banks: 8 Rust files / 7,444 lines / 134 `#[test]`; 33 Java files / 4,317 lines / 14 suites; three consumer modules each with its own suite.
- **Deferred:** nothing recorded — the file is a status pin, not a plan.
- **Docs:** the new checklist is itself the doc; no other board file changed in the PR.
- **Gates:** docs only, no code gates. The PR states explicitly: no code, no ABI, no layout, no test touched.
- **Why this one gets an entry at all** (the termination clause does not apply): the PR's content is a CORRECTION of a standing cross-session claim plus a supersession ruling, not board hygiene recording prior PRs. A future session needs the "why" — which is exactly the test the clause turns on.
- **Confidence:** high on the correction itself (three-way agreement, and the dependency/substring negatives are mechanically checkable); the per-rung W8 statuses are copied from the board rather than independently re-verified here, which the PR says in its own Method section. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #36 — board: one substrate, five gloves — Ghidra is the glove, not the model (merged 2026-08-26, merge `79885e7`)

- **Added:** one `EPIPHANIES.md` entry (100 lines, the only file changed), `E-ONE-SUBSTRATE-FIVE-GLOVES-GHIDRA-IS-THE-GLOVE-NOT-THE-MODEL-1`, status DOCTRINE [OPERATOR-FRAMED] — naming the vision the r2il/r2conc/ogar-loco arc has been building toward so it stops being re-derived each session. The claim: every product framing — bring-your-own-code Foundry substrate, RE/security-analyst platform, zero-trust sandbox (whitelist-only, malware-scan-before-run, autonomous alerting), the C64-game-into-ogar-loco dream, stone-age-Java bare-metal — is the SAME three steps (lower arbitrary code → R2IL, intake once; address it in ogar-loco by ordinal, not object; execute zero-copy through the mask via r2conc). One substrate, a policy/render **glove** on top; only the glove differs.
- **Locked:** "Ghidra as Java" resolves against R12 (PR #34) — the p-code vocabulary cannot flatten (payloads 0/2, ordinals 3/3), so the answer is the inversion the stack keeps re-deriving: **Java STEERS, Rust DECODES+EXECUTES, the seam carries ordinals not objects.** What remains of Ghidra at runtime is one arm — `libsla` decode — with §7.8's hexagon as its replacement path. Also fixed as an invariant for the zero-trust glove: **scan-then-execute, never execute-then-scan** — the malware mask runs on the lifted R2IL *before* `step`; the sandbox executes only `lifted ∩ whitelist`.
- **A correction applied inside the PR (codex P2), recorded rather than amended away:** the first draft banked "2.1 ns/op through Panama into ndarray" — both halves unsupported by the committed reproducer. R7 explicitly refuses to bank a throughput number (1.76–3.48 s, a 2× spread) and its `sweep` is pure in-JVM `MemorySegment.get`, no FFI and no ndarray call, so it cannot support an "into ndarray" claim at all. What R7 does bank, and what the entry now rests on, is **zero per-op allocation — 960 B fixed for 10⁹ ops**, byte-identical across three runs. The doctrine's load-bearing evidence is R12's flatten measurement, not a ns/op. The PR carries two commits for this reason.
- **Deferred:** a real ndarray-through-Panama throughput measurement — the entry states it would need its own committed reproducer and does not exist yet.
- **Docs:** the epiphany is the doc; cross-refs `E-LGJ-GHIDRAS-SEAM-IS-AN-INTERFACE-…` (R12, PR #34) and lance-graph `r2il-machine-semantic-contract-v1.md` §7.8.
- **Gates:** docs only, no code gates — the diff is one board file, no Rust, Java, ABI or test touched.
- **Confidence:** high on the framing and on R12's measured seam, per the entry's own Confidence line; the throughput half is explicitly NOT pinned after the codex P2 correction. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #35 — R12-observed's JDK line said the tool options, not the JDK (merged 2026-08-25, merge `358809c`)

- **Added (a CORRECTION, not a capability):** the one-line provenance fix to `valhalla-lab/reproducers/R12-observed.txt`, banked one commit earlier in PR #34. **What was wrong:** the file's `JDK:` line had captured `JAVA_TOOL_OPTIONS`' startup notice — `Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=… -Dhttps.proxyHost=… -Dhttp.nonProxyHosts=…` — instead of the first line of `java -version`. So the one field identifying WHICH BUILD produced the measurement identified nothing, and dragged a wall of proxy and truststore settings into a committed evidence file. **What replaced it:** `JDK: openjdk version "27-jep401ea3" 2026-09-15` — which is also the build the R12 epiphany cites by name, so the two now agree instead of one being noise. Diff is exactly 1 insertion / 1 deletion in 1 file.
- **Locked:** the reasoning for making it its own PR rather than a quiet amend — an observed file's entire job is to be the durable record of a run, so a provenance line naming the wrong thing is a **defect in evidence, not a typo**. The PR also names the mechanism by which it got through: a claim nothing checks, the repo's own falsifiability rule applied to itself.
- **Deferred:** nothing recorded.
- **Docs:** none beyond the evidence file itself; no board file changed.
- **Gates:** docs/evidence only, no code gates — the measurement rows in `R12-observed.txt` are untouched, only the provenance line changed.
- **Non-finding stated by the PR:** no secret leaked — the captured text is a localhost proxy port and truststore paths, not a credential.
- **Confidence:** high; the change is a single line and both the wrong and the right value are visible in the diff, so nothing here rests on narration. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #34 — R12: the Ghidra seam is an interface, and its P-code vocabulary cannot flatten (merged 2026-08-25, merge `23369f8`)

- **Added:** the Ghidra end of the `r2il-machine-semantic-contract-v1` arc (lance-graph #1027, merged), driven while a sibling session works W0–W4. Five files, 304 insertions, no deletions: `valhalla-lab/reproducers/R12_GhidraPcodeVocabularyVsCliff.java` (120 lines), the banked run `R12-observed.txt` (49 lines, including the VM's own `PrintFlatArrayLayout` output verbatim), a `reproducers/README.md` section, plus `EPIPHANIES.md` `E-LGJ-GHIDRAS-SEAM-IS-AN-INTERFACE-AND-ITS-VOCABULARY-CANNOT-FLATTEN-1` and a dated `LATEST_STATE.md` entry — both board artifacts in the PR's own commit.
- **Locked, finding 1 — the seam is an interface, no core fork required.** An open unknown flagged twice in-session and never checked, gating the entire Java half. Traced: `Language` (interface, `model/lang/Language.java:29`) `.parse(MemBuffer, ProcessorContext, boolean)` → `InstructionPrototype` (interface, `:35`) `.getPcode(context, override)` → `PcodeOp[]`. `InstructionDB.getPcode()` (`:608-628`) does nothing but delegate; `InstructionPrototype` has exactly TWO implementations (`SleighInstructionPrototype`, `InvalidPrototype`); `SleighLanguage` constructs the real one at exactly ONE site (`SleighLanguage.java:392`); `Instruction` is itself an interface with an `InstructionStub` already in tree. A third implementation is the insertion point, and the answer is favourable.
- **Locked, finding 2 — the vocabulary cannot be carried as value classes; its identity can.** Field shapes TRANSCRIBED from `Varnode.java:51-54` and `PcodeOp.java:102-105` (not invented), run through R2/R4's harness on the JEP 401 EA build with `-XX:+PrintFlatArrayLayout` so the **VM** reports element sizes rather than the program asserting them: `VarnodePayload(int,int,long)` 16 B and `PcodeOpPayload(int,long)` 12 B do NOT flatten; `VarnodeNarrow`, `VarnodeRef(long)`, `PcodeOpRef(long)`, `InstructionRef(long)` all flatten at VM element size 8. Banked as **refs flat 3/3, payloads flat 0/2**. The two Payload rows are the OPTIMISTIC lower bound — every reference deleted; the real `Varnode` also holds an `Address` and the real `PcodeOp` a `SequenceNumber`, a `Varnode[]` and a `Varnode`, so **a 2-input `PcodeOp` is five heap objects** — *ONE ROW IS NOT ONE JAVA OBJECT* at its worst. Verdict: the W5 facade ADDRESSES the vocabulary rather than carrying it, which needs nothing new — the same result `LaneId`/`Ordinal`/`MaskId` already rely on, and the same reason `RowRange` (16 B) does not flatten. W5's central question answered before W5 starts.
- **The unanticipated part, named as an option and deliberately NOT proposed as the design:** `VarnodeNarrow` (`spaceId:u8`, `size:u8`, 48-bit offset) also flattens, so 8 bytes carry a varnode's real CONTENT rather than a pointer to it — a descriptor reading space and size with no lane round-trip, bounded by exactly one condition (a 48-bit offset), and whether that suffices is a W0/W1 address-space question, not a Valhalla one. Pre-empting W1's tenant carving from this side is what the plan's own R1 rule ("no private object graph then serialize") forbids one layer up. Also noted: the single-`long` refs come back `NON_ATOMIC_FLAT` while multi-field `VarnodeNarrow` is `ATOMIC_FLAT` — both flatten at 8, only the tearing guarantee differs.
- **Deferred:** everything downstream — no `InstructionPrototype` implementation, no facade types, no descriptor mint; those wait on W1's tenant spec. Nothing swapped, nothing minted, no layout touched; measurement and source trace only.
- **Docs:** `reproducers/README.md` R12 section (question / method / measured table / verdict); EPIPHANIES + LATEST_STATE in the same commit, cross-referencing lance-graph `r2il-machine-semantic-contract-v1.md` §2 and §6 W5.
- **Gates:** `lgj-abi` 134 lib tests green — unchanged, and nothing in the PR touches it (no Rust or Java source file is in the diff). R12 compiles and runs on `/opt/jdks/jdk-27` (JEP 401 EA); the run is banked verbatim in `R12-observed.txt`.
- **Confidence:** high on both halves — each is a cited source location or a VM-reported number, and the evidence file is committed rather than narrated. One caveat, corrected the same day by PR #35: the banked file's `JDK:` provenance line initially named the tool options rather than the JDK, so as merged this PR's evidence did not identify its own build. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #33 — Land the stranded ABI minor 8 on main + board hygiene (merged 2026-08-25, merge `23096ce`)

- **Landed (the stranded content, not hygiene):** the two commits carrying ABI minor 8 that had never reached `main`. PR #32 (minor 8) was stacked on PR #30's branch — two open PRs claiming the same ABI minor would have collided — and #30 merged to `main` FIRST, so #32 merged into a branch already absorbed. `main` therefore sat at `LGJ_ABI_MINOR = 7` while `Carving.java`'s rework, `CarvingTable` + `CarvingTableTest` (136 lines), the load-gate prefix fix in `internal/ffm/{Abi,Layouts}.java`, `abi.md` §17 and the minor-8 `abi.rs`/`kernels.rs` derivation lived only on `claude/layout-probe`, two commits ahead. Nothing was lost or divergent (`main` was a strict ancestor) — but the reviewed work was absent from the branch anyone would build, and neither PR gave any signal: both report "merged", both green. It surfaced only because a wake event prompted a `git log origin/main` check instead of trusting the merge notification.
- **Locked:** the failure is generic to stacked PRs, so it is written down twice rather than quietly fixed — `ISSUES.md` `ISS-LGJ-STACK-TAIL-STRANDED-MINOR-8` (found + resolved, double-entry) and a new `CLAUDE.md` iron rule naming the check that must come back empty: `git log origin/main..origin/<base>`.
- **Added (board hygiene half):** `CROSS_REPO_PRS.md` (NEW, 112 lines, modeled on lance-graph's file of the same name) — this repo is gated on three upstream PRs by its own missing-capability STOP rule and had no ledger of them at all, the gating relationship living only in commit messages: ndarray **#283** (`masked_strided_group_sum`, gated minor 5), lance-graph **#1025** (`ClassView::cascade_shape`, gated minor 6 and minor 8's derivation), ndarray **#280** (the `mask_andnot` family, gated minor 4). Each entry names the missing capability AND what the hand-rolled local version would have been — the part that makes the STOP rule legible rather than a slogan; all three numbers verified against the upstream logs. `README.md` (NEW, 65 lines) — what each board file answers, the one-writer rule and its base case, the non-recursion clause, measure-then-pin, and an explicit section on what is deliberately NOT carried over from lance-graph's board (`sprint-log-*/`, `agent-tags/`, the entropy ledgers): that repo runs large worker fleets, this one has not, and an empty ledger read as authoritative is worse than an absent file. `STATUS_BOARD.md` gains the minors 5–8 sweep arc (it stopped at 2026-08-18 and carried nothing for the sweep, including the two operator corrections that shaped it). `ISSUES.md` also gains `ISS-LGJ-FACETSCHEMA-PAIR48` (open, upstream-owned, verified at `facet_schema.rs:34` and `class_view.rs:1168`), noted honestly as possibly not a defect — `Pair48` has real consumers (`helix` `Signed360`, `cam_pq` `[u8; 6]`, both genuinely 48-bit), so the open question is whether it and `CascadeShape` are one question asked twice; this repo consumes the contract, it does not arbitrate it. `AGENT_LOG.md` gains the session including both owned mistakes.
- **Deferred:** `ISS-LGJ-FACETSCHEMA-PAIR48` stays open and upstream-owned — nothing resolved here.
- **Docs:** `docs/abi.md` (the minor-8 §17 and §2 text arriving with the stranded tail); root `CLAUDE.md` iron-rule addition; `.claude/board/README.md` as the board's own rule set.
- **Gates:** as the PR claimed, re-run on this branch rather than inherited — Rust **134** lib tests, `fmt` + `clippy -D warnings` clean; Java **304** checks; `OldAbiCompatTest` green against minors 1 and 4.
- **Owned mistake:** two, both logged in `AGENT_LOG.md` — the `git checkout` that destroyed uncommitted work in `kernels.rs` (carried over from the #32 session), and the stacked-PR tail this PR exists to repair.
- **Confidence:** high on the stranding narrative and the file inventory — both are visible directly in the 18-file diffstat (1086 insertions) and the parent chain (`53ce031` → `23096ce`). Gate numbers are as the PR body claimed, not re-verified. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #32 — ABI minor 8: the register groupings served as DATA (merged 2026-08-25, merge `feba97e` — reached `main` only via PR #33; see that entry for the stranding)

- **Added:** `LgjAbiManifest.{carving_count, carvings[8]}` (docs/abi.md
  §17) — the §14 wire encoding, previously hand-written in THREE places
  (a Rust `match`, Java's `Carving` enum, §14's table) with nothing that
  would fail if they disagreed. Now one source and two derivations: the
  contract owns the SET (`CascadeShape::ROTATIONS`), `kernels::CARVING_ORDER`
  (a `const`, group count descending) derives the ENCODING, and the manifest
  serves it. Java's `Carving` keeps its declared ARITY (the name IS the
  arity) and looks its wire value up in the served table.
  New: `CarvingTable` (with the one clearly-named pre-minor-8 shim),
  `CarvingTableTest` (16 checks, in `AllTests`), two Rust falsifiers.
- **Locked:** meaning is declared, encoding is served. A variant REORDER
  upstream cannot re-map the wire (position is computed from `groups()`,
  not declaration order); a variant ADDED upstream appears automatically
  and is caught by the both-ways membership test rather than surfacing on
  someone's data. `CARVING_ORDER` is `const` and not `LazyLock` because
  the manifest that serves it is const-initialised.
- **Also fixed (a real latent defect, not scope creep):** Java's load gate
  required the FULL manifest layout, so this — the first growth of the
  manifest struct — would have made every older artifact fail to load,
  contradicting §2's additive promise. Gate now requires only the 104-byte
  BASE PREFIX; later fields are read when `size_of_manifest` covers them
  AND the minor is high enough. Measured: all four historical `.so`s
  (minors 1-4) still load and gate per-minor correctly.
- **Deferred:** nothing new. `FacetSchema`'s third reading is still
  `Pair48` rather than the operator-ruled L6 quads — flagged earlier,
  untouched here.
- **Docs:** `docs/abi.md` §17 (new), §2 (load-gate prefix + minor-8
  history), §14 table regraded DESCRIPTIVE, manifest struct listing,
  header constants.
- **Gates:** Rust 134 lib tests / fmt / clippy `-D warnings` clean; Java
  304 checks (`AllTests`, was 288); `OldAbiCompatTest` 4/7/7/7 against
  minors 1-4. Stacked on PR #30 (minor 7).
- **Disable-runs, all red-then-green:** swapping the packed axes fails the
  Rust serve test; reversing the sort fails the order test and two others;
  changing one Java constant's arity fails BOTH membership directions;
  restoring the full-layout load gate makes the minor-4 library fail to
  load outright.
- **Confidence:** high on the derivation and the falsifiers (each disabled
  and observed red). Medium on the 8-slot table width — the bound is
  argued from `G·D = 12`'s divisors, not measured against a future
  `CascadeShape`.
- **Owned mistake:** mid-session I ran `git checkout` on `kernels.rs` to
  undo a disable-run edit and destroyed the uncommitted work in that file.
  Reconstructed and re-verified (134 tests, same count). Disable-runs are
  now backed up to a file first, never reverted with `git checkout`.

## PR #31 — valhalla-lab: R11 — the physical layout is a schema; SoA lanes measured 9.2× (merged 2026-08-25, merge `d741fd9`)

- **Added:** `valhalla-lab/reproducers/R11_LayoutIsASchema.java` (122 lines) + `R11-observed.txt` (38 lines) + a `README.md` section — 190 insertions, **zero deletions, lab-only, no ABI change**. Answers the question *"would it be difficult to nudge Valhalla/Panama through 32×12-bucket SoA?"* with a measurement and a structural finding.
- **Measured:** one-facet sweep, 65,536 rows, the same logical content under two layouts, checksum-parity-pinned across all 32 facets — AoS 512-stride (today) **12.0–13.0 ns/row** against SoA facet-lane (32 × 12-B-register buckets) **~1.30 ns/row**, i.e. **~9.2×**. Line arithmetic predicts only 4× (16 of 64 B used per line vs all 64); the rest is sequential prefetch plus 32× denser TLB coverage (256 rows per 4K page at stride 16 vs 8 at stride 512). An earlier note in this repo called the 4× *"arithmetic, not a result"* — it is now a result, and an underestimate.
- **Locked (the structural finding, which the PR body ranks above the ratio):** the layout was already DATA at every boundary except the store's constructor. ONE projector runs both layouts, selected by a `LayoutSchema` **record** — the "schema apply" is a descriptor swap, not code; no Java type changes, so **Valhalla is untouched by construction** (what crosses is still a ≤4-B group, only offsets moved); and the native kernels are already stride-parameterized (`masked_strided_group_sum`, `eq_u32_strided_to_mask`) — AoS is stride 512, an SoA lane is stride 16, one code path either way, with `LgjLaneDesc` carrying `stride_bytes` since minor 1. The ABI-side cost of a real SoA store is therefore an **additive constructor plus lane descriptors**, not a kernel rewrite and not a Java rewrite.
- **Deferred:** a real SoA store (this PR builds none). Explicitly not measured: writes/generation, and Lance's own on-disk columnar behaviour. Scope stated honestly in the body — a whole-ROW consumer inverts the preference (AoS is contiguous for "all 32 facets of one row"; SoA is the scattered one there), which is exactly why the layout belongs in data as a per-workload schema.
- **Docs:** `valhalla-lab/reproducers/README.md` (+30) is the whole doc surface; `docs/abi.md` untouched.
- **Gates:** none recorded in the PR body — no ABI or main-tree code changed, so no Rust/Java suite number is claimed for this PR. The evidence is `R11-observed.txt` (the committed run output) and the checksum-parity pin inside the reproducer.
- **Confidence:** medium-high. The diff is unambiguous (three lab files, additive only) and the structural claims about `LgjLaneDesc`/the stride-parameterized kernels are checkable in-tree; the 12.0–13.0 vs ~1.30 ns/row numbers are as the body and `R11-observed.txt` claimed them, on one machine, not re-run here. No disable-runs recorded for this PR. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #30 — abi: minor 7 — whole-row layout probe, and the classid table made global (merged 2026-08-25, merge `53ce031`)

- **Added:** `lgj_row_layout_probe` — for EVERY facet, the set of groupings its selected rows carry, in ONE crossing. Per facet, OR-accumulate a 3-bit set plus one bit for an unanswerable classid, then `aligned ⟺ popcount(byte) == 1 && no unanswerable bit`. One `or` per (row, facet): no comparison, no early exit, cost independent of the data. **An OR-accumulated set is exact where cheaper accumulators are not** — a sum of wire values cannot tell `{0,2}` from `{1,1}`, an XOR cannot tell `{1,1}` from `{}`; the set forgets multiplicity, which is exactly what the question does not need, and `0` stays the empty set, distinguishable from disagreement. Java side: `RowLayout` (NEW, 98 lines), `RowStore` + `FacetId` accessors, `internal/ffm/{Downcalls,Engine}.java` wiring, `FacetSumParityTest` (58 lines). Rust side: `native/lgj-abi/src/class_view_provider.rs` (NEW, 115 lines), `exports.rs` + `kernels.rs` growth, `abi.rs` minor bump. Plus `valhalla-lab/reproducers/R10_SchemaAlignsWithStorage.java` + `R10-observed.txt`.
- **Corrected (shape, not output):** the `classid → grouping` table was on `RowStore` — one 64 KiB copy per dataset. The same classid means the same class in every SoA, so the resolution is dataset-independent, provably so here since `FixtureClassView` is a unit struct with no per-store state. Hoisted to a process-global `LazyLock` in `class_view_provider` (the 44 lines deleted from `rowstore.rs` are that move). The answers were already right; the placement implied two datasets could disagree about what a classid carves into, which the address space does not permit.
- **Locked:** the table captures **layout only** — meaning, RBAC, ontology category and render template are separate resolutions off the same address, none belong in the table and none can be inferred from it, stated in `abi.md` so it does not accrete. R10's result: raw storage bytes, a Panama `MemoryLayout`, and a Valhalla value class decode every register identically, each layout describes exactly 12 bytes, and the schemas genuinely read differently so the agreement is not trivial — with the flattening split pinned, the whole 12-B schema `false` (and cannot be), one group (2 / 3 / 4 B) **`true`, all three**. **The schema bolts on at the GROUP, not the register:** `12 = 6×2 = 4×3 = 3×4` means the largest group in any carving is 4 bytes, half the flattening budget, while the register is 12 and the facet 16 — neither of which Java can flatten or needs to.
- **The probe paid immediately:** a test asserting a `maskOfFacetClass(facet 3, …)` selection is fully aligned **failed**, and the expectation was wrong rather than the code — that mask constrains facet 3 only, so the other 31 facets carry whatever classids the generator gave them. Measured **1 of 32 facets aligned**. Precisely the confusion a whole-row probe exists to remove.
- **Deferred:** nothing recorded.
- **Docs:** `docs/abi.md` +67 (the probe, the global table, the layout-only statement); `valhalla-lab/reproducers/README.md` +23.
- **Gates:** as the PR claimed — **132 Rust · 288 Java** · `clippy -D warnings` clean · `fmt` clean · `abi 0.7`.
- **Confidence:** high on the structural claims (the `rowstore.rs` → `class_view_provider.rs` move and the new probe are directly legible in the 15-file diffstat, 734 insertions / 58 deletions). The 1-of-32 measurement and the gate counts are as the body claimed, not re-verified; no disable-runs recorded for this PR. Note also that this PR merged to `main` BEFORE the minor-8 PR stacked on its branch — the stranding PR #33 then had to repair. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #29 — abi: memoise the carving resolution — per dataset, and per population (merged 2026-08-25, merge `b0e84e2`)

- **Added:** both memos the resolved sweep was re-deriving, at their two natural lifetimes. **Per dataset** — `RowStore` gains a `OnceLock` `classid → grouping` table built on the first resolved sweep; `class_id_for` narrows a `u32` classid to `u16`, so it is 65,536 one-byte entries (`0` = no ClassView answer, else wire+1): **64 KiB, 65,536 ClassView calls once, never again** — bounded and one-off against a per-row consult that is unbounded in sweeps. `OnceLock` rather than `LazyLock` because the resolver is supplied by the caller: first caller wins, every later one reads. **Per population** — the grouping itself, memoised INSIDE `MaskWords` (`registry.rs`), so it is read under the same lock that guards the words and a resolution can never be observed against a population it was not computed from.
- **Locked (three properties that make it safe, not merely fast):** **keyed by facet, not merely cached** — different facets of the same rows carry different classids and can resolve differently, so a memo holding only "the grouping" would answer a question about facet 3 with facet 7's answer, *wrong, not stale*; **invalidation cannot be forgotten** — it lives in `write_mask()` and `lock_masks_ordered()`, the only two ways to obtain the right to mutate a mask, and any writer invalidates whether or not it changes a bit (conservative, and impossible for a new mask-mutating op to skip); **filled under the READ guard** — the obvious alternative (compute under read, upgrade to write, store) is wrong twice, since it would clear the very memo it is storing and, between dropping the read and taking the write, another thread could change the population so the stored value would describe rows that no longer match. The read guard IS the interval in which the population is stable — hence `AtomicU64` (`resolved_carving`) rather than a plain `Option`, encoded `0` = empty else `PRESENT | (facet << 8) | wire` with `CARVING_PRESENT = 1 << 63`, so **facet 0 / grouping 0 is a real answer rather than reading as an empty memo**.
- **Disable-runs, five, all red-then-green as the body claimed:** ignore the facet key; stop invalidating in `write_mask`; stop invalidating in `lock_masks_ordered`; and drop the present bit — **both halves**. That last one is the recorded lesson: removing only the *check* leaves the bit still *set*, so the first attempt at that disable passed, and the wrong thing was the disable rather than the test. A test-only `RESOLUTIONS` counter makes the memo observable rather than asserted — the first sweep resolves, five repeats over the same population do not, a different facet does, and a rewritten population does. **A memo that never hits is overhead; one that hits after a write is a wrong answer** — both halves falsifiable. Pinned in-tree by `the_memo_is_keyed_by_facet_not_merely_cached`, `facet_zero_wire_zero_is_a_real_answer_not_an_empty_memo`, `every_legal_facet_round_trips_through_the_memo_encoding`, `handing_out_mutation_rights_invalidates_the_memo`, `the_ordered_multi_mask_lock_invalidates_too`.
- **Deferred:** nothing recorded. (The per-dataset `RowStore` table shipped here is superseded one PR later — PR #30 hoists it to a process-global `LazyLock`, on the ground that a classid is a global address; the memo's *placement* was the correction, not its answers.)
- **Docs:** `docs/abi.md` +43.
- **Gates:** as the PR claimed — **129 Rust · 277 Java** · `clippy -D warnings` clean · `fmt` clean.
- **Confidence:** high. The atomic encoding, the present bit, and all five named falsifiers are visible directly in the `registry.rs` diff (183 lines added), so the safety argument is checkable rather than asserted; the gate counts and the red-then-green disable observations are as the body claimed, not re-run here. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #28 — abi: minor 6 — ResolvedCarving, and all three followups closed (merged 2026-08-25, merge `671ea98`)

- **Added:** ABI minor 5→6 — `lgj_reduce_facet_sum_resolved` (`docs/abi.md`, +110 lines) resolving every selected row's `classid → ClassId → ClassView::cascade_shape`, requiring agreement across the population, sweeping monomorphically, and **reporting the resolved grouping back** so a caller learns what it got. `RowStore.facetSum` (the verified sibling to #26's unchecked `facetSumAs`), `FacetSum` result carrier, `Carving.ofWire` (throws on an unknown value, so an unrecognised grouping can never be read as a known one), `Downcalls`/`Engine`/`Status` wiring. 14 files, +832/−114.
- **Locked:** `Carving` is `pub type Carving = CascadeShape` — the local enum minted in #26 was **a re-mint of an existing contract type**, the parallel-object-model anti-pattern, and the PR names it as such. Only the u32 wire encoding stays local, pinned **by group count** so an upstream variant reorder cannot silently re-map it (`the_wire_mapping_is_pinned_by_group_count_not_declaration_order`). The question is asked once at the population's edge, never inside the sweep. G11 contract-import fence widened by one module (`facet`), deliberately. Upstream dependencies (`ClassView::cascade_shape` in lance-graph, `masked_strided_group_sum` in ndarray) landed FIRST per the missing-capability STOP rule — the PR body says "merge those first".
- **The fixture had to be made able to fail:** `FixtureClassView::cascade_shape` now varies by `class % 3` (cycle `G6D2, G4D3, G3D4`) rather than answering the trait's constant `G3D4` zero-fallback. A constant answer makes every population trivially homogeneous, so the resolve-to-one guard could never fire and its test would pass for an implementation that never checked. The paired half is stated in the fixture's own doc comment: classids 3 and 6 **share** a grouping and must still resolve, or the refusal degenerates to "reject every multi-class population". Empty resolves to `None`, never to a default — pinned by `an_empty_population_resolves_to_nothing_rather_than_a_default`.
- **Followup 3 closed at the right layer:** the scalar kernel #26 shipped as a named `ndarray::simd` gap is now one delegating call into `ndarray::simd::masked_strided_group_sum`; the "SCALAR, and deliberately so" doc block is deleted, not merely amended.
- **Deferred:** generalising to an op-code on ONE reduce symbol (mirroring `lgj_plan_eval`'s `LgjOpDesc`, `sum` as op-code 0) — stated with its firing condition rather than as an aspiration: **a second reduction must NOT become a second symbol.** #26's open product question ("is `sum` R8's checksum escaping the laboratory?") is answered rather than dropped: it is the only mask-**consuming** op over the register, and the cheapest that cannot be faked from outside, which is why it doubles as the parity oracle for both.
- **Docs:** `docs/abi.md` (+110) carries the new symbol and the minor-6 ledger; `.claude/board/EPIPHANIES.md` (+87) in the PR's own commit.
- **Gates (as the PR claimed):** 123 Rust · 277 Java · **7 old-ABI compat checks per historical library**, now gating minor 6 too · clippy `-D warnings` clean · fmt clean · `.so` rebuilt first · runtime reports `abi 0.6`.
- **Disable-runs:** six red-then-green on the resolver and wire mapping — ignore mixing, skip an unanswerable classid, invent a default for empty, reorder the wire mapping — plus the paired can-still-resolve halves.
- **Owned mistake:** the local `Carving` enum was committed in #26 "while writing a note about not claiming authority" — the contract's `CascadeShape` had carried the three groupings, the algebra, and the class-conditioned sentence all along. What genuinely did not exist was the `ClassView` accessor. The PR states this in its own body rather than quietly aliasing the type.
- **Confidence:** high on the diff-verified facts (the `Carving` alias, the varying fixture, the `masked_strided_group_sum` delegation, the six named resolver tests all appear in `git show`); the gate counts and the six disable-runs are cited as the body claimed them, not re-run here. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #27 — abi: close the eager-init defect at minors 2-4, not just 5 (merged 2026-08-25, merge `7479c40`)

- **Added:** one lazy `Downcalls` holder per minor — `Minor2`/`Minor3`/`Minor4`, joining #26's `Minor5` — initialised on first *access* rather than in the class initialiser. `OldAbiCompatTest` rewritten from minor-5-only and one-directional to per-minor and both-directional. 4 files, +261/−55; the Rust side untouched.
- **Reproduced against real libraries, not reasoned about:** four libraries built from this repo's own history — `bd92c58` (minor 1), `beac5de` (2), `92a0e55` (3), `e8f0ce6` (4) — with the **current** Java run against each. `SmokeTest`, which uses nothing newer than minor 1, died every time in the `Downcalls` class initialiser on the first symbol from a *later* minor: against the minor-1 library on `lgj_rowstore_open` (a minor-2 symbol), against minor 2 on `lgj_rowstore_open_with_edges` (minor 3), against minor 3 on `lgj_mask_andnot` (minor 4). **Against the minor-1 library, minor-1 operations could not run** — the additive-minor promise was not merely unenforced in that direction, it was inverted.
- **Locked:** the **14 minor-1 base handles stay eager on purpose.** A library missing any of those is not an older library, it is a wrong one, and that failure should be immediate and total. Laziness answers "this library predates the feature"; it is not a general policy. The falsifier gates each minor in whichever direction the loaded library calls for — available ⇒ the feature must actually work; absent ⇒ `AbiMismatchException` naming *that* minor, never a bare missing-symbol failure and never a failure of some other minor's feature. Both halves are required, because a gate that rejected everything would satisfy a rejection-only test.
- **Deferred:** the compat suite stays OUT of `AllTests` and skips loudly without `-Dlgj.oldlibrary` — it needs artifacts this repo does not ship; the four libraries are reproducible from the commits named above.
- **Docs:** `docs/abi.md` §2 (+35) gains the both-directions compatibility statement and the measured table — versioning is where that promise belongs; `.claude/board/EPIPHANIES.md` (+58) in the PR's own commit.
- **Gates (as the PR claimed):** 263 Java checks · **22 old-ABI compat checks across four historical libraries** — minor 1 → 4 checks, minors 2/3/4 → 6 each, all green, each reporting the right verdict for its library.
- **Disable-run, per minor rather than in aggregate:** reverting *only* minor 2 to eager while leaving 3/4/5 lazy reproduces the class-initialiser crash against the minor-1 library. A single minor regressing is caught.
- **Confidence:** high. This PR closes a gap #26 recorded and explicitly left open ("minors 2-4 share the defect and are NOT fixed here"), and the measured table is per-library evidence rather than reasoning; the diff (`Downcalls.java`, `OldAbiCompatTest.java` +110) matches the claimed shape. The gate counts and the per-minor disable-run are cited as the body claimed them, not re-run here. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #26 — abi: minor 5 — `lgj_reduce_facet_sum`, the mask path's missing execution half (merged 2026-08-25, merge `beac2fe`)

- **Added:** ABI minor 4→5, one new symbol `lgj_reduce_facet_sum` (`docs/abi.md` §14) — the first op that can *consume* a mask against the 12-byte facet register. `Carving` (Java enum, 3 readings), `RowStore.facetSumAs`, `FacetSumParityTest`, `Downcalls.Minor5` lazy holder, `Status` codes, plus the Rust kernel and export. 13 files, +1215/−9.
- **The finding came before the code:** reading the membrane first showed "wire the mask path into lgj-abi" was **half a task already done** — `lgj_op_eq_classid` has turned a classid column into a mask since minor 2, already through `ndarray::simd::eq_u32_strided_to_mask`, the same primitive R8 arm E′ measured. The gap was the execution half.
- **Six operator-review findings, all confirmed and fixed** — each sitting where no test was pointed, none falsified by the 117 Rust + 263 Java green gates: (1) **the feature gate was defeated by the class it guards** — `Abi.requireMinor(N)`'s javadoc promises to fail before any downcall is attempted, but every `Downcalls` handle is a `static final` resolved in the class initialiser; reproduced against a real ABI 0.4 `.so` built from merged `main`, where `SmokeTest` died in `<clinit>`. (2) the normative ledger contradicted itself (minor 4 / "21 symbols" / history ending at 4 while §14 already documented `-15`) — now consistent at minor 5, 22 symbols. (3) **claimed ClassView authority it cannot verify** — a mask is an opaque population and the fixture ClassView had no carving resolver, so the method was renamed `facetSumAs` and documented as a raw reinterpretation primitive whose caller owns correctness. (4) **`i64` is not closed under the reduction** — `wrapping_add` at up to `3 × (2³² − 1)` per row under quads overflows after ~715,827,882 rows ≈ 341 GiB of 512-byte rows, inside this substrate's contemplated scale; now `i128` accumulation, range-checked once, `LGJ_ERR_SUM_OVERFLOW` (`-16`), `out_sum` untouched. (5–8) four small untruths (wrong status constant in the export doc, a header still counting 20 handles, a stranded javadoc, a complexity claim ignoring the unconditional mask scan — now `O(mask_words + popcount × groups)`). (9) an overclaimed reachability reclassified rather than deleted, since `registry` allocates masks at exactly `mask_words_for(n_rows)`.
- **Deferred, and each with its successor named:** the verified shape `classid → ClassView → ResolvedCarving → (population + its carving) → sum` recorded as the next rung rather than faked — deliberately NOT a per-row consult, "that would put the entropy straight back in the loop" (closed by #28). **Minors 2–4 share the eager-init defect and are NOT fixed here** — pre-existing, own change, own falsifier (closed by #27). The kernel is **scalar, deliberately**: `ndarray::simd` had no primitive for "gather a sub-word group out of a 512-byte-strided register under a runtime grouping and widen-accumulate", and raw intrinsics would create the second SIMD surface §8 forbids — a named gap under the W1a contract, not an unexamined choice (closed by #28).
- **Left open as a product question, flagged rather than decided:** is `sum` genuinely the first product operation, or R8's checksum escaping the laboratory? R8 proved the execution *shape*; it did not prove that "sum packed rails/triplets/quads" earns permanent ABI vocabulary. If no consumer needs facet sum, the honest move is to keep the shape and drop the operation.
- **Docs:** `docs/abi.md` (+131) §14 and the corrected minor/symbol ledger; `.claude/board/EPIPHANIES.md` (+170) in the PR's own commit.
- **Gates (as the PR claimed):** 118 Rust tests · 263 Java checks · **4 old-ABI compat checks against a real 0.4 library** · clippy `-D warnings` clean · fmt clean · `.so` rebuilt first · runtime reports `abi 0.5, simd ndarray::simd avx512`.
- **Disable-runs:** nine red-then-green, including two through the membrane and one reverting the lazy holder. `FacetSumParityTest` recomputes every expected value in Java from the public per-row accessors — the two paths share no code.
- **Confidence:** high on substance, with one provenance caveat: this entry is reconstructed largely from a body that was itself rewritten after operator review ("_Updated after operator review — six findings_"), so the six findings are recorded as the body states them and the gate counts and nine disable-runs are not re-verified here; the file list, +1215/−9, and the new `Carving`/`FacetSumParityTest`/`OldAbiCompatTest` additions are diff-checked. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #25 — board: record R6–R8 and the twice-repeated measurement-ledger defect (merged 2026-08-25, merge `405a2a1`)

- **Added:** one append-only `.claude/board/EPIPHANIES.md` entry (+78) for the R4–R8 arc merged in #24, which carried an entry for R4/R5 only — plus, in a second commit on the same branch (`e8f0ce6`), a calibration of the R8 FFI claim in `valhalla-lab/reproducers/{r8_report.py, README.md, R8-observed.txt}`. 4 files, +148/−48.
- **Locked — the defect that arc produced twice.** `R7-observed.txt` shipped with prose quoting a throughput range its own pinned runs contradicted; that was caught and repaired, and **R8 committed the identical defect one commit later**. Root cause is mechanical, not attentional: prose hand-copied from run N while the raw block was regenerated at run N+1, which leaves every such artifact one regeneration away from lying about itself. Repaired structurally in #24 by generating the report from the runs it just captured.
- **The repair turned out to be the right shape, not merely a safer one:** the first regeneration moved B′ ~25 % while every structural conclusion held identically. **The stability of conclusions under unstable absolutes is itself the result**, and only a regenerable artifact can expose it — a hand-pinned one hides it.
- **Two further review findings, both real:** the toolchain was not unified across the control and the arm (1.94.1 vs 1.97.1 — an escape hatch on the "bulk FFI costs nothing" claim), and the sweep-only comparison understated the lawful mask shape, which wins **end-to-end** once population construction is counted.
- **The calibration, in the diff:** "B ≈ the standalone Rust process. One bulk FFI crossing costs nothing measurable" is replaced by "**one bulk Panama crossing shows no measurable penalty at this scale**", stated that way deliberately — one generated run had the in-JVM arm *faster* than the standalone process, which means process/JIT/turbo/cache context is larger than any crossing cost, so the two arms must not be called performance-identical even though no penalty is exposed. `r8_report.py` now **detects which of the three cases a run lands in and says so** (faster than max / slower than min / ranges overlap), so the calibration is enforced by the generator rather than restated in prose — the same structural fix the entry itself is about.
- **Docs:** the R6–R8 measured architecture recorded so a future session does not reconstruct it from commit messages — JEP 401's atomicity cause for the 8-byte cliff (and that `UseArrayFlattening`/`UseFieldFlattening` are `false` by default, so the flags-on run had to be *done* rather than assumed); 10⁹ projections at 960 B total; the five-arm entropy-boundary result with its control leg.
- **Gates:** nothing recorded — no test counts claimed, and none apply to a board-and-generator change.
- **Owned mistake, this entry's own:** the PR body says "Docs-only: one append-only entry in `.claude/board/EPIPHANIES.md`. **No code, no reproducer changes.**" The diff contradicts it — the second commit changes `r8_report.py`, `README.md`, and `R8-observed.txt`. The body was accurate when written and was not updated when the calibration commit was pushed onto the same branch; recorded here rather than smoothed over, because a PR body going stale against its own branch is the same one-regeneration-from-lying shape the entry documents.
- **Confidence:** medium-high. The recorded findings are the PR body's own account of the R6–R8 arc and are not re-derived here; what is diff-verified is the file set, the calibration text, and the three-case detection in `r8_report.py`, plus the body-vs-diff discrepancy noted above. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #24 — Valhalla/Panama layout authority + mask-native Rust execution, measured (merged 2026-08-25, merge `7165615`)

- **Added:** `valhalla-lab/reproducers/` — five reproducers (R4–R8), each with a pinned `R*-observed.txt`: `R4_CarvingVsCliff.java`, `R5_ClassidHasNoStaticSpelling.java`, `R6_WhyEightBytes.java`, `R7_BillionOpsZeroAlloc.java`, `R8_EntropyBoundary.java`, plus the R8 native side (`r8_native.rs`, `r8_standalone.rs`, the `r8-ndarray/` crate) and `r8_report.py`. A `README.md` for the reproducer set; `.gitignore` entries for the build artifacts (`.so`, standalone binary, `r8-ndarray/target/`).
- **Locked (as the PR body reports its measurements):** the 8-byte array-flattening cliff is on TOTAL payload and the V3 carvings do not dodge it — `Pair`/`Triplet`/`Quad` flat, every `Reg12*`/`Facet16*` non-flat, so **the carving is sound as SoA and only as SoA** (N parallel rail arrays, never one `Facet[]`). `isFlatArray()` alone is not a sufficient test: `Four8AsTwo8`, 32 bytes, reports flat at VM element size 8 because its components are stored as *references* — element sizes are now pinned beside every boolean. A runtime-`classid` layout requires descriptor/accessor dispatch (R5): over 65,536 rows, projecting allocates 800 B total while hydrating a 16-byte `Facet` costs 32–104 B/row **varying by run** — the spread is the finding. The cliff is JEP 401 by design, not a version gap or a flag (R6: already the Java 27 numbers, `27-jep401ea3`; forcing all five flattening flags changes nothing). 10⁹ projected operations allocate **960 B total** (R7) — 15,000× the operations for +160 B over R5. R8: five arms, same bytes, same op multiset, checksum-identical *including a standalone Rust process with no JVM*; one bulk FFI crossing costs nothing measurable (B ≈ standalone), `D > B` is falsified, and one crossing per projection is ~30× slower (~12 ns/op) — the anti-JNI rule with a number on it. Under random classid both D′ (index lists) and E′ (`ndarray::simd` masks) recover ~4.8×, and counting the population BUILD moves break-even from ~120 passes to ~10, leaving a reusable mask where D′ leaves a materialized population the mask-native law forbids as currency. The win is specialization **placement**, not "Java beats Rust".
- **Deferred:** nothing recorded — the PR body states it touches neither `native/lgj-abi`, the Java API, nor the membrane; it is a measurement lab plus board/doc corrections.
- **Docs:** `.claude/board/EPIPHANIES.md` (+73), `AGENT_LOG.md` (+85), `TECH_DEBT.md` (+95); `java/README.md` `[restricted]`-warning count corrected six → **seven** (the board had said seven since 2026-08-17; this file was the only stale copy).
- **Gates:** as claimed in the body — all Rust arms built with `rustc 1.97.1`, `-O -Ctarget-cpu=x86-64-v4 -Cdebuginfo=0` (toolchain unified; native kernels and the standalone baseline had been 1.94.1 while the ndarray crate required 1.97.1, an escape hatch on the "bulk FFI == standalone" claim). Arm E consumes SIMD ONLY through `ndarray::simd` (`eq_u32_strided_to_mask`, `mask_or`) per `abi.md` §8. Arm E asserts its mask popcounts equal the partition scan's counts, so the two population representations are the same set.
- **Owned mistake:** R7's prose quoted a throughput range its own pinned runs contradicted (throughput is now explicitly **not banked**, only the exact 960 B), and **R8's report drifted the same way one commit after the R7 fix** — prose hand-copied from run N, raw block regenerated at run N+1. Repaired structurally rather than by hand: `r8_report.py` now derives the prose from the captured runs, so they cannot disagree. One regeneration saw B′ move ~25% while every structural conclusion held identically. Also recorded: R4/R5 code landed without its board entry — a same-commit-rule break, recorded rather than back-dated, in `E-LGJ-LAYOUT-AUTHORITY-IS-TRANSFERABLE-BUT-ONLY-ABOVE-8-BYTES-1`.
- **Confidence:** medium-high. The reproducers and their pinned `R*-observed.txt` are in-tree and the generated-report discipline is structural, but this entry is reconstructed from the PR body and diffstat — the numbers are as the body claimed, not re-verified here, and this is a real merge commit (two parents), not a squash. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #23 — W8b: mask-native Java facade + Graph migration (merged 2026-08-18, squash `3f927c7`)

- **Added:** the D-LGJ-W8 FACADE + GRAPH MIGRATION rungs (spec `.claude/plans/mask-native-navigation-correction-v1.md` §3.5–§3.7) on top of PR-W8a's substrate. Facade: `WideFieldMask` (record; validated `ofFacets`, zero-extending `ofMatchBits`), `RowStore.hop(int, WideFieldMask, Mask)` + `hop(int, Mask)`, `RowStore.importRows(long...)` — the ONE named import exception — `Mask.minus(Mask)`, `Mask.materializeRows()` — the ONE named materialiser — `Status −14`, `Downcalls` 20 handles / 21 symbols + `requireMinor(4)`, `Engine.LaneWindow.setU64` (the first write accessor, used only by `importRows`). Graph migration (`consumers/graph/`): a native `Mask` frontier with zero `long[]`/`Collection` fields, `from(Mask)` new, `minus(long...)` REMOVED, `rows()` → `materializeRows()`, a real `close()`. New test `MaskNativeOpsTest`; `GraphHopTest` rewritten with a reflective 3-way allowlist (fields / parameters / returns) over `Graph` AND `Edge`, the vacuous literal-true assert deleted, a G9 flagship `from(maskOfFacetClass(...)).hop(...).count()` (seed=133 → hop → 240, zero row-id values outside the independent oracle), and a G3 allocation gate (384-byte floor, flat across 10-vs-500-row frontiers).
- **Locked:** zero FFM types in any public signature; the two named exceptions are the only doors in and out of row-id space.
- **Measured, then pinned (two predictions were wrong, in the direction the worker brief's own reasoning flagged):** crossing constants pinned from measurement (ABI 0.4, release `.so`, JDK 26.0.2) — `hop` predicted 1, **measured 2** (`createMask` + `lgj_hop`); `importRows` predicted 1, **measured 2** (`createMask` + `describeMask`, per-row word writes in-process — 3-vs-29-row cost identical); `count` / `minus` / materialize-first unchanged at 1 / 2 / 1. Three stale "one native crossing" javadoc claims corrected at the source (`maskOfFacetClass`, `hop`, `importRows`).
- **Deferred:** nothing recorded.
- **Docs:** `.claude/board/{ISSUES,LATEST_STATE,STATUS_BOARD}.md` and the wave/plan supersession notes (`.claude/plans/consumer-graph-traversal-v1.md`, `.claude/waves/wave-consumer-graph.md`) appended per spec §3.7 — append-only, originals preserved, all in the PR's own commit.
- **Gates:** the `.so` was rebuilt FIRST — and the root-level copy was found STALE (pre-minor-4), the exact eager-clinit trap the repo `CLAUDE.md` names; refreshed before any suite ran. `javac -Xlint:all`: 7 pre-existing `[restricted]` warnings, 0 new. **AllTests 245 ✓ (incl. the new MaskNativeOpsTest 41 + ApiSurfaceTest) + GraphHopTest 66 ✓ + TradesParity 12 ✓ + TradesAllocation 3 ✓ + BricksAuth 62 ✓ = 388 checks.**
- **Disable-run, red-then-green:** an injected `public long[] rows()` fired exactly the G1/G8 allowlist check; restore → 66/66.
- **Confidence:** high — the crossing table is measurement-corrected prediction with attribution read from source, and the allowlist has a red-then-green disable behind it; the gate counts are as the PR body claimed, not re-run here. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #22 — D-LGJ-W8 PR-W8a: contract dep + FixtureClassView + `lgj_mask_andnot` + `lgj_hop` (ABI minor 4) (merged 2026-08-18, squash `9019417`)

- **Added:** the substrate half of the council-ratified mask-native correction (spec v3 §3.2–3.4, frozen in #20; PR-N = ndarray #280, merged first per the wave order). A `lance-graph-contract` path dep (`default-features = false`) — the RULING's contract inheritance, the law without the engine, with the G11 import fence honored (`class_view` / `canonical_node` / `ontology` only). New `native/lgj-abi/src/class_view_provider.rs`: `FixtureClassView`, the late-bound law provider (32 `FieldRef`s via `OnceLock`), the named seam fns `edge_participation` / `decode_mode`, and `class_id_for` as the pinned u32→u16 boundary. `lgj_mask_andnot` in `exports.rs`: dedup-before-lock aliasing discipline as a **5-branch tree** — ANDNOT is non-commutative, so `dst==b` needs a scratch copy of `a`, a real structural divergence from `mask_binop`, documented — with the kernel via `ndarray::simd::{mask_andnot, mask_andnot_assign}` plus an explicit repairing tail-clear. `lgj_hop`: mode fence first (≠0 → new status **−14**, dst provably untouched); src snapshot under a read lock released before the dst write lock (aliasing deadlock-free by construction, council S3-4); composition kernel reusing `eq_u32_strided_to_mask` for classid-match, scalar decode+scatter only, u64 bounds check BEFORE the cast per S3-6.
- **Locked:** ABI minor 3→4; `abi.md` §13 added, symbol counts 19→21, and §12's Java-layer hop composition regraded ⊘ SUPERSEDED in place.
- **Deferred:** nothing recorded — W8b (Java facade + Graph migration) named as the follower, and it landed as #23.
- **Docs:** `docs/abi.md` (+163) and the board artifacts in the same commit per §3.10's gate column — STATUS_BOARD SUBSTRATE flip, LATEST_STATE entry, ISSUES `ISS-LGJ-CLASSID-WIDTH-PIN` width-pin entry.
- **Gates:** `cargo test` **110/110** · clippy `-D warnings` clean · fmt clean · release `.so` exports **21/21** `lgj_` symbols (`nm -D`).
- **Disable-runs, G6(a)–(e), all red-then-green:** (a) decode offset +4 → exactly the 10/19/29 fixture-parity test red; (b) participation forced EMPTY → parity + provider test red; (c) tail-clear removed → the corrupted-operand repair test red; (d) mode fence bypassed → reserved-mode test red; (e) **`ptr_eq` dedup bypassed → the aliasing test DEADLOCKS** (60 s timeout kill) — the S3-4 deadlock is real and the discipline load-bearing.
- **Confidence:** high — five disable-runs, one of which reproduces a genuine deadlock rather than a failed assertion, and the symbol count is checkable against the built `.so`; the numbers are as the PR body claimed, not re-run here. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #20 — D-LGJ-W8 A3 freeze (PR-0): ratified correction spec v3 + root CLAUDE.md + board storno (merged 2026-08-18, squash `c479f76`)

- **Added:** `.claude/plans/mask-native-navigation-correction-v1.md`
  (SPEC v3, council-RATIFIED — Part I: the mask-native navigation
  correction; Part II: the 64K parallel-SoA compute / deterministic
  placement / batch-publication architecture, audited at file:line
  across the lance-graph spine; full change ledger v1→v2→v2.1→v3);
  root `CLAUDE.md` (CREATED — the repo's first root policy guard:
  mask-native invariant covering query AND compute verbs, named
  import/materialise exceptions, three-axes model, the GridLake hard
  gate, missing-capability STOP rule, no model-policy section by
  explicit rule); `EPIPHANIES.md` storno
  `E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1`; `STATUS_BOARD.md`
  D-LGJ-W8 row (gate ladder AUDIT✓→SPEC✓→COUNCIL✓→FREEZE=this PR);
  `LATEST_STATE.md` correction-of-record.
- **Locked:** the operator rulings of 2026-08-18 (CORRECTION WAVE +
  RULING CLARIFICATION + A1 ARCHITECTURE RULING) as frozen decisions;
  the three/four currencies; PR #18 demoted to scalar reference oracle
  (NOT destroyed — both BFS transcriptions preserved verbatim); the
  landing-key REQUIRED PROPERTIES (never an algorithm) with the
  generic-parallel-write hard gate; W8 = mask correction only, GridLake
  specified-not-implemented.
- **Council record:** 5 savants (44 findings — incl. the
  lock_masks_ordered aliasing DEADLOCK trap, the W1a free-fn deviation
  surfaced not smuggled) + operator A2 verdict + 3 adversarial
  reviewers: 1 BLOCK(P0) resolved (model identifiers de-named to roles
  in the committed spec; the repo's pre-existing board occurrences
  flagged to the operator, deliberately NOT swept), 6 FIX(P1) applied
  (§8.4 evidence re-scoping — temporal.rs's objection is restart-only,
  answered by the durable position_base cursor; the surviving
  within-cycle arrival leak argued on its own evidence; §9 axis split
  filing the leak on PUBLICATION; F-LAND scoped to the placement leg;
  G2 respecified as a committed call-sites-only check; G11
  contract-import fence; same-commit board artifacts in W8a/W8b gate
  columns), ~20 FIX(P2). One reviewer-vs-savant factual conflict
  settled by reading the file (Graph.java storno anchor = :18-19; the
  S3-1 "correction" to :19-20 was itself wrong — stornoed in the
  ledger).
- **Deferred:** ALL implementation (PR-N ndarray → PR-W8a substrate →
  PR-W8b facade/migration, per spec §3.10); the GridLake/compute wave
  (hard-gated); decode modes 1..=3 (RESERVED, −14, promotion trigger
  named); Wide-tier WideFieldMask promotion (NG12); fused multi-hop
  traversal (NG10); mask reuse/pooling (NG11); the 125/233 ms
  reproduction/receipt PR (operator-measured priors, receipt
  out-of-tree — provenance split pinned in spec §8.3).
- **Docs:** the spec is self-contained; root CLAUDE.md points at the
  enforcing artifacts.
- **Confidence:** High — no code changed; every normative claim in the
  frozen text carries a council-verified file:line or an explicit
  grade/seam.

## PR #18 — consumers/graph: traversal facade + falsifiers (merged 2026-08-18, squash `5d3e694`)

- **Added:** `consumers/graph/` — the third and final planned consumer
  example. `Graph`/`Edge` (2 Sonnet workers, G1): immutable chaining over
  a plain `long[]` row-index frontier (no native `Mask` has a public
  constructor from Java-computed rows — checked, ruled a documented
  simplification, not a fourth substrate detour). `GraphHopTest` (G2):
  hop correctness via two independently-written pure-Java BFS
  transcriptions, crossings-∝-hops, anti-vacuity, zero-serialization,
  `Edge`'s reflection guard.
- **Locked:** dispatch happened only after the substrate was proven
  complete at all three levels (#14/#15 ABI, #16/#17 public facade) —
  the wave was checked three separate times before a real, load-bearing
  gap stopped surfacing.
- **A measured finding, caught and fixed before shipping:** G2's first
  draft assumed every hop costs an identical crossing count. Measured
  with a standalone 4-hop probe: hop 1 on a fresh store costs 2
  (`facetMatches` + a one-time `RowStore.rawLane()` resolution), hops
  2/3/4 cost exactly 1, steady-state — the setup cost is per-`RowStore`,
  not per-hop. Corrected `Graph.hop()`'s javadoc and the test's
  assertions to state this precisely across three consecutive hops with
  three different source-row counts.
- **Docs:** STATUS_BOARD D-LGJ-W5 (graph row, marking all three planned
  consumer examples DONE); LATEST_STATE dated entry; EPIPHANIES entry;
  wave file marked DONE — all landed in the PR's own commit.
- **Confidence:** High — `GraphHopTest` 43/43, core suite 204/204
  unaffected, trades (12+3) and bricks (62) unaffected, disable-run
  (target-decode offset corrupted by +4) verified red-then-green via the
  set-equality check. Codex hit usage limits, no review obtained.

## PR #16 — RowStore: public per-row payload accessors (merged 2026-08-18, squash `8e4f9aa`)

- **Added:** `RowStore.classidAt`/`payloadLow64At`/`payloadHi32At` — three
  new public, primitive-returning methods reusing `lgj_lane_describe`
  (already ABI minor 1, no new ABI surface), resolved once per store and
  cached; every read after is in-process.
- **Locked:** the third and final gap in the graph-consumer wave's
  substrate chain — `facetMatches` gives WHICH facets matched, never their
  payload; the only raw-byte reader (`internal.ffm.Engine.describeLane`)
  is off-limits to a consumer package by `ApiSurfaceTest`'s own design.
  Decision D1a's text assumed a capability that existed only internally.
- **A measured self-correction, recorded rather than smoothed over:** the
  first draft carried the closed-store guard in two places; disabling one
  was masked by the other via Java's receiver-before-argument evaluation
  order, producing a **false-negative disable-run (30/30 green under
  broken code)**. Traced rather than accepted, de-duplicated to the one
  correct location, re-verified genuinely red-then-green.
- **Deferred:** G1 (traversal facade) + G2 (falsifier tests) — the
  substrate is now proven at all three levels (Rust generator, ABI
  membrane, public core facade); dispatch is next.
- **Docs:** STATUS_BOARD D-LGJ-W7; LATEST_STATE dated entry; EPIPHANIES
  entry recording both the gap and the self-caught vacuous falsifier — all
  landed in the PR's own commit.
- **Confidence:** High — `AllTests` 204/204 (+10), `ApiSurfaceTest`
  unchanged at 3/3 (zero FFM type in any public signature), both
  disable-runs verified (one only after the redundant-guard correction
  above). Both bot reviewers at usage limits, no review obtained.

## PR #14 — lgj-abi: edge-bearing row store ABI addition (merged 2026-08-18, squash `be8fb60`)

- **Added:** `lgj_rowstore_open_with_edges` (`docs/abi.md` §12, ABI minor
  2→3) — mirrors `lgj_rowstore_open` symbol-for-symbol (same
  `LGJ_RESOURCE_ROWSTORE` kind, same lane shape, no new mask op, purely an
  alternative constructor). `registry::open_rowstore_with_edges` →
  `exports::lgj_rowstore_open_with_edges` → `Downcalls.rowstoreOpenWithEdges`
  → `Engine.openRowStoreWithEdges` (`Abi.requireMinor(3)`) →
  `RowStore.openWithEdges`. A Java-side transcription of the D1a hop
  mechanism (facet-match crossing + raw lane-0 payload decode, zero new ABI
  op) added to `RowStoreParityTest`.
- **Locked:** the graph-consumer wave's own D1b rule ("a new ABI symbol
  must go through the substrate wave process FIRST as its own W-tier PR")
  applied to itself — a row-store *constructor* needing ABI growth is the
  same shape of gap as a new hop op, and gets the same treatment: orchestrator
  work, never a consumer worker's ad hoc addition. Found before any G1/G2
  worker spawned, not discovered mid-dispatch.
- **A measured cross-language result, the strongest new evidence:** the
  Java hop transcription, run at the exact parameters already pinned as a
  Rust regression (`n=2000, seed=0xF00D_CAFE, edge_classid=0, gate_mask=0x0,
  radius=25`), reproduces the identical hop counts **to the row** (10-row
  seed → 19 at 1 hop → 29 at 2 hops) — proving the membrane carries the
  same edge *structure*, not merely the same classid stream. Two
  disable-runs, both red-then-green: a classid-not-threaded bug at the
  registry level (caught by the out-of-range-parity test); the Java hop's
  classid-match condition forced to always skip (1-hop/2-hop collapsed to
  0, anti-vacuity assertion caught it).
- **A real self-inflicted false alarm, recorded so it isn't re-diagnosed:**
  the stale top-level `target/release/liblgj_abi.so` (minor 2, pre-dating
  this pass) made every Java suite fail at class-init — `Downcalls` eagerly
  resolves every method handle including the new one — until rebuilt with
  `CARGO_TARGET_DIR=$ROOT/target cargo build --release` per the documented
  convention. Not a substrate defect.
- **Deferred:** the graph wave's G1 (traversal facade) + G2 (falsifier
  tests) — substrate is now proven at both the Rust generator level and the
  Java membrane level; dispatch is the next action, not yet executed.
- **Docs:** STATUS_BOARD D-LGJ-W6; LATEST_STATE dated entry; EPIPHANIES
  entry recording the gap and its fix — all landed in the PR's own commit.
- **Confidence:** High — `cargo test` 93/93, clippy/fmt clean, `nm -D`
  confirms the exported symbol; Java `AllTests` 194/194; both disable-runs
  verified. Both bot reviewers (Cursor Bugbot, Codex) hit usage limits, no
  review obtained.

## PR #13 — rowstore: `generate_with_edges` — the graph-consumer wave's measured blocker, cleared (merged 2026-08-18, squash `beac5de`)

- **Added:** `RowStore::generate_with_edges(n_rows, seed, edge_classid, edge_gate_mask, edge_radius)` in `native/lgj-abi/src/rowstore.rs` (+265) — additive, `generate()` untouched. Classid assignment is byte-identical to `generate()` (same SplitMix64 draws, same `(a >> 33) & 0xF` formula); bits 37..64 of the same `a` draw become an independent sparsity gate, so `edge_classid = 16` (outside the `0..16` range) makes the gate structurally unreachable and reproduces `generate()` exactly — **pinned by test, not just argued**. A sparse, gated subset of `edge_classid`-matching facets get a bounded local-neighbourhood target row instead of raw noise, which is what keeps a 1–2 hop BFS non-vacuous. New `native/lgj-abi/examples/graph_density_probe.rs` (+85).
- **Locked:** the blocker was a DATA-SHAPE problem, not a mechanism problem, and it was caught before any workers spawned. The wave was nearly dispatched on the strength of Decision D1a's mechanism (writable masks, existing facet-match) being sufficient — claimed twice in earlier turns — before `wave-consumer-graph.md`'s own STOP condition was re-read **in full**: plain `generate()`'s payload is uniform random noise, so a decoded 1–2 hop BFS over it saturates to nearly every row regardless of decode convention, vacuous under the wave's own falsifier #4 ("seed / 1-hop / 2-hop must be three different, non-empty, non-total sizes").
- **Measured, not guessed:** the probe swept `(gate_mask, radius)` before any parameter was chosen. The first pass at `n_rows=1000` was too small (avg degree < 1, everything collapsed to zero); widened to `n_rows=20_000` for real signal (`gate_mask=0x0, radius=25`: seed=20 → 1-hop=30 → 2-hop=40), then re-measured at a test-suite-sized `n_rows=2000` for the pinned regression: **`seed=10 → 1-hop=19 → 2-hop=29`**, pinned as `measured_hop_counts_are_three_distinct_non_empty_non_total_sizes`.
- **Deferred:** the consumer itself. Scoped to the substrate-tier generator only, per the wave file's own rule that a generator extension is not a consumer hack — "the graph consumer is dispatchable", not dispatched in this pass.
- **Docs:** `.claude/board/{EPIPHANIES,LATEST_STATE,PR_ARC_INVENTORY}.md` in the same commit; `wave-consumer-graph.md` updated in place (STOP condition marked RESOLVED with the measured numbers, and its stale "calcify, do not dispatch" header corrected — that gate had already been lifted session-wide when W5a/W5b shipped under identical wording); `.claude/plans/lgj-soa-substrate-v1.md` and the ghidra / ogar-machine wave files touched.
- **Gates:** `lgj-abi` **90/90** (was 84, +6 new tests), fmt clean, clippy `--all-targets --all-features` clean.
- **Disable-runs, red-then-green:** dropping `rem_euclid` from the target-formula wrap → exactly the 3 target-formula tests red (transcription, in-bounds/radius, pinned regression), the other 7 green; firing the sparsity gate on classid match alone → only the transcription test red. The second result is stated precisely rather than treated as a miss: the in-bounds/radius invariant correctly stayed green, because geometry validity is orthogonal to *which* facets get the treatment — verified as the right outcome rather than a vacuous test before moving on.
- **Confidence:** high on the mechanism and the disable table, which reads as genuinely two-sided; note the squash's own commit message body is the mission-plan text from the branch's first commit rather than this PR's scope, so the entry is written from the PR body and the diffstat, and the gate counts are as the body claimed, not re-run here. Backfilled 2026-08-27 from the PR body and diff, not written at merge time.

## PR #12 — consumer example: Bricks, mask-first authorization (merged 2026-08-17, squash `8f16e8d`)

- **Added:** `consumers/bricks/` — the second consumer proof over the
  unchanged core. `Bricks`/`BricksSession`/`BricksQuery`/`Role`/`Orders`/
  `UnauthorizedQueryException`; `BricksAuthTest` 62/62. Wave
  `wave-consumer-bricks.md`, 2 Sonnet workers (K1 main / K2 test,
  disjoint), orchestrator-gated.
- **Locked:** authorization is a **predicate in the same lazy chain** as
  `where(...)`, composed before execution and fused into the same single
  crossing — not a post-filter. `Role.EU_ONLY` = `REGION.eq(EU)`;
  `DENY_ALL` = `REGION.eq(0xFFFF)`, a real impossible predicate that pays
  a real crossing and counts 0; fail-closed throws BEFORE any crossing
  (no default-allow path exists); aggregate-only egress is **structural**
  (`BricksQuery`/`long`/`Map` are the only public return types — asserted
  by reflection, so no row-shaped type can be added without breaking the
  test). Disable-run: `requireAuthorized` short-circuited → exactly the
  3 can-fire fail-closed checks red, 59 green; restored 62/62.
- **A measured correction, recorded rather than smoothed over:** a **sum
  terminal costs 2 crossings** (plan eval into the mask +
  `lgj_reduce_sum_i32`), unlike `count()`'s 1 — so `sumBy()` is **32
  crossings (16 groups × 2), IDENTICAL at 1K and 64K rows**. K1's Javadoc
  claimed one crossing per group; the measurement corrected the doc. The
  thesis assertion is the shape (crossings ∝ groups, never rows), which
  is why the same literal is pinned at both row counts.
- **Deferred:** a native grouped-aggregate kernel (one crossing, 16
  buckets) — named in the Javadoc as the W6-tier follow-up, deliberately
  not built because nothing has measured a need; W5c graph (shelved on
  the D1 ruling + the edge-bearing generator substrate change).
- **Docs:** STATUS_BOARD D-LGJ-W5 → bricks DONE; LATEST_STATE dispatch-4
  entry, which also owns that W5a (#11) shipped without one.
- **Confidence:** High — zero new membrane surface, zero core changes,
  core suite unaffected at 188/188; every falsifier disable-verified.
  Both bot reviewers at usage limits (no human or bot review obtained).

## PR #11 — consumer example: World/Trades (merged 2026-08-17, squash `db7bdf1`)

- **Added:** `consumers/trades/` — own compile unit, core consumed as a
  third party would. `Trade` (schema-not-entity, reflection-proven),
  `World.open` → the existing lazy `View` under domain names;
  `TradesParityTest` 12/12, `TradesAllocationTest` 3/3.
- **Locked:** the poster's number, measured — **240 bytes allocated per
  query, IDENTICAL at 64K and 1M rows** (row-count independence is the
  thesis assertion); 0 crossings composing / 1 at terminal, through the
  domain vocabulary; the membrane's own `LANE_KIND_MISMATCH` catches a
  misbound schema (disable-run green-red-green). Zero new membrane
  surface, zero core changes — the consumer iron rule held on its first
  real test.
- **Deferred:** QUANTITY (honestly absent — arrives with ClassView/W6);
  bricks + graph consumer waves (still shelved).
- **Docs:** STATUS_BOARD W5 row → trades DONE.
- **Confidence:** High — every falsifier two-sided or anti-vacuity
  guarded; no `java/` file changed. Bot reviewers at usage limits.

## PR #10 — third parity read path + R2IL handoff boundary (merged 2026-08-17, squash `4114c4e`)

- **Added:** `RowStoreParityTest` section reading all 32,000 classids of a
  1000-row store DIRECTLY from the raw lane-0 segment through
  `Layouts.ROW_LAYOUT.byteOffset(...)` — proving the LAYOUT's carving
  against the generator, and giving `ROW_LAYOUT` its first real consumer
  (it had been defined and size-checked but read by nothing). AllTests
  185 → 188.
- **Locked:** three independent routes to the same numbers (native
  kernels / generator transcription / structured segment read); the raw
  lane's own description pinned (n*512 bytes, contiguous). The R2IL
  handoff boundary recorded in `ghidra-integration-v1.md`: r2sleigh/ruff
  integration arrives from ANOTHER session — lift-candidate C and the
  r2dec direction are FROZEN here until it lands.
- **Deferred:** everything the handoff covers, deliberately.
- **Docs:** the plan's handoff section IS the record.
- **Confidence:** High — closes a gap the W3 worker itself flagged rather
  than one discovered by accident. Bot reviewers at usage limits.

## PR #9 — bench Component F: the boundary on the real layout (merged 2026-08-17, squash `b28bd34`)

- **Added:** `F_RowStoreFacetScan` + `RowStoreData` + two `Kernels`
  facet-match arms (Java mirrors of the Rust chunk algorithm,
  line-for-line traceable); `summarise.sh` F-table generator (old "E/F"
  section retitled "E" — real naming collision); `RESULTS.md` §F;
  `execution-boundary.md` re-measured paragraph; `RowStore.handle()`
  (package-private, bench bridge only); the r2sleigh plan addendum
  (lift candidate C + decompiler candidate, `libsla-sys` FFI fact pinned).
- **Locked:** the W4 finding — Component C's direction survives on the
  512-byte-row layout, its margin collapses: Vector API wins the 32-facet
  strided scan 2.51×/1.92×/1.14× (4K/65K/1M rows) vs C's 56×; all arms
  converge on memory bandwidth at 512 MiB. Cross-check-before-timing held
  at every row count. Native arm's per-call allocation asymmetry
  disclosed; `facetMatchesInto` named as follow-up.
- **Deferred:** consumer waves (operator-gated shelf); W6 (named).
- **Docs:** RESULTS/TABLES regenerated mechanically from the merged CSV.
- **Confidence:** High — real JMH, 9/9 combos, cross-checks green, main
  suite re-verified 185/185 against the fresh minor-2 `.so`. Bot
  reviewers at usage limits, did not run.

## PR #8 — Java RowStore facade: W3 shipped (merged 2026-08-17, squash `320808d`)

- **Added:** `RowStore`/`FacetMatchView`/`FacetId`/`NativeResource` public
  API; `Mask.source()` retyped `NativePattern → NativeResource`;
  `RowStoreParityTest`/`RowStoreLifetimeTest` (53 new checks).
- **Locked:** the wave-dispatch system works end to end — 3 disjoint
  Sonnet workers, zero merge conflicts, mutually consistent signatures
  with no coordination beyond the frozen briefs
  (`E-LGJ-WAVE-DISPATCH-VALIDATED-1`).
- **Deferred:** W4 (bench Component F) — the wave file's second dispatch.
- **Docs:** `STATUS_BOARD` D-LGJ-W3 DONE; `LATEST_STATE`; EPIPHANIES entry
  incl. an orchestrator-side false alarm (guessed env var name instead
  of reading `Abi.java`'s `ENV_LIBRARY` constant) recorded so it isn't
  repeated.
- **Confidence:** High — 185/185 (was 132), 0 new lint warnings, one real
  bug (`FacetMatchView.rowCount()` missing its closed-store guard) caught
  by the mandated tests and fixed before merge, both disable-runs
  red-then-green with the exact predicted blast radius. Bot reviewers at
  usage limits, did not run.

## PR #7 — waves calcified: dispatch maps for every plan (merged 2026-08-17, squash `68f7add`)

- **Added:** `.claude/waves/` — README (standing rules + verbatim worker
  preamble) + six dispatchable maps (substrate W3+W4 READY; three
  consumer waves DO-NOT-DISPATCH; Ghidra G1+G2 shelved; OGAR-Machine
  P-M1 BLOCKED behind a 4-condition gate incl. explicit operator go);
  `.claude/plans/ghidra-integration-v1.md` (G0 archaeology from the real
  clone: 74 P-code opcodes, `PcodeEmulator` as reference oracle, Toy as
  minimal lift target).
- **Locked:** the calcify-then-dispatch rhythm + the eight
  muscle-memory rules (`E-LGJ-CALCIFY-THEN-DISPATCH-1`); the op-set
  halt-loudly discipline; the D1a/D1b hop fork with ruling guidance.
- **Deferred:** ALL execution, by operator ruling — nothing dispatched,
  no code changed.
- **Docs:** the PR is docs; mapping-time catches recorded (graph
  consumer needs an edge-bearing generator arm — substrate change,
  flagged before it could burn a dispatch).
- **Confidence:** High for the maps (grounded in shipped code + real
  clone archaeology); execution confidence deliberately unclaimed until
  the waves run. Bot reviewers at usage limits, did not run.

## PR #6 — layout-bridge assessment, OGAR Machine plan, hydrate note (merged 2026-08-17, squash `8954e53`)

- **Added:** `.claude/knowledge/prior-art-and-the-layout-bridge-claim.md`
  (the first archived ChatGPT context assessed once: convergent
  confirmation; kept the callability-vs-shared-executable-layout
  positioning + the schema-key extractable + the baseline-dependent
  claims discipline; pinned its three errors so it is never cited
  naively); `.claude/plans/ogar-machine-v1.md` (the second context — a
  genuinely NEW workload: population emulation, one row = one machine
  state, Ghidra P-code as guest ISA, differential migration testing;
  EXPLORATORY, gated on W3 + a W5 example + archaeology + probe P-M1).
- **Locked:** W6 named in the substrate plan (schema/classid field on the
  descriptors when ClassView lands); lance-graph #957's
  `lance-graph-hydrate` recorded as the INHERITED hydration path — never
  re-derived here (#958 is its hardening fast-follow, owned elsewhere).
- **Deferred:** everything in ogar-machine-v1 (named, not scheduled).
- **Docs:** the PR IS docs; `AdaWorldAPI/ghidra` attached + shallow-cloned
  at `/workspace/ghidra` for the future P-code archaeology.
- **Confidence:** High for the assessments (checked against shipped code
  and measurements); the OGAR Machine plan is explicitly exploratory.
  Both bot reviewers hit usage limits and did not run.

## PR #5 — SoA row store: 512B rows, 32 facet lanes, ABI minor 2 (merged 2026-08-17, squash `78aa60e`)

Companion: **AdaWorldAPI/ndarray#279** (W1), merged first — `lgj-abi`'s
`kernels.rs` calls `iter_u32x16` / `eq_u32_strided_to_mask` from it.

- **Added:** `native/lgj-abi/src/rowstore.rs` (one `Arc<[u8]>`, two
  readings, zero copies, normative SplitMix64 generator);
  `LGJ_RESOURCE_ROWSTORE` + `lgj_rowstore_open` + `lgj_op_eq_classid` +
  `lgj_row_facet_match`; `docs/abi.md` §11; the W1–W5 wave plan and three
  consumer-example plans; `.claude/knowledge/soa-row-store-layout.md`.
- **Locked:** the 512 B / 32 × (4 B classid + 12 B payload) layout as
  substrate truth (Java's view may differ); facet lanes ride the
  **unchanged** `LgjLaneDesc`; masks parent onto row stores so the whole
  existing mask algebra applies with no new surface; `byte_len` is the
  exact covered span `(len-1)*stride + elem_bytes` (the old `len*stride`
  form would have let Java bound a segment past the allocation's end);
  ABI minor 1→2 and the §1/§7 symbol count corrected 14→18 (`nm -D`).
  Doctrine: `E-LGJ-THE-MIDDLE-TIER-IS-DELETED-NOT-WRAPPED-1`;
  self-correction: `E-LGJ-THE-FLAT-FIXTURE-WAS-SCAFFOLDING-NOT-THE-TARGET-1`.
- **Deferred:** `align(64)` base (stated honestly — arrives with real
  `NodeRow`); payload semantics (a ClassView concern one layer up); fused
  plans over facet lanes (W6, only if measurement asks).
- **Docs:** `abi.md` §11 + 4 plan files + 1 knowledge doc + full board.
- **Confidence:** High — 84/84, clippy/fmt clean, 18/18 symbols; both new
  kernels parity-checked against independent scalar references over
  10 row counts × 2 seeds × 4 facets × 4 needles and cross-checked a third
  way; two-sided payload-vs-classid falsifier. Both bot reviewers (cursor,
  codex) hit usage limits and did not run.

## PR #4 — Phase I synthesis docs + fusion re-run + board hygiene (merged 2026-08-17, squash `bd92c58`)

- **Added:** `docs/{architecture,panama,valhalla-lab,execution-boundary}.md`
  (D-LGJ-I DONE — synthesis, each claim tied to its proving artifact);
  the fusion-sweep 256-row re-run (`RESULTS.md` rewritten from
  `jmh-results-merged.csv`, `TABLES.md` mechanically generated from the
  same file); refreshed Valhalla lab result files (findings unchanged).
- **Locked:** the fusion self-correction — "fused ≈ unfused" was true
  only at 65,536 rows; at 256 rows × 8 predicates unfused/fused = 2.99×.
  The `MultiLaneColumn` decision
  (`E-LGJ-SIMD-SOA-IS-FOR-THE-ROW-STORE-NOT-THE-FLAT-LANES-1`): declined
  for flat lanes, earmarked for the 512-byte row-store slice; operator
  layout reference (64K × 512 B, 32 × (4 B classid + 12 B)) recorded.
- **Deferred:** `NodeRow`/`WideFieldMask` wiring (unchanged);
  `MultiLaneColumn` adoption gated on that slice.
- **Docs:** the four docs ARE the deliverable; board updated in-commit,
  incl. this file's #1-#3 backfill (lapse owned above).
- **Confidence:** High — docs-only + measured data; both bot reviewers
  (cursor, codex) hit usage limits and did not run.

## PR #3 — Vector API bench: real JMH, cross-checked (merged 2026-08-17, squash)

- **Added:** `bench/` — real JMH 1.37 suite (Components A/B/C/E:
  downcall overhead, segment access, execution boundary sweep 64→4.2M
  rows, fusion/planning), `Data.crossCheck()` gating every fork,
  `summarise.sh` mechanical table generator, `RESULTS.md`, raw
  run logs + CSV.
- **Locked:** the headline finding — Java Vector API zero-copy on the
  native segment beats the native crossing at every row count tested
  (56.4× → 1.33×); native beats Java *scalar* only past ~4K-16K rows.
  Recorded as `E-LGJ-VECTOR-API-BEATS-THE-CROSSING-1`.
- **Deferred:** fusion sweep ran at 65,536 rows only (repaid post-merge
  by the E_ re-run with a 256-row arm — see PR #4).
- **Docs:** `bench/README.md`, board updates.
- **Confidence:** High — 50/50 rows, 0 failures, two independent
  computations of the same CSV agree.

## PR #2 — Valhalla lab: three-truths, causal isolation, 3 reproducers (merged 2026-08-17, squash)

- **Added:** `valhalla-lab/` — shared/stable/valhalla trees, self-verifying
  `run.sh` (vocab-diff honesty gate + flattening-flag causal isolation),
  `docs/three-truths.md`, reproducers R1/R2/R3 with observed outputs.
- **Locked:** the 8-byte array-flattening cliff (R2, VM-confirmed);
  native-one-crossing beats hydration ~38-57× on BOTH JDKs; production
  API adopts zero Valhalla-only mechanisms — migration stays
  `record` → `value record`, one word per type.
- **Deferred:** nothing; the lab is complete for this vocabulary.
- **Docs:** lab README + three-truths; board updates.
- **Confidence:** High — one real defect (`Class::isValue()` not on
  JDK 26) found by compile failure and fixed before landing.

## PR #1 — Core vertical slice: ABI contract, native crate, Java facade (merged 2026-08-17, squash)

- **Added:** `docs/abi.md` (normative, 14 symbols / 4 repr(C) types /
  13 status codes / generation-checked handles); `native/lgj-abi`
  (72/72, clippy/fmt clean, 14/14 exported symbols via `nm -D`);
  `java/` facade + FFM membrane (132/132, reflection-enforced zero-FFM
  public surface); 5 new `ndarray::simd` primitives under the W1a
  contract (41/41); the `.claude/` ensemble + board.
- **Locked:** disable-verified generation check (exactly 2 tests red
  when broken, 70 green); the manifest cross-check rejects a real wrong
  `.so`; laziness measured (0 crossings to build, exactly 1 to
  evaluate); target-cpu=x86-64-v4 divergence recorded.
- **Deferred:** real graph types (`NodeRow`/`WideFieldMask`) — generic
  fixture first, by design (`docs/abi.md` §10).
- **Docs:** `docs/abi.md`, knowledge docs, board.
- **Confidence:** High — one real audit violation (`ndarray::hpc`
  import) found and fixed pre-merge; recorded in EPIPHANIES.

# Issues Log — Open + Resolved (double-entry, append-only)

## ISS-LGJ-ORPHAN-MASK-CLOSE-LEAKED (2026-08-28) — RESOLVED same day (#57)

**A real leak, found behind a disagreement between two reviewers about a doc
line.** `Mask.close()` guarded its native close with `if (parent.isOpen())`,
commented *"the selection was freed with it; calling close again would only earn
an INVALID_HANDLE. Nothing leaks either way."* **Both halves were false**, and
the registry says so plainly: `registry::close` takes only the handle's **own**
slot and never cascades to children, and `create_mask` gives a mask its own
`Box<[u64]>`. So an orphaned selection is still a live resource holding a live
allocation, and skipping the call leaked **both the words and the registry
slot** for the life of the process.

**How it surfaced, which is the part worth keeping.** Codex (P2) said an
orphan's `lgj_close` returns `OK`; CodeRabbit said `INVALID_HANDLE` and asked
for the latter to be documented. They cannot both be right, so it went to
source: `exports.rs`'s `a_mask_whose_parent_closed_reports_parent_closed` ends
`assert_eq!(lgj_close(m), LGJ_OK)`. Codex had the fact; CodeRabbit had the
inconsistency; **neither was arguing about a leak, and the leak was what the
disagreement was standing on.** Following the more confident reviewer, or
splitting the difference, would have documented the bug as correct behaviour.

**Falsified via the complement, because the leak itself is invisible.** Nothing
observable distinguishes a leaked slot from a live one — so
`MaskNativeOpsTest.orphanCloseActuallyReleases` asserts that a **second** close
on the same handle is REJECTED, which can only happen if the first one really
ran. Red-then-green confirmed: red under the old skip (`condition was false`),
green after. Suite 337 → **338**.

**Fixed** by closing unconditionally, with the registry facts in the comment
rather than a bare "always close" instruction — the old comment was wrong
because it reasoned from a plausible assumption about cascade, so the
replacement records what the registry actually does.

**Swept for the same shape elsewhere, and it is CLEAN** (2026-08-28, after the
fix — a bug found once is worth one grep for its siblings). Every
`Engine.close` call site in `java/src/main`, exhaustively:

| site | shape |
|---|---|
| `Mask.close` | unconditional (this fix) |
| `RowStore.close` | unconditional; holds no child handles — its only `long` fields are `handle` and `rowCount` |
| `NativePattern.close` | unconditional, and closes its three internal masks (`scratchMask` / `auxMask` / `allMask`) **first**, so a child is never left pointing at a dead parent even transiently |

**No other conditional skip exists.** Note what the sweep does *not* cover: a
`Mask` a caller holds over a `NativePattern` is not one of those three internal
fields, so it still becomes an orphan when its parent closes — which is correct
by design (the caller owns it) and is now safe precisely because this fix makes
`Mask.close()` release an orphan.

## ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW (2026-08-28) — OPEN

**Opened late, and the lateness is the first thing worth recording.** v3 W4
requires this issue to be opened **in the same commit** that re-scopes
`ISS-LGJ-EPOCH-UNCHECKED` to `RowStore` — *"Two entries, never one — a scoped
resolution must not absorb an unfixed window."* #53 shipped the `Mask` half and
#55 first tried to close the `RowStore` half; neither opened this. Found while
re-reading W4 to check the unwind against it, one commit after the re-scoping
rather than in it. The failure shape is the arc's own: **a paired obligation
left standing beside a discharged one.**

**What it is.** The W1.1 `Mask` probe narrows the cached-descriptor staleness
window; it does not close it, and the residue is cross-thread. Three facts,
each re-verified in-tree today rather than carried forward from the plan:

1. `RowStore.closed` (`RowStore.java:35`) and `Mask.closed` (`Mask.java:26`)
   are **non-volatile and lock-free** — so the Java-side guard carries both a
   check-then-act race and a *visibility* race, facade-wide and pre-existing.
2. `Engine.windowOf` builds `MemorySegment.ofAddress(addr).reinterpret(byteLen)`
   (`Engine.java:403`) — an **unbounded-lifetime** segment with no FFM scope
   check. Nothing in the segment itself can observe the resource closing.
3. `Engine.close(long)` is `public static` (`Engine.java:73`), so it is
   reachable **outside** the owning facade object.

Together: a Java-side lock would be sound only under an unstated *"this object
is the sole closer of this handle"* assumption. **Writing that assumption down
is arm (ii)'s deliverable, and it is still unwritten.**

**Stated rather than smoothed over.** That deliverable is a **documented
contract, not an enforced one** — facts 2 and 3 are exactly why no Java-side
mechanism can enforce it. The probe's own read is a native acquire through the
registry `RwLock`, which is why it *narrows* the window; a second thread
closing between the probe's return and the segment read is still unguarded.

**Not a regression, and not newly introduced by W1.1.** The ABI states its own
position: concurrency is unbenchmarked and *"a concurrent Java writer would
need a documented protocol, which this ABI version does not define"*
(`exports.rs`, cf. `abi.md` concurrency section). This entry exists so the
window is **tracked** rather than absorbed into a scoped resolution that does
not cover it.

**Closure terms — HALF DISCHARGED 2026-08-28, and the half matters.** The
written-contract half is done: `docs/abi.md` "Concurrency" carries *The
sole-closer contract (normative for callers; NOT enforced)* with all three
unenforceability facts, and `Engine.close` / `RowStore.close` / `Mask.close`
each state it where a caller actually reads. Arm (ii)'s deliverable is
discharged.

**The issue stays OPEN**, because writing a contract down is not enforcing it —
which is precisely what arm (ii) was scoped to deliver and what W4 declined to
call a fix. It closes only when the ABI defines a concurrent access protocol
that makes the contract enforceable. Not gated on any measurement; nothing here
is a cost question.

**Corrected while writing it:** `abi.md`'s own resource table claimed *"every
operation on [a child of a closed parent] returns `PARENT_CLOSED`"* — false for
the cached-descriptor path, which resolves no handle and therefore returns no
status at all. The row now says **handle-mediated** operation and points at the
contract. The doc had been overclaiming exactly the guarantee this issue exists
to deny.

## ISS-LGJ-ROWSTORE-PER-ACCESS-MEASURED — EXPLORATORY, decides nothing

> ⊘ Retitled (Codex P1a on #55). The header read *"the answer is 'not at
> all'"*. It is not an answer: the run below is void by §5.2 and its arms are
> not the production accessor (§5.4). See the struck Consequences.

The gate §5 named and never had. `bench/.../H_CachedAccessorProbe.java`,
5 forks x 8 iterations, `rows=65536`, release `.so`:

| arm | ns/accessor-call | 99.9% half-width | % of own score |
|---|---|---|---|
| `cached` (probe absent) | **9.398** | ±0.362 | 3.85% ✓ |
| `probed` (probe present) | **44.932** | ±0.931 | 2.07% ✓ |

`delta_ns = 35.534`, `hw_delta = sqrt(0.362² + 0.931²) = 0.999`, delta CI
**[34.54, 36.53]**, `ratio = 4.78` (**flagged**, threshold 2.0). Both arms
clear the per-arm acceptance rule. The power precondition `hw_delta < N/2`
is met for any `N > 2.00 ns`.

⊘ **STRUCK (Codex P1a on #55).** This paragraph read a verdict off the table
— *"FAIL at any `N ≤ 34.54`… So: not at all. The `RowStore` half does not
ship."* No verdict is available here: §5's verdict function takes an `N` from
an amendment that precedes the run, and none does. The numbers stay; the
conclusion does not.

**⚠ This run is NOT the pre-registered gate, and the reason is my error.**
§5.2 requires the amendment naming `N` to be committed *before* the first
run; I built and ran the benchmark without one. By §5.2's own words a
result whose amendment does not precede it "is not a measurement — it is a
post-hoc threshold, and the run is void." So this is recorded as an
**EXPLORATORY** measurement, and any `N` written now is contaminated by
having seen it.

⊘ **STRUCK (Codex P1a on #55).** This paragraph argued *"what saves the
conclusion is that it does not depend on `N`"* — the exact move §5.2 exists to
forbid, dressed as an exemption from it. A conclusion that "does not depend on
`N`" is a conclusion reached without the gate, and it is doubly unavailable
here because the arms are not the production accessor (P1b): the ratio is
against a *reconstructed* 9.4 ns body, not `classidAt`'s.

**Consequences:**
- ⊘ **STRUCK (Codex P1a on #55).** This bullet read: *"`ISS-LGJ-EPOCH-UNCHECKED`
  closes for `Mask` (shipped, #53) and is **CLOSED AS WON'T-FIX for `RowStore`
  per-access** on this measurement."* **It closed a safety gap as won't-fix using
  a run this same entry declares void.** Replacing the required ex-ante cutoff
  with the post-hoc judgment that "no principled budget … is 35 ns" is exactly
  the substitution pre-registration exists to prevent — and it contradicts v3 W4,
  which requires this issue to stay OPEN and scoped to `RowStore` until the half
  has validly shipped or been validly rejected. **`ISS-LGJ-EPOCH-UNCHECKED`
  remains OPEN for `RowStore`.** The `Mask` half's closure (#53) is unaffected:
  it shipped on a falsifier, not on a budget.
- ⊘ **STRUCK (Codex P1b on #55).** This bullet claimed to correct
  `ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT`'s "forces TWO BUILDS". **The
  original finding was right and this "correction" was wrong.** `H_CachedAccessorProbe`
  calls `Engine.describeLane` directly, so both arms *reconstruct* an accessor
  body and neither is `RowStore.classidAt` — they bypass the cached `lanes[]`
  lookup, `requireOpen`, the `FacetId` null-check and the bounds check. §5.4's
  own words are that the accessor "is an inlining/compile barrier that changes
  the surrounding loop's optimization… That total is the right thing to gate on."
  Two **production** variants genuinely cannot share one classpath without the
  hoistable branch §5.5 forbids, so the two-build requirement stands, unamended.

**What this run still is.** An indicative measurement of a bare
`lgj_lane_describe` crossing on a hot read loop (35.5 ns delta, CI
[34.54, 36.53]) — real, reproducible, and useful as an order-of-magnitude
input to whatever `N` an amendment eventually names. It is **not** the cost of
the shape that would ship, and it decides nothing.

**A valid `RowStore` gate still needs all three, in this order:** (1) an
amendment naming `N > 0`, committed *before* the run; (2) before/after variants
of the **production** accessor — two builds of `java/`, per the unamended
finding above; (3) a results commit citing that amendment's sha.

**The pattern, for the ledger — eighth instance.** `ISS-LGJ-SECOND-VERDICT-BESIDE-THE-FIRST`
records seven cases of a repair fixing one verdict path and leaving a second
standing beside it. This is the eighth and the worst shape of it: the entry
*correctly* declared its own run void in one paragraph and then *acted on that
run anyway* in the next. Naming a failure mode does not confer immunity to it.

## ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT — the RowStore gate cannot be run as specified

Found while starting the benchmark v3 names as the `RowStore` half's gate.
Two ordering problems, both in the plan rather than in the code:

**1. The gate requires the thing it gates to exist.** v3 says the
`RowStore` half is *"per-access or not at all, gated on a benchmark that
does not yet exist"*. But the benchmark's `after` arm **is** the per-access
probe — there is nothing to measure until it is written. So the honest
sequence is *implement, measure, then keep or discard*, and the plan reads
as though the measurement precedes the implementation. Whether to write an
implementation that may be discarded is an operator call, not a silent one.

**2. §5.5's build-time variant swap forces TWO BUILDS.** §5.5 rules out an
`if (guardEnabled)` branch inside the accessor — correctly, since it is
hoistable and is not the shape either arm ships. The consequence was never
stated: with one Java source tree there is no way to have both arms on one
classpath, so `before` and `after` are **separate builds of `java/`,
benchmarked in separate JMH runs**, and the comparison is across runs
rather than across `@Param` values. Every existing sweep in `bench/`
(A–G) compares arms *within* one run; this one structurally cannot.

Consequences for whoever writes it: the two runs must pin the same JDK,
the same `liblgj_abi.so`, and the same machine state, and the amendment
naming `N` must precede **both**. The pre-registration rule (§5.2) is
unchanged but now has two commits to order against, not one.

**Not blocking the `Mask` half**, which shipped in #53 without needing any
of this — it was never cost-gated.

## ISS-LGJ-EPOCH-UNCHECKED — RESOLVED for the `Mask` half (W1.1)

`Mask.words()` now re-authorises its cached word lane with the substrate
on every use of that cache: one O(1) downcall per whole scan, never per
word. ⊘ **Corrected on #54 (Codex P2):** an earlier version of this line claimed
`Engine.epoch`'s javadoc ("Java re-checks this before trusting a cached
lane segment") "is no longer false for masks". **It is still false.** The
fix routes through `Engine.describeMask`, so `Engine.epoch` retains
exactly zero callers — its only mention in `src/main` is the javadoc that
now explains why it is *not* used. That javadoc has been rewritten to say
so, which is what actually discharges the W3 obligation; the claim above
would have hidden it.

**A measured correction to the v3 plan, found by building it.** v3 §6 said
the probe would be `lgj_resource_info`, which "already reads live". It does
— but it resolves the mask's **own** registry slot, and **that slot
outlives its parent**. The first implementation probed it and the falsifier
went red the right way: closing the parent store natively left the probe
silent, `count()` correctly reported `PARENT_CLOSED`, and
`materializeRows()` went on to **read freed bytes without crashing**.

Two things worth keeping from that:

1. **An absent segfault is not evidence of safety.** The exact hazard
   Codex's P1 on #49 described, reproduced here on purpose and observed
   doing nothing visible.
2. **Handle liveness is not lane liveness.** A child handle resolving says
   nothing about whether the bytes it points at are still owned. The
   authorising question is the one that resolves the parent chain.

`lgj_mask_describe` is that question — `registry::resolve_mask_with_parent`,
O(1), fills a descriptor and does no work over the population — so the fix
needs **no new ABI symbol** and v3's "no new production symbol" ruling
survives, with its *reason* corrected.

**Still open:** the `RowStore` half and the cached-descriptor path in
`RowStore.lanes[]`. This entry closes the `Mask` half only.

**⊘ "the identical cached-descriptor path" — struck 2026-08-28. The two paths
are NOT identical, and the difference may settle the `RowStore` half without a
benchmark.** Verified in-tree, not reasoned from the design: a `Mask` has a
parent it does **not** own (`create_mask` takes `PATTERN | ROWSTORE`), so
closing that parent is **in-contract** and still invalidates the child's cached
words — the W1.1 falsifier. A `RowStore` is only ever a *parent*; its bytes are
allocate-once `Arc<[u8]>` with **no realloc surface**; its constructor is
private and every factory mints a fresh handle; and its one closer sets the
flag `requireOpen` reads. So **within** the sole-closer contract there is no
state where its cached lane is stale and `closed` is false — the weaker
mechanism is sufficient **for this resource, inside that contract**,
structurally rather than by luck.

**The conditional is load-bearing and belongs here, not in a footnote** (both
reviewers on #58 said so independently). The contract landed in #57 as
**documentation, not enforcement**: `Engine.close(long)` is still `public
static`, `windowOf`'s segment is unbounded-lifetime, and `closed` is a plain
non-volatile boolean. A contract violation, or a concurrent close against a
live reader, *can* produce a stale cached lane with `closed == false`. **Q4
does not claim that is impossible — it claims it is out of contract**, and the
probe would defend only that territory, incompletely (re-describing narrows the
window, never closes it). The stronger form needs **enforcement**, not more
argument.

**Not acted on, and that is the point.** Full argument, its adversarial check,
and its single load-bearing dependency are `epoch-recheck-v3.md` **Q4**, filed
as a PROPOSAL. W4's rejection route for this half is a *measurement*; rejection
on structural grounds is a route the plan does not define, so adopting it is an
amendment rather than a session's own call. **Closing this issue on an argument
I wrote today would repeat instance eight one PR after it was caught.** The
issue stays OPEN pending a ruling.

## ISS-LGJ-SECOND-VERDICT-BESIDE-THE-FIRST — §5 adversarial read, FIXED

**The pattern, which is the point of this entry.** Three successive
repairs to ONE rule (audit rows 20, 22, 23), and each fixed a verdict
path while **leaving a second, independent verdict standing beside it**:

| repair | fixed | left standing |
|---|---|---|
| row 20 | overlap as a verdict | overlap as a *label* |
| row 22 | the label | the standalone `delta_ns ≤ 0` auto-pass |
| row 23 | the auto-pass | — (one verdict function now) |
| #55 (8th) | — | the entry declared its own run void, then acted on it one paragraph later |
| #57 (9th) | `abi.md`'s "**every** operation returns `PARENT_CLOSED`" | "every **handle-mediated** operation returns `PARENT_CLOSED`" — still false |
| #58 (10th) | Q4's *conclusion*, made conditional | the *adjacent* sentence, still claiming close-after-cache is out of contract |

**The tenth arrived inside the fix for the eighth's cousin, one hop sideways.**
Two reviewers made Q4's conclusion conditional; the qualification landed, and
the sentence **immediately below it** went on asserting that "the scenario Q1
names (a close after the lane is cached) is reachable only by violating the
sole-closer contract." False: that is an ordinary single-thread ordering, and
`RowStoreLifetimeTest` exercises it. Caught by CodeRabbit, which read the
paragraph rather than the diff hunk.

**Why this one had teeth rather than being a wording slip:** read literally it
implied `requireOpen()` guards nothing in contract — an invitation to delete
the guard that makes the ordinary case safe. The ninth instance cost a
quantifier; this one could have cost a check.

**What generalizes past instance nine:** nine was the same *sentence* narrowed
and still wrong. Ten is a *neighbouring* sentence left asserting the stronger
claim after its neighbour was weakened. So the sweep after any qualification is
not "re-read the sentence I changed" — it is **re-read the paragraph, and ask
which other sentence was resting on the claim I just weakened.**

**The ninth is the purest instance the ledger has, and it is mine from one PR
later.** Fixing the eighth taught nothing about the ninth, because they are not
the same claim — they are the same *sentence*, narrowed once and still wrong.
`abi.md` promised `PARENT_CLOSED` from *every* operation on an orphaned child;
#57 narrowed that to every **handle-mediated** operation and shipped it as the
correction. Codex (P2, #57) showed the narrowed version false in two ways the
repo's own tests already assert: `lgj_resource_info` resolves the child's own
still-live slot and returns `OK`, and `lgj_close(orphan)` returns `OK` —
pinned at `exports.rs`'s `a_mask_whose_parent_closed_reports_parent_closed`,
whose last line is `assert_eq!(lgj_close(m), LGJ_OK)`.

The true predicate is narrower than either: only operations that resolve the
child **with its parent** (`resolve_mask_with_parent`). Both sites now say that
and name the two exclusions.

**What generalizes:** rows 5-7 recorded fixes that *introduced* the next defect.
This is that shape at its tightest — the repair and the residue in one sentence,
so no diff could show them apart. A quantifier narrowed once ("every" → "every
handle-mediated") reads like diligence and is only a *smaller* overclaim; the
check that catches it is enumerating the actual call sites, not re-reading the
sentence.

Each version read correctly *in isolation*, which is why three review
rounds did not catch it: a diff review sees the rule that changed, not
the rule that did not. Only reading §5 whole, against its own premises,
surfaces two rules that disagree on the same input.

**Verified contradictions, with numbers.** `delta_ns = −1`,
`hw_delta = 50`, `N = 10`: the auto-pass clause returns PASS, the delta
table returns UNDERPOWERED, and nothing said which wins. And per-arm-only
run acceptance at its own 10% ceiling gives `hw_delta = 14.1 ns` for two
100 ns arms, making the PASS row **unreachable for every `N ≤ 14`** even
when the probe is free — a gate that cannot return its own PASS.

**Four further defects in the same pass:** the ratio still compared on
bare point estimates (the error just fixed for the delta, left on the
secondary metric, able to veto a passing delta); "paired per-iteration
samples" incoherent with §5.5's build-time variant swap; a choose-at-
analysis-time estimator reopening the freedom Q3 closed; and the retained
"median of 5" contradicting the `avgt`-with-CI score every rule uses.

**Standing lesson:** when a rule is repaired under review pressure, read
the *whole* rule afterwards, not the diff. Three of these six survived
three review rounds precisely because each round looked at what changed.

**⊘ AMENDED 2026-08-28 (#53) — the "FIXED" above was premature, and the
way it was wrong is the entry's own subject.** The repair declared the
delta table the sole verdict function and then **left three earlier
verdict statements standing** — "Ship if `delta_ns < N`", "Ship if
`ratio < 2.0`", and Q3's "both must pass". CodeRabbit and Codex found it
independently; Codex's counterexample is `delta_ns = 9, hw_delta = 2,
N = 10`, where the earlier rule ships and the table returns UNDERPOWERED.

So the pattern this entry describes claimed a **fourth** instance, and
its author was the commit that named it. The lesson is therefore stronger
than first written: **naming a failure mode does not confer immunity to
it.** Writing "each repair left a second verdict standing" in the same
diff that left a second verdict standing is as clear a demonstration as
the ledger will ever get. All three statements are now struck in place
(⊘, never deleted) — the delta and ratio bullets define quantities, the
table alone returns a verdict.

## ISS-LGJ-CI-OVERLAP-AUTOPASS — post-merge finding on #49, FIXED in the follow-up

**What:** `epoch-recheck-v3.md` §5's first statement of the measurement
rule auto-passed **any** run whose two arms' 99.9% CIs overlap, on the
reasoning that an unresolvable cost cannot exceed a positive budget.

**Why it is wrong:** arm-CI overlap and "the delta is under N" are
different propositions. Scores 100 ns and 118 ns with 9% half-widths give
`[91, 109]` and `[107.4, 128.6]` — overlapping — while `delta_ns = 18`.
Under an `N = 10 ns` amendment the rule ships a probe costing ~2× budget.

**Fix:** both gates are evaluated independently of overlap; overlap is a
LABEL on the verdict, never a verdict. A failure so labelled stays a
failure — the remedy is a better-powered run, never a re-interpretation.

**⊘ STORNO 2026-08-28 (same day, from #51) — the Fix above is itself
superseded.** Keeping arm-CI overlap as a *label* is still unsound:
overlapping CIs for two separate means are not a confidence interval for
their difference, and say nothing about how that difference compares to
`N`. So the label asserted a noise-floor claim the data does not support,
and in the other direction let a point-estimate failure be written up as
definitively "too costly" on a run that could not tell. **The uncertainty
is now computed on `delta_ns` itself** and its interval compared to `0`
and `N`; an interval straddling `N` is **UNDERPOWERED** — neither pass
nor fail, remedy a better-powered run. Arm-vs-arm comparison no longer
appears in the rule. The line above is kept, not deleted: it is what the
first repair said, and the second repair is only legible against it.

**Provenance worth keeping:** CodeRabbit posted this ~2 minutes AFTER #49
merged, so no gate on #49 could have caught it and the merge itself could
not have waited for it. A subscription is not finished at merge — the
last review can land after. Companion finding in the same batch: the arc
headline stated one delivery path where the plan has two (Mask
unconditional, RowStore benchmark-gated).


## ISS-LGJ-EPOCH-UNCHECKED (2026-08-28) — OPEN (council ruled; implementation queued)

> **⊕ COUNCIL RULING (2026-08-28, `epoch-recheck-v3.md`).** The 5+3 council
> found this issue's own framing narrower than the truth: an epoch
> **mismatch is unreachable** through `lgj_resource_info` — `close` bumps
> the slot generation (`registry.rs:331-338`), `insert` hands the advanced
> generation out (`:253-257`), and the export opens with `resolve`
> (`exports.rs:214-222`), so a stale handle **throws** rather than
> returning a mismatched epoch (only a `u32` wrap could make the compare
> fire). The fix is therefore a **native generation-checked liveness probe
> replacing the Java `closed` boolean** at the cached-descriptor seam — not
> "wiring epoch checking". The hazard the probe defends is real and
> reachable (close the native handle out from under a live wrapper; the
> cached segment then reads freed storage), which is why resolution (b)
> was NOT selected despite the unreachability finding: (b) requires the
> HAZARD unreachable, and it is not.
>
> **Closure terms, pre-registered and PER-HALF** (tightened 2026-08-28
> after external review on PR #49 — the first wording would have let this
> close on the `Mask` half alone while `RowStore`'s cached path stayed
> boolean-guarded, i.e. with the issue's own condition still true):
> **RESOLVED only when BOTH halves ship.** If measurement rejects the
> per-access `RowStore` probe, this issue stays **OPEN**, re-scoped in
> writing to `RowStore`, and the doctrine's cached-descriptor exclusion
> stays live for that path. A **NEW** issue is opened in the same commit
> for the cross-thread lifecycle-vs-access window arm (ii) documents
> rather than fixes. Two entries, never one — a scoped resolution must
> not absorb an unfixed window.

**Found.** By the 5+3 council's `handle-lifecycle-auditor` pass on PR #45
(the zero-copy/memory-safety doctrine review). `LgjLaneDesc`/`LgjMaskDesc`
carry an `epoch` field (`exports.rs`, lane/mask describe exports)
specifically so a Java-side holder of a cached descriptor address could
re-validate it against the live resource. Grepped `epoch` across
`java/src/main` — it appears only in layout/record construction
(`Engine.java`'s `LaneWindow`), never in a comparison. The re-check the
field exists for is unwired; the only guard on the cached-descriptor path
is a Java `closed` boolean (weaker: it does not detect a slot that closed
and was reused for a different resource within the same process, the way
a generation comparison would).

**Not urgent, not silent-corruption-shaped today**: `RowStore`/`Mask`
mark themselves permanently closed on `close()` (no slot reuse observed
from the Java facade's own lifecycle — a `RowStore` handle is never
recycled to address a different resource while a `lanes[]` cache still
points at the old one, per `RowStoreLifetimeTest`). The gap is real but
currently unreachable through the facade's own public API; it becomes
load-bearing only if a future capability lets a Java-held descriptor
outlive a same-slot resource swap. Filed so it is not silently assumed
covered by the generation-registry claim in `CLAUDE.md`'s zero-copy
section (see that section's "Pointer value is not provenance" bullet,
corrected 2026-08-28 to name this gap explicitly rather than imply
`epoch` is checked).

**Next step, not yet scheduled**: wire `epoch` comparison into
`RowStore`/`Mask`'s per-access path (`lane(int)`, `checkedRow`), or, if
measurement shows the facade's own close-discipline makes it provably
unreachable, downgrade this entry to a documented invariant rather than
a live gap — either resolution needs the measurement, not assumption.

## ISS-LGJ-HOP-LAYOUT-BLOCKS-THE-ALGEBRA (2026-08-27) — RESOLVED (same day; ABI minor 10)

**Found.** By landing R1 (selection as mask algebra) and measuring it.

**Measured.** 65 536 rows, all densities: the lawful shape costs
**~40 600 µs** where the shipped one-pass sweep costs ~2 100 µs — a **19×
regression** — because 32 facets × 2 predicates is 64 whole-population passes
at **stride 512**, ~2 GB of memory traffic to read 512 KB of classids. Scaling
is worse than linear (1 024 rows → 144 µs; 65 536 rows → 40 632 µs, 282× for
64× the rows), the signature of cache and TLB failing together.

**Root cause is the layout, not the algebra** — and it was priced before this
arc started. R11 (#31) measured AoS 512-stride at 12–13 ns/row against an SoA
facet lane at ~1.3 ns/row (**9.2×**) and found the layout already *data* at
every boundary except the store's constructor, with the kernels already
stride-parameterized. PR #40 then banked the opposite as a law — *"a scalar
gather beats a vectorised sweep; the win is in not doing the work"* — a
measurement taken inside the defect and generalised as a property of the
operation. That claim is superseded; see the storno on #40's arc entry.

**The fix is measured, not proposed.** A columnar `(row × facet)` plane —
same bytes, field-major — runs the identical algebra at **902–2 271 µs**,
~40× the AoS mask shape and 2.3–5.7× the sweep it replaces, with cost tracking
the canvas rather than the frontier (2.5× across a 10 000× density range).
Banked: `.claude/board/hop-mask-algebra-vs-columnar.txt`.

**RESOLVED — ABI minor 10 landed the columnar store**, exactly the shape
R11 priced: an additive constructor (`lgj_rowstore_open_columnar`, facet-
major: contiguous per-facet classid/lo64/hi32 blocks, same 512n bytes, same
draws) plus lane descriptors (33 → 97 lanes so every field is served).
Measured THROUGH THE ABI at 65 536 rows: hop **3.3–4.8×** over AoS at every
frontier arm, byte-identical answers, the pinned 10 → 19 → 29 on both
layouts. The register-sweep family refuses facet-major with the new
`UNSUPPORTED_LAYOUT` (-18) — a row-major operation stays honest about being
one — pinned two-sided (same calls succeed on AoS). Java is proven
LAYOUT-BLIND: its accessors read through served descriptors, and the
disable-run (stride hard-coded 512) fails the columnar store at the first
row where the layouts' addresses diverge. Remaining headroom, named not
hidden: the lab's single-plane pass measured a further ~10× beyond the
per-facet columnar sweep — a fused whole-region kernel is the next rung,
not this one.

## ISS-LGJ-ARC-INVENTORY-STOPPED-AT-32 (2026-08-27) — RESOLVED

**Found.** While landing PR #42's own arc entry, per the board README's
rule (`PR_ARC_INVENTORY.md` — "every PR, at open").

**Measured, and the first count was wrong.** The entry as first filed said
"entries run `#1..#20`, then `#32`" and "nineteen PRs". Enumerating rather
than eyeballing the range gives a different and less tidy answer:

- entries present: `#1`–`#12`, `#14`, `#16`, `#18`, `#20`, `#32`;
- **missing: `#13`, `#22`–`#31`, `#33`–`#41` — twenty PRs**, not nineteen,
  and the gap starts at `#13`, well inside the range the first count read
  as complete;
- `#15`, `#17`, `#19`, `#21` are also absent and are **correctly** absent —
  each is itself an arc-entry-only PR, exempt under the termination clause;
- `#32`'s entry read `(draft, opened 2026-08-25)` with no merge sha, so the
  newest entry in the file was also stale.

The first count was a range subtraction over a file that has holes. Stated
here rather than silently corrected, because "I checked" and "I enumerated"
are different claims and only the second one was ever load-bearing.

**Why it mattered.** `LATEST_STATE.md` answers *what exists*; the arc
answers *why*. As it stood, a future session read this repo as going from
ABI minor 8 straight to a ClassView provider — the whole hop arc (the
32-sweep root cause, the gather rewrite, the measured absence of a
crossover, the memoisation and its cold regression) absent from the record
that exists to carry exactly that.

**Resolved.** All twenty entries written, plus `#32`'s header corrected to
name its merge and the fact that it reached `main` only via `#33`. Method,
which was the whole point: each entry drafted from **that PR's own body and
diff** — five parallel agents, four PRs each, none permitted to work from a
later session's recall. Every backfilled entry ends its **Confidence:**
bullet with `Backfilled 2026-08-27 from the PR body and diff, not written at
merge time`, so a reader can tell at a glance which entries were written at
merge time and which were reconstructed; several say plainly which of their
claims are the PR body's own and were not re-verified.

**What the backfill itself turned up** (each recorded in the entry it
belongs to, not only here): #25's PR body asserts "no code, no reproducer
changes" and its own diff contradicts it — a second commit landed on the
branch after the body was written; #39 left its `lgj_hop` doc comment
describing the pre-change design, caught one PR later by #40; #34's banked
evidence file did not identify its own JDK, corrected same-day by #35; and
#41 is merged on `main` while its own title reads `[DO NOT MERGE AS-IS]`,
which the entry records as unresolved disposition rather than an
endorsement.

**Standing rule, unchanged and now the live one:** the entry goes in at
open, in the PR's own commit. The backfill is the repair, not the process.

## ISS-LGJ-HOP-SWEEPS-FULL-POPULATION (2026-08-27) — RESOLVED

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

**Resolved in part, same day.** `lgj_hop` now calls
`simd_rowstore_facet_match` ONCE — all 32 facets per row in a single
`MultiLaneColumn` pass — and walks src's set rows instead of every row ×
facet. No new kernel; that function already existed and is the same
sanctioned `ndarray::simd` surface, it was just being consumed the wrong way
round. Measured **1.28×–3.48×**, largest at scale (24 798 → 7 120 µs at
1 %/65 536). The untouched scalar arms are the control and moved <9 %, so the
gain is not a faster host.

**What remains OPEN, and it is structural.** `simd_rowstore_facet_match`
still sweeps the whole population, so at a 1 % frontier native is still 43×
the best scalar arm (7 120 µs vs 164 µs): the DECODE half is now
frontier-bounded, the COMPARE half is not. The next rung — gather per src row
— is O(frontier) but is NOT obviously better, because a dense frontier should
favour the sweep's sequential access. That crossover is a measurement, not a
judgement call, and Component G is the instrument for it.

**Why this was not one bigger change.** The one-pass fix is bounded, needs no
new kernel, has no density crossover, and is strictly less work at every
point in the measured space. The gather rewrite is none of those things.
Landing them together would have made a regression in either impossible to
attribute.

**RESOLVED, same day, second pass.** The remaining structural half is gone:
`lgj_hop` no longer sweeps at all. It gathers — touching only the rows `src`
names, reading each one's participating facets in place out of that row's own
512 bytes.

**The crossover this issue predicted does not exist.**
`examples/hop_gather_vs_sweep.rs` measured both shapes over five populations
(1 024 … 262 144) × twelve densities — 60 configurations, byte-identical
output asserted at every one, plus an anti-vacuity empty-hop guard. **Gather
wins all 60**, from 2 754× at 0.01 % density to **1.73× at 100 %**.

The prediction was that a dense frontier would favour the sweep's sequential
vectorised access. Wrong, and the mechanism is the correction: a sweep
MATERIALISES an `n`-element per-row intermediate that each row reads exactly
once, so it is never amortised — at full density it does everything the gather
does PLUS allocate, zero, write and re-read `n` u32s. Strictly more work at
every density. The reasoning had been about access PATTERN and missed that one
shape simply does MORE.

Consequence for the design: **no threshold, no dispatch, no heuristic gate.**
A crossover would have required one, with the two-sided evidence such a gate
demands; its absence makes the change unconditional and much simpler.

End to end through Component G, the independent instrument: native_hop
24 798 → 34.4 µs at 1 %/65 536 (**720×**), and the ordering inverted — native
is now FASTEST at every configuration, 2.0×–13× ahead of the best scalar arm,
having started this arc slowest at every configuration.

**Still open, and named rather than hidden:** the one shape that could favour
a precomputed per-row mask is REUSE — memoising it across many hops on the
same `(store, classid)`. That is a caching design with its own invalidation
questions and is deliberately not this function's.

Raw probe output: `.claude/board/hop-gather-vs-sweep-crossover.txt`.
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

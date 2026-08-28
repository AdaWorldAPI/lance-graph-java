# epoch-recheck-v3 — RATIFIED (the W1.1 council's output)

**Status: RATIFIED (2026-08-28).** 5+3 complete: Phase 0 spec
(`epoch-recheck-phase0-v1.md`), Phase 1 five savants, Phase 2
consolidation (`epoch-recheck-v2-draft.md`), Phase 3 three reviewers,
Phase 4 this document. The predecessors are retained, not deleted; where
v3 overrides them it says so. This is the document implementation works
from.

The reviewers found one **blocking** defect in v2 (two of them
independently), closed one of v2's open questions outright, and corrected
five factual errors. §8 is the audit ledger.

---

## 0. THE HEADLINE — the resolution is not what the spec called it

**An epoch MISMATCH is unreachable through `lgj_resource_info(handle)`.**
`registry::close` takes the entry out and then bumps the slot generation
(`registry.rs:331-338`, `wrapping_add(1)`, skipping 0); `insert` reuses a
free slot at that already-advanced generation under its own comment —
*"its generation is already ahead of any handle that used to point at
it"* (`registry.rs:253-257`); `lgj_resource_info` opens with
`registry::resolve` (`exports.rs:214-222`), which rejects on
`slot.generation != gen` (`registry.rs:276-287`); `Status.java:124-127`
maps `INVALID_HANDLE` to `ClosedResourceException`. `entry.epoch` is
immutable and a slot cannot be refilled without a close, so no non-wrap
path reaches a live-but-different epoch. Correctly scoped: unreachable
short of a `u32` generation wrap on one slot.

Verified three times independently — by two savants, by the Phase-2
consolidation, and by a Phase-3 reviewer who re-derived it from source
expecting it to break.

**Consequence for naming, load-bearing for the doctrine text:** resolution
(a) does not deliver "epoch checking". It delivers a **native,
generation-checked liveness probe replacing a Java boolean** at the
cached-descriptor seam.

**Scope of "downstream" (reviewer correction).** §1 C1-C2 and §3 follow
from this finding. **§1 C3 and §5 do not** — the two measurement findings
hold regardless of it, and they are the two that most threaten this wave's
schedule. Do not read them as corollaries.

---

## 1. THREE CORRECTIONS TO THE SPEC

### C1 — §5's pre-registered disable-run must be replaced

The spec's gate names a fixture that is **unconstructible as specified**
(per §0, no fixture can produce an epoch mismatch), and **vacuous in its
nearest constructible approximation** (reach slot reuse via
`store.close()` and the Java `closed` boolean throws identically with the
recheck removed — green under both arms; `RowStore.java:361-369`, and on
the Mask side `requireUsable` at `Mask.java:175-185` reached from
`materializeRows` at `:102`). Both reviewers who examined it agree it was
not fixable in place.

**W5 — the replacement.** Construct the state the boolean cannot see:
**invalidate** the native resource (bump its registry generation) while a
live Java wrapper still holds `closed == false`, then read a lane that was
already resolved into the wrapper's cache.

*(Two external findings shaped this, and the plan takes the stricter half
of each. CodeRabbit's suggested flow ended in `Engine.close(store.handle())`
— which would still perform the read-after-free Codex flagged; Codex's
remedy alone would still have left the cache unpopulated and the assertion
vacuous. The construction below is invalidate-without-free AND
populate-first, so neither defect survives.)*

- **Populate the lane cache FIRST, or the test is vacuous** (CodeRabbit
  Major, PR #49 — verified in source). `RowStore.lane()` resolves lazily:
  if `lanes[laneId] == null` it calls `Engine.describeLane(handle, …)`
  (`RowStore.java:361-368`), a **fresh downcall** that fails at `resolve`
  after a native close and throws on its own. A test that closed without
  first populating the cache would therefore see "it threw" **with the
  probe disabled** — passing for the wrong reason, the exact vacuity class
  this falsifier replaced. Order is mandatory: call `classidAt` once while
  the wrapper is open (which populates `lanes[]`), THEN invalidate, THEN
  call the accessor again. Note `Engine.describeLane(store.handle(), 0)` —
  the call `RowStoreParityTest.java:186` makes and which v3 cited as the
  route — does **not** populate `RowStore.lanes[]`; only an accessor does.

- **Route (v2 was wrong here).** No `bench` bridge. `java/src/test/.../lancegraph/`
  is the **same package**, so `handle()` is directly visible, and
  `RowStoreParityTest.java:186,251` already calls `store.handle()` +
  `Engine.describeLane(...)` from a test; `Engine.close(long)` is public
  within `internal.ffm` (`Engine.java:73-75`). v2's bench route would have
  put a merge-gating falsifier where the test harness cannot run it
  (`bench/run.sh:32` compiles against `java/src/main` only and runs JMH,
  not `Checks`).
- **Assertion shape.** Assert **only that the accessor throws** — never on
  any value read. With the probe: expect `ClosedResourceException`.
  Disabled: the accessor returns normally and the "it threw" assertion
  fails.
- **Construction — invalidate WITHOUT freeing (third correction, Codex P1
  on PR #49; this is the one that actually makes W5 sound).** Asserting
  only on the throw was necessary and **not sufficient**: with the probe
  disabled, `classidAt` still *executes* `MemorySegment.get` against
  storage `registry::close` has already freed (`registry.rs:343` drops the
  `Arc`; `Engine.java:389`'s segment carries no FFM scope). Not looking at
  the value does not stop the read from happening — the JVM can die before
  the assertion is ever reached, and choosing a small fixture guarantees
  nothing about whether the allocator keeps the page mapped. So the
  disable arm's outcome stayed platform-dependent.

  **The construction must therefore invalidate the registry entry while
  keeping the allocation alive.** No existing path does this: `close`
  takes the entry out AND drops the `Arc` in one step
  (`registry.rs:330-344`). This needs a **test-only ABI export** that bumps
  the slot generation while deliberately leaking the `Arc`, so the address
  stays mapped. Then both arms are deterministic and neither touches freed
  memory: the probe arm's `resolve` fails → throws; the disabled arm reads
  **live, still-mapped** bytes, returns a stale-but-valid value, and the
  "it threw" assertion fails → RED, no UB, on every platform.

  Per the STOP rule and C2's discipline, that export is a substrate-side
  deliverable and a **full ABI citizen** — its own minor bump, manifest +
  `abi.md` entry, lazy holder, `requireMinor(N)`, and an
  `OldAbiCompatTest` leg. It is the **only** new symbol this wave admits,
  and it is admitted because without it the mandatory gate cannot report
  failure reliably. (Tracked as its own row on `STATUS_BOARD.md`.)

- **The pattern this defect illustrates, recorded because it took three
  rounds.** v2 asserted on bytes read after a use-after-free. Phase 3
  corrected the *assertion* — stop looking at the value. Codex corrected
  the *execution* — stop performing the read. Each round removed one
  symptom of the same root cause and left the cause standing, and the
  middle round felt like a fix precisely because it addressed what the
  previous critique had named. **When a correction targets the observation
  of an unsafe operation rather than the operation, ask whether the
  operation still happens.**
- **Struck from v2:** the "distinguish the probe's failure from
  `requireOpen`'s" clause. On this fixture `closed == false` by
  construction, so `requireOpen` cannot fire at all — the clause is inert
  on its own test. (`Status.toException` already gives `INVALID_HANDLE` a
  distinct narrative, `Status.java:124-127`, so the property is satisfied
  without an obligation.)

### C2 — the fallback export is struck

`lgj_resource_info` is already a minor-1 symbol (`abi.md:394`) reading
`entry.epoch` live, and `Engine.epoch(long)` (`Engine.java:85-89`) already
wraps it. **Resolution (a) needs zero new ABI surface.**

Per §0 the protection lives *in `resolve`* — so an export minted to be
CHEAPER by skipping resolve would pass a delete-the-call disable-run while
silently losing the guarantee. Read this as a **gate requirement on a
symbol this document strikes**, not as a defect on `main`: nothing
hazardous exists today. If measurement ever forces the fallback, its gate
MUST include a leg exercising the export's own stale-handle rejection, and
the four ABI-citizen obligations stand — minor bump (`abi.rs:72`),
manifest + `abi.md` entry (`abi.md:146-164`), `requireMinor(N)` at the
call site (`Engine.java:277`, `:286`), old-library rejection leg
(`OldAbiCompatTest.java:144-145`) — using the lazy-holder pattern
(`Downcalls.java:423-461`), reusing `INVALID_HANDLE` (`-2`) with no new
status code (`abi.md:157-164` precedent).

### C3 — "the banked benches are structurally blind" is FALSE, on `main`

`G_HopExecutionBoundary.java:212,215,231,234,237` call
`payloadHi32At`/`payloadLow64At`/`classidAt`; `bench/results/jmh-results-G.csv`
banks both `java_scalar_*` arms including `rows=65536`. The merged plan's
claim (`mask-membrane-valhalla-integration-v1.md:170`) is universal, so
one counterexample makes it false; it holds for benches A–F. **Storno
lands with this document's PR.**

**Struck from v2 (reviewer correction):** the claim that G is therefore "a
pre-existing before-number source". It is not — G measures a whole hop
boundary in `us/op` while §5's gate is specified in `ns/accessor-call`, so
G cannot serve as its baseline. v2 contradicted its own §5 here. The
surviving consequence is the second one: a future regression in G must not
be dismissed as structurally unrelated.

---

## 2. THE TWO CACHED SITES ARE STRUCTURALLY ASYMMETRIC

Two savants conflicted on the same lines; the consolidation settled it
against source and Phase 3 re-verified it:

| site | guard | granularity |
|---|---|---|
| `RowStore.lane(int)` (`RowStore.java:361-369`) | `requireOpen("row read")` at `:362` | runs on **every accessor call** — `classidAt`/`payloadLow64At`/`payloadHi32At` each call `lane()` |
| `Mask.words()` (`Mask.java:142-147`) | **none** | one caller, `materializeRows()`, which guards once at `:102` then loops the whole word lane at `:103-117` |

A probe at the seam therefore costs a native downcall **per row read** in
`RowStore` and **once per whole-mask scan** in `Mask`.

**Two sub-findings with homes, not deferrals:** `Mask.words()`'s missing
guard is a defect in its own right, masked today only by having a single
guarded caller (→ §7 Q2); and `materializeRows()`'s once-per-scan
exposure is a mandatory javadoc clause (→ W1).

---

## 3. THE ATOMICITY ARM — DECIDED: (ii), scoped contract

**No read/close lock, no native lifetime lease. The probe ships as
stale-cache detection BETWEEN top-level facade calls, with the scope
written down.** Three verified reasons (v2 claimed four "independent"
reasons; the fourth is a corollary of the first plus §5's ordering rule
and is not a separate leg):

1. **Arm (i)'s mandatory falsifier cannot report red.**
   `LifetimeTest`/`RowStoreLifetimeTest` are single-threaded `main` +
   `Checks` (`RowStoreLifetimeTest.java:22-34`) and there is no `Thread`
   anywhere in `java/src/test`. An interleaving test's RED here is a JVM
   crash or a silently wrong read — the "guard that cannot bark" class.
2. **Arm (i) would not close the hole it names.** `Engine.windowOf` builds
   an unbounded-lifetime `MemorySegment.ofAddress(addr).reinterpret(...)`
   with no FFM scope check (`Engine.java:389`), and `Engine.close(handle)`
   is reachable outside the facade (`Engine.java:73-75`). A Java-side lock
   is sound only under an unstated "this object is the sole closer of this
   handle" assumption, which must be written down regardless — **that
   written assumption is arm (ii)'s deliverable.**
3. **The window is pre-existing, cross-thread-only, and already unguarded
   facade-wide.** `RowStore.closed` (`:35`, `:461`, `:469`) and
   `Mask.closed` (`:26`, `:127`, `:176`) are non-volatile and lock-free, so
   the current guard carries both the same check-then-act race and a
   *visibility* race; the probe strictly narrows it, its own read being a
   native acquire through the registry `RwLock`. The ABI states its own
   position: concurrency is unbenchmarked and "a concurrent Java writer
   would need a documented protocol, which this ABI version does not
   define" (`exports.rs:356-360`; cf. `abi.md:263-270`).

**Stated rather than smoothed over (reviewer correction):** arm (ii)'s
written assumption is a **documented contract, not an enforced one** — the
same `Engine.windowOf`/`Engine.close` facts that defeat (i) mean nothing
prevents its violation under (ii) either. C1's mandatory falsifier
**deliberately violates that contract by construction** in order to produce
its red. That is not a contradiction; it is what makes the contract
testable at all, and it is written here so no future session mistakes the
contract for a mechanism.

**Noted, cutting the other way:** `NativePattern` — the sibling class —
already implements arm (i)'s shape, wrapping `close()` in
`synchronized (lock)` and re-checking inside it on every operation
(`NativePattern.java:122-144`, `:171-226`), while `RowStore` and `Mask`
have no lock. That asymmetry is undocumented (→ §7 Q4). It is weaker
support for (i) than it looks: that lock guards only handle-mediated
`Engine.*` calls, already generation-checked natively, never a cached
raw-segment read.

### What arm (ii) obliges — six, and W1/W2 are mechanically checkable

- **W1 — Javadoc scope, on both classes.** A thread-safety block on
  `RowStore` and `Mask`: the facade is not thread-safe; the caller must
  establish happens-before between `close()` and every access; a concurrent
  close-vs-access is undefined and **no guard detects it**. On
  `Mask.materializeRows()`, state that the guard is checked once for the
  whole scan. **Checkable:** a `DoctrineFenceTest` leg asserting the
  required literal (e.g. `happens-before`) appears in both class javadocs —
  the scanner already reads `java/src/main` source text
  (`DoctrineFenceTest.java:231-283`, `scanForTokens` at `:537`).
- **W2 — Doctrine wording matching that scope exactly.** In `CLAUDE.md`'s
  "Pointer value is not provenance" bullet: the cached-descriptor path is
  re-validated against the generation-checked registry **at each top-level
  facade call**; not atomic with respect to a concurrent `close`. Never
  "unconditional", never "on every dereference". **Checkable:** a fence leg
  scanning root `CLAUDE.md` for those forbidden strings in that bullet.
  Note fence 1 currently references `CLAUDE.md` as an out-of-band list it
  never reads (`DoctrineFenceTest.java:277`) — **this precedent must be
  built, not merely cited.**
- **W3 — Name the mechanism honestly, and fix the javadoc that already
  lies.** The shipped thing is a native generation-checked liveness probe;
  if the epoch compare is retained as a second leg, its javadoc must say it
  can fire only under `u32` generation wrap. **And:** `Engine.java:84`
  currently reads *"Liveness stamp of a resource. Java re-checks this
  before trusting a cached lane segment."* while `Engine.epoch` has **zero
  callers anywhere in `java/src`** (verified) — a shipped doc-comment
  asserting a behaviour that does not exist, in the exact file three
  savants cited without noticing. Correcting it is part of W3 whether or
  not the probe ships.
- **W4 — Pre-registered status, two entries, and closure is PER-HALF**
  (CodeRabbit Major, PR #49). `ISS-LGJ-EPOCH-UNCHECKED` closes as
  **RESOLVED** — the status the spec pre-registered for outcome (a) —
  **only when BOTH halves ship**. If measurement rejects the `RowStore`
  half (§7 Q1), the issue does **not** close on the `Mask` half alone: the
  `RowStore` cached-descriptor path would still be boolean-guarded, which
  is the condition the issue names. In that case the issue stays OPEN,
  scoped in writing to `RowStore`, and W2's doctrine note keeps the
  cached-descriptor exclusion live for that path specifically. And a
  **NEW** issue records the cross-thread lifecycle-vs-access window that
  (ii) documents rather than fixes.
  (v2 invented a `RESOLVED-SCOPED` status defined nowhere; a status minted
  at consolidation time is how a downgrade gets relabelled. The scoping
  lives in the second issue and in W2's wording, not in a new vocabulary.)
- **W5 — C1's falsifier**, as rewritten above.
- **W6 — Caching the comparison RESULT is forbidden.** The obvious "check
  once per call, not once per row" optimization would be a **third
  staleness authority**, derived from a stale read of the second, free to
  diverge from the resource's current state between write and use. This is
  a normative prohibition engaging the one-copy law, not a footnote —
  v2 stranded it under a heading that said CLEAN.

---

## 4. THE TWO LENSES THAT PASSED THE CHANGE — and the two pre-existing gaps they found

*(Retitled from v2's "CAME BACK CLEAN": the heading said nothing was owed,
and two real corrections evaporated under it.)*

**Zero-copy — clean on the change.** `Engine.epoch` (`Engine.java:85-89`)
reads only the per-thread `Scratch.info` segment (a
`ThreadLocal.withInitial`, allocated once per thread) and returns a `long`
— no `new`, no array, no `MemorySegment.copy`. It adds native crossings,
not allocations: no materialization site, no fence-1 pin change
(`DoctrineFenceTest.java:97-147`). **Pre-existing gap it surfaced →
promoted to W6.**

**Java surface — clean on the change.** The seam is two private methods;
`ClosedResourceException` is already what `RowStore.requireOpen`
(`:468-475`) and `Mask.requireUsable` (`:175-185`) throw and is a plain
public exception, so reuse needs zero public-surface change — with a
**distinct message**, since still-open-but-stale is a different condition
from closed. **Pre-existing gap it surfaced → §7 Q5:** fence 2b rejects
only *topology* name stems (`DoctrineFenceTest.java:347-355`), and
`ApiSurfaceTest` rejects only FFM/`internal.` **types** in public
signatures (`ApiSurfaceTest.java:38-40`) — so a public `boolean isStale()`
returns a primitive and passes both. v2 said "the actual guard is
`ApiSurfaceTest` plus keeping the seam private"; honestly stated, **the
guard is discipline, not a gate.**

---

## 5. THE MEASUREMENT GATE — undecidable today, and now with a pre-registered acceptance FORM

§5's 2× threshold cannot be evaluated today. These **extend** the spec's
protocol; its frozen clauses are **retained**: the 65,536-row fixture,
ns/accessor-call, ≥1M calls, median of 5, before/after, banked, targeting
the cached-descriptor accessors themselves.

Why it is undecidable *today*, not merely unmeasured:

1. **No isolated per-accessor benchmark exists anywhere in `bench/`** — the
   accessor's own baseline has never been measured.
2. **The only banked data cannot resolve a 2× effect.**
   `jmh-results-G.csv`, `@Fork(1)` / 3 warmup / 5 measurement
   (`G_HopExecutionBoundary.java:92-94`): `25.639505 ± 61.918845` us/op
   (error **2.4× the score**), `269.635512 ± 229.450931` (85%),
   `180.563246 ± 70.114359` (39%), and at rows=65536 also
   `9215.489321 ± 3935.414475` (43%). *(v2 generalised "the rows=65536 arms
   are better"; they are not uniformly better — corrected.)*
3. **The ratio is well-posed; the units are not.** Numerator and
   denominator are the same accessor, so this is NOT the retracted
   isolated-stage-over-whole-pipeline error. But the baseline is an
   in-process `segment.get(...)` (`RowStore.java:397/417/435`) against a
   full downcall + `Status.check`. **Hypothesis, not measurement
   (labelled as such):** at a low-single-digit-ns baseline a 2× ratio may
   be within timer and JIT noise. Nobody has measured that here.
4. **The measured delta is not "the compare".** A downcall inside the
   accessor is an inlining/compile barrier that changes the surrounding
   loop's optimization. That total is the right thing to gate on, but must
   be labelled as such, and results must be consumed (Blackhole) or one arm
   is eliminated and the ratio is manufactured.
5. **Variant swapping must be build-time.** An `if (guardEnabled)` branch
   inside `classidAt` is itself hoistable and is not the shape either arm
   ships.

**The acceptance FORM, pre-registered now so the gate has a satisfaction
condition** (without this §5 is not a gate but a veto of indefinite
duration): ≥5 forks; accept a run only if the 99.9% CI half-width is
< 10% of the score; report the **ns delta AND the ratio**.

**Q3 is closed here, and the cutoff is procedurally pre-registered
(Codex P2 on PR #49).** v3 as first written left both the metric and its
number open — the ratio "retained", an absolute budget still an open
question, and the cutoff "fixed BEFORE the run" by nobody in particular.
That is not pre-registration: an implementer could pick the metric AND
its threshold after seeing preliminary data, and identical measurements
could then yield opposite RowStore ship decisions. Ruled:

1. **The ns delta is PRIMARY; the 2× ratio is a secondary sanity check.**
   Both must pass. The ratio does not replace the budget and the budget
   does not replace the ratio — at a low-single-digit-ns baseline the
   ratio alone is undecidable (§5.3), and a budget alone would hide a
   pathological multiple on a fast accessor.
2. **The numeric ns cutoff is recorded by an amendment to THIS file whose
   commit precedes the first benchmark run**, and the wave's results
   commit must cite that amendment's sha. No number is invented here
   because none has been measured; what is fixed here is that the number
   is fixed *first*, in the repository, where its ordering is checkable.
   A results commit whose cited amendment does not precede it is not a
   measurement — it is a post-hoc threshold, and the run is void.

---

## 6. THE RESOLUTION

Ship **(a), renamed and re-scoped**, under arm **(ii)**:

- A native generation-checked liveness probe at the cached-descriptor seam,
  replacing the `closed` boolean's sole authority. **No new ABI symbol.**
- **`Mask`: ships regardless.** Probe at `words()` — one downcall per whole
  scan, and falsifiable by the same C1 construction
  (`Engine.close(mask.handle())` with `mask.closed == false` and the parent
  open, so `requireUsable` at `:175-185` passes). This half carries a real
  gate at negligible cost.
  **This closes Q2's safety half** (CodeRabbit, PR #49 — the two statements
  were left floating): both of `requireUsable`'s conditions imply the
  native handle is already dead — `Mask.close()` closes it when the parent
  is open, and when the parent is gone "the selection was freed with it"
  (`Mask.java:126-133`) — so a probe at `words()` fires in both cases and a
  future second caller of `words()` inherits protection rather than
  nothing. What the probe does NOT inherit is `requireUsable`'s clearer
  PARENT_CLOSED narrative; adding the guard for message quality stays worth
  doing, but it is a diagnostics improvement, not a safety gap.
- **`RowStore`: per-access placement inside `lane()`, or this half does not
  ship.** See §7 — Q1 is closed.
- Arm (ii)'s six obligations W1–W6, with W1/W2 mechanically fenced.
- C1's falsifier replacing the spec's; C2's fallback strike; C3's storno.
- §5's acceptance form pre-registered; its five prerequisites are
  deliverables, and the spec's frozen protocol clauses are retained.
- **D4 retained explicitly:** every existing `LifetimeTest` /
  `RowStoreLifetimeTest` falsifier stays green. C1 *replaces* one gate; it
  retires none of the frozen ones.

**Why resolution (b) is NOT selected, despite §0 being an unreachability
proof** (v2 deleted this without a word): (b) requires proving the
**hazard** unreachable — that slot-reuse-while-cached is structurally
impossible. §0 proves something different and narrower: that the **epoch
compare** is unreachable. C1 demonstrates the hazard is very much
reachable (close the native handle out from under a live wrapper and the
cached segment reads freed storage). The probe therefore defends a
*different* hazard than the one §0 rules out, and (b)'s premise is not
established. (b) remains available only if §5's measurement forces it.

**§0's proof status — resolved by pinning it, not by downgrading it**
(CodeRabbit Major, PR #49: §0 read "verified three times" while this
paragraph demanded a test or a *claimed, unverified* label, and the board
files asserted high confidence — a three-way conflict). The spec required
unreachability be shown by "a falsifier-backed proof (a test that tries to
construct the bad sequence and fails, **not prose**)". Three independent
source derivations establish the code path as it stands today; they cannot
establish that a future refactor keeps it that way, which is what a test
is for. **Deliverable, and it is cheap:** a test that opens a store, takes
its handle, closes it, and asserts `Engine.epoch(handle)` **throws**
rather than returning any epoch. Deterministic and UB-free by construction
— `lgj_resource_info` fails inside `resolve` before it ever touches
`entry.info()` (`exports.rs:214-222`), so nothing reads freed memory.
With that test committed, §0 is pinned behaviour and every citation of it
stands as written; without it, every citation drops to *claimed,
unverified*. No third option.

## 7. OPEN QUESTIONS

- **Q1 — CLOSED by Phase 3, on falsifiability rather than cost.** v2 posed
  per-access vs. first-resolve placement in `RowStore` as an open trade. It
  is not: if the probe sits at lane first-resolve, the only scenario it
  could catch is the one it is blind to (a close *after* the lane is
  cached — exactly C1's construction), so **no input would make the
  shipped guard fail** and the wave would ship a decoration with a green
  test beside it. Only placement inside `lane()` (`RowStore.java:361-362`,
  per accessor call) is falsifiable. Measurement (§5) cannot rescue the
  cheap arm; if per-access proves too costly, the RowStore half does not
  ship and the Mask half does.
- **Q2 — safety half CLOSED** by the `words()` probe (see §6: every
  `requireUsable` condition implies native closure, so the probe fires in
  both). What remains open is only message quality — whether `words()` also
  gains `requireUsable` for its clearer PARENT_CLOSED narrative.
- **Q3 — CLOSED** by §5 (Codex P2): neither replaces the other. The ns
  delta is primary, the 2× ratio a secondary sanity check, both must pass,
  and the numeric cutoff is recorded by a dated amendment to this file
  whose commit must precede the first benchmark run.
- **Q4.** Is `NativePattern`'s locked-close asymmetry worth normalizing, or
  documenting as deliberate?
- **Q5 (new, from §4).** Does the fence gain a freshness-name stem, or is
  "`ApiSurfaceTest` + private seam + discipline" declared sufficient — in
  writing, since neither existing gate catches a public `isStale()`?

## 8. AUDIT LEDGER — what Phase 3 changed

| # | verdict | change to v2 |
|---|---|---|
| 1 | BLOCKING | W5's disable arm is a use-after-free; "deterministic" was false and the standard v2 applied to arm (i) exempted its own falsifier. Rewritten: assert only *that it throws*, honest UAF labelling, pinned small fixture. |
| 2 | wrong route | W5's `bench` bridge struck — the test tree is the same package and already calls `handle()`; the bench route would have put a merge gate where the harness cannot run it. |
| 3 | Q1 closed | Per-access placement is forced by falsifiability, not chosen on cost. |
| 4 | inert clause | W5's "distinguish from `requireOpen`" struck: `closed == false` by construction on that fixture. |
| 5 | invented status | `RESOLVED-SCOPED` → the pre-registered `RESOLVED` + a second issue. |
| 6 | deleted track | §6 now states why (b) is not selected despite §0. |
| 7 | evaporated | W6 (third-staleness-authority ban) and Q5 (fence gap) promoted out of the "CLEAN" heading; §4 retitled. |
| 8 | missed defect | `Engine.java:84`'s javadoc claims a behaviour with zero callers → added to W3. |
| 9 | overclaims | "unfalsifiable"→"unconstructible + vacuous"; G struck as a before-number source; the rows=65536 dispersion corrected; "the actual guard is `ApiSurfaceTest`"→discipline, not a gate; four "independent" reasons→three; §5.3 labelled a hypothesis. |
| 10 | mis-citation | `Mask.java:124-127` (double-close throw) → `requireUsable` at `:175-185` via `materializeRows` `:102`. |
| 11 | orphaned gate | D4's frozen falsifiers restored to §6 explicitly. |
| 12 | unenforceable | W1/W2 given mechanical fence legs; §5 given a pre-registered acceptance form. |

### 8b — external review on PR #49 (after ratification)

| # | verdict | change |
|---|---|---|
| 13 | P1, third round on one defect | W5 again: asserting only on the throw stopped the council from *looking* at freed bytes but not from *reading* them, so the disable arm stayed platform-dependent. Construction changed to invalidate-without-free via a test-only ABI export (the one new symbol this wave admits, a full ABI citizen). The three-round pattern is recorded in §1 C1 as its own lesson. |
| 14 | P2, pre-registration hole | Q3 closed (ns delta primary, ratio secondary, both must pass) and the numeric cutoff bound to an amendment commit that must PRECEDE the first run — otherwise metric and threshold could both be chosen after seeing data. |
| 15 | Major, would have made W5 vacuous | The falsifier must populate `RowStore.lanes[]` via a real accessor call BEFORE invalidating: `lane()` resolves lazily, so an unpopulated cache makes the post-close `classidAt` throw from its own fresh `describeLane` — passing with the probe disabled. `Engine.describeLane(store.handle(), 0)`, the route v3 cited, does not populate the cache. |
| 16 | Major, proof-status conflict | §0 was "verified three times" while §6 demanded a test-or-downgrade and the board asserted high confidence. Resolved by pinning §0 with a cheap UB-free test (`Engine.epoch` on a closed handle throws), not by downgrading. |
| 17 | Major, closure-term hole | `ISS-LGJ-EPOCH-UNCHECKED` could have closed RESOLVED on the Mask half alone while `RowStore`'s cached path stayed boolean-guarded. Closure is now per-half and conditional. |
| 18 | Minor ×2 | The `u32` generation-wrap qualification restored to both board summaries; the Mask "ships regardless" statement reconciled with Q2. |

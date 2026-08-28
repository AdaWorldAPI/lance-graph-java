# epoch-recheck-v2 — DRAFT (Phase 2 consolidation of the W1.1 council)

**Status: SUPERSEDED by `.claude/plans/epoch-recheck-v3.md` (2026-08-28).**
Retained as the council's Phase-2 artifact — the reviewers' findings are
only auditable against the draft they attacked. Phase 3 found one BLOCKING
defect here (W5's disable arm is a use-after-free, so "deterministic" was
false), closed Q1 outright, and corrected five factual errors; v3 §8 is the
ledger. Read v3, not this, for what to implement.

**Original status line: DRAFT v2 — Phase 1 complete (5 savants cast and
returned), Phase 2 consolidation done, Phase 3 reviewers NOT yet cast.**
Predecessor: `.claude/plans/epoch-recheck-phase0-v1.md` (the Phase-0 spec,
whose §1 frozen decisions and §4 non-goals still bind). Reviewers see THIS
document only; they do not see the five raw savant reports.

Lenses cast, per spec §6: handle safety, ABI membrane, zero-copy law, Java
surface, measurement. Every claim below carries the file:line a savant gave
or that this consolidation verified directly; where the consolidation
overrode a savant, it says so.

---

## 0. THE HEADLINE — the resolution is not what the spec called it

**Two lenses, working independently, found the same thing: an epoch
MISMATCH is unreachable through `lgj_resource_info(handle)`.**

`registry::close` bumps the slot generation (`registry.rs:335`,
`wrapping_add(1)`, skipping 0) and `insert` reuses a free slot returning
`encode_handle(slot.generation, idx)` under its own comment — *"its
generation is already ahead of any handle that used to point at it"*
(`registry.rs:253-257`) — so a Java-held stale handle can never resolve to
any resource again. **This consolidation re-read both sites and
`lgj_resource_info`'s body directly rather than relaying the reports**, because
everything below depends on it. `lgj_resource_info` begins with
`registry::resolve(handle)` (`exports.rs:219` → `registry.rs:276-287`),
and `Downcalls.resourceInfo` ends in `Status.check` (`Downcalls.java:240`),
so `Engine.epoch(staleHandle)` **throws at resolve** rather than returning
a mismatched epoch. The cached-vs-live epoch comparison can only fire
after a `u32` generation wrap on one slot.

**Consequence for naming, which is load-bearing for the doctrine text:**
resolution (a) does not deliver "epoch checking". It delivers a **native,
generation-checked liveness probe replacing a Java boolean** at the
cached-descriptor seam. Independent double-confirmation from two lenses is
the strongest evidence this council produced; the rest of this document is
downstream of it.

---

## 1. WHAT THE SPEC GOT WRONG (three corrections, each evidenced)

### C1 — §5's pre-registered disable-run is unfalsifiable as written

§5 requires "a test constructing same-slot-resource-reuse-while-cached"
to go RED with the recheck removed. Per §0, that fixture **cannot construct
a mismatch at all**; and if the test reaches slot reuse via
`store.close()`, the Java `closed` boolean throws identically with the
recheck removed — green under both arms (`RowStore.java:361-369`,
`Mask.java:124-127`). That is precisely this repo's own recorded
vacuous-disable class.

**Replacement falsifier (mandatory, replaces §5's):** close the NATIVE
resource while a live Java wrapper still holds `closed == false` — via the
existing `bench` split-package bridge (`RowStore.handle()` is
package-private but reached by `NativeAccess`, documented at
`RowStore.java:439-443`; `Engine.close(long)` is public within
`internal.ffm`, `Engine.java:73-75`), or via two wrappers over one handle.
Then read an already-resolved lane. With the probe → the
`ClosedResourceException`-shaped failure. **Disable-run:** remove the probe
→ `closed` is still `false`, the cached segment is read against freed
storage → RED. The assertion must distinguish the probe's failure from
`requireOpen`'s by type or message, or the test re-admits the vacuity it
replaces.

This falsifier is also the one case where the native probe is *provably*
better than the boolean, and it is reachable today, not hypothetical.

### C2 — the fallback export is not merely unnecessary, it is hazardous

`lgj_resource_info` is already a minor-1 symbol (`abi.md:394`) that reads
`entry.epoch` live from the registry, and `Engine.epoch(long)`
(`Engine.java:85-89`) already wraps it. **Resolution (a) needs zero new ABI
surface.** §3's surrounding prose risks implying a bump is needed
regardless; it is not.

Worse, per §0 the protection lives *in `resolve`*. An export minted to be
CHEAPER by skipping resolve would still pass a disable-run written against
the resolve-based version — both go red when the call is deleted — while
silently losing the guarantee. **Therefore:** the fallback is struck from
the resolution unless measurement forces it; if forced, its gate MUST
include a leg exercising the export's own stale-handle rejection, not
merely its presence.

If ever minted, the four ABI-citizen obligations stand and are confirmed
complete against how minors 9 and 10 actually landed: minor bump
(`abi.rs:72`), manifest + `abi.md` entry (`abi.md:146-164`),
`requireMinor(N)` at the call site (`Engine.java:277`, `:286`), and an
old-library rejection leg (`OldAbiCompatTest.java:144-145`). It must use
the lazy-holder pattern (`Downcalls.java:423-461`, minors 5+), never the
grandfathered eager one. It needs no new status code — reuse
`INVALID_HANDLE` (`-2`), consistent with minor 9's "no new status"
precedent (`abi.md:157-164`).

### C3 — "the banked benches are structurally blind" is FALSE, on `main`

The merged plan asserts it
(`mask-membrane-valhalla-integration-v1.md:170`) and spec §5 repeats it.
But `bench/src/main/java/.../G_HopExecutionBoundary.java:212,215,231,234,237`
calls `payloadHi32At` / `payloadLow64At` / `classidAt` directly, banked at
`bench/results/jmh-results-G.csv` including `rows=65536` — the exact
accessors on the exact fixture the protocol names. The blindness claim
holds for benches A–F and **not** for G's two `java_scalar_*` arms.

Two consequences: G is a **pre-existing before-number source**, and leaving
the false claim standing would let a real future regression in G be
dismissed as "structurally unrelated". This correction lands as a storno on
the merged plan, in the same commit as v3.

---

## 2. WHAT THE SPEC DIDN'T ASK, AND MUST (the consolidation's own finding)

**The two cached-descriptor sites are structurally asymmetric.** One savant
reported both classes guard before returning the cached window; another
reported `Mask.words()` has no guard. They conflict on the same lines, so
this consolidation read the source and settles it — the second is right,
and the fuller picture is more useful than either report:

| site | guard | granularity |
|---|---|---|
| `RowStore.lane(int)` (`RowStore.java:361-369`) | calls `requireOpen("row read")` at `:362` | runs on **every accessor call** — `classidAt`/`payloadLow64At`/`payloadHi32At` each call `lane()` |
| `Mask.words()` (`Mask.java:142-147`) | **none** | exactly one caller, `materializeRows()`, which guards once at `:102` and then loops the entire word lane at `:103-117` |

So a probe placed "at the seam" costs a native downcall **per row read** in
`RowStore` and **once per whole-mask scan** in `Mask`. This settles the
zero-copy lens's open question (per-resolve vs per-access): in `RowStore`,
`lane()` IS the per-access path, and placing the probe there is the
expensive shape the measurement lens warns about.

**Two sub-findings that are not this wave's job but must not be lost:**
`Mask.words()`'s missing guard is a defect in its own right (today it is
masked only by having a single guarded caller — a second caller would
inherit no guard); and `Mask.materializeRows()` guards once for an entire
scan, so even under a probe its exposure is one check per scan, which the
javadoc must state rather than imply.

---

## 3. THE ATOMICITY ARM — DECIDED: (ii), scoped contract

**Do not add a read/close lock or a native lifetime lease. Ship the probe
as stale-cache detection BETWEEN top-level facade calls, with the scope
written down.** Four independent reasons, from three lenses:

1. **Arm (i)'s mandatory falsifier cannot report red.**
   `LifetimeTest`/`RowStoreLifetimeTest` are single-threaded `main` +
   `Checks` (`RowStoreLifetimeTest.java:22-34`), and `grep` finds no
   `Thread` anywhere in `java/src/test`. An interleaving test's RED outcome
   here is a JVM crash or a silently wrong read — the "guard that cannot
   bark" class this repo forbids as evidence.
2. **Arm (i) would not close the hole it names.** `Engine.windowOf` builds
   `MemorySegment.ofAddress(addr).reinterpret(byteLen)`
   (`Engine.java:389`) — a global, unbounded-lifetime segment with no FFM
   scope check — and `Engine.close(handle)` is reachable outside the facade
   (`Engine.java:73-75`). A Java-side lock is sound only under an unstated
   "this object is the sole closer of this handle" assumption, which must be
   written down regardless. **That written assumption is arm (ii)'s
   deliverable.**
3. **The window is pre-existing, cross-thread-only, and already unguarded
   facade-wide.** `RowStore.closed` (`:35`, `:461`, `:469`) and
   `Mask.closed` (`:26`, `:127`, `:176`) are non-volatile and lock-free, so
   the current guard carries not only the same check-then-act race but a
   *visibility* race; the probe strictly narrows it, since its own read is a
   native acquire through the registry `RwLock`. The ABI states its own
   position: concurrency is unbenchmarked and "a concurrent Java writer
   would need a documented protocol, which this ABI version does not
   define" (`exports.rs:356-360`; cf. `abi.md:263-270`).
4. **Arm (i) invalidates the measurement gate.** An uncontended lock in a
   single-threaded JMH loop measures its best case, while the cost arm (i)
   exists to buy is cross-thread — measuring the cheap axis and shipping
   against the expensive one is the failure §5's own ordering rule exists to
   prevent.

**Noted, because it cuts the other way and must not be buried:**
`NativePattern` — the sibling class — already implements arm (i)'s exact
shape, wrapping `close()` in `synchronized (lock)` and re-checking
`requireOpen` inside the lock on every operation
(`NativePattern.java:122-144`, `:171-172`, `:180-181`, `:197-198`,
`:213-214`, `:225-226`), while `RowStore` and `Mask` have no lock at all.
That asymmetry is undocumented and gets a line in whatever ships. It is
weaker support for (i) than it looks, though: that lock guards only
handle-mediated `Engine.*` calls, which are already generation-checked
natively, and never a cached raw-segment read.

### What arm (ii) obliges — all five, or it is not (ii)

- **W1 — Javadoc scope, on both classes.** A thread-safety block on
  `RowStore` and `Mask`: the facade is not thread-safe; the caller must
  establish happens-before between `close()` and every access; a concurrent
  close-vs-access is undefined and **no guard detects it**. On
  `Mask.materializeRows()` specifically, state that the guard is checked
  once for the whole scan.
- **W2 — Doctrine wording matching that scope exactly.** In `CLAUDE.md`'s
  "Pointer value is not provenance" bullet: the cached-descriptor path is
  re-validated against the generation-checked registry **at each top-level
  facade call**; it is not atomic with respect to a concurrent `close`.
  Never "unconditional", never "on every dereference".
- **W3 — Name the mechanism honestly** (§0). The shipped thing is a native
  generation-checked liveness probe. If the epoch compare is retained as a
  second leg, its javadoc must say it can fire only under `u32` generation
  wrap, or a future session will read it as the load-bearing check.
- **W4 — Two issue entries, not one.** `ISS-LGJ-EPOCH-UNCHECKED` closes as
  **RESOLVED-SCOPED**; a NEW issue records the cross-thread
  lifecycle-vs-access window that (ii) documents rather than fixes, so it is
  not silently absorbed into a "resolved".
- **W5 — The deterministic falsifier of C1** replaces §5's, and is
  mandatory.

---

## 4. THE TWO LENSES THAT CAME BACK CLEAN (with one correction each)

**Zero-copy: CLEAN.** `Engine.epoch` (`Engine.java:85-89`) reads only the
per-thread `Scratch.info` segment (a `ThreadLocal.withInitial`, allocated
once per thread) and returns a `long` — no `new`, no array, no
`MemorySegment.copy`. It adds native crossings, not allocations, so no
materialization site and no fence-1 pin change (`DoctrineFenceTest.java:97-147`).
**Correction it carries:** caching the comparison RESULT (an obvious
"check once per call, not once per row" optimization) would be a **third
staleness authority** — derived from a stale read of the second one, so it
can diverge from the resource's current state between write and use. It is
forbidden; say so in v3 so nobody proposes it as a cost fix.

**Java surface: CLEAN.** The seam is two private methods; the failure type
`ClosedResourceException` is already what `RowStore.requireOpen`
(`:468-475`) and `Mask.requireUsable` (`:175-185`) throw, and it is a plain
public exception, not an FFM type — so reusing it needs zero public-surface
change. Use the same type with a **distinct message**: still-open-but-stale
is a different condition from closed, and reusing the closed narrative
would mislead. **Correction it carries:** fence 2b does NOT backstop a
leaking freshness method — it rejects only *topology* name stems
(`DoctrineFenceTest.java:330-367`), so a public `isStale()`/`validate()`
would sail through. The actual guard is `ApiSurfaceTest` plus keeping the
seam private. (This corrects an assumption in the savant's own brief.)

---

## 5. THE MEASUREMENT GATE IS NOT CURRENTLY DECIDABLE

§5's 2× threshold cannot be evaluated today, for reasons that are
themselves deliverables:

1. **No isolated per-accessor benchmark exists anywhere in `bench/`** — the
   accessor's own baseline has never been measured. Bench G is
   accessor-dominated but measures a whole hop boundary, not the accessor.
2. **The banked data cannot resolve a 2× effect.** `jmh-results-G.csv`,
   `java_scalar_classidScan`, at `@Fork(1)` / 3 warmup / 5 measurement
   (`G_HopExecutionBoundary.java:92-94`): `25.639505 ± 61.918845` us/op
   (error **2.4× the score**) and `269.635512 ± 229.450931` (85%) at
   rows=4096; the rows=65536 arms are better but still
   `180.563246 ± 70.114359` (39%). A 2× criterion is not resolvable at that
   dispersion — and note these are whole-hop numbers, so the accessor's own
   dispersion is unknown, not merely large.
3. **The ratio is well-posed but the units are not.** Numerator and
   denominator are the same accessor, so this is NOT the retracted
   isolated-stage-over-whole-pipeline error. But the baseline is an
   in-process `segment.get(...)` (`RowStore.java:397/417/435`, single-digit
   ns) against a full downcall + `Status.check`, so a 2× ratio is a
   difference timer and JIT noise can produce unaided.
4. **The measured delta is not "the compare".** A downcall inside the
   accessor is an inlining/compile barrier that changes the surrounding
   loop's optimization. That total is the right thing to gate on, but must
   be labelled as such, and results must be consumed (Blackhole) or one arm
   gets eliminated and the ratio is manufactured.
5. **Variant swapping must be build-time.** An `if (guardEnabled)` branch
   inside `classidAt` is itself hoistable and is not the shape either arm
   ships.

**Therefore v3 must add, as named deliverables the spec omits:** an
isolated per-accessor JMH benchmark with build-time variant swapping and
Blackhole consumption, at `@Fork > 1`; and an **absolute ns budget beside
the 2× ratio**, because a low-single-digit-ns baseline makes a bare ratio
undecidable. Until those exist, neither (a) nor (b) can be selected on
measurement — and selecting on anything else would be the
gate-observability failure the plan's own standing question names.

---

## 6. THE REVISED RESOLUTION (what v3 would ratify)

Ship **(a), renamed and re-scoped**, under arm **(ii)**:

- A native generation-checked liveness probe at the cached-descriptor seam,
  replacing the `closed` boolean's sole authority. **No new ABI symbol.**
- `Mask`: probe at `words()` or at `materializeRows()`'s existing guard —
  cheap, once per scan.
- `RowStore`: placement is the **one open engineering question** (§7 Q1),
  because `lane()` runs per accessor call.
- Arm (ii)'s five obligations W1–W5.
- C1's falsifier replacing §5's; C2's fallback strike; C3's storno on the
  merged plan.
- §5's threshold work (§5 items 1-5) as a prerequisite, not an afterthought.

## 7. OPEN QUESTIONS FOR PHASE 3

- **Q1 (the real one).** In `RowStore`, does the probe go per-access (in
  `lane()`, full guarantee, one downcall per row read) or at lane
  first-resolve (nearly free, but blind to a close that happens after the
  lane is cached — which is exactly C1's reachable scenario)? The guarantee
  and the cost point in opposite directions and no measurement exists yet
  (§5). This is where a reviewer should push hardest.
- **Q2.** Does `Mask.words()`'s missing guard get fixed in this wave, or
  filed separately? It is currently masked by having one guarded caller.
- **Q3.** Should the 2× ratio be replaced outright by an absolute ns budget,
  given §5.3?
- **Q4.** Is `NativePattern`'s locked-close asymmetry (§3) worth
  normalizing, or worth documenting as deliberate?

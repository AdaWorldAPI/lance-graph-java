# mask-membrane-valhalla-integration-v1 — the layered integration plan

**Status: PROPOSED (2026-08-28).** Written after the PR #44→#46 arc closed:
the mask algebra restored and made fast (minor 10 columnar store, 3.8–5.9×
through the real ABI), the simd.rs isomorphism pinned as CLAUDE.md's
ENFORCEMENT LAYER (E1–E6), the zero-copy/memory-safety doctrine pinned and
then council-corrected (5+3, PR #46), and the 64K execution end measured
in-tree (`exp-kia-a2-64k-fresh-run.txt`). This plan is the consolidation:
it names each layer's finished state, its open honesty gaps, and the wave
order that closes them — **masking underneath, Panama the membrane,
Valhalla cheap addresses, everything else underneath.**

## The layer model (frozen — this plan implements it, never re-litigates it)

```text
Application / TinkerPop         semantic intent: where()/hop()/out()/has()
        │                       (vocabulary only — never execution)
        ▼
lance-graph-java (facade)       ndarray's simd.rs, one level up: 37-fns-
        │                       0-instructions discipline. Names things a
        │                       backend already does (E5). Never computes
        │                       (E1). Scalar only as cfg(test) oracle (E2).
        ▼
Valhalla                        cheap ABI-shaped ADDRESSES: FacetId, Mask
        │                       handle, ProjectionHint, LaneDesc-shaped
        │                       values. Describe/address the one native
        │                       substrate; never own enough state to
        │                       become a second one.
        ▼
Panama (internal/ffm)           THE MEMBRANE. Shape crosses. Meaning
        │                       crosses. Operations cross. Ownership does
        │                       not cross. Descriptors, layout contract,
        │                       generation-checked handles, manifest-first
        │                       handshake. FFM types quarantined here
════════╪═══════ membrane ══════ (ApiSurfaceTest = this repo's simd-savant)
        ▼
lgj-abi (Rust)                  MASK ALGEBRA IS THE EXECUTION CURRENCY.
        │                       hop = MASK × CLASSVIEW/WIDEFIELDMASK →
        │                       MASK, word-parallel, layout-blind Java
        │                       above (minor 10). Bulk or lifecycle, ∝
        │                       n_rows, never per-row.
        ▼
ndarray::simd                   backends (AVX-512/AVX2/NEON/scalar). All
        │                       SIMD from the polyfill, never hpc::*,
        │                       never raw intrinsics. Selection is
        │                       ndarray's business — invisible above.
        ▼
lance-graph substrate           EVERYTHING ELSE UNDERNEATH: placement
                                (row_of(owner)-shaped landing), publication
                                (freeze → coalesce → SOLE writer → one
                                DatasetVersion), worker topology (an
                                internal scheduling variable, never a
                                consumer API), the sealed temporal horizon.
```

Frozen decisions this diagram carries (cited, not re-arguable here):

- F1. The mask-native invariant + named exceptions (Import/Materialisation)
  — `CLAUDE.md` § mask-native invariant, operator-ruled 2026-08-18.
- F2. The simd.rs isomorphism E1–E6 — `CLAUDE.md` § ENFORCEMENT LAYER,
  operator-ruled 2026-08-27 (`E-JAVA-IS-SIMD-RS-VALHALLA-PANAMA-IS-THE-
  POLYFILL-1`).
- F3. Zero-copy + memory safety, as **corrected by PR #46** — the scoped
  wording is canonical (handle-mediated registry check; minors 2-4 as the
  tracked `requireMinor` exception; the five-item exhaustive
  materialization list; the Import carve-out on `segment.set`).
- F4. Ownership constitution + §E worker-topology rule — the operator's
  enforcement pass, audited clean 2026-08-27: no `workers(`/`workerCount`/
  `parallelism(` ever becomes consumer semantics; EXP-KIA's worker sweep
  stays a native benchmark independent variable.
- F5. GridLake (the generic parallel write API) stays **BLOCKED until
  deterministic landing identity is independent of producer arrival order**
  — `mask-native-navigation-correction-v1.md` §10; no placement algorithm
  enters the Java ABI.
- F6. The missing-capability STOP rule — a needed primitive lands
  substrate-first (`ndarray::simd` → lgj-abi → membrane → facade), never
  hand-rolled a layer up. Three-times-proven (D-LGJ-SWEEP-5/6, W6/W7).
- F7. The measured ground truth for "everything else underneath":
  compute parallelizes 3.27× @ workers=8 but the cast/collect/wal/apply
  convergence tail is FLAT across worker count and is ~90% of a steady
  cycle (`E-EXP-KIA-A2-64K-CONVERGENCE-TAIL-DOMINATES-1`). Any seam that
  only accelerates compute optimizes the wrong ~10%.

## Where each layer stands (measured, not asserted)

| Layer | Done | Open (the honesty gaps, all council-named) |
|---|---|---|
| Facade | layout-blind (disable-run red at first divergent address); geometry derived from `Layouts` only (J2 closed); no compute path (G2 + reflective allowlist) | nothing structural — grows only names for W2/W4 verbs |
| Valhalla | descriptors are already ABI-shaped records (`LaneWindow`, `LgjLaneDesc` mirror); three-truths lab method stands | value-class promotion is a LAB question until measured (E4 keeps Vector API permanently lab) |
| Panama | manifest-first handshake (magic→major→minor→sizes→endianness); generation-checked handles, 23/23 registry falsifiers green; FFM quarantine reflection-enforced | **G-A**: cached-descriptor path is `closed`-boolean-guarded only — `epoch` field designed, unconsulted (`ISS-LGJ-EPOCH-UNCHECKED`). **G-B**: minors 2-4 fail at `Downcalls.<clinit>`, not at the `requireMinor` call |
| Mask algebra | hop = `src ∧ class_f ∧ struct_f` word-parallel (R1); facet-match reduction native (minor 9); columnar store (minor 10) 3.8–5.9× over AoS through the real ABI (3.8× all-rows / 4.7× classid / 5.9× hop2 — the full measured range, slowest arm included) | **G-C**: fused single-plane columnar pass (~10× further per the lab arm, zero new kernels). **G-D**: register-sweep family answers columnar with an honest −18, not a result |
| ndarray::simd | every kernel routes through the polyfill; backend diagnostic-only above | any new mask primitive (e.g. `mask_xor`) lands HERE first (F6) |
| Substrate | 64K compute proven parallel + digest-stable; sole-writer publication sequential-by-design | **G-E**: the convergence tail is where the time is (F7); the seam waits on the F5 landing-key gate — substrate-side work, never this repo's |

## The waves

Ordering rule: close the membrane's safety story first (a fast path over a
membrane with named holes is backwards), then finish the mask layer's speed
story, then let the address layer and the substrate seam follow evidence.
Every wave lands with board hygiene same-commit, disable-runs
red-then-green, and NO new consumer-facing topology/backend semantics
(F2/F4 gate every wave, mechanically).

### W0 — mechanical fences for the doctrine (cheap, do first)

The PR #46 corrections turned three prose claims into checkable
properties. Make them tests so they cannot silently drift again:

- **W0.1** Materialization-list gate: a committed check (test or CI grep
  with an allowlist) that fails when a sixth production
  `long[]`/`copyOf`/`toArray` site appears outside the five named ones —
  the enumeration went stale once inside the very tree it audited; a list
  without a gate is a hand-maintained artifact with extra steps.
- **W0.2** Worker-topology fence: the §E grep
  (`workers(`/`workerCount`/`parallelism(`/`threads(`/`partitions(`/
  `shards(` over `java/src/main` + ABI structs/exports) as an executable
  check beside `ApiSurfaceTest`, not a session ritual.
- **W0.3** SIMD-branch fence: assert no `src/main` conditional consumes
  `simdBackend()` (diagnostic-only, F2/E4).
- Gate: each fence proven able to fire (plant the violation, watch it go
  red, remove it) — the falsifiability rule, applied to the fences
  themselves.

**Scope honesty (added after the PR #48 review round):** the lexical
fences are TRIPWIRES, not proofs. Every evasion finding the reviews
produced (Codex ×3, CodeRabbit ×3) was one finding in six costumes — a
text pattern evaded by a legal spelling it did not anticipate. What the
fences actually guarantee: the accidental violation and the lazy evasion
fail loudly, and the census pins force a determined evasion to touch the
pin table in the same diff, which makes it reviewable. Where the fenced
property is visible in the COMPILED surface, the fence gets a reflective
arm instead (fence 2b: no topology-named public method — proven
non-redundant by a name/paren split across two lines that only the
reflective arm caught); where it exists only in source (call sites,
branches), lexical + census is the honest ceiling, and review + wording
discipline remain the last layer.

### W1 — close the membrane (G-A + G-B)

- **W1.1 Epoch re-check** (the Phase-0 council spec is COMMITTED at
  `.claude/plans/epoch-recheck-phase0-v1.md` — frozen decisions, input
  inventory, both accepted resolutions, pre-registered gates, and the
  five per-savant question sets live there, not in a session transcript).
  Target resolution (a): compare the cached `LaneWindow.epoch` against the
  live resource epoch on the access path (`Engine.epoch(handle)` exists;
  a lighter epoch-only export is the fallback IF measurement shows the
  full 32-byte `lgj_resource_info` read is too costly per access — and if
  that export is minted it is a **full ABI citizen**: its own minor bump,
  manifest + `abi.md` entry, `requireMinor(N)` at the Java call site, and
  an old-library rejection leg in `OldAbiCompatTest`, or the fallback
  recreates the missing-symbol failure class W1 exists to remove).
  The epoch fetch and the cached read are two steps — the council's
  spec carries the check-then-read ATOMICITY constraint as a mandatory
  decision output (serialize/lease with an interleaving falsifier, or a
  written scoped contract; the spec's §3 "Atomicity constraint" is
  authoritative — a CodeRabbit review addition, and the race is
  pre-existing in the `closed`-boolean guard, cross-thread-only).
  Resolution (a) resolves `ISS-LGJ-EPOCH-UNCHECKED` at the scope the
  chosen atomicity arm actually delivers — the doctrine wording matches
  that scope, never exceeds it.
  Fallback (b) — formal unreachability proof + doctrine
  downgrade — only on measured cost, never on assumption; under (b) the
  issue closes as **DOWNGRADED-DOCUMENTED, never "resolved"**, and the
  doctrine's scope-note stays live (see the W1-completion paragraph).
  **The measurement target is the cached-descriptor accessors THEMSELVES**
  (`RowStore.classidAt`/`payloadLow64At`/`payloadHi32At` through
  `lane()`, `Mask`'s cached-`words` reads) — a Codex review correction
  (PR #47): the banked hop/columnar benchmarks run entirely through
  native operations and never touch the cached-descriptor path, so they
  are structurally blind to per-access epoch overhead and would stay
  unchanged even if every accessor gained a native crossing. A
  before/after micro-measure of the actual accessors is the gate; the
  banked native benches remain only a regression backstop for the
  operations they do exercise.
  Gates: a disable-run test constructing same-slot-reuse-while-cached goes
  red with the check off, green with it on; all existing
  `LifetimeTest`/`RowStoreLifetimeTest` falsifiers stay green; measured
  per-accessor overhead banked before/after.
- **W1.2 Lazy-holder migration for minors 2-4**: move the eager
  `MethodHandle` resolution for row store/edges/hop into per-minor lazy
  nested holders (the pattern minors 5+ already use), so an ABI-0.1
  library fails at `requireMinor(N)` with a clean message, at every minor.
  **The structural requirement, stated as the invariant** (a CodeRabbit
  addition, PR #47): every public path entering a minor-N capability
  calls `Abi.requireMinor(N)` BEFORE its first reference to that minor's
  lazy holder — a holder touched first re-creates the clinit failure one
  step later.
  Gates: `OldAbiCompatTest` gains the minors-2-4 legs it structurally
  could not have before (build a real minor-1 library in a worktree
  beside the siblings — /tmp worktrees break the path deps, measured),
  with **one independent negative probe per minor** — the existing
  minor-4 gate rides `Mask.minus` and cannot discriminate alone, so
  either each minor gets its own probe or the "at every minor" claim is
  narrowed to the minors actually probed; an unprobed minor may not be
  claimed covered.
- **On W1 completion under resolution (a) for W1.1**: the two scope-notes
  PR #46 added to the doctrine become historical (marked resolved in
  place per append-only rules), and the membrane's safety story is
  unconditional for the first time. **Under fallback (b) this paragraph
  does NOT apply**: the doctrine keeps its scoped wording permanently,
  the cached-descriptor scope-note stays live, and no "unconditional"
  claim is made anywhere — a measured downgrade is a documented boundary,
  not a closed gap.

### W2 — finish the mask layer (G-C + G-D)

- **W2.1 Fused single-plane pass**: the lab arm measured ~10× further
  headroom over the shipped columnar hop by fusing the classid/struct
  predicates into one plane traversal — zero new kernels claimed by the
  lab arm; verify that claim against the real ABI the way minor 10 was
  verified (equivalence asserted BEFORE timing, pinned hop identical,
  bytes-differ anti-vacuity). If a new primitive IS needed after all, it
  lands in `ndarray::simd` first (F6) with backend/tail parity before any
  ABI exposure.
- **W2.2 Register sweeps over facet-major**: replace the −18
  `UNSUPPORTED_LAYOUT` refusal with a real columnar answer for
  `lgj_reduce_facet_sum`/`_resolved`/`lgj_row_layout_probe`. The refusal
  was the honest placeholder; the lanes (33–97) already serve every field
  contiguously, so the sweep becomes a contiguous-lane reduction. The −18
  path STAYS reachable for genuinely unsupported future layouts — re-pin
  the two-sided test rather than deleting it.
- Gates: byte-identical results AoS vs columnar on every sweep (two
  independent oracles, the minor-9 discipline); banked bench extended
  with the sweep arms; ABI minor bump per additive symbol/behavior;
  `abi.md` sections in the same commit.

### W3 — Valhalla: cheap addresses, on evidence only

- Scope: promote the descriptor vocabulary (`FacetId`, `MaskId`-shaped
  handles, `ProjectionHint`, carving ordinals) to Valhalla value classes
  where the lab's three-truths method shows a real win (flattening,
  scalarization, allocation-free composition) — the ownership test from
  the enforcement pass decides admissibility: *does the value
  describe/address the one native substrate, or does Java now own enough
  state to be a second substrate?* Describe → allow. Reproduce → STOP.
- Non-goals, permanent: Vector API in `src/main` (E4); any public
  signature carrying FFM or `internal.*` types (E6); constrained value
  types that a user can fabricate into a valid-looking native identity
  (raw `long` constructors stay package-private/native-minted).
- Gates: R4/R10-style measured before/after on the real facade paths (the
  substrate half — carving groups ≤4 B, 512/region/block 64-alignment —
  is already pinned as tests from minor 10); JDK line pinned per
  `r12-jdk-line` conventions; every promotion individually reversible.

### W4 — everything else underneath: the substrate seam, measurement-gated

- **W4.1 Read side (unblocked now)**: bind the sealed temporal horizon
  through the membrane using the contract's CLEAN vocabulary —
  `LanceVersion`/`VersionRange`/`TemporalPov` — never the engine's
  Kanban-entangled types (frozen in
  `mask-native-navigation-correction-v1.md` §8.1). A Java consumer asks
  "as of which sealed version" as a descriptor; the substrate resolves it.
  Substrate-first: whatever version-pinned read primitive lgj-abi needs
  goes through F6.
- **W4.2 Write side (BLOCKED, and this plan keeps it blocked)**: the
  F7 measurement says the convergence tail (cast/collect/wal/apply,
  sequential by design, sole writer) is ~90% of the cycle — so the
  valuable seam work is on the SUBSTRATE side of the membrane
  (lance-graph's landing-key gate, F5), not here. lance-graph-java's
  whole write story remains: stage semantic intent, cross as descriptors,
  the sole native writer lands and publishes. No batch/commit verbs enter
  the facade until F5's gate resolves upstream; when it does, the verbs
  arrive as names for what the substrate then already does (E5).
- Gate for W4.1: crossing count ∝ queries not rows; a version-pinned read
  proven byte-stable against a later write (the time-machine falsifier);
  zero new Java-side state beyond the descriptor.

## D-ids (STATUS_BOARD rows land with this plan's PR)

| D-id | Deliverable | Wave |
|---|---|---|
| D-LGJ-MMV-0 | The three mechanical fences, each proven able to fire | W0 |
| D-LGJ-MMV-1a | Epoch re-check wired (or measured-and-proven fallback), `ISS-LGJ-EPOCH-UNCHECKED` resolved | W1 |
| D-LGJ-MMV-1b | Minors 2-4 lazy holders + `OldAbiCompatTest` minor-1 leg; doctrine wording restored by storno | W1 |
| D-LGJ-MMV-2a | Fused single-plane columnar pass, equivalence-then-timing | W2 |
| D-LGJ-MMV-2b | Register sweeps answer facet-major; −18 re-pinned two-sided | W2 |
| D-LGJ-MMV-3 | Valhalla value-class promotions, individually measured + reversible | W3 |
| D-LGJ-MMV-4 | Temporal read binding via contract vocabulary | W4.1 |

W4.2 deliberately has no D-id: it is not this repo's work until F5
resolves upstream, and giving it a row here would invite exactly the
consumer-side scheduling creep F4 forbids.

## What would falsify this plan's ordering

- The W1/W2 flip condition, in reproducible units (a CodeRabbit
  correction — "costs more than the fused pass gains" compared two
  different denominators): on the SAME 65,536-row fixture, W1.1's number
  is ns/accessor-call over ≥1M calls on the cached-descriptor accessors,
  median of 5; the flip fires iff the epoch re-check cannot be brought
  under **2× the accessor's measured baseline** even via the epoch-only
  export. The measured quantity is the re-check UNDER THE CHOSEN
  atomicity arm (the council spec's §3) — a lock/lease per access is a
  different cost than a bare compare, so the atomicity decision precedes
  the measurement, and an arm-(i) choice legitimately raises the flip's
  probability; the flip firing under (i) is a valid outcome, not a
  failure of the plan. In that case W1.1 pauses on its (a)/(b) decision while W2.1
  proceeds — for its OWN paths only: W2's fused pass and sweeps run
  through native operations, not the cached-descriptor accessors, so
  they may be investigated and shipped regardless; nothing that READS
  through a cached descriptor ships an "unconditional safety" claim
  while W1.1 is unresolved. Recorded as a dated amendment here, not
  silently.
- If the W2.1 lab claim (~10×, zero new kernels) does not survive
  real-ABI verification, W2.1 re-enters through F6 as an ndarray::simd
  primitive proposal — the plan does not pre-authorize a membrane-side
  workaround.
- If Valhalla's measured wins in W3 are nil on the real facade paths, W3
  closes as "lab-confirmed, production-deferred" — a valid outcome, not a
  failure; the addresses are already cheap enough to have hit every
  allocation gate.

**Standing review question for every future gate in this plan** (the
review round's recurring failure class, three-for-three: benchmarks that
structurally could not see per-accessor overhead, vacuous falsifiers,
whitespace-evadable fences): *can this gate observe the violation it
names, through the path a violation would actually take?* A gate that
cannot is worse than none — it certifies. Every new gate answers this
question in its own description before it counts as a gate.

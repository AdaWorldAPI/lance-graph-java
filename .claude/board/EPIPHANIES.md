# Epiphanies Log — Findings, Corrections, "Aha" Moments (APPEND-ONLY)

> Prepend new entries at the top. Never edit a past entry except its
> `**Status:**`/`**Confidence:**` line. A correction gets its own new,
> dated entry that references the one it corrects — the storno rule.

## 2026-08-18 (measured) — the graph-consumer STOP condition was real, and is now cleared

**Status:** FINDING + a correction of MY OWN earlier claim. **Confidence:**
High — measured (`examples/graph_density_probe.rs`), not argued.

### What "in the meantime" surfaced

Asked what was available to build while `ruff_r2il` PR2/PR3 are blocked.
First instinct was W5c (graph consumer) — I'd twice previously argued its
D1a design needs zero substrate change (mask words are writable, facet-
match already exists). Both times I checked the MECHANISM and skipped
`wave-consumer-graph.md`'s own STOP condition, which names a DIFFERENT,
real blocker: `RowStore::generate()`'s payload is uniform-random noise, so
a decoded 1-2 hop BFS over it saturates to nearly every row regardless of
decode convention — vacuous under the wave's own falsifier #4 ("seed / 1-
hop / 2-hop must be three different, non-empty, non-total sizes"). No
mechanism fix (writable masks, zero-copy reads) touches this; it is a
DATA-SHAPE problem, not a decode-ambiguity problem, and the wave file says
so explicitly: *"this wave NEEDS a deliberate edge-bearing generator arm:
that is a substrate change, not a consumer hack."* I was about to dispatch
G1/G2 into it before re-reading the STOP block in full — caught before any
workers spawned.

### The fix, measured before committing to parameters

`RowStore::generate_with_edges(n_rows, seed, edge_classid, edge_gate_mask,
edge_radius)` (native/lgj-abi/src/rowstore.rs) — additive, `generate()`
untouched. Classid assignment is byte-identical to `generate()` (same
SplitMix64 draws, same `(a>>>33)&0xF` formula; unused bits 37..64 of `a`
become an independent sparsity gate, so `edge_classid=16` — out of range —
reproduces `generate()` exactly, pinned by test). A gated, sparse subset of
`edge_classid`-matching facets get a BOUNDED local-neighbourhood target
(`row + offset mod n`, `offset` drawn from `b`, clamped to `±edge_radius`)
instead of raw noise.

`examples/graph_density_probe.rs` swept `(gate_mask, radius)` before any
parameter was chosen — first pass at `n_rows=1000` was too small (avg
degree < 1, everything collapsed to zero); widened to `n_rows=20_000` and
got real, usable numbers (`gate_mask=0x0, radius=25`: seed=20 → 1hop=30 →
2hop=40). Re-measured at a smaller, test-suite-friendly `n_rows=2000` for
the pinned regression: seed=10 → **1hop=19 → 2hop=29** — three different,
non-empty, non-total sizes, exactly the falsifier's own shape, now pinned
as `measured_hop_counts_are_three_distinct_non_empty_non_total_sizes`.

**Two disable-runs, both as specified:**
- Broke the radius-wrap formula (dropped `rem_euclid`) → exactly the three
  tests touching the target formula went red (the transcription test, the
  in-bounds/radius invariant, the pinned hop-count regression); the seven
  tests that don't touch target computation stayed green. Restored.
- Ignored the sparsity gate mask (fired on classid match alone) → only the
  transcription test went red. The in-bounds/radius invariant test
  correctly stayed green — density and target-correctness are orthogonal
  properties, and the disable changes density, not correctness. Verified
  this is the RIGHT outcome, not a vacuous test: geometry validity doesn't
  depend on WHICH facets get the treatment, only on the treatment itself
  once applied. Restored.

Gates: `lgj-abi` 90/90 (was 84; +6 new tests), fmt clean, clippy
`--all-targets --all-features` clean.

### Consequence

`wave-consumer-graph.md` updated in place: the STOP condition marked
RESOLVED with the measured numbers, and the file's own dispatch header
corrected — the "calcify, do not dispatch" gate was already lifted
session-wide (W5a/W5b shipped under the identical wording); this wave's
GENUINE extra gate was the generator, now cleared. **The graph consumer
(G1/G2) is dispatchable.** Not dispatched in this same pass — this PR is
scoped to the substrate-tier generator only, per the wave file's own rule
that a generator extension is NOT a consumer hack and lands as its own
change.

## 2026-08-18 (R2IL handshake) — E-LGJ-VALHALLA-IS-INTEGRATED-AS-A-PROPERTY-NOT-A-CONCEPT-1

**Status:** FINDING + a storno of my own handoff premise (operator-caught).
**Confidence:** High — verified against the tree, not recalled.

### The handshake, first

The R2IL session answered all five questions of the cross-session prompt:
(1) `0xC4` acknowledged as a fixed point — PR3 mints INTO it, provenance
fence as specified, concept names/slots arrive in the PR body; (2) the
stale `ogar_codebook` mirror confirmed first-hand at lance-graph `db488f5`
— and the sync is explicitly handed to THIS session ("don't wait on me...
open it separately, now"; serialize on one owner, which is now me); (3)
commitment: PR2 ships an abi.md-§11-style layout doc IN the same PR, and
the two ⚠ stability flags flip in that same commit — build against the
doc, never against `furnace.rs`; (4) `0xC0` is **Panama alone** — Valhalla
gets no domain representation; (5) a Java-side consumer IS the expected
end-state: `consumers/ghidra/` beside `trades/` and `bricks/`, gated on
(2)+(3) — shape W6/W7 toward it, keep the read-only fence until the PR2
doc exists and PR3's classids are real. Status note: the ruff session is
mid upstream-catch-up merge (~1500 commits, separate branch); PR2→PR3
queue after it settles.

### The storno — my premise was understated, the ruling survives anyway

My handoff prompt told the R2IL session Valhalla "was a laboratory phase
here, not a door." The operator demanded a double-check, and the tree says
that summary was WRONG about integration while right about addressability:

- **Valhalla IS integrated, by design, in the shipping API.** All five
  production descriptor types (`LaneId`/`Ordinal`/`MaskId`/`RowRange`/
  `FacetId`) carry a "Valhalla A/B candidate" Javadoc contract: the same
  source compiles as `value record` under JEP 401 — migration is ONE WORD
  per type. That constraint is load-bearing on the shipping surface; it is
  the arc's "Panama and Valhalla become the supraconductor" request
  honored at the vocabulary level.
- **A real EA build ran the A/B** (`27-jep401ea3`, in-container):
  flattening cliff measured at 8-byte payload (`RowRange` at 16 B landing
  on the wrong side, recorded as the one over-optimistic expectation);
  `LaneId`/`Ordinal` arrays 5.5× smaller, reads up to 8.3× faster where
  flattening applies; the bulk thesis unchanged — native wins 38–57× on
  BOTH platforms, which is exactly why bulk data stays native and only the
  descriptor vocabulary is Valhalla-shaped.
- **Deliberately NOT adopted:** the three preview-only mechanisms (no
  `--add-exports`, no `jdk.internal.*`) — "distorting a public API to fit
  a preview VM's current budget would bake a temporary constraint into a
  permanent surface."

**Why the ruling stands on the corrected premise:** a `ConceptDomain` is a
vocabulary of ADDRESSABLE things. Valhalla's integration here is a designed
PROPERTY of the C0 concepts' Java vocabulary (one-word readiness +
measured flattening payoff), and properties of concepts do not get
domains. The R2IL session's own phrase — "a facet on an existing concept,
not a domain" — described the true state better than my premise did.

**Landed:** OGAR PR #277 (merged, `386a6fd`) corrects the `JavaRuntime`
doc comment to "Panama FFM alone," states the integrated-as-property
argument IN the doc so the ruling cannot be misread as "Valhalla is
unintegrated," updates the layout-doc band row, and APPENDS a dated
correction to `D-CBAND-ALTITUDE` (original text kept, per append-only).

**The meta-lesson, same family as the shape-vs-altitude storno:** a
one-line characterization written to justify a conclusion can be
simultaneously right about the conclusion and wrong as a description —
and it is the DESCRIPTION that calcifies when quoted into doc comments.
The operator's "double check whether you didn't integrate it or the other
session just isn't aware" is the exact question that separates the two.

### Now owned by this session (from the handshake)

1. **The `ogar_codebook` mirror sync in lance-graph-contract** — add
   `Ontology`(0x03)/`Blocks`(0x17)/`JavaRuntime`(0xC0)/`Analytics`(0xC1)/
   `BinaryLifting`(0xC4) to the wire-mirror + parity pins. Opening now.
2. **Shape W6/W7 toward `consumers/ghidra/`** — with the read-only fence
   held until PR2's layout doc + PR3's real classids exist.

## 2026-08-18 (even later) — ruff #96 is a DIFFERENT arm; the real find was already on main: a staging guide addressed to THIS session

**Status:** FINDING. **Confidence:** High — read the merged PR body and the
in-tree harvest artifacts directly, not summarized.

### PR #96 is not the drill-down proposer this repo is waiting on

`AdaWorldAPI/ruff` PR #96 ("residual ledger for the plain arm") extends
`ruff_python_spo`'s PLAIN Python-source harvest (PR #95: dismech/A2UI-sdk/
ruff-scripts corpora, CURIE-shaped constants — `MONDO`/`KISAO`/`infores`
prefixes, bio-ontology work). It is its own "self-adaptive drill loop," but
over a **different crate, different corpus family, different consumer**
(ontology/MedCare-rs-shaped harvest) than `ruff_r2il` (binary/R2IL lifting,
the one `E-LGJ-GHIDRA-G1-G2-SUPERSEDED-BY-R2IL-1` tracks). Zero overlap with
this repo's C-band/Ghidra/JavaRuntime concerns — recorded here only so a
future session doesn't wire a false connection between the two arms because
they share vocabulary ("residual ledger," "drill loop," "proposer").

### What IS relevant, sitting on `ruff` main since a same-day, non-PR commit

Commit `bbaebda` (between PR #94 and PR #95, pushed directly to main) added
`.claude/harvest/r2il/STAGED-CODEGEN-GUIDE.md`, explicitly addressed:
*"Audience: the sibling session that consumes this arc's output (the Ghidra
console work...)"* — this repo, by description if not by name. It confirms
what the prior reconciliation already found (PR 2 routes→V3 has NOT landed)
and adds the piece that entry lacked: **a 5-stage staging order (S1–S5) that
does NOT wait on PR 2**, with "do not skip to S3" stated plainly (S3 is
codegen into an additive landing zone; S1/S2 are read-only ledger/ore
inspection). Also pins the stability table per artifact — `FlatFact`'s two
payload slots and the provisional `VarnodeFacet` classid (`0x0000`,
placeholder for the PR-3 `ogar_codebook` mint) are explicitly **not**
stable; slag/census/provenance/convention are.

### S1 done — read the ledger, no codegen, no PR2 dependency

The pass-1 harvest artifacts are already in-tree at `ruff/.claude/harvest/
r2il/` (gitignored, present on disk). Read directly:

- **B1 conservation: PASS** — `dropped=0`, `harvested(54304) =
  classified(17557) + residual(36747)`.
- **B2 seven-opcode coverage: INVESTIGATE (91.30%)** — inside the declared
  90–99% band, not a KILL.
- **B3 slag named-and-addressed: PASS** — 43 distinct residual shapes,
  `dominant_share = 0.215` (well under the 0.60 ceiling), every bucket
  except `no_facet_coordinate` carries an example address.
- **The non-bar prediction MISSED, and was recorded honestly rather than
  hidden**: only 14.15% of `Op` facts classified, against a pre-registered
  60–80% guess. Dominant residual reason is `opcode_not_in_convention` —
  expected, since pass 1 deliberately classifies only 7 of P-code's 74
  opcodes (Copy/IntAdd/Load/Store/CBranch/Call/Return); the other ~67 are
  correctly unclassified, not mis-measured.
- **Corpus is r2sleigh's own e2e stress-test fixtures** (143 functions,
  x86-64, commit `60942f6`), not yet a Ghidra-shaped real binary — still a
  bring-up-scale run, not production scale.

### Consequence: still nothing to physically consume, and that's correct

`wave-ghidra-g1-g2.md` / `wave-ogar-machine-pm1.md` gate #3 are unchanged —
PR2/PR3 remain unmerged, and the stability table says explicitly not to
persist `FlatFact` payload bytes or the placeholder classid yet. The
concrete, unblocked next step for this repo — whenever there is a driving
reason to spend it, not scheduled here — is **S2** (join ore rows back to
native addresses via `ore::instruction_addr`, still read-only) as
preparation, so S3 (an additive landing-zone crate here, `// @generated`,
never edited into existing files) is measured before it is built, per the
guide's own "the MedCare and OpenProject transcodes earned their numbers by
measuring at S1/S2 first" precedent.

## 2026-08-18 (later) — E-LGJ-GHIDRA-G1-G2-SUPERSEDED-BY-R2IL-1

**Status:** FINDING (reconciliation, per `ghidra-integration-v1.md`'s own
HANDOFF BOUNDARY note: *"the receiving session should reconcile the handoff
against this plan's G-waves — they may supersede G1's lift-path decision
entirely — rather than running both designs in parallel."*). **Confidence:**
High — read against the merged PR, not inferred from the operator's summary.

### What landed elsewhere, verified directly

`AdaWorldAPI/ruff` PR #94 (merged, `10fab88`'s ancestor) shipped
`crates/ruff_r2il`: a typed intake arm reading r2sleigh's R2IL/SSA directly
(`../../../r2sleigh/crates/{r2il,r2ssa}`, in-process, ~43s) into
`ore → furnace → slag`. `dropped == 0` by construction
(`harvested = classified + residual`); slag is a **named, addressed**
residual ledger (`ResidualLedger::by_address`), not a catch-all — and B3's
own falsifier makes `residual == 0` a **KILL**, meaning the ladder was
deliberately left unfinished for a follow-on pass. That follow-on —
reading `by_address` and proposing finer `ConventionRow`s at each address,
re-running, converging pass over pass — is the "drill-down proposer" the
operator flagged as in progress in another session. It is PR2 in the R2IL
plan's own wave ladder (`.claude/plans/r2il-behavioral-ir-v1.md`), gated on
PR1's corpus numbers; PR3 (the classid mint for the R2IL container concept
in `lance-graph-contract::ogar_codebook`, item O5) is gated on PR2 proving
the route set. **Neither PR2 nor PR3 has landed as of this entry.**

### The reconciliation

`ghidra-integration-v1.md`'s G1 (an `analyzeHeadless` post-script dumping a
bespoke P-code text form) and G2 (a hand-rolled versioned LE image format +
Rust loader) are **superseded**, not merely lower-priority. The R2IL plan's
own stop condition already answers the question G1/G2 existed to answer:
*"§22.1: direct r2il/r2ssa consumption solves the upstream seam — YES
(43s)."* Dispatching `wave-ghidra-g1-g2.md` now would build a second,
throwaway lift path and a second, competing image format next to one that
is already merged, typed, and further along. `wave-ghidra-g1-g2.md` marked
superseded in place (kept for G0's real archaeology — the 74-opcode count,
the `PcodeEmulator` oracle precedent — which is still true and reusable,
just not via a Ghidra-side script). `wave-ogar-machine-pm1.md`'s gate #3
repointed from "Ghidra G1+G2 merged" to "ruff_r2il PR2+PR3 merged," so the
next session checking that gate finds the real dependency instead of a
dead one.

### Consequence for the C-band ruling

None to the reservation itself — `E-LGJ-THE-DOMAIN-BYTE-CARRIES-ALTITUDE-1`'s
`0xC4 BinaryLifting` fence ("Ghidra and r2sleigh are two consumers of the
same SLEIGH specs over ONE vocabulary") is now literally true in code, not
just anticipated: `ruff_r2il` path-deps r2sleigh's SLEIGH-driven crates
directly. PR3's classid mint, when it lands, is the first real tenant of
that slot.

### A separate, independently-found gap — flagged, not fixed here

`lance-graph-contract::ogar_codebook` documents itself as a **wire-compatible
mirror** of OGAR `ogar-vocab::ConceptDomain` under an explicit drift guard
("if OGAR's CODEBOOK ever moves an id, BOTH sides must update together").
Read directly against OGAR post-PR-#276: the mirror is missing `Ontology`
(`0x03`, present in OGAR before this session) and `Blocks` (`0x17`, added
2026-08-04) — **pre-existing drift, not caused by this session's C-band PR**
— and will also lack `JavaRuntime`/`Analytics`/`BinaryLifting` (`0xC0`/
`0xC1`/`0xC4`) once PR3 needs to route on them. Out of scope to fix
speculatively from here (lance-graph-contract is on this branch but not
under active work this session, and the mirror's own convention is to catch
up via its parity tests, not via an unprompted sync); recorded so PR3 does
not silently trip on it.

## 2026-08-18 — E-LGJ-THE-DOMAIN-BYTE-CARRIES-ALTITUDE-1

**Status:** RULING (operator, 2026-08-18: *"Java is an entire different layer
that's why I chose another higher level"*). **Confidence:** High for the
ruling; the reservation itself is OGAR-side and NOT yet made.

### The ruling

The classid's domain byte (`0xDDCC`'s `DD`, canon hi u16) is **stratified by
altitude**, not a flat namespace where placement is mnemonic or next-free.
Numerically higher = architecturally higher layer. The **C-band is the layer
above the Rust substrate**, and within it:

| slot | owner | why there |
|---|---|---|
| **C0** | **Java · Panama · Valhalla** | the supraconductor membrane over the SoA substrate — the FLOOR of that layer, the door everything else in it arrives through |
| **C1** | **ogar-bricks + Databricks** | the analyst estate |
| **C4** | **Ghidra** | bolted onto C0 (Ghidra *is* a JVM application — this repo's own G0 archaeology: fork at 12.2 DEV, minimum Java 25, Gradle ≥ 9.1), and explosive — C4 the plastic explosive, for the blast radius of turning any binary into addressable rows |

C4 is a **tenant** of the layer C0 floors, not a peer of C0. That internal
ordering is part of the ruling, not decoration.

### Why the axis is structurally sound (not just mnemonic)

The domain byte is the first two nibbles of the classid, so its **top nibble
is a 16-way altitude selector**: one mask separates "substrate ontology" from
"host layer" with zero lookup and zero value decode — the canon's *the key
prerenders nodes with zero value decode*, applied to layering. A first-nibble
split is the most expensive split available in the 16-ary cascade; spending it
on **altitude** is what makes it worth spending.

### What this corrects (storno — three of my own proposals, all wrong)

I mapped by **subject matter** ("P-code is an opcode vocabulary, Blocks is an
opcode vocabulary, therefore adjacent") when the actual axis is **altitude**.
Withdrawn, in order:

1. **"Seat P-code at `0x1718` as a loco consumer slot."** Wrong tier. `0x17`
   is ogar-loco = **lance-graph's own internal orchestration** (elixir-on-rails
   shaped, rs-graph-llm as the graph executor, Rig marking the replayability
   boundary between external LLM and internal low-code). It is a tier with a
   job, not a container for any palette whose ops fit in a byte. The `0x1717`+
   consumer slots are frontends *of that orchestration*.
2. **"Put P-code at `0x18`, next to Blocks."** Same error, one slot over.
3. **"A separate substrate/layout-contract domain"** as my third pick. Not a
   separate thing — it is **C0's content**. See the consequence below.

Root cause, worth keeping because it recurred three times in one session: I
flattened distinct motifs into one family because they share a **shape**
(everything becomes `(function : value)` calls in a 512-byte node). Loco's ABI
being *reusable* does not make `0x17` a parking lot. **Shape-similarity is not
domain-identity** — the dilution failure this workspace names by name.

### What survives, and is the useful half

**Reuse loco's node shape; own your own domain.** Loco says it itself — the
classid naming *a function body* "belongs here, at the substrate, and a
frontend references it rather than minting its own" (`ogar-loco` module doc,
`LocoConcept::FunctionBody` = `0x1701`). So a C4 P-code body can BE a loco
`FunctionBody` while every P-code concept lives in C4; likewise a C1 pipeline.
**Borrowing the container is not joining the domain.**

Also surviving, unchanged: the **two registers** point. An *op vocabulary*
(palette bytes) and an *artifact ontology* (concept ids) are different
registers. Ghidra: P-code ops vs function/section/symbol. Databricks: pipeline
verbs vs catalog/table/column/type — and the latter already has a real seam
here, `lance-graph-catalog/src/unity_catalog.rs`, with Delta as a table reader.

### The one consequence for code in THIS repo

**W6's schema/classid field on `LgjResourceInfo`/`LgjLaneDesc` carries a C0
concept.** A row store stamping which layout contract its bytes obey is the
membrane naming itself from inside its own layer — not a substrate concept
borrowed downward. Nothing else on the current wave list depends on the
allocation, so W5c/W6 are unblocked either way.

### Open, and NOT ours to close

The reservation is an **OGAR-side, operator-gated** act (`ogar-vocab`'s
`ConceptDomain` + the §2 allocation table; minting is gated on the 5+3 pass,
while *reserving* explicitly "costs nothing"). Two mechanical notes for
whoever makes it:

- **C2/C3 fall between C1 and C4.** Blocks set the precedent that a deliberate
  gap gets a **pinned test asserting it stays `Unassigned`** (`ogar-vocab`
  `lib.rs:5624-5644`, guarding the `0x10`–`0x16` gap) so a later pass cannot
  "tidy" a domain downward into it. The C-band wants the same three lines.
- **`0xC0` is a digit-swap of `0x0C` Automation** (`0xC001_0000` vs
  `0x0C01_0000`). Raised once, not decisive, recorded so it is not
  re-discovered as if new.


## 2026-08-17 — E-LGJ-WAVE-DISPATCH-VALIDATED-1

**Status:** FINDING (first real dispatch of the wave system). **Confidence:**
High — measured, not asserted.

The wave map (`E-LGJ-CALCIFY-THEN-DISPATCH-1`) was written to be
"dispatchable as-is by a session with zero shared context." First test: W3,
three Sonnet workers on disjoint file scopes, zero coordination between
them beyond the frozen signatures the orchestrator's briefs specified. All
three landed clean, mutually consistent (same `RowStore.open(long,long)`
signature, same `FacetId.index()` accessor — nobody guessed differently),
and the disjoint-scope rule held with zero merge conflicts.

Two things the gate sequence actually caught, worth recording precisely
because they're the mechanism, not the anecdote:

1. **A real defect, caught by the tests the wave mandated.**
   `FacetMatchView.rowCount()` was missing the same closed-store guard
   `matchesOf`/`cardinality` both carried — one accessor out of three,
   asymmetric, exactly the kind of gap a reviewer skims past and a
   two-sided lifetime test does not. `RowStoreLifetimeTest` (itself
   AI-written, by a different worker than the one who wrote the class
   under test) caught it on the first real run. This is the payoff of
   "workers never run the gate themselves" — the orchestrator's fresh,
   independent test run is what a self-reported "looks right" cannot be.
2. **A false alarm from the ORCHESTRATOR's own environment, not the
   code.** The first two `AllTests` invocations failed with every
   pre-existing suite red — before touching a single line of worker
   output. Root cause: I used an invented env var name (`LGJ_NATIVE_LIB`)
   instead of the real one (`LGJ_LIBRARY`, defined in `Abi.java`), so the
   runtime silently fell back to a stale `.so` from an unrelated default
   search path. The fix was to READ THE CODE (`Abi.java`'s
   `ENV_LIBRARY` constant) rather than guess a plausible-sounding name.
   Lesson for future orchestrator runs: verify the discovery mechanism
   from source before trusting a gate result — a wrong environment can
   look exactly like a real regression.

Both disable-runs (version-gate inflation; generator draw-order swap) ran
red-then-green with the EXACT expected suite-level blast radius — no
overreach, no under-reach — closing the loop the wave file promised.

## 2026-08-17 — E-LGJ-CALCIFY-THEN-DISPATCH-1

**Status:** DOCTRINE (operator-ruled: "don't execute the consumer plans yet,
just calcify the insights and make sure the muscle memory of the epiphanies
helps to gain momentum"). **Confidence:** High.

The working rhythm this repo now runs on, made explicit so it compounds
instead of being re-derived:

**plan → wave map → (shelf) → dispatch → gates → merge → arc entry**

A *plan* says what and why. A *wave file* (`.claude/waves/`) says exactly
who edits which file under which verbatim guardrails, with which disable-runs
and gate commands — dispatchable as-is, months later, by a session with zero
shared context. Writing the wave map WITHOUT executing it is not deferral;
it is the calcification step: decisions get made while the context is hot
(worker scopes, D1-style design forks, STOP triggers, the graph wave's
discovery that the fixture payload is PRNG noise and traversal needs a
deliberate edge-bearing generator arm — found at MAPPING time, not
mid-dispatch), and execution later starts from momentum instead of from
archaeology.

**The muscle memory, in one list** (each item earned at least once this
session, provenance in the entries below and in PR bodies #1–#6):

1. **Disable-run or it didn't happen.** Green tests prove nothing about a
   guard; break the thing, watch exactly the right tests go red, restore.
2. **Scaffolding-vs-target check.** When a proposal doesn't fit the code,
   ask which one is the placeholder before declining the proposal.
3. **The membrane never grows from the consumer side.** A needed symbol
   goes back through the wave process (now stamped in every consumer wave).
4. **Measure before believing direction** — the Vector API beat the
   crossing; fusion was noise at 65K rows and 3× at 256; the doc that
   assumed otherwise got corrected by the bench, not vice versa.
5. **Independent recomputation over golden blobs** — parity tests
   transcribe the generator; two (better: three) independent paths to one
   number.
6. **Assessments happen once, on the record** — archived discussions get
   one knowledge-doc verdict (kept/pinned-wrong) so they are never
   re-mined or cited naively.
7. **Exact-span over round-up** at every boundary a segment can be built
   from (the `byte_len` lesson — the difference between a view and an
   out-of-bounds capability).
8. **Board in the same commit** as the work it records; arc entry at
   merge; realign after every squash.

## 2026-08-17 — E-LGJ-THE-MIDDLE-TIER-IS-DELETED-NOT-WRAPPED-1

**Status:** DOCTRINE (operator-stated, scope confirmed). **Confidence:** High —
four directives + three posters, restated and confirmed in session.

The blast radius, recorded because a session that reads this repo as "a faster
Java binding to a Rust library" will make locally-sensible decisions that are
globally wrong:

1. **The middle of the Java data stack is deleted, not wrapped.** Today:
   App → DTO/ORM → Gremlin/TinkerPop → JanusGraph → Cassandra → Elastic /
   ClickHouse / Lucene = six components, five serialization boundaries, three
   mental models. After: **one** explicit ABI boundary, **zero** serialization
   boundaries. The middleware and side-car analytics tiers do not get wrapped —
   lance-graph + ndarray under one Panama membrane already *are* the traversal,
   analytics and search substrate. *"Java als low-code Oberfläche, ABI als
   Wahrheit."*
2. **Objects are eliminated, not optimized.** 10⁹ logical entities ⇒ **0** Java
   objects: no header tax, no GC churn, masks instead of pointers, survivors
   only touch heavy data. Valhalla's role is narrow and already measured here —
   it makes the *tiny descriptor vocabulary* free (≤8 B flattens; the 16 B
   entity does not), which is exactly why entities stay native and descriptors
   stay `record`-shaped.
3. **The trust boundary collapses with the data boundary.** Mask-first: the
   RBAC/ABAC clamp composes BEFORE execution, the scan runs on authorized lanes
   only, and only aggregates/projections leave. Security enforced at the source
   is a *consequence* of zero-copy, not a feature bolted on.
4. **The migration asymmetry is the weapon.** The developer-visible diff is
   `stream().filter(λ)` → `.where(Field.gt(...))`; everything underneath changes
   universe. Hence the standing rule: **the ABI is a machine membrane and never
   the product API** — the product is the illusion that ordinary Java just works
   at 10⁹ objects.

Operator's compression: *"Java Panama and Valhalla become the supraconductor
over lance-graph ABI shaped SoA substrate."* Supraconductor is precise — current
(the query) flows with no resistance (no allocation, no GC, no serialization)
through a thin familiar surface.

**Consequence for review:** any proposal that adds a serialization step, a
per-element crossing, an object materialization, or a post-filter security check
is not a tradeoff to weigh — it contradicts the thesis and is rejected.

## 2026-08-17 — E-LGJ-THE-FLAT-FIXTURE-WAS-SCAFFOLDING-NOT-THE-TARGET-1

**Status:** CORRECTION (of my own framing). **Confidence:** High — operator
correction, acted on the same session.

I answered the `simd_soa` question by measuring `MultiLaneColumn` against the
**flat three-lane fixture**, found two real API mismatches, and recorded a
"declined for now" verdict. The operator corrected the frame: *"the whole point
is Java should optimize the SoA layout — we won't dismiss the initial plans
just because you found it doesn't apply for unorganized non-SoA."*

The technical findings were right and are unchanged (see the entry below); the
**conclusion drawn from them was scoped wrong**. The flat fixture was always
scaffolding — `docs/abi.md` §10 and `architecture.md` said so from PR #1 ("the
generic fixture in this first slice was deliberately chosen … so the membrane's
physics could be proven independent of graph semantics"). Measuring a
substrate-shaped tool against the scaffolding and concluding "not yet" inverted
which one was provisional.

**The generalizable failure:** when a proposal doesn't fit the *current* code,
check whether the proposal is early or whether the **code is the placeholder**.
Here the code was the placeholder, and the right move was to build the real
shape (the 512-byte row store, W2, shipped same session) rather than defer the
tool. A "declined, revisit later" verdict is only honest when the thing it was
measured against is the thing that stays.

## 2026-08-17 — E-LGJ-SIMD-SOA-IS-FOR-THE-ROW-STORE-NOT-THE-FLAT-LANES-1

**Status:** DECISION (declined refactor, with the trigger for revisiting named).
**Confidence:** High — decided by reading `ndarray/src/simd_soa.rs`'s full API, not by taste.

Operator suggestion: "if you use SoA, calling simd_soa.rs would make sense" — should
`native/lgj-abi/src/kernels.rs` route through `ndarray::simd_soa::MultiLaneColumn` (the canonical
`Arc<[u8]>` SoA carrier) instead of raw `&[u32]`/`&[i32]` slices? **Answer: not for today's
flat-lane fixture; yes for the future 512-byte row-store slice.** Two concrete API mismatches,
not a style call:

1. **No tail handling.** `MultiLaneColumn::new()` hard-requires `len % 64 == 0`; every `iter_*`
   yields only full 64-byte chunks via `as_chunks::<64>()` — no remainder arm. The
   `simd_int_ops` primitives this project consumes do the opposite by design: full 16-lane
   groups + a scalar tail for arbitrary caller-chosen `n_rows`. Wrapping the fixture's lanes in
   `MultiLaneColumn` would force 64-byte padding on every allocation, bought for nothing.
2. **No `u32` lane.** `MultiLaneColumn` ships u8x64/f32x16/f64x8/u64x8/i32x16/i64x8 iterators —
   no u32. The fixture's `ids`/`classes` are `u32` (`eq_u32_to_mask`).

So `kernels.rs` already calls the correct layer: the `ndarray::simd_int_ops` primitives own their
chunking internally. `MultiLaneColumn` sits *above* that layer, for uniform pre-padded columns.

**Where it DOES fit — the operator-stated layout reference (recorded verbatim so it survives):**
"the 64k x 512 bytes SoA layout is enforced everywhere in lance-graph (32 Lanes each 4 bytes
classview+12 bytes). For Java the layout might differ — just for reference." A 512-byte,
64-byte-aligned row store (32 × 16-byte V3 facets) is padded/aligned *by construction* — no tail
problem — and each row is a natural `iter_u8x64` chunk-of-chunks. When the real
`NodeRow`/facet slice replaces the generic fixture (`docs/abi.md` §10, `docs/architecture.md`
"where a real graph slice would attach"), `MultiLaneColumn` is the type to reach for. Not before.

## 2026-08-17 — E-LGJ-VECTOR-API-BEATS-THE-CROSSING-1

**Status:** FINDING. **Confidence:** High (real JMH 1.37, `Data.crossCheck()` guards every fork,
independently cross-checked against a second, mechanically-generated computation of the same CSV).

Completes D-LGJ-G, the mission's mandated "where does execution belong — measure it, do not assume
the Rust side wins" comparison. The honest answer complicates the thesis in a useful way: **for a
single predicate over one native lane, the Java Vector API — reading the SAME native
`MemorySegment` zero-copy via `IntVector.fromMemorySegment`, no `byte[]`, no bounce buffer — beats
the native `lgj_plan_eval` crossing at every row count tested, from 64 to 4,194,304**, by 56.4× at
small sizes down to 1.33-1.41× at the largest:

| rows | native (µs) | vectorApi (µs) | vectorApi wins by |
|---:|---:|---:|---:|
| 64 | 0.612 | 0.011 | 56.40× |
| 65,536 | 15.324 | 8.027 | 1.91× |
| 4,194,304 | 1858.686 | 1319.107 | 1.41× |

A second, separate crossover is also real: native beats a plain Java **scalar** loop only past
roughly 4,096-16,384 rows — below that the crossing's own fixed cost (consistent with Component A's
measured ~22 ns bare-downcall floor) is not yet repaid.

**Why this does not overturn the project's thesis, and where the thesis's own machinery already
shows the real answer.** Component C isolates exactly one predicate, one lane — the case with
nothing to fuse and nothing to coordinate, which is precisely the case a zero-copy Vector kernel is
best at. Component E (multi-predicate fusion) shows the picture change: SIMD-vs-scalar is the
largest lever measured anywhere in this benchmark (10.8×-31.1×, growing with predicate count), and
`fused`/`unfused` land within this harness's own stated ~10% noise floor of each other at 65,536
rows — meaning the fused plan's real value is the STRUCTURAL guarantee of exactly one crossing
regardless of predicate count (already proven separately by `LazinessTest`), not a large measured
time saving at this scale. The honest verdict, matching the mission brief's own framing rather than
either extreme: **the crossing is worth paying for composed, multi-predicate work — not for reading
one predicate off one lane, where Java on the same memory is simply faster.**

**Method note, since two independent computations of the same data is itself worth recording as a
discipline:** `RESULTS.md` was hand-written from the raw `results/jmh-results.csv`, then verified
against `bench/summarise.sh` — a separate script the same PR ships that mechanically regenerates
the tables from the CSV "so a re-run's numbers can be regenerated mechanically — a table
transcribed by hand is a table that can drift from its own data" (the script's own doc comment).
Both productions of the same 50-row CSV agreed to 3 decimal places on every cell checked.

## 2026-08-17 — E-LGJ-VALHALLA-MEASURED-NOT-ASSUMED-1

**Status:** FINDING. **Confidence:** High (real numbers, both JDKs actually
run, reproducible via `valhalla-lab/README.md`).

The mandatory N-objects-vs-N-values-vs-1-lane experiment
(`.claude/knowledge/valhalla-three-truths-method.md`'s "one experiment that
must never be skipped") ran on both real JDKs. Headline, on 65,536 rows,
identical question, identical answer on every path:

| | native, one crossing | hydrate 65,536 `Row`, then scan |
|---|---:|---:|
| stable JDK 26 | 19.5 µs, 289 KiB | 746 µs, 2.00 MiB |
| Valhalla JDK 27 EA | 15.7 µs, 289.5 KiB | 900 µs, 2.50 MiB |

**The thesis's prediction held, and the reason why is itself a measured
finding, not an assumption:** `LaneId` (one field) measured `FLAT` under
Valhalla via the real VM query `ValueClass.isFlatArray` (2.90 B/element vs
16.00 B on stable — ~5.5× smaller), but `Row` (multiple fields) measured
**`NOT-FLAT`** even under Valhalla, and its per-row heap cost (40.01 B) was
*larger* than the stable JDK's own record-array cost (32.01 B). Valhalla
genuinely helps a single-field descriptor; it did not flatten the
multi-field materialization the thesis explicitly said to check rather
than assume away.

**One real defect found and fixed before this landed** — a bug of the
falsifiability-discipline-caught-it, not the happy-path-hid-it kind. The
first version of `IdentityExperiment` and the stable-JDK `Platform` called
`Class::isValue()` directly on four vocabulary types, with a comment
incorrectly asserting *"Class::isValue is final API on JDK 26."* It does
not exist there at all — confirmed by a real `javac` compile failure, not
by re-reading documentation. Fixed by routing every identity query through
`Platform.isValueClass(Class<?>)`: the stable half answers `false`
honestly (a JDK with no value-class concept can never produce one — the
answer is exact, not a guess, unlike the genuinely-unknowable
`arrayFlatness` case the same file already handles correctly), the
Valhalla half answers with the real `type.isValue()`. The correction
mirrors `E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1`'s finding about
`kernels.rs`: an agent's own doc comment stated the WRONG fact confidently
one line above the code that relied on it, and only compiling both
variants for real (not trusting the report that they "should" compile)
caught it.

**Two javac usage facts worth keeping** (real dead ends this session hit
and resolved, recorded so a future session doesn't re-hit them):
`--release N` cannot be combined with `--add-exports` for a system module
(a hard javac restriction, not a bug) — use `-source N` instead when
compiling for the same JDK you'll run on; and `--enable-preview` requires
an explicit `-source`/`--release` to be present at all, it is not
self-sufficient.

## 2026-08-17 — E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1

**Status:** FINDING. **Confidence:** High (measured, not asserted — every
number below came from an actual command run, not from an agent's report).

The core vertical slice (`docs/abi.md` + `native/lgj-abi` + `java/`) is
real, compiles clean, and its safety claims are not merely tested but
**disable-verified**: `registry.rs::resolve`'s generation check
(`slot.generation != gen`) was deliberately short-circuited to
`if false && ...`, and the suite re-run. Exactly the two tests whose names
claim to guard this property —
`a_reused_slot_invalidates_the_old_handle` and
`fabricated_handles_are_rejected_not_dereferenced` — went red; all other
70 stayed green. This is the falsifiability discipline this workspace's
sibling repos (tesseract-rs, MedCare-rs) both independently arrived at —
"a test that passes on the happy path is not evidence" — applied to this
repo's very first disable-verification, and it passed the meta-test: the
tests were real, not decorative.

**The one real rule violation the D-LGJ-AUDIT sweep found**:
`native/lgj-abi/src/kernels.rs::simd_popcount` called
`ndarray::hpc::bitwise::popcount_batch_u64` directly — the exact pattern
`E-LGJ-SIMD-PROVENANCE-1` exists to forbid. This is worth recording as a
finding in its own right: **the rule was stated correctly in the agent's
own doc comment one line above the violation** ("Reused, not
reimplemented — this already exists in `ndarray`...") — the agent
correctly identified WHERE the function lived but reached for the
internal path it happened to see in ndarray's source rather than the
re-export it was told to prefer. A soft "verify the exact path" brief
instruction was not sufficient; a mechanical grep gate is what actually
caught it. **Consequence for future briefs in this repo:** soft
instructions ("prefer X") get a mechanical audit regardless of how
clearly they were stated — this is now standing practice, not a
one-time fix.

**Numbers on record**, so a future session can spot-check rather than
re-run everything from scratch: Rust `cargo test` 72/72; `ndarray`
`simd_int_ops` tests 41/41; `clippy -D warnings` and `fmt --check` both
clean; release build exports exactly the 14 symbols `docs/abi.md` §7
names; Java `javac -Xlint:all` produces exactly 7 `[restricted]`
warnings, all in `internal/ffm/*` or one test deliberately exercising it;
`AllTests` 132/132 across 8 suites. Full breakdown on `STATUS_BOARD.md`'s
D-LGJ-B/C/D/E rows.

## 2026-08-17 — E-LGJ-V4-DIVERGES-FROM-NDARRAY-DEFAULT-1

**Status:** FINDING. **Confidence:** High (operator-directed, mechanically
applied).

`native/lgj-abi/.cargo/config.toml` pins `-Ctarget-cpu=x86-64-v4`
(AVX-512), **deliberately diverging** from `/home/user/ndarray`'s own
default of `-Ctarget-cpu=x86-64-v3` (AVX2). This is not a mistake to
reconcile later — the two repos have different distribution goals: ndarray
targets portable redistribution (v3 = Haswell-and-later, ~2013+), while
`lance-graph-java`'s native artifact in this phase is built and run on one
known host (verified AVX-512-capable this session) for a research vertical
slice, not shipped broadly. The `LgjAbiManifest::simd_backend` field is
what makes this divergence self-documenting at runtime rather than a
silent assumption — a consumer reads the manifest rather than assuming
which tier compiled.

**Consequence:** any future portable-distribution build of `lgj-abi` must
override with `CARGO_BUILD_RUSTFLAGS='-Ctarget-cpu=x86-64-v3'` at build
time, per the comment left in `.cargo/config.toml`. Do not silently change
the file's *default* back to v3 without a stated reason — v4 is the
deliberate choice for this phase.

## 2026-08-17 — E-LGJ-VALHALLA-ALREADY-MAINLINE-1

**Status:** FINDING. **Confidence:** High (measured by direct `diff -rq`
across three local checkouts + live compile/run verification).

JEP 401 (Value Classes and Objects) has **already integrated into mainline
JDK** as a preview feature — it is not exclusive to a separate Valhalla
fork. Measured this session: `/home/user/valhalla` (`lworld` branch,
2026-07-30 HEAD) is **behind** mainline `/home/user/jdk` (2026-08-17 HEAD)
for value-class purposes; its own last relevant commit is literally
*"[lworld] things to delete from lworld just before integrating JEP-401."*
`/home/user/panama-foreign`'s `java.lang.foreign` package is **byte-
identical** to mainline (`diff -rq` exit 0).

**Consequence:** this project needs exactly ONE production JDK (a GA
build, verified this session as `/opt/jdks/jdk-26.0.2`, where FFM is
final) and ONE Valhalla-preview JDK (the *official EA binary*
`27-jep401ea3+1-1` from `jdk.java.net/valhalla/`, not a source build of any
local fork). Building any of the three local OpenJDK source checkouts from
source for this project would have cost real time for zero benefit — the
binary already exists and was verified to work. See
`.claude/knowledge/jdk-toolchain-facts.md` for the full toolchain matrix.

**Corollary, stated so a future session doesn't re-litigate it:** null-
restricted *type* syntax (`Foo!`) and specialized generics do **not**
exist in any checkout verified this session — only the internal
`@jdk.internal.vm.annotation.NullRestricted` field annotation plus
`jdk.internal.value.ValueClass` factories, gated behind `--add-exports`.
Do not assume `Foo!` syntax is available; it measurably is not, as of this
session's verification.

## 2026-08-17 — E-LGJ-NO-C-EVER-1

**Status:** RULE (operator directive, locked). **Confidence:** N/A —
founding constraint, not a discovered fact.

Operator, verbatim: *"There's no C ever. We reuse Panama project for a
rust only."* `extern "C"` names the SysV AMD64 psABI (a platform calling
convention), not the C language; `#[repr(C)]` names a platform aggregate
layout rule, not a C struct. Consequence: no `.h` file, no `cbindgen`, no
`jextract` (structurally inapplicable — its only input is a C header, and
none exists), no JNI, anywhere in this repo, ever. Full statement:
`.claude/knowledge/no-c-ever.md`. This is the single most load-bearing
rule in the project and the one most likely to be violated by habit
(reaching for `jextract` because "that's how Panama projects usually
work") rather than by disagreement — flagged here so it is checked
mechanically (`abi-membrane-warden`'s doctrine item 1) rather than trusted
to memory.

## 2026-08-17 — E-LGJ-SIMD-PROVENANCE-1

**Status:** RULE (operator directive, locked). **Confidence:** N/A —
founding constraint.

Operator, verbatim: *"Never use ndarray::hpc, trampoline to
ndarray::simd::* instead."* `ndarray::hpc::*` is ndarray's internal
implementation namespace; `ndarray::simd::*` is the sanctioned re-export
surface every consumer in the Ada stack is expected to use (ndarray's own
CLAUDE.md: *"Consumer writes `crate::simd::F32x16`. Period."*). This repo
is one more consumer of that invariant, not an exception. Full statement
and falsifier grep: `.claude/knowledge/simd-provenance.md`. This directive
arrived AFTER the first vertical-slice fan-out was already dispatched
(whose briefs mentioned the popcount primitive via its `hpc::bitwise`
path, with an instruction to "verify the exact path" and prefer the
`simd` re-export) — `STATUS_BOARD.md`'s `D-LGJ-AUDIT` entry exists
specifically to mechanically check the fan-out's actual output against
this rule rather than assume the earlier, softer brief language was
sufficient.

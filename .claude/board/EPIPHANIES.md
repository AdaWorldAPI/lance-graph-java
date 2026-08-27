# Epiphanies Log — Findings, Corrections, "Aha" Moments (APPEND-ONLY)

> Prepend new entries at the top. Never edit a past entry except its
> `**Status:**`/`**Confidence:**` line. A correction gets its own new,
> dated entry that references the one it corrects — the storno rule.

## 2026-08-27 — E-JAVA-IS-SIMD-RS-VALHALLA-PANAMA-IS-THE-POLYFILL-1

**Status:** DOCTRINE — [OPERATOR-FRAMED]. Pinned as the ENFORCEMENT LAYER in
root `CLAUDE.md` (rules E1–E6), same commit.
**Confidence:** High — the grounding is measured in-tree, not argued.

The operator's frame, verbatim intent: *"look at ndarray. Java is like
simd.rs. Valhalla/Panama is the polyfill. Rust is like
simd_{AMX,avx512,avx2,neon,wasm}.rs."*

Verified against the actual tree before pinning: `ndarray/src/simd.rs` is
**37 functions and zero shipping instructions** — every raw intrinsic in the
file sits inside `#[cfg(test)]`, where `_mm256_unpacklo_epi32` appears only
as the oracle a wrapper is checked against. `simd_avx512.rs` alone carries
**488** intrinsics. And the detail that seals it: `simd_scalar.rs` is a
**backend, below the facade** — the fallback is never written inline in
`simd.rs`. The facade is pure vocabulary; the backends are pure machinery;
the dispatch is free at compile time.

**Why this is a doctrine and not an analogy.** Every violation this session
found reads as a breach of the isomorphism, at the layer it names:

- `FacetMatchView.cardinality`'s Java popcount loop = an inline scalar
  fallback in `simd.rs` (three strikes: the loop, then 32 composed counts
  summed in Java, then a proposed buffer-popcount symbol — each still Java
  holding a moving part; ABI minor 9 is the lawful shape).
- J2's hand-written `ROW_BYTES = 512` beside the declared `ROW_LAYOUT` =
  the facade carrying a second spelling of a backend constant (fixed this
  commit: `Layouts` derives, `RowStore` names).
- The Vector API question resolves permanently: a backend inside Java, and
  Java has no backends — lab arm forever.

**The polyfill reading is precise, not poetic.** Valhalla's A/B types
compile as ordinary records pre-JEP-401 exactly the way `simd.rs` code runs
on the scalar backend off-x86: one source, zero cost where the platform
provides it, still CORRECT where it does not. Panama likewise —
`JAVA_INT_UNALIGNED` works everywhere and JITs to a mov where it can.
Degradation without a second source is the definition of a polyfill.

**The stack nests.** lgj's bottom is ndarray's top: `lgj_hop` →
`kernels.rs` → `ndarray::simd` → `simd_avx512.rs` is facade → polyfill →
backend twice over, self-similar. Cross-refs: root `CLAUDE.md` E1–E6;
`E-BINDING-A-REAL-PROVIDER-MEASURES-THE-FIXTURE-1` (the ClassView half of
the same session); minor-9 arc entry in `LATEST_STATE.md`.

## 2026-08-27 — E-BINDING-A-REAL-PROVIDER-MEASURES-THE-FIXTURE-1

**Status:** FINDING — measured, pinned by a test rather than asserted.
**Confidence:** High. Both halves are numbers, and the disable-run is
red-then-green on five tests.

Binding the real `ClassView` provider (`OgarClassView`) behind
`--features ogar-classview` did not make the hop *better*. It made the hop
*empty* — and that emptiness is the most useful thing the wiring produced.

The provider itself is correct and discriminating: 98 registered classes,
**12 distinct** participation masks where the fixture answered one. But the
generated row store's classid domain is `0..16`
(`ROWSTORE_CLASS_CARDINALITY`) while every vocabulary classid is `>= 0x0100`
— **disjoint**. So a generated store under a real provider traverses nothing,
for every classid in its own domain.

**The generalizable part.** A fixture with a plausible answer for every input
(`FieldMask::FULL`) is indistinguishable from a bound provider until you bind
one. `FULL` is the answer that never disagrees, which is exactly why it
cannot be falsified in place. The measurement that mattered was not "does
the provider work" — it was **binding it and reading what the rest of the
system then failed to do**. The seam was declared closed-enough for two
waves because nothing in the suite could tell the two providers apart.

**Consequence, stated rather than fixed here:** the remaining fixture is the
row CONTENT, not the layout or the kernels. Replacing `RowStore::generate`
with Lance-loaded SoA rows is what makes the bound provider observable
end-to-end; until then the feature is a correct provider over rows it has no
classes for.

**Discipline note.** Two fixture-semantics tests had to be gated OFF under
the feature. Neither was deleted: each got a paired ON twin asserting the
CONTRASTING fact on the same inputs (all-32 vs none; 19/29 vs empty), so the
gate reads as evidence of a changed answer rather than as a suppressed
failure.

## 2026-08-26 — E-ONE-SUBSTRATE-FIVE-GLOVES-GHIDRA-IS-THE-GLOVE-NOT-THE-MODEL-1

**Status:** DOCTRINE — [OPERATOR-FRAMED]. The "what is it FOR" that the
r2il/r2conc/ogar-loco arc has been building toward, named so it does not
dilute across sessions. Cross-refs: this repo's
`E-LGJ-GHIDRAS-SEAM-IS-AN-INTERFACE-AND-ITS-VOCABULARY-CANNOT-FLATTEN-1`
(R12, PR #34); lance-graph `r2il-machine-semantic-contract-v1.md` §7.8
(V4 = V3 + executable content; the zipper isomorphism; three-tier JIT).
**Confidence:** High on the framing and on R12's measured seam; the
throughput figure is a single-CPU lab measurement (see below).

### The operation, and the five faces of it

Every product framing the operator named is the SAME three steps:

```
lower arbitrary code → R2IL/p-code   (INTAKE: r2sleigh lift, once, C++ allowed)
address it in ogar-loco               (classid + lane ordinal; zipper: address IS program)
execute zero-copy through the mask     (RUNTIME: r2conc, pure Rust, N-lane sweep)
```

They are one substrate with a policy/render GLOVE on top; only the glove
differs:

- **"Bring your own code" Foundry-aspiring substrate** — glove = ingest +
  ontology UI.
- **RE / security-analyst platform** — glove = the analyst UI (Java).
- **Zero-trust sandbox** (whitelist-only execution; every binary
  pattern-scanned for malware before it runs; autonomous alerting) —
  glove = a POLICY MASK. Whitelisting is a mask AND; malware detection is
  a masked pattern-match over the lifted R2IL BEFORE `step`; alerting is
  the alpha plane firing on an anomaly. The §7.8 hexagon-proposes /
  table-verifies machinery, pointed at "is this a known-bad shape" instead
  of "which op is this." Defensive by construction.
- **The C64 dream** — lower a game (Giana Sisters) into ogar-loco; glove =
  a2ui-paint `Skin::Tile` over the game's addressed state (Mario-editor
  flavor).
- **Stone-age Java bare-metal** (TinkerPop, EDI) — glove = NONE; Java
  stops carrying objects and addresses lanes.

The zero-trust face is the proof it is one substrate: the security product
is the cognitive substrate wearing a policy glove. Nothing new is built.

### "Ghidra as Java" resolves against R12, and the answer is brutal

R12 measured it: **Ghidra's own p-code vocabulary cannot flatten** —
payloads 0/2, ordinals 3/3. A 2-input `PcodeOp` is five heap objects. So
"Ghidra as Java" does NOT mean porting Ghidra's object model to Valhalla
value classes. It means the inversion the stack keeps re-deriving:

> **Java STEERS** (analyst UI, whitelist policy, alerting, the Mario
> editor). **Rust DECODES + EXECUTES** (r2sleigh lift + r2conc). **The
> seam carries ORDINALS, never objects.**

That is W5 already specced, the lance-graph-java mask-native law, and the
zipper isomorphism — three independent derivations of one seam. Ghidra's
Java is the glove; Ghidra's decompiler object graph is exactly what we
throw away. (r2sleigh already reimplements the downstream arms in Rust:
r2ssa = heritage, r2dec = decompiler, r2types = type inference, r2sym +
r2conc = emulation. What remains of Ghidra at runtime is ONE arm: `libsla`
decode. §7.8's hexagon is that arm's replacement path — learn the byte→op
map, exact-table as authority, libsla as the 0.4% fallback while muscle
memory grows.)

### The throughput, and the one thing that IS banked about it

**What is banked** (R7, committed, `R7_BillionOpsZeroAlloc.java` +
`R7-observed.txt`): 10^9 group projections allocate **exactly 960 B**,
byte-identical across all three runs — fixed scaffolding, not a per-op
cost (a 1 B/op survivor would have shown a gigabyte). That is the
ordinal-seam payoff stated safely: **zero per-op allocation**, addressing
lanes instead of carrying objects (R5's hydrating path at the same op
shape is 32–104 B/row = 32–104 GB at this scale).

**What is NOT banked, and must not be pinned:** a throughput number. R7's
own runs span **1.76–3.48 s (287–567 M ops/s), a 2× spread — too wide to
pin**, and R7 says so in as many words. The operator's spoken "~2 s /
2.1 ns" lands inside that spread but is a point in a noisy cloud, not a
result. And R7's `sweep` is pure in-JVM `MemorySegment.get` — **no FFI,
no ndarray call** — so it cannot support an "into ndarray" claim at all.

(Corrected 2026-08-26 after a codex P2 on PR #36 caught the first draft
banking the 2.1 ns figure and attributing it to a Panama→ndarray path.
The doctrine is unchanged — R12's flatten measurement is the load-bearing
evidence, and zero-alloc is the safe throughput-adjacent claim. If a real
ndarray-through-Panama throughput is wanted as evidence, it needs its own
committed reproducer; it does not exist yet.)

### The one invariant to fix before any glove is built

The zero-trust glove lowers UNTRUSTED, possibly hostile binaries. That
turns r2conc's existing loud-refusal discipline (`Unsupported`,
`PcodeRelativeBranch`, `OutOfBounds`) from a correctness boundary into a
SECURITY boundary. The invariant, stated now so a future session cannot
wire it backwards: **the malware-scan mask runs on the lifted R2IL BEFORE
`step`; the sandbox executes only `lifted ∩ whitelist`; a lifted op never
reaches a real effect without passing the policy mask first.** Scan-then-
execute, never execute-then-scan.


## 2026-08-25 — E-LGJ-GHIDRAS-SEAM-IS-AN-INTERFACE-AND-ITS-VOCABULARY-CANNOT-FLATTEN-1

**Status:** MEASURED — R12 + the Ghidra source trace. No code swapped yet.
**Confidence:** High on both halves; each is a citation or a VM-reported number.

Starting the `r2il-machine-semantic-contract-v1` arc from the GHIDRA end
while a sibling session drives W0-W4. Two findings, and they point the
same way.

**1. The seam is an interface, and no core fork is required.** Traced:

```
Language (INTERFACE, model/lang/Language.java:29)
  .parse(MemBuffer, ProcessorContext, boolean)
      -> InstructionPrototype (INTERFACE, :35)
           .getPcode(context, override)  -> PcodeOp[]
```

`InstructionDB.getPcode()` (`:608-628`) does nothing but delegate to
`proto.getPcode(...)`. `InstructionPrototype` has exactly TWO
implementations — `SleighInstructionPrototype` and `InvalidPrototype` —
and `SleighLanguage` constructs the former at exactly ONE site
(`SleighLanguage.java:392`). `Instruction` is itself an interface with an
`InstructionStub` already in tree, so alternative implementations are an
established pattern rather than a hack.

This was an OPEN UNKNOWN that had been flagged twice this session and
never checked; it gates the whole Java half, and the answer is favourable.

**2. Ghidra's P-code vocabulary cannot be carried as value classes — its
identity can.** R12, field shapes transcribed from
`Varnode.java:51-54` / `PcodeOp.java:102-105`, run on the JEP 401 EA build
with the VM reporting element sizes:

| shape | flat? |
|---|---|
| `VarnodePayload(int,int,long)` 16 B | **no** |
| `PcodeOpPayload(int,long)` 12 B | **no** |
| `VarnodeRef` / `PcodeOpRef` / `InstructionRef` (`long`) | **yes**, element size 8 |

And those Payload rows are the OPTIMISTIC bound — every reference deleted.
The real `Varnode` holds an `Address`; the real `PcodeOp` holds a
`SequenceNumber`, a `Varnode[]` and a `Varnode`, so **a 2-input `PcodeOp`
is five heap objects**. That is "ONE ROW IS NOT ONE JAVA OBJECT" at its
worst: one instruction becomes a small object graph.

**Why this needs nothing new.** The verdict — *address the vocabulary,
don't carry it* — is the same result `LaneId`/`Ordinal`/`MaskId` already
rely on, and the same reason `RowRange` (16 B) does not flatten. The
existing descriptor discipline answers W5's central question before W5
starts.

**The unanticipated part, recorded so it is not lost.** `VarnodeNarrow`
(`spaceId:u8`, `size:u8`, 48-bit offset) ALSO flattens. So 8 bytes is
enough to carry a varnode's real CONTENT, not merely a pointer to it — a
content-bearing descriptor that reads space and size without a lane
round-trip. Bounded by exactly one condition: a 48-bit offset. Whether
that suffices is a W0/W1 address-space question, not a Valhalla one.
**Named as an option, NOT proposed as the design** — pre-empting W1's
tenant carving from this side is precisely what the plan's R1 rule
("no private object graph then serialize") forbids one layer up.

Cross-ref: `.claude/plans/r2il-machine-semantic-contract-v1.md` §2 (the
facade-not-data-model correction) and §6 W5, both in lance-graph.

## 2026-08-25 — E-LGJ-A-CONSTANT-COPIED-THREE-TIMES-HAS-NO-FALSIFIER-1

**Status:** SHIPPED — ABI minor 8, docs/abi.md §17.
**Confidence:** High on the mechanism; every guard disable-verified.

The §14 carving wire encoding lived in three places: a Rust `match`, a
Java `enum`, and abi.md's own table. Each was correct. Nothing anywhere
would have failed if one had stopped agreeing with the others.

**That is the finding, and it generalises past this constant.** Three
copies of one fact is not a documentation problem to be tidied — it is a
correctness problem with NO falsifier, and its failure mode is silent:
a variant added or reordered upstream re-maps one copy, a sweep then
reads the same 12 bytes under the wrong grouping, and returns a
plausible number. "Keep them in sync" is not a mechanism.

The fix is not a fourth copy that checks the others. It is ONE source
(`CascadeShape::ROTATIONS`, the contract's) and two DERIVATIONS: the ABI
computes the encoding from it by a RULE (group count, descending) rather
than by declaration position, and the manifest serves the result to Java
so Java restates nothing. A reorder upstream cannot re-map the wire; an
addition upstream propagates.

**The two corollaries worth keeping:**

1. **Derive by a rule, not by position.** Had the order been "declaration
   order of the enum", the derivation would have been just as automatic
   and just as fragile — the drift would simply have moved upstream.
2. **Meaning is declared; encoding is served.** `RAILS_6X2` keeps its
   arity as a literal, because the arity IS the constant's identity and a
   name that lies about it is worse than a hardcode. Only the encoding —
   which carries no meaning — became data.

**And the change surfaced a latent defect one layer down**, which is the
usual reward for touching a boundary: Java's load gate required the FULL
manifest layout, so the first growth of that struct would have made every
older artifact fail to load, in flat contradiction of §2's additive
promise. It had been written that way since minor 1 and was unreachable
until now. Measured: restoring the full-layout gate makes all four
historical libraries fail outright.

Cf. `E-LGJ-THE-RESOLVER-WAS-UPSTREAM-ALL-ALONG-1` — same shape one rung
up (the answer was already in the contract; the local version was the
copy).

## 2026-08-25 — E-LGJ-THE-RESOLVER-WAS-UPSTREAM-ALL-ALONG-1

**Status:** SHIPPED — ABI minor 6 + the contract accessor + the ndarray
primitive. All three open followups closed.
**Confidence:** High; every guard disable-verified, both directions.

### The finding, again before the code

`facetSumAs` was named honestly because "the fixture ClassView carries no
carving resolver at all". That was true of the FIXTURE and false of the
CONTRACT: `lance_graph_contract::facet::CascadeShape` has carried the three
groupings (`G6D2` rails / `G4D3` triplets / `G3D4` quads, each `G·D = 12`), the
full algebra, and its own sentence that the grouping is *"class-conditioned:
`classid` selects it from the inherited schema"* all along. **The local
`Carving` enum was a re-mint of an existing contract type** — the
parallel-object-model anti-pattern in miniature, committed while writing a note
about not claiming authority.

What genuinely did not exist: a `ClassView` method RETURNING one. That is
substrate-tier, so it landed upstream first per the Missing-capability STOP
rule.

Two near-misses worth recording, because both LOOKED like the answer:

- `ClassView::rail_carving` returns `RailCarving` — but that is rail-path
  geometry per axis (`InterleavedPairs` / `AxisSlab`), a different question.
- `FacetSchema::of_classid` is classid-selected and three-way — but its third
  reading is `Pair48` (`2 × 48-bit`), NOT the le-contract L6 quads
  (`3 × (8:8:8:8)` SPOG, operator-RULED 2026-07-06). It answers "which payload
  FORMAT", not "which `G·D = 12` grouping". **That divergence between the ruled
  doc and the shipped enum is real and is left as an upstream observation, not
  silently reconciled.**

### What landed

1. **contract**: `ClassView::cascade_shape(class)`, zero-fallback `G3D4`, same
   registry-resolution pattern as its four siblings.
2. **lgj-abi minor 6**: `lgj_reduce_facet_sum_resolved` — resolves every
   selected row's `classid → ClassId → cascade_shape`, requires agreement, then
   sweeps monomorphically and REPORTS the grouping back. `Carving` is now
   `pub type Carving = CascadeShape`; only the u32 wire encoding stays local,
   pinned BY GROUP COUNT so an upstream variant reorder cannot re-map it.
   G11 fence widened by one module (`facet`), deliberately.
3. **Java**: `RowStore.facetSum → FacetSum(sum, carving)`. `facetSumAs` remains
   the deliberate reinterpretation escape hatch.

**The question is asked once at the population's edge, never inside the sweep.**
Resolution is `O(mask_words + popcount)`; the sweep carries no per-row dispatch.
The fix for "unverified" was never "consult more often".

### The fixture had to be made able to FAIL

`FixtureClassView::cascade_shape` returns `class % 3` rather than the trait's
constant. A constant answer makes every population trivially homogeneous, so the
"does this resolve to ONE grouping" guard could never fire and its test would
pass for an implementation that never checked. Varying makes both outcomes
reachable on real fixture data — and the paired half matters as much: classids 3
and 6 SHARE a grouping and must still resolve, or the refusal would just be
"reject every multi-class population".

Empty resolves to `None`, deliberately: zero rows carry zero classes, so
reporting the zero-fallback would be inventing an answer.

### Followup 2 — does `sum` earn ABI vocabulary? RETAINED, with a real reason

§14's justification was weak (a benchmark's checksum). The better one, now in
`abi.md` §15: it is the only mask-CONSUMING operation over the register, so
without it the mask path has a build half and no execution half; and it is the
cheapest operation that cannot be faked from outside, since any correct
implementation must visit exactly the selected rows and decode exactly the
resolved grouping — which is why it doubles as the parity oracle for both.

**The condition to revisit is stated so it can fire:** a second reduction
(min/max/count-distinct) must NOT become a second symbol. That is the point to
generalise to an op-code parameter on one reduce symbol, mirroring
`lgj_plan_eval`'s `LgjOpDesc`, with `sum` as op-code 0.

### Followup 3 — the ndarray gap, closed at the right layer

`ndarray::simd::masked_strided_group_sum`. The consumer's hand-rolled loop is
now one delegating call. The upstream kernel is scalar and says why with the
reasoning: one small register per record at a large stride is memory-bound,
records are not adjacent so several cannot be vector-loaded, and widening six
`u16`s inside one record optimises the part that is already free. A contiguous
variant would genuinely vectorise and is named as a DIFFERENT primitive rather
than a flag.

## 2026-08-25 — E-LGJ-THE-GATE-NOW-HOLDS-AT-EVERY-MINOR-1

**Status:** SHIPPED — the minors-2-4 half of the eager-init defect, closed.
**Confidence:** High — reproduced at every historical minor against REAL
libraries before the fix, verified after, and disable-verified per minor.

Closes the gap `E-LGJ-A-FEATURE-GATE-DEFEATED-BY-EAGER-CLASS-INIT-1` left open
and explicitly tracked ("minors 2-4 share the defect and are NOT fixed here").

### The defect was worse at the older minors than at minor 5

Built four real libraries from this repo's own history — `bd92c58` (minor 1),
`beac5de` (2), `92a0e55` (3), `e8f0ce6` (4) — and ran the CURRENT Java against
each. `SmokeTest`, which uses nothing newer than minor 1, died every time on the
first symbol from a LATER minor:

| library | died on | which is |
|---|---|---|
| minor 1 | `lgj_rowstore_open` | a minor-**2** symbol |
| minor 2 | `lgj_rowstore_open_with_edges` | minor 3 |
| minor 3 | `lgj_mask_andnot` | minor 4 |
| minor 4 | `lgj_reduce_facet_sum` | minor 5 (already fixed) |

**Against the minor-1 library, minor-1 operations could not run.** The additive-
minor promise was not merely unenforced in that direction — it was inverted.

### The fix, and the line that is deliberately NOT moved

One lazy holder per minor (`Minor2`/`Minor3`/`Minor4`, joining `Minor5`),
initialised on first ACCESS. **The 14 minor-1 base handles stay eager on
purpose:** a library missing any of them is not an older library, it is a wrong
one, and that failure should be immediate and total. Laziness is the right answer
for "this library predates the feature", not a general policy.

### The falsifier now checks both directions, per minor

`OldAbiCompatTest` was minor-5-only and one-directional. It now gates each minor
in whichever direction the loaded library calls for: **available ⇒ the feature
must actually work; absent ⇒ `AbiMismatchException` naming that minor.** Both
halves are needed — a gate that rejected everything would satisfy a
rejection-only test, which is exactly the vacuity trap this session has now hit
often enough to check for by reflex.

Measured after the fix: minor 1 → 4 checks, minors 2/3/4 → 6 checks each, all
green, with each minor reporting the right verdict for that library.

Disable-verified per minor rather than in aggregate: reverting ONLY minor 2 to
eager, leaving 3/4/5 lazy, reproduces the `<clinit>` crash against the minor-1
library. A single minor regressing is caught.

### What this leaves

The compat suite needs artifacts this repo does not ship, so it stays out of
`AllTests` and skips loudly without `-Dlgj.oldlibrary` — a suite that silently
passes when its subject is absent is worse than one that is absent. The four
libraries are reproducible from the commits named above; the recipe is in the
test's own javadoc and in `abi.md` §2.

## 2026-08-25 — E-LGJ-A-FEATURE-GATE-DEFEATED-BY-EAGER-CLASS-INIT-1

**Status:** SIX REVIEW FINDINGS, ALL CONFIRMED AND FIXED (PR #26, pre-merge).
**Confidence:** High — each was reproduced before being fixed, and the two
substantive ones are disable-verified.

Operator review of the minor-5 work found six things **117 Rust + 263 Java green
gates did not falsify.** Recorded because the pattern is the lesson: every one
sat in a place no test was pointed at.

### 1. The guard was defeated by the class it guards (the serious one)

`Abi.requireMinor(N)`'s own javadoc promises it "fails loudly, **before any
downcall for the feature is attempted**". It could not: every handle in
`Downcalls` is a `static final` resolved in `<clinit>`, and `mh()` throws on an
absent symbol. Reproduced against a REAL ABI 0.4 library built from merged
`main`: `SmokeTest` — which touches nothing newer than minor 1 — died in
`Downcalls.<clinit>` on the missing `lgj_reduce_facet_sum`. The guard never ran.

Fixed with a nested `Minor5` holder (initialised on first ACCESS, not with
`Downcalls`), and `OldAbiCompatTest` now proves BOTH halves against the real 0.4
`.so`: a minor-1 fluent count still succeeds, and `facetSumAs` alone fails with
`AbiMismatchException` naming the minor. Disable-verified — reverting the holder
reproduces the `<clinit>` crash.

**The same latent defect applies to minors 2-4** and is NOT fixed here: their
handles are still eager. Pre-existing, not introduced by this PR, and its own
change with its own falsifier. Recorded so it is tracked rather than rediscovered.

### 2. The normative ledger contradicted itself on a membrane change

`abi.md` still said `LGJ_ABI_MINOR = 4`, "currently 21 symbols", a history
stopping at minor 4, and a status table stopping at `-14` — while §14 and the §7
header had been updated to 22 symbols and `-15`. The one document that must never
self-contradict on a membrane change did. Now consistent at minor 5.

### 3. Claimed ClassView authority the symbol cannot verify

`Carving`'s doc said the reading "is resolved through its ClassView", and the
method took any `(FacetId, Carving, Mask)` triple. But a mask is an **opaque
population** — the test itself unions rows from classids 1 and 2 and applies one
carving to all of them — and the fixture ClassView has **no carving resolver at
all**. The primitive cannot check what it claimed.

Fixed by naming it honestly rather than faking the authority: **`facetSumAs`**, a
raw reinterpretation primitive whose caller owns correctness. The stronger shape
is recorded as the next rung, not pretended:
`classid → ClassView → ResolvedCarving → (population + its carving) → sum` —
binding the answer to the population ONCE, which keeps the ALU receiving an
answer rather than a question while making the binding checkable. Deliberately
NOT a per-row consult, which would put the entropy straight back in the loop.

### 4. `i64` is not closed under the reduction

The kernel used `wrapping_add`. Under quads one row contributes up to
`3 × (2³² − 1)`, so `i64::MAX` falls after ~715 827 882 maximum-valued rows —
about 341 GiB of 512-byte rows, INSIDE this substrate's contemplated scale, not
safely beyond it. §14 merely said "widened to i64" and defined no wrapping
semantics. Now accumulates in `i128`, range-checks once, and returns
`LGJ_ERR_SUM_OVERFLOW` (-16) with `out_sum` untouched. Silent wrapping is exactly
the plausible-but-wrong result this ABI otherwise works to prevent.

### 5-8. Four cleanups, each a small untruth

Rust export doc named `LGJ_ERR_INVALID_ARGUMENT` while the code returned
`LGJ_ERR_UNSUPPORTED_CARVING`; `Downcalls`' header still counted 20 handles;
`Engine.java` stranded `eqClassid`'s javadoc above the new method; and the
complexity claim said "proportional to popcount" when the implementation scans
every mask word regardless — now `O(mask_words + popcount × groups)`.

### 9. An overclaimed reachability, corrected

The early-break test called an overlong mask-word slice ABI-reachable. It is not:
`registry` allocates at exactly `mask_words_for(n_rows)` and boxes that slice.
Test kept — a kernel correct only on well-formed input is a latent bug, and the
`rlib` has callers other than `exports.rs` — but reclassified as internal kernel
robustness rather than a membrane case.

### The open question this review left standing

**Is `sum` the first product operation, or R8's checksum escaping the lab?** The
ABI's own law says symbol growth is a design smell needing justification. R8
proved the *execution shape*; it did not prove that "sum packed rails/triplets/
quads" deserves permanent ABI vocabulary. If no consumer needs facet sum, the
honest move is to keep the shape and drop the operation. Left open deliberately —
it is a product question, not an engineering one.

## 2026-08-25 — E-LGJ-THE-MASK-PATH-WAS-HALF-WIRED-ALL-ALONG-1

**Status:** SHIPPED — ABI minor 5, `lgj_reduce_facet_sum` (`docs/abi.md` §14).
**Confidence:** High. 117 Rust + 263 Java checks green; every guard disable-verified
red-then-green on both sides of the membrane.

### The finding, before the code

"Wire the mask path into lgj-abi" turned out to be **half a task**, and reading the
membrane before writing to it is what showed that:

- **The BUILD half has existed since ABI minor 2.** `lgj_op_eq_classid` already turns a
  classid column into a mask, and already routes through
  `ndarray::simd::eq_u32_strided_to_mask` — the *same* primitive R8 arm E' measured. The
  whole mask algebra (`create`/`and`/`or`/`andnot`/`count`/`describe`) was already there,
  and `lgj_mask_create` already accepted a row store as parent.
- **The EXECUTION half was the gap.** Nothing could CONSUME a mask against the 12-byte
  facet register. `lgj_reduce_sum_i32` sums a contiguous `I32` *pattern* lane — not a
  strided facet register under a carving. So a consumer wanting the R8 E' shape had to
  leave the membrane, which is exactly the pressure the Missing-capability STOP rule
  exists to relieve.

Recording this because the pre-reading is the reusable part: the instinct was to build a
mask surface, and the mask surface was already 80% shipped.

### What landed

`lgj_reduce_facet_sum(res, facet, carving, mask, out_sum)` — sums every group of one
facet's 12-byte register, under `carving`, over the rows a mask selects. Work is
proportional to the mask's POPCOUNT, so §6's bulk-or-lifecycle rule holds by construction
and an empty mask costs O(words).

**The carving is a caller-supplied, VALIDATED parameter, not a ClassView consult — and
that is the load-bearing design decision.** It follows `lgj_hop`'s `decode_mode`
precedent (§13) rather than `edge_participation`'s consult, because the reading is what
the caller already resolved from the ClassView *before* crossing. Re-resolving it per row
inside the sweep would put the question back in the hot loop — which is the exact thing
the symbol exists to take out of it. A per-row ClassView consult here would be the
mask-native law's own defect, one layer down.

New status `LGJ_ERR_UNSUPPORTED_CARVING` (-15), checked FIRST so `out_sum` is provably
untouched on rejection. Deliberately not a reuse of `-14`: that names the edge-decode
axis, and an unknown register reading must never alias a known one.

### SIMD provenance: a NAMED GAP, not a quiet scalar

The kernel is **scalar, deliberately.** `ndarray::simd` has no primitive for "gather a
sub-word group out of a 512-byte-strided register under a runtime grouping and
widen-accumulate" — `masked_sum_i32` is contiguous `i32`, `eq_u32_strided_to_mask` reads
ONE aligned `u32` per row, not six unaligned `u16`s. Writing raw intrinsics here would
create precisely the second SIMD surface §8 exists to prevent. So the vector form belongs
in `ndarray::simd` under the W1a consumer contract — added THERE, consumed here, never
re-implemented at this layer. Stated in the kernel doc, in `abi.md` §14, and here, so it
is a tracked gap rather than an unexamined choice.

(Sub-word loads are byte-wise rather than `u16`/`u32` reads because a group's offset is
`facet*16 + 4 + g*group_bytes`, not guaranteed aligned for the 3-byte reading — and an
unaligned wide read is UB in Rust even where the hardware tolerates it.)

### A vacuous test, caught by its own disable-run

`an_empty_mask_never_touches_the_buffer` claimed to cover BOTH the mask-selection property
and the `base_row >= n_rows` early break. The disable-run proved it covered only the
first: with all-zero words the inner loop never runs either way, so removing the break
changed nothing. The break's real job is preventing `n_rows - base_row` from UNDERFLOWING
for a word beginning past the row count — a reachable input, since `lgj_mask_create`
rounds up to whole words. Split into a second test with a set bit in a word starting at
row 128 over a 64-row store; that one goes red under the disable.

**Five Rust disables and two Java disables, all red-then-green:** ignore the carving,
ignore the mask, drop the tail clamp, drop the early break, accept any carving wire value;
and through the membrane, ignore the carving argument and accept a foreign-parent mask.

### Java surface

`RowStore.facetSum(FacetId, Carving, Mask)` beside its build-half partner
`maskOfFacetClass`, plus a public `Carving` enum whose wire encoding is package-private —
a consumer names the reading, never its encoding. `FacetSumParityTest` recomputes every
expected value **in Java from the public per-row accessors** (`payloadLow64At` +
`payloadHi32At`, reassembled into the 12 bytes and re-carved), never by calling
`facetSum` twice. The two paths share no code, so agreement is evidence rather than
tautology.

## 2026-08-25 — E-LGJ-THE-MEASUREMENT-LEDGER-DRIFTED-TWICE-SO-THE-REPORT-IS-NOW-GENERATED-1

**Status:** DEFECT FOUND TWICE, REPAIRED STRUCTURALLY (R7, R8; merged in PR #24).
**Confidence:** The defect and its repair are certain. The measurements are ranges,
regenerable, and deliberately not pinned as absolutes — see below.

### The defect

`R7-observed.txt` shipped with its READING prose quoting a throughput range its OWN pinned
raw runs contradicted (prose said 369-439 M ops/s; the three pinned runs said 1.76-3.48 s,
i.e. 287-567). Operator review caught it. It was repaired — and then **R8 committed the
identical defect one commit later**: prose quoting 2417-2479 / 3959-4014 / 41.01 ms while
its own regenerated raw block held 3172-3248 / 3841-3946 / 40.36 ms.

**Root cause is mechanical, not attentional.** The prose was hand-copied from run N while
the raw block was regenerated at run N+1. Every artifact built that way is one
regeneration away from lying about itself. "Be more careful" does not fix a method that
produces the defect by construction — twice in two consecutive commits is the proof.

### The repair

`valhalla-lab/reproducers/r8_report.py` GENERATES the report: it runs every arm, parses
the output it just captured, and derives every quoted range, ratio, per-pass cost and
break-even from that same output. Raw block and prose come from one subprocess result and
cannot disagree. The generated file states this at its head and instructs regeneration
rather than hand-editing.

The README's R8 section was also rewritten to stop duplicating absolute figures at all: it
states structural results and ratios, and names the generated file as the authority.

### Why that turned out to be the right shape, not merely a safer one

The first regeneration produced materially different absolutes — B' moved ~25% — while
EVERY structural conclusion held identically: B ~ standalone, D > B falsified, C ~30x,
the B' collapse, the ~4.8x D'/E' recovery, the end-to-end E' win. **The stability of the
conclusions under unstable absolutes is itself the result**, and it is only visible
because the numbers are regenerable rather than pinned. A hand-pinned artifact would have
hidden it.

### Two further corrections from the same review, both real

1. **Toolchain was not unified.** The native kernels and standalone baseline built with
   rustc 1.94.1 while the ndarray crate required 1.97.1. That left an escape hatch on the
   load-bearing "one bulk FFI crossing costs nothing measurable" claim, since the control
   and the arm were not the same compiler. All three artifacts now build with 1.97.1,
   `-O -Ctarget-cpu=x86-64-v4 -Cdebuginfo=0`.
2. **Sweep-only comparison understated the lawful shape.** R8 first reported E' (masks) as
   "2-3% slower" than D' (index lists). That compares SWEEPS, and the sweeps are tied. The
   whole difference is BUILDING the population, which must be counted:
   `ndarray::simd::eq_u32_strided_to_mask` (one bulk call) is an order of magnitude cheaper
   than the Java scalar partition scan, so **E' wins END-TO-END on the first execution**,
   moving break-even from ~120 passes to ~10 — and leaves behind a reusable mask where D'
   leaves a materialized population the mask-native law forbids as internal currency.
   Obeying the law is the fast path, not a tax on it.

### The measured architecture (R6-R8), for the record

- **R6:** the 8-byte cliff is JEP 401 BY DESIGN, not a version gap and not a flag. These
  already are the JDK 27 numbers (27-jep401ea3); forcing all five flattening flags changes
  nothing (`UseArrayFlattening`/`UseFieldFlattening` are `false` by DEFAULT in that build,
  which is why the flags-on run had to be done rather than assumed). JEP 401 states the
  cause: flattened references must be read/written atomically, capping mutable flattened
  fields at 64 bits. The exemption it names is for value-class FIELDS; SoA lanes are
  ARRAYS, whose elements are mutable by definition. Its speculative 128-bit note would move
  the cliff to 16 B — the 12-byte register would fit, the 512-byte row would not.
- **R7:** 10^9 projections allocate 960 B TOTAL. Against R5's 65,536 ops at 800 B:
  operations grew 15,000x, allocation grew 160 B — fixed scaffolding, not per-op cost.
- **R8:** five arms, checksum-identical including a standalone Rust process. Bulk FFI is
  free; per-projection FFI is ~30x (the anti-JNI rule, quantified); specialization buys
  NOTHING when dispatch is branch-predictable (part 1 is the control that keeps part 2
  honest); under random classids the split architecture wins ~4.8x because the selector
  layer creates the information once, before the sweep.

**Not "Java is faster than Rust"** — the winning kernels ARE Rust and so is the mask
builder. The win is specialization PLACEMENT. The durable principle: **entropy belongs
outside the hot loop**, and its scope leg — when there is no entropy, moving it buys
nothing.

## 2026-08-25 — E-LGJ-LAYOUT-AUTHORITY-IS-TRANSFERABLE-BUT-ONLY-ABOVE-8-BYTES-1

**Status:** MEASURED (R4, R5, `valhalla-lab/reproducers/`, JDK 27 EA).
**Confidence:** High for the measurements; the division-of-authority rule
below is the reading of them and is open to a counter-measurement.

**Board-discipline note, first:** the R4/R5 code landed in `6828f4a` WITHOUT
this entry, which breaks this repo's own same-commit rule. Recorded here
rather than quietly back-dated.

### What was asked

Can the three V3 carvings (`6x(u8:u8)` / `4x(u8:u8:u8)` / `3x(u8:u8:u8:u8)`)
dodge the R2 8-byte array-flattening cliff? And can a `classid`-dependent
layout be expressed at all?

### What was measured

1. **The cliff is on TOTAL PAYLOAD.** Every real width is non-flat in all
   three array kinds — `Reg12AsRails/Triplets/Quads`, `Facet16As*` — and the
   monolithic control `Reg12Flat` behaves identically. The carving changes
   nothing.
2. **Nesting costs flatness even UNDER the budget** (`Nest7` false vs `Flat7`
   true), because a record component is nullable by default and stored in its
   nullable flat layout (`Pair` 2->4, `Quad` 4->8). Confirmed by mechanism,
   not inferred: `@NullRestricted` flips all three predicted failures
   false->true. Removing the inflation still does not rescue 12/16 B.
3. **`isFlatArray()` alone is not a sufficient test.** `Four8AsTwo8`, a
   32-byte record, reports flat at VM **element size 8** — its
   `@NullRestricted Two8` components are themselves non-flattenable and are
   stored as REFERENCES. A flat array of pointers is the opposite of the
   property being sought. `Nest8Single` is the inverse hazard: 8 B payload,
   element size 16. R4-observed.txt now pins element sizes beside every
   boolean. Answering the operator's `32x(2x8 byte)` question from the
   boolean alone would have shipped a false positive.
4. **Neither mechanism can express a runtime-selected layout.** A Panama
   `VarHandle` binds its path at construction; a value class is a static type.
   The carving choice is a Java-side switch in every possible design.
5. **Cost of giving Java a row type, 65,536 rows:** project (no element type)
   800 B total / 0.01 B/row, identical every run. Hydrate (16-byte `Facet`)
   32-104 B/row, varying by run because escape analysis is best-effort. The
   3x spread across identical runs is the finding, not noise.

### The reading — authority is transferable, and Valhalla is not crippled by it

Java's layout authority engages ONLY on types Java instantiates. The projecting
path never gives it one, so the authority never engages: that is why its cost is
both zero and *stable*, while the hydrating path's cost is decided by the
compiler per run.

So the division is measurable rather than aesthetic:

- **payload > 8 B** — Rust/the contract holds layout authority. Java sees a
  descriptor, a handle, or a mask. This is the row, the facet, the register,
  the 512-byte canonical stride.
- **payload <= 8 B, unnested** — measured flat, so Java may hold it: `Pair`
  2 B, `Triplet` 3 B, `Quad` 4 B, `Lane8` 8 B are all `true`. Handles,
  versions, coordinate pairs, a single rail value.

Valhalla keeps a real, measured domain; it simply was never the right tool for
the ROW. **The move that would cripple both is the opposite one** — trying to
make a value class express the 12/16/512-byte payload, which R4 shows cannot
work and which costs the stability measured in R5.

### Consequences

- The carving is sound **as SoA and only as SoA**: N parallel rail arrays,
  each element under the budget, never one `Facet[]`.
- Do NOT change the substrate layout to chase Java flatness. Past 8 bytes the
  width is irrelevant to Java's decision, which is exactly what leaves the
  substrate free to choose its stride for cache/Morton reasons.
- Never report `isFlatArray()` without the VM element size beside it.

## 2026-08-18 — E-LGJ-ERGONOMICS-MUST-NOT-LEAK-INTO-CURRENCY-1 (STORNO, operator-ruled, council-ratified)

**Status:** RULED — operator CORRECTION WAVE + RULING CLARIFICATION +
A1 ARCHITECTURE RULING (all 2026-08-18), ratified by the full 5+3
council (spec v3: `.claude/plans/mask-native-navigation-correction-v1.md`).
**Confidence:** Operator-locked.

### What this corrects

`Graph.java:18-19`'s claim that the `long[]` frontier is *"a deliberate
simplification for this consumer example, not a workaround."* That
sentence is VALID for the bounded fixture it shipped in — and INVALID
as target precedent. The entries below in this log (the graph-wave
entries of 2026-08-18) accurately recorded the decision as made; what
they could not know is that the currency itself was drift. The
sharpest evidence that fixtures calcify: `GraphHopTest:459`'s own
reflective allowlist had begun ENFORCING `long[]` as the sanctioned
non-scalar egress — the guard test was defending the drift.

### What PR #18 DID prove (preserved, not deleted)

Membrane-surviving edge data (the 10/19/29 pinned hop counts reproduced
cross-language), falsifiable hop semantics (set-equality caught a +4
decode corruption a count check missed), measurable crossing cost
(first-hop 2 / steady-state 1 — measured, then pinned), and a viable
fluent vocabulary (`from`/`hop`/`minus`/`count`). It remains the SCALAR
REFERENCE ORACLE — both independent BFS transcriptions stay verbatim.

### What it did NOT prove

That `long[]` is a frontier currency, that `TreeSet<Long>` is a query
substrate, or that per-row payload reads are a target execution model.
**Zero serialization does not imply zero-copy semantic navigation — a
`long[]` of selected row IDs is still a materialised population.**

### The ruling (the law this repo now carries)

The three currencies — ClassView (meaning, late-bound provider) /
WideFieldMask–FieldMask (facet participation) / Mask (population) —
come from `lance-graph-contract`, THE semantic law; the contract split
from the engine is blast-radius containment, NOT semantic optionality.
The correction is one architectural closure: contract →
ClassView/FieldMask/WideFieldMask → mask-native population ops →
lgj-abi → Java ergonomic facade, complete only when Java ergonomics
navigate the contract-governed substrate without row hydration. The A1
ARCHITECTURE RULING extends the same law to COMPUTE: the 64K parallel
SoA / deterministic-landing / batch-version model is inherited as
architecture (parallel compute PROVEN in-tree via the EXP-KIA
concurrency probe; the ~125/233 ms figures are operator-measured with
the receipt currently out-of-tree; the batch-publication sole writer is
shipped but not yet production-wired) — with the landing-key Rubicon:
CastId/arrival order never becomes semantic, and the generic parallel
write API stays BLOCKED until landing identity is arrival-order
independent. Root `CLAUDE.md` (created with this entry) is the policy
guard; wave D-LGJ-W8 (STATUS_BOARD) is the execution record.

### Honest note

The drift was self-documented at every step by this repo's own board
hygiene — the D1 ruling, the javadoc, this log — which is exactly why
it was correctable by audit instead of archaeology. The failure mode
was not silence; it was a locally-reasonable decision calcifying into
precedent because its own guard test enforced it.

## 2026-08-18 (final for now) — the graph wave's own crossing-cost assumption was wrong, measured and fixed before shipping

**Status:** FINDING, caught during orchestrator review of dispatched
worker output, before the wave's PR landed. **Confidence:** High —
measured directly with a standalone 4-hop probe before touching any
shipped file.

### What G2 assumed, and why it was a reasonable but wrong guess

G2 (the falsifier-test worker) wrote a crossings-proportional-to-hops
check asserting that a second hop on the same store costs exactly what
the first hop cost. Reasonable, since `Graph.hop()`'s own javadoc (as G1
wrote it) claimed "pays exactly one native crossing... for the whole
hop" unconditionally. Running the suite for the first time surfaced two
failures: `expected 2 but was 1` (second hop) and `expected 4 but was 3`
(two hops together).

### The measurement, not a guess

Rather than loosen the assertion to "roughly proportional" — which would
have shipped a WEAKER test to make a genuinely interesting result
disappear — wrote a standalone 4-hop probe against the same pinned
fixture and measured the actual per-hop cost sequence: **2, 1, 1, 1.**
Traced the mechanism: `Graph.hop()`'s first payload read
(`RowStore.payloadHi32At`/`payloadLow64At`) on ANY given store triggers
`RowStore`'s own lazily-resolved raw lane-0 window (`rawLane()`, cached
as a private field ON THE STORE — see D-LGJ-W7's own entry above) — a
one-time `lgj_lane_describe` crossing. Every `Graph` derived from the
same `open(store)` call shares that one `RowStore` instance, so the cost
is paid exactly once per store, not once per hop and not once per
`Graph`. Hop 1 = `facetMatches` (1) + `rawLane` init (1) = 2. Hop 2
onward = `facetMatches` (1) alone = 1, forever, for that store.

### The fix — correct both the doc and the test, not just the test

`Graph.hop()`'s javadoc gained a "Measured, not merely designed"
paragraph stating the true relationship, with the measured sequence
cited directly. `GraphHopTest`'s crossing section was rewritten to
measure THREE consecutive hops (not two) — first-hop cost asserted at
exactly 2, second AND third hop cost each asserted at exactly 1, and
second-equals-third asserted explicitly (proving steady-state holds
past the second hop, not merely that hops 1 and 2 happen to differ by
coincidence). All three source-row counts across the three hops differ
(10, 19, 29-ish), so the steady-state-cost claim is checked against
genuinely different inputs each time, not the same fixture re-measured.

### Why this is worth recording as its own entry

This is the wave's version of the falsifiability rule cutting the other
way: not "the test was vacuous," but **"the test's ASSUMPTION was wrong,
and a real measurement — taken before editing anything — turned a
guessed invariant into a precisely stated, three-times-confirmed one."**
The corrected result is actually a STRONGER, more informative claim than
the original "1 crossing per hop, full stop" — it names the one-time
setup cost explicitly instead of hiding it inside an average, and it
matches the wave design doc's own stated target (1 per hop) in the
correct sense: amortized, steady-state, not literal from the very first
call.

### Gates

`GraphHopTest` 43/43 after the fix (was 41/43 before, with the two crossing
assertions red on the actual, correct implementation — the bug was in the
TEST's assumption, not in `Graph`). Core suite 204/204 unaffected; trades
(12+3) and bricks (62) unaffected. Disable-run (target-decode offset
corrupted by +4) verified red-then-green: hop correctness's SET-equality
check caught the corruption even though the row COUNT coincidentally still
matched — direct vindication of G2's choice to check the set, not just its
size, per the wave's own falsifier #1 requirement.

### Consequence

All three planned consumer examples from `lgj-vertical-slice-v1`/
`lgj-soa-substrate-v1` — trades, bricks, graph — are now DONE. Full
record: `STATUS_BOARD.md` D-LGJ-W5 (graph row).

## 2026-08-18 (even later still) — the same gap, one layer up: no PUBLIC path to a payload either, and a self-caught vacuous disable-run

**Status:** FINDING + a correction of the entry directly below (which
declared the substrate complete at the ABI level, but not at the public
core-facade level) + a process note about catching my OWN vacuous
falsifier rather than an existing one. **Confidence:** High — measured
(full test suite before/after, two disable-runs, one of them initially
wrong and caught by re-reading rather than trusting a green result).

### What the ABI-minor-3 entry's own "genuinely dispatchable" undersold

Immediately after landing `lgj_rowstore_open_with_edges`, worked through
concretely — not just in principle — how `consumers/graph`'s `Graph.hop()`
would decode a matched facet's target row. It could not:
`RowStore.facetMatches` returns a per-row **bitset** of which facets
matched a classid; it never carries the matched facet's payload bytes.
The only capability that ever read raw row bytes at all was
`internal.ffm.Engine.describeLane`, and `ApiSurfaceTest` forbids
`internal.*`/`MemorySegment` from appearing in any consumer-package public
signature, by design, mechanically enforced. Decision D1a's own text —
*"read matched facets' payloads via the raw lane 0 segment (zero-copy, no
crossing)"* — assumed a capability that existed only inside the core
package's own internals, never surfaced to a consumer. Same shape of gap
as the ABI-symbol one, one layer higher: **"the mechanism exists
internally" was mistaken for "a consumer can reach it" twice in the same
wave.**

### The fix — zero new ABI surface, reuse what already exists

`RowStore.classidAt(long row, FacetId facet)` / `payloadLow64At(...)` /
`payloadHi32At(...)`: three new public, primitive-returning methods. No
new `extern "C"` symbol, no ABI minor bump — they reuse `lgj_lane_describe`
(already minor 1, already classified "lifecycle" per abi.md §6), resolved
once per store and cached; every subsequent call is an in-process segment
read with zero further crossings, matching `exports.rs`'s own stated
doctrine verbatim: *"if Java wants one row it reads the MemorySegment
in-process, with no crossing at all."*

Added to `RowStoreParityTest`: the SAME pinned hop numbers (19 at 1 hop,
29 at 2 hops) reproduced a SECOND time — this time through the genuinely
public path — proving not just that the mechanism works, but that a real
`consumers/graph` package can actually reach it. `AllTests` 204/204
(+10 over the prior entry's 194).

### The self-caught vacuous disable-run — worth recording precisely

First draft guarded the closed-store check in `rawLane()` (the method that
actually touches the native pointer) AND, redundantly, in `rowOffset()`
(pure arithmetic, touches nothing). Disabled `rawLane()`'s check to prove
it load-bearing — and the full disable-run test suite came back **30/30
green**, under code that was genuinely broken. The natural move at that
point is to trust the green result and move on. Instead: asked WHY it
didn't fail, and found the answer in Java's own evaluation order —
`a.method(args)` evaluates the receiver `a` before the argument list, so
`rawLane().get(..., rowOffset(row, facet))` always runs `rawLane()` first;
disabling only `rawLane()`'s guard left `rowOffset()`'s redundant copy to
catch the closed-store case anyway, masking the disable entirely.

De-duplicated to the single correct location (the guard belongs on the
method that touches the pointer, not on pure arithmetic downstream of it)
and re-ran the SAME disable-run against the corrected code: this time it
went red exactly as expected (`expected ClosedResourceException but
nothing was thrown` on both affected checks), then green on restore.

**The generalizable rule, sharper than the falsifiability rule's usual
form:** a disable-run that stays green is not automatically a passing
grade for the CODE — it may be a failing grade for the TEST's isolation.
Redundant guards are the one shape of bug a disable-run can silently
paper over, because disabling one leaves the other standing. The fix
generalizes past this file: when a disable-run doesn't fire, the next
question is never "good, unaffected" — it is "did I disable the thing
that actually runs, or a copy of it."

### Gates

`javac -Xlint:all` clean (same 7 pre-existing `[restricted]` warnings, zero
new). `AllTests` **204/204**. `ApiSurfaceTest` unchanged at 3/3 — zero FFM
type introduced into any public signature. Both disable-runs (bounds
guard via `IndexOutOfBoundsException`, closed-store guard via the
corrected single location) verified red-then-green.

### Consequence

The graph-consumer wave is now dispatchable on genuinely solid ground,
proven at three independent, individually-tested levels: the Rust
generator (below), the ABI membrane (below), and the public core facade a
`consumers/graph` package can actually compile against (this entry). G1
(traversal facade) and G2 (falsifier tests) are next, not yet spawned.

## 2026-08-18 (later still) — "the generator exists" ≠ "Java can reach it": the membrane gap the prior entry's own resolution note missed

**Status:** FINDING + a correction of the entry directly below this one.
**Confidence:** High — measured (`nm -D` before/after, full Rust + Java test
suites, two disable-runs).

### What the prior entry's "RESOLVED" undersold

The STOP-condition entry below this one — and `wave-consumer-graph.md`'s own
header — declared the graph wave DISPATCHABLE once
`RowStore::generate_with_edges` existed and its numbers were pinned. True at
the Rust level, but incomplete: `Engine.openRowStore` /
`registry::open_rowstore` (the only path Java has to a `RowStore`) call
plain `RowStore::generate` unconditionally. **No `extern "C"` symbol for the
edge-bearing generator existed.** A worker briefed to build `Graph.java`
against `RowStore.open` would have had no way to reach non-vacuous data at
all — the exact STOP condition this wave was declared clear of, just moved
one layer up the stack, and it would have surfaced as a live "the file I
need is outside my scope" STOP report from a Sonnet worker mid-dispatch
rather than being caught here, before any worker spawned.

Same shape as the finding this whole sub-arc started from: checking the
*mechanism* (does a hop composition exist) without checking the *path*
(can a caller actually reach the data the mechanism needs). Twice now in
one wave.

### The fix — the wave's own D1b rule, applied to itself

`wave-consumer-graph.md`'s Decision D1 already states the rule for exactly
this shape of gap: *"a new ABI symbol... must go through the substrate wave
process FIRST as its own W-tier PR (the consumer-never-grows-the-membrane
rule)."* Applied it to the row-store CONSTRUCTOR, not just the hop op D1
was originally about — same rule, same reasoning: growing the membrane is
orchestrator/W-tier work, never a consumer worker's ad hoc addition.

`lgj_rowstore_open_with_edges` (ABI minor 2→3, `docs/abi.md` §12):
`registry::open_rowstore_with_edges` + the `extern "C"` export mirror
`open_rowstore`/`lgj_rowstore_open` symbol-for-symbol — same
`LGJ_RESOURCE_ROWSTORE` kind, same lane shape, no new mask op, purely an
alternative constructor. Java: `Downcalls.rowstoreOpenWithEdges` +
`Engine.openRowStoreWithEdges` (`Abi.requireMinor(3)`, matching the row
store's own minor-2 gate pattern) + the public `RowStore.openWithEdges`
factory.

### The strongest new result: Java independently reproduces the D1a hop, not just the classid stream

Added to `RowStoreParityTest` rather than deferred to G1/G2: a **Java-side
transcription of the exact D1a mechanism** (`lgj_row_facet_match` crossing +
raw lane-0 payload decode, zero new ABI op — the mechanism the wave's own
Decision D1 chose to start with) at the SAME parameters as the Rust-pinned
regression (`n=2000, seed=0xF00D_CAFE, edge_classid=0, gate_mask=0x0,
radius=25`). Result: **19 rows at 1 hop, 29 at 2 hops — identical to the
Rust side, to the row.** This is a stronger falsifier than the Rust
regression alone: it proves the membrane doesn't just carry the same
classid stream, it carries the same *edge structure*, read through the
exact mechanism a real `Graph.hop()` will use.

Two disable-runs, both red-then-green:
1. **Registry level:** hardcoded `open_rowstore_with_edges`'s inner call to
   pass `edge_classid = 0` regardless of the argument → the new
   `out_of_range_edge_classid_matches_plain_open_through_the_registry` test
   (which passes `edge_classid = 16`, expecting parity with plain `open`)
   went red, because classid 0 with `gate_mask = 0x0` genuinely writes
   structured edges the plain generator never would. Restored, 93/93.
2. **Java level:** forced the hop transcription's classid-match check to
   always skip (`if (true) { continue; }`) → 1-hop and 2-hop both collapsed
   to 0 and the anti-vacuity assertion failed exactly as expected. Restored,
   194/194.

### Gates

`cargo test` 93/93 (+3 over the prior entry's 90), clippy `-D warnings` +
`fmt --check` clean, release build exports `lgj_rowstore_open_with_edges`
(`nm -D`, confirmed present). Java `AllTests` 194/194 (+6, all in
`RowStoreParityTest`) — full suite re-run, not just the new section, since
the stale top-level `target/release/liblgj_abi.so` (pre-dating this pass,
minor 2) initially made EVERY suite fail at class-init (`Downcalls`
eagerly resolves all method handles including the new one) until rebuilt
with `CARGO_TARGET_DIR=$ROOT/target cargo build --release` per the
documented build convention — a real, if brief, self-inflicted false
alarm, not a substrate defect; recorded so a future session doesn't
re-diagnose the same eager-resolution behavior as a bug.

### Consequence

The graph-consumer wave is now genuinely dispatchable — the substrate is
proven at BOTH the Rust generator level (prior entry) and the Java
membrane level (this entry), through the exact D1a mechanism the wave
already chose. G1 (traversal facade) and G2 (falsifier tests) are next,
not yet spawned.

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

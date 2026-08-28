# Reproducers — Valhalla limitations hit while expressing the ideal API

Three limitations were hit. **None of them changed the API.** Where the ideal shape could not be
expressed, that fact is recorded here and the production types stayed as they are — distorting a
public API to fit a preview VM's current budget would bake a temporary constraint into a permanent
surface.

Each reproducer is a single self-contained file with its command line in the header comment, and a
`*-observed.txt` holding the exact output that file produced on this box.

| # | Limitation | Belongs to |
|---|---|---|
| [R1](#r1) | `@NullRestricted` field in an ordinary class fails at class load | **javac** |
| [R2](#r2) | Array flattening stops at an 8-byte payload | **HotSpot / Valhalla** |
| [R3](#r3) | The densest layout has no supported spelling, and generics discard it | **Valhalla (language + libraries)** |
| [R4](#r4) | Sub-group carving does not dodge R2's cliff — and nesting *inflates* the payload | **HotSpot / Valhalla** |
| [R8](#r8) | Five arms: bulk FFI is free, per-op FFI is 31x, and the LAWFUL mask shape wins | **measured, JDK 27 EA + rustc 1.97.1 @ v4** |
| [R7](#r7) | 10^9 projected operations allocate 960 B TOTAL — zero-copy at the endgame scale | **measured, JDK 27 EA** |
| [R6](#r6) | The cliff is JEP 401 by design (atomicity), not a version gap or a flag | **HotSpot / JEP 401 spec** |
| [R5](#r5) | A classid-dependent layout has no static spelling in Panama or Valhalla | **Panama + Valhalla (by construction)** |

Environment for every observation below: `openjdk 27-jep401ea3+1-1`, Linux x86-64,
Intel Xeon @ 2.10 GHz (4 vCPU, AVX-512).

---

## R1 — `@NullRestricted` on a field of an identity class {#r1}

**File:** `R1_NullRestrictedFieldInIdentityClass.java` · **Observed:** `R1-observed.txt`

**Desired semantics.** An ordinary class holds a value-typed field flat — the field is never
null, so no reference and no header should be needed:

```java
final class Descriptor {                 // ordinary identity class
    @NullRestricted final LaneId lane;   // want: 4 bytes inline
    Descriptor(int i) { this.lane = new LaneId(i); }
}
```

**Ordinary Java (JDK 26).** Compiles and runs; the field is a reference. The annotation does not
exist, so the question cannot even be asked.

**Valhalla (JDK 27 EA).** Compiles, then fails at class load:

```
java.lang.VerifyError: All strict final fields must be initialized before super():
  1 field(s), lane:LR1_...$LaneId; in R1_...$Descriptor
```

**Why.** A null-restricted field is a *strict* field: the VM requires it to be assigned before the
`super()` call. javac emits field initialisers *after* `super()`, and this build has no `@Strict`
annotation for javac to key on — `jdk.internal.vm.annotation` here contains `NullRestricted` and
`LooselyConsistentValue` but no `Strict`. There is no Java source form that expresses the required
order, so the combination is unreachable from source.

**Workaround, and its cost.** Make the container itself a `value class`; its fields are then
implicitly strict and it works. That is what `src/valhalla/.../Containers.java` does. The cost is
that the workaround is not always available: a container that legitimately has identity — anything
mutable, anything used as a lock, anything with lifecycle — cannot become a value class, and
therefore cannot hold a flat field at all on this build.

**Consequence for this project.** None yet, and that is luck rather than design: the descriptor
types (`Field` and friends) happen to be immutable. Had `NativePattern` — which is genuinely an
identity object, it owns a native resource and closes it — wanted a flat `LaneId` field, there
would be no way to write it.

---

## R2 — array flattening stops at an 8-byte payload {#r2}

**File:** `R2_FlatteningCliff.java` · **Observed:** `R2-observed.txt`

**Desired semantics.** An array of value objects is a dense block of their payloads, whatever the
payload is — that is the entire promise that makes "values are just data" attractive.

**Observed** (`ValueClass.isFlatArray`, the VM answering about its own array):

```
type   payload  NR-nonAtomic     NR-atomic        nullable-atomic
P4         4 B  true             true             true
P8i        8 B  true             true             false
P8l        8 B  true             true             false
P12       12 B  false            false            false
P16       16 B  false            false            false
P16l      16 B  false            false            false
```

The cliff is at 8 bytes and it is total: past it, **no** array flavour flattens. Confirmed
independently by `-XX:+PrintFlatArrayLayout`, which logs a layout only for the shapes above the
line (`element size 4`, `element size 8`) and nothing for the others.

**Why.** Flattening past a machine word needs either an atomic wide store or a decision to give
atomicity up; the current implementation declines both above 8 bytes. `FlatArrayElementMaxOops`
exists as a knob for reference-bearing payloads; there is no product knob that lifts the
primitive-payload ceiling on this build.

**Consequence for this project — and it is the interesting one.** The line the VM draws is exactly
the line the thesis draws:

| Type | Payload | Flat? | Which side of the thesis |
|---|---|---|---|
| `LaneId`, `Ordinal` | 4 B | **yes** | tiny descriptor vocabulary — Valhalla helps |
| `MaskId` | 8 B | **yes** | tiny descriptor vocabulary — Valhalla helps |
| `RowRange` | 16 B | no | descriptor, but already too wide |
| `Row` (id + class + value) | 16 B | no | per-entity materialisation — Valhalla does not help |

So "Valhalla helps the descriptors, not the entities" is not a hand-wave about object headers. On
this build it is a hard cutoff in the VM, and a realistic entity is on the wrong side of it by
construction: an id plus one field already exceeds the budget.

`RowRange` landing on the wrong side is worth stating plainly, because it is the one place the
expectation was too optimistic — it is a descriptor, it was expected to flatten, and it does not.

---

## R3 — the densest layout has no supported spelling {#r3}

**File:** `R3_NoSupportedFlatSurface.java` · **Observed:** `R3-observed.txt`,
`R3-bang-syntax-observed.txt`

**Desired semantics.** `LaneId![] lanes = new LaneId![n];` — an array of non-null values, densely
packed, spelled in ordinary Java.

**Observed:**

```
(1) new LaneId[8]                     flat=true  accepts null=true  (nullable-flat: pays for a null marker)
(2) LaneId![]                         DOES NOT PARSE — no null-restricted type syntax
(3) ValueClass.newNullRestricted...   flat=true  accepts null=false  (jdk.internal, needs --add-exports)
(4) List<LaneId>.toArray()            flat=false — generics erase to Object[]
```

and the syntax probe:

```
error: not a statement
    L![] x = new L![2];
    ^
```

Three separate gaps, and the first is the one most likely to be misread:

1. **Supported source already flattens — partially.** `new LaneId[8]` *is* flat for a 4-byte
   payload. It is *nullable*-flat, so it carries a null marker and is not the densest encoding, and
   by R2 it stops being flat at all past 8 bytes. Reporting "plain arrays are not flat" would have
   been wrong; the measured claim is narrower and more useful.
2. **The densest form is `jdk.internal`.** `ValueClass.newNullRestrictedNonAtomicArray` needs
   `--add-exports java.base/jdk.internal.value=ALL-UNNAMED` and its own javadoc says it "should
   only be used by internal JDK classes for experimental purposes". A library cannot ship it.
3. **Generics erase, so the boundary undoes it.** `List<LaneId>` is `Object[]` underneath and the
   array is not flat. Any collection, stream, or generic cache reverts everything the previous two
   points achieved. Specialised generics are the missing piece and are not in this build.

**Consequence for this project.** The production API keeps `LaneId` and friends as plain `record`s
and does **not** adopt any of this. The migration path stays a one-word source change (`record` →
`value record`) precisely because nothing was bent to accommodate the current preview: no
`jdk.internal` dependency, no `--add-exports` in the shipped build, no API that hands out arrays of
descriptors.

It also removes a temptation worth naming: if `List<LaneId>` had flattened, "just hand the caller a
`List<Row>`" would look like a viable alternative to the native lane. It does not flatten, so the
bulk path is not competing with a hypothetical fast object path — it is competing with the same
boxed one Java has always had.

---

## R4 — can the carving dodge the 8-byte cliff? (`R4_CarvingVsCliff.java`)

**Question.** R2 measured *monolithic* 12/16-byte value classes and found them never
flat. The V3 register is carved three ways (`6x(u8:u8)` rails / `4x(u8:u8:u8)` triplets
/ `3x(u8:u8:u8:u8)` quads). Does spelling the same total as a *composition* of
sub-8-byte value classes behave differently?

**Answer: no, and the nested spelling is strictly worse.** Observed output is pinned in
`R4-observed.txt`.

- The sub-groups alone are perfectly flat: `Pair` 2 B, `Triplet` 3 B, `Quad` 4 B — all
  `true` in all three array kinds.
- Every real width is `false` in all three kinds: `Reg12AsRails`, `Reg12AsTriplets`,
  `Reg12AsQuads`, `Reg12Flat`, `Facet16AsRails`, `Facet16AsQuads`. The carving changes
  nothing; the monolithic control `Reg12Flat` behaves identically.
- Nesting costs flatness *even under the budget*: `Nest7` (3+4 B) is `false` while the
  unnested `Flat7` is `true`. Mechanism: a record component is **nullable** by default,
  so it is stored in its nullable flat layout (`Pair` 2→4, `Quad` 4→8), and it is the
  *inflated* sum the budget must satisfy.
- That mechanism is confirmed, not assumed: `@NullRestricted` flips all three predicted
  failures — `Nest7`→`Nest7NR`, `Nest8AsQuads`→`Nest8AsQuadsNR`,
  `Nest6AsPairs`→`Nest6AsPairsNR`, each `false`→`true`.
- Removing the inflation still does not rescue the real widths: `Reg12AsQuadsNR`,
  `Reg12AsRailsNR`, `Facet16AsQuadsNR` remain `false`. **The cliff is on total payload.**

**Actionable consequence.** The carving is sound *as SoA and only as SoA*: N parallel
rail arrays, each element under the budget, never one `Facet[]`.

### `isFlatArray()` alone is not a sufficient test — read the element size

Group F (the word-aligned family, added when the operator asked whether `32x(2x8 byte)`
would behave differently) produced a **non-monotone** row that looked like good news and
is not:

| type | payload | isFlatArray | VM element size |
|---|---|---|---|
| `Lane8` | 8 B | `true` | 8 |
| `Two8` | 16 B | `false` | — |
| `Two8NR` | 16 B | `false` | — |
| `Four8AsTwo8` | **32 B** | **`true`** | **8** |
| `Blk64AsTwo8` | 64 B | `false` | — |

A 32-byte record reporting flat at **element size 8** is not carrying 32 bytes inline.
Its two `@NullRestricted Two8` components are themselves non-flattenable, so each is
stored as a **reference**; the array is flat *in pointers*, which is the exact opposite
of the property being sought. `Nest8Single` shows the same hazard from the other side:
an 8-byte payload at element size **16**.

So the rule is: **never report `isFlatArray()` without the VM's element size beside it.**
`R4-observed.txt` now carries both, from `-XX:+UnlockDiagnosticVMOptions
-XX:+PrintFlatArrayLayout`. Answering "does 2x8 grouping help?" from the boolean alone
would have shipped a false positive.

## R5 — a classid-dependent layout has no static spelling (`R5_ClassidHasNoStaticSpelling.java`)

**Question.** The classid's ClassView chooses which carving applies. Can either
mechanism express a layout selected by a runtime value?

**Answer (tightened on operator review, 2026-08-25 — the first wording, "neither Panama
nor Valhalla can select a layout by runtime classid", was broader than the measured
fact):** runtime classid requires **descriptor/accessor dispatch**. Panama absolutely can
*construct or choose* a `MemoryLayout` at runtime after seeing a classid; what it cannot
do is make one already-bound `VarHandle` reinterpret its path per row. A Valhalla value
class is a static type and cannot be selected by a runtime `int` at all. So layout
selection cannot live *inside* one statically bound value type or one already-bound
`VarHandle` — the carving choice is a Java-side dispatch in every possible design, and
the question is only what it dispatches over (the ClassView schema preset).

**Measured, 65,536 rows** (`R5-observed.txt`):

| strategy | allocated |
|---|---|
| project — classid-dispatched, no element type | **800 B total / 0.01 B/row** |
| hydrate — a 16-byte `Facet` value object per row | 32–104 B/row, varying by run |

The hydrate spread across four identical runs (32.01, 37.24, 104.01, 101.76 B/row) is
the finding, not noise: escape analysis is best-effort, nothing in the source chooses
whether it fires, and the cost ranges over 3x between runs of the same binary. Project
has no spread because the JVM is never given an element type to have an opinion about.

**This is the operator's insight, quantified.** Java asserts an independent awareness of
its own layout — up to 104 bytes of it to carry 16 bytes of substrate. Zero-copy
transparency is therefore not obtained by finding a better Java spelling of the row; it
is obtained by never handing Java a row type at all. **Project, do not hydrate.**

## R6 — is the cliff a version gap, a tunable, or Valhalla's design? (`R6_WhyEightBytes.java`)

R4 measured *where* the cliff is. R6 asks *why*, because the answer decides whether the
architecture call is durable or just a workaround for an early build. Three arms:

**Is it a JDK-version gap? No — this already is 27.** R4, R5 and R6 all run on
`27-jep401ea3+1-1`, the JEP 401 early-access build. There is no later JDK to upgrade to.

**Is it a tunable? No.** Forcing all five flattening flags — `UseArrayFlattening`,
`UseFieldFlattening`, `UseAtomicValueFlattening`, `UseNonAtomicValueFlattening`,
`UseNullableValueFlattening` — produces output byte-identical to the default run. (Note
that `UseArrayFlattening` and `UseFieldFlattening` are `false` by default in this build,
which is why the flags-on run had to be done rather than assumed.)

**Is it by design? Yes, and JEP 401 says so directly:**

> Reference flattening must maintain the integrity of data. A flattened reference must
> always be read and written atomically, or it could become corrupted. On common hardware
> architectures, this limits the size of mutable fields that store flattened references to
> no more than 64 bits.

So the 8 bytes are one machine word, and the constraint is hardware atomicity — not a
prototype limitation someone will patch.

### The exemption the JEP names, tested — and why it would not rescue SoA anyway

The JEP continues: *"The fields of a value class, by contrast, do not have this atomicity
limitation, since the fields of value objects can never be observed to be mutated."* That
is a distinction R4 never tested, so R6 tests it: a 12-byte `Reg12` as a field of a value
class, with field + non-atomic flattening enabled.

Measured: `HoldsReg12.r` is `REGULAR 4/4` — **a reference, not flattened.** The 4-byte
control `HoldsQuad.q` flattens as expected, and `Reg12`'s own three `Quad` fields are each
`FLAT 4/4` inline, so field flattening plainly works at 4 bytes. Whether 12 B failing here
is an EA implementation gap or a further constraint is **not determined by this
reproducer** and is recorded as an open item, not a conclusion.

But the durable point does not depend on resolving that: **the exemption is for FIELDS,
and SoA lanes are ARRAYS.** Array elements are mutable by definition, which puts them
inside the constraint rather than the exemption. And the JEP's own forward-looking note —
*"Future enhancements may enable more flattening... perhaps 128-bit atomic mutable fields
will become viable"* — would move the cliff from 8 to 16 bytes: the 12-byte register would
fit, and the 512-byte canonical row still would not.

**Consequence for the architecture.** "Project, don't hydrate" is not a workaround for an
early-access build that a later JDK repeals. It follows from a hardware atomicity
constraint that JEP 401 states as design, and the one relaxation on the roadmap does not
reach the row.

## R7 — one billion operations, zero materialization (`R7_BillionOpsZeroAlloc.java`)

The endgame claim, stated falsifiably: a billion Java operations over substrate bytes with
zero per-operation materialization. "Zero copy" here is a number that must not grow — if
anything per-op survives (an iterator, a boxed long, a hidden hydration), a billion ops
multiply it into gigabytes and the claim dies loudly.

The operation is the real one from R5: read classid → dispatch carving → project a group of
the 12-byte register straight out of the `MemorySegment`. No element type ever exists.

**Measured** (`R7-observed.txt`, three runs):

| quantity | value |
|---|---|
| operations | 1,000,000,000 group projections |
| allocated | **960 B total** — byte-identical across runs → 0.00000096 B/op |
| wall | NOT pinned — the three pinned runs span 1.76–3.48 s (287–567 M ops/s), a 2× spread |

The decisive comparison is against R5's own numbers: 65,536 ops allocated 800 B.
Operations grew **15,000×**; allocation grew 160 B. The bytes are fixed scaffolding
(measurement plumbing, enum values), not a per-op cost. The hydrating path at the same op
shape costs 32–104 B/row — at a billion rows, 32–104 **GB** of churn, decided per run by
escape analysis.

The per-op time is hot-cache load-and-shift scale (the working set is ~1 MiB,
cache-resident after warm-up — an earlier wording said "memory-latency scale", corrected on
operator review 2026-08-25, same pass that caught the observed-file's prose quoting a
different run set than its own pinned runs). What is load-bearing survives the correction:
there is no FFI in the loop and no object either — the segment is the substrate, and the
operation compiles to a bounds-checked load plus shifts. This is what "Java holds a zero-copy pointer" actually
looks like — the pointer is the `MemorySegment` + offset arithmetic, authority over the
bytes stays with the substrate, and Java's own layout machinery never engages because it
is never handed a type to lay out.

## R8 — the entropy boundary: five arms, one checksum (`R8_EntropyBoundary.java`)

R7 proved Java projection allocates nothing, and deliberately proved **nothing** about
Java-vs-Rust speed — its loop contains no FFI and no Rust. R8 measures that, symmetrically:
same 1 MiB of bytes (filled by the *same* native `r8_fill` for every arm), same classid
distribution, same op accounting, same checksum. **Checksum equality across arms is the
proof that every arm did the same work on the same bytes**, and it holds — including
against a standalone Rust process with no JVM at all. Arm E additionally asserts its mask
popcounts equal the Java partition scan's counts, so the two population representations are
the same *set*, not merely the same size.

| arm | what it is |
|---|---|
| A | Java `MemorySegment` + carving switch (R7's loop) |
| B | one bulk FFI call → generic Rust sweep (Rust re-derives the carving per row) |
| C | one FFI crossing **per projection** — the anti-JNI shape |
| D | Java resolves the preset → monomorphic Rust kernels per carving subpopulation |
| E | the **lawful** shape: per-carving bitmasks built by `ndarray::simd`, swept by mask bits |

> **Numbers live in `R8-observed.txt`, which is GENERATED by `r8_report.py` — not written by
> hand.** Every range in that file's prose is derived from the raw runs printed in the same
> file, in the same execution. This section therefore states *structural* results and
> ratios; it deliberately does not duplicate absolute figures. See "Why the report is
> generated" below — this is a repaired defect, not a stylistic preference.

All Rust artifacts — native kernels, the standalone baseline, and the ndarray crate — are
built with **one** compiler and one profile: `rustc 1.97.1`, `-O -Ctarget-cpu=x86-64-v4
-Cdebuginfo=0`. (The host has `avx512f/bw/dq/vl/vbmi/ifma`; ndarray's own
`.cargo/config.toml` pins v3 and this crate sits outside it, so v4 is explicit — that is
what makes `simd.rs` dispatch to the `simd_avx512` arm. Toolchain unified on operator
review so "bulk FFI costs nothing" has no compiler escape hatch.)

### Part 1 — period-4 classid: the control

- **One bulk Panama crossing shows no measurable penalty at this scale.** Stated that way
  deliberately, *not* as "bulk FFI equals standalone Rust" (calibration from operator
  review): one generated run had the in-JVM arm **faster** than the standalone process,
  which means process/JIT/turbo/cache context is larger than any crossing cost — so the two
  arms must not be called performance-identical even though no penalty is exposed. The
  generator now detects which of the three cases a run lands in and says so.
- **D > B is falsified.** A period-4 pattern is perfectly branch-predictable, so the generic
  sweep's per-row dispatch is already free — specialization cannot beat a predictor that has
  specialized.
- **C is ~30× slower than B**, ~12 ns/op. The anti-JNI rule with a number on it.

### Part 2 — random classid: where dispatch actually costs

B collapses (the dispatch cost was always there; the predictor was paying it). Both D′ and
E′ land ~4.8× above it. The split architecture wins because the selector layer
(classid → ClassView → mask) creates the information **once**, before the sweep, where the
monolithic generic loop re-derives it per row and eats the mispredict every time.

### The lawful shape is not the compromise — and sweep-only understates it

E′ exists because D′ cheats: an index list is a *materialized population*, which the
mask-native law forbids as internal currency. Comparing sweeps alone, D′ and E′ are
effectively tied, which reads as "E′ pays 2–3% for obeying the law". **That framing is
wrong** (operator correction): what differs between them is *building* the population, and
that must be counted.

```
D' = build (Java scalar scan -> index lists) + sweep
E' = build (ndarray::simd, ONE bulk call)   + sweep
```

Measured end-to-end, the lawful mask pipeline **wins on the first execution** — the mask
build is an order of magnitude cheaper than the Java scan, which moves break-even from
~120 passes to ~10. And it leaves behind a **mask**, reusable for and/or/andnot,
authorization, traversal, attention, where D′ leaves an index list the law forbids as
currency.

So `ndarray::simd` buys the lawful representation for free at execution time and then wins
on construction. **Obeying the law is the fast path, not a tax on it.**

### What this does and does not show

Not "Java is faster than Rust" — the winning kernels *are* Rust, and so is the mask builder.
A Rust-alone program that built masks first would match E′. The win is specialization
**placement**, not language. What is architectural: the knowledge lives in the
classid/ClassView layer, the population stays a mask, the sweep is one bulk call over it.
Part 1 is the control that keeps part 2 honest — when dispatch is predictable, partitioning
buys nothing.

### Why the report is generated

R7 shipped an artifact whose prose quoted one run set while its own pinned raw block held
another. That was caught, repaired — and then **R8 repeated it one commit later**, because
the prose was again hand-copied from a previous run while the raw block was regenerated.
Twice is a defect in the method, not in the care taken.

`r8_report.py` removes the possibility: it runs every arm, parses the output it just
captured, and derives every quoted range and ratio from it. Raw block and prose come from
the same subprocess output and cannot disagree. Regenerate with:

```sh
python3 r8_report.py > R8-observed.txt
```

Absolute figures move run to run (one regeneration saw B′ shift ~25% while every structural
conclusion — B ≈ standalone, D > B falsified, C ~30×, the B′ collapse, the ~4.8× D′/E′
recovery, the end-to-end E′ win — held identically). That stability of *conclusions* under
*unstable* absolutes is why the ratios are the result and the raw numbers are the evidence.

## R10 — the same schema in storage, Panama and Valhalla (`R10_SchemaAlignsWithStorage.java`)

The substrate carves its 12 content-blind bytes three ways and resolves which from the
classid. R10 asks whether Java can hold that schema *honestly* — three descriptions of
the same bytes that must not disagree.

**Measured** (`R10-observed.txt`):

| as a value class | flat? |
|---|---|
| the whole SCHEMA (`Rails6` / `Triplets4` / `Quads3`, 12 B) | `false` — and cannot be, per R4/R6 |
| one GROUP (`Rail` 2 B / `Triplet` 3 B / `Quad` 4 B) | **`true`, all three** |

And the alignment that makes the second row usable: for every schema, decoding a register
from **raw storage bytes**, through a **Panama `MemoryLayout`**, and via the **Valhalla
value class** yields identical values — with each layout describing exactly 12 bytes, and
the three schemas genuinely reading differently (so the agreement is not trivial).

**The consequence for "bolt the schema into Valhalla":** it bolts on at the GROUP, not at
the register. `12 = 6×2 = 4×3 = 3×4` means the largest group in any carving is 4 bytes —
half the flattening budget — while the register is 12 and the facet 16, neither of which
Java can flatten or needs to. So the schema is expressible on both sides; what crosses is
the group, and the register stays where it is.
## R11 — the physical layout is a schema, and applying it is a descriptor swap (`R11_LayoutIsASchema.java`)

The store today is AoS: 32 facets × 16 B interleaved in a 512-B row, lanes exposed as
*strided views*. R11 expresses the layout as **data** — a `LayoutSchema` record whose only
job is the address function — and runs ONE projector under both:

```
AoS : slot(r,f) = r*512 + f*16          (rows outer — today)
SoA : slot(r,f) = f*(N*16) + r*16       (facet lanes — 32 × 12-byte-register buckets)
```

Checksum parity across all 32 facets proves the two are readings of the same logical
content, so the timing difference is layout, not data.

**Measured** (`R11-observed.txt`): ~12.0–13.0 ns/row (AoS) vs ~1.30 ns/row (SoA) —
**~9.2×** on a one-facet sweep. Line arithmetic alone predicts 4× (16/64 B used per line vs
64/64); the rest is sequential prefetch plus 32× denser TLB coverage. An earlier claim in
this repo called the 4× "arithmetic, not a result" — it is now a result, and it was an
*under*-estimate.

**The structural finding outranks the ratio.** Because Java only ever projects (R5/R7), the
AoS→SoA flip touched no Java type and no sweep code — only the descriptor. Valhalla is
untouched by construction: what crosses is still a ≤4-B group; only offsets moved. And the
native kernels are already stride-parameterized, so the same holds below the membrane. **The
layout was already data at every boundary except the store's constructor.**

**Honest scope:** a whole-row consumer inverts the preference — AoS is contiguous for "all
32 facets of one row", SoA scattered. The schema is a per-workload choice, which is exactly
why it belongs in data rather than in code.

## R12 — Ghidra's P-code vocabulary against the cliff

**Question.** The `r2il-machine-semantic-contract-v1` plan's W5 puts a
lance-graph-java facade under Ghidra: `PcodeOp` / `Instruction` / `Varnode`
as lazy views over handles + masks. Before designing it, one measurement
decides its whole shape — are Ghidra's own P-code types flattenable?

**Method.** Field shapes TRANSCRIBED from Ghidra at cited paths, not
invented (`Varnode.java:51-54`, `PcodeOp.java:102-105`), then run through
R2/R4's harness on the JEP 401 EA build with `-XX:+PrintFlatArrayLayout`
so the VM reports its own element sizes rather than the program asserting
them.

**Measured.**

| shape | nonAtomic | atomic | VM element size |
|---|---|---|---|
| `VarnodePayload(int,int,long)` — 16 B | false | false | — |
| `PcodeOpPayload(int,long)` — 12 B | false | false | — |
| `VarnodeNarrow` — 8 B packed content | **true** | **true** | 8 (`NULL_FREE_ATOMIC_FLAT`) |
| `VarnodeRef(long)` | **true** | **true** | 8 (`NULL_FREE_NON_ATOMIC_FLAT`) |
| `PcodeOpRef(long)` | **true** | **true** | 8 |
| `InstructionRef(long)` | **true** | **true** | 8 |

The two `*Payload` rows are the **optimistic lower bound** for Ghidra's
real types — every reference deleted. The real `Varnode` additionally holds
an `Address`; the real `PcodeOp` holds a `SequenceNumber`, a `Varnode[]`
and a `Varnode`, so **a 2-input `PcodeOp` is five heap objects**.

**Verdict: the facade must ADDRESS the vocabulary, not CARRY it** — which
needs nothing new. It is the same result `LaneId` / `Ordinal` / `MaskId`
already rely on, and the same reason `RowRange` (16 B) does not flatten.

**The finding the plan did not anticipate.** `VarnodeNarrow` — `spaceId:u8`,
`size:u8`, 48-bit offset — **also flattens**. So 8 bytes is enough to carry
a varnode's real CONTENT, not merely a pointer to it. That is a live design
option for W5 (a content-bearing descriptor, no lane round-trip to read a
varnode's space or size) and it is bounded by exactly one condition: a
48-bit offset. Whether that suffices is a W0/W1 question about the address
space, not a Valhalla one — recorded here so the option is not lost, NOT
proposed as the design.

Note the layout kinds differ: the single-`long` refs are `NON_ATOMIC_FLAT`,
the multi-field `VarnodeNarrow` is `ATOMIC_FLAT`. Both flatten at element
size 8; only the tearing guarantee differs.


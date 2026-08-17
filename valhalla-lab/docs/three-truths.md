# The three truths of a semantic value

> Every number in this document was produced by `valhalla-lab/run.sh` on this box and is
> reproduced verbatim from `valhalla-lab/results/`. Nothing here is quoted from a specification or
> a blog post. Where something could not be measured, it says so.

The API defines a small vocabulary — `LaneId`, `RowRange`, `MaskId`, `Ordinal` — whose entire job
is to stop an `int` that means "which column" from being confused with an `int` that means "which
row". Four types, each wrapping one or two primitives.

Such a type has three truths, and they are not the same truth:

| | |
|---|---|
| **(a) semantic** | what the concept *means* — a `LaneId` is a value; its object identity is not part of what it is, and nothing in the API should be able to observe it |
| **(b) stable-Java** | what a `record` on a production JDK actually *is* — a heap object with a header, reached through a pointer |
| **(c) Valhalla** | what the *same source* becomes as a `value record` under JEP 401 |

The lab's design is one sentence: **compile one experiment source against two vocabularies that
differ by exactly one word, and diff the output.** `run.sh` step 0 enforces the "exactly one word"
part by diffing `src/valhalla/.../Vocab.java` against `src/stable/.../Vocab.java` modulo the
`value` modifier and refusing to run if anything else differs. Without that check the A/B could
silently become a comparison of two different programs.

---

## Environment

| | |
|---|---|
| stable JDK | `openjdk 26.0.2+10-55` — FFM final, **no** `--enable-preview` |
| Valhalla JDK | `openjdk 27-jep401ea3+1-1` — JEP 401 early access, `--enable-preview` |
| CPU | Intel Xeon @ 2.10 GHz, 4 vCPU, AVX-512 (`avx512f/dq/bw/vl/vbmi/ifma/cd`) |
| native library | `liblgj_abi.so`, `abi 0.1`, `simd ndarray::simd avx512`, `profile release` |

Reproduce everything: `valhalla-lab/run.sh`.

---

## (a) Semantic truth — and the finding is what *does not* change

| observation | stable | Valhalla |
|---|---|---|
| `equal state ⇒ equals()` | true | true |
| `different state ⇒ !equals()` | true | true |
| `equal state ⇒ equal hashCode()` | true | true |
| `equal state ⇒ equal toString()` | true | true |
| `Class::isValue()` | false | **true** |
| `a == b` for equal state | false | **true** |
| `identityHashCode(a) == identityHashCode(b)` | false | **true** |
| a local of this type accepts null | true | true |
| an array slot accepts null | true | **false** (null-restricted array) |
| `synchronized(x)` | legal | **compile error**: *required: a type with identity* |

The first four rows are the result. Every behaviour the production API actually *uses* is
identical across the two object models. The rows that differ are exactly the ones the API was
written never to depend on: reference equality, identity hash, and locking. That is why the
migration is a one-word source change and not a redesign — and it is checked here rather than
asserted in a comment.

Two rows deserve a note because they surprise people:

- **`a == b` becomes `true`.** Under Valhalla, `==` on a value class *is* state comparison. Code
  that used `==` as a cheap identity check silently changes meaning. This API never does.
- **A local variable of a value type still accepts `null`.** Null-restriction is a property of a
  *field* or an *array*, not of the class. `LaneId x = null;` compiles on both platforms.

`synchronized` is reported as a documented compile error rather than executed, because writing it
in the shared source would break the stable compile too. It was verified separately:

```
error: unexpected type
    try { synchronized (p) { ... } }
          ^
  required: a type with identity
  found:    LaneId
```

---

## (b) vs (c) Representation — measured in allocated bytes, not nanoseconds

The primary instrument is `ThreadMXBean.getThreadAllocatedBytes` (measured harness baseline: **0
bytes**). Bytes are the observation; time is the consequence. A timing alone cannot answer "did
this abstraction cost an object?", because escape analysis removes many allocations in a tight
loop and proves nothing about the general case.

All figures per operation, `N = 1,000,000`.

| measurement | stable | Valhalla | |
|---|---:|---:|---|
| construct a `LaneId`, store into an array | 16.00 B | **2.89 B** | 5.5× less |
| construct a `LaneId`, never escaping | 7.03 B | 8.00 B | ~equal — escape analysis already handles this |
| `LaneId[N]` array + elements, per element | 20.00 B | **6.89 B** | 2.9× less |
| bare `LaneId[N]`, per slot | 4.00 B | 4.00 B | equal (compressed oops vs flat int) |
| construct a `Descriptor` (two wrappers) | 56.00 B | **38.84 B** | 1.4× less |
| pass two wrappers through 3 call levels | 8.29 B | 10.45 B | ~equal |
| **read 65,536 `LaneId` from an array** | 44,182 ns | **5,349 ns** | **8.3× faster** |

`LaneId[1024]` reports `FLAT` on Valhalla and `UNKNOWN` on stable — deliberately not `false`. A
stable JDK has no `ValueClass.isFlatArray` to ask, so "the question does not exist here" is the
honest answer; printing `false` would claim a measurement that was never taken.

### The flattening knobs prove causation, not correlation

Re-running the Valhalla build with the VM's own flattening disabled:

| | default | `-XX:-UseArrayFlattening` | `-XX:-UseFieldFlattening` |
|---|---:|---:|---:|
| `LaneId` array, per element | 6.89 B | 28.00 B | 6.71 B |
| `LaneId[1024]` flat? | FLAT | NOT-FLAT | FLAT |
| read 65,536 from array | 5,349 ns | 47,469 ns | 5,303 ns |
| `Descriptor`, per instance | 38.84 B | 40.51 B | 80.00 B |

Turning array flattening off returns the array numbers to roughly the stable baseline and makes
the read **8.9× slower**; turning field flattening off doubles the `Descriptor` cost and leaves
arrays alone. The two knobs move exactly the two measurements they name. This is the difference
between "the Valhalla JDK was faster" and "*flattening* is what made it faster".

### Escape analysis is doing more of the stable JDK's work than it looks

With `-XX:-DoEscapeAnalysis`, i.e. what the object model costs when the JIT cannot rescue it:

| measurement | stable default | stable, no EA | Valhalla default | Valhalla, no EA |
|---|---:|---:|---:|---:|
| `LaneId` never escaping | 7.03 B | 16.00 B | 8.00 B | 25.60 B |
| pass 2 wrappers, 3 levels | 8.29 B | 32.00 B | 10.45 B | 31.00 B |
| **`Ordinal` built per element** (65,536 iterations) | 50,062 ns | **5,166,233 ns** | 50,021 ns | **94,346 ns** |

That last row is the clearest single number in the lab. Unaided by escape analysis, building a
one-`int` wrapper per element costs the stable JDK **103×**; it costs Valhalla **1.9×**. This is
the concrete meaning of "the abstraction stops being something you pay for" — not that it is
faster when the JIT can see through it, but that it stays cheap when the JIT cannot.

Honesty about what this row is *not*: with escape analysis on — the configuration anyone actually
ships — the two are indistinguishable at 50 µs. Valhalla's gain here is in **robustness**, not in
peak. That distinction is easy to lose and worth keeping.

### `-XX:±InlineTypePassFieldsAsArgs`

Not measured. The knob exists (`InlineTypePassFieldsAsArgs = true`, pd product) and the
method-passing row above is where it would show. On this hardware the measured argument-passing
difference between the platforms is inside the noise of the allocation instrument in the
default configuration, so a knob sweep would have produced numbers without meaning. Stated as
unmeasured rather than reported as "no effect".

---

## The flattening cliff — the finding that reframes the whole thesis

The first `ThesisExperiment` run produced something that looked like a bug: the Valhalla `Row`
array reported `NOT-FLAT` and cost **more** per row than the stable record (40.00 B vs 32.00 B).
Rather than explain it away, the question became a measurement. Sweeping payload shapes and asking
`ValueClass.isFlatArray` directly:

```
type   payload  NR-nonAtomic     NR-atomic        nullable-atomic
P4         4 B  true             true             true
P8i        8 B  true             true             false
P8l        8 B  true             true             false
P12       12 B  false            false            false
P16       16 B  false            false            false
P16l      16 B  false            false            false
```

**Flattening stops at an 8-byte payload, and past it no array flavour flattens at all.**
Independently confirmed by `-XX:+PrintFlatArrayLayout`, which logs a layout for the shapes above
the line and nothing below it. Full write-up: `reproducers/README.md` § R2.

Applied to this project's actual types:

| type | payload | flat? | side of the thesis |
|---|---:|---|---|
| `LaneId`, `Ordinal` | 4 B | **yes** | descriptor vocabulary — Valhalla helps |
| `MaskId` | 8 B | **yes** | descriptor vocabulary — Valhalla helps |
| `RowRange` | 16 B | no | descriptor, but already over budget |
| `Row` (id + class + value) | 16 B | no | per-entity materialisation — Valhalla does not help |

So "Valhalla helps the descriptors, not the entities" is not a hand-wave about object headers. On
this build it is a hard cutoff in the VM, and any realistic entity is on the wrong side of it by
construction — an id plus a single field already exceeds the budget.

`RowRange` falling on the wrong side is the one place the going-in expectation was too optimistic.
It is a descriptor, it was expected to flatten, and it does not.

---

## The thesis experiment

> 64,000 logical entities must NOT require 64,000 Java objects.

Same 65,536 rows, same question (`count(class == 7 AND value > 100)` and `sum(value)`), three
representations. Paths 2 and 3 are populated *from the very lanes path 1 scans*, and all three
answers are asserted equal — `2173 rows, sum 499246`, 3.32 % selectivity — before any number is
reported. A benchmark whose variants compute different things measures nothing.

### Heap cost

| | stable | Valhalla |
|---|---:|---:|
| **(1) native** — Java bytes allocated *per query*, warm | **816 B** | **816 B** |
| **(1) native** — Java objects per row | **0** | **0** |
| **(1) native** — native lane bytes | 1.00 MiB | 1.00 MiB |
| **(1) native** — mask bytes | 8.0 KiB | 8.0 KiB |
| (2)/(3) hydrate 65,536 `Row` — allocated | 2.00 MiB | **2.50 MiB** |
| … per row | 32.00 B | **40.00 B** |
| … array flat? | UNKNOWN | **NOT-FLAT** |
| … retained heap (approximate, GC delta) | 2.25 MiB | 2.75 MiB |

The 816 bytes is the number that matters, and what matters about it is that it does not depend on
the row count: it is the fluent chain's own bookkeeping (the predicate list and the marshalled op
descriptors), paid once per query whether the pattern holds 1,024 rows or 4,000,000.

**Valhalla is 25 % worse here, not better.** That is the R2 cliff: a 16-byte `Row` cannot flatten,
so it pays a value-object layout without getting a value-object layout's benefit. Reported as
observed; it contradicts the naive expectation that value classes make entities cheaper, and it
supports the thesis more strongly than the expected result would have.

### Time to answer

| | stable | Valhalla |
|---|---:|---:|
| **(1) native — one crossing, fused plan** | **16,378 ns** | **18,805 ns** |
| (2/3) hydrate 65,536 `Row` objects | 603,139 ns | 768,624 ns |
| (2/3) scan the materialised objects | 151,886 ns | 91,178 ns |
| (2/3) hydrate **then** scan (honest total) | **788,227 ns** | **898,955 ns** |

Medians of 51 iterations after 200–2,000 warm-up runs. Spreads are in `results/*.txt`; the
hydration rows have long tails (stable max 5.2 ms) because they allocate 2 MiB per iteration and
occasionally meet a GC, which is exactly why the median is reported.

**Native is 48× faster than the honest object total on stable, 48× on Valhalla.** Note where
Valhalla's one real win sits: *scanning* already-materialised objects is 1.7× faster (91 µs vs
152 µs), because the scan is a read-only walk that benefits from better locality. It does not
matter, because the hydration that had to happen first costs 8× what the scan saves.

That is the thesis, measured: the expensive thing is not *scanning* 65,536 objects, it is
*existing* as 65,536 objects. Valhalla makes the scan cheaper and does not make the existing
cheaper, so it does not move the answer.

### And the per-entity path never had to happen

The comparison above is generous to the object path — it assumes the developer needs the objects.
In the API they do not: `data.view().where(CLASS.eq(7)).where(VALUE.gt(100)).count()` is the
entire program. No arena, no segment, no mask, no lane, no opcode, no row loop, and no `Row`.

---

## Verdict

1. **The semantic contract is portable.** Every behaviour the API relies on is identical under
   both object models; only the behaviours it was written to avoid differ. The migration is a
   one-word source change, and it stays that way because the lab did **not** bend the API to fit a
   preview VM (see `reproducers/README.md`).
2. **Valhalla is a real win for the descriptor vocabulary.** 5.5× less allocation per `LaneId`,
   8.3× faster array reads, and — the durable part — 103× → 1.9× when escape analysis cannot help.
   A `LaneId` really does stop being something you pay for.
3. **Valhalla does not rescue per-entity materialisation, and on this build it makes it worse.**
   40 B/row against 32 B/row, `NOT-FLAT`, because a 16-byte payload is over the VM's flattening
   budget. The expected finding was "it does not help"; the observed finding is "it costs 25 %
   more".
4. **Therefore the architecture does not change.** Bulk data stays in native lanes with a packed
   mask; the tiny semantic vocabulary stays as records today and becomes value records the day
   JEP 401 ships. Those are two different decisions about two different kinds of object, and the
   measurements say to keep them different.

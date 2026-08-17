# The Valhalla lab — synthesis

> Companion to `valhalla-lab/README.md` and `valhalla-lab/docs/three-truths.md`
> (the raw findings and numbers) and `.claude/knowledge/valhalla-three-truths-method.md`
> (the method this lab is required to follow). This document is the "so
> what" — how the measurements bear on this project's actual API and
> migration story.

## The method, briefly

For each semantic value type in this project's small vocabulary (`LaneId`,
`Ordinal`, `MaskId`, `RowRange`, `Row`), three truths are distinguished and
never conflated:

1. **Semantic truth** — what the type *should* mean (identity-free, an
   opaque descriptor, safe to treat as a plain value).
2. **Stable-Java truth** — the `record` implementation on JDK 26 GA,
   measured, not assumed to already deliver (1).
3. **Valhalla truth** — the *same source shape*, compiled as `value
   record` against the JEP 401 early-access build, likewise measured.

The mechanism that keeps this honest: `valhalla-lab/run.sh` step 0
mechanically diffs `src/valhalla/Vocab.java` against `src/stable/Vocab.java`
*modulo the literal word `value`* and refuses to run if they differ by
more than that — so the A/B compares two runtimes on one program, never
two different programs.

## The headline result, and why it is more useful than a clean "yes"

The mission's mandatory experiment — 65,536 rows as (i) one native lane
plus one packed mask, (ii) hydrated Java objects, (iii) hydrated Valhalla
value objects — returned a nuanced answer, and the nuance is the finding:

| | native, one crossing | hydrate 65,536 `Row`, then scan |
|---|---:|---:|
| stable JDK 26 | 19.5 µs, 289 KiB | 746 µs, 2.00 MiB |
| Valhalla JDK 27 EA | 15.7 µs, 289.5 KiB | 900 µs, 2.50 MiB |

Native wins ~38–57× on time and ~7–9× on heap, **on both platforms** —
Valhalla does not close this gap. The reason is not "object headers are
still there" hand-waving; it's a hard, VM-confirmed cutoff:

## R2 — the flattening cliff explains the whole result

`ValueClass.isFlatArray` (the VM answering about its own array, not an
inference) shows array flattening stops dead at an **8-byte payload**:

| type | payload | flattens? |
|---|---:|---|
| `LaneId`, `Ordinal` | 4 B | **yes** |
| `MaskId` | 8 B | **yes** |
| `RowRange` | 16 B | no |
| `Row` (id + class + value) | 16 B | no |

`RowRange` landing on the wrong side is recorded in
`valhalla-lab/reproducers/README.md` as the one place the expectation was
too optimistic going in — a descriptor that was *expected* to flatten and
measurably does not. The line the VM draws is exactly the line separating
"tiny descriptor vocabulary" from "per-entity payload" in this project's
own design vocabulary: an id plus one more field already exceeds the
budget, so no realistic `Row`-shaped entity can benefit, by construction,
on this build.

**Causal isolation, not correlation:** `run.sh` runs the whole suite three
more times with `UseArrayFlattening`/`UseFieldFlattening` toggled off
independently. Turning `UseArrayFlattening` off alone drops `LaneId`'s
per-element cost from 6.89 B to 28.00 B (matches "not flat" exactly);
turning `UseFieldFlattening` off alone changes nothing for `LaneId` (an
`int`-payload type has no sub-fields to flatten) — confirming array
flattening, not field flattening, is the mechanism actually in play for
this vocabulary.

## What this means for the project's own API — and what it does NOT mean

**The production `java/` API adopts none of the three Valhalla-only
mechanisms found** (R1's `@NullRestricted` container trick, R2's flat-array
allocation, R3's `jdk.internal.value.ValueClass` factories). Per
`reproducers/README.md`: *"distorting a public API to fit a preview VM's
current budget would bake a temporary constraint into a permanent
surface."* Concretely:

- No `--add-exports` in the shipped build.
- No `jdk.internal.*` dependency anywhere in `java/`.
- The migration path from today's `record`-based vocabulary to Valhalla,
  the day JEP 401 ships as final, stays **exactly one word per type**
  (`record` → `value record`) — because nothing about today's API was
  bent around the preview build's current limits.

**What Valhalla DOES already buy, measured, not theoretical:** for the
single-field descriptor types this project actually uses as its public
vocabulary (`LaneId`, `Ordinal`), array storage is 5.5× smaller and array
reads are up to 8.3× faster once flattening applies — real numbers
Structure the semantic types were already shaped to receive, because they
were designed to be tiny and identity-free *before* this lab ever ran (per
`.claude/knowledge/john-doe-migration-thesis.md`'s litmus test: the small
vocabulary, never the bulk data, is where Valhalla was ever expected to
matter).

## The three reproducers, and which JDK component each belongs to

Filed as minimal, self-contained, independently-runnable files under
`valhalla-lab/reproducers/`, per the mission's explicit instruction that a
genuine Valhalla limitation gets a reproducer rather than an API
distortion:

| # | limitation | belongs to |
|---|---|---|
| R1 | `@NullRestricted` field on an ordinary (identity) class fails `VerifyError` at class load — javac emits field initializers after `super()`, the VM demands strict fields before it, no source form expresses the required order | **javac** |
| R2 | Array flattening has a hard 8-byte payload cliff | **HotSpot / Valhalla** |
| R3 | The densest null-restricted array form is `jdk.internal`-only; generics erase flattening entirely (`List<LaneId>` is `Object[]` underneath); `Foo!` null-restricted type syntax does not parse | **Valhalla (language + libraries)** |

R3's `Foo!` finding corroborates the earlier archaeology independently: a
direct compiler probe (`javac`, not documentation) confirms the syntax
genuinely does not exist in this build, matching what the three-JDK
source-checkout comparison found before any lab code was written (see
`.claude/board/EPIPHANIES.md` `E-LGJ-VALHALLA-ALREADY-MAINLINE-1`).

## The honest limit of this lab

Single-fork-equivalent caveats apply here too, stated in
`valhalla-lab/docs/three-truths.md`: this is a hand-rolled harness
(`Lab.time`), not JMH — labelled as such deliberately, with its timing
numbers treated as secondary evidence supporting the byte-count
measurements (`getThreadAllocatedBytes`, the primary instrument), not as
a benchmark-grade claim in their own right. JMH-grade timing for the
execution-boundary question lives in `bench/`, not here — see
`docs/execution-boundary.md`.

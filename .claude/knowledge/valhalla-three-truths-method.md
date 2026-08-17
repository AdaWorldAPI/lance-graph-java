# The Three Truths Method — How To Evaluate Any Semantic Value Concept

> READ BY: valhalla-lab-scientist, and any agent proposing a new semantic
> value type (NodeId, LaneId, RowRange, MaskId, Ordinal, ...)

## Status: METHOD (mandated by the mission brief §8, operationalized here)

## The Rule

For every important semantic abstraction, distinguish and record three
separate things — never conflate them:

1. **Semantic truth** — what SHOULD this concept mean? (e.g. "a `LaneId` is
   a value; its object identity should be irrelevant; two `LaneId`s with the
   same index are the same `LaneId`.")
2. **Stable-Java truth** — how is that contract expressed using the normal,
   fully-supported JDK today (a `final record` on JDK 26 GA)?
3. **Valhalla truth** — how does the SAME contract look on the current
   Valhalla preview (`value record` / `value class` on the JEP 401 EA build,
   `/opt/jdks/jdk-27`)?

Then **measure**, never assert. Record for (2) and (3): representation,
identity presence (`Class.isValue()`), allocation behaviour, array behaviour
(flattened or not — toggle `-XX:±UseArrayFlattening` and observe), field
flattening in a containing struct (`-XX:±UseFieldFlattening`), generic
behaviour, method-passing cost, nullability implications, and interaction
with FFM (can the value type address a `MemorySegment` as cheaply as a raw
`long`?).

## Why This Exists

The mission brief is explicit: *"Do not claim theoretical improvements
without measurement."* Valhalla is genuinely mid-flight (JEP 401 is preview,
not final — see `jdk-toolchain-facts.md`), and the temptation to assert "value
classes will make this free" without running the JDK 27 EA build is real and
must be refused every time.

## Where Findings Go

`valhalla-lab/` holds the runnable code and measured numbers for each of the
three truths, one experiment per semantic value type. If a genuine Valhalla
limitation is found while trying to express the ideal semantic-truth API,
it becomes a **minimal standalone reproducer** under `valhalla-lab/reproducers/`
recording: desired semantics / current ordinary-Java behaviour / current
Valhalla behaviour / observed layout+allocation / benchmark result / which
component the deficiency actually belongs to (javac, HotSpot, Valhalla,
Vector API, FFM, or this project's own design). **The API is never distorted
to accommodate a current Valhalla limitation** — the limitation is reported
upward instead.

## The One Experiment That Must Never Be Skipped

Per `john-doe-migration-thesis.md`'s litmus test: compare (i) 65,536 rows as
1 native lane + 1 packed mask + one bulk op, (ii) 65,536 plain Java objects,
(iii) 65,536 Valhalla value objects in an array. The thesis's prediction is
that Valhalla helps the *tiny descriptor vocabulary* and does **not** rescue
per-entity materialization at that scale — but this is a prediction to be
checked against real numbers in `valhalla-lab/`, not a conclusion to assume
into the docs.

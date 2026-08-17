# The John Doe Migration Thesis — What This Project Is Actually For

> READ BY: every agent, before writing any public Java API, README prose,
> or architecture doc in this repo. This is the thesis every other decision
> in this repo serves.

## Status: FINDING (operator-stated founding thesis, locked)

## The Center Is Not Panama, Valhalla, SIMD, or lance-graph Individually

The user's own reframing, which supersedes any earlier "FFI showcase" framing
of this project:

> Any ordinary Java developer can take yesterday's object-heavy Java, receive
> a generated/schema-fed API that still feels like Java, and suddenly execute
> against a zero-copy columnar graph substrate without learning Rust, SoA,
> SIMD, FFM, Lance, or graph-engine internals.

And the killer consequence:

> **64,000 logical "things being considered" no longer require 64,000 Java
> objects.**

## The Migration Story, Concretely

Old Java (the mental model the machine pays for):

```java
List<Person> berliners = new ArrayList<>();
for (Person person : people) {
    if (person.getAge() > 65 && person.getCity().equals("Berlin")) {
        berliners.add(person);
    }
}
```

The mental model is `Person, Person, Person, Person, ...` — 64,000 headers,
64,000 references, 64,000 allocations, 64,000 GC-visible identities.

New Java (embarrassingly familiar surface, per the thesis):

```java
var berliners = people.where(Person.AGE.gt(65))
                       .where(Person.CITY.eq("Berlin"));
```

Underneath: `Person.AGE.gt(65)` and `Person.CITY.eq(...)` are **tiny typed
predicates**, combined into a mask plan, evaluated in ONE bulk crossing over
1 native lane set + 1 packed mask. There never needed to be 64,000 `Person`
objects.

## Why the Schema Vocabulary Is the Accessibility Layer

The developer must never see `lane 17`, `predicate 0x37`, `mask word 491`.
They see `Person.AGE`, `Person.CITY` — generated (or, in this first slice,
hand-written *as if* generated — see `Pattern.java`) vocabulary with IDE
autocomplete and compile-time type safety. The schema spoon-feeds the safe
surface; the engine gets SoA; the trade is unusually good.

## The 64K-Thought Generalization

The same shape applies beyond "rows of a table": 65,536 hypotheses,
candidate diagnoses, graph relationships, reasoning states. Traditional OO
instinct: `Thought[] thoughts = new Thought[65536]` — each with identity,
allocation, GC visibility, pointer-chasing. This project's answer instead:

```
65,536 candidates
  state lane       i8[65536]
  confidence lane  i16[65536]
  class lane       u32[65536]
  active mask      8192 bytes
```

Composed via mask intersection (`A ∩ B ∩ C`) evaluated by SIMD/bitmap, never
by materializing 65,536 objects. **Java manipulates meaning. The substrate
manipulates population.**

## Why This Explains the Valhalla Split (see `valhalla-three-truths-method.md`)

Valhalla's sweet spot is the **tiny vocabulary controlling the population** —
`NodeId`, `LaneId`, `RowRange`, `Predicate`, `Lens`, `Range`, `Shape`,
`Capability` — not the population itself. Valhalla does not turn 64K
candidates back into 64K prettier objects; it makes the *instructions that
steer* those candidates identity-free and cheap. Panama makes the membrane
between that vocabulary and the population disappear. `ndarray::simd` +
the native SoA fixture make the population itself cheap. Three deliberately
different jobs, one triad.

## The Litmus Test for Any API Decision in This Repo

> Does this proposal turn N logical entities into N Java objects (of any
> kind — plain, record, or value)? If yes, reject it, regardless of how
> elegant the object looks. N entities become 1 lane set + 1 mask + a
> handful of typed descriptors, always.

This is why `docs/abi.md` §6 forbids per-element crossings, why the Java
facade's `View` is lazy (building a predicate chain must not materialize
anything), and why the Valhalla lab's headline experiment (see
`valhalla-three-truths-method.md`) directly measures "N Java objects" vs
"N Valhalla value objects in an array" vs "1 lane + 1 mask" — because the
thesis predicts the first two both lose to the third, and that prediction
must be checked, not assumed.

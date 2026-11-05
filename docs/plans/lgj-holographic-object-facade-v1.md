# LGJ-FOLDING-PRECEDENT-1: Folding without materialization

**Status:** PROPOSED / documentary reference only. **Date:** 2026-11-05. **Owner:** lance-graph-java for the precedent evidence only. **Dependency status:** NON-BLOCKING REFERENCE, not an upstream component of GridCraft, Bagop, ndarray or report-rs.

## Operator clarification: strictly a precedent

**lance-graph-java has exactly one role in this seven-PR program: demonstrate the pattern of folding over native columnar/mask representations without materializing one host-language object per logical row.**

Nothing else is imported from this repository into the planned XVERWEIS implementation. It is **not**:

- An execution or binary dependency of GridCraft, ndarray, Bagop or report-rs.
- The owner of XVERWEIS semantics, PSCustomObject semantics, order, multiplicity, canonical identity or tenant scope.
- A required Java/Valhalla/Panama FFM bridge, ABI or API.
- A prerequisite owner decision, implementation gate or cross-runtime oracle for P0–P3/P5–P6.
- A mandate to replicate a Java object façade, consumer DSL or host-language type hierarchy.

The actual implementation route remains **holographic DR64 Bag → lawfully admitted Bagop fold → ndarray positional/SIMD operations → GridCraft/PowerShell presentation**. The first three responsibilities belong to lance-graph and ndarray, not to Java.

## Source-backed fold-without-hydration evidence

- [lance-graph-java/README.md](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/README.md): 65,536 logical entities are represented as a native lane set, packed selection mask, small descriptors and bulk terminal operation, rather than 65,536 Java row objects.
- [java/README.md](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/java/README.md): a fluent where/where/count chain composes lazy predicates and crosses the membrane at terminal evaluation. Its LazinessTest counts crossings, rather than assuming laziness.
- [valhalla-lab/docs/three-truths.md](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/valhalla-lab/docs/three-truths.md): measurements distinguish semantic equivalence, allocation and runtime cost. Do not infer GridCraft performance from Java/Valhalla observations.
- [mask-membrane plan](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/.claude/plans/mask-membrane-valhalla-integration-v1.md): host façade names a native operation, does not recompute native mask algebra; execution ownership stays below the membrane.

The transferable invariant is **construct a tiny descriptor of a fold over a borrowed population; execute the fold in the owning native substrate; materialize only the demanded terminal scalar, viewport or external serialization**.

The Java-specific mechanisms that realize this invariant do **not** transfer as requirements.

## Narrow precedent checklist

| Evidence question | Method | What it does NOT prove |
|---|---|---|
| Are logical rows represented without one Java object each? | Review native lane+mask design and allocated bytes | That GridCraft has already achieved zero row hydration |
| Is folding lazy until terminal evaluation? | Read the Java LazinessTest and native crossing counter | That an XVERWEIS ordered fold is admitted |
| Are rows folded in native SIMD? | Trace existing native mask/count call and backend evidence | That lookup/multiplicity/first-last semantics are automatically correct |
| Is cost attributed correctly? | Separate bind, descriptor construction, terminal fold and egress | That every runtime, architecture or dataset has the same performance |

**The end-to-end implementation oracle belongs to P6 GridCraft; semantic law belongs to P2 lance-graph; SIMD mechanics belong to P1 ndarray.** P4 does not supply a parallel Java oracle or require a Java runtime during the probe.

## Scope of this PR

One documentation-only reference, no Java source changes, no new tests, no FFM changes, no implementer tasks for Java. Downstream readers should cite the precedent only for the no-materialization folding design and its measurement discipline.

### Decision log

- **2026-11-05:** Initial plan was too broad: it described PSCustomObject semantic façade design and implied P4 was a predecessor/gate.
- **2026-11-05 (correction):** Operator clarified lance-graph-java is **only a precedent for folding without materialization**. All Java-specific façade, API and runtime obligations are out of scope. P4 is non-blocking historical/empirical evidence. Implementations do not wait for P4 approval.

**No implementation authorization. No extra dependency on the Java repository.**

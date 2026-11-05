# LGJ-HOLOGRAPHIC-OBJECT-FACADE-1: Typed façade, no row hydration

**Status:** PROPOSED / plans only. **Date:** 2026-11-05. **Owner:** lance-graph-java. **Dependencies:** lance-graph P0 and P2 (semantic decisions, not runtime dependencies).

**Mission:** Capture the existing Valhalla/Panama membrane precedent for GridCraft and a PowerShell PSCustomObject-shaped holographic Bag projection. **Do not** introduce Java or PowerShell as an execution dependency of GridCraft. DR64 remains exclusively the Bag representation; the façade has no alternate semantic authority.

## Existing source-backed precedent

- [Top-level architecture](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/README.md) describes one native lane set + packed mask + semantic descriptors in place of 64,000 Java objects.
- [Java semantic API](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/java/README.md): fluent View/where/count; one membrane crossing for a terminal operation; API surface checks disallow native internals in the consumer API.
- [Valhalla three truths](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/valhalla-lab/docs/three-truths.md): separate semantic equivalence, physical representation, and measured allocation/throughput. Some benchmarks were run under older JDKs and must not be silently generalized to all JDK 28 builds.
- [Membrane integration plan](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/.claude/plans/mask-membrane-valhalla-integration-v1.md): consumer façade only names native operations; no Java-side mask ALU or consumer-visible worker topology; stale generation checks and exception list matter.
- [ABI contract](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/docs/abi.md) and [Panama design](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/docs/panama.md) separate descriptors, handles, memory lifetime and execution.

## Proposed semantic mapping

A PowerShell expression can *look* like:

    $customer.Services.ExchangeOnline

while its canonical interpretation is:

    TypedPropertyPath(Customer, Services, ExchangeOnline)
        + authenticated Bag scope
        + DT64 method/field binding
        + witnessed DR frame / layout / value revision
        → Bagop projected value, not allocated nested objects

Java / PowerShell / GridCraft owns human-readable property syntax, projection descriptors and presentation. Bagop owns Bag semantics. ndarray/mask-risc owns authorized computation; DR64 represents the Bag. **No PSCustomObject interpreter, Java heap object tree, HashMap of customer attributes, or second SIMD engine is permitted in the hot path.** Actual PowerShell object construction is allowed at a terminal serialization boundary when the external host requires it.

## Semantics and refusals

| Obligation | Plan | Gate |
|---|---|---|
| Property path resolves correctly | Typed symbols bind via owner DT64 basis | Unknown/unbound path refuses, not dynamic HashMap fallback |
| Lifetime | Descriptor stamped with source/frame/layout/scope/value witness | Stale, closed, foreign-scope handles refuse |
| No per-row hydration | One native population, masks, tiny value descriptors | Allocated bytes proportional to descriptors/terminal demand |
| Multiplicity | Named object view is a lens over Bag occurrences | No silent DISTINCT or first-row overwrite |
| Missing/unknown | Preserve source cell states and errors | No invented null defaults |
| Fusion/laziness | Descriptor chain has no native crossing until terminal | Count crossings and allocations, not prose assertions |
| Native ownership | No facade code runs its own SIMD/Bag folds | ApiSurfaceTest-like static audit |

Do not describe PowerShell as already running through this Java ABI. This is a **cross-language design analogy** and an evidence plan. Valhalla value classes are neither required for Rust nor a proof that PowerShell avoids per-output object allocation.

## Independent research falsifier

Use one synthetic 65,536-identity Bag fixture with duplicate occurrences, two tenants with same external label and one value revision. Compare:

1. Direct Bagop typed property projection.
2. Java façade descriptor terminal operation (only for existing admitted verbs).
3. Proposed GridCraft virtual cell descriptor.
4. Independent scalar oracle over canonical identities/values.

Track object allocations, native crossing count, frame and scope refusals. Disable semantic handle validation and require wrong/unauthorized results to be caught. For a property verb not admitted in Java, specify the missing native primitive and stop; do not implement Java computation. Separate overhead of typed descriptor construction from object hydration and from cold/terminal serialization.

## Traceability and decisions

| Requirement | Existing contract | Owner | Falsifier |
|---|---|---|---|
| Zero row-object façade | java/README.md + valhalla three truths | LGJ | Heap allocated bytes vs 65,536 entities |
| One native Bag | P0 | lance-graph | Drop derived indexes, compare oracle |
| XVERWEIS/PSCustomObject meaning | P2 | Bagop/DT64 | Ordered occurrence and named field parity |
| Spreadsheet projection | P3 | GridCraft | Viewport not whole worksheet |
| Integrated proof | P6 | GridCraft orchestrates | Independent cross-runtime semantics |

**Decision log 2026-11-05:** derive façade rules from existing LGJ precedent. **Next implementation PR:** none in this repo unless the owner finds a specifically missing semantic descriptor after P2. No Java build/code changes in this plan PR.

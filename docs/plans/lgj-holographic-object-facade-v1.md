# LGJ-FOLDING-PRECEDENT-1: Folding without materializing host-language objects

**Status:** DEFERRED / non-blocking documentary precedent. **Date:** 2026-11-05. **Owner:** lance-graph-java for this evidence only. No native code change or Java integration is proposed.

## Exactly one transferable observation

A native fold can operate on borrowed columnar/mask representations **without instantiating one Java/PowerShell/Excel object per logical row**. That is a relevant measurement and API-design precedent for a *SAP / PSCustomObject-shaped logical tile* implemented on the existing lance-graph/ndarray substrate.

- [lance-graph-java README](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/README.md): native lane and packed mask representation versus host objects.
- [java/README](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/java/README.md): fluent `where`/`count` descriptor, native crossing and laziness checks.
- [three truths](https://github.com/AdaWorldAPI/lance-graph-java/blob/main/valhalla-lab/docs/three-truths.md): independently distinguish semantic equivalence, allocations and runtime costs.

These examples do **not** establish a fast or admitted GUID join, DR ISA layout parity, lawful cross-table Gather, Excel formula compatibility, or DT64 behavior invariance.

## Explicitly not owned here

- **DR:** routes identity × property × predicate coordinates, the "Google Maps teleportation" mechanism.
- **DT64:** enforces structural and behavioral parity via `ClassView`, `WideFieldMask`, `MethodRef`, immutable class bindings and route versions.
- **OGAR:** normalizes `User_{GUID}` and resolves AD/Entra/EXO source-qualified identity correspondences.
- **Quack / mask-RISC / ndarray:** lower and execute admitted computation over SoA lanes.
- **GridCraft:** optional spreadsheet presentation of an already-bound tile.

Java is **not** a runtime, ABI, semantic, codebook, object-model, method-identity or test-oracle dependency for any of these owners. P0/P1/P2 and the core SAP/Guid128 lab do not wait on this PR.

## Review-only questions

| Question | Acceptable evidence | Not implied |
|---|---|---|
| One logical object without one host allocation? | Host allocation count and resident lane/mask census | No object ever allocated at egress |
| Lazy native fold? | Number of crossings, terminal scalar and unchanged borrowed columns | Any Excel/XVERWEIS mode is admitted |
| Fast warmed case? | Split bind, native kernel, output and memory cost | End-to-end speedup across runtimes |
| Independent semantics? | Separate scalar oracle for the function actually tested | Source/tenant/layout witness correctness without tests |

**Decision:** retain as a **non-blocking reference only**. The core independent oracle must be in the owning lance-graph/OGAR POC. A separate GridCraft Excel oracle, if later written, belongs to the optional consumer test plan P6. No implementation, new tests or merge obligation here.

# lance-graph-java

**Not Java bindings for a Rust graph library.** A migration path: an ordinary
Java developer writes familiar, boring, fluent Java and gets zero-copy
columnar/SIMD execution against `AdaWorldAPI/lance-graph`, without learning
Rust, SoA layout, SIMD, FFM, or graph-engine internals.

```
    64,000 logical "things being considered"
              do NOT require
    64,000 Java objects.

    They become:
      1 native lane set
    + 1 packed mask
    + a handful of tiny typed semantic descriptors
    + one bulk operation
```

## The architecture

```
                    JAVA SEMANTIC PLANE

               lance-graph-java API          <- what a Java developer writes
                        |
              typed semantic values          <- generated-schema vocabulary
                        |
             View / Mask / Lens DSL          <- lazy, monotonic-narrowing
                        |
             stable-JDK implementation
                        |
               Valhalla laboratory            <- the tiny vocabulary only

====================== PANAMA FFM ======================

                 stable ABI membrane          <- docs/abi.md, 14 symbols

====================== RUST DATA PLANE ======================

                   lance-graph
                        |
          ClassView / WideFieldMask
                        |
              Lens / Mask algebra
                        |
                     ndarray
                        |
               ndarray::simd::*               <- the ONLY SIMD surface
                        |
 AVX-512 / AVX2 / NEON / WASM SIMD / scalar
```

There is **no C anywhere** in this stack — `extern "C"` names the platform
calling convention (SysV AMD64 psABI here), not the C language. No headers,
no `cbindgen`, no `jextract`, no JNI. See `docs/abi.md` §1 and
`.claude/knowledge/no-c-ever.md`.

## Layout

| Path | What |
|---|---|
| `docs/abi.md` | The normative Rust↔Java ABI contract. Read this first. |
| `native/lgj-abi/` | Rust: the ABI crate. Depends on `ndarray` (path sibling) for all SIMD, via `ndarray::simd::*` only — never `ndarray::hpc` directly. |
| `java/` | Java: the FFM membrane (`internal/ffm`, Panama machinery) + the public semantic facade (`View`/`Mask`/`Predicate`/`Pattern`). |
| `valhalla-lab/` | The three-truths method applied to the small semantic value vocabulary (`LaneId`/`RowRange`/...), on a real JEP 401 early-access JDK. |
| `bench/` | Cost-component-separated benchmarks: Panama downcall overhead, `MemorySegment` throughput, the Rust kernel, Java Vector API over the same native memory, fused vs unfused plan cost. |
| `.claude/` | Agent ensemble + knowledge docs + project board, scoped to this repo's actual seams. Start at `.claude/agents/BOOT.md`. |

## Toolchains

- Rust: **1.97.1** stable, matching `ndarray`/`lance-graph`'s pin.
- Production JDK: a GA build with `java.lang.foreign` final (no preview
  flag needed) — verified against JDK 26 GA.
- Valhalla lab JDK: the official JEP 401 early-access binary from
  `jdk.java.net/valhalla/` — a value-classes preview build, kept physically
  separate from the production path.

See `.claude/knowledge/jdk-toolchain-facts.md` for exact verified paths and
flags in the reference development environment.

## Status

Early research vertical slice. See `.claude/board/STATUS_BOARD.md` for
current per-deliverable status and `.claude/board/LATEST_STATE.md` for the
current contract inventory.

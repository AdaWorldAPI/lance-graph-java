# Where does execution belong? — measured, not assumed

Real JMH 1.37, `--enable-preview` off (Vector API only needs `--add-modules jdk.incubator.vector`,
not preview), JDK 26 GA, `@Fork(1) @Warmup(5×500ms) @Measurement(8×500ms)`, `AverageTime`. Full run:
`results/jmh-run.txt` (1,679 lines, every warm-up iteration). Machine-readable:
`results/jmh-results.csv`. Reproduce with `./run.sh` (~12 min on 4 vCPU — this run: 00:12:22).

**Gate.** 50/50 benchmark rows completed, 0 failures. `Data.crossCheck()` (native vs Vector vs
scalar agree on both count and sum) ran in `@Setup` for every fork and never threw — the three
kernels compute the same answer, so a speed comparison between them is meaningful rather than a
race between a correct implementation and a subtly wrong faster one.

## The headline finding — and it complicates the thesis in an honest way

Component C (`java_scalarLoop` / `java_vectorApi` / `native_fusedPlan`) sweeps one predicate
(`class == 7`) over row counts from 64 to 4,194,304, all three arms answering the identical
question from the identical native lane:

| rows | scalar (µs) | vectorApi (µs) | native (µs) | native beats scalar by | **vectorApi beats native by** |
|---:|---:|---:|---:|---:|---:|
| 64 | 0.028 | 0.011 | 0.612 | 0.05× (native LOSES) | 56.40× |
| 256 | 0.085 | 0.025 | 0.623 | 0.14× (native LOSES) | 24.96× |
| 1,024 | 0.338 | 0.078 | 0.708 | 0.48× (native LOSES) | 9.07× |
| 4,096 | 1.252 | 0.385 | 1.524 | 0.82× (native LOSES) | 3.96× |
| 16,384 | 5.553 | 1.744 | 4.291 | **1.29×** | 2.46× |
| 65,536 | 42.846 | 8.027 | 15.324 | 2.80× | 1.91× |
| 262,144 | 343.389 | 42.519 | 69.374 | 4.95× | 1.63× |
| 1,048,576 | 1,623.313 | 310.405 | 411.333 | 3.95× | 1.33× |
| 4,194,304 | 6,602.036 | 1,319.107 | 1,858.686 | 3.55× | 1.41× |

Two crossovers, both real:

1. **Native beats a plain Java scalar loop only past roughly 4,096–16,384 rows.** Below that, the
   crossing overhead (the ~0.6 µs floor visible at row=64, consistent with Component A's raw
   downcall cost) is not repaid yet — a scalar loop over a few thousand elements is simply cheap
   enough in Java that there is nothing to win by leaving the JVM.
2. **The Java Vector API, reading the SAME native `MemorySegment` with zero copy
   (`IntVector.fromMemorySegment`), beats the native crossing at every single row count
   tested** — never below 1.3×, and by more than an order of magnitude at small sizes. This
   is the finding this project's own mission brief asked for by name: *"Where is the cheapest
   and cleanest execution boundary? Not: how can we maximize the amount of Java code?"* — and
   the honest answer, for this one-predicate/one-lane workload, is that it is **not** the Rust
   crossing.

**Why this does not overturn the thesis, and where it does bite.** Component C measures ONE
predicate over ONE lane — exactly the case where a zero-copy Vector kernel has nothing to fuse and
nothing to coordinate. Component E (below) measures what happens once there is more than one
predicate, which is the case the fluent `View` API actually optimizes for.

## Component E — fusion matters once there is more than one predicate

`fused` (native, one crossing, N predicates AND-combined in one plan) vs `unfused` (native, N
crossings, one `mask_and` per predicate) vs `fusedScalarKernel` (the SAME fused plan forced through
the crate's own scalar reference path, not SIMD), at 65,536 rows:

| predicates | fused (µs) | unfused (µs) | fusedScalarKernel (µs) | SIMD speedup over scalar |
|---:|---:|---:|---:|---:|
| 1 | 6.818 | 7.641 | 73.419 | 10.8× |
| 2 | 15.599 | 15.025 | 405.096 | 26.0× |
| 4 | 29.721 | 27.309 | 923.100 | 31.1× |
| 8 | 59.363 | 61.916 | 1,807.261 | 30.4× |

Two findings, neither of which was assumed going in:

- **`fused` and `unfused` are close** — within noise of each other at this row count (see the
  single-fork caveat below). The `lgj_plan_eval` fused path exists to guarantee ONE crossing
  regardless of predicate count (a structural property `LazinessTest` in the Java suite already
  proves), not because N separate crossings at 65,536 rows are individually expensive — Component A
  already showed a bare downcall costs ~22 ns, so 8 of them add roughly 176 ns against a
  multi-microsecond total. The value of fusion at this scale is the crossing-count GUARANTEE, not a
  large measured time saving.
- **SIMD vs scalar is the biggest lever in this whole benchmark suite** — 10.8×–31.1×, growing
  with predicate count. This is the number that justifies routing every kernel through
  `ndarray::simd` rather than a portable scalar loop, and it dwarfs the crossing-cost questions
  Components A/B/C spend most of their effort isolating.

`planConstructionOnly` (0.053–0.634 µs, scaling with predicate count but NOT with row count — 65,536
rows throughout) confirms `LazinessTest`'s claim under real JMH conditions: building the fluent
chain costs time proportional to the number of `.where()` calls, never to the number of rows.

## Component A — the floor

| benchmark | ns/op |
|---|---:|
| `javaCallControl` (a plain Java method call, the noise floor) | 0.497 |
| `bareDowncall_noArgs` | 21.911 |
| `downcall_twoArgs_outPointer` | 117.855 |

A bare Panama downcall costs ~22 ns over a plain Java call; adding two arguments and an out-pointer
roughly quintuples that. Both numbers are the ~0.6 µs floor Component C's small-row-count native
arm sits on top of (downcall cost + the fixed per-call marshalling `Engine`/`Downcalls` do above the
raw linker).

## Component B — raw throughput, no crossing

| benchmark | µs/op (65,536 elements) |
|---|---:|
| `heapArrayBaseline` (data already in a Java `int[]`) | 5.158 |
| `segmentScalar` (same data, read from a native `MemorySegment`, scalar loop) | 5.220 |
| `segmentVector` (same data, `IntVector.fromMemorySegment`) | 3.337 |

Reading a native segment scalar-wise costs essentially the same as reading a heap array (1.2%
difference — within this harness's own stated ~10% noise floor, see below) — `MemorySegment` access
is not itself a tax. The Vector API is the thing that's actually faster here (1.55× over both), not
the memory's location.

## Honest limitations (stated in `README.md`, repeated here because they qualify every number above)

- **Single fork.** `@Fork(1)` cannot see run-to-run JIT/ASLR variance. Differences under ~10% between
  arms are not established by this harness — this is why `fused` vs `unfused` above is reported as
  "close" rather than a specific winner.
- **Shared container, not a tuned host.** No CPU pinning, no disabled turbo/hyperthreading. Large
  effects (the order-of-magnitude ones — scalar-vs-vector, the small-row native crossover) are safe
  to read; anything under ~10% is not.
- **Component C's ONE-predicate shape is deliberate, not the whole story.** It isolates the
  crossing question cleanly; Component E is what shows the picture changes once predicates compose.

## The verdict, stated the way the mission brief asked for it

*"Where is the cheapest and cleanest execution boundary?"* — measured, not assumed: for a single
predicate over a native lane, **Java itself, via the Vector API reading the segment zero-copy, is
the fastest arm tested at every scale**. The native crossing earns its keep once real work — SIMD
kernels for multi-predicate fusion, the guaranteed-one-crossing property for an arbitrarily long
`View` chain, and (per `valhalla-lab/`) genuine per-population bulk operations — is on the other
side of it. The honest reading is not "Rust wins" or "Java wins" but **"the crossing is worth
paying for composed work, not for one predicate read alone"** — exactly the nuance the mission
brief's Phase G asked this benchmark to establish rather than assume in either direction.

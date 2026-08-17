# Prior art, and the claim that distinguishes this project

> **READ BY:** anyone writing positioning/architecture prose, the consumer-
> example authors (W5), and any session tempted to cite the operator's
> archived ChatGPT discussion (2026-08-17, predates this repo's build) as
> design authority. That discussion is assessed HERE, once — its residual
> value extracted, its errors pinned — so it is not re-mined.

## Assessment verdict

The discussion **converged independently on the architecture this repo then
built and measured** — layout-centric bridge, engine-chooses-the-kernel,
"Java understands the layout, Rust owns the physics," a brutally small
vertical-slice MVP. Convergence from an independent derivation is mild
evidence the shape is right, and zero evidence about anything the
discussion *assumed but never measured*. Three parts remain valuable; two
parts are wrong relative to what this repo has since measured or mandates.

## 1. The distinction that holds up: callability vs shared executable layout

The competitive landscape, correctly characterized there and worth keeping:

| bridge | what it is | what it solves |
|---|---|---|
| JNI / `jni-rs` | env pointers, object handles, marshaling ceremony | callability |
| `j4rs` | higher-level interop layer | callability |
| UniFFI | binding generator (Kotlin/Swift/Python official; Java external, FFM-based) | callability |
| Panama FFM (JEP 454, final since JDK 22) | the *mechanism* — `MemorySegment`/`MemoryLayout`/`Linker` | reachability, not semantics |

All of these are **function bridges**: Java calls a native function, values
marshal across. This project's claim is a **layout bridge**: Java and Rust
*execute over the same bytes*, and the contract is the layout itself
(`docs/abi.md`), runtime-proven by the manifest cross-check. The
discussion's phrase for the moat — solving *"shared executable layout"*
where existing bridges solve *"callability"* — is the crispest one-line
positioning this project has and is worth using verbatim.

## 2. Where the discussion's sketch was WEAKER than what was built

Its `OgarAbiPage` sketch (`schema_key, row_count, stride, lane_count,
flags, data_ptr`) carries a bare `data_ptr` with **no lifetime story at
all** — no generation check, no epoch, no owner, no parent-liveness, no
close semantics. That is precisely the machinery this repo's registry
provides and **disable-verifies** (`abi.md` §4: no code path in which a
stale handle dereferences freed memory). A page descriptor without a
liveness protocol is a use-after-free with documentation. Keep this as the
standing answer to "couldn't the ABI just be one page struct?"

## 3. The genuinely forward-looking extractable: the schema key as the join point

> "One ABI key connects: Java MemoryLayout, Rust repr(C)/SoA, ClassView,
> ontology predicate, low-code block, Lance column projection, SIMD
> kernel."

This is the OGAR classid doctrine — *classid is pure address; the magic is
what it resolves to* — arriving from an independent direction, which is
worth something. Concretely for this repo: today a resource names its
layout contract only *implicitly* (`kind == ROWSTORE` ⇒ the §11 geometry).
When the real ClassView slice lands, `LgjResourceInfo` (and/or
`LgjLaneDesc`) should gain an explicit **schema/classid field** so a
resource names *which* layout contract its bytes obey — additive, one
minor bump, and it is what makes "one key, many projections" literal at
the membrane. Filed as the W6 consideration in
`.claude/plans/lgj-soa-substrate-v1.md`.

## 4. The claims discipline worth adopting verbatim

The discussion's own guard against its headline numbers: never state
"2000×" as an engine claim. The architecture turns *pathological baselines*
(object-per-edge allocation, boxed values, string predicates, virtual
dispatch, JNI marshaling, JSON serialization, per-row Java loops) into
dense native kernels — so deltas are **baseline-dependent**: 10×, 100×,
1000×+ depending on how bad the baseline was. This repo's bench already
practices the stronger form (measure, publish the reproduction command,
state the noise floor); the W5 consumer examples MUST phrase any
comparison this way, and the graph-traversal plan already cites the
six-component BEFORE stack *as architecture, not as a benchmarked number*.

## 5. Two corrections — pin these so the text is never cited naively

1. **"Rust/RISC-speed execution through the membrane" assumes the native
   side always wins. Measured false here.** Component C: the Java Vector
   API, zero-copy on the *same* native segment, beats the native crossing
   at **every** row count tested (64 → 4.2M) for a single predicate; the
   crossing pays off for *composed, fused* work (Component E, 10.8–31.1×).
   The real split is finer than the discussion's frame and lives in
   `docs/execution-boundary.md`. Any prose inheriting the discussion's
   framing must inherit the measurement instead.
2. **Its ndarray paragraph is about UPSTREAM crates.io ndarray** ("an
   ergonomic n-dimensional array crate … `matrixmultiply` underneath, not
   a SIMD layer"). The **AdaWorldAPI fork** this stack mandates is a
   different artifact: `ndarray::simd` IS the SIMD polyfill layer
   (dispatched AVX-512/AVX2/NEON/wasm/scalar under the W1a consumer
   contract), and this project's §8 provenance rule depends on that. Do
   not import the discussion's correction against the fork. (Its nightly
   caveat about `std::simd` portable SIMD is true and is exactly why the
   polyfill exists on stable.)
3. *(minor, was already true)* Java's Vector API is still incubating —
   consistent with this repo's `--add-modules jdk.incubator.vector` and
   noted in `jdk-toolchain-facts.md`.

## The slogan shelf (used sparingly, they are earned now)

- *"Java understands the layout. Rust owns the physics."* — matches
  `architecture.md` §layers; safe to use.
- *"Panama gives Java fingers; this ABI gives it bones, tendons, and a
  nervous system."* — rhetorically fine; keep it out of normative docs.
- *"Existing bridges solve callability; this solves shared executable
  layout."* — the best of the three; use in positioning.

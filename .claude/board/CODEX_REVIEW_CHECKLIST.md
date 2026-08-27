# Codex Review Checklist

> A pre-emptive checklist for lance-graph-java worker prompts and
> main-thread commits — the medcare-rs pattern (auth/RBAC/PHI/crypto),
> adapted to this repo's actual risk surface: an FFI/ABI membrane
> between Rust and the JVM, where the failure modes are memory safety,
> undefined behavior across the boundary, and silent physics leaking
> into a "familiar Java" API that promises none of it is visible.
>
> Session 1 (2026-08-17) — no codex findings yet to cite as provenance
> (see §8). This checklist is pre-registered from the failure modes
> `docs/abi.md` and the agent cards already name, on the same logic
> medcare-rs used after its sprint-1 findings: bake the checklist into
> worker prompts BEFORE the first review round, not after.
>
> Use this checklist before pushing any PR that touches:
> - `native/lgj-abi/` (any Rust ABI/FFI code)
> - `java/src/main/java/.../internal/ffm/` (any Panama code)
> - `java/src/main/java/.../lancegraph/` (the public API surface)
> - any new `ndarray::simd` primitive consumed by this repo
> - any Valhalla or benchmark claim in `valhalla-lab/` or `bench/`
>
> The checklist is intentionally exhaustive — most items will be
> trivially satisfied. The point is the cognitive prompt: "did I
> consider this failure mode?"

## 1. FFI / ABI memory safety (`native/lgj-abi/src/registry.rs`, `exports.rs`)

- [ ] Every `extern "C"` function checks every `*mut`/`*const` out-parameter for null before writing through it (`NULL_ARGUMENT`, not a segfault)
- [ ] Every handle lookup validates BOTH slot-occupied AND generation-match before touching the payload — a stale handle returns `INVALID_HANDLE`, never a dereference of freed memory (see `handle-lifecycle-auditor`'s card for the exact attack list)
- [ ] Double-close returns `INVALID_HANDLE` on the second call, not UB — traced through the actual lock-acquire order, not assumed from the design doc
- [ ] A mask whose parent resource closed returns `PARENT_CLOSED` on EVERY subsequent operation, checked per-call (not cached at mask-creation time)
- [ ] No lane is ever resized, reallocated, or moved while its owning resource is alive (`docs/abi.md` §4's "no relocation" guarantee) — any growable-lane proposal needs a `major` version bump, not a quiet exception
- [ ] Every `extern "C"` function body is wrapped in `catch_unwind`; no panic can unwind into JVM frames (test this by deliberately triggering an internal panic and asserting it surfaces as a negative status, not a crash)
- [ ] Registry lock discipline: the registry-level lock is held only long enough to resolve `index → Arc<ResourceEntry>` and is dropped BEFORE the entry's own inner lock is taken (no nested-lock ordering that could deadlock two concurrent calls)
- [ ] A fabricated handle (0, `u64::MAX`, an index past the registry's current length) never panics on an out-of-bounds index — bounds-checked before indexing

## 2. No C, ever (`.claude/knowledge/no-c-ever.md`)

- [ ] No new `.h`/`.hpp` file anywhere in the diff
- [ ] No `build.rs` invoking `cc`/`cbindgen`/`jextract`
- [ ] No `JNIEnv`, `jni::*`, or any JNI-shaped construct
- [ ] Every new/changed `extern "C"` function's cost scales with `n_rows`, or is lifecycle (open/close/describe) — a per-element crossing is the JNI anti-pattern wearing Panama's clothes, reject it regardless of how small it looks
- [ ] No string (`char*`/`CString`) crosses the boundary except the two fixed-size manifest name fields
- [ ] No callback/upcall introduced for a bulk operation

## 3. SIMD provenance (`.claude/knowledge/simd-provenance.md`, `.claude/knowledge/simd-lane-width-family.md`)

- [ ] `grep -rn "ndarray::hpc" native/lgj-abi/src` returns nothing — every import goes through `ndarray::simd::`
- [ ] `grep -rn "core::arch\|_mm[0-9]\|vld1q\|target_feature" native/lgj-abi/src` returns nothing outside the ONE sanctioned manifest-reporting cfg block in `abi.rs`
- [ ] `native/lgj-abi/src/kernels.rs` is the ONLY file in the crate importing from `ndarray`
- [ ] The independent scalar reference path (`lgj_plan_eval_scalar`) has zero `ndarray` dependency — plain Rust loops only, so it can actually falsify the SIMD path rather than test itself
- [ ] If a primitive was missing from `ndarray::simd`, it was added to `ndarray` under that repo's own W1a consumer contract (all backends + scalar + parity test), never worked around locally
- [ ] Doc comments naming a lane-width type (`U32x16`, etc.) match what the compiled backend actually dispatches — don't assert a width the build baseline doesn't guarantee

## 4. Public Java API surface (`.claude/agents/java-surface-warden.md`)

- [ ] Zero `java.lang.foreign` types (`MemorySegment`, `Arena`, `Linker`, `MethodHandle`, `FunctionDescriptor`, `MemoryLayout`, `VarHandle`) in any signature outside `internal/ffm/`
- [ ] No public `long`/numeric value that is secretly a pointer, lane id, or opcode without a domain-terms Javadoc sentence explaining what it means
- [ ] `View.where(...)` performs zero downcalls and allocates zero native masks — only a terminal operation executes
- [ ] Composition can only narrow a `View` — no code path (public or accidental) widens it
- [ ] No `Stream<Element>`/`List<Element>`/`Element[]`/`Iterator` over hydrated rows anywhere in the public surface — 64K rows must never become 64K Java objects (`.claude/knowledge/john-doe-migration-thesis.md`)
- [ ] Schema vocabulary (`Pattern.CLASS.gt(...)`, etc.) is typed per-field — a type mismatch must fail to COMPILE, not fail at runtime
- [ ] The schema vocabulary reads as something a code generator could mechanically emit — no hand-crafted fluent cleverness a generator couldn't produce

## 5. Panama bridge correctness (`.claude/agents/panama-bridge-engineer.md`)

- [ ] Every `MemoryLayout` in `Layouts.java` independently derives (via `layout.byteSize()`/`byteAlignment()`) the same size/alignment `docs/abi.md` documents for the matching `#[repr(C)]` Rust type — not copy-pasted from the doc as a bare constant
- [ ] The manifest cross-check in `Abi.java` compares Java's independently-derived layout sizes against the RUNTIME manifest from `lgj_abi_manifest()`, not against another Java constant
- [ ] `abi_major` mismatch fails load; `abi_minor` requires `>=` (both directions tested)
- [ ] Every downcall `MethodHandle` is resolved exactly once into a `static final` — never re-resolved inside a hot-path method
- [ ] Every `FunctionDescriptor` argument order/layout matches `docs/abi.md` §7 exactly — a mismatch here is silent data corruption, not a compile error
- [ ] `--enable-native-access` is documented in `java/README.md`'s exact command lines, and those commands were actually run against `/opt/jdks/jdk-26.0.2`

## 6. Valhalla / benchmark claims (`.claude/agents/valhalla-lab-scientist.md`)

- [ ] Every semantic value type claim states all three truths (semantic / stable-Java / Valhalla) — never Valhalla behavior presented alone as the whole story
- [ ] Every number in `bench/` has an exact reproduction command line (JDK path, flags, row count)
- [ ] Cost components are NOT conflated: bare downcall overhead, `MemorySegment` throughput, the Rust kernel, Vector API execution, fused-vs-unfused, View-construction cost are all reported separately
- [ ] JVM warmup is real (stated iteration counts, warmup-vs-measured split) — no cold-JVM-vs-warmed-native comparison
- [ ] A discovered Valhalla limitation produced a reproducer in `valhalla-lab/reproducers/`, not a workaround baked into the public API
- [ ] The mandatory N-objects vs N-values vs 1-lane experiment is present with real measured numbers, not the thesis's prediction asserted as fact
- [ ] JMH-vs-hand-rolled is labeled honestly — no hand-rolled loop presented as JMH-grade

## 7. Toolchain / build discipline

- [ ] No spawned agent ran `cargo` in any form (`.claude/knowledge/agent-cargo-hygiene.md`) — only the orchestrating main thread compiles/tests/lints
- [ ] `native/lgj-abi/.cargo/config.toml`'s `target-cpu` baseline matches what the artifact is actually meant to run on (v4/AVX-512 for this-host-only research use; v3/AVX2 if ever redistributed — see `TECH_DEBT.md` `TD-LGJ-V4-BASELINE-NOT-PORTABLE`)
- [ ] `/opt/jdks/jdk-26.0.2` used for production Java, `/opt/jdks/jdk-27` (JEP 401 EA) used ONLY for `valhalla-lab/` — no `--enable-preview`-compiled class ever reaches the production `java/` tree
- [ ] `df -h /` checked before and after any large parallel dispatch — target-dir residue is a known risk this session (`ISS-LGJ-TARGET-DIR-SIZE-WATCH`)

## 8. The simd.rs isomorphism (root CLAUDE.md E1–E6 — added 2026-08-27, from three same-day strikes)

- [ ] No Java loop over rows, facets, or partial results in `src/main` — a Java-side reduction, however small, is an inline scalar fallback in the facade (E1). The tell to grep for: a doc comment defending Java-side compute on crossing count ("saves a crossing") — R8 measured bulk crossings as free, and that defence appeared verbatim on the violation
- [ ] Java scalar recomputes appear ONLY in test suites as oracles (E2) — the license `simd.rs` gives raw intrinsics under `#[cfg(test)]`, and nowhere else
- [ ] No hand-written row-geometry literal (`512`, `16`, `+ 4`, `+ 12`) in the facade — sizes and offsets come from `internal/ffm/Layouts`' DERIVED constants (E3); a second spelling of the layout is the carving-triplication defect minor 8 killed, reborn
- [ ] No Vector API in `src/main` (E4 — a backend inside Java; lab arms only)
- [ ] A new facade method is ONE delegation — anything more means the substrate is missing a word and the change starts backend-first (E5, the STOP rule)

## 9. PR hygiene

- [ ] Commit message body explains the WHY, not just the WHAT
- [ ] PR body includes a Test Plan with checkboxes, and states which falsification gates from `.claude/plans/lgj-vertical-slice-v1.md` were actually run (not just "should pass")
- [ ] Cross-references to `EPIPHANIES.md`/`TECH_DEBT.md`/`ISSUES.md` entries where relevant
- [ ] Board files updated in the SAME PR as the code they describe (`LATEST_STATE.md`, `STATUS_BOARD.md`) — per this workspace's own board-hygiene convention, a PR that ships a type/plan/finding without updating the board is incomplete

---

## How to use this checklist

**As a worker writing code:** open this file alongside your scratchpad.
Tick boxes as you go. Boxes you can't tick — either fix the issue or
document the deferral in the PR body.

**As main-thread reviewing a diff:** scan §1-§6 relevant to the touched
code. Items not addressed — request changes before merge.

**As a future session:** this checklist is pre-registered from this
repo's own stated design risks, not yet from a real codex finding —
unlike medcare-rs's checklist, which was written AFTER two P1/P2
catches. Add new items the moment a real review (codex or otherwise)
catches something that should have been pre-emptive, and update the
provenance table below.

---

## Provenance

| Source | Cross-reference |
|---|---|
| `docs/abi.md` §4 (ownership/handle safety) | this file §1 |
| `docs/abi.md` §1, `.claude/knowledge/no-c-ever.md` | this file §2 |
| `.claude/knowledge/simd-provenance.md`, `simd-lane-width-family.md` | this file §3 |
| `.claude/knowledge/john-doe-migration-thesis.md` | this file §4 |
| `docs/abi.md` §5, §7 | this file §5 |
| `.claude/knowledge/valhalla-three-truths-method.md` | this file §6 |
| `.claude/knowledge/agent-cargo-hygiene.md`, `EPIPHANIES.md` `E-LGJ-V4-DIVERGES-FROM-NDARRAY-DEFAULT-1` | this file §7 |
| medcare-rs `.claude/board/CODEX_REVIEW_CHECKLIST.md` (the pattern this file adapts) | structural template only — domain content is unrelated (no PHI/RBAC/crypto in this repo) |
| No codex findings yet — session 1 | update this row the first time one lands |

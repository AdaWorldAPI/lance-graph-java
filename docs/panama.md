# The Panama membrane

> Companion to `docs/abi.md` (the normative Rust-side contract this
> document's Java-side machinery is checked against) and
> `.claude/agents/panama-bridge-engineer.md` (the review checklist for this
> code). This document explains the design decisions; the code and the
> tests are the proof.

## The one property that matters: a header is a claim, a manifest is a fact

Project Panama exists so the JVM can speak the platform calling convention
directly — no C compiler, no header, no `jextract` (see `docs/abi.md` §1,
`.claude/knowledge/no-c-ever.md`). But that only removes the *tool*; it
does not remove the *risk* a header used to (loudly) warn about: Java's
compiled-in idea of a struct's layout silently disagreeing with what the
native artifact actually produces.

This project's answer is `lgj_abi_manifest()` — a function that returns a
pointer to a `'static` struct the compiled `.so` fills in from
`core::mem::size_of`/`align_of` on its own real types, never a hand-typed
constant (`native/lgj-abi/src/abi.rs`, with `const _: () =
assert!(size_of::<T>() == N)` compile-time locks on every `#[repr(C)]`
type). `Abi.java` reads this manifest at load time and compares it against
**a second, independently-derived number**: Java's own `MemoryLayout`
definitions in `Layouts.java`, via `layout.byteSize()`/`byteAlignment()`.

Two independently-computed numbers, not one number checked against itself.
`AbiContractTest` proves the check is real: a genuine shared library that
loads fine (`libz.so.1`) is still rejected — refused, not called into —
because it exports no `lgj_abi_manifest` symbol at all.

## Ownership crosses the FFM boundary as belt-and-braces, not once

`docs/abi.md` §4 gives Rust the generation-checked handle as the ground
truth for whether a resource is alive. Panama gives the *Java-side*
bookkeeping no borrow checker at all, so `Abi`/the public facade adds its
own fail-fast layer on top rather than trusting the native check alone:
a Java-side closed flag on a resource makes a use-after-close throw a
clear Java exception (`ClosedResourceException`) *before* the call ever
reaches native code — verified by `LifetimeTest`'s 23 checks (use after
close, double close, a selection outliving its parent, a selection closed
before its parent, an empty resource behaving legally). The native
`INVALID_HANDLE`/`PARENT_CLOSED` status codes are what actually prevent
memory unsafety; the Java-side flag is what keeps the failure mode
readable instead of an opaque native error surfacing through five layers
of `MethodHandle.invokeExact`.

## Restricted methods are a feature, not friction

Every FFM operation this project needs — `SymbolLookup.libraryLookup`,
`Linker.downcallHandle`, `MemorySegment.reinterpret` — is `@Restricted` in
the JDK, meaning it needs `--enable-native-access` and produces a compiler
warning without a suppression. This project does not suppress it: `javac
-Xlint:all` on the shipped tree produces **exactly 7** `[restricted]`
warnings, every one of them inside `internal/ffm/*` or a test file
deliberately exercising the same restricted call independently
(`AbiContractTest`). That count is a machine-checkable statement — not
prose — that every unsafe FFM operation in this project lives in the one
package `ApiSurfaceTest` already proves the public API never exposes.

## Downcall handles are resolved once, never per call

`Downcalls.java` resolves every `MethodHandle` into a `static final` at
class-init, matching `FunctionDescriptor`s to `docs/abi.md` §7 argument-
for-argument. `Component A` in `bench/` deliberately re-binds its own
method handles independently of `Downcalls` — not out of duplication, but
because measuring the JDK's own linker cost separately from this
project's wrapper is the only way to attribute the wrapper's overhead
correctly (`bench/README.md` rule 4). Measured: a bare downcall costs
~22 ns over a plain Java call (`bench/RESULTS.md`, Component A) — the
floor every native-side operation in this project sits on top of.

## `--enable-preview` never reaches the shipped path

The production Java tree (`java/`) targets `/opt/jdks/jdk-26.0.2`, where
FFM is **final** — the only flag needed anywhere is
`--enable-native-access=ALL-UNNAMED`. The Valhalla lab
(`valhalla-lab/src/valhalla`) is compiled *separately*, with its own
`-source 27 --enable-preview`, into its own output directory
(`results/valhalla-lab/`), and is never on the classpath the production
tests or the bench harness run against. `--enable-preview`-compiled
classfiles carry a preview marker that poisons every consumer that loads
them; keeping the two trees physically separate (rather than, say,
compiling once and gating features at runtime) is what makes this a
structural guarantee rather than a discipline someone could accidentally
violate. See `.claude/knowledge/jdk-toolchain-facts.md` for the exact
verified flag matrix across all three JDKs this project touches.

## What Panama did NOT need to solve here

Two things worth naming because they are easy to assume Panama handles and
it does not, by design, in this project:

- **No upcalls.** `Linker.Option`/`upcallStub` exist in the API; this
  project has zero uses of them. An upcall per element is the JNI
  anti-pattern in a different costume (`docs/abi.md` §10), and the bulk-op
  shape never needs a callback into Java mid-kernel.
- **No `captureCallState`/`errno`.** Nothing this membrane wraps is a
  syscall. Every failure is a negative `i32` status the Rust side computed
  deliberately, never an OS error code Panama would need to capture.

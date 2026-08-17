# There Is No C Here — Ever

> READ BY: all agents touching native/lgj-abi, java/internal/ffm, or any
> future jextract/cbindgen/JNI proposal

## Status: FINDING (locked by the project's founding directive)

The user's own words, verbatim: **"There's no C ever. We reuse Panama project
for a rust only."**

## The One-Line Rule

`extern "C"` names a **platform calling convention** (SysV AMD64 psABI here,
AAPCS64 on ARM). `#[repr(C)]` names a **platform aggregate layout rule**.
Neither requires C source, a C compiler, a header, or a C runtime. The stack
is `Rust → platform ABI → Java`, with zero C artifacts anywhere.

## Consequences (all load-bearing, all checked mechanically)

- No `.h` file exists in this repo. Grep for one before merging anything.
- No C toolchain (`gcc`/`clang`) is a build dependency.
- **No `cbindgen`.** Its output is a C header; we have no consumer for one.
- **No `jextract`.** jextract's only input is a C header — with none to
  extract from, the tool has no job here. This is not "unused," it is
  structurally inapplicable.
- **No JNI**, and no JNI-*shaped* Panama code either (see the anti-JNI rule
  in `docs/abi.md` §6 and the `abi-membrane-warden` agent card).

## What replaces the header

A **self-describing runtime manifest** (`lgj_abi_manifest()`), emitted by the
compiled artifact itself, read and cross-checked by Java at load time. See
`docs/abi.md` §1 and §5. A header is a claim; the manifest is a fact about
the artifact that produced it — it cannot disagree with itself.

## Falsifier

Any of these in a diff is an automatic block:
- a new `.h`/`.hpp` file anywhere in the repo
- a `build.rs` invoking `cc`/`cbindgen`
- a `jextract` invocation in any script or CI file
- `JNIEnv`, `jni::*`, `#[no_mangle]` functions shaped as one-call-per-element
  (grep the function body: does its cost scale with `n_rows`, or is it
  lifecycle? If neither, it's the JNI anti-pattern wearing Panama's clothes)

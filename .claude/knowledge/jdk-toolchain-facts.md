# Pinned Toolchain Facts — Verified, Not Assumed

> READ BY: every agent before invoking javac/java, before writing a build
> script, before making any claim about FFM/Vector-API/Valhalla preview
> status. Facts below were verified by direct execution in this
> environment, not recalled from training data — re-verify before trusting
> if the environment changes.

## Status: FINDING (measured directly, dated)

## Rust

- Toolchain: **1.97.1** (stable), installed via `rustup toolchain install
  1.97.1`. Matches the pin in `ndarray/rust-toolchain.toml` and
  `lance-graph/rust-toolchain.toml`.
- Host CPU has AVX-512 (`avx512f/bw/cd/dq/ifma/vbmi/vl`) — both the AVX2
  (v3) and AVX-512 (v4) backends of `ndarray::simd` are exercisable here.
- `ndarray/.cargo/config.toml` pins `-Ctarget-cpu=x86-64-v3` as the default
  build baseline (SIGILL trap if omitted downstream — see `abi.md` §"SIGILL
  trap"). Any crate depending on `ndarray`'s AVX2 backend must mirror this.

## JDKs available locally

| Path | Version | FFM (`java.lang.foreign`) | JEP 401 value classes | Vector API |
|---|---|---|---|---|
| `/usr/bin/java` (system) | OpenJDK 21.0.10 | **preview** — needs `--enable-preview` | not present | incubating, needs `--add-modules jdk.incubator.vector` |
| `/opt/jdks/jdk-26.0.2` | OpenJDK 26.0.2 GA | **FINAL** — no preview flag, only `--enable-native-access=<module>` for restricted calls | not present (mainline, not the Valhalla fork) | incubating |
| `/opt/jdks/jdk-27` | `27-jep401ea3+1-1`, official JEP 401 EA build from `jdk.java.net/valhalla/` | final (this vintage postdates FFM finalization) | **works** — `value class`, `value record`, `Class.isValue()` verified `true`, with `--enable-preview --release 27` | incubating |

**Verified by direct execution in this session** (not by reading docs):
`Arena.ofConfined()` + `MemorySegment` read/write + `Linker.nativeLinker()`
(`SysVx64Linker`) all work on `/opt/jdks/jdk-26.0.2` with zero preview flags.
`value class LaneId { ... }` and `value record RowRange(...)` both compiled
and ran on `/opt/jdks/jdk-27` with `--enable-preview`, and
`LaneId.class.isValue()` printed `true`.

## Decision this locks in

- **Production path (`java/`) targets `/opt/jdks/jdk-26.0.2`.** No preview
  flags in the shipped build. This is a real, deliberate strength of the
  design: the FFM membrane runs on a *shipped GA JDK*, not an experimental
  one.
- **Valhalla lab (`valhalla-lab/`) targets `/opt/jdks/jdk-27`.** Same source
  shape, compiled twice (once as `record`, once as `value record`), so the
  A/B is genuinely apples-to-apples.
- **The full Valhalla source checkout at `/home/user/valhalla` is NOT
  needed** for this project. It was independently confirmed (by a separate
  archaeology pass) to be *behind* mainline `/home/user/jdk` for
  value-class purposes — its last relevant commit is literally "things to
  delete from lworld just before integrating JEP-401." The official EA
  *binary* download supersedes building it from source. Do not spend time
  building `/home/user/valhalla` or `/home/user/panama-foreign` from
  source for this project.
- **`--enable-preview` is a classfile-poisoning flag** (classfile minor
  version becomes preview-marked): every `.class` compiled with it can only
  run with `--enable-preview` set, and this contaminates transitively. Keep
  the Valhalla-flavored sources physically separate from the production
  `java/` tree for exactly this reason — never let a preview-compiled class
  leak into the path a JDK-26 consumer loads.

## Falsifier

Any doc or code comment asserting "value classes are final in JDK 28" or
"Vector API is finalized" without re-verifying against a real build is
wrong until re-checked — both were incubating/preview at last verification.

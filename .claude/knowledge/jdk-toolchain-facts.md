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

---

## ⊘ ACQUISITION LADDER — added 2026-09-16, because this doc was RIGHT and a session still concluded the opposite

This file already named `/opt/jdks/jdk-26.0.2` as the production path. A later
session nonetheless shipped two commits titled **"UNVERIFIED: JDK 26 not
available in this sandbox"** and left the Java half of an ABI-minor-11 change
unverified through to PR.

Re-checked 2026-09-16: **`/opt/jdks/jdk-26.0.2` was present the whole time**
(26.0.2.1, 2026-08-18), and the full suite runs `ALL PASSED (409 checks)` on
it. Nothing was missing. The session looked at `java -version` / `/usr/lib/jvm`
— which show only the system OpenJDK 21 — and inferred absence.

**The generalizable defect is in the SHAPE of what this doc recorded, not in
its accuracy.** It recorded a LOCATION. A location is environment-specific and
evaporates when a container is rebuilt, and a reader who does not find it has
no next move. A METHOD survives. So the ladder below is the method; check the
rungs in order and only report "no JDK" after all four fail.

### Rung 1 — the pre-provisioned path (check this FIRST, always)

```sh
ls -d /opt/jdks/*/ && for d in /opt/jdks/*/; do "$d/bin/java" -version; done
```

`which java` and `/usr/lib/jvm` do **not** see `/opt/jdks`. A session that only
runs those two will conclude "21 only" while a 26 sits one directory away. That
is exactly what happened.

### Rung 2 — the system archive (Ubuntu noble → JDK 25)

```sh
sudo apt-get update                          # NOT optional, see the 404 trap
sudo apt-get install -y openjdk-25-jdk-headless
```

Panama FFM is **final since 22**, so 25 runs the whole `internal/ffm` membrane
and the entire suite. Verified 2026-09-16: `ALL PASSED (409 checks)` on
`openjdk 25.0.4`, identical to 26.

**The 404 trap.** The pre-seeded index named `openjdk-25 25.0.2+10-1~24.04`, a
version already withdrawn from the pool, so the install died on a bare
`404 Not Found` — which reads as *"no such package"* rather than *"your index
is stale"*. `apt-get update` moved the candidate to `25.0.4+7-1~24.04` and it
installed immediately. **A cached view reporting absence is not evidence of
absence** — the same failure shape as rung 1.

### Rung 3 — an extra apt source (Adoptium → JDK 26, and 8..26 generally)

Ubuntu noble stops at 25. Adoptium carries 8 through 26:

```sh
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo tee /etc/apt/keyrings/adoptium.asc >/dev/null
. /etc/os-release && echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] \
https://packages.adoptium.net/artifactory/deb $VERSION_CODENAME main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo apt-get install -y temurin-26-jdk
```

Verified 2026-09-16: `temurin-26-jdk` → `26.0.2.1`, `ALL PASSED (409 checks)`.
`apt-cache search temurin` shows **no 27+**, so this rung tops out at 26.

### Rung 4 — `jdk.java.net` for the Valhalla EA, and this one IS now needed

**`/opt/jdks/jdk-27` is GONE from this container** — the JEP 401 EA build this
doc's table names for `value class` / `value record` is no longer
pre-provisioned, and no apt rung can replace it (Adoptium stops at 26, and
mainline 27 would not help anyway: `value record` is a Valhalla EA feature, not
a mainline one).

`https://jdk.java.net/valhalla/` is reachable through this environment's proxy
(verified `HTTP 200`). Re-fetch there when `valhalla-lab` is the actual work.

### Which rung for which need

| need | rung | status 2026-09-16 |
|---|---|---|
| the 16-suite `AllTests` run (409 checks) | 1, 2 or 3 — any | **verified on all three**: `/opt/jdks/jdk-26.0.2`, `openjdk-25`, `temurin-26` |
| Panama FFM / the whole `internal/ffm` membrane | 1, 2 or 3 | final since 22, so any of them |
| `valhalla-lab/src/stable` (records, JDK 26) | 1 or 3 | fine |
| `valhalla-lab/src/valhalla` (`value record`, JEP 401 EA) | **4 only** | **pre-provisioned build GONE; refetch** |

`bench/` and `valhalla-lab/` are measurement arms, **not gates**. The merge gate
is the Rust suite plus the 409-check Java run, and every rung that satisfies it
is reachable here.

### The standing consequence

**"The Java side could not be verified" is not an acceptable status line for
this repo.** Four rungs, four minutes. A PR that claims the Java surface is
unverified is claiming something falsifiable by `ls /opt/jdks`.

## Falsifier for this section

If a future container has none of rungs 1-3, the claim "always reachable" is
dead and this section must be re-graded rather than trusted. Check by running
rung 1 and rung 2 and reporting both outcomes — not by recalling this table.

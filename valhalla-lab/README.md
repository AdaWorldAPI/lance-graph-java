# The Valhalla laboratory

The three-truths method (`.claude/knowledge/valhalla-three-truths-method.md`) applied to this
project's small semantic value vocabulary — `LaneId`, `Ordinal`, `MaskId`, `RowRange`, `Row` — and
to the mission's mandatory headline experiment: does Valhalla rescue per-entity materialization at
65,536-row scale, or only the tiny descriptor vocabulary around it?

**Same experiment source, compiled twice** — once against a stable JDK where the vocabulary types
are plain `record`s, once against the JEP 401 early-access JDK where they are `value record`s — so
the comparison is genuinely apples-to-apples, not two different programs.

## Layout

```
src/shared/     experiment logic, byte-identical on both compiles
src/stable/     Vocab.java (record), Containers.java, Platform.java — the stable-JDK half of the A/B
src/valhalla/   Vocab.java (value record), Containers.java, Platform.java — the Valhalla half
```

`Platform` is the one seam between them: same signatures on both sides, so `src/shared/` never
branches on which platform it's running on except by asking `Platform` — never by calling a
Valhalla-only API (like `Class::isValue` or `jdk.internal.value.ValueClass`) directly. That is a
real rule, not a style preference: `Class::isValue` does not exist at all on a stable JDK, so a
direct call would fail to *compile* the stable half, not just report the wrong answer.

`NativeAccess` (in `src/shared/`, package `com.adaworldapi.lancegraph`) is a read-only, split-package
escape hatch into the shipped library's package-private handle — documented in the file itself. It
exists because the lab has to build the very thing the thesis says you should not build (65,536 Java
objects) from the *same bytes* the native kernel reads, or the comparison proves nothing. Nothing
under `java/` changes to support this.

## Build and run

### Stable half (JDK 26 GA, plain `record`s)

```sh
javac -d out-stable $(find ../java/src/main/java src/shared src/stable -name '*.java')

java --enable-native-access=ALL-UNNAMED \
    -Dlgj.library=../target/release/liblgj_abi.so \
    -cp out-stable com.adaworldapi.lancegraph.lab.RunAll
```

### Valhalla half (the JEP 401 EA build, `value record`s)

`--release` cannot be combined with `--add-exports` (a real javac restriction — `--release` uses a
stricter cross-compilation module model). Use `-source` instead when compiling *for* the JDK you are
also running on, which is the case here.

```sh
javac --enable-preview -source 27 \
    --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
    -d out-valhalla $(find ../java/src/main/java src/shared src/valhalla -name '*.java')

java --enable-preview --enable-native-access=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
    -Dlgj.library=../target/release/liblgj_abi.so \
    -cp out-valhalla com.adaworldapi.lancegraph.lab.RunAll
```

Both need the JDK paths from `.claude/knowledge/jdk-toolchain-facts.md` — do not use `/usr/bin/java`
(JDK 21, no value classes at all) for either.

## What each experiment measures

| Class | Question |
|---|---|
| `IdentityExperiment` | Truth (a), semantic: is identity actually unobservable? `Class::isValue`, reference equality, array flatness, `synchronized` legality — measured on both platforms, asked to agree everywhere except reference equality (which no caller in the production API uses). |
| `FootprintExperiment` | Truth (b)/(c), representation: per-object bytes, array layout, field flattening, call-argument passing — via `jol-core`'s real VM instrumentation where available, allocation-delta measurement elsewhere. |
| `FfmAddressingExperiment` | Is the wrapper free where it actually touches native memory — a `RowRange`/`Ordinal` around an FFM offset vs a bare `long`? |
| `ThesisExperiment` | The mandatory headline: 65,536 rows as (1) one native lane + one packed mask + one crossing, vs (2)/(3) hydrated Java objects, on the SAME question and the SAME answer. Heap cost and wall time, both platforms. |

## Measured headline (2026-08-17, this environment)

Real numbers from a real run — reproduce with the commands above before citing a different number.

|  | native, one crossing | hydrate 65,536 `Row`, then scan |
|---|---:|---:|
| stable JDK 26 | 19.5 µs, 289 KiB Java-side | 746 µs, 2.00 MiB |
| Valhalla (JDK 27 EA) | 15.7 µs, 289.5 KiB Java-side | 900 µs, 2.50 MiB |

The native path wins by roughly **38–57×** on time and **7–9×** on Java heap, on **both** platforms —
Valhalla does not close this gap, because `Row` (multiple fields) measured `NOT-FLAT` even under
Valhalla, while the single-field `LaneId` measured `FLAT` (2.90 B/element vs 16.00 B on stable, ~5.5×
smaller). This is the mission thesis's prediction, confirmed rather than assumed: **Valhalla helps
the tiny descriptor vocabulary; it does not rescue per-entity materialization at this scale.** See
`IdentityExperiment`'s and `FootprintExperiment`'s full output for the field-by-field evidence.

## A defect found and fixed while wiring this up

The first version of `IdentityExperiment`/stable `Platform` called `Class::isValue()` directly for
four of the five vocabulary types (`Ordinal`/`MaskId`/`RowRange`/`Row`), with a comment incorrectly
claiming it was "final API on JDK 26." It is not — `javac` on JDK 26 GA does not have that method at
all, confirmed by a real compile failure, not by reading documentation. Fixed by routing every
identity query through `Platform.isValueClass(Class<?>)`, which the stable half answers `false` (a
JDK with no value-class concept can never produce one, so the answer is exact, not a guess) and the
Valhalla half answers with the real `type.isValue()`. See `EPIPHANIES.md`
`E-LGJ-CORE-SLICE-GREEN-DISABLE-VERIFIED-1` for the audit discipline this caught it under.

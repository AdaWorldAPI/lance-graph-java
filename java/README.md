# The Java side

> The product is this API. The ABI (`../docs/abi.md`) is a machine membrane underneath it, and a
> consumer of this package is never asked to know that the membrane exists.

## What a consumer writes

```java
try (var data = NativePattern.open(65_536)) {
    long n = data.view()
                 .where(Pattern.CLASS.eq(7))
                 .where(Pattern.VALUE.gt(100))
                 .count();
}
```

Nothing in that snippet mentions an arena, a memory segment, a linker, a lane index, an opcode, a
packed mask word, or a SIMD backend. It is nevertheless the whole real path: 65,536 rows that never
enter the Java heap, two predicates fused into one vectorised kernel, **one** crossing of the
membrane, one number back.

That is the thesis in one line: **64,000 logical entities do not become 64,000 Java objects.** They
become one native lane set, one packed mask, a few tiny schema descriptors, and one bulk operation.

## Toolchain

There is no Maven, no Gradle, no downloaded dependency, and no C toolchain. `javac` and `java` are
the entire Java toolchain, exactly as `cargo` is the entire Rust one.

- **JDK 26** (`/opt/jdks/jdk-26.0.2`). FFM is **final** in JDK 26, so `--enable-preview` is neither
  needed nor accepted. Do **not** use a JDK 21 `java` on the path — there FFM is preview-gated and
  these commands will not work.
- No JNI, no `jextract`, no `cbindgen`, no `.h` file anywhere in the project. See `../docs/abi.md`
  §0 for why those are absent by construction rather than by preference.

## Build

```sh
cd java
/opt/jdks/jdk-26.0.2/bin/javac -d out $(find src/main/java src/test/java -name '*.java')
```

Compilation emits six `[restricted]` warnings with `-Xlint:all`, all of them in
`internal/ffm/{Abi,Downcalls,Engine}.java`. That is not noise to be suppressed — it is a
machine-checkable statement that every unsafe FFM operation in the project lives in the one package
that is allowed to contain them.

## Run

```sh
# everything
/opt/jdks/jdk-26.0.2/bin/java --enable-native-access=ALL-UNNAMED -cp out \
    com.adaworldapi.lancegraph.AllTests

# or one suite at a time — each has its own main
/opt/jdks/jdk-26.0.2/bin/java --enable-native-access=ALL-UNNAMED -cp out \
    com.adaworldapi.lancegraph.SmokeTest
```

### Flags

| Flag | Needed? | Why |
|---|---|---|
| `--enable-native-access=ALL-UNNAMED` | **yes** | FFM's restricted methods (`libraryLookup`, `downcallHandle`, `reinterpret`) refuse to run without it. Omitting it does not fail the build — it fails at load, which is worse. |
| `--enable-preview` | **no** | FFM is final in JDK 26. Passing it is an error. |
| `-Djava.library.path=…` | **no** | Not used. That is the JNI mechanism; this project resolves the artifact itself (below). |

### Where the native library is found

Resolution order, first hit wins:

1. `-Dlgj.library=/abs/path/liblgj_abi.so` — an explicit file.
2. `LGJ_LIBRARY` environment variable — same meaning.
3. `-Dlgj.library.dir=/abs/dir` — a directory holding the platform-named artifact.
4. Walking up from the working directory for `target/release/liblgj_abi.so`, then
   `target/debug/liblgj_abi.so`.

Release is searched before debug deliberately: if both exist, silently benchmarking the debug build
would be a measurement error rather than an inconvenience.

If nothing is found, or the artifact is found but disagrees with this build, the failure names the
exact problem and every path that was tried. A test run in that state reports **exit code 2 and the
word SKIPPED**, never a red failure — "you have not built the library" and "your library is broken"
must not look the same in a log.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | all checks passed |
| `1` | at least one check failed |
| `2` | the native artifact is unavailable, so nothing was run |

## Layout

```
src/main/java/com/adaworldapi/lancegraph/                 the public semantic API
src/main/java/com/adaworldapi/lancegraph/internal/ffm/    ALL Panama machinery, and nowhere else
src/test/java/com/adaworldapi/lancegraph/                 tests (plain Java, no JUnit)
```

The split is enforced, not merely intended: `ApiSurfaceTest` walks every public member of every
public type by reflection and fails if any signature mentions `java.lang.foreign.*`,
`java.lang.invoke.*`, or our own `internal.*` package. It needs no native library, because the
shape of an API is a compile-time property — which makes it the one suite worth running *before*
the artifact exists.

## The tests, and what each one would catch

| Suite | The claim it can falsify |
|---|---|
| `ApiSurfaceTest` | The membrane leaked into the consumer API. Needs no native library. |
| `AbiContractTest` | The self-describing manifest is not actually checked, or cannot reject anything. |
| `SmokeTest` | The advertised fluent chain does not work. |
| `FixtureParityTest` | The kernels return the wrong numbers — Java recomputes the expected answers from the generator, independently. |
| `FusionParityTest` | Fusion, unfused composition and the scalar reference disagree. |
| `LazinessTest` | Building a chain crosses the membrane, or a terminal op's cost grows with predicates or rows. |
| `NarrowingTest` | Adding a condition can widen a selection. |
| `LifetimeTest` | A stale handle reaches native code — use after close, double close, a selection outliving its parent. |

Two of these are worth calling out because they are unusual:

**`FixtureParityTest` is a genuine cross-language check.** Property tests ("the count never grows",
"the paths agree") can all pass while every number is wrong together. This suite transcribes the
fixture's SplitMix64 generator into Java, counts with an ordinary loop, and compares against what
the vectorised kernels produced over data Java never sees. It ships no golden file, because a golden
file only proves nobody edited it.

**`LazinessTest` measures rather than asserts.** The membrane counts its own crossings, so
"building a View makes zero crossings" is a number in a log rather than a comment that quietly stops
being true. It also pins the load-bearing property: a four-condition chain and a sixteen-condition
chain both cost exactly one crossing, and so do 1,024 rows and 1,000,000 rows.

## Two things deliberately not on the consumer path

- **`Diagnostics`** — `countUnfused` and `countScalar` exist so fusion and SIMD can be measured
  *against something*. They are in a separately named class rather than as methods on `View`, so
  nobody reaches them thinking they are an option.
- **`internal.ffm.Engine`** — the only way to raw native storage, reachable only by naming an
  internal package explicitly. Ordinary composition never hands it to you.

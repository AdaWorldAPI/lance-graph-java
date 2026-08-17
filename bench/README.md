# bench — where does execution belong?

A JMH harness that keeps the cost components **separate**, because conflating them is how a
benchmark ends up arguing for whichever side its author already preferred. Put enough work behind
a downcall and the crossing disappears; put none behind it and the crossing is everything.

> **This is real JMH** — `jmh-core 1.37`, forked JVMs, per-fork warm-up, compiler blackholes
> (the run log confirms `Compiler Blackholes ... are in use`). Not a hand-rolled loop. The
> hand-rolled harness in [`valhalla-lab`](../valhalla-lab) is labelled as such in its own README
> and its timings are secondary evidence there.

**The measured numbers and the verdict are in [`RESULTS.md`](RESULTS.md).**

## Run it

```sh
cd bench && ./run.sh          # everything (~12 min on 4 vCPU)
cd bench && ./run.sh C_       # only the execution-boundary row sweep
```

Requires `liblgj_abi.so`. Build it first if missing:

```sh
cd native/lgj-abi && CARGO_TARGET_DIR=$(cd ../.. && pwd)/target cargo build --release
```

Output: `results/jmh-run.txt` (full log, including every warm-up iteration) and
`results/jmh-results.csv` (machine-readable).

## The components, and why each is isolated

| | class | question it answers alone |
|---|---|---|
| **A** | `A_DowncallOverhead` | what does crossing the membrane cost, with no work behind it? |
| **B** | `B_SegmentAccess` | how fast can Java read native memory at all? |
| **C** | `C_ExecutionBoundary` | native kernel vs Java Vector API vs Java scalar, swept over row count |
| **D** | *(in C)* | the Vector API arm — same segment, zero copy |
| **E** | `E_FusionAndPlanning` | fused (1 crossing) vs unfused (N crossings), swept over predicate count |
| **F** | *(in E)* | what does building the fluent chain cost, with no crossing at all? |

C and D live in one class on purpose: the question is a comparison, and separate classes would let
a difference in setup masquerade as a difference in execution. E and F likewise share a fixture.

## The rules this harness holds itself to

**1. Every arm computes the same answer, and it is checked before anything is timed.**
`Data.crossCheck()` runs in `@Setup` and throws if the native, vector, and scalar kernels disagree
on either the count or the sum. This is not politeness: a Vector kernel with a broken tail is
*faster* than a correct one, so an unchecked comparison rewards the bug.

**2. The Vector API arm is genuinely zero-copy.** `IntVector.fromMemorySegment(species, segment,
offset, ByteOrder.nativeOrder())` reads the native lane in place. No `byte[]`, no `int[]`, no
`MemorySegment.toArray`, no bounce buffer. A copy anywhere would make the comparison dishonest in
both directions at once — the Java side would pay a cost the Rust side does not, and the Rust side
would get credit for avoiding a copy the design never requires.

The one heap `int[]` in the harness (`Data.valuesHeap`) is used **only** by Component B, as the
"data already in Java" ceiling. Nothing that compares against the native kernel touches it.

**3. Nothing was added to the native library to be benchmarked.** Component A binds
`lgj_abi_manifest` and `lgj_mask_count` — both real ABI symbols. A symbol that exists only to be
measured is not the thing being measured.

**4. Component A binds its own method handles** rather than reusing
`internal.ffm.Downcalls`, so it measures the JDK's linker rather than this project's wrapper. The
wrapper's own overhead is then visible as the difference between A and C.

**5. The laziness claim is asserted, not assumed.** `planConstructionOnly` reads
`Diagnostics.crossings()` before and after building the chain and throws if it moved.

**6. Warm JVM, always.** JMH forks, warms each fork, and discards warm-up. Cold-start numbers
appear nowhere. `shouldDoGC(true)` runs a GC between iterations so a collection triggered by one
arm is not attributed to the next.

## Settings

`@Fork(1)`, `@Warmup(5 × 500 ms)`, `@Measurement(8 × 500 ms)`, `Mode.AverageTime`. Reported as
mean ± 99.9 % confidence interval, which is JMH's default and what the CSV contains.

**One fork, and that is a limitation worth naming.** A single fork cannot see run-to-run variance
from JIT compilation-order nondeterminism or address-space layout. Two or more forks would be
better; on a 4-vCPU container the full sweep already takes ~12 minutes and doubling it was judged
not worth the wall-clock. Treat differences under roughly 10 % between arms as not established by
this harness.

**Shared container, not a tuned benchmark host.** No CPU pinning, no isolated cores, no disabled
turbo, no disabled hyperthreading. The confidence intervals reflect that. Large effects (order of
magnitude) are safe to read; small ones are not.

## Fetching JMH

The jars are not vendored. Fetch them into `lib/`:

```sh
mkdir -p bench/lib && cd bench/lib
B=https://repo1.maven.org/maven2
curl -sSLO --noproxy '*' $B/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar
curl -sSLO --noproxy '*' $B/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar
curl -sSLO --noproxy '*' $B/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar
curl -sSLO --noproxy '*' $B/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar
```

### One trap, recorded so nobody loses an hour to it

On JDK 23+ annotation processing is **off by default**. Without `-proc:full`, JMH's generator
never runs, no `META-INF/BenchmarkList` is produced, and the harness dies at startup with:

```
ERROR: Unable to find the resource: /META-INF/BenchmarkList
```

which reads like a classpath problem and is not one. `run.sh` passes `-proc:full` with a comment
saying why. JMH also rejects benchmark classes in the default package, with a clear message.

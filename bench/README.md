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

## The §5 gate — two builds, one verdict function

The `RowStore` half of W1.1 (`.claude/plans/epoch-recheck-v3.md` §5) is gated on a per-accessor
cost measurement that Component H could not supply: H times a bare `lgj_lane_describe` crossing,
not the production accessor (Codex P1 on #55). Component I is the instrument that does, and it is
deliberately split from `run.sh` because it cannot share one classpath between its arms:

```sh
cd bench && ./gate-run.sh                     # builds java/ TWICE, runs Component I against each
cd bench && ./gate.py --selftest              # the §5 verdict table, pinned on the plan's own cases
cd bench && ./gate.py --n <N> --amendment <sha> results/gate-before.csv results/gate-after.csv
```

- **The seam.** `java/src/main/java/.../LaneProbe.java` is a package-private no-op that
  `RowStore.lane()` calls on every cached read. `bench/variants/probed/LaneProbe.java` is the same
  class with the per-access liveness probe (re-describe + epoch/length compare, the `Mask.words()`
  shape from #53). `gate-run.sh` compiles the production tree once as shipped and once with that
  single file swapped — §5.5's build-time variant swap, and the two-build consequence
  `ISS-LGJ-BENCH-GATE-PRECEDES-ITS-SUBJECT` names, taken literally. There is no flag and no branch
  in either arm; both are `RowStore.classidAt` as the JIT sees it.
- **The instrument.** `I_ProductionAccessorGate` has one `@Benchmark`, `store.classidAt(row, f)`,
  Blackhole-consumed, `@Fork(5)`, §5's 65,536-row fixture, `CALLS_PER_OP = 1`. It emits one CSV
  and decides nothing.
- **The verdict.** `gate.py` is the §5 table and only the §5 table: `delta_ns = after − before`,
  `hw_delta = sqrt(hw_a² + hw_b²)`, ex-ante power `hw_delta < N/2`, then PASS / FAIL /
  UNDERPOWERED from the delta interval against `N`; the "below resolution" label when the interval
  contains 0; the 2× ratio recorded as a flag that blocks nothing. `--selftest` pins the plan's
  own worked examples, including the three verdict rules §5 struck. It refuses `N ≤ 0`, refuses a
  CSV with fewer than 5 × 8 samples (so `LGJ_BENCH_QUICK=1` smoke runs can never be mistaken for
  evidence), and requires the sha of the amendment that recorded `N` — a run whose `N` was chosen
  after the numbers is not pre-registered and this tool will not bless it.

**What has NOT happened:** the gate has not been run as evidence. No amendment names `N`, and §5.2
says that commit must precede both runs. A quick-mode smoke run on 2026-09-03 (1 fork, 2
iterations — refused by `gate.py` by design) showed the swap takes: before ≈ 11 ns/call, after ≈
41 ns/call, consistent with H's +35.5 ns bare crossing. That is a plumbing check, not a
measurement, and it is not banked.

## The components, and why each is isolated

| | class | question it answers alone |
|---|---|---|
| **A** | `A_DowncallOverhead` | what does crossing the membrane cost, with no work behind it? |
| **B** | `B_SegmentAccess` | how fast can Java read native memory at all? |
| **C** | `C_ExecutionBoundary` | native kernel vs Java Vector API vs Java scalar, swept over row count |
| **D** | *(in C)* | the Vector API arm — same segment, zero copy |
| **E** | `E_FusionAndPlanning` | fused (1 crossing) vs unfused (N crossings), swept over predicate count |
| **F** | *(in E)* | what does building the fluent chain cost, with no crossing at all? |
| **G** | `G_HopExecutionBoundary` | native `lgj_hop` vs the two preserved scalar hop oracles, swept over row count × frontier density (the F-PARITY harness, spec §3.8/§12) |

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

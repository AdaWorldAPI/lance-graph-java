# valhalla-lab

An A/B experiment on the API's small semantic vocabulary — `LaneId`, `RowRange`, `MaskId`,
`Ordinal` — plus a direct test of the project's central claim about bulk data.

**Read [`docs/three-truths.md`](docs/three-truths.md) for the findings and the numbers.**
**Read [`reproducers/README.md`](reproducers/README.md) for the three Valhalla limitations hit.**

## What it does

One experiment source (`src/shared`) is compiled twice: once against `src/stable` (plain
`record`s, JDK 26) and once against `src/valhalla` (`value record`s, JDK 27 EA). The two
vocabularies are **byte-identical apart from the word `value`**, and `run.sh` step 0 diffs them and
refuses to run if that stops being true — without that check the A/B could quietly become a
comparison of two different programs.

```
src/shared/    the experiments — compiled unchanged against both vocabularies
src/stable/    Vocab.java (record), Platform.java, Containers.java     -> JDK 26
src/valhalla/  Vocab.java (value record), Platform.java, Containers.java -> JDK 27 EA
reproducers/   standalone files for each limitation hit, with observed output
results/       every run's output, plus the A/B diff
```

## Run it

```sh
cd valhalla-lab && ./run.sh
```

Requires `liblgj_abi.so`; build it first if missing:

```sh
cd native/lgj-abi && CARGO_TARGET_DIR=$(cd ../.. && pwd)/target cargo build --release
```

Override the JDKs with `STABLE_JDK=` / `VALHALLA_JDK=`. The script runs six configurations —
both platforms at default settings, both with `-XX:-DoEscapeAnalysis`, and Valhalla with each
flattening knob disabled — and writes each to `results/`.

## The experiments

| | what it answers | primary instrument |
|---|---|---|
| `IdentityExperiment` | is the semantic contract observably the same under both object models? | behavioural assertions |
| `FlatteningCliffExperiment` | which payload shapes does the VM actually flatten? | `ValueClass.isFlatArray` |
| `FootprintExperiment` | does the abstraction cost a heap object — in arrays, in fields, in arguments? | `getThreadAllocatedBytes` |
| `FfmAddressingExperiment` | is the wrapper free where it addresses native memory? | allocated bytes, then time |
| `ThesisExperiment` | 65,536 entities: native lane vs Java objects vs Valhalla value objects | allocated bytes, retained heap, time |

## Two things about the method

**Allocated bytes, not nanoseconds, is the primary instrument.** "Did this abstraction cost an
object?" is answered directly by TLAB accounting and only inferred, weakly, from a timing —
escape analysis deletes allocations in tight loops and proves nothing about the general case. The
runs with `-XX:-DoEscapeAnalysis` exist for the same reason: they measure what the object model
costs when the JIT cannot rescue it, which is the property that generalises to real code.

**This is not JMH.** `Lab.time` warms up, measures repeatedly, and reports a median with the full
min/max spread. It does not fork, does not detect steady state, and does not do statistical
inference. JMH-grade numbers live in [`bench/`](../bench) and are produced by actual JMH; the
timings here are secondary evidence supporting the byte counts, and are labelled as such.

## The verdict in one line

Valhalla makes the four-type descriptor vocabulary genuinely free (5.5× less allocation, 8.3×
faster array reads, and 103× → 1.9× once escape analysis is out of the picture). It does **not**
rescue per-entity materialisation — on this build a 16-byte `Row` is over the VM's flattening
budget and costs 25 % *more* than the plain record. Bulk data stays native; the vocabulary stays
records today and becomes value records the day JEP 401 ships.

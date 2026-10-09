# interop/tinkerpop: A1 parity harness

One seeded graph, two engines, the same answers. The harness opens a
`RowStore.openWithEdges` fixture and copies its edges into **TinkerGraph** 3.7.7,
the TinkerPop line JanusGraph 1.x builds on. It then answers k-hop expansions
from the same seeds twice:

| Engine | Query |
|---|---|
| lance-graph-java | `Graph.from(seeds).hop(KNOWS)…count()`, a mask hop chain |
| Gremlin | `g.V(seeds).out("knows")….dedup().count()` |

```sh
./fetch-deps.sh   # once: 19 jars from Maven Central, each sha256-checked against deps.lock
./run.sh          # needs JDK 28 (LGJ_JDK, default /opt/jdks/jdk-28) and native/lgj-abi built --release
```

The harness lives outside `consumers/` on purpose. CI compiles every file under
`consumers/` against `java/out` alone, and TinkerPop is not on that classpath.
The `java-suites` job runs this harness in two separate steps at its end:
`fetch-deps.sh`, then `run.sh` with `LGJ_JDK` set to the job's JDK 28 EA.

## What it checks (25 checks, all green)

- The copy has edges, so a match is not between two empty sets.
- k = 1, 2, 3: the hop-chain count equals the Gremlin `dedup()` count on every fixture. The pinned fixture reproduces GraphHopTest's own 19 and 29.
- Falsifier: hopping over a classid with no edges does not match Gremlin. A second falsifier, run by hand: with the chain one hop short, every comparison goes red.
- Walks versus vertices: `out()…count()` without `dedup()` counts walks, which come out higher (for example 76 against 55). The suite requires at least one fixture where they differ, proving it can tell a bag from a set. Only the `dedup()` form may ever be lowered to a mask chain.

## Rules it follows

- The TinkerGraph copy reads facets one row at a time (`classidAt`, `payloadHi32At`, `payloadLow64At`). That is a scalar test oracle, which lance-graph-java licenses only in test code (rule E2). It is never an execution path.
- Nothing here changes `src/main`. A provider strategy that rewrites Gremlin into mask chains is the next step (A2), and it must keep a counted, visible fallback for every traversal it does not lower.

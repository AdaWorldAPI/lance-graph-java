# interop/tinkerpop: A1 parity harness and A2 lowering strategy

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
- Nothing here changes `src/main`.

## A2: `LanceHopStrategy` (55 checks, all green)

A TinkerPop provider strategy. Register it on a traversal source:

```java
GraphTraversalSource g = graph.traversal()
        .withStrategies(new LanceHopStrategy(store, Map.of("knows", Edge.KNOWS)));
long n = g.V(seeds).out("knows").out("knows").dedup().count().next();
```

It lowers exactly one shape, `V(ids).out(label)^k.dedup().count()` with k >= 1, onto
the mask hop chain. The whole traversal becomes one `LanceHopCountStep`, which
emits the count when iterated. `repeat(out(label)).times(k)` reaches the same
shape because TinkerPop unrolls it first. Vertex ids are row numbers, and each
edge label maps to an edge classid.

Everything else stays with Gremlin, and each such root traversal is counted in
`fellBack()`, with the reason in `lastFallbackReason()`:

- a walk count (no `dedup()`), which is a bag, not a set;
- `has()`, `in()`, `both()`, an unmapped label, more than one label per hop;
- `V()` without ids, a non-numeric id, or an id outside the row store;
- any `as()` label.

`LoweringStrategyTest` checks four things:

- **Parity:** lowered equals plain Gremlin for k = 1..3, through both `out()^k` and `repeat()`, and the traversal really ran as one `LanceHopCountStep`.
- **Provenance:** against an *empty* TinkerGraph the lowered traversal still returns the row store's count, while plain Gremlin returns 0. The answer comes from the mask chain, not from Gremlin.
- **Fallbacks:** each excluded shape is counted once and answers exactly as Gremlin.
- **Counters:** the lowered and fallback counters move by one per root traversal.

Disable runs, each red then green:
- remove the `dedup()` guard: the walk count through `barrier()` gets lowered and gives the distinct count instead (6 red);
- make the lowered step return 0: every parity check and the provenance check go red (7 red).

Gremlin rewrites `out().count()` into `outE().count()` before provider strategies
run, so a plain walk count never reaches the `dedup()` guard. The `barrier()`
case exists to exercise that guard.

The strategy reads the row store it was given, not the graph the traversal
source wraps. Keeping both consistent is the caller's job.

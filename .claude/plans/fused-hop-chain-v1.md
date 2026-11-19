# fused-hop-chain-v1 — the native-call cost of a k-hop chain

> **Status:** PROPOSAL (2026-11-19). Not accepted. Nothing is implemented: no
> Rust, Java, test or `docs/abi.md` change accompanies this file. Base: `main`
> at `0286573`, ABI minor 14. A read-only design pass; the only thing run is
> the probe in §1.3, compiled outside the repository against the existing
> public API. No `cargo` was run (BOOT.md:32-37).
>
> **Where the requirement comes from.** Not from this repository. A separate
> proof of concept (`AdaWorldAPI/janusgraph`, `janusgraph-lgj/`) answers
> Gremlin `V(ids).out(l)^k` shapes with this repository's public Java API only,
> and states a rule of its own: *a read, a filter or an ingest crosses to the
> native side once; releasing a handle afterwards is not counted.* That rule is
> stricter than what this repository accepted (§1.4). It is an outside
> requirement to weigh, and §4 weighs it. The consumer built nothing one layer
> up, which is what the STOP rule asks (`CLAUDE.md:764-770`).

## 0. Short answer

A k-hop count costs `3k + 4` native calls, and a count with one label filter
`3k + 10`. Reading the Java and Rust here and running the shapes confirms every
figure (§1.3). Of the options: A is the status quo; B finds no lever in the
existing API; C does not fit the plan surface; D fits the doctrine but has no
latency, allocation or disable-run evidence. §4 recommends A now and lists the
measurements that would make D acceptable or close it.

## 1. The problem in this repository's terms

### 1.1 Which symbols each Java operation calls

Every `Downcalls` method increments one counter (`Downcalls.java:186-202`),
which `Diagnostics.crossings()` reads. `Abi.requireMinor` reads a cached
manifest and makes no call (`Abi.java:168-177`).

| Java operation | Native calls | Read from |
|---|---|---|
| `RowStore.importRows(rows)` | 2: `lgj_mask_create`, `lgj_mask_describe`; bit writes run in process | `RowStore.java:424-431`, `Engine.java:67-71,525-529` |
| `RowStore.hop` / `Graph.hop` | 2: `lgj_mask_create` (a new destination), `lgj_hop` | `RowStore.java:371-373`, `Graph.java:158-163` |
| `Mask.count()` | 1: `lgj_mask_count` | `Mask.java:71-74` |
| `Mask.materializeRows()` | 1 per call: `lgj_mask_describe` (the cache is re-described each call) | `Mask.java:210-212,317-347` |
| `RowStore.maskOfFacetClass` | 2: `lgj_mask_create`, `lgj_op_eq_classid` | `RowStore.java:159-160` |
| `Mask.ternlog` | 2: `lgj_mask_create`, `lgj_mask_ternlog` | `Mask.java:170-172` |
| `Mask.close()` | 1: `lgj_close`, unconditional | `Mask.java:261` |

`Graph` is eager and immutable: each step owns one native mask and returns a
new `Graph` (`Graph.java:91-103,158-163`). It exposes no frontier and has no
`and`, so a label filter runs on `Mask` directly (`Graph.java:98`).

### 1.2 The four shapes

`V(ids).out(l)^k.dedup().count()` is `importRows` (2), k × `hop` (2k), `count`
(1) and a `close` for each of the k+1 masks: `3k + 4`. `.id()` swaps `count`
for one `materializeRows`: also `3k + 4`. One `hasLabel` after the last hop
adds `maskOfFacetClass` (2), `ternlog` `0xC0` (2) and two closes: `3k + 10`.
`dedup()` costs nothing: a mask is a set.

### 1.3 What was measured here

Probe `Measure.java`, outside the repository, compiled with `--release 28
--enable-preview` against `java/src/main` and `consumers/graph/src/main` and
run against the prebuilt release `liblgj_abi.so` for this commit (abi 0.14). It
mirrors the consumer's `HopChain` (seed, k hops, terminal, close every mask)
through the public API. Seeds: 10 rows (`i*37+5`); edge classid 0, gate 0,
radius 25; label facet 3, class 10.

| Quantity | Measured |
|---|---|
| per operation, alone | importRows 2, hop 2, count 1, materializeRows 1, maskOfFacetClass 2, ternlog 2, close 1 |
| count and id, k = 1, 2, 3 | 7, 10, 13 |
| hasLabel+count and hasLabel+id, k = 1, 2, 3 | 13, 16, 19 |
| same shapes at 2,000 and 65,536 rows, AoS and facet-major | identical counts |

This repository already pins the per-operation values at
`GraphHopTest.java:148-151,171-172`. Not reproduced here: other seed-set sizes
(the consumer ran 1, 10, 500, 5,000 and reports no change; read, not measured)
and its 1,152-byte Java-heap figure (it includes JanusGraph's steps).

Wall time of the same path, median of 21 to 801 runs, shared 4-vCPU container,
one run per cell: indicative only.

| Store | One hop + close of its result | Chain `count`, k = 1 / 2 / 3 | `importRows()` + `close`, empty |
|---|---|---|---|
| facet-major, 2,000 rows | 14.5 µs | 30 / 32 / 47 µs | 0.77 µs |
| facet-major, 65,536 rows | 743 µs | 0.79 / 1.57 / 2.32 ms | 2.2 µs |
| AoS, 2,000 rows | 248 µs | 259 / 498 / 757 µs | 0.36 µs |
| AoS, 65,536 rows | 28.4 ms | 30 / 58 / 85 ms | 3.5 µs |

The 30 and 32 µs cells are not monotone in k: noise exceeds the effect there,
and nothing below about 10 µs should be read as more than an order of magnitude.

### 1.4 What this repository accepted

`consumer-graph-traversal-v1.md:70-73`, falsifier 3: *crossings scale with
HOPS ... equals the hop count (+1 terminal)*. It predates `lgj_hop` replacing
the Java composition (`abi.md:834-853`), under which a hop was one crossing.
The shipped path is 2 per hop plus 1 per mask closed, and
`GraphHopTest.java:400-416` asserts only independence of rows and frontier
size. "Scales with hops" survives; "one crossing per request" was never
accepted here, nor was one per hop.

### 1.5 How much of the time the calls can be

A bare downcall is 22 ns and `lgj_mask_count` 118 ns, registry lookup included
(`bench/RESULTS.md:54-56`). Per extra hop a chain makes one `lgj_mask_create`
and one `lgj_close` beyond the hop. §1.3's last column times an empty import
plus close (which also includes a describe) separately from the hop: 0.77 µs
against a 14.5 µs hop in the smallest facet-major store, about 5 %; 0.3 % at
65,536 rows facet-major; below 0.2 % on AoS. These are two separate timings
from one run on a shared container, so the ratio is indicative, not an upper
bound: the lifecycle arm of a chain (k × create + hop + close against k hops on
one mask) was not measured, and §4 item 1 asks for it. Nothing else a fused
call might change was measured either. Two facts
bear on that: `lgj_hop` allocates four `Vec<u64>` of `n_rows/64` words per call
(`exports.rs:2759,2767,2803-2804`), and no Rust-side allocation test covers
`lgj_hop` (`native/lgj-abi/tests/` holds `plan_eval_no_alloc.rs` and
`plan_scalar_no_alloc.rs` only). Separately, the hop latencies at
`bench/RESULTS.md:276-398` describe a gather kernel that `exports.rs:2703-2711`
says was ruled out and replaced; they are not a basis for any hop latency. The
current record is `.claude/board/hop-mask-algebra-vs-columnar.txt`.

## 2. The rules that decide it

| Rule | Source | Bears on |
|---|---|---|
| ABI small; growth argued, not defaulted; try an op-code in `LgjOpDesc` before a symbol | `abi.md:60-78`; `abi-membrane-warden.md:60-64` | C before D |
| Every function is bulk (∝ `n_rows`) or lifecycle | `abi.md:79-80,484-504`; warden `:37-42` | a hop chain is bulk |
| No strings, no callbacks; symbol count, doc and minor move together | `abi.md:693-701,87-100`; warden `:43-55` | seed carriage; D's mechanics |
| A mask belongs to the resource it was created over | `abi.md:254-307`; `exports.rs:2632-2655` | any call taking masks |
| Java never grows a compute path; the decomposition of an answer does not cross | `CLAUDE.md:565-574` (E1) | Java loops over hops |
| "Saves a crossing" is the recorded tell, not a defence | `CLAUDE.md:576-581` (E2) | B |
| Capability lands backend-first; a facade method that is not one delegation means the substrate lacks a word | `CLAUDE.md:595-599` (E5) | B versus D |
| Missing capability: STOP, substrate first | `CLAUDE.md:764-770` | the consumer's position |
| Nobody optimises Java; no new `Mask` verb; a mask concept on a public signature is a defect | `CLAUDE.md:468-483,549-556` | new public Java methods |
| Measure then pin; disable-runs red-then-green | `CLAUDE.md:782-784`; `abi.md:1738-1769` | the evidence bar |
| Precedent bar: crossings, bytes written, allocation, native latency, a disable table | `abi.md:1861-1877` (§20), `1936-1981` (§21) | what D must bring |

For a fused chain: `abi.md:500-504` fuses a chain of predicates into one
crossing, and abi.md §20 and §21 did the same for grouped sums, counts and sums.
Against: §21 accepted a symbol whose latency gain was 0 to 25 % (`abi.md:1962-1965`),
so the bar is measurement, not magnitude; and the 2026-09-16 ruling points the
Java surface at a `sql()`-shaped call, not graph verbs (`CLAUDE.md:415-556`), so a
chain may be a question for the upstream query surface first (inferred; §6 Q3).

## 3. Options

Native calls for the four shapes at k = 1, 2, 3 (count and id; then hasLabel +
count and id). Everything except A is computed from §1.1, not measured.

| Option | count, id | hasLabel + count, id |
|---|---|---|
| A, today | 7, 10, 13 | 13, 16, 19 |
| B, existing API only | 7, 10, 13 | 13, 16, 19 |
| B2, a new Java chain method | 7, 8, 9 | 13, 14, 15 |
| C, if it fitted: count | 4, 4, 4 | 4, 4, 4 |
| C, if it fitted: id | 7, 7, 7 | 7, 7, 7 |
| D, chain symbol, distinct src and dst, seed kept without a copy (§D, lifetime) | 7, 7, 7 | 13, 13, 13 |
| D, same, seed copied at each derivation | 10, 13, 16 | 16, 19, 22 |
| D, in place (only if `Graph` imported its own seeds) | 5, 5, 5 | 11, 11, 11 |

### A. Change nothing

"Crossings scale with hops" stays the contract, restated as measured: 3 per
hop (create, hop, close), 4 fixed for a count or ids, 10 fixed with one label.
Surface cost: none. Evidence work it would still earn, with no symbol change:
a chain-level crossing pin beside `GraphHopTest.java:148-151` so `3k + 4` is a
repository fact and not only the consumer's, and fixing two stale statements
found while reading (`GraphHopTest.java:526-527` says import is "mask create"
while pinning 2; `bench/RESULTS.md:276-398` as in §1.5). Neither is done here.
**Survives the rules: yes.** It does not meet the outside requirement.

### B. Java side only, no ABI change

- *Reuse a destination.* `lgj_hop` accepts `dst == src` (`abi.md:944-951`;
  `exports.rs:2679-2686`), so k hops could run on one mask. The public API
  cannot say so: `RowStore.hop` always creates its destination
  (`RowStore.java:371`), `Graph.hop` always returns a new owner of a new mask
  (`Graph.java:162`), and every `Mask` method allocates (`Mask.java:111,170`).
  A method taking a destination mask is a new `Mask` verb on a public signature,
  rejected by name (`CLAUDE.md:479-483,549-556`). A method `hop(src, int...
  classids)` that creates one mask and loops `Engine.hop` is B2: create 1 + k
  hops + seed 2 + terminal 1 + two closes = `k + 6`. It saves nothing at k = 1,
  it is not one delegation (E5), it is Java deciding how many parts an answer
  has (E1), and its goal is fewer crossings in Java (`CLAUDE.md:468-472`).
- *Drop the describe in `importRows`.* It returns the address the in-process
  word writes need (`RowStore.java:425-430`); `lgj_mask_create` returns a handle
  only (`abi.md:543`). No Java change removes it.
- *Skip closes.* `Graph.java:70-84` calls retention until the store closes
  sanctioned. A long-lived store serving many queries would accumulate
  `n_rows/8` bytes and a registry slot per mask, and the consumer's rule
  excludes closes from its count anyway.

**Survives the rules: nothing is available through the existing API; B2 does
not survive** (E1, E5, the 2026-09-16 ruling).

### C. A hop inside the existing fused-plan surface

What fits an `LgjOpDesc` (24 bytes, size asserted and cross-checked by Java at
`abi.rs:531-532,653` and `Layouts.java:372`): the edge classid and the
effective facet mask, packed as `operand = (facet_mask << 32) | classid`, the
precedent being the TCAM op-code (`abi.md:1680-1683`); and the decode mode in
`lane_id` (no lane is read) or in `_reserved`, which an old library refuses
today (`exports.rs:1696-1698`), so it would fail loudly. What does not fit:

1. **Resource kind.** `lgj_plan_eval` and `lgj_plan_scalar` resolve a
   `LGJ_RESOURCE_PATTERN` (`exports.rs:898,2369`) and accept lane ids below
   `PATTERN_LANE_COUNT` over three fixture lanes (`exports.rs:1703-1706,
   911-919`). `lgj_hop` needs `LGJ_RESOURCE_ROWSTORE` (`exports.rs:2644`). The
   plan surface cannot name a row store, so even the label filter is outside
   it (it is `lgj_op_eq_classid`, `abi.md:757-766`). C first needs the plan
   evaluator to accept row stores and strided facet lanes: more than an op-code.
2. **The accumulator model.** A plan starts from all rows and folds each op in
   with AND or OR (`abi.md:576-591`). A hop replaces the accumulator with a
   permutation of itself: a third `combine` value (refused today as
   `UNKNOWN_OPCODE`, `exports.rs:1700-1702`, so safe against old libraries)
   that breaks both `plan_lower` rewrites, "ops before the first AND are dead"
   and "an AND is gated under the accumulator" (`plan_lower.rs:9-37,83-122`).
3. **The evaluator is upstream.** `plan_lower` emits a `mask_risc::Program` and
   this crate keeps no second opcode loop (`Cargo.toml:49`). Upstream (the
   `lance-graph` checkout at `98cefe12a`, not this repository),
   `Terminal::ScatterOrU32` is "legal only when it IS the externally demanded
   result ... never an intermediate" (`lance-graph-mask-risc/src/ir.rs:339-348`)
   and the gather-shaped `OutgoingHop` is quarantined, "NOT permission to use
   that mask as an intermediate in Gremlin repeat" (`graph_hop.rs:1-19`). A
   k-hop program makes hops 1..k-1 intermediates. `lgj_hop` is a local kernel,
   not a mask-risc program (`exports.rs:2803-2860`), so this blocks C and not D.
4. **Seeds.** A variable-length row list cannot ride in a 24-byte descriptor,
   and the plan symbols cannot gain a pointer without a new symbol. A mask
   handle in `operand` hides a lifetime in a struct the evaluator copies, which
   `abi.md:1583-1587` declined for a pointer, and `plan_eval` passes `masks:
   &[]` on purpose (`exports.rs:1883-1890`). Predicate-born seeds would fit;
   `V(ids)` would not.

If it fitted, a count would be import 2 + `lgj_plan_scalar` 1 + close 1 = 4 at
any k, with the label as a fused predicate: the only option that removes the
hasLabel +6. Ids would be 2 + create 1 + `plan_eval` 1 + materialize 1 + 2
closes = 7. Those are a bound, not an offer.
**Survives the rules: no, today.** Points 1 to 3 are substrate-tier changes the
STOP rule puts upstream first, and point 4 makes it a new symbol anyway.

### D. A new symbol, `lgj_hop_chain`

Sketch, names provisional:

```
i32 lgj_hop_chain(u64 store, const LgjOpDesc* hops, u32 n_hops,
                  u64 src_mask, u64 dst_mask)        // minor 15
```

Each `hops[i]` reuses `LgjOpDesc` as in §C (hop op-code, decode mode in
`lane_id`, packed `operand`): no new `#[repr(C)]` struct and no manifest field
(a dedicated 16-byte struct would need one, the minor-8 route, `abi.md:200-204`).
`opcode_required_kind` keeps returning `None` for the hop op-code
(`abi.rs:364-371`) so the plan symbols keep refusing it. Semantics: validate
every hop before any write; snapshot `src` under a read lock and release it; run
k hops on reused owned scratch; write `dst` once under one write lock; `dst ==
src` legal as for `lgj_hop`; a decode-mode refusal runs first and leaves `dst`
untouched (`exports.rs:2728-2730`).

**What abi.md §1 demands.** A symbol "argued for, not a default"
(`abi.md:75-78`), after the warden's question whether an op-code can carry it
(`abi-membrane-warden.md:60-64`): C answers that it cannot. §6 holds: k hops of
work ∝ `n_rows` each, no per-element call, string or callback; the §13 bulk
statement (`abi.md:989-996`) applies per hop and the mask-algebra shape stays,
since the chain calls the existing per-hop kernel.
**Does the evidence meet the bar? No.** §20 and §21 were argued with a crossing
count, bytes written, native allocation, native latency and a red-then-green
table (`abi.md:1936-1981`). For D there is a crossing count (§1.3) and an
indicative ratio for the lifecycle share (§1.5). There is no native latency for a chain,
no allocation figure for `lgj_hop` or a chain, and no disable run, because
nothing exists to disable.

*Surface cost.* One symbol (31 to 32), minor 14 to 15; `abi.md` §1, §2, §7 and a
§22 in the six-part shape; one lazy `Minor15` holder (`Downcalls.java:498-535`
pattern) and one `Engine` method behind `Abi.requireMinor(15)`. No new public
Java signature: `Graph` would defer its hops and run them at a terminal, and
against an older library keep today's per-hop path, as `View.count()` does
(`abi.md:1927-1934`). `ApiSurfaceTest` is unaffected.

*Lifetime of a deferred graph.* Today `Graph.hop` writes into a new `Mask`, so
a derived graph stays usable after its source `Graph` closes (`Graph.java:158-163`,
`close()` at `:238-242` releases only its own frontier). A deferred graph reads
its seed at the terminal, so that property does not come for free, and the way
it is kept decides whether D saves anything:

| How the seed stays valid | Calls for seed, k hops, terminal | Cost outside this plan |
|---|---|---|
| Copy the seed at every derivation (`lgj_mask_create` + `lgj_mask_or`, later one `lgj_close`), from a materialized or a deferred source alike | 3k + 7 | none; but it is 3 more than A at every k, so D saves nothing |
| Keep the caller's row list in Java until the terminal, and import then | 7 at any k | only for `from(long...)`; a Java array of ids lives as long as the graph (the Import exception of `CLAUDE.md:129-137` stretched over time), and an out-of-range row is refused at the terminal, not at `from` |
| Share the seed natively between source and derived graphs | 7 at any k | a retain-style lifecycle symbol, since Java may not hold the lifetime logic; needed for `from(Mask)`, which takes ownership of a native mask (`Graph.java:131-140`) |
| A derived graph does not outlive its source | 7 at any k | a change to documented public behaviour |

A recorded chain is plain data and can always be copied; the seed is the only
part with a lifetime. So the two D rows of the options table that opens §3
that give 7 (and 5) calls at any k hold only under the second, third or fourth
row here; the D row marked "seed copied at each derivation" is the first row. Under copying, which is the only
row that needs no further ruling, D costs 3k + 7 against A's 3k + 4. Whichever
row is chosen, a test must close the source first and then run the derived
terminal.

*Tests.* Rust: the chain byte-identical to k single `lgj_hop` calls for k =
1..4 with two distinct edge classids (order must matter), both layouts, and
`dst == src`; a failure at hop j > 0 leaves `dst` untouched; `n_hops == 0`
(§6 Q5); the hop op-code is `UNKNOWN_OPCODE` in `lgj_plan_eval`; a row for
`lgj_hop_chain` beside `provenance.rs:191-207,261-262` (src and dst checked
separately, §3.1); a counting-allocator test modelled on
`tests/plan_scalar_no_alloc.rs` showing allocation does not grow with k beyond
a constant. Java: `OldAbiCompatTest` in both directions against the minor-14
library built from this commit, and a chain crossing pin in `GraphHopTest`.

*Disable table rows,* each red-then-green with the knob shown to bind
(`abi.md:1753-1769`): the decode-mode check moved after the first write;
validation moved into the loop together with writing each hop's result into
`dst` as it completes (so a late refusal leaves a partial write; moving the
validation alone leaves `dst` untouched and the atomicity test would stay
green, which is not evidence); the source
snapshot removed under aliasing; hop order reversed; tail clear after the last
hop removed; either provenance check removed; the Java version gate removed;
the Java fallback answering differently from the chain.

*Risks.*
- Locks, aliasing, provenance: `lgj_hop` holds one mask lock at a time, read
  then write (`abi.md:944-951`); the chain must hold none across the k hops,
  must give the k-hop composition of the pre-call `src` when `dst == src`, and
  must check both masks against the store separately. It reads `src` once, so a
  racing writer is seen as before the whole chain; k separate calls could see
  it between hops: a stronger snapshot, to state in §22.
- Allocation: the chain can drop four `Vec` allocations per extra hop; the one
  plausible non-crossing benefit, unmeasured (§1.5).
- Lazy `Graph`: branching from one intermediate re-runs its prefix unless
  memoised, and a memo is a second owner of a mask; today the prefix runs once
  (`Graph.java:158-163`). Measurement 4 in §4 includes that case.
- Upstream: whether a hop result fed to the next hop inside one native call is
  within the ruling on intermediate populations (`abi.md:1787-1789`) is for the
  owner. `lgj_hop` is not a mask-risc program, so the `ScatterOrU32` wording
  does not apply by its terms (inferred; Q3).
- Reach: D removes `3k - 3` calls from the count and id shapes and nothing from
  the label filter. At k = 1 it equals today.

**Survives the rules: as a design, yes. As an accepted change, not on current
evidence.**

*D-seed.* A native `lgj_mask_import_rows(store, const u64* rows, u64 n, u64*
out)` would remove the describe and the Java per-row loop (2 to 1 calls). The
row list is the Import exception (`CLAUDE.md:129-137`), now native. It saves one
call per query for any k, argues worse than D, and is listed for the record.

## 4. Recommendation

Adopt A now; do not implement B2, C or D. A satisfies every rule, and §1.5
puts the lifecycle calls at roughly 5 % of the smallest hop measured, as an
indicative ratio from separate timings. D is what this repository's doctrine would choose if a fused chain
were wanted, because C does not fit; but its case rests on a crossing count and
§20 and §21 set the bar at measured latency, allocation and a disable run. The
outside rule is not met by D either: D gives 7 calls for every k at best, and
3k + 7 if a derived graph keeps the lifetime it has now by copying its seed (§3.D),
which is worse than A; and one call
per request needs the seed, the filter and the terminal fused, which is a query
verb and belongs to the `sql()` direction (`CLAUDE.md:415-556`).

Evidence missing before anything beyond A, and what would produce it:

1. Native latency of k chained hops against one chain call, both layouts, 1 K to
   1 M rows, k = 1..4, medians of 201, no JVM: a new example beside
   `examples/plan_scalar_cost.rs`, reusing the frontier arms of
   `examples/columnar_hop_bench.rs`. The chain arm needs a prototype; the
   lifecycle arm (k × create + hop + close against k hops on one mask) does not.
2. Rust allocation per call for `lgj_hop` today and for a chain, by a
   counting-allocator test like `tests/plan_scalar_no_alloc.rs`. The `lgj_hop`
   half needs no prototype.
3. End to end through the JVM at several store sizes: bench component G's
   `native_hop` arm (`bench/RESULTS.md:266-283`) plus a chain arm, via
   `bench/run.sh`, so FFM and facade costs are inside the number.
4. The branching `Graph` case of §3.D: one prefix, two continuations, calls and
   wall time, lazy against eager.
5. The consumer's workload: store sizes, k, request rate. A hypothesis to
   measure, not a finding: in stores of a few hundred rows the hop itself is
   cheaper, so the lifecycle calls may be a larger share than the roughly 5 %
   seen at 2,000 rows. No store below 2,000 rows was timed. If measurement
   bears it out, crossings plus the allocation figure may meet the bar; at the
   sizes timed here nothing shows that they do.

D proceeds only if 1 to 3 show a benefit for the workload of item 5: lower
latency outside run-to-run noise at the consumer's store sizes and k, with
allocation per call no higher than k single hops. It then goes ahead as its own
change with the §22 and disable table above and the board updates a symbol
change requires. A difference in the other direction (a chain that is slower
or allocates more), or no difference outside noise, closes D as "do not add",
with §1.3 as the record.

## 5. Non-goals

- No change to the mask-native invariant: no row-id frontier, no
  mask → ids → mask round trip; ids only through `materialize*`
  (`CLAUDE.md:103-137`).
- No per-element or per-edge call, no upcall, no string.
- No Java-side compute path and no new public `Mask` or hop verb
  (`CLAUDE.md:479-483`).
- No schema, no write path, no storage backend, no change to `docs/abi.md` or
  `LgjOpDesc`.
- No claim about speed: the §1.3 numbers locate the question, they do not answer it.

## 6. Open questions for the owner

1. Is "one native call per request, closes excluded" a requirement on this
   repository or on the consumer's use of it? If the latter, A closes this.
2. Which store sizes, hop depths and request rates does the consumer expect?
3. Is a graph-chain capability built in this ABI, or upstream as a query verb
   behind the `sql()` surface? Does the ruling on intermediate populations
   cover a local hop kernel that feeds its output to its next hop?
4. Is a `Graph` that defers its hops acceptable (eager and immutable today,
   `Graph.java:91-103`), given the branching cost?
5. For `lgj_hop_chain`, `n_hops == 0`: copy of `src`, or `EMPTY_PLAN` as in
   `lgj_plan_eval` (`exports.rs:1689-1691`)?
6. Are the chain-level pin and the two stale statements of §3.A to be fixed
   whatever Q1 answers?
7. How does a deferred graph keep its seed valid after its source closes
   (§3.D, lifetime)? Copying costs more calls than A. The three ways that keep
   the saving each need a ruling: a row list held in Java until the terminal, a
   retain-style lifecycle symbol, or a derived graph that may not outlive its
   source.

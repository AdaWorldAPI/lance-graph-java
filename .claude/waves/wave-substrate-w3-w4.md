# Wave: substrate W3 (Java RowStore facade) + W4 (bench Component F)

> Executes `lgj-soa-substrate-v1.md` W3 then W4. Two dispatches, two PRs.
> Inherits every standing rule in `waves/README.md`.

---

## Dispatch 1 — W3: the Java `RowStore` facade

**Preconditions (verify before spawning):** PR #5 merged (ABI minor 2 on
`main`); `native/lgj-abi` release build present or rebuildable
(orchestrator runs `cargo build --release` centrally); JDK 26 at
`/opt/jdks/jdk-26.0.2`.

**Contract inputs every worker reads first:** `docs/abi.md` §5, §7, §11;
`.claude/knowledge/soa-row-store-layout.md`;
`.claude/knowledge/abi-ownership-and-handles.md`; the existing
`java/.../internal/ffm/*.java` and `NativePattern.java` for house style.

### Worker roster (3 Sonnet workers, disjoint scopes)

**J1 — FFM membrane extension.**
YOUR SCOPE (only): `java/src/main/java/com/adaworldapi/lancegraph/internal/ffm/Downcalls.java`,
`Layouts.java`, `Abi.java`.
Deliverables:
1. `Downcalls`: three new `static final MethodHandle`s —
   `lgj_rowstore_open (u64,u64,ptr)->i32`,
   `lgj_op_eq_classid (u64,u32,u32,u64)->i32`,
   `lgj_row_facet_match (u64,u32,ptr,u64)->i32` — `FunctionDescriptor`s
   matched to `docs/abi.md` §11 argument-for-argument, same style as the
   existing 15.
2. `Layouts`: `ROW_LAYOUT = sequenceLayout(32, structLayout(JAVA_INT
   "classid", sequenceLayout(12, JAVA_BYTE) "payload"))` (must byteSize()
   to 512 — add that as a static assert in the class init, matching the
   existing manifest-cross-check spirit); `FACET_MATCH_ELEM = JAVA_INT`.
3. `Abi`: a `requireMinor(int)` guard — base load keeps requiring
   `minor >= 1`; row-store entry points call `requireMinor(2)` and throw
   a clear `AbiVersionException`-style error BEFORE any downcall when the
   loaded library reports an older minor.
OTHER AGENTS own: all public-package files (J2), all tests (J3).

**J2 — public facade.**
YOUR SCOPE (only): NEW files
`java/src/main/java/com/adaworldapi/lancegraph/RowStore.java`,
`FacetMatchView.java`, `FacetId.java`.
Deliverables:
1. `RowStore` (AutoCloseable): `open(Arena, long nRows, long seed)`;
   `long rowCount()`; `Mask maskOfFacetClass(FacetId facet, int classId)`
   (creates a mask via the EXISTING mask machinery with the row store as
   parent, runs `lgj_op_eq_classid`, returns the existing public `Mask`
   type — reuse, do not mint a parallel mask); `FacetMatchView
   facetMatches(int classId)` (allocates an `nRows`-int segment in the
   store's arena, one `lgj_row_facet_match` crossing, wraps it).
2. `FacetMatchView`: `int rowCount()`, `int matchesOf(long row)` (segment
   read, no crossing), `long cardinality()` (Java-side popcount loop over
   the segment — document that it is Java-side on purpose).
3. `FacetId`: a `record FacetId(int index)` with range check 0..31 —
   Valhalla-shaped descriptor (≤8 B payload), one-word `value record`
   migration later.
HARD RULE: zero `java.lang.foreign` types in any public signature
(`ApiSurfaceTest` enforces by reflection — it will fail your work, not a
human). Javadoc every public member, citing `docs/abi.md` §11 where the
semantics come from.
OTHER AGENTS own: `internal/ffm/*` (J1), tests (J3).

**J3 — tests.**
YOUR SCOPE (only): NEW files
`java/src/test/java/com/adaworldapi/lancegraph/RowStoreParityTest.java`,
`RowStoreLifetimeTest.java`.
Deliverables:
1. `RowStoreParityTest`: transcribe the §11 generator (SplitMix64 already
   transcribed in `FixtureParityTest` — reuse that class's generator
   verbatim, two draws per facet, `a` then `b`); for n ∈ {1, 64, 65,
   1000}: (a) recompute every facet-7 classid in pure Java and compare
   `maskOfFacetClass(7, c).count()` against the recomputed count for
   c ∈ {0, 9, 15}; (b) `facetMatches(9)`: per-row expected 32-bit set
   recomputed in pure Java, compared via `matchesOf(row)` for every row;
   (c) read classids DIRECTLY from the raw lane 0 segment through
   `ROW_LAYOUT`-derived offsets and compare against the generator — two
   independent read paths (native mask vs Java segment read) against one
   independent recomputation.
2. `RowStoreLifetimeTest`: use-after-close throws; a `Mask` from a closed
   store fails with the parent-closed error; `FacetMatchView` remains
   readable after the store closes ONLY if its backing arena is the
   caller's (assert whichever the design gives — and state it in the
   Javadoc via a STOP-report if J2's choice is ambiguous); double close
   is idempotent-or-throws per existing `LifetimeTest` conventions
   (match them, do not invent new ones).
Tests are plain-main style matching `AllTests` conventions — read
`FixtureParityTest` first and mirror its structure.
OTHER AGENTS own: `internal/ffm/*` (J1), public facade (J2).

### Orchestrator-only steps (in order)

1. `cargo build --release` in `native/lgj-abi` (fresh `.so`, 18 symbols).
2. Dispatch J1+J2+J3 in parallel (disjoint — safe).
3. After all land: wire `AllTests.java` (orchestrator-owned) to include
   the two new test classes.
4. Compile: `javac -Xlint:all` over `java/` — expected `[restricted]`
   warnings: the existing 7 + any new ones ONLY in `internal/ffm/*`;
   count and record the exact number.
5. Run `AllTests` against the fresh `.so`. All green required.
6. **Disable-run 1 (version gate):** change `requireMinor(2)` to
   `requireMinor(3)` → every RowStore test must fail with the version
   error, nothing else may fail; restore; re-run green.
7. **Disable-run 2 (parity is real):** flip one byte-order or offset in
   J3's pure-Java generator transcription (e.g. swap the a/b draw order)
   → parity tests must go red; restore; green.
8. `ApiSurfaceTest` must pass unmodified (it auto-walks the package — a
   new public type leaking FFM fails without anyone editing the test).
9. Board: STATUS_BOARD D-LGJ-W3 → DONE with the real numbers;
   LATEST_STATE prepend; commit, push, PR, merge per house rhythm;
   PR_ARC_INVENTORY entry at merge.

**STOP conditions for the whole dispatch:** any needed ABI behavior
missing from §11 (goes back to the plan as a W6 item, never patched
ad hoc); `Mask` reuse turning out to require a new public constructor
(design decision → orchestrator resolves, workers stop).

---

## Dispatch 2 — W4: bench Component F (after W3 merges)

**Question (verbatim from the plan):** on the REAL layout, where does the
per-row facet scan belong — native crossing, Java Vector API, or scalar?

### Worker roster (1 Sonnet worker)

**B1 — Component F.**
YOUR SCOPE (only): NEW file
`bench/src/main/java/com/adaworldapi/lancegraph/bench/F_RowStoreFacetScan.java`;
plus `Data.java` and `Kernels.java` MAY be extended (they are yours for
this dispatch — no other worker runs concurrently).
Deliverables, mirroring the house bench discipline
(`bench/README.md` rules — cross-check before timing, cost components
separated):
1. Arms over one `RowStore` (n_rows param: 4_096, 65_536, 1_048_576;
   classId fixed at 9):
   - `native_facetMatch` — one `lgj_row_facet_match` crossing into a
     pre-allocated out segment (allocation OUTSIDE the timed body,
     documented).
   - `java_vectorApi` — `IntVector.fromMemorySegment` over the raw
     lane-0 segment, species 512-bit: one 16-int vector = one 64-byte
     chunk = 4 facets; `eq(broadcast(classId)).toLong() & 0x1111`-fold,
     8 chunks per row — the SAME algorithm as the Rust kernel, stated in
     a comment (algorithmic symmetry is the point of the comparison).
   - `java_scalar` — VarHandle walk of the 32 classids per row.
2. `@Setup` cross-check: all three arms' full output arrays bit-identical
   before anything is timed (the `Data.crossCheck` discipline; throw on
   mismatch).
3. JMH params identical to existing components (`@Fork(1)`, 5×500ms
   warmup, 8×500ms measurement, AverageTime, GC between iterations).

### Orchestrator-only steps

1. Run `bench/run.sh F_` centrally (expect ~10 min).
2. `summarise.sh` regenerates tables from the merged CSV — RESULTS.md
   prose updated to cite the new table, never hand-typed numbers.
3. Fold the finding into `docs/execution-boundary.md` (one paragraph:
   does the Component C conclusion survive on the strided/row layout?)
   — whichever way it lands, it lands as measurement.
4. Board, PR, merge, arc entry per house rhythm.

**Falsifier:** the cross-check itself (a wrong arm cannot be timed); and
the summarise.sh mechanical-table rule (prose cannot drift from CSV).

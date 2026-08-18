# lgj-soa-substrate-v1 — Slice 2: the lance-graph-shaped SoA substrate

> **Status: ACTIVE** (2026-08-17). Successor to `lgj-vertical-slice-v1.md`,
> which is COMPLETE (PRs #1–#4 merged). Operator directives that reframed
> this slice, in order:
> 1. *"the 64k x 512 bytes SoA layout is enforced everywhere in lance-graph
>    (32 Lanes each 4 bytes classview+12 bytes). For Java the layout might
>    differ — just for reference."*
> 2. *"Java should optimize the SoA layout — we won't dismiss the initial
>    plans just because it doesn't apply for unorganized non-SoA; that's
>    the whole point about project Panama."*
> 3. *"Abandon any use of serialization in favor of lance-graph 64k
>    concurrency zero copy and ndarray SIMD polyfill — the low code low
>    migration cost experience."*
> 4. *"Java Panama and Valhalla become the supraconductor over lance-graph
>    ABI shaped SoA substrate."*
>
> Blast radius (operator-confirmed, three posters): this is not a faster
> binding — it is the deletion of the Java data stack's middle tier (the
> ORM/DTO layer, graph middleware, serialization frameworks, side-car
> analytics) in favor of ONE ABI boundary over ONE substrate, with the JVM
> kept as the familiar low-migration-cost surface. The formula:
> `ClassView → WideFieldMask → Meta Gate (64K) → SIMD Sweep → SoA Lanes →
> Survivors → Seal & Persist (Lance)`.

## The design waves

| wave | deliverable | status |
|---|---|---|
| **W1** | ndarray: `MultiLaneColumn` u32 lane (`iter_u32x16`) + `eq_u32_strided_to_mask` (W1a contract: parity tests, re-exports, both x86 arms) | **DONE** — ndarray PR #279; 46+15 tests, clippy/fmt clean |
| **W2** | lgj-abi row store: `LGJ_RESOURCE_ROWSTORE`, `lgj_rowstore_open`, facet lanes via the EXISTING `LgjLaneDesc` (`stride=512`), `lgj_op_eq_classid` (row masks composing with the existing algebra), `lgj_row_facet_match` (per-row facet bitsets into a caller buffer via `MultiLaneColumn`), ABI minor 1→2, `docs/abi.md` §11 | **DONE** — 84/84 incl. end-to-end membrane test, 18/18 symbols via `nm -D` |
| **W3** | Java `RowStore` facade: no FFM in public signatures; structured `MemoryLayout` (`sequence(32, struct(u32 classid, 12B payload))`); minor-≥2 gate; `FacetMatchView` zero-copy accessor over a Java-arena segment; `RowStoreParityTest` transcribing the generator | OPEN — next |
| **W4** | Bench Component F: Java Vector API per-row facet scan (one `IntVector` 16-lane chunk = 4 facets, same algorithm as the Rust kernel) vs `lgj_row_facet_match` crossing vs scalar VarHandle walk — the "where does execution belong" question re-asked on the REAL layout | OPEN |
| **W5** | The three consumer examples (own plan files, below) | PLANNED |
| **W6** *(named, not scheduled)* | ClassView wiring — and with it an explicit **schema/classid field** on `LgjResourceInfo`/`LgjLaneDesc` (additive, one minor bump), so a resource names WHICH layout contract its bytes obey instead of implying it via `kind`. Provenance + rationale: `.claude/knowledge/prior-art-and-the-layout-bridge-claim.md` §3 ("one key, many projections" made literal at the membrane). **The classid that field carries is a C0 concept** — the Java/Panama/Valhalla layer naming itself from inside its own stratum, not a substrate concept borrowed downward; see `.claude/board/EPIPHANIES.md` `E-LGJ-THE-DOMAIN-BYTE-CARRIES-ALTITUDE-1` (the reservation is OGAR-side and operator-gated, and W6 is not blocked on it). Also the `align(64)` base guarantee (real `NodeRow`) and, only if measurement asks, fused plans over facet lanes | NAMED |

Wave rule (house style): one wave = one reviewable PR; gates run centrally
(orchestrator only — agents never run cargo); every safety property lands
disable-verified, every measured claim lands with its reproduction command.

**Cross-repo dependency note (2026-08-17, operator-flagged):** lance-graph
**#957** (merged) minted `crates/lance-graph-hydrate` — the generic
SoA→S3→volume→Lance hydration pattern (four-state lifecycle,
hydrate-aside/publish-by-rename, warm markers, dirty detection), minted in
lance-graph *specifically so consumers inherit it as a path/git dependency
rather than re-implement*. **#958** (merged 2026-08-17, another session's
PR) was its 5+3
council hardening fast-follow. Consequence here: when this substrate's
persistence slice arrives (the "Seal & Persist (Lance)" column of the
formula, and `ogar-machine-v1.md`'s time-machine storage), the hydration
path is `lance-graph-hydrate` — inherited, never re-derived. Do not design
a hydration mechanism in this repo.

## What W2 locked (so W3+ doesn't re-derive it)

- **Layout truth:** `ROW_BYTES=512`, `ROW_FACETS=32`, `FACET_BYTES=16`,
  classid = leading LE u32. Generator: 2 SplitMix64 draws per facet
  (`a`→classid via `(a>>>33)&0xF`, `b`+low-`a` → payload), 64 draws/row.
- **Lane map:** lane 0 = raw U8 contiguous; lane `1+f` = facet `f` classid,
  U32, stride 512. `byte_len` = exact covered span
  `(len-1)*stride + elem_bytes` — never rounds up past the allocation.
- **Masks parent onto row stores** exactly as onto patterns; the whole
  existing mask algebra applies unchanged (proven in
  `the_rowstore_slice_end_to_end_through_the_membrane`).
- **Carrier:** `Arc<[u8]>`, base u8-aligned (honest limit — the align(64)
  guarantee arrives with real `NodeRow` wiring); `n*512 % 64 == 0` by
  construction is what makes `MultiLaneColumn::new` infallible here.

## The three consumer examples (W5) — one plan file each

Each is one poster made runnable, on the SAME substrate, each exercising a
different face of it:

| plan | poster | face of the substrate |
|---|---|---|
| `consumer-world-trades-v1.md` | "One Billion Objects in Java" | the fluent domain API: `World.open(...)` → schema-named fields → `.where().count()`, zero objects |
| `consumer-bricks-analytics-v1.md` | "OGAR-Bricks done right" | mask-first security: RBAC clamp BEFORE execution, survivors-only, aggregates leave |
| `consumer-graph-traversal-v1.md` | "Java Graph Stack (richtig gemacht)" | facet edges as addresses: traversal = facet-match + mask hops, no middleware |

Sequencing: any order after W3; each is independently shippable; none
blocks the others. All three consume ONLY the public Java facade — a
consumer plan that needs a new ABI symbol goes back through this plan's
wave process instead of growing the membrane ad hoc.

## Falsification obligations carried forward

- W3 parity: Java recomputes classids from the transcribed generator AND
  reads them back through the raw-lane segment — two independent paths to
  the same numbers.
- W3 disable-run: break the minor-version gate (require ≥ 3) and prove
  load fails; restore.
- W4 cross-check before timing: all three arms must agree on every
  facet-match bitset before any timing is reported (the `Data.crossCheck`
  discipline).
- Every consumer example ends with an assertion computed independently of
  the substrate (transcribed-generator arithmetic), never a golden blob.

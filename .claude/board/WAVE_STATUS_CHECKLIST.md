# WAVE STATUS CHECKLIST — what is done, what is superseded, what is blocked

> **Why this file exists.** Across session resets the assistant repeatedly
> stated that "one lance-graph-java wave is ~90% done" and attributed the
> remainder to `wave-ghidra-g1-g2`. **That attribution is wrong.** The
> operator corrected it 2026-08-27; this file is the durable answer so the
> conflation stops recurring.
>
> Verified 2026-08-27 against three independent reads — the seven
> `.claude/waves/*.md` files, the four board files, and the git tree +
> source census (which trusts no doc). All three agree.
>
> Append-only, per `.claude/board/README.md`. Statuses below are copied
> from `STATUS_BOARD.md` / `LATEST_STATE.md`, not re-derived.

---

## The correction, in one line

**The LGJ wave IS complete. `wave-ghidra-g1-g2` is NOT the remaining 10% —
it is SUPERSEDED and must never be dispatched.** The genuine ~90% item is
**D-LGJ-W8** (mask-native navigation correction): 7 of its 9 gate rungs are
done, **FALSIFIERS** and **POLICY** remain.

---

## 1. Substrate + facade — COMPLETE

| ✅ | D-id | What | Status |
|---|---|---|---|
| ✅ | D-LGJ-A … D-LGJ-AUDIT (12 rows) | Core vertical slice: ABI contract, native crate, Java facade, Valhalla lab, JMH bench, falsification review, docs | DONE 2026-08-17 (PR #1–#4) |
| ✅ | D-LGJ-W1 | ndarray `iter_u32x16` / `eq_u32_strided_to_mask` (W1a contract) | DONE 2026-08-17 |
| ✅ | D-LGJ-W2 | lgj-abi row store, strided facet lanes, ABI minor 1→2 | DONE 2026-08-17 (PR #5) |
| ✅ | D-LGJ-W3 | Java `RowStore` facade | DONE 2026-08-17 (PR #8) |
| ✅ | D-LGJ-W4 | Bench Component F on the real layout | DONE 2026-08-17 (PR #9) |
| ✅ | D-LGJ-W5 | Three consumer examples | trades + bricks DONE 2026-08-17 (PR #11, #12); graph DONE 2026-08-18 (PR #18) |
| ✅ | D-LGJ-W6 | Edge-bearing row store, ABI minor 2→3 | DONE 2026-08-18 (PR #14) |
| ✅ | D-LGJ-W7 | Public per-row payload accessors | DONE 2026-08-18 (PR #16) |
| ✅ | D-LGJ-SWEEP-5 | `lgj_reduce_facet_sum`, ABI minor 5 | DONE 2026-08-25 |
| ✅ | D-LGJ-SWEEP-6 | `lgj_reduce_facet_sum_resolved`, ABI minor 6 | DONE 2026-08-25 |
| ✅ | D-LGJ-SWEEP-7 | `lgj_row_layout_probe`, ABI minor 7 | DONE 2026-08-25 (PR #30) |
| ✅ | D-LGJ-SWEEP-8 | Register groupings served as DATA, ABI minor 8 | DONE 2026-08-25 (PR #32) |

Code census confirms it: `native/lgj-abi/src/` = 8 files / 7 444 lines /
**134 Rust `#[test]`**; `java/src/main/java/` = 33 files / 4 317 lines, 14
suites under the hand-rolled `Checks`/`AllTests` harness (this repo uses no
JUnit); plus `consumers/{trades,bricks,graph}` each with their own suite.

## 2. The genuine ~90% item — D-LGJ-W8

Gate ladder, from `STATUS_BOARD.md` Table 2:

| ✅ | AUDIT |
| ✅ | SPEC |
| ✅ | COUNCIL (v3 ratified) |
| ✅ | FREEZE (PR #20, squash `c479f76`) |
| ✅ | SUBSTRATE (ndarray PR-N + lgj-abi PR-W8a, ABI minor 4) — 2026-08-18 |
| ✅ | FACADE (PR-W8b) — 2026-08-18 |
| ✅ | GRAPH MIGRATION (PR-W8b) — 2026-08-18 |
| ⬜ | **FALSIFIERS** — spec §12 `F-*` pre-registrations beyond what `GraphHopTest` already carries |
| ⬜ | **POLICY** |

**7 of 9 rungs = the "~90% done" wave.** It is the mask-native wave, and it
lives entirely inside this repo — no upstream dependency, no Ghidra
dependency. Nothing blocks finishing it but doing it.

## 3. Ghidra waves — SUPERSEDED, do not dispatch

| ⊘ | `wave-ghidra-g1-g2.md` | **SUPERSEDED 2026-08-18** — "do not dispatch under any circumstance." Superseded by `AdaWorldAPI/ruff` **PR #94** (`crates/ruff_r2il` — ore/furnace/slag typed intake reading r2sleigh's R2IL/SSA), which shipped the capability G1/G2 were going to build. The file is retained for its archaeology only; G0's 74-opcode / `PcodeEmulator` findings still stand as reference. |

**This is the correction.** `wave-ghidra-g1-g2` is not pending work, not
"the last 10 %", and not a gate on anything. It was *replaced*, upstream,
by a merged PR in a different repo.

Two `G1`/`G2` name collisions that fed the confusion, both resolved:

- **D-LGJ-W5 graph consumer** has workers named G1/G2 (traversal facade /
  falsifier tests) — **both DONE**, PR #18, 43/43 disable-verified.
- **D-LGJ-W8 spec** has *gates* named G1/G2/G6/G9/G11 (e.g. G11 =
  contract-import fence, G2 = call-sites-only check) — these are gate
  names, not deliverables.

Neither is `wave-ghidra-g1-g2`.

## 4. Blocked / calcified

| ⛔ | `wave-ogar-machine-pm1.md` (probe P-M1) | **BLOCKED — do not dispatch.** Four gates, all required: (1) W3 merged ✅; (2) ≥1 W5 consumer example shipped ✅; (3) `ruff_r2il` **PR2 AND PR3 merged** ⬜ *(repointed 2026-08-18 from the old "Ghidra G1+G2 merged" wording — that gate could never be satisfied once G1/G2 were superseded)*; (4) explicit operator go ⬜. |
| 🧊 | `wave-consumer-trades.md`, `wave-consumer-bricks.md` | **DO NOT DISPATCH** — operator ruling 2026-08-17, "calcified, not executed, until called." Note the *deliverables* (D-LGJ-W5 trades/bricks) shipped anyway via PR #11/#12; the wave files are the un-dispatched expansion. |
| 🧊 | `wave-consumer-graph.md` | DONE 2026-08-18, ⊘ superseded in part by the D-LGJ-W8 correction. |

**The real critical path to Ghidra/R2IL consumption is upstream, not here.**

> **⊘ CORRECTION 2026-08-27 — this paragraph was wrong twice, and the file
> that exists to stop stale cross-repo claims had become one.** It read:
> *"`ruff_r2il` PR2 (drill-down proposer, gated on PR1 corpus numbers) then
> PR3 (classid mint in `ogar_codebook`, item O5, gated on PR2). Both unmerged
> as of 2026-08-25."* Measured against the repo:
>
> 1. **PR1 / O1 is DONE and committed** — `ruff` `.claude/harvest/r2il/CORPUS-PROFILE-RESULT.md`:
>    100.00 % inline fit (`dst+src0+src1`), 0.00 % needing Vec routing, on all
>    four corpus binaries (`stress_test`, `stress_test_opt`, `/bin/ls`,
>    `/usr/bin/env`).
> 2. **PR2 is DONE and measured** — `ORACLE-RESULT.md`: the round-trip
>    reconstruction oracle, **zero mismatches over 35,946 matched op sites**
>    (`:120`).
> 3. **PR2 was never a "drill-down proposer".** That phrase appears nowhere in
>    the r2il plans; the PR2 gate deliverable is the round-trip oracle
>    (`.claude/plans/r2il-roundtrip-oracle-spec-v1.md:1`). The nearest real
>    "drill proposer" is `ruff_python_spo`'s, a different crate and a
>    different arm.
>
> Append-only: the wrong text is quoted above rather than deleted, because the
> failure mode — a checklist confidently asserting another repo's state — is
> the exact one this file was created to prevent.

**Actual open item:** **O6** — the attribute-gap / schema-widening decision the
oracle run opened (`ORACLE-RESULT.md`: `MemorySpace` dominates the gap;
unresolved is whether to widen the schema for it, and the shipped
`minimal_pass_one` convention's own coverage). O6 scopes **O5/PR3**, the
`ogar_codebook` classid mint that lance-graph owns. Until PR3 lands, the facet
is an opaque key and must not be persisted as a durable address
(`STAGED-CODEGEN-GUIDE.md:34`, placeholder `PROVISIONAL_R2IL_VARNODE = 0x0000`).

## 5. What this repo does NOT yet contain — measured, not assumed

Verified by symbol census over `native/`, `java/`, `docs/` (2026-08-27):

- ⬜ **No `r2il` / `r2conc` / `r2sleigh` / `sleigh` binding of any kind.**
  `native/lgj-abi/Cargo.toml` `[dependencies]` is exactly two local path
  deps — `ndarray` and `lance-graph-contract`. Nothing else.
- ⬜ **No Ghidra symbol in shipping code.** Every apparent hit across Rust,
  Java and docs is the substring `opcode`, referring to this crate's own
  `LgjOpCode` ABI vocabulary — never Ghidra's.
- ✅ The **only** real P-code content is `valhalla-lab/reproducers/R12_GhidraPcodeVocabularyVsCliff.java`
  + `R12-observed.txt` — a standalone flattening reproducer that transcribes
  Ghidra's `Varnode`/`PcodeOp` field shapes as `value record`s. It is
  **measurement, wired into nothing** (R12 verdict: P-code payloads do not
  flatten at 16 B/12 B — 0/2; ordinals do at VM element size 8 — 3/3;
  unanticipated: 8-byte `VarnodeNarrow` also flattens).

So "consume the zero-copy modded forks of r2conc / R2IL from lance-graph-java"
is **not started in code**. The seam is *verified* (2026-08-25:
`Language.parse` → `InstructionPrototype` → `getPcode()` confirmed as
interfaces, no Ghidra core fork required) and the vocabulary is *measured*
(R12) — but no binding exists.

---

## The checklist, condensed

- [x] Core vertical slice (D-LGJ-A…AUDIT) — DONE
- [x] SoA substrate W1–W7 — DONE
- [x] Mask-native sweep, ABI minors 5–8 — DONE
- [x] **D-LGJ-W8 FALSIFIERS** — F-HYDR was already shipped as `GraphHopTest`'s
      G3 gate (384-byte floor, flat 10-vs-500 rows); F-PARITY's harness
      (bench Component G) landed 2026-08-27 **and was measured**. The other
      six `F-*` are pre-registered for the COMPUTE wave, not W8 — §12's own
      "W8 carries only those marked [W8]" marks exactly two.
- [x] **D-LGJ-W8 POLICY** — discharged at the **A3 freeze (PR #20)**, not last:
      §3.9 says the artifacts land "BEFORE workers", and all of them exist
      dated 2026-08-18 (root `CLAUDE.md` incl. §13's compute additions,
      `EPIPHANIES.md:683` storno, the `STATUS_BOARD` row, `LATEST_STATE`,
      `PR_ARC_INVENTORY`). The ladder's `… → FALSIFIERS → POLICY` ordering is
      misleading and the board carried it as "remaining" for nine days.
- [x] ~~wave-ghidra-g1-g2~~ — **SUPERSEDED, never dispatch** (ruff PR #94)
- [x] `ruff_r2il` PR1/O1 (corpus profile) — DONE, measured
- [x] `ruff_r2il` PR2 (round-trip oracle) — DONE, zero mismatches / 35,946 sites
- [ ] `ruff_r2il` **O6** (MemorySpace schema-widening decision) → **O5/PR3** (`ogar_codebook` mint)
- [ ] `wave-ogar-machine-pm1` — blocked behind PR2+PR3 + operator go
- [ ] Any r2il/r2conc binding in this repo — **not started**

## Open issues (unchanged by this file)

| id | state |
|---|---|
| `ISS-LGJ-FACETSCHEMA-PAIR48` | OPEN, upstream-owned |
| `ISS-LGJ-CLASSID-WIDTH-PIN` | OPEN, regraded — only the u16 shape-space ceiling remains |
| `ISS-LGJ-TARGET-DIR-SIZE-WATCH` | OPEN, watch-only |
| `ISS-LGJ-STACK-TAIL-STRANDED-MINOR-8` | RESOLVED 2026-08-25 |
| `ISS-LGJ-FANOUT-UNREVIEWED` | resolved for core; lab rows later DONE |
| `ISS-LGJ-DEV-BRANCH-STILL-UNCOMMITTED` | RESOLVED 2026-08-17 |

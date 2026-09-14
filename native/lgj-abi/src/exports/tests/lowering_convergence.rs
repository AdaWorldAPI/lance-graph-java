//! The differential between this crate's own plan lowering and
//! `lance-graph-quack`'s — two lowerings of the SAME LAW, compared directly
//! for the first time.
//!
//! [`crate::plan_lower`] lowers a FLAT OP LIST (`&[LgjOpDesc]`, a left fold
//! seeded with all-ones and folded with `&=`/`|=`) to a `mask_risc::Program`.
//! `lance_graph_quack::lower` lowers a Boolean [`Filter`] TREE to the same
//! `Program` IR. Read side by side, both implement one algebra: the
//! accumulator starts all-ones; an AND narrows it and gates every later
//! comparison under whatever survives so far; an OR widens it and must NOT
//! be gated, because `acc | p` depends on `p` exactly where `acc` is zero —
//! gating there would discard precisely the bits an OR exists to admit; and
//! any op sitting before the plan's first AND is dead, since
//! `all_ones | p == all_ones` regardless of what `p` selects. `plan_lower`
//! drops the dead prefix by scanning for the least AND index; the tree
//! reading below drops it for free, because [`fold_to_tree`]'s fold never
//! establishes an accumulator until the first AND arrives — an `Or`
//! encountered with no accumulator yet is not merely un-selective, it is
//! genuinely absent from the tree.
//!
//! Each side already has its OWN oracle, and neither can see the other
//! drift: `plan_lower`'s is `pr4_equivalence.rs`'s frozen pre-PR4 loop, a
//! per-op accumulate-and-combine that never builds a `Program` at all;
//! `lance-graph-quack`'s is its own per-row reading, in its own crate, that
//! has never heard of `plan_lower`. A defect the two lowerings happen to
//! SHARE — the same wrong reading of the AND/OR asymmetry, say — would pass
//! both of those oracles and would ALSO pass this file, since "the two
//! lowerings agree with each other" is a different claim from "either one
//! agrees with its own oracle". What this file adds is orthogonal to both:
//! it is the only place that would notice the two independently-written
//! lowerings have started to DISAGREE with each other, which a same-crate
//! refactor in either one could otherwise introduce with nothing here to
//! catch it.
//!
//! This file is therefore a DIFFERENTIAL between the two lowerings, and
//! explicitly NOT a delegation from one to the other — the two sections
//! below say what that means and what it costs.
//!
//! # What this does NOT prove
//!
//! This is a differential over ANSWERS — row counts a `Program` selects —
//! never over the emitted op lists themselves, so two lowerings that reach
//! the same count via structurally different `Program`s (different slot
//! counts, different op ordering) are indistinguishable here, and that is
//! fine: nothing in this file claims otherwise. It also cannot, by
//! construction, catch a defect both lowerings share (see above). Neither
//! gap is a hole in THIS file specifically — `pr4_equivalence.rs`'s frozen
//! loop and `lance-graph-quack`'s own per-row oracle each independently pin
//! their own side's correctness against ground truth, and the three arms
//! (plan_lower-vs-frozen-loop, quack-vs-its-own-oracle, and
//! plan_lower-vs-quack here) are complementary, not redundant.
//!
//! # A differential, deliberately NOT a delegation
//!
//! This crate could, in principle, retire [`crate::plan_lower`] entirely and
//! have `plan_eval_impl` build [`fold_to_tree`] and hand it to
//! `lance_graph_quack::lower`. It does not, and `lance-graph-quack` is wired
//! as a DEV-dependency (see this crate's `Cargo.toml`, whose own comment
//! says so) specifically to make that impossible by construction: the
//! membrane (`lgj-abi`, which ships as the `cdylib` Java's `Linker` loads)
//! must not depend on a CONSUMER of the IR it serves — that dependency
//! points the wrong way. Sharing the LAW between two independent
//! implementations is the whole point of this file; sharing a runtime
//! dependency in that direction would invert the layering the membrane
//! exists to keep. What ships from this crate is the differential above,
//! never a delegation to the crate it is differential against.

use super::*;

use lance_graph_mask_risc::Program;
use lance_graph_quack::{Agg, Cmp, Col, Filter, Query};

/// `n = 1000, seed = 33` — the identical fixture
/// `pr4_matrix.rs::combine_mode_products_agree_with_the_oracle` sweeps, so
/// its recorded per-vector counts (in that file's own module doc) are a
/// usable cross-reference. SplitMix64 is deterministic: the same `(n, seed)`
/// always regenerates the identical lanes.
const SWEEP_N: u64 = 1000;
const SWEEP_SEED: u64 = 33;

/// `&[LgjOpDesc]` (a left fold seeded with all-ones) read as the Boolean
/// TREE it denotes. `None` means the fold is still sitting on the all-ones
/// seed — no op has combined with AND yet.
///
/// This is where the prefix rewrite `plan_lower`'s own module doc describes
/// falls out for free, as a PROPERTY of the tree construction rather than
/// as a special case anyone had to remember to write: an `Or` encountered
/// while `acc` is still `None` returns `(None, false)` below, so the op
/// simply never becomes a node — `all_ones | p == all_ones`, so a leaf that
/// contributes nothing is a leaf that is never built, not a leaf that is
/// built and then discarded. `plan_lower` reaches the identical answer by
/// scanning for the least AND index up front; this fold reaches it by
/// construction, one op at a time.
fn fold_to_tree(ops: &[LgjOpDesc]) -> Option<Filter> {
    let mut acc: Option<Filter> = None;
    for o in ops {
        let leaf = Filter::Cmp(Col(o.lane_id as u16), cmp_of(o));
        acc = match (acc, o.combine == LGJ_COMBINE_AND) {
            // all_ones & p == p — the first AND IS the accumulator.
            (None, true) => Some(leaf),
            // all_ones | p == all_ones — the op is DEAD. This is exactly
            // `plan_lower`'s prefix rewrite, arriving here as a property of
            // the tree construction rather than as a special case.
            (None, false) => None,
            // AND is associative, so consecutive ANDs flatten into one
            // n-ary junction instead of left-nesting.
            (Some(Filter::And(mut v)), true) => {
                v.push(leaf);
                Some(Filter::And(v))
            }
            (Some(t), true) => Some(Filter::and([t, leaf])),
            (Some(Filter::Or(mut v)), false) => {
                v.push(leaf);
                Some(Filter::Or(v))
            }
            (Some(t), false) => Some(Filter::or([t, leaf])),
        };
    }
    acc
}

/// One `LgjOpDesc` opcode + operand -> one `Cmp`, kept TEXTUALLY PARALLEL to
/// `plan_lower::pred_of` (every cast below is the identical cast, in the
/// identical order) rather than merely equivalent to it: a different cast
/// here is exactly the class of bug this differential exists to catch, and
/// two spellings that happen to agree today are less trustworthy than two
/// spellings that are visibly the same read.
fn cmp_of(o: &LgjOpDesc) -> Cmp {
    match o.op {
        LGJ_OP_EQ_U32 => Cmp::EqU32(o.operand as u32),
        LGJ_OP_NE_U32 => Cmp::NeU32(o.operand as u32),
        LGJ_OP_EQ_I32 => Cmp::EqI32(o.operand as i32),
        LGJ_OP_NE_I32 => Cmp::NeI32(o.operand as i32),
        LGJ_OP_GT_I32 => Cmp::GtI32(o.operand as i32),
        LGJ_OP_LT_I32 => Cmp::LtI32(o.operand as i32),
        LGJ_OP_LE_I32 => Cmp::LeI32(o.operand as i32),
        LGJ_OP_GE_I32 => Cmp::GeI32(o.operand as i32),
        LGJ_OP_TERNARY_MATCH_U32 => {
            let packed = o.operand as u64;
            Cmp::MatchU32 {
                pattern: packed as u32,
                care: (packed >> 32) as u32,
            }
        }
        other => panic!("opcode {other} is not in the nine this file maps"),
    }
}

/// Execute an already-lowered [`Program`] against `planes`, reading its
/// `Terminal::Count` result.
///
/// Both arms funnel through this ONE runner, and — unlike the Planes
/// construction each arm builds for itself below — sharing the EXECUTOR
/// here is correct rather than risky: `execute` is third-party ground truth
/// neither lowering is allowed to reimplement, so calling the identical
/// function on both sides is the whole point of comparing LOWERINGS rather
/// than comparing EXECUTIONS.
fn run(p: &Program, planes: &Planes) -> usize {
    let words = planes.n_rows.div_ceil(64);
    let slots = p.scratch_slots as usize;
    let mut buf = vec![0u64; scratch_words_for(words, slots).expect("sized")];
    let mut scratch = Scratch::over(&mut buf, words, slots).expect("carves");
    match execute(p, planes, &mut scratch, None).expect("runs") {
        Value::Count(c) => c,
        other => panic!("not a count: {other:?}"),
    }
}

/// Arm A: this crate's own flat-op-list lowering.
///
/// `lanes`/`planes` are built HERE, independently of [`eval_quack`]'s own
/// copy below, exactly the way `exports.rs`'s `plan_eval_impl` builds them
/// (`LaneRef::U64(ids)`, `LaneRef::U32(classes)`, `LaneRef::I32(values)`, in
/// that order — pinned by the same `const _` index assertions
/// `plan_eval_impl` relies on). Duplicating this handful of lines rather
/// than sharing a builder between the two arms is deliberate: a shared
/// Planes-construction bug would corrupt both arms identically and the
/// differential could not see it, where duplicated construction turns such
/// a bug into an ordinary mismatch between the two arms.
fn eval_plan_lower(fixture: &Fixture, n_rows: u64, ops: &[LgjOpDesc]) -> usize {
    let lanes = [
        LaneRef::U64(fixture.ids()),
        LaneRef::U32(fixture.classes()),
        LaneRef::I32(fixture.values()),
    ];
    let planes = Planes {
        n_rows: n_rows as usize,
        masks: &[],
        lanes: &lanes,
    };
    match plan_lower::lower_plan(ops).expect("opcode is mapped") {
        plan_lower::Lowered::AllRows => n_rows as usize,
        plan_lower::Lowered::Program(p) => run(&p, &planes),
    }
}

/// Arm B: `lance-graph-quack`'s Boolean-tree lowering, fed the SAME plan
/// read as the tree it denotes (see [`fold_to_tree`]). See [`eval_plan_lower`]
/// for why the `lanes`/`planes` construction is duplicated rather than
/// shared between the two arms.
fn eval_quack(fixture: &Fixture, n_rows: u64, ops: &[LgjOpDesc]) -> usize {
    let lanes = [
        LaneRef::U64(fixture.ids()),
        LaneRef::U32(fixture.classes()),
        LaneRef::I32(fixture.values()),
    ];
    let planes = Planes {
        n_rows: n_rows as usize,
        masks: &[],
        lanes: &lanes,
    };
    match fold_to_tree(ops) {
        None => n_rows as usize,
        Some(filter) => {
            let q = Query {
                filter,
                agg: Agg::Count,
            };
            run(&lance_graph_quack::lower(&q).expect("lowers"), &planes)
        }
    }
}

/// Run both arms over the identical `ops` and assert they select the
/// identical row count, returning that count so callers can also fold it
/// into an anti-vacuity check.
fn assert_lowerings_agree(fixture: &Fixture, n_rows: u64, ops: &[LgjOpDesc], label: &str) -> usize {
    let from_plan_lower = eval_plan_lower(fixture, n_rows, ops);
    let from_quack = eval_quack(fixture, n_rows, ops);
    assert_eq!(
        from_plan_lower, from_quack,
        "{label}: plan_lower's flat-op-list lowering and lance-graph-quack's \
         tree lowering select different row counts ({from_plan_lower} vs {from_quack})"
    );
    from_plan_lower
}

// ---------------------------------------------------------------------------
// The 28-vector combine sweep, shared by the first two tests below.
// ---------------------------------------------------------------------------

/// A FIXED (opcode, lane, operand) pair per arity, so that across the whole
/// sweep only the COMBINE VECTOR varies — any disagreement is therefore
/// attributable to combine dispatch alone, the same discipline
/// `pr4_matrix.rs::combine_mode_products_agree_with_the_oracle` uses for its
/// own 2- and 3-op sweeps.
const TWO_OP: [(u32, u32, i64); 2] = [
    (LGJ_OP_EQ_U32, LANE_CLASSES, 7),
    (LGJ_OP_GT_I32, LANE_VALUES, 100),
];
const THREE_OP: [(u32, u32, i64); 3] = [
    (LGJ_OP_EQ_U32, LANE_CLASSES, 7),
    (LGJ_OP_GT_I32, LANE_VALUES, 100),
    (LGJ_OP_EQ_U32, LANE_CLASSES, 3),
];
/// The arity-3 set above, plus a fourth op — this repo's own combine sweep
/// stops at 3; this file widens it to 4 (16 vectors) because a 3-op sweep
/// cannot exercise a SECOND consecutive AND landing after an OR-then-AND
/// pair, a shape only a 4-op vector can produce.
const FOUR_OP: [(u32, u32, i64); 4] = [
    (LGJ_OP_EQ_U32, LANE_CLASSES, 7),
    (LGJ_OP_GT_I32, LANE_VALUES, 100),
    (LGJ_OP_EQ_U32, LANE_CLASSES, 3),
    (LGJ_OP_LT_I32, LANE_VALUES, 200),
];

/// Render a combine value as the word its constant names, for failure
/// messages — the same convention `pr4_matrix.rs::combine_name` uses (a
/// private helper of the same name in a sibling module; the two cannot
/// collide, they simply agree).
fn combine_name(c: u32) -> &'static str {
    if c == LGJ_COMBINE_AND {
        "AND"
    } else {
        "OR"
    }
}

/// Bit `i` of `bits` selects op `i`'s combiner: `0` = AND, `1` = OR.
fn ops_for_combine(base: &[(u32, u32, i64)], bits: u32) -> Vec<LgjOpDesc> {
    base.iter()
        .enumerate()
        .map(|(i, &(opcode, lane, operand))| {
            let combine = if bits & (1 << i) == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            };
            op(opcode, lane, operand, combine)
        })
        .collect()
}

/// A human-readable label for one combine vector, e.g. `"n=3 [AND, OR, AND]"`.
fn combine_label(base: &[(u32, u32, i64)], bits: u32) -> String {
    let names: Vec<&str> = (0..base.len())
        .map(|i| {
            combine_name(if bits & (1 << i) == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            })
        })
        .collect();
    format!("n={} [{}]", base.len(), names.join(", "))
}

/// Drive `visit` over every combine vector this file's first two tests both
/// need — the full `{AND, OR}^n` product for `n = 2, 3, 4` (4 + 8 + 16 = 28
/// vectors) — factored ONCE so the two tests provably sweep the SAME 28
/// rather than two independently-typed lookalikes that could silently drift
/// apart from each other.
fn for_each_combine_vector(mut visit: impl FnMut(String, Vec<LgjOpDesc>)) {
    for bits in 0u32..4 {
        visit(combine_label(&TWO_OP, bits), ops_for_combine(&TWO_OP, bits));
    }
    for bits in 0u32..8 {
        visit(
            combine_label(&THREE_OP, bits),
            ops_for_combine(&THREE_OP, bits),
        );
    }
    for bits in 0u32..16 {
        visit(
            combine_label(&FOUR_OP, bits),
            ops_for_combine(&FOUR_OP, bits),
        );
    }
}

/// FAILS IF: `plan_lower` and `lance-graph-quack` select a different row
/// count for ANY of the 28 combine vectors this test sweeps over two fixed
/// (opcode, lane, operand) sets — the differential's headline claim, over
/// the widest combine-dispatch surface either implementation is exercised
/// against anywhere in this crate.
#[test]
fn the_two_lowerings_agree_on_every_combine_vector() {
    let fixture = Fixture::generate(SWEEP_N, SWEEP_SEED).expect("n_rows fits usize on this target");
    let mut results: Vec<(String, usize)> = Vec::new();

    for_each_combine_vector(|label, ops| {
        let count = assert_lowerings_agree(&fixture, SWEEP_N, &ops, &label);
        results.push((label, count));
    });

    // `--nocapture` shows the real numbers; the anti-vacuity check below is
    // computed from this SAME vector, not asserted separately from it.
    for (label, count) in &results {
        println!("{label} -> {count}");
    }
    assert_eq!(
        results.len(),
        28,
        "the sweep must cover all 4 + 8 + 16 = 28 combine vectors, got {}",
        results.len()
    );

    // Anti-vacuity for the sweep as a whole (this workspace's own
    // falsifiability rule): agreement between two lowerings that both
    // answered "0" or "n" on every vector would prove nothing about combine
    // dispatch — it would just be two implementations of the identity
    // function agreeing with each other.
    //
    // MEASURED, not chosen: 21 of 28. The seven degenerate vectors are
    // `n=2 [OR,OR]`, `n=3 [AND,AND,AND]` and `[OR,OR,OR]`, and
    // `n=4 [AND,AND,AND,AND]`, `[OR,AND,OR,OR]`, `[AND,OR,OR,OR]`,
    // `[OR,OR,OR,OR]`. Four of those are the structural all-OR-tail
    // saturations the `assert_eq!`s above already pin separately; the
    // all-AND zeros are structural too (`class == 7` and `class == 3`
    // cannot both hold).
    //
    // The exact form is what made a real fixture defect visible. A first
    // version of the arity-4 arm appended `LT_I32(500)` — past the values
    // lane's own maximum of 361, so an always-true op — and the whole
    // 16-vector arm collapsed to eight saturated 1000s plus eight verbatim
    // copies of the arity-3 row, contributing exactly ZERO discriminating
    // power. It still cleared a `>= 15` floor, by sitting on it. Measuring
    // the lane and replacing the operand with `LT_I32(200)` (676 of 1000)
    // took the count 15 -> 21. A floor would have shipped the dead arm.
    let non_degenerate = results
        .iter()
        .filter(|(_, count)| *count > 0 && *count < SWEEP_N as usize)
        .count();
    assert_eq!(
        non_degenerate,
        21,
        "the combine sweep's discriminating power moved: {non_degenerate} of \
         {} vectors were non-degenerate (neither 0 nor {SWEEP_N}), expected 21. \
         Re-measure with --nocapture and re-pin; do NOT relax this to a floor",
        results.len()
    );
}

/// FAILS IF: `plan_lower`'s `Lowered::AllRows` shortcut and the tree fold's
/// `None` sentinel are reached under DIFFERENT conditions. They are meant to
/// be the SAME predicate — "no op in this plan combines with AND" — reached
/// by two different routes: a linear scan for the least AND index on one
/// side, a fold that never establishes an accumulator until the first AND
/// arrives on the other. A plan that flips one without flipping the other
/// would silently change which arm of a caller's `match` fires.
#[test]
fn the_all_rows_shortcut_is_the_same_condition_on_both_sides() {
    let mut all_rows = 0usize;
    let mut not_all_rows = 0usize;

    for_each_combine_vector(|label, ops| {
        let is_all_rows = matches!(
            plan_lower::lower_plan(&ops).expect("opcode is mapped"),
            plan_lower::Lowered::AllRows
        );
        let is_none = fold_to_tree(&ops).is_none();
        assert_eq!(
            is_all_rows, is_none,
            "{label}: plan_lower reports AllRows={is_all_rows} but the tree fold's \
             None-ness is {is_none} — the two must agree on whether any op in this \
             plan combines with AND"
        );
        if is_all_rows {
            all_rows += 1;
        } else {
            not_all_rows += 1;
        }
    });

    // Two-sided anti-vacuity, both counts checked EXACTLY: a version that
    // always agreed by both sides unconditionally returning `false` (or
    // both unconditionally returning `true`) would pass every assertion
    // above while proving nothing about the shared condition. The all-OR
    // vector is EXACTLY one per arity (every bit set: 0b11, 0b111, 0b1111),
    // so the split is known in advance rather than merely "some of each".
    assert_eq!(
        all_rows, 3,
        "expected exactly 3 all-OR vectors (one per arity) to read AllRows/None \
         on both sides, got {all_rows}"
    );
    assert_eq!(
        not_all_rows, 25,
        "expected the remaining 25 of 28 vectors to build a real program on both \
         sides, got {not_all_rows}"
    );
}

// ---------------------------------------------------------------------------
// Per-opcode predicate agreement.
// ---------------------------------------------------------------------------

/// The nine mapped opcodes, each paired with a (lane, operand) that is a
/// well-formed predicate over that lane's documented domain (`fixture.rs`:
/// classes `0..=15`, values `-150..=361`).
///
/// Every operand is MEASURED against the real lane rather than chosen from
/// the documented domain bounds, and that distinction cost a revision: a
/// first version gave `LT_I32`/`LE_I32` the operand `500`, past the values
/// lane's maximum of `361`, on the reasoning that a structurally always-true
/// predicate still exercises opcode-to-predicate AGREEMENT. It does not
/// exercise it usefully. Two arms agreeing that EVERY row survives cannot
/// separate `LtI32` from `LeI32` from any other predicate that is also
/// always-true on this lane — so a swapped entry in either mapping table
/// would have passed, which is precisely the defect this file exists to
/// catch. Measured (n = 1000, seed = 33): values span `-150..=361` with
/// median 100, so `LT_I32(200)` selects 676 and `LE_I32(300)` selects 865.
///
/// The four ordered comparisons are also given DISTINCT counts on purpose.
/// This is a differential between two arms, not against ground truth, so a
/// mis-map is visible only when it moves ONE arm's count — and two opcodes
/// that happen to select the same number of rows would hide a swap between
/// exactly those two.
const OPCODE_CASES: [(&str, u32, u32, i64); 9] = [
    ("EQ_U32", LGJ_OP_EQ_U32, LANE_CLASSES, 7),
    ("NE_U32", LGJ_OP_NE_U32, LANE_CLASSES, 7),
    ("EQ_I32", LGJ_OP_EQ_I32, LANE_VALUES, 100),
    ("NE_I32", LGJ_OP_NE_I32, LANE_VALUES, 100),
    ("GT_I32", LGJ_OP_GT_I32, LANE_VALUES, 100),
    ("LT_I32", LGJ_OP_LT_I32, LANE_VALUES, 200),
    ("LE_I32", LGJ_OP_LE_I32, LANE_VALUES, 300),
    ("GE_I32", LGJ_OP_GE_I32, LANE_VALUES, 100),
    (
        "TERNARY_MATCH",
        LGJ_OP_TERNARY_MATCH_U32,
        LANE_CLASSES,
        ((0x0000_000Fu64 << 32) | 7) as i64,
    ),
];

/// Two DISTINCT AND predicates on the lane OPPOSITE `lane`, used to sandwich
/// a subject opcode at position 1 of a genuine 3-op AND chain. Opposite-lane
/// so the chain never repeats the subject's own (opcode, lane) pair;
/// distinct from EACH OTHER so a lowering that silently reused position 0's
/// fields for position 2 (or vice versa) cannot hide behind two identical
/// entries.
fn flanking_ands(lane: u32) -> [LgjOpDesc; 2] {
    if lane == LANE_VALUES {
        [
            op(LGJ_OP_NE_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
            op(LGJ_OP_EQ_U32, LANE_CLASSES, 0, LGJ_COMBINE_AND),
        ]
    } else {
        [
            op(LGJ_OP_LT_I32, LANE_VALUES, 200, LGJ_COMBINE_AND),
            op(LGJ_OP_GT_I32, LANE_VALUES, -100, LGJ_COMBINE_AND),
        ]
    }
}

/// FAILS IF: any of the nine mapped opcodes translate to a DIFFERENT
/// predicate in `plan_lower`'s lowering than in `lance-graph-quack`'s —
/// checked both as the bare seed (position 0, ungated by anything) and
/// sandwiched at position 1 of a genuine 3-op AND chain, where BOTH
/// lowerings must gate the subject's own evaluation under an accumulator
/// two DIFFERENT flanking predicates have already narrowed. Also checks
/// that the TCAM opcode's packed `pattern`/`care` halves are not
/// interchangeable in either lowering, and that a half-swap moves both
/// lowerings identically.
#[test]
fn every_opcode_maps_to_the_same_predicate_in_both_lowerings() {
    let fixture = Fixture::generate(SWEEP_N, SWEEP_SEED).expect("n_rows fits usize on this target");
    let mut seed_counts: Vec<(&str, usize)> = Vec::new();

    for &(label, opcode, lane, operand) in &OPCODE_CASES {
        let subject = op(opcode, lane, operand, LGJ_COMBINE_AND);

        // Position 0: the bare seed. A lone AND op against the all-ones
        // accumulator IS its own predicate on both sides — no gate to get
        // wrong yet.
        let seed_count = assert_lowerings_agree(
            &fixture,
            SWEEP_N,
            &[subject],
            &format!("{label} at position 0"),
        );
        seed_counts.push((label, seed_count));

        // Position 1 of 3: genuinely sandwiched, gated by an accumulator
        // TWO other predicates have already narrowed — see `flanking_ands`.
        let [before, after] = flanking_ands(lane);
        let three_op = [before, subject, after];
        assert_lowerings_agree(
            &fixture,
            SWEEP_N,
            &three_op,
            &format!("{label} at position 1 of 3"),
        );
    }

    for (label, count) in &seed_counts {
        println!("{label} at position 0 -> {count}");
    }
    assert_eq!(
        seed_counts.len(),
        9,
        "expected all 9 mapped opcodes to be checked, got {}",
        seed_counts.len()
    );

    // Anti-vacuity: an opcode whose seed selects everything or nothing
    // cannot discriminate ANYTHING checked above for that opcode — two
    // lowerings agreeing that "everything" or "nothing" survives is not
    // evidence either translated the predicate correctly.
    //
    // MEASURED, and it is ALL NINE: 65, 935, 2, 998, 498, 676, 866, 500, 65
    // at n = 1000. A first version gave `LT_I32`/`LE_I32` the operand `500`
    // and both selected every row, which made the floor `7` — and a floor of
    // 7 is exactly what let two structurally untested opcode mappings ship.
    // `LtI32`, `LeI32`, and any other always-true reading are
    // indistinguishable when every row survives, so the swap this file
    // exists to catch would have passed on those two rows. See
    // `OPCODE_CASES`'s doc comment for the measurement that replaced them.
    let proper_subset = seed_counts
        .iter()
        .filter(|(_, c)| *c > 0 && *c < SWEEP_N as usize)
        .count();
    assert_eq!(
        proper_subset, 9,
        "{proper_subset} of 9 opcode seeds selected a proper subset of \
         {SWEEP_N} rows, expected all 9. An opcode that selects everything or \
         nothing is not being compared — re-measure the lane and pick an \
         operand inside it; do NOT relax this to a floor"
    );

    // The TCAM half-swap: pattern and care must not be interchangeable in
    // EITHER lowering. `pattern=7, care=0x0000_000F` (`OPCODE_CASES`'s own
    // TCAM row) already has `pattern != care`, which is the precondition a
    // half-swap needs to be observable at all (per `pr4_matrix.rs`'s own
    // note: the bug this guards against "is invisible whenever
    // `pattern == care`").
    let original_operand = ((0x0000_000Fu64 << 32) | 7) as i64;
    let swapped_operand = ((7u64 << 32) | 0x0000_000F) as i64;
    assert_ne!(
        original_operand, swapped_operand,
        "the swap must actually change the packed operand"
    );

    let original = op(
        LGJ_OP_TERNARY_MATCH_U32,
        LANE_CLASSES,
        original_operand,
        LGJ_COMBINE_AND,
    );
    let swapped = op(
        LGJ_OP_TERNARY_MATCH_U32,
        LANE_CLASSES,
        swapped_operand,
        LGJ_COMBINE_AND,
    );

    let original_count =
        assert_lowerings_agree(&fixture, SWEEP_N, &[original], "TCAM original halves");
    let swapped_count =
        assert_lowerings_agree(&fixture, SWEEP_N, &[swapped], "TCAM swapped halves");
    // The point of this half-swap is that it must move BOTH sides
    // IDENTICALLY, never just one — `assert_lowerings_agree` above already
    // proved that for each operand separately. This second assertion is the
    // other half: the swap must move them somewhere DIFFERENT from where
    // the original sat, or a lowering that silently ignored the swap
    // entirely (reading the operand identically before and after) would
    // still pass every check above by construction.
    assert_ne!(
        original_count, swapped_count,
        "swapping pattern and care selected the identical {original_count} rows — \
         pattern=7 and care=0x0000_000F are not equal, so a genuine half-swap must \
         select a DIFFERENT population; an identical count means the swap had no \
         observable effect on at least one lowering"
    );

    // And the swapped operand must ALSO agree once sandwiched, same as
    // every other opcode above — a half-swap bug could plausibly be scoped
    // to the ungated arm alone.
    let [before, after] = flanking_ands(LANE_CLASSES);
    assert_lowerings_agree(
        &fixture,
        SWEEP_N,
        &[before, swapped, after],
        "TCAM swapped halves at position 1 of 3",
    );
}

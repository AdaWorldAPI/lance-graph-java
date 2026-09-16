//! PR4's C1 falsifier matrix.
//!
//! `pr4_equivalence.rs` proves the frozen oracle agrees with the live path on
//! ONE two-op AND plan. That is not the matrix: PR4 replaces `plan_eval_impl`'s
//! opcode loop with a lowering (`&[LgjOpDesc] -> mask_risc::Program`) plus
//! `mask_risc::execute`, and a bug in the lowering can be scoped to exactly the
//! shapes this file checks and nothing the single example above would ever
//! reach:
//!
//! - an opcode wired correctly only for the UNGATED (position-0) case, wrong
//!   once it sits behind a real AND-narrowed or OR-widened accumulator
//!   (`every_opcode_agrees_with_the_oracle_at_every_position_in_a_plan`);
//! - the TCAM op's packed `(care << 32) | pattern` operand with its two
//!   halves swapped — invisible whenever `pattern == care`
//!   (`the_tcam_operand_halves_are_not_interchangeable`);
//! - a plan whose accumulator quietly bottoms out or saturates partway
//!   through, so ops after that point run unexercised even though the FINAL
//!   count still looks ordinary
//!   (`a_non_degenerate_plan_stays_non_degenerate_at_every_prefix`);
//! - a combine vector this crate's existing plans never happened to use — the
//!   full `{AND, OR}^n` product rather than just all-AND or AND-then-OR
//!   (`combine_mode_products_agree_with_the_oracle`).
//!
//! Every plan here is still run through [`assert_agrees`], so the oracle
//! comparison is the same one `pr4_equivalence.rs` establishes — this file
//! only widens WHICH plans get compared.

use super::pr4_equivalence::assert_agrees;
use super::*;

/// Render a combine value as the word its constant names, for failure
/// messages in [`combine_mode_products_agree_with_the_oracle`].
fn combine_name(c: u32) -> &'static str {
    if c == LGJ_COMBINE_AND {
        "AND"
    } else {
        "OR"
    }
}

/// FAILS IF: an opcode's `LgjOpDesc.op -> predicate kernel` mapping is wired
/// correctly only for the UNGATED, position-0 case. Because `plan_eval_impl`
/// now routes through `mask_risc::execute` and AND- vs. OR-narrowing may take
/// different branches inside `kernels::combine_into`, a mis-map could be
/// scoped to exactly one gated arm — passing at position 0 (a single op
/// against the all-ones seed IS the predicate) and disagreeing with the
/// oracle only once a real accumulator sits in front of it.
#[test]
fn every_opcode_agrees_with_the_oracle_at_every_position_in_a_plan() {
    let n = 1000u64;
    let p = open(n, 33);

    // (label, opcode, lane, operand). Every triple except `GT_I32` is the
    // exact one `every_minor_11_opcode_runs_on_both_plan_paths_and_they_agree`
    // (and its eq/ne-partition arm, both in this same file) already measured
    // as a proper subset on THIS (n=1000, seed=33) fixture — SplitMix64 is
    // deterministic, so the same (n, seed) always regenerates the identical
    // lanes. `GT_I32` predates ABI minor 11 and is checked fresh below.
    let cases: [(&str, u32, u32, i64); 9] = [
        ("EQ_U32", LGJ_OP_EQ_U32, LANE_CLASSES, 7),
        ("GT_I32", LGJ_OP_GT_I32, LANE_VALUES, 100),
        ("NE_U32", LGJ_OP_NE_U32, LANE_CLASSES, 7),
        ("EQ_I32", LGJ_OP_EQ_I32, LANE_VALUES, 100),
        ("NE_I32", LGJ_OP_NE_I32, LANE_VALUES, 100),
        ("LT_I32", LGJ_OP_LT_I32, LANE_VALUES, 100),
        ("LE_I32", LGJ_OP_LE_I32, LANE_VALUES, 100),
        ("GE_I32", LGJ_OP_GE_I32, LANE_VALUES, 100),
        (
            "TERNARY_MATCH_U32",
            LGJ_OP_TERNARY_MATCH_U32,
            LANE_CLASSES,
            tcam_operand(5, 0b111),
        ),
    ];

    for (label, opcode, lane, operand) in cases {
        // `other` is a different opcode on the OTHER lane, so a plan that
        // only ever evaluated one of the two ops would still disagree with
        // one that genuinely evaluated both.
        let other = if lane == LANE_VALUES {
            op(LGJ_OP_NE_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND)
        } else {
            op(LGJ_OP_LT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)
        };

        // Position 0: the op alone. `combine` MUST be AND here — seeded with
        // OR, the all-ones accumulator stays all-ones regardless of the
        // predicate, which would fail the anti-vacuity check below for every
        // opcode instead of measuring anything about this one.
        let pos0 = assert_agrees(
            p,
            &[op(opcode, lane, operand, LGJ_COMBINE_AND)],
            &format!("{label} at position 0"),
        );
        assert!(
            pos0.count > 0 && pos0.count < n,
            "{label} at position 0 selected {} of {n} rows — not a proper subset, \
             so its gated arms below cannot be discriminating either",
            pos0.count
        );

        // Position 1, gated by an AND: [AND other, AND subject].
        assert_agrees(
            p,
            &[other, op(opcode, lane, operand, LGJ_COMBINE_AND)],
            &format!("{label} at position 1, AND-gated"),
        );

        // Position 1, gated by an OR: [AND other, OR subject].
        assert_agrees(
            p,
            &[other, op(opcode, lane, operand, LGJ_COMBINE_OR)],
            &format!("{label} at position 1, OR-gated"),
        );
    }

    assert_eq!(lgj_close(p), LGJ_OK);
}

/// FAILS IF: `LGJ_OP_TERNARY_MATCH_U32`'s packed `operand` can have its two
/// 32-bit halves swapped in the mask-risc lowering (`(pattern << 32) | care`
/// instead of the documented `(care << 32) | pattern`) without changing the
/// observable result. That bug is invisible whenever `pattern == care` —
/// swapping two equal halves is a no-op — which is exactly the shape of the
/// pre-existing `the_tcam_opcodes_packed_operand_decodes_both_halves` fixture
/// (`tcam_operand(0b1000, 0b1000)`). Every arm here uses `pattern != care`.
#[test]
fn the_tcam_operand_halves_are_not_interchangeable() {
    let n = 1000u64;
    let p = open(n, 33);
    let other = op(LGJ_OP_LT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND);

    // (label, pattern, care) — both pairs asymmetric, so a half-swap changes
    // the selected population instead of silently reproducing it.
    let cases: [(&str, u32, u32); 2] = [
        ("pattern=5 care=0b111", 5, 0b111),
        ("pattern=7 care=0b1000", 7, 0b1000),
    ];

    for (label, pattern, care) in cases {
        assert_ne!(
            pattern, care,
            "{label}: a pattern==care fixture cannot see a half-swap"
        );
        let operand = tcam_operand(pattern, care);

        let pos0 = assert_agrees(
            p,
            &[op(
                LGJ_OP_TERNARY_MATCH_U32,
                LANE_CLASSES,
                operand,
                LGJ_COMBINE_AND,
            )],
            &format!("{label} at position 0"),
        );
        assert!(
            pos0.count > 0 && pos0.count < n,
            "{label} at position 0 selected {} of {n} rows — not a proper subset",
            pos0.count
        );

        assert_agrees(
            p,
            &[
                other,
                op(
                    LGJ_OP_TERNARY_MATCH_U32,
                    LANE_CLASSES,
                    operand,
                    LGJ_COMBINE_AND,
                ),
            ],
            &format!("{label} at position 1, AND-gated"),
        );

        assert_agrees(
            p,
            &[
                other,
                op(
                    LGJ_OP_TERNARY_MATCH_U32,
                    LANE_CLASSES,
                    operand,
                    LGJ_COMBINE_OR,
                ),
            ],
            &format!("{label} at position 1, OR-gated"),
        );
    }

    assert_eq!(lgj_close(p), LGJ_OK);
}

/// FAILS IF: an implementation only guards against a fully-degenerate FINAL
/// accumulator (all-zero or all-ones), so ops sitting after the accumulator
/// bottoms out at zero (making every later AND a no-op) or saturates to
/// all-ones (making every later OR a no-op) go unexercised without the final
/// count ever looking wrong — a mid-plan collapse can still land the last op
/// on a perfectly ordinary-looking count.
#[test]
fn a_non_degenerate_plan_stays_non_degenerate_at_every_prefix() {
    let n = 1000u64;
    let p = open(n, 33);

    // A 4-op mixed AND/OR plan. Op 0 must be AND — an OR at position 0 is
    // unconditionally degenerate against the all-ones seed (see the
    // position-0 comment above) — and from there the combine vector
    // genuinely alternates.
    let ops = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_OR),
        op(LGJ_OP_LT_I32, LANE_VALUES, 200, LGJ_COMBINE_AND),
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 3, LGJ_COMBINE_OR),
    ];

    for k in 1..=ops.len() {
        let prefix = &ops[..k];
        let out = assert_agrees(p, prefix, &format!("prefix length {k}"));
        assert!(
            out.count > 0 && out.count < n,
            "prefix length {k} selected {} of {n} rows — a degenerate prefix means \
             op(s) {k}..{} of the full 4-op plan would run with a bottomed-out or \
             saturated accumulator and never really be exercised",
            out.count,
            ops.len()
        );
    }

    assert_eq!(lgj_close(p), LGJ_OK);
}

/// FAILS IF: combine dispatch is only correct for the two shapes this crate's
/// existing plans have ever used — all-AND, or AND-then-OR — and mishandles
/// any other member of the `{AND, OR}^n` product: most plausibly an OR that
/// is not in the final position, or two ORs in a row (which is provably
/// `n_rows` regardless of predicate content, checked directly below, so
/// getting it wrong would show up as a SHRUNK count rather than merely a
/// mismatch against the oracle).
#[test]
fn combine_mode_products_agree_with_the_oracle() {
    let n = 1000u64;
    let p = open(n, 33);

    // A FIXED opcode/lane/operand set per arity: only the combine VECTOR
    // varies across the sweep, so any divergence is attributable to combine
    // dispatch alone.
    let two_op: [(u32, u32, i64); 2] = [
        (LGJ_OP_EQ_U32, LANE_CLASSES, 7),
        (LGJ_OP_GT_I32, LANE_VALUES, 100),
    ];
    let three_op: [(u32, u32, i64); 3] = [
        (LGJ_OP_EQ_U32, LANE_CLASSES, 7),
        (LGJ_OP_GT_I32, LANE_VALUES, 100),
        (LGJ_OP_EQ_U32, LANE_CLASSES, 3),
    ];

    let mut degenerate: Vec<String> = Vec::new();
    let mut non_degenerate = 0u32;

    for bits in 0u32..4 {
        // bit i of `bits` selects op i's combiner: 0 = AND, 1 = OR.
        let combine: [u32; 2] = [
            if bits & 1 == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            },
            if bits & 2 == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            },
        ];
        let label = format!(
            "n=2 [{}, {}]",
            combine_name(combine[0]),
            combine_name(combine[1])
        );
        let ops = [
            op(two_op[0].0, two_op[0].1, two_op[0].2, combine[0]),
            op(two_op[1].0, two_op[1].1, two_op[1].2, combine[1]),
        ];
        let out = assert_agrees(p, &ops, &label);
        if out.count == 0 || out.count == n {
            degenerate.push(format!("{label} -> {}", out.count));
        } else {
            non_degenerate += 1;
        }
        // Structurally guaranteed regardless of what the two predicates
        // select: once the accumulator starts all-ones, `acc |= x` can never
        // shrink it — an all-OR vector is always `n_rows`, full stop.
        if combine == [LGJ_COMBINE_OR, LGJ_COMBINE_OR] {
            assert_eq!(
                out.count, n,
                "{label}: an all-OR vector must stay saturated at all-ones"
            );
        }
    }

    for bits in 0u32..8 {
        let combine: [u32; 3] = [
            if bits & 1 == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            },
            if bits & 2 == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            },
            if bits & 4 == 0 {
                LGJ_COMBINE_AND
            } else {
                LGJ_COMBINE_OR
            },
        ];
        let label = format!(
            "n=3 [{}, {}, {}]",
            combine_name(combine[0]),
            combine_name(combine[1]),
            combine_name(combine[2])
        );
        let ops = [
            op(three_op[0].0, three_op[0].1, three_op[0].2, combine[0]),
            op(three_op[1].0, three_op[1].1, three_op[1].2, combine[1]),
            op(three_op[2].0, three_op[2].1, three_op[2].2, combine[2]),
        ];
        let out = assert_agrees(p, &ops, &label);
        if out.count == 0 || out.count == n {
            degenerate.push(format!("{label} -> {}", out.count));
        } else {
            non_degenerate += 1;
        }
        if combine == [LGJ_COMBINE_OR, LGJ_COMBINE_OR, LGJ_COMBINE_OR] {
            assert_eq!(
                out.count, n,
                "{label}: an all-OR vector must stay saturated at all-ones"
            );
        }
    }

    // Anti-vacuity for the sweep as a whole: the fixed opcode/operand set
    // must exercise SOME real composition, or every oracle-agreement check
    // above would have passed by comparing two identically-degenerate
    // answers rather than by exercising combine dispatch.
    //
    // The bound is MEASURED, not chosen. On this (n=1000, seed=33) fixture
    // the 12 sweeps are: n=2 [AND,AND] 39, [OR,AND] 498, [AND,OR] 524,
    // [OR,OR] 1000; n=3 [AND,AND,AND] 0, [OR,AND,AND] 40, [AND,OR,AND] 40,
    // [OR,OR,AND] 73, [AND,AND,OR] 112, [OR,AND,OR] 531, [AND,OR,OR] 557,
    // [OR,OR,OR] 1000. Exactly THREE are degenerate and two of those are
    // degenerate by construction rather than by fixture — an all-OR vector
    // saturates at `n` no matter what the predicates select, which the two
    // structural `assert_eq!`s above already pin separately. So the real
    // discriminating power of this sweep is 9 of 12, and that is the bound.
    //
    // `> 0` was the original, and it is the weak form this repo's own
    // falsifiability rule names: it passes when ELEVEN of twelve sweeps have
    // gone degenerate, which is precisely the state where the oracle
    // comparisons stop comparing anything. A fixture drift that hollowed the
    // sweep out would read as green under it and red under this.
    assert_eq!(
        non_degenerate, 9,
        "the combine sweep's discriminating power moved: expected 9 of 12 \
         non-degenerate, got {non_degenerate} (degenerate: {degenerate:?}). \
         Two of the three degenerate sweeps are the structural all-OR \
         saturations; a third is fixture-dependent. Re-measure before re-pinning"
    );

    assert_eq!(lgj_close(p), LGJ_OK);
}

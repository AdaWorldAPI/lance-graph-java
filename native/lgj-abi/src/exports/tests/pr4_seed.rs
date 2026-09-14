//! PR4 C1 seed: the leading-OR falsifier matrix.
//!
//! `abi.md` §7's contract is unambiguous: "the accumulator starts as all rows
//! set." A lowering can still get this wrong by taking a shortcut that reads
//! naturally but ignores the FIRST op's own `combine` field — something like
//! "the accumulator starts all-ones, so the first AND is a no-op; just let
//! `acc := pred_0` directly." Applied unconditionally, that shortcut is
//! correct for `combine = AND` and silently WRONG for `combine = OR`, because
//! `ALL | pred_0 == ALL` regardless of what `pred_0` selects, while
//! `acc := pred_0` gives `pred_0`'s own — generally smaller — selection.
//!
//! Every plan in this crate's pre-existing test suite begins with `AND` (the
//! only two `LGJ_COMBINE_OR` sites are both the SECOND op — see
//! `simd_and_scalar_plans_agree_bit_for_bit` and `or_plans_widen` in
//! `exports.rs`), so that wrong shortcut passes the entire suite as it stands
//! today. These tests exist to make it fail: they are pure ADDITIONS, and per
//! this module's freeze discipline they must be GREEN against the current
//! (pre-PR4) implementation, which does not take the shortcut — `plan_eval_impl`
//! seeds `acc` to all-ones and clears its tail unconditionally, before
//! looking at any op at all.
//!
//! T1-T3 pin the OR side across opcodes, operands, plan lengths and word
//! boundaries. T4 pins the algebra of OR followed by AND. T5 is the paired
//! silent half — without it, an implementation that returned every row for
//! EVERY single-op plan (AND included) would pass T1-T4 while being wrong.

use super::pr4_equivalence::assert_agrees;
use super::*;

/// The bit pattern for "every one of `n_rows` rows is selected": every word
/// full of ones except the last, whose bits at row index `>= n_rows` are
/// cleared. Built from the two primitives every write path in this crate
/// already uses for exactly this purpose (`plan_eval_impl`'s own seeding
/// step is `for w in acc.iter_mut() { *w = u64::MAX; } clear_tail_bits(&mut
/// acc, n_rows);`) — reusing them here means this expected value can never
/// silently drift from the tail-bit convention the implementation itself is
/// built on.
fn all_rows_words(n_rows: u64) -> Vec<u64> {
    let mut w = vec![u64::MAX; mask_words_for(n_rows) as usize];
    clear_tail_bits(&mut w, n_rows);
    w
}

/// True iff no bit at row index `>= n_rows` survives in the last word.
///
/// Written as its own small, independent formula (not by delegating to
/// [`clear_tail_bits`] and diffing) so it can catch a bug at the ONE call
/// site that matters — the plan's published `dst_mask` — rather than merely
/// re-confirming that the helper above computes what it always computes.
///
/// `n_rows % 64 == 0` needs its own branch: when the last word is exactly
/// full there is no tail region at all, and shifting by zero would (wrongly)
/// demand the whole word be zero — the same edge case `clear_tail_bits`'s own
/// `if used != 0` guards against (`abi.rs`'s `tail_bits_are_cleared` test
/// pins it: `clear_tail_bits(&mut w2, 128)` is a no-op, not a zeroing).
fn tail_is_clean(words: &[u64], n_rows: u64) -> bool {
    let rem = n_rows % 64;
    if rem == 0 {
        return true;
    }
    match words.last() {
        Some(&last) => last >> rem == 0,
        None => true,
    }
}

/// The 8 non-ternary comparison opcodes, paired with the lane
/// `opcode_required_kind` (`abi.rs`) requires of them: the three `U32`
/// opcodes read `LANE_CLASSES` (`0..=15`), the five `I32` opcodes read
/// `LANE_VALUES` (`-150..=361`) — both ranges are `fixture.rs`'s own
/// documented generation contract.
///
/// `LGJ_OP_TERNARY_MATCH_U32` is deliberately excluded: its `operand` packs
/// two 32-bit halves (`(care << 32) | pattern`) rather than a plain needle,
/// which needs its own construction orthogonal to this seed.
const LEADING_OR_OPCODES: [(u32, u32); 8] = [
    (LGJ_OP_EQ_U32, LANE_CLASSES),
    (LGJ_OP_GT_I32, LANE_VALUES),
    (LGJ_OP_NE_U32, LANE_CLASSES),
    (LGJ_OP_EQ_I32, LANE_VALUES),
    (LGJ_OP_NE_I32, LANE_VALUES),
    (LGJ_OP_LT_I32, LANE_VALUES),
    (LGJ_OP_LE_I32, LANE_VALUES),
    (LGJ_OP_GE_I32, LANE_VALUES),
];

/// Three operands per opcode, chosen against the documented domains
/// (`fixture.rs`: classes `0..=15`, values `-150..=361`) to select roughly
/// nothing, roughly half, and roughly everything of the fixture's rows — so
/// a single coincidentally-safe operand cannot hide the shortcut.
///
/// The four inequality opcodes over `LANE_VALUES` (`GT`/`LT`/`LE`/`GE`) hit
/// all three bands EXACTLY: the domain has 512 values, and the midpoint
/// operands below (105/106) split it into two halves of 256 values each.
/// `EQ`/`NE` cannot naturally reach "half" over a 16- or 512-valued domain (a
/// single (in)equality selects at most ~1/16 or ~1/512, never ~50%), so their
/// middle entry is a second, different in-range value instead of a forced
/// "half" — still a genuinely distinct predicate, which is what the property
/// under test actually needs.
fn leading_or_operands(op: u32) -> [i64; 3] {
    match op {
        // class == X: 999 matches no row (no class is that high); 7 and 0
        // each match ~1/16 of rows, from opposite ends of the domain.
        LGJ_OP_EQ_U32 => [999, 7, 0],
        // class != X: 999 matches EVERY row (no class equals it); 7 and 0
        // each exclude ~1/16, matching ~15/16 from opposite ends.
        LGJ_OP_NE_U32 => [999, 7, 0],
        // value == X: 1000 is out of range (matches nothing); 105 and -150
        // (the domain's own minimum) each match only a handful of rows.
        LGJ_OP_EQ_I32 => [1000, 105, -150],
        // value != X: 1000 is out of range (matches EVERY row); 105 and
        // -150 each exclude only a handful, matching nearly every row.
        LGJ_OP_NE_I32 => [1000, 105, -150],
        // value > X: 1000 matches nothing (max is 361); 105 splits the
        // domain exactly in half (values 106..=361, 256 of 512); -1000
        // matches every row (min is -150).
        LGJ_OP_GT_I32 => [1000, 105, -1000],
        // value < X: mirror of GT_I32, with the halves and extremes swapped.
        LGJ_OP_LT_I32 => [-1000, 106, 1000],
        // value <= X: same shape as LT_I32 (the boundary shifts inclusion
        // by one, which is irrelevant to "roughly nothing/half/everything").
        LGJ_OP_LE_I32 => [-1000, 105, 1000],
        // value >= X: mirror of LE_I32.
        LGJ_OP_GE_I32 => [1000, 106, -1000],
        _ => unreachable!("leading_or_operands: opcode {op} is not in LEADING_OR_OPCODES"),
    }
}

/// FAILS IF: a lowering takes the shortcut "the accumulator starts all-ones,
/// so the first AND is a no-op; let `acc := pred_0` directly" and applies it
/// WITHOUT checking that op's own `combine` field. Every plan below has
/// exactly one op, with `combine = OR` — per `abi.md` §7 ("the accumulator
/// starts as all rows set"), `ALL | pred_0 == ALL` regardless of what
/// `pred_0` selects, so the correct answer is `n` for every operand tried.
/// The wrong shortcut instead publishes `pred_0` itself, which is wrong for
/// every operand below except the handful chosen to already select
/// everything — the spread of selectivities is what stops those from
/// masking the rest.
#[test]
fn a_leading_or_returns_every_row_whatever_the_predicate_says() {
    let n = 1000u64;
    let p = open(n, 0xA11C_E000);
    let expected = all_rows_words(n);

    for &(opcode, lane) in &LEADING_OR_OPCODES {
        for operand in leading_or_operands(opcode) {
            let plan = [op(opcode, lane, operand, LGJ_COMBINE_OR)];
            let out = assert_agrees(
                p,
                &plan,
                &format!("leading OR, opcode={opcode} lane={lane} operand={operand}"),
            );
            assert_eq!(
                out.status, LGJ_OK,
                "opcode={opcode} operand={operand}: a well-formed single-op plan must validate"
            );
            assert_eq!(
                out.count, n,
                "opcode={opcode} operand={operand}: a leading OR must select every row \
                 (got {}), whatever the predicate itself matched",
                out.count
            );
            assert_eq!(
                out.words, expected,
                "opcode={opcode} operand={operand}: a leading OR must publish an all-ones, \
                 tail-clean mask, not the predicate's own bits"
            );
        }
    }

    lgj_close(p);
}

/// FAILS IF: the seed used ahead of a leading OR is a raw memset of every
/// WORD to all-ones that skips the tail-clearing the normal path always
/// applies before publishing — the composed defect this whole seed matrix
/// exists to catch. At a word boundary that shows up as two DIFFERENT wrong
/// numbers: the popcount over-counts by the tail width (`n_words * 64`
/// instead of `n`), and the raw published word carries garbage bits past row
/// `n`. Asserting both means a failure names which one actually fired.
///
/// The predicate (`value > 1000`) is chosen to select LITERALLY ZERO rows for
/// any seed — no fixture value ever exceeds 361 (`fixture.rs`'s documented
/// range) — so a DIFFERENT shortcut (`acc := pred_0`, T1's target) would also
/// be caught here, with its own distinct wrong number (`0` instead of `n`).
#[test]
fn a_leading_or_at_a_word_boundary_keeps_its_tail_clean() {
    for n in [1u64, 63, 65, 127, 128, 999, 4097] {
        let p = open(n, 0x5EED_0000 | n);
        let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 1000, LGJ_COMBINE_OR)];
        let out = assert_agrees(p, &ops, &format!("word-boundary leading OR, n={n}"));

        assert_eq!(
            out.count, n,
            "n={n}: a leading OR must select every row, not a rounded-up-to-64 word count \
             (got {})",
            out.count
        );
        assert!(
            tail_is_clean(&out.words, n),
            "n={n}: dirty tail bits survived past row {n} (last word = {:#x})",
            out.words.last().copied().unwrap_or(0)
        );

        lgj_close(p);
    }
}

/// FAILS IF: an all-OR plan is evaluated as if only its first op existed —
/// each op below uses a DIFFERENT opcode, lane and operand, so a bug that
/// silently reused op[0]'s fields for every later position (rather than
/// genuinely reading each one) cannot hide behind identical entries. The
/// contract's own algebra says the answer must be `n` regardless of length:
/// the first OR forces the accumulator to ALL, and `ALL | anything == ALL`
/// for every op after it.
#[test]
fn an_all_or_plan_returns_every_row_however_many_ops_it_has() {
    let n = 3000u64;
    let p = open(n, 0x0A11_0A11);
    let expected = all_rows_words(n);

    // Four distinct, individually well-formed predicates spanning both
    // required lane kinds, so a 2/3/4-op prefix of this pool never repeats
    // an (opcode, lane, operand) triple.
    let pool = [
        op(LGJ_OP_EQ_U32, LANE_CLASSES, 3, LGJ_COMBINE_OR),
        op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_OR),
        op(LGJ_OP_NE_U32, LANE_CLASSES, 9, LGJ_COMBINE_OR),
        op(LGJ_OP_LT_I32, LANE_VALUES, -50, LGJ_COMBINE_OR),
    ];

    for len in 2..=4usize {
        let plan = &pool[..len];
        let out = assert_agrees(p, plan, &format!("all-OR plan, len={len}"));
        assert_eq!(
            out.count, n,
            "len={len}: an all-OR plan must select every row regardless of its length \
             (got {})",
            out.count
        );
        assert_eq!(
            out.words, expected,
            "len={len}: an all-OR plan must publish an all-ones, tail-clean mask"
        );
    }

    lgj_close(p);
}

/// FAILS IF: the leading OR contributes anything beyond forcing the
/// accumulator to ALL before the AND narrows it. `[OR p0, AND p1]` and
/// `[AND p1]` alone must be bit-for-bit indistinguishable — status, count,
/// AND every published word — because a leading OR that reaches ALL (T1)
/// followed by an AND (T5) is, by the contract's own algebra, exactly the
/// AND alone. `p0` is deliberately arbitrary: ANY well-formed predicate
/// there must give the identical final answer, since a leading OR erases
/// whatever it selects.
#[test]
fn an_or_then_and_plan_reduces_to_the_and_predicate_alone() {
    let n = 5000u64;
    let p = open(n, 0x0FF1_CE00);

    let p0 = op(LGJ_OP_EQ_U32, LANE_CLASSES, 5, LGJ_COMBINE_OR);
    let p1 = op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND);

    let plan_or_then_and = [p0, p1];
    let plan_and_alone = [p1];

    let out_combined = assert_agrees(p, &plan_or_then_and, "[OR p0, AND p1]");
    let out_alone = assert_agrees(p, &plan_and_alone, "[AND p1] alone");

    assert_eq!(
        out_combined, out_alone,
        "a leading OR followed by an AND must equal the AND predicate alone"
    );
    // Anti-vacuity: both sides agreeing because both selected everything (or
    // nothing) would prove nothing about the ALGEBRA — p1 must genuinely cut
    // the population down to a proper subset, or this passes for a broken
    // implementation that ignores every op and always returns everything.
    assert!(
        out_combined.count > 0 && out_combined.count < n,
        "fixture must select a proper subset, got {}",
        out_combined.count
    );

    lgj_close(p);
}

/// FAILS IF: a single-op plan treats its op's `combine` field as irrelevant
/// and always publishes "the whole answer" regardless of it — the ONE case
/// where that reading happens to be correct is a single AND, and this test
/// pins it against the fixture's OWN ground truth (computed by direct
/// iteration over `Fixture::values()`, not via any plan machinery) rather
/// than merely against another run of the same code path.
///
/// This is the paired silent half of T1-T4: without it, an implementation
/// that unconditionally returned every row for EVERY single-op plan — AND
/// included — would satisfy every assertion above while being wrong exactly
/// here, where "every row" is not the answer.
#[test]
fn a_leading_and_is_still_exactly_the_predicate() {
    let n = 2500u64;
    let seed = 0xDEAD_10CC;
    let p = open(n, seed);
    let f = Fixture::generate(n, seed).expect("n_rows fits usize on this target");

    let predicate = op(LGJ_OP_LT_I32, LANE_VALUES, 50, LGJ_COMBINE_AND);
    let out = assert_agrees(p, &[predicate], "single AND op");

    let want = f.values().iter().filter(|&&v| v < 50).count() as u64;
    assert_eq!(
        out.count, want,
        "a single AND op must equal the predicate's own selection exactly, \
         computed independently from the raw fixture values (got {}, want {want})",
        out.count
    );
    // Anti-vacuity: without this, a broken implementation that returns ALL
    // rows unconditionally for every single-op plan would pass by accident —
    // this is precisely the case where "all rows" is the WRONG answer.
    assert!(
        out.count > 0 && out.count < n,
        "fixture must select a proper subset, got {}",
        out.count
    );

    lgj_close(p);
}

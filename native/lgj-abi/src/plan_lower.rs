//! The plan lowering — `&[LgjOpDesc]` becomes one `mask_risc::Program`.
//!
//! This is the component PR4 introduces that did not exist before, and the
//! reason `pr4_equivalence.rs` freezes a copy of the old loop rather than
//! reusing mask-risc's own oracle: the lowering sits ABOVE both `execute` and
//! `reference_execute`, so a bug here produces the same wrong `Program` for
//! both and mask-risc's own differential stays green.
//!
//! # The prefix rewrite
//!
//! The old loop seeded its accumulator with all rows and then folded each op
//! in with `&=` or `|=`. An `|=` against an all-ones accumulator cannot shrink
//! it, so **every op before the first AND is dead**: whatever it selects, the
//! accumulator is still all rows when the first AND arrives, and
//! `all_ones & p == p`.
//!
//! So let `k` be the least index whose combine is AND. Ops `0..k` are dropped;
//! op `k` becomes a bare `Pred` writing slot 0 (it IS the accumulator, no
//! seed needed); each later op writes slot 1 and folds into slot 0. If no op
//! combines with AND (`k == n`) the answer is every row and **no program is
//! built at all** — there is nothing left to evaluate.
//!
//! That is not an optimisation bolted on: it is what lets the lowering avoid
//! needing a fill/constant op, which the mask-RISC deliberately does not have.
//!
//! # The survivor skip, and where it is NOT sound
//!
//! A later op whose combine is AND is gated `under` slot 0: `acc & p` depends
//! on `p` only where `acc` already has a survivor, so the predicate runs over
//! the accumulator's live 64-row words and nothing else.
//!
//! A later op whose combine is OR is **not** gated, and the asymmetry is the
//! whole correctness question in this file. `acc | p` depends on `p` exactly
//! where `acc` is ZERO — the rows a gate under `acc` would discard. Gating an
//! OR would zero precisely the bits that matter and quietly shrink the answer
//! to `acc` itself. The combine-vector sweep in `pr4_matrix.rs` is what holds
//! this: it runs the full `{AND, OR}^n` product against the frozen oracle.

use crate::abi::{
    LgjOpDesc, LGJ_COMBINE_AND, LGJ_OP_EQ_I32, LGJ_OP_EQ_U32, LGJ_OP_GE_I32, LGJ_OP_GT_I32,
    LGJ_OP_LE_I32, LGJ_OP_LT_I32, LGJ_OP_NE_I32, LGJ_OP_NE_U32, LGJ_OP_TERNARY_MATCH_U32,
};
use lance_graph_mask_risc::{MaskOp, Operand, Pred, Program, Terminal};

/// Which lane a grouped sum groups by — this table's own `u32` lane, or a
/// `u32` lane of ANOTHER table read through a `u32` key lane of this one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum GroupKey {
    /// `GROUP BY key` where `key` is a `U32` lane of the executing table.
    Local(u16),
    /// `GROUP BY other.key` read as `foreign.lanes[key][fk[i]]` — `fk` is a
    /// `U32` lane of the executing table, `key` indexes the `Foreign::lanes`
    /// the caller supplies. The indirection is fused inside the terminal; no
    /// remapped key lane and no partner-side mask ever exist.
    Via { fk: u16, key: u16 },
}

/// Scratch slots the lowering ever names: slot 0 is the accumulator (the old
/// `acc`, now living in the caller's arena rather than in a per-call `vec!`),
/// slot 1 the per-op predicate destination (the old `scratch`).
pub(crate) const SLOTS: u32 = 2;

/// The scratch slot the final mask lands in — the accumulator.
pub(crate) const ACC_SLOT: u16 = 0;

/// What a plan lowers to.
pub(crate) enum Lowered {
    /// Every combine is OR. The accumulator starts all-ones and `|=` can never
    /// shrink it, so the answer is every row with a clean tail — a constant,
    /// reached without building or running a program.
    AllRows,
    /// The surviving suffix, as one straight-line program whose terminal
    /// counts slot [`ACC_SLOT`].
    Program(Program),
}

/// Lower a validated plan.
///
/// `ops` must already have passed `validate_plan`, which is what makes the
/// opcode map total. It is still written as a `None` rather than a panic: an
/// unreachable arm that aborts the process is a worse failure than one that
/// returns the error the validator would have returned anyway.
pub(crate) fn lower_plan(ops: &[LgjOpDesc]) -> Option<Lowered> {
    let Some(k) = ops.iter().position(|o| o.combine == LGJ_COMBINE_AND) else {
        return Some(Lowered::AllRows);
    };

    let mut program_ops = Vec::with_capacity(2 * (ops.len() - k) - 1);
    program_ops.push(MaskOp::Pred {
        pred: pred_of(&ops[k])?,
        under: None,
        dst: ACC_SLOT,
    });
    for op in &ops[k + 1..] {
        let is_and = op.combine == LGJ_COMBINE_AND;
        program_ops.push(MaskOp::Pred {
            pred: pred_of(op)?,
            // See the module doc: an AND reads its predicate only where the
            // accumulator already survives; an OR reads it exactly where the
            // accumulator does not, so gating one would answer `acc`.
            under: is_and.then_some(Operand::Scratch(ACC_SLOT)),
            dst: 1,
        });
        let (a, b, dst) = (Operand::Scratch(ACC_SLOT), Operand::Scratch(1), ACC_SLOT);
        program_ops.push(if is_and {
            MaskOp::And { a, b, dst }
        } else {
            MaskOp::Or { a, b, dst }
        });
    }

    // `Keep`, not `Count`: the destination mask IS the demanded result, so
    // the executor writes it tile by tile straight into the resource's own
    // words (`Out::Mask`) and the count is a popcount over that sink. Under
    // tiled execution no population-sized scratch exists to count from.
    Some(Lowered::Program(Program::new(
        program_ops,
        Terminal::Keep {
            mask: Operand::Scratch(ACC_SLOT),
        },
    )))
}

/// Lower a validated plan STRAIGHT INTO a grouped sum — the same prefix
/// rewrite and survivor skip as [`lower_plan`], ending in
/// [`Terminal::GroupSumI32`] / [`Terminal::GroupSumViaI32`] over the
/// accumulator instead of [`Terminal::Keep`].
///
/// This is the shape that lets a `GROUP BY … SUM` cross the membrane once
/// and return only group totals: no `Keep` lands a population anywhere, the
/// accumulator lives in tile-local scratch, and the terminal folds `val` into
/// the caller's `i64` buffer tile by tile.
///
/// # The all-rows arm
///
/// [`lower_plan`] answers `AllRows` without a program because its caller can
/// fill the destination mask itself. A grouped sum has no destination mask
/// to fill, so the all-rows case — an empty plan, or one whose every combine
/// is OR — needs an accumulator that IS every row. That is
/// [`Pred::Range`]`{ lo: 0, hi: n_rows }`: a three-pass fill over the tile's
/// words (`mask_set_range`), reading no lane. It is the ONLY `Range` this
/// crate ever emits, and with `hi == n_rows` exactly it can never leave the
/// lane, which is what keeps `exec_error_to_status`'s `RangeOutOfBounds` arm
/// an internal-bug mapping — pinned by
/// `range_falsifier::the_group_sum_range_is_the_whole_lane_and_nothing_else`.
///
/// `n_rows` is `u32` because [`Pred::Range`] is; a caller with more rows than
/// that cannot take the all-rows arm and must say so before lowering.
pub(crate) fn lower_group_sum(
    ops: &[LgjOpDesc],
    n_rows: u32,
    group: GroupKey,
    val: u16,
) -> Option<Program> {
    let mask = Operand::Scratch(ACC_SLOT);
    let terminal = match group {
        GroupKey::Local(key) => Terminal::GroupSumI32 { mask, key, val },
        GroupKey::Via { fk, key } => Terminal::GroupSumViaI32 { mask, fk, key, val },
    };
    let program_ops = match lower_plan(ops)? {
        Lowered::AllRows => vec![MaskOp::Pred {
            pred: Pred::Range { lo: 0, hi: n_rows },
            under: None,
            dst: ACC_SLOT,
        }],
        Lowered::Program(p) => p.ops,
    };
    Some(Program::new(program_ops, terminal))
}

/// One `LgjOpDesc` opcode + operand → one `Pred`.
///
/// Every operand conversion is the one `kernels::eval_predicate` performs, and
/// deliberately spelled the same way: the operand arrives sign-extended in an
/// `i64`, a `u32` needle is its low 32 bits as an exact bit pattern, and the
/// TCAM operand packs `(care << 32) | pattern`. A different cast here is
/// exactly the class of bug the frozen oracle exists to catch, so the two
/// readings are kept textually parallel rather than merely equivalent.
fn pred_of(op: &LgjOpDesc) -> Option<Pred> {
    let lane = u16::try_from(op.lane_id).ok()?;
    Some(match op.op {
        LGJ_OP_EQ_U32 => Pred::EqU32 {
            lane,
            v: op.operand as u32,
        },
        LGJ_OP_NE_U32 => Pred::NeU32 {
            lane,
            v: op.operand as u32,
        },
        LGJ_OP_EQ_I32 => Pred::EqI32 {
            lane,
            v: op.operand as i32,
        },
        LGJ_OP_NE_I32 => Pred::NeI32 {
            lane,
            v: op.operand as i32,
        },
        LGJ_OP_GT_I32 => Pred::GtI32 {
            lane,
            t: op.operand as i32,
        },
        LGJ_OP_LT_I32 => Pred::LtI32 {
            lane,
            t: op.operand as i32,
        },
        LGJ_OP_LE_I32 => Pred::LeI32 {
            lane,
            t: op.operand as i32,
        },
        LGJ_OP_GE_I32 => Pred::GeI32 {
            lane,
            t: op.operand as i32,
        },
        LGJ_OP_TERNARY_MATCH_U32 => {
            let packed = op.operand as u64;
            Pred::MatchU32 {
                lane,
                pattern: packed as u32,
                care: (packed >> 32) as u32,
            }
        }
        _ => return None,
    })
}

#[cfg(test)]
mod range_falsifier {
    use super::*;
    use crate::abi::LGJ_COMBINE_OR;

    /// **The falsifier behind `exec_error_to_status`'s `RangeOutOfBounds` arm.**
    ///
    /// That arm maps a `mask_risc` error into the documented *"would be a bug
    /// in THIS file, not in a caller's plan"* family, and the justification is
    /// one sentence: **this lowering emits no `Pred::Range`**, exactly as it
    /// emits no sum terminal and no blend. A sentence is not a fence, so this
    /// sweeps every opcode a caller can put in an `LgjOpDesc` — the nine
    /// defined ones and a band of undefined ones either side — through the
    /// real `lower_plan` and reads every emitted predicate.
    ///
    /// **When LGJ gains a Range lowering this test goes RED**, and that is its
    /// whole purpose: it forces the author to decide, deliberately and
    /// visibly, what a caller should see when a range leaves the lane — rather
    /// than inheriting `LGJ_ERR_ALLOCATION_FAILED`, which would then be a lie.
    ///
    /// Minor 12 did gain ONE `Range` — [`lower_group_sum`]'s all-rows arm — and
    /// decided it deliberately: that range is always `0..n_rows`, so it cannot
    /// leave the lane and the internal-bug mapping stays true. It is pinned by
    /// the sibling test below; this sweep still holds for every opcode through
    /// `lower_plan`, which is the path a caller's operand reaches.
    #[test]
    fn no_opcode_lowers_to_pred_range() {
        let mut lowered_count = 0usize;
        for op in 0u32..=32 {
            for combine in [LGJ_COMBINE_AND, LGJ_COMBINE_OR] {
                let desc = LgjOpDesc {
                    op,
                    lane_id: 0,
                    operand: -1,
                    combine,
                    _reserved: 0,
                };
                let Some(lowered) = lower_plan(&[desc]) else {
                    continue;
                };
                let Lowered::Program(program) = lowered else {
                    continue; // AllRows: no predicate at all.
                };
                lowered_count += 1;
                for mask_op in &program.ops {
                    if let MaskOp::Pred { pred, .. } = mask_op {
                        assert!(
                            !matches!(pred, Pred::Range { .. }),
                            "opcode {op} lowered to Pred::Range — `exec_error_to_status` maps \
                             `RangeOutOfBounds` as an internal bug, which is now false: give it \
                             a caller-visible status before shipping the Range lowering"
                        );
                    }
                }
            }
        }
        // Anti-vacuity: a sweep that lowers nothing proves nothing. Nine
        // opcodes are defined and each lowers under `LGJ_COMBINE_AND`.
        assert!(
            lowered_count >= 9,
            "the sweep lowered only {lowered_count} programs — it is not exercising the lowering"
        );
    }

    /// The one `Range` this crate emits is the whole lane, in the all-rows arm
    /// only. An empty plan and an all-OR plan both take it; a plan with an AND
    /// emits none. Any other `Range` would make `exec_error_to_status`'s
    /// `RangeOutOfBounds` arm reachable through the ABI, and this is the test
    /// that would say so.
    #[test]
    fn the_group_sum_range_is_the_whole_lane_and_nothing_else() {
        let or_op = LgjOpDesc {
            op: LGJ_OP_EQ_U32,
            lane_id: 1,
            operand: 7,
            combine: LGJ_COMBINE_OR,
            _reserved: 0,
        };
        let and_op = LgjOpDesc {
            combine: LGJ_COMBINE_AND,
            ..or_op
        };
        let ranges = |ops: &[LgjOpDesc]| -> Vec<(u32, u32)> {
            let p = lower_group_sum(ops, 1000, GroupKey::Local(1), 2).expect("lowers");
            p.ops
                .iter()
                .filter_map(|op| match op {
                    MaskOp::Pred {
                        pred: Pred::Range { lo, hi },
                        ..
                    } => Some((*lo, *hi)),
                    _ => None,
                })
                .collect()
        };
        assert_eq!(ranges(&[]), vec![(0, 1000)], "an empty plan is every row");
        assert_eq!(
            ranges(&[or_op, or_op]),
            vec![(0, 1000)],
            "an all-OR plan is every row"
        );
        assert!(
            ranges(&[and_op]).is_empty(),
            "a plan with an AND needs no range"
        );
        assert!(ranges(&[or_op, and_op, or_op]).is_empty());
        // And the terminal is the grouped sum over the accumulator, both shapes.
        let local = lower_group_sum(&[and_op], 1000, GroupKey::Local(1), 2).unwrap();
        assert_eq!(
            local.terminal,
            Terminal::GroupSumI32 {
                mask: Operand::Scratch(ACC_SLOT),
                key: 1,
                val: 2
            }
        );
        let via = lower_group_sum(&[], 1000, GroupKey::Via { fk: 1, key: 0 }, 2).unwrap();
        assert_eq!(
            via.terminal,
            Terminal::GroupSumViaI32 {
                mask: Operand::Scratch(ACC_SLOT),
                fk: 1,
                key: 0,
                val: 2
            }
        );
    }
}

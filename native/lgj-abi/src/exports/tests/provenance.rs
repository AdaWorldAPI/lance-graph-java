//! What each mask-taking export checks about WHERE a mask came from — pinned
//! as it is today, so a change to the rule is a deliberate re-pin.
//!
//! Every mask handle is checked for liveness, kind and a live parent
//! (`registry::resolve_mask_with_parent`). Beyond that the exports follow two
//! different rules:
//!
//! - **same parent**: the mask algebra requires all its masks to share one
//!   parent; the register sweeps require the mask's parent to be the very
//!   resource they read (`Arc::ptr_eq`).
//! - **row count only**: the predicate ops, `lgj_plan_eval`, the lane
//!   reductions and `lgj_hop` accept any live mask of the right length, even
//!   one created over a different resource, or over a resource of another
//!   kind.
//!
//! This file states that matrix as a table and asserts it exactly. It then
//! shows what the second rule permits: a selection computed on one resource,
//! stored in a mask that belongs to another, and summed over the other's
//! values without any status reporting it.

use super::*;

const N: u64 = 256;

struct World {
    pattern: u64,
    pattern_sibling: u64,
    store: u64,
    store_sibling: u64,
}

fn pattern(n: u64, seed: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(call::pattern_open(n, seed, &mut h), LGJ_OK);
    h
}

fn store(n: u64, seed: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(unsafe { lgj_rowstore_open(n, seed, &mut h) }, LGJ_OK);
    h
}

fn mask_over(parent: u64) -> u64 {
    let mut h = 0u64;
    assert_eq!(call::mask_create(parent, LGJ_MASK_INIT_ALL, &mut h), LGJ_OK);
    h
}

/// Where a mask came from, relative to the resource an export is asked to
/// read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Origin {
    /// Created over the resource itself.
    Own,
    /// Created over another resource of the same kind and row count.
    Sibling,
    /// Created over a resource of the OTHER kind with the same row count.
    OtherKind,
    /// Created over a same-kind resource with fewer rows.
    Short,
    /// Its parent was closed.
    Orphan,
}

const ORIGINS: [Origin; 5] = [
    Origin::Own,
    Origin::Sibling,
    Origin::OtherKind,
    Origin::Short,
    Origin::Orphan,
];

/// A fresh mask of the given origin for a PATTERN export reading `w.pattern`,
/// or (with `for_store`) for a ROW-STORE export reading `w.store`.
fn mask_of(w: &World, origin: Origin, for_store: bool) -> u64 {
    match (origin, for_store) {
        (Origin::Own, false) => mask_over(w.pattern),
        (Origin::Own, true) => mask_over(w.store),
        (Origin::Sibling, false) => mask_over(w.pattern_sibling),
        (Origin::Sibling, true) => mask_over(w.store_sibling),
        (Origin::OtherKind, false) => mask_over(w.store),
        (Origin::OtherKind, true) => mask_over(w.pattern),
        (Origin::Short, false) => mask_over(pattern(N / 2, 9)),
        (Origin::Short, true) => mask_over(store(N / 2, 9)),
        (Origin::Orphan, false) => {
            let p = pattern(N, 77);
            let m = mask_over(p);
            assert_eq!(lgj_close(p), LGJ_OK);
            m
        }
        (Origin::Orphan, true) => {
            let s = store(N, 77);
            let m = mask_over(s);
            assert_eq!(lgj_close(s), LGJ_OK);
            m
        }
    }
}

type Export = (&'static str, bool, fn(&World, u64) -> i32);

/// Every export that takes one mask beside a resource. The `bool` is whether
/// the resource is the row store.
fn exports() -> Vec<Export> {
    vec![
        ("lgj_op_eq_u32", false, |w, m| {
            lgj_op_eq_u32(w.pattern, LANE_CLASSES, 7, m)
        }),
        ("lgj_op_gt_i32", false, |w, m| {
            lgj_op_gt_i32(w.pattern, LANE_VALUES, 100, m)
        }),
        ("lgj_plan_eval", false, |w, m| {
            let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)];
            let mut c = 0u64;
            call::plan_eval(w.pattern, ops.as_ptr(), 1, m, &mut c)
        }),
        ("lgj_reduce_sum_i32", false, |w, m| {
            let mut s = 0i64;
            call::reduce_sum_i32(w.pattern, LANE_VALUES, m, &mut s)
        }),
        ("lgj_reduce_i32", false, |w, m| {
            let (mut v, mut present) = (0i64, 0u32);
            call11::reduce_i32(
                w.pattern,
                LANE_VALUES,
                LGJ_REDUCE_MAX,
                m,
                &mut v,
                &mut present,
            )
        }),
        ("lgj_op_eq_classid", true, |w, m| {
            lgj_op_eq_classid(w.store, 0, 3, m)
        }),
        ("lgj_op_ternary_match", true, |w, m| {
            let (pattern, care) = ([0u8; 12], [0u8; 12]);
            call11::op_ternary_match(w.store, 0, pattern.as_ptr(), care.as_ptr(), m)
        }),
        ("lgj_hop (as src)", true, |w, m| {
            let dst = mask_over(w.store);
            lgj_hop(w.store, 0, 0xFFFF_FFFF, 0, m, dst)
        }),
        ("lgj_hop (as dst)", true, |w, m| {
            let src = mask_over(w.store);
            lgj_hop(w.store, 0, 0xFFFF_FFFF, 0, src, m)
        }),
        ("lgj_reduce_facet_sum", true, |w, m| {
            let mut s = 0i64;
            unsafe { lgj_reduce_facet_sum(w.store, 0, 0, m, &mut s) }
        }),
        ("lgj_reduce_facet_sum_resolved", true, |w, m| {
            let (mut s, mut carving) = (0i64, 0u32);
            unsafe { lgj_reduce_facet_sum_resolved(w.store, 0, m, &mut s, &mut carving) }
        }),
        ("lgj_row_layout_probe", true, |w, m| {
            let mut out = [0u8; 32];
            unsafe { lgj_row_layout_probe(w.store, m, out.as_mut_ptr(), 32) }
        }),
    ]
}

const OK: i32 = LGJ_OK;
const LEN: i32 = LGJ_ERR_MASK_LENGTH_MISMATCH;
const CLOSED: i32 = LGJ_ERR_PARENT_CLOSED;

/// The matrix, as measured. Columns follow [`ORIGINS`]:
/// own, sibling, other kind, short, orphan.
///
/// The two rules are visible as the `sibling` and `other kind` columns: `OK`
/// under the row-count rule, `LEN` under the same-parent rule.
const EXPECTED: [(&str, [i32; 5]); 12] = [
    ("lgj_op_eq_u32", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_op_gt_i32", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_plan_eval", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_reduce_sum_i32", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_reduce_i32", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_op_eq_classid", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_op_ternary_match", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_hop (as src)", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_hop (as dst)", [OK, OK, OK, LEN, CLOSED]),
    ("lgj_reduce_facet_sum", [OK, LEN, LEN, LEN, CLOSED]),
    // `own` passes the parent check and is then refused for a reason that has
    // nothing to do with provenance: an all-rows mask spans classes whose
    // registers resolve differently. The parent check still comes first, as
    // the next three columns show.
    (
        "lgj_reduce_facet_sum_resolved",
        [LGJ_ERR_UNRESOLVED_CARVING, LEN, LEN, LEN, CLOSED],
    ),
    ("lgj_row_layout_probe", [OK, LEN, LEN, LEN, CLOSED]),
];

fn world() -> World {
    World {
        pattern: pattern(N, 1),
        pattern_sibling: pattern(N, 2),
        store: store(N, 3),
        store_sibling: store(N, 4),
    }
}

#[test]
fn the_mask_origin_matrix_is_as_pinned() {
    let w = world();
    let mut measured = Vec::new();
    for (name, for_store, call) in exports() {
        let row: Vec<i32> = ORIGINS
            .iter()
            .map(|&o| call(&w, mask_of(&w, o, for_store)))
            .collect();
        measured.push((name, row));
    }
    assert_eq!(measured.len(), EXPECTED.len());
    for ((name, row), (want_name, want)) in measured.iter().zip(EXPECTED.iter()) {
        assert_eq!(name, want_name);
        assert_eq!(
            row.as_slice(),
            want,
            "{name}: [own, sibling, other kind, short, orphan]"
        );
    }
    // Both rules are present, so the table is not one row repeated.
    let lenient = EXPECTED.iter().filter(|(_, r)| r[1] == OK).count();
    let strict = EXPECTED.iter().filter(|(_, r)| r[1] == LEN).count();
    assert_eq!((lenient, strict), (9, 3));
}

/// The mask algebra is under the same-parent rule: two masks of equal length
/// over different resources do not combine, whichever of the three positions
/// the foreign one is in.
#[test]
fn mask_algebra_requires_one_parent() {
    let w = world();
    let (a, b, d) = (
        mask_over(w.pattern),
        mask_over(w.pattern),
        mask_over(w.pattern),
    );
    let foreign = mask_over(w.pattern_sibling);
    assert_eq!(lgj_mask_and(a, b, d), LGJ_OK);
    for (x, y, z) in [(foreign, b, d), (a, foreign, d), (a, b, foreign)] {
        assert_eq!(lgj_mask_and(x, y, z), LEN);
        assert_eq!(lgj_mask_or(x, y, z), LEN);
        assert_eq!(lgj_mask_andnot(x, y, z), LEN);
        assert_eq!(lgj_mask_ternlog(x, y, z, z, 0x80), LEN);
    }
}

/// What the row-count rule permits. A predicate evaluated on `B` lands in a
/// mask that belongs to `A`; summing `A`'s values over it then reports `OK`
/// and a number that is the answer to no question about `A` or about `B`.
#[test]
fn a_selection_can_be_relabelled_onto_another_resource_today() {
    let (n, seed_a, seed_b) = (4096u64, 11u64, 22u64);
    let (a, b) = (pattern(n, seed_a), pattern(n, seed_b));
    let fa = Fixture::generate(n, seed_a).unwrap();
    let fb = Fixture::generate(n, seed_b).unwrap();

    let mask_of_a = mask_over(a);
    // "class == 7" evaluated on B, written into A's mask.
    assert_eq!(lgj_op_eq_u32(b, LANE_CLASSES, 7, mask_of_a), LGJ_OK);
    let mut got = 0i64;
    assert_eq!(
        call::reduce_sum_i32(a, LANE_VALUES, mask_of_a, &mut got),
        LGJ_OK
    );

    let sum_where = |sel: &Fixture, vals: &Fixture| -> i64 {
        (0..n as usize)
            .filter(|&i| sel.classes()[i] == 7)
            .map(|i| i64::from(vals.values()[i]))
            .sum()
    };
    let a_on_a = sum_where(&fa, &fa);
    let b_on_b = sum_where(&fb, &fb);
    let b_selects_a_values = sum_where(&fb, &fa);

    assert_eq!(
        got, b_selects_a_values,
        "the call answered B's selection over A's values"
    );
    assert_ne!(got, a_on_a, "which is not the answer for A");
    assert_ne!(got, b_on_b, "and not the answer for B");

    // The same pairing is refused where the same-parent rule applies.
    let mask_of_b = mask_over(b);
    assert_eq!(lgj_mask_and(mask_of_a, mask_of_b, mask_of_a), LEN);
}

/// Two schemas over one population share one handle, so they share one
/// parent: any rule keyed on the parent leaves this case working. Here two
/// predicates over different lanes of one resource compose in one mask.
#[test]
fn two_readings_of_one_resource_share_its_parent() {
    let w = world();
    let (by_class, by_value, both) = (
        mask_over(w.pattern),
        mask_over(w.pattern),
        mask_over(w.pattern),
    );
    assert_eq!(lgj_op_eq_u32(w.pattern, LANE_CLASSES, 7, by_class), LGJ_OK);
    assert_eq!(lgj_op_gt_i32(w.pattern, LANE_VALUES, 100, by_value), LGJ_OK);
    assert_eq!(lgj_mask_and(by_class, by_value, both), LGJ_OK);
    let (mut all, mut narrowed) = (0u64, 0u64);
    assert_eq!(call::mask_count(by_class, &mut all), LGJ_OK);
    assert_eq!(call::mask_count(both, &mut narrowed), LGJ_OK);
    assert!(narrowed > 0 && narrowed < all);
}

/// Two handles onto one population are not representable today: opening the
/// same `(rows, seed)` twice builds two resources with equal contents and
/// distinct identities, and a mask over one is foreign to the other under the
/// same-parent rule. There is no export that returns a second handle to an
/// existing resource.
#[test]
fn equal_contents_are_not_one_population() {
    let (first, second) = (store(N, 5), store(N, 5));
    assert_ne!(first, second);
    let mask_of_first = mask_over(first);
    let mut s = 0i64;
    assert_eq!(
        unsafe { lgj_reduce_facet_sum(first, 0, 0, mask_of_first, &mut s) },
        LGJ_OK
    );
    assert_eq!(
        unsafe { lgj_reduce_facet_sum(second, 0, 0, mask_of_first, &mut s) },
        LEN
    );
}

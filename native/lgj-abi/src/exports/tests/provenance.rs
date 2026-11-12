//! A mask may be read or written only against the resource it was created
//! over (`docs/abi.md` §3.1, ABI minor 14).
//!
//! Every mask handle is checked for liveness, kind and a live parent
//! (`registry::resolve_mask_with_parent`). Every export that takes a mask
//! beside a resource then requires that parent to BE the resource, compared
//! by registry identity (`Arc::ptr_eq`), never by handle value, row count,
//! kind or contents.
//!
//! Until minor 14 nine of these exports checked only the row count. This file
//! pinned that as a table; the table is re-pinned here with every `sibling`
//! and `other kind` cell a refusal. Each refusal is also checked to leave the
//! destination mask and every scalar output as they were.

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

fn count_of(mask: u64) -> u64 {
    let mut c = 0u64;
    assert_eq!(call::mask_count(mask, &mut c), LGJ_OK);
    c
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

/// A fresh all-rows mask of the given origin for a PATTERN export reading
/// `w.pattern`, or (with `for_store`) for a ROW-STORE export reading
/// `w.store`.
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

/// A value no output in this file takes on success.
const SENTINEL: i64 = i64::MIN + 0x9A7E;

/// One mask-taking export.
struct Export {
    name: &'static str,
    /// Whether the resource argument is the row store.
    for_store: bool,
    /// Whether the mask under test is the export's DESTINATION.
    writes_mask: bool,
    /// Run it against the mask. Returns the status and whether every output
    /// other than the mask under test still holds what it held before.
    call: fn(&World, u64) -> (i32, bool),
}

fn exports() -> Vec<Export> {
    vec![
        Export {
            name: "lgj_op_eq_u32",
            for_store: false,
            writes_mask: true,
            call: |w, m| (lgj_op_eq_u32(w.pattern, LANE_CLASSES, 7, m), true),
        },
        Export {
            name: "lgj_op_gt_i32",
            for_store: false,
            writes_mask: true,
            call: |w, m| (lgj_op_gt_i32(w.pattern, LANE_VALUES, 100, m), true),
        },
        Export {
            name: "lgj_plan_eval",
            for_store: false,
            writes_mask: true,
            call: |w, m| {
                let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)];
                let mut c = SENTINEL as u64;
                let st = call::plan_eval(w.pattern, ops.as_ptr(), 1, m, &mut c);
                (st, c == SENTINEL as u64)
            },
        },
        Export {
            name: "lgj_reduce_sum_i32",
            for_store: false,
            writes_mask: false,
            call: |w, m| {
                let mut s = SENTINEL;
                let st = call::reduce_sum_i32(w.pattern, LANE_VALUES, m, &mut s);
                (st, s == SENTINEL)
            },
        },
        Export {
            name: "lgj_reduce_i32",
            for_store: false,
            writes_mask: false,
            call: |w, m| {
                let (mut v, mut present) = (SENTINEL, 77u32);
                let st = call11::reduce_i32(
                    w.pattern,
                    LANE_VALUES,
                    LGJ_REDUCE_MAX,
                    m,
                    &mut v,
                    &mut present,
                );
                (st, v == SENTINEL && present == 77)
            },
        },
        Export {
            name: "lgj_op_eq_classid",
            for_store: true,
            writes_mask: true,
            call: |w, m| (lgj_op_eq_classid(w.store, 0, 3, m), true),
        },
        Export {
            name: "lgj_op_ternary_match",
            for_store: true,
            writes_mask: true,
            call: |w, m| {
                // Selective: the register's first byte must be 1.
                let mut pattern = [0u8; 12];
                let mut care = [0u8; 12];
                (pattern[0], care[0]) = (1, 0xFF);
                (
                    call11::op_ternary_match(w.store, 0, pattern.as_ptr(), care.as_ptr(), m),
                    true,
                )
            },
        },
        Export {
            name: "lgj_hop (as src)",
            for_store: true,
            writes_mask: false,
            call: |w, m| {
                let dst = mask_over(w.store);
                let st = lgj_hop(w.store, 0, 0xFFFF_FFFF, 0, m, dst);
                // On a refusal the caller's own destination is untouched too.
                (st, count_of(dst) == N)
            },
        },
        Export {
            name: "lgj_hop (as dst)",
            for_store: true,
            writes_mask: true,
            call: |w, m| {
                let src = mask_over(w.store);
                (lgj_hop(w.store, 0, 0xFFFF_FFFF, 0, src, m), true)
            },
        },
        Export {
            name: "lgj_reduce_facet_sum",
            for_store: true,
            writes_mask: false,
            call: |w, m| {
                let mut s = SENTINEL;
                let st = unsafe { lgj_reduce_facet_sum(w.store, 0, 0, m, &mut s) };
                (st, s == SENTINEL)
            },
        },
        Export {
            name: "lgj_reduce_facet_sum_resolved",
            for_store: true,
            writes_mask: false,
            call: |w, m| {
                let (mut s, mut carving) = (SENTINEL, 77u32);
                let st =
                    unsafe { lgj_reduce_facet_sum_resolved(w.store, 0, m, &mut s, &mut carving) };
                (st, s == SENTINEL && carving == 77)
            },
        },
        Export {
            name: "lgj_row_layout_probe",
            for_store: true,
            writes_mask: false,
            call: |w, m| {
                let mut out = [0xA5u8; 32];
                let st = unsafe { lgj_row_layout_probe(w.store, m, out.as_mut_ptr(), 32) };
                (st, out.iter().all(|&b| b == 0xA5))
            },
        },
    ]
}

const OK: i32 = LGJ_OK;
const LEN: i32 = LGJ_ERR_MASK_LENGTH_MISMATCH;
const CLOSED: i32 = LGJ_ERR_PARENT_CLOSED;

/// The matrix. Columns follow [`ORIGINS`]:
/// own, sibling, other kind, short, orphan.
///
/// Every export refuses a mask that is not its resource's own. Until minor 14
/// the first nine rows read `[OK, OK, OK, LEN, CLOSED]`.
const EXPECTED: [(&str, [i32; 5]); 12] = [
    ("lgj_op_eq_u32", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_op_gt_i32", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_plan_eval", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_reduce_sum_i32", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_reduce_i32", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_op_eq_classid", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_op_ternary_match", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_hop (as src)", [OK, LEN, LEN, LEN, CLOSED]),
    ("lgj_hop (as dst)", [OK, LEN, LEN, LEN, CLOSED]),
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

/// The 12 × 5 matrix, and for every refusal: the mask under test and every
/// other output are exactly as they were.
#[test]
fn the_mask_origin_matrix_is_as_pinned() {
    let w = world();
    let all = exports();
    assert_eq!(all.len(), EXPECTED.len());
    let (mut refusals, mut writers_observed) = (0usize, 0usize);
    for (export, (want_name, want)) in all.iter().zip(EXPECTED.iter()) {
        assert_eq!(export.name, *want_name);
        for (&origin, &want_status) in ORIGINS.iter().zip(want.iter()) {
            let mask = mask_of(&w, origin, export.for_store);
            // An orphan's count cannot be read (its parent is closed), so its
            // words are checked through the status alone.
            let before = (origin != Origin::Orphan).then(|| count_of(mask));
            let (status, others_untouched) = (export.call)(&w, mask);
            assert_eq!(
                status, want_status,
                "{} with a mask of origin {origin:?}",
                export.name
            );
            if status == OK {
                if export.writes_mask {
                    // Anti-vacuity for the "untouched" checks below: on
                    // success the export DOES change an all-rows mask.
                    assert_ne!(
                        Some(count_of(mask)),
                        before,
                        "{}: a successful call left its destination unchanged, so an \
                         unchanged destination proves nothing",
                        export.name
                    );
                    writers_observed += 1;
                }
                continue;
            }
            refusals += 1;
            assert!(
                others_untouched,
                "{} refused a mask of origin {origin:?} but wrote an output",
                export.name
            );
            if let Some(before) = before {
                assert_eq!(
                    count_of(mask),
                    before,
                    "{} refused a mask of origin {origin:?} but changed it",
                    export.name
                );
            }
        }
    }
    // 12 exports × 4 foreign origins, plus the one provenance-unrelated
    // refusal in the `own` column.
    assert_eq!(refusals, 12 * 4 + 1);
    // Six of the twelve write the mask under test.
    assert_eq!(writers_observed, 6);
}

/// `lgj_hop` checks its two masks separately: a foreign source is refused
/// with an own destination, a foreign destination with an own source, and
/// neither mask is changed by the refusal.
#[test]
fn hop_checks_source_and_destination_separately() {
    let w = world();
    let hop = |src, dst| lgj_hop(w.store, 0, 0xFFFF_FFFF, 0, src, dst);
    for foreign_parent in [w.store_sibling, w.pattern] {
        let (own_src, own_dst, foreign) = (
            mask_over(w.store),
            mask_over(w.store),
            mask_over(foreign_parent),
        );
        assert_eq!(hop(foreign, own_dst), LEN, "foreign source");
        assert_eq!(hop(own_src, foreign), LEN, "foreign destination");
        assert_eq!(hop(foreign, foreign), LEN, "both foreign");
        for m in [own_src, own_dst, foreign] {
            assert_eq!(count_of(m), N, "a refused hop changed a mask");
        }
        // Aliasing an own mask as both source and destination still works.
        assert_eq!(hop(own_src, own_src), LGJ_OK);
        assert_eq!(hop(own_src, own_dst), LGJ_OK);
    }
}

/// The mask algebra is under the same rule: two masks of equal length over
/// different resources do not combine, whichever of the three positions the
/// foreign one is in.
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

/// Until minor 14 this sequence returned `OK` twice and a number that
/// answered no question: a predicate evaluated on `B` landed in a mask that
/// belongs to `A`, and `A`'s values were summed over it. Both steps are now
/// refused, in either pairing, and nothing is written.
#[test]
fn a_selection_cannot_be_relabelled_onto_another_resource() {
    let (n, seed_a, seed_b) = (4096u64, 11u64, 22u64);
    let (a, b) = (pattern(n, seed_a), pattern(n, seed_b));
    let fa = Fixture::generate(n, seed_a).unwrap();
    let fb = Fixture::generate(n, seed_b).unwrap();
    let on = |f: &Fixture| (0..n as usize).filter(|&i| f.classes()[i] == 7).count() as u64;
    assert_ne!(on(&fa), on(&fb), "the two populations select differently");

    // Step 1 of the old hazard: write B's selection into A's mask.
    let mask_of_a = mask_over(a);
    assert_eq!(lgj_op_eq_u32(b, LANE_CLASSES, 7, mask_of_a), LEN);
    assert_eq!(
        count_of(mask_of_a),
        n,
        "the refused predicate left A's mask alone"
    );

    // Step 2: sum A's values over a mask that holds B's selection.
    let mask_of_b = mask_over(b);
    assert_eq!(lgj_op_eq_u32(b, LANE_CLASSES, 7, mask_of_b), LGJ_OK);
    assert_eq!(count_of(mask_of_b), on(&fb));
    let mut got = SENTINEL;
    assert_eq!(
        call::reduce_sum_i32(a, LANE_VALUES, mask_of_b, &mut got),
        LEN
    );
    assert_eq!(got, SENTINEL, "the refused reduction wrote no sum");

    // Each resource with its own mask still answers.
    assert_eq!(lgj_op_eq_u32(a, LANE_CLASSES, 7, mask_of_a), LGJ_OK);
    assert_eq!(count_of(mask_of_a), on(&fa));
    let mut sum = SENTINEL;
    assert_eq!(
        call::reduce_sum_i32(a, LANE_VALUES, mask_of_a, &mut sum),
        LGJ_OK
    );
    let want: i64 = (0..n as usize)
        .filter(|&i| fa.classes()[i] == 7)
        .map(|i| i64::from(fa.values()[i]))
        .sum();
    assert_eq!(sum, want);
}

/// Two schemas over one population share one handle, so they share one
/// parent: the rule leaves this case working. Here two predicates over
/// different lanes of one resource compose in one mask, and one mask serves
/// reductions over two different lanes.
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
    let narrowed = count_of(both);
    assert!(narrowed > 0 && narrowed < count_of(by_class));

    let (mut sum, mut max, mut present) = (0i64, 0i64, 0u32);
    assert_eq!(
        call::reduce_sum_i32(w.pattern, LANE_VALUES, both, &mut sum),
        LGJ_OK
    );
    assert_eq!(
        call11::reduce_i32(
            w.pattern,
            LANE_VALUES,
            LGJ_REDUCE_MAX,
            both,
            &mut max,
            &mut present
        ),
        LGJ_OK
    );
    assert_eq!(present, 1);
    assert!(max > 100 && sum >= max);
}

/// Equal contents are not one population: opening the same `(rows, seed)`
/// twice builds two resources with distinct identities, and a mask over one
/// is foreign to the other. There is no export that returns a second handle
/// to an existing resource, so two handles onto one population cannot be
/// constructed.
#[test]
fn equal_contents_are_not_one_population() {
    let (first, second) = (store(N, 5), store(N, 5));
    assert_ne!(first, second);
    let mask_of_first = mask_over(first);
    assert_eq!(lgj_op_eq_classid(first, 0, 3, mask_of_first), LGJ_OK);
    assert_eq!(lgj_op_eq_classid(second, 0, 3, mask_of_first), LEN);

    let (p1, p2) = (pattern(N, 5), pattern(N, 5));
    let mask_of_p1 = mask_over(p1);
    let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND)];
    let mut c = 0u64;
    assert_eq!(
        call::plan_eval(p1, ops.as_ptr(), 1, mask_of_p1, &mut c),
        LGJ_OK
    );
    assert_eq!(
        call::plan_eval(p2, ops.as_ptr(), 1, mask_of_p1, &mut c),
        LEN
    );
}

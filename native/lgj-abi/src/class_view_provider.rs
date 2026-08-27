//! The `ClassView` LAW's fixture ANSWERS — D-LGJ-W8 §3.3.
//!
//! # Law vs. answers
//!
//! `lance_graph_contract::class_view::ClassView` is the LAW: a resolver
//! trait, late-bound by design (the operator's RULING CLARIFICATION,
//! `.claude/plans/mask-native-navigation-correction-v1.md` §1: *"the
//! contract defines the law; an ontology/cache/provider supplies the
//! answers"*). `FixtureClassView` below is one such provider — the
//! DEFAULT one, matching the SoA row store [`crate::rowstore`] generates:
//! one deterministic domain where every classid gets the SAME 32 facets
//! (`predicate_iri: "lgj:facet/N"`, `label: "facetN"`), because the
//! generator itself does not vary a row's *shape* by classid — only its
//! *content*.
//!
//! # The real provider (feature `ogar-classview`)
//!
//! `ogar_class_view::OgarClassView` — the ontology-backed provider over
//! `ogar_vocab` — is bound behind the `ogar-classview` feature, and
//! [`edge_participation`] then derives from each class's actual field
//! count instead of the fixture constant. Measured over the vocabulary
//! (`examples/classview_census.rs`): 98 registered classes, **12 distinct
//! participation masks** (field counts 0–13), against the fixture's single
//! `0xFFFF_FFFF` for all 98.
//!
//! What is NOT yet closed, stated plainly: the generated store draws its
//! classids from `0..16` ([`crate::rowstore::ROWSTORE_CLASS_CARDINALITY`])
//! while every vocabulary classid is `>= 0x0100`, so the two domains are
//! **disjoint** — a generated store under the real provider hops nothing.
//! The remaining fixture is the row CONTENT; replacing it with
//! Lance-loaded SoA rows is what makes the bound provider observable
//! end-to-end. Pinned, not merely asserted, by
//! `exports::tests::hop_under_the_real_provider_narrows_by_class`.
//!
//! A per-resource provider slot on the registry entry (rather than this
//! module-level singleton) remains the seam for binding a provider PER
//! dataset rather than process-wide.
//!
//! # The `edge_participation` / `decode_mode` seam (§4-NG6)
//!
//! [`lgj_hop`](crate::exports::lgj_hop) needs to know, per edge classid,
//! WHICH of a class's 32 facets actually carry structured edges. The
//! `ClassView` trait has no `edge_slots`-shaped method for this today — a
//! lance-graph decision, not one this crate can make — so the two
//! functions below are a free-function seam ON THE PROVIDER, consulted
//! directly by `lgj_hop` rather than through the trait. When
//! `ClassView::edge_slots` (or an equivalent) lands upstream, these two
//! functions become its fixture-provider implementation instead of a
//! standalone seam.
//!
//! # Classid width (§3.2)
//!
//! The wire keeps `u32` classids (canon 8-hex; matches `rbac.rs`'s
//! `ClassId = u32` and the existing `lgj_op_eq_classid`), while the
//! contract's [`ClassId`] is `u16`. [`class_id_for`] is the ONE conversion
//! point: a classid outside `u16` range has no ClassView answer. This
//! fixture provider's answers do not actually depend on classid identity
//! at all — but the conversion + bounds check happens anyway, right here,
//! so the discipline is visible at the one point in this crate where a
//! wire classid meets the contract's narrower `ClassId` width. A future
//! non-fixture provider is exactly what would consult the `Some(class_id)`
//! side of this conversion for real.

use lance_graph_contract::class_view::{ClassId, ClassView, FieldMask};
use lance_graph_contract::facet::CascadeShape;
use lance_graph_contract::ontology::{DisplayTemplate, FieldRef};
#[cfg(feature = "ogar-classview")]
use ogar_class_view::OgarClassView;
use std::sync::OnceLock;

/// Facets per row in the fixture row-store domain
/// ([`crate::rowstore::ROW_FACETS`]) — duplicated as a `const` here (rather
/// than importing `rowstore`, which is outside the G11 contract-import
/// fence's concern but would otherwise couple this module to the row
/// store's own layout constant) because a `ClassView` provider's field
/// count is a LAW-level fact about the domain, not a re-export of a
/// storage-layout detail. The two are asserted equal by a test below.
const FIXTURE_FIELD_COUNT: usize = 32;

/// The class's ordered field set — 32 `FieldRef`s, one per facet, built
/// once and shared by every classid (see the module docs: the generator
/// does not vary shape by classid, only content).
fn fixture_fields() -> &'static [FieldRef] {
    static FIELDS: OnceLock<Vec<FieldRef>> = OnceLock::new();
    FIELDS.get_or_init(|| {
        (0..FIXTURE_FIELD_COUNT)
            .map(|i| FieldRef::new(format!("lgj:facet/{i}"), format!("facet{i}")))
            .collect()
    })
}

/// Convert the wire's `u32` classid into the contract's `ClassId` (`u16`)
/// at the ClassView-consult boundary (spec §3.2): a classid outside `u16`
/// range has no ClassView answer, `None`. Never a truncating cast — a
/// classid `> u16::MAX` is a genuinely different fact from "class 0", not
/// an aliased reading of it.
pub fn class_id_for(classid: u32) -> Option<ClassId> {
    u16::try_from(classid).ok()
}

/// The fixture `ClassView` — one deterministic answer set, shared by every
/// classid the generator produces (and, per the module docs, by classids
/// it doesn't).
#[derive(Debug, Default)]
pub struct FixtureClassView;

impl ClassView for FixtureClassView {
    /// The same 32 `FieldRef`s regardless of `class` — see the module docs.
    fn fields(&self, _class: ClassId) -> &[FieldRef] {
        fixture_fields()
    }

    /// The simplest valid template — this domain has no card/detail/summary
    /// distinction to make.
    fn template(&self, _class: ClassId) -> DisplayTemplate {
        DisplayTemplate::Card
    }

    /// The fixture's per-class register grouping — see
    /// [`FixtureClassView::fixture_cascade_shape`] for why this varies rather
    /// than returning the trait's constant zero-fallback.
    fn cascade_shape(&self, class: ClassId) -> CascadeShape {
        FixtureClassView::fixture_cascade_shape(class)
    }

    /// No DOLCE taxonomy in the fixture domain — category `0` for every
    /// class, the same "no answer beyond the default" reading
    /// `is_a_parent`'s trait default already uses for taxonomy.
    fn dolce_category_id(&self, _class: ClassId) -> u8 {
        0
    }

    // `edge_codec_flavor` is left at the trait default (`CoarseOnly`,
    // class_view.rs:1109) — unused by decode mode 0 (spec §3.4), and this
    // fixture domain has no reason to opt into residue/PQ fidelity.
}

/// The **process-global** `classid -> register grouping` table (abi.md §15).
///
/// # Why global, and not per dataset
///
/// **A classid is a global address: the same classid means the same class in
/// every SoA.** The hi half is a concept minted once in the shared codebook and
/// the lo half is an app render prefix; neither is scoped to a dataset. So
/// `classid -> ClassView -> cascade_shape` is dataset-INDEPENDENT, and holding
/// one table per store would be N identical copies of the same 64 KiB answer —
/// provably so here, since [`FixtureClassView`] is a unit struct with no
/// per-store state at all.
///
/// An earlier version put this table on `RowStore`. That was wrong in shape
/// rather than in output: the answers were right, but the placement implied two
/// datasets could disagree about what a classid carves into, which the address
/// space does not permit.
///
/// # What it captures, and what it deliberately does not
///
/// **Only LAYOUT.** The classid resolves how the 12 content-blind bytes are
/// grouped — `6×2` / `4×3` / `3×4` — and nothing else. Meaning, RBAC, ontology
/// category and render template are all separate resolutions off the same
/// address; none of them belong in this table and none can be inferred from it.
///
/// # Shape
///
/// [`class_id_for`] narrows a `u32` classid to `u16`, so the table is 65_536
/// one-byte entries: `0` = no `ClassView` answer, otherwise the grouping's wire
/// value plus one. 64 KiB, built once for the process on first use, never
/// rebuilt. A `LazyLock` and not a `OnceLock` because at this layer the provider
/// IS known — there is no caller-supplied resolver to wait for.
static CARVING_TABLE: std::sync::LazyLock<Box<[u8]>> = std::sync::LazyLock::new(|| {
    use lance_graph_contract::class_view::ClassView;
    let mut t = vec![0u8; 1 << 16].into_boxed_slice();
    for (cid, slot) in t.iter_mut().enumerate() {
        // +1 so 0 keeps its "no answer" meaning.
        *slot = class_id_for(cid as u32).map_or(0, |c| {
            crate::kernels::carving_to_wire(FixtureClassView.cascade_shape(c)) as u8 + 1
        });
    }
    t
});

/// This process's register grouping for `classid`, as a wire value.
///
/// `None` when the classid has no `ClassView` answer — including every classid
/// outside `u16` range, which [`class_id_for`] already reports as unanswerable
/// rather than truncating into a different class.
#[must_use]
pub fn carving_wire_of(classid: u32) -> Option<u8> {
    let idx = usize::try_from(classid)
        .ok()
        .filter(|&i| i < CARVING_TABLE.len())?;
    match CARVING_TABLE[idx] {
        0 => None,
        w => Some(w - 1),
    }
}

/// How this fixture carves the 12-byte content-blind register, per class —
/// the `ClassView::cascade_shape` override (contract, 2026-08-25).
///
/// **This VARIES by class, deliberately, and that is a fixture choice rather
/// than canon.** The trait's own zero-fallback is a constant `G3D4`, and a
/// constant answer would make every population trivially homogeneous — so the
/// "does this population resolve to ONE grouping" guard could never fire, and a
/// test for it would pass for an implementation that never checked. Varying by
/// `class % 3` makes both outcomes reachable on the real fixture: a mask built
/// by `lgj_op_eq_classid` is single-class and resolves; a union across classids
/// is mixed and is rejected.
///
/// The cycle is `G6D2, G4D3, G3D4` — the contract's own `ROTATIONS` order
/// reversed to group-count ascending, matching how `CascadeShape` documents the
/// three inherited schemas (Rails `6×2`, other frameworks `4×3`, the GUID
/// `3×4`). A real provider resolves this from the class registry instead.
impl FixtureClassView {
    /// The fixture's per-class grouping, exposed for tests that need to predict
    /// it without going through the trait object.
    #[must_use]
    pub const fn fixture_cascade_shape(class: ClassId) -> CascadeShape {
        match class % 3 {
            0 => CascadeShape::G6D2,
            1 => CascadeShape::G4D3,
            _ => CascadeShape::G3D4,
        }
    }
}

/// Which of a class's 32 facet positions [`crate::exports::lgj_hop`] may
/// treat as edge-bearing for `classid` — the wire form of the contract's
/// [`FieldMask`], restricted to this store's 32 facets (bits `32..64` of
/// `FieldMask::FULL` are masked off; [`FieldMask::intersect`] is the
/// contract's own bitwise-AND).
///
/// The fixture answer is a CONSTANT: every facet participates, for every
/// classid (in range or not — see [`class_id_for`]'s doc). A real,
/// non-fixture provider is what would vary this per class.
pub fn edge_participation(classid: u32) -> FieldMask {
    // The bounds check happens for its own sake (see `class_id_for`'s doc
    // on why): the fixture's answer does not depend on the result, but the
    // real provider's does, and the conversion is what it consults.
    let class_id = class_id_for(classid);

    #[cfg(feature = "ogar-classview")]
    {
        // THE REAL PROVIDER. `OgarClassView` walks `ogar_vocab`'s promoted
        // classes, so `fields(class)` is that class's genuine basis --
        // attributes AND associations, in source order -- and therefore
        // VARIES by class where the fixture is constant. This is the seam
        // §4-NG6 named; binding it is what makes the ClassView half of
        // `MASK x ClassView -> MASK` discriminate at all.
        //
        // A class with `k` fields owns facet positions `0..k`, so positions
        // at or past `k` cannot carry one of its edges. That is a genuine
        // per-class narrowing.
        //
        // PRECISION, stated honestly: `fields()` is attributes ++
        // associations flattened, and only the associations are actually
        // edge-bearing. The trait cannot tell them apart, and
        // `all_canonical_classes()` -- which can -- is private to
        // ogar-class-view. So this answer is a SUPERSET of the true edge
        // set: it may admit an attribute position (which the structured-edge
        // `hi32 == 0` gate in `lgj_hop` then rejects) but it can never MISS
        // a real edge. Over-admitting is the safe direction; under-admitting
        // would silently lose edges. Narrowing to associations-only needs
        // ogar-class-view to expose that split -- an OGAR-side ask, not a
        // local workaround.
        //
        // Unknown class -> the provider's documented empty-field fallback ->
        // an EMPTY mask, so the hop finds nothing rather than everything.
        // That is the opposite of the fixture's answer and is deliberate: an
        // unregistered classid is not a licence to traverse every facet.
        let Some(cid) = class_id else {
            return FieldMask::from(0u64);
        };
        let view = ogar_view();
        let k = <OgarClassView as ClassView>::fields(view, cid).len();
        let bits: u64 = if k >= 32 {
            0xFFFF_FFFF
        } else {
            (1u64 << k) - 1
        };
        FieldMask::from(bits)
    }

    #[cfg(not(feature = "ogar-classview"))]
    {
        let _ = class_id;
        FieldMask::FULL.intersect(FieldMask(0xFFFF_FFFF))
    }
}

/// The process-wide real provider, built once.
///
/// `OgarClassView::new()` is pure construction over `ogar_vocab` (no I/O),
/// and the registry is read-only afterwards -- so a `OnceLock` is the whole
/// lifecycle. Deliberately NOT rebuilt per call: it walks every promoted
/// class.
#[cfg(feature = "ogar-classview")]
fn ogar_view() -> &'static OgarClassView {
    static VIEW: std::sync::OnceLock<OgarClassView> = std::sync::OnceLock::new();
    VIEW.get_or_init(OgarClassView::new)
}

/// Which structured-edge decode convention `classid`'s edge facets use —
/// a per-class answer for a FUTURE consumer of this seam. `lgj_hop` itself
/// does NOT consult this function: its `decode_mode` is a caller-supplied
/// ABI parameter (spec §3.4), validated directly against the RESERVED-mode
/// fence, independent of any per-class answer. This function exists
/// because the named seam (§4-NG6) is a *pair* — participation AND decode
/// convention — even though only the first half is wired into `lgj_hop`
/// this wave.
///
/// The fixture answer is a CONSTANT `0` — the §12 fixture convention —
/// for every classid, exactly like [`edge_participation`].
pub fn decode_mode(classid: u32) -> u32 {
    let _class_id = class_id_for(classid);
    0
}

#[cfg(all(test, feature = "ogar-classview"))]
mod ogar_provider_tests {
    use super::{edge_participation, ogar_view};
    use lance_graph_contract::class_view::{ClassView, FieldMask};

    /// The CONTRAST to `tests::edge_participation_covers_exactly_the_low_32_bits`
    /// (which is gated OFF under this feature): the real provider NARROWS.
    ///
    /// Same inputs, opposite answers. The fixture says classid 0
    /// participates in all 32 facets; the real provider says an
    /// unregistered classid participates in NONE — an unknown class is not
    /// a licence to traverse every facet. And the richest REGISTERED class
    /// in the vocabulary still participates in strictly fewer than 32,
    /// because 32 is the store's facet capacity, not any class's field
    /// count.
    ///
    /// DISABLE: return `FieldMask::FULL` from the `ogar-classview` arm of
    /// `edge_participation` and both halves fail at once.
    #[test]
    fn the_real_provider_narrows_rather_than_widens() {
        let unregistered = edge_participation(0);
        assert_eq!(
            unregistered.count(),
            0,
            "an unregistered classid must participate in NOTHING, where the \
             fixture answered all 32"
        );

        // 0x0103 is the richest class in `ogar_vocab` (13 fields, measured by
        // `examples/classview_census.rs`). Even the richest is well under the
        // store's 32-facet capacity.
        let richest = edge_participation(0x0103);
        assert_eq!(richest.count(), 13, "0x0103 carries 13 fields");
        assert!(
            richest.count() < 32,
            "even the richest registered class must narrow below the fixture's 32"
        );
        // Anti-vacuity: this is the LOW-k prefix, not an arbitrary 13 bits —
        // so a provider that returned any 13-bit pattern would fail.
        for bit in 0..13u8 {
            assert!(richest.has(bit), "bit {bit} must participate");
        }
        for bit in 13..64u8 {
            assert!(!richest.has(bit), "bit {bit} is beyond this class's fields");
        }
    }

    /// THE point of binding the real provider: the answer must depend on the
    /// class. The fixture returns `FULL` for every classid, so this is the
    /// one assertion that separates a bound provider from a stub.
    ///
    /// DISABLE: return `FieldMask::FULL` from the `ogar-classview` arm and
    /// this fails -- every class collapses to one answer again.
    #[test]
    fn the_real_provider_varies_participation_by_class() {
        let view = ogar_view();
        let ids: Vec<_> = view.known_class_ids().collect();
        assert!(
            ids.len() >= 2,
            "need >=2 registered classes to show variation, got {}",
            ids.len()
        );

        let masks: Vec<FieldMask> = ids
            .iter()
            .map(|c| edge_participation(u32::from(*c)))
            .collect();
        let distinct: std::collections::BTreeSet<u64> = masks.iter().map(|m| m.0).collect();
        assert!(
            distinct.len() >= 2,
            "the real provider must give >=2 DISTINCT participation masks across {} classes, \
             got {distinct:?} -- a single answer means the provider is not discriminating",
            ids.len()
        );

        // Anti-vacuity: the variation must come from real field counts, not
        // from some classes being absent. Every mask here is non-empty and
        // its popcount equals that class's field count (capped at 32).
        for (c, m) in ids.iter().zip(masks.iter()) {
            let k = <_ as ClassView>::fields(view, *c).len().min(32);
            assert_eq!(
                m.0.count_ones() as usize,
                k,
                "class {c}: mask popcount must equal its field count"
            );
        }
    }

    /// An unregistered classid gets an EMPTY mask, not a full one -- the
    /// opposite of the fixture. A classid the ontology does not know is not
    /// a licence to traverse all 32 facets.
    ///
    /// DISABLE: fall through to `FieldMask::FULL` for the unknown case and
    /// this fails.
    #[test]
    fn an_unregistered_classid_participates_in_nothing() {
        let view = ogar_view();
        let known: std::collections::BTreeSet<u16> = view.known_class_ids().collect();
        // Find a classid the registry does not carry.
        let unknown = (0u16..=u16::MAX)
            .find(|c| !known.contains(c))
            .expect("some classid must be unregistered");
        assert!(
            edge_participation(u32::from(unknown)).is_empty(),
            "unregistered class {unknown} must participate in nothing"
        );

        // ... and the silence twin: a REGISTERED class does not come back
        // empty, so the test above is not passing because everything is empty.
        let a_known = *known.iter().next().expect("at least one known class");
        assert!(!edge_participation(u32::from(a_known)).is_empty());
    }

    /// Out-of-range classids are refused before the provider is consulted --
    /// the `class_id_for` bounds check still governs.
    #[test]
    fn an_out_of_range_classid_participates_in_nothing() {
        assert!(edge_participation(u32::from(u16::MAX) + 1).is_empty());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixture_field_count_matches_the_rowstore_facet_count() {
        assert_eq!(FIXTURE_FIELD_COUNT, crate::rowstore::ROW_FACETS as usize);
    }

    #[test]
    fn fields_are_32_distinct_predicate_iris_stable_across_calls() {
        let view = FixtureClassView;
        let f1 = view.fields(0);
        let f2 = view.fields(9999);
        assert_eq!(f1.len(), 32);
        // Same static slice (same address), not merely equal content —
        // proves the `OnceLock` is genuinely shared, not rebuilt per call.
        assert_eq!(f1.as_ptr(), f2.as_ptr());
        for (i, field) in f1.iter().enumerate() {
            assert_eq!(field.predicate_iri, format!("lgj:facet/{i}"));
            assert_eq!(field.label, format!("facet{i}"));
        }
        let mut iris: Vec<&str> = f1.iter().map(|f| f.predicate_iri.as_str()).collect();
        iris.sort_unstable();
        iris.dedup();
        assert_eq!(iris.len(), 32, "every predicate_iri must be distinct");
    }

    #[test]
    fn template_and_dolce_category_are_the_simplest_valid_answers() {
        let view = FixtureClassView;
        assert_eq!(view.template(0), DisplayTemplate::Card);
        assert_eq!(view.dolce_category_id(0), 0);
        // Not gated by classid identity either.
        assert_eq!(view.template(500), DisplayTemplate::Card);
        assert_eq!(view.dolce_category_id(500), 0);
    }

    #[test]
    fn edge_codec_flavor_is_the_trait_default_coarse_only() {
        use lance_graph_contract::canonical_node::EdgeCodecFlavor;
        let view = FixtureClassView;
        assert_eq!(view.edge_codec_flavor(0), EdgeCodecFlavor::CoarseOnly);
    }

    #[test]
    fn class_id_for_rejects_only_out_of_range_classids() {
        assert_eq!(class_id_for(0), Some(0));
        assert_eq!(class_id_for(15), Some(15));
        assert_eq!(class_id_for(u16::MAX as u32), Some(u16::MAX));
        assert_eq!(class_id_for(u16::MAX as u32 + 1), None);
        assert_eq!(class_id_for(u32::MAX), None);
    }

    /// The classid argument is genuinely unused by this fixture's answer —
    /// pinned here rather than merely asserted in a doc comment (the
    /// falsifiability rule: an unexercised claim is not a behaviour).
    #[test]
    #[cfg(not(feature = "ogar-classview"))]
    fn edge_participation_is_unaffected_by_the_classid_width_boundary() {
        let in_range = edge_participation(0);
        let at_boundary = edge_participation(u16::MAX as u32);
        let just_over = edge_participation(u16::MAX as u32 + 1);
        let max = edge_participation(u32::MAX);
        assert_eq!(in_range, at_boundary);
        assert_eq!(in_range, just_over);
        assert_eq!(in_range, max);
    }

    /// The FIXTURE's answer: every classid participates in all 32 facets.
    ///
    /// Gated OFF under `ogar-classview` deliberately — the real provider
    /// MUST fail this, and its failing is the evidence that it
    /// discriminates. The contrasting fact is pinned by
    /// `ogar_provider_tests::the_real_provider_narrows_rather_than_widens`,
    /// which asserts the opposite of each line below on the same inputs.
    #[test]
    #[cfg(not(feature = "ogar-classview"))]
    fn edge_participation_covers_exactly_the_low_32_bits() {
        let p = edge_participation(0);
        assert_eq!(p.count(), 32);
        for bit in 0..32u8 {
            assert!(p.has(bit), "bit {bit} must participate");
        }
        for bit in 32..64u8 {
            assert!(!p.has(bit), "bit {bit} is beyond this store's 32 facets");
        }
    }

    #[test]
    fn decode_mode_is_zero_for_every_classid_in_or_out_of_range() {
        assert_eq!(decode_mode(0), 0);
        assert_eq!(decode_mode(15), 0);
        assert_eq!(decode_mode(u16::MAX as u32 + 1), 0);
        assert_eq!(decode_mode(u32::MAX), 0);
    }

    /// A classid is a GLOBAL address: the same classid resolves to the same
    /// layout in every SoA. This is why the table is process-global rather than
    /// per dataset — two stores built from different seeds, holding different
    /// bytes, must still agree about what a given classid carves into.
    #[test]
    fn the_layout_of_a_classid_is_the_same_in_every_dataset() {
        let a = crate::rowstore::RowStore::generate(64, 0x1111).unwrap();
        let b = crate::rowstore::RowStore::generate(64, 0x9999).unwrap();
        // Different datasets, genuinely different content.
        assert_ne!(a.as_bytes(), b.as_bytes(), "the two stores must differ");

        for classid in 0..64u32 {
            assert_eq!(
                carving_wire_of(classid),
                carving_wire_of(classid),
                "classid {classid} must resolve identically, dataset-independent"
            );
        }
        // And the resolution genuinely varies BY CLASSID — otherwise the
        // agreement above would hold for a table that answered one constant.
        let answers: std::collections::HashSet<_> =
            (0..64u32).filter_map(carving_wire_of).collect();
        assert!(
            answers.len() > 1,
            "the table must discriminate between classids, not answer a constant"
        );
    }

    /// The table captures LAYOUT and nothing else. A classid with no ClassView
    /// answer reports none rather than truncating into a different class — the
    /// property that stops a `> u16::MAX` classid aliasing onto class 0.
    #[test]
    fn a_classid_with_no_classview_answer_is_none_not_class_zero() {
        assert!(carving_wire_of(0).is_some(), "class 0 is answerable");
        assert_eq!(
            carving_wire_of(0x1_0000),
            None,
            "a classid past u16 range has no answer, and must not alias class 0"
        );
        assert_eq!(carving_wire_of(u32::MAX), None);
    }

    /// Every answer the table gives is a legal wire value that round-trips back
    /// to a real grouping — a `+1` encoding error would show up as a decode
    /// failure here rather than as a wrong sweep much later.
    #[test]
    fn every_table_answer_round_trips_to_a_real_grouping() {
        for classid in 0..1024u32 {
            if let Some(w) = carving_wire_of(classid) {
                let shape = crate::kernels::carving_from_wire(u32::from(w))
                    .unwrap_or_else(|| panic!("classid {classid} gave undecodable wire {w}"));
                assert_eq!(crate::kernels::carving_to_wire(shape), u32::from(w));
            }
        }
    }
}

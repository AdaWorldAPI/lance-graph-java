//! The `ClassView` LAW's fixture ANSWERS — D-LGJ-W8 §3.3.
//!
//! # Law vs. answers
//!
//! `lance_graph_contract::class_view::ClassView` is the LAW: a resolver
//! trait, late-bound by design (the operator's RULING CLARIFICATION,
//! `.claude/plans/mask-native-navigation-correction-v1.md` §1: *"the
//! contract defines the law; an ontology/cache/provider supplies the
//! answers"*). `FixtureClassView` below is one such provider — the ONLY
//! one this crate needs, because the whole SoA row store [`crate::rowstore`]
//! generates is one deterministic domain: every classid gets the SAME 32
//! facets (`predicate_iri: "lgj:facet/N"`, `label: "facetN"`), because the
//! generator itself does not vary a row's *shape* by classid — only its
//! *content*.
//!
//! A real ontology/cache provider (a future, non-fixture `ClassView` impl)
//! is a NAMED SEAM, not a gap this module tries to fill: see the trait
//! itself for the shape a real provider would fill in, and
//! `.claude/plans/mask-native-navigation-correction-v1.md` §4-NG3 for why
//! it stays out of scope here. A per-resource provider slot on the
//! registry entry (rather than this module-level singleton) is the seam
//! for wiring one in.
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
use lance_graph_contract::ontology::{DisplayTemplate, FieldRef};
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
    // on why): this fixture's answer does not depend on the result, but a
    // real provider's would, and the conversion is what it would consult.
    let _class_id = class_id_for(classid);
    FieldMask::FULL.intersect(FieldMask(0xFFFF_FFFF))
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
    fn edge_participation_is_unaffected_by_the_classid_width_boundary() {
        let in_range = edge_participation(0);
        let at_boundary = edge_participation(u16::MAX as u32);
        let just_over = edge_participation(u16::MAX as u32 + 1);
        let max = edge_participation(u32::MAX);
        assert_eq!(in_range, at_boundary);
        assert_eq!(in_range, just_over);
        assert_eq!(in_range, max);
    }

    #[test]
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
}

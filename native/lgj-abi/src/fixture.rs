//! The generic SoA fixture — three lanes, allocated once, never moved.
//!
//! "Generic is not toy": the *shape* under test is struct-of-arrays with a
//! packed-bit selection mask, which is exactly the shape `lance-graph`'s
//! `NodeRow` / `WideFieldMask` present. Wiring those concrete types in is a
//! later slice; nothing about the membrane changes when it happens.
//!
//! # The hard allocation guarantee (abi.md §4)
//!
//! Lanes are allocated once in [`Fixture::generate`] and are never reallocated,
//! resized, or moved while the resource is alive. Each lane is a
//! `Box<[T]>` — the heap buffer's address is fixed for the box's whole life, so
//! a `MemorySegment` Java built from a [`crate::abi::LgjLaneDesc`] stays valid
//! until `lgj_close`. Any future growable lane requires an ABI **major** bump.

use crate::abi::{LgjElemKind, LGJ_FLAG_CONTIGUOUS, LGJ_FLAG_READABLE};

// Lane ids for a pattern resource (abi.md §7).
/// Lane 0 — entity ids, `U64`.
pub const LANE_IDS: u32 = 0;
/// Lane 1 — class tags, `U32`.
pub const LANE_CLASSES: u32 = 1;
/// Lane 2 — signed measurements, `I32`.
pub const LANE_VALUES: u32 = 2;
/// Number of lanes a pattern exposes.
pub const PATTERN_LANE_COUNT: u32 = 3;

/// Number of distinct `class` values the generator produces: `0..16`.
pub const CLASS_CARDINALITY: u64 = 16;
/// Span of the `value` distribution before the shift below.
pub const VALUE_SPAN: i64 = 512;
/// Amount subtracted from the raw span, which is what puts negatives in range.
pub const VALUE_BIAS: i64 = 150;

/// SplitMix64 — the reference generator, hand-written so there is no dependency
/// and no ambiguity about which variant is meant.
///
/// Exactly the published constants: increment `0x9E3779B97F4A7C15`, mixers
/// `0xBF58476D1CE4E5B9` and `0x94D049BB133111EB`, shifts 30/27/31. All
/// arithmetic is wrapping (i.e. mod 2^64), which is what Java's `long` does
/// natively — so a Java re-implementation is a transcription, not a port.
#[derive(Debug, Clone)]
pub struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    /// Seed the generator. No warm-up draws — the first `next_u64` already
    /// advances the state, exactly as the reference does.
    pub const fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Advance and return the next 64 bits.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
}

/// Three lanes of `n_rows` elements each, generated deterministically from a
/// seed.
///
/// # The generation algorithm — NORMATIVE
///
/// The Java-side test recomputes expected counts from this description alone,
/// with no data file, which makes it a genuine cross-language parity check. So
/// this is a contract, not an implementation detail:
///
/// ```text
/// rng = SplitMix64(seed)                     // state = seed, no warm-up draws
/// for i in 0 .. n_rows:                      // ascending, one row at a time
///     a = rng.next_u64()                     // FIRST draw of the row
///     b = rng.next_u64()                     // SECOND draw of the row
///     ids[i]     = i                              as u64   // dense, 0-based
///     classes[i] = ((a >>> 33) & 0xF)             as u32   // 0 ..= 15
///     values[i]  = (((b >>> 40) & 0x1FF) - 150)   as i32   // -150 ..= 361
/// ```
///
/// Two draws per row, `a` before `b`, is part of the contract: swapping them or
/// drawing one `u64` and slicing it would produce different lanes for the same
/// seed and silently break Java's independently-computed expectations.
///
/// Why these ranges:
/// - `classes` spans 16 values, so `class == 7` selects ≈ 1/16 of the rows —
///   a meaningful fraction, neither "everything" nor "nothing" (a predicate
///   that matches all or no rows cannot falsify a narrowing property).
/// - `values` spans `-150 ..= 361`, which *straddles* the threshold 100 used
///   throughout the tests and includes negatives — so `> 100` is a real signed
///   comparison, and an implementation that compared unsigned by mistake would
///   be caught rather than accidentally agreeing.
///
/// `ids` is deliberately the dense row index: it is the join key, and making it
/// trivially predictable means a Java test can check *lane addressing itself*
/// without also having to model the PRNG.
///
/// `Debug` deliberately prints only the shape, never the lanes: a 64,000-row
/// dump in a failing assertion is noise, not evidence.
pub struct Fixture {
    /// Logical row count; every lane has exactly this many elements.
    pub n_rows: u64,
    /// The seed these lanes were generated from.
    pub seed: u64,
    ids: Box<[u64]>,
    classes: Box<[u32]>,
    values: Box<[i32]>,
}

impl std::fmt::Debug for Fixture {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Fixture")
            .field("n_rows", &self.n_rows)
            .field("seed", &self.seed)
            .finish_non_exhaustive()
    }
}

impl Fixture {
    /// Build the fixture. Allocates each lane exactly once.
    ///
    /// Returns `None` if `n_rows` exceeds what can be indexed on this target
    /// (the caller maps that to `LENGTH_OVERFLOW`).
    pub fn generate(n_rows: u64, seed: u64) -> Option<Self> {
        let n = usize::try_from(n_rows).ok()?;
        // Reject anything whose byte length would overflow: the widest lane is
        // 8 bytes/elem, and masks add 8 bytes per 64 rows on top.
        n.checked_mul(8)?;

        let mut rng = SplitMix64::new(seed);
        let mut ids = Vec::new();
        let mut classes = Vec::new();
        let mut values = Vec::new();
        ids.try_reserve_exact(n).ok()?;
        classes.try_reserve_exact(n).ok()?;
        values.try_reserve_exact(n).ok()?;

        for i in 0..n {
            let a = rng.next_u64();
            let b = rng.next_u64();
            ids.push(i as u64);
            classes.push(((a >> 33) & (CLASS_CARDINALITY - 1)) as u32);
            values.push((((b >> 40) & (VALUE_SPAN as u64 - 1)) as i64 - VALUE_BIAS) as i32);
        }

        Some(Self {
            n_rows,
            seed,
            ids: ids.into_boxed_slice(),
            classes: classes.into_boxed_slice(),
            values: values.into_boxed_slice(),
        })
    }

    /// Lane 0 — the dense row index.
    pub fn ids(&self) -> &[u64] {
        &self.ids
    }
    /// Lane 1 — class tags, `0 ..= 15`.
    pub fn classes(&self) -> &[u32] {
        &self.classes
    }
    /// Lane 2 — signed values, `-150 ..= 361`.
    pub fn values(&self) -> &[i32] {
        &self.values
    }

    /// `(base address, element count, element kind)` for a lane id, or `None`
    /// if the id is out of range (⇒ `INVALID_LANE`).
    ///
    /// The address is taken from the boxed slice, whose buffer never moves — see
    /// this module's header.
    pub fn lane_raw(&self, lane_id: u32) -> Option<(u64, u64, LgjElemKind)> {
        match lane_id {
            LANE_IDS => Some((
                self.ids.as_ptr() as u64,
                self.ids.len() as u64,
                LgjElemKind::U64,
            )),
            LANE_CLASSES => Some((
                self.classes.as_ptr() as u64,
                self.classes.len() as u64,
                LgjElemKind::U32,
            )),
            LANE_VALUES => Some((
                self.values.as_ptr() as u64,
                self.values.len() as u64,
                LgjElemKind::I32,
            )),
            _ => None,
        }
    }

    /// Flags every pattern lane carries: readable + contiguous, and **never**
    /// writable (abi.md §7 — pattern lanes are read-only).
    pub const fn lane_flags() -> u32 {
        LGJ_FLAG_READABLE | LGJ_FLAG_CONTIGUOUS
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The published SplitMix64 test vector for seed 0 — proves this is the
    /// real generator and not a lookalike, which is what makes the Java
    /// transcription checkable.
    #[test]
    fn splitmix64_matches_the_reference_vector() {
        let mut r = SplitMix64::new(0);
        assert_eq!(r.next_u64(), 0xE220_A839_7B1D_CDAF);
        assert_eq!(r.next_u64(), 0x6E78_9E6A_A1B9_65F4);
        assert_eq!(r.next_u64(), 0x06C4_5D18_8009_454F);
    }

    #[test]
    fn generation_is_deterministic_for_a_seed() {
        let a = Fixture::generate(1000, 42).unwrap();
        let b = Fixture::generate(1000, 42).unwrap();
        assert_eq!(a.classes(), b.classes());
        assert_eq!(a.values(), b.values());
    }

    #[test]
    fn different_seeds_produce_different_lanes() {
        let a = Fixture::generate(1000, 1).unwrap();
        let b = Fixture::generate(1000, 2).unwrap();
        assert_ne!(a.values(), b.values());
    }

    #[test]
    fn lanes_have_the_documented_shape() {
        let f = Fixture::generate(4096, 7).unwrap();
        assert_eq!(f.ids().len(), 4096);
        // ids are the dense row index.
        assert!(f.ids().iter().enumerate().all(|(i, &v)| v == i as u64));
        // classes span exactly 0..16.
        assert!(f.classes().iter().all(|&c| c < 16));
        // and actually *use* the range — a constant lane would make every
        // class predicate vacuous.
        let distinct = {
            let mut seen = [false; 16];
            for &c in f.classes() {
                seen[c as usize] = true;
            }
            seen.iter().filter(|&&s| s).count()
        };
        assert_eq!(distinct, 16, "all 16 classes must occur at n=4096");
        // values straddle the test threshold and include negatives.
        assert!(f.values().iter().all(|&v| (-150..=361).contains(&v)));
        assert!(f.values().iter().any(|&v| v < 0), "negatives must occur");
        assert!(f.values().iter().any(|&v| v > 100));
        assert!(f.values().iter().any(|&v| v <= 100));
    }

    /// A predicate that matches everything or nothing cannot falsify anything,
    /// so pin that the fixture's headline predicates select a *middling*
    /// fraction.
    #[test]
    fn headline_predicates_select_a_meaningful_fraction() {
        let f = Fixture::generate(64_000, 0xABCD).unwrap();
        let n = f.n_rows as f64;
        let c7 = f.classes().iter().filter(|&&c| c == 7).count() as f64 / n;
        let gt = f.values().iter().filter(|&&v| v > 100).count() as f64 / n;
        assert!((0.04..0.09).contains(&c7), "class==7 fraction was {c7}");
        assert!((0.35..0.65).contains(&gt), "value>100 fraction was {gt}");
    }

    #[test]
    fn zero_rows_is_legal_and_empty() {
        let f = Fixture::generate(0, 1).unwrap();
        assert_eq!(f.ids().len(), 0);
        assert_eq!(f.lane_raw(0).unwrap().1, 0);
    }

    #[test]
    fn lane_ids_beyond_the_last_are_rejected() {
        let f = Fixture::generate(8, 1).unwrap();
        assert!(f.lane_raw(0).is_some());
        assert!(f.lane_raw(2).is_some());
        assert!(f.lane_raw(3).is_none());
        assert!(f.lane_raw(u32::MAX).is_none());
    }

    /// The allocation-stability guarantee, checked rather than asserted in prose:
    /// a lane's base address does not change across repeated describes.
    #[test]
    fn lane_addresses_are_stable() {
        let f = Fixture::generate(1024, 3).unwrap();
        let first = f.lane_raw(LANE_VALUES).unwrap().0;
        for _ in 0..100 {
            assert_eq!(f.lane_raw(LANE_VALUES).unwrap().0, first);
        }
    }
}

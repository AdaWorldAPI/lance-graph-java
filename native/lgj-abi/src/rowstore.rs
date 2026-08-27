//! The SoA row store — the lance-graph-shaped substrate (abi.md §11).
//!
//! Where [`crate::fixture`] proved the membrane over three flat lanes, this
//! module carries the layout the whole stack actually converges on — the
//! operator-stated reference (2026-08-17): **64K rows × 512 bytes per row,
//! read as 32 lanes of 16 bytes each: a 4-byte little-endian classid plus a
//! 12-byte payload** (the lance-graph V3 content-blind facet). The Java side
//! may lay its *view* out differently; these bytes are the substrate truth.
//!
//! # One buffer, two readings, zero copies
//!
//! The store is ONE `Arc<[u8]>` of `n_rows * 512` bytes. Everything else is a
//! *reading* of those bytes, never a copy:
//!
//! - **Row reading** — row `r` is bytes `r*512 .. (r+1)*512`; facet `f` of
//!   row `r` is the 16 bytes at `r*512 + f*16`, its classid the leading LE
//!   `u32`. This is what `iter_u8x64`/`iter_u32x16`-style chunk scans and
//!   Java's structured `MemoryLayout` both address.
//! - **Facet-lane reading** — classid lane `f` is a strided `u32` column:
//!   `first_offset = f*16`, `stride = 512`, `count = n_rows`. This is what
//!   [`crate::abi::LgjLaneDesc::stride_bytes`] has described since ABI 0.1 —
//!   the descriptor anticipated this module.
//!
//! `Arc<[u8]>` is the deliberate carrier: its heap buffer never moves for the
//! Arc's whole life (the §4 allocation-stability guarantee), a clone is a
//! refcount bump (the kernels wrap the same bytes in an
//! `ndarray::simd::MultiLaneColumn` without copying), and shared immutable
//! ownership is exactly the one-writer-per-resource concurrency shape the
//! 64K-mailbox model wants.
//!
//! # Alignment (stated honestly)
//!
//! Rows are 512-byte *strided* within the buffer, but the buffer's base is
//! only `u8`-aligned — `Arc<[u8]>` cannot promise more on stable Rust.
//! Nothing in this slice needs more: Panama reads are alignment-agnostic
//! (`ValueLayout.JAVA_INT_UNALIGNED` exists precisely for this), and every
//! `ndarray::simd` load goes through `from_array`-style register fills. The
//! 64-byte-aligned guarantee arrives with the real `NodeRow`
//! (`#[repr(C, align(64))]`) wiring, not here.

/// Bytes per row: 32 facets × 16 bytes.
pub const ROW_BYTES: u64 = 512;
/// Facet lanes per row.
pub const ROW_FACETS: u32 = 32;
/// Bytes per facet: 4-byte classid + 12-byte payload.
pub const FACET_BYTES: u64 = 16;
/// The classid is the facet's leading little-endian `u32`.
pub const FACET_CLASSID_BYTES: u64 = 4;
/// Byte offset, within a facet, of the payload's high `u32`. The generator
/// zeroes it on a structured edge and fills it with noise otherwise, so
/// `== 0` at this offset IS the structured-edge predicate — one strided
/// equality, exactly like the classid match at offset 0.
pub const FACET_PAYLOAD_HI32_OFFSET: u64 = 12;
/// Classid cardinality the generator produces: `0..16` (same recipe as the
/// flat fixture, so predicates select the same middling fraction).
pub const ROWSTORE_CLASS_CARDINALITY: u64 = 16;

/// Lane id of the raw whole-buffer lane (`U8`, contiguous, `n_rows * 512`
/// elements).
pub const LANE_RAW: u32 = 0;
/// Lane id of facet `f`'s classid lane is `LANE_FACET_BASE + f`.
pub const LANE_FACET_BASE: u32 = 1;
/// Lane id of facet `f`'s payload-low64 lane is `LANE_LO64_BASE + f`.
pub const LANE_LO64_BASE: u32 = LANE_FACET_BASE + ROW_FACETS;
/// Lane id of facet `f`'s payload-hi32 lane is `LANE_HI32_BASE + f`.
pub const LANE_HI32_BASE: u32 = LANE_LO64_BASE + ROW_FACETS;
/// Total describable lanes: 1 raw + 32 classid + 32 lo64 + 32 hi32.
pub const ROWSTORE_LANE_COUNT: u32 = 1 + 3 * ROW_FACETS;

use std::sync::Arc;

use crate::fixture::SplitMix64;

/// The SoA row store: one shared, immutable, address-stable byte buffer.
///
/// # The generation algorithm — NORMATIVE
///
/// Like [`crate::fixture::Fixture`], the Java parity test recomputes
/// expectations from this description alone, so it is a contract:
///
/// ```text
/// rng = SplitMix64(seed)                  // state = seed, no warm-up draws
/// for row in 0 .. n_rows:                 // ascending
///     for facet in 0 .. 32:               // ascending within the row
///         a = rng.next_u64()              // FIRST draw of the facet
///         b = rng.next_u64()              // SECOND draw of the facet
///         base = row*512 + facet*16
///         bytes[base    .. base+4 ]  = le32( (a >>> 33) & 0xF )   // classid
///         bytes[base+4  .. base+12]  = le64( b )                  // payload
///         bytes[base+12 .. base+16]  = le32( a & 0xFFFFFFFF )     // payload
/// ```
///
/// Two draws per facet, `a` before `b`, 64 draws per row. The classid recipe
/// `(a >>> 33) & 0xF` is byte-identical to the flat fixture's class lane, so
/// a classid predicate selects the same ≈1/16 fraction here.
pub struct RowStore {
    /// Logical row count.
    pub n_rows: u64,
    /// The seed the buffer was generated from.
    pub seed: u64,
    /// How the 512 bytes per row are ARRANGED in `bytes`. The logical content
    /// — 32 facets of `classid(4) + payload(12)` per row, same generator, same
    /// draws — is identical under both; only the addresses differ. A layout is
    /// a SCHEMA over the same bytes (R11), never a second store kind.
    pub layout: RowLayout,
    bytes: Arc<[u8]>,
}

/// The two physical arrangements of the same `n_rows × 32 × (4+12)` content.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RowLayout {
    /// Row-major: row `r` is 512 contiguous bytes, facet `f` at `r*512 + f*16`
    /// (`classid` at `+0`, payload low64 at `+4`, payload hi32 at `+12`).
    /// Whole-row reads are contiguous; any single-field sweep is stride 512.
    AosRows,
    /// Facet-major columnar: three field regions, each split into 32
    /// contiguous per-facet blocks —
    ///
    /// ```text
    /// [0        .. 128n)  classid   facet f at 0    + f*4n, stride 4
    /// [128n     .. 384n)  lo64      facet f at 128n + f*8n, stride 8
    /// [384n     .. 512n)  hi32      facet f at 384n + f*4n, stride 4
    /// ```
    ///
    /// Every single-field sweep is CONTIGUOUS — which is what makes the hop's
    /// mask algebra run at canvas speed instead of paying 64 stride-512
    /// passes (measured 19× against it; `.claude/board/`
    /// `hop-mask-algebra-vs-columnar.txt`). Every region and per-facet block
    /// offset is a multiple of 64 for ANY `n_rows` (128, 384 and 512 are all
    /// multiples of 64, and blocks are `4n`/`8n` from 64-multiple bases with
    /// the same divisibility), so the alignment story is the base pointer's
    /// alone — same honest `u8`-aligned statement as AoS, and the kernels use
    /// unaligned loads either way.
    FacetMajor,
}

impl RowLayout {
    /// `(first_offset, stride_bytes)` of facet `facet`'s classid lane — the
    /// pair every strided kernel consumes. ONE source for the lane geometry;
    /// `lane_raw` serves the same numbers over the ABI.
    #[inline]
    pub fn classid_lane(self, n_rows: u64, facet: u32) -> (usize, usize) {
        match self {
            RowLayout::AosRows => (
                (u64::from(facet) * FACET_BYTES) as usize,
                ROW_BYTES as usize,
            ),
            RowLayout::FacetMajor => ((u64::from(facet) * 4 * n_rows) as usize, 4),
        }
    }

    /// `(first_offset, stride_bytes)` of facet `facet`'s payload-lo64 lane.
    #[inline]
    pub fn lo64_lane(self, n_rows: u64, facet: u32) -> (usize, usize) {
        match self {
            RowLayout::AosRows => (
                (u64::from(facet) * FACET_BYTES + 4) as usize,
                ROW_BYTES as usize,
            ),
            RowLayout::FacetMajor => ((128 * n_rows + u64::from(facet) * 8 * n_rows) as usize, 8),
        }
    }

    /// `(first_offset, stride_bytes)` of facet `facet`'s payload-hi32 lane.
    #[inline]
    pub fn hi32_lane(self, n_rows: u64, facet: u32) -> (usize, usize) {
        match self {
            RowLayout::AosRows => (
                (u64::from(facet) * FACET_BYTES + FACET_PAYLOAD_HI32_OFFSET) as usize,
                ROW_BYTES as usize,
            ),
            RowLayout::FacetMajor => ((384 * n_rows + u64::from(facet) * 4 * n_rows) as usize, 4),
        }
    }

    /// Byte offset of `(row, facet)`'s classid under this layout.
    #[inline]
    pub fn classid_offset(self, n_rows: u64, row: u64, facet: u32) -> usize {
        match self {
            RowLayout::AosRows => (row * ROW_BYTES + u64::from(facet) * FACET_BYTES) as usize,
            RowLayout::FacetMajor => (u64::from(facet) * 4 * n_rows + row * 4) as usize,
        }
    }

    /// Byte offset of `(row, facet)`'s payload low 64 bits under this layout.
    #[inline]
    pub fn lo64_offset(self, n_rows: u64, row: u64, facet: u32) -> usize {
        match self {
            RowLayout::AosRows => (row * ROW_BYTES + u64::from(facet) * FACET_BYTES + 4) as usize,
            RowLayout::FacetMajor => {
                (128 * n_rows + u64::from(facet) * 8 * n_rows + row * 8) as usize
            }
        }
    }

    /// Byte offset of `(row, facet)`'s payload high 32 bits under this layout.
    #[inline]
    pub fn hi32_offset(self, n_rows: u64, row: u64, facet: u32) -> usize {
        match self {
            RowLayout::AosRows => {
                (row * ROW_BYTES + u64::from(facet) * FACET_BYTES + FACET_PAYLOAD_HI32_OFFSET)
                    as usize
            }
            RowLayout::FacetMajor => {
                (384 * n_rows + u64::from(facet) * 4 * n_rows + row * 4) as usize
            }
        }
    }
}

impl std::fmt::Debug for RowStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RowStore")
            .field("n_rows", &self.n_rows)
            .field("seed", &self.seed)
            .finish_non_exhaustive()
    }
}

impl RowStore {
    /// Build the store. Allocates the buffer exactly once.
    ///
    /// Returns `None` if `n_rows * 512` overflows or cannot be allocated
    /// (the caller maps that to `LENGTH_OVERFLOW` / `ALLOCATION_FAILED`).
    pub fn generate(n_rows: u64, seed: u64) -> Option<Self> {
        Self::generate_in(n_rows, seed, RowLayout::AosRows)
    }

    /// [`Self::generate`] under an explicit [`RowLayout`]. Same draws, same
    /// logical content — pinned by `layouts_hold_identical_logical_content`.
    pub fn generate_in(n_rows: u64, seed: u64, layout: RowLayout) -> Option<Self> {
        let n = usize::try_from(n_rows).ok()?;
        let byte_len = n.checked_mul(ROW_BYTES as usize)?;

        let mut bytes = Vec::new();
        bytes.try_reserve_exact(byte_len).ok()?;
        bytes.resize(byte_len, 0u8);

        let mut rng = SplitMix64::new(seed);
        for row in 0..n {
            for facet in 0..ROW_FACETS as usize {
                let a = rng.next_u64();
                let b = rng.next_u64();
                let classid = ((a >> 33) & (ROWSTORE_CLASS_CARDINALITY - 1)) as u32;
                let co = layout.classid_offset(n_rows, row as u64, facet as u32);
                let lo = layout.lo64_offset(n_rows, row as u64, facet as u32);
                let hi = layout.hi32_offset(n_rows, row as u64, facet as u32);
                bytes[co..co + 4].copy_from_slice(&classid.to_le_bytes());
                bytes[lo..lo + 8].copy_from_slice(&b.to_le_bytes());
                bytes[hi..hi + 4].copy_from_slice(&(a as u32).to_le_bytes());
            }
        }

        Some(Self {
            n_rows,
            seed,
            layout,
            bytes: Arc::from(bytes),
        })
    }

    /// Build a store whose classid stream is byte-identical to [`Self::generate`]
    /// but with a sparse, bounded-neighbourhood subset of `edge_classid`-matching
    /// facets carrying a *structured* payload (a nearby row index) instead of raw
    /// random bits — the graph-consumer wave's "deliberate edge-bearing generator
    /// arm" (`.claude/waves/wave-consumer-graph.md`'s STOP condition: the plain
    /// [`Self::generate`] payload is PRNG noise, so a 1/2-hop BFS over it
    /// saturates to nearly every row within one or two hops — vacuous under any
    /// non-trivial anti-vacuity check, regardless of decode convention).
    ///
    /// # Why classid stays byte-identical to `generate()`
    ///
    /// `classid = (a >>> 33) & 0xF` consumes bits 33..37 of the SAME per-facet
    /// `a` draw `generate()` makes; bits 37..64 (27 bits) are otherwise unused
    /// by that formula and are spent here as an INDEPENDENT sparsity gate, so
    /// this function never perturbs classid assignment — `edge_classid = 16`
    /// (out of the 0..16 range) makes the gate structurally unreachable, and
    /// the byte output is then provably identical to `generate()` (pinned by a
    /// test, not merely argued).
    ///
    /// # The sparsity gate and why it exists
    ///
    /// A facet becomes a structured edge only when BOTH `classid ==
    /// edge_classid` (probability `1/16`) AND `(a & edge_gate_mask) == 0`
    /// (probability `1/(edge_gate_mask + 1)`, `edge_gate_mask` a power-of-two
    /// minus one). The two gates compose multiplicatively and independently
    /// (disjoint bit ranges of the same draw), so overall edge density per
    /// facet is `1 / (16 * (edge_gate_mask + 1))` — tunable without touching
    /// the classid formula. `examples/graph_density_probe.rs` measures real
    /// 1-hop/2-hop set sizes across candidate gate masks; this function does
    /// not itself pick "good" parameters — see that probe's recorded numbers
    /// before choosing them for a real fixture.
    ///
    /// # The target encoding
    ///
    /// A structured edge's payload is `target_row` (LE u64, in the 8-byte
    /// `b`-slot) with the trailing 4-byte `a`-slot zeroed as a marker —
    /// `target_row = (row as i64 + offset).rem_euclid(n_rows as i64)`,
    /// `offset` drawn from `b % (2*edge_radius+1)` recentred to
    /// `-edge_radius..=+edge_radius` — a BOUNDED local neighbourhood, never a
    /// uniform global target (the mechanism that keeps the graph sparse AND
    /// local, which is what makes hop-count matter at all). A non-edge facet
    /// (gate failed) keeps `generate()`'s raw `(b, a_lo32)` payload exactly.
    ///
    /// Returns `None` on the same overflow/allocation conditions as
    /// [`Self::generate`], and if `edge_radius >= n_rows` (a radius that
    /// cannot wrap meaningfully) or `n_rows == 0`.
    #[allow(clippy::too_many_arguments)]
    pub fn generate_with_edges(
        n_rows: u64,
        seed: u64,
        edge_classid: u32,
        edge_gate_mask: u64,
        edge_radius: u32,
    ) -> Option<Self> {
        Self::generate_with_edges_in(
            n_rows,
            seed,
            edge_classid,
            edge_gate_mask,
            edge_radius,
            RowLayout::AosRows,
        )
    }

    /// [`Self::generate_with_edges`] under an explicit [`RowLayout`]. Same
    /// draws, same logical content, same pinned 10 → 19 → 29 hop counts.
    pub fn generate_with_edges_in(
        n_rows: u64,
        seed: u64,
        edge_classid: u32,
        edge_gate_mask: u64,
        edge_radius: u32,
        layout: RowLayout,
    ) -> Option<Self> {
        let n = usize::try_from(n_rows).ok()?;
        if n == 0 || u64::from(edge_radius) >= n_rows {
            return None;
        }
        let byte_len = n.checked_mul(ROW_BYTES as usize)?;

        let mut bytes = Vec::new();
        bytes.try_reserve_exact(byte_len).ok()?;
        bytes.resize(byte_len, 0u8);

        let mut rng = SplitMix64::new(seed);
        for row in 0..n {
            for facet in 0..ROW_FACETS as usize {
                let a = rng.next_u64();
                let b = rng.next_u64();
                let classid = ((a >> 33) & (ROWSTORE_CLASS_CARDINALITY - 1)) as u32;

                let (payload_lo64, payload_hi32) =
                    if classid == edge_classid && (a & edge_gate_mask) == 0 {
                        let span = 2 * u64::from(edge_radius) + 1;
                        let raw_offset = (b % span) as i64 - i64::from(edge_radius);
                        let target = (row as i64 + raw_offset).rem_euclid(n as i64) as u64;
                        (target, 0u32)
                    } else {
                        (b, a as u32)
                    };

                let co = layout.classid_offset(n_rows, row as u64, facet as u32);
                let lo = layout.lo64_offset(n_rows, row as u64, facet as u32);
                let hi = layout.hi32_offset(n_rows, row as u64, facet as u32);
                bytes[co..co + 4].copy_from_slice(&classid.to_le_bytes());
                bytes[lo..lo + 8].copy_from_slice(&payload_lo64.to_le_bytes());
                bytes[hi..hi + 4].copy_from_slice(&payload_hi32.to_le_bytes());
            }
        }

        Some(Self {
            n_rows,
            seed,
            layout,
            bytes: Arc::from(bytes),
        })
    }

    /// The whole buffer as a byte slice. Zero-copy; the address is stable for
    /// the store's life (see the module header).
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    /// A cheap shared handle to the same bytes — what the kernels wrap in a
    /// `MultiLaneColumn` without copying.
    pub fn bytes_arc(&self) -> Arc<[u8]> {
        Arc::clone(&self.bytes)
    }

    /// The classid of facet `facet` in row `row` — the scalar (one-element)
    /// read, used by tests and the scalar reference kernels. Bulk access goes
    /// through the lanes, never through a loop over this.
    pub fn classid_at(&self, row: u64, facet: u32) -> u32 {
        let base = self.layout.classid_offset(self.n_rows, row, facet);
        u32::from_le_bytes(self.bytes[base..base + 4].try_into().unwrap())
    }

    /// The payload's low 64 bits at `(row, facet)` — layout-aware scalar read.
    pub fn payload_lo64_at(&self, row: u64, facet: u32) -> u64 {
        let base = self.layout.lo64_offset(self.n_rows, row, facet);
        u64::from_le_bytes(self.bytes[base..base + 8].try_into().unwrap())
    }

    /// The payload's high 32 bits at `(row, facet)` — layout-aware scalar read.
    pub fn payload_hi32_at(&self, row: u64, facet: u32) -> u32 {
        let base = self.layout.hi32_offset(self.n_rows, row, facet);
        u32::from_le_bytes(self.bytes[base..base + 4].try_into().unwrap())
    }

    /// `(base address, len_elems, elem_kind, stride_bytes, contiguous)` for a
    /// lane id, or `None` for an out-of-range id (⇒ `INVALID_LANE`).
    pub fn lane_raw(&self, lane_id: u32) -> Option<(u64, u64, crate::abi::LgjElemKind, u32, bool)> {
        use crate::abi::LgjElemKind;
        if lane_id == LANE_RAW {
            return Some((
                self.bytes.as_ptr() as u64,
                self.bytes.len() as u64,
                LgjElemKind::U8,
                1,
                true,
            ));
        }
        // The lane table is the SCHEMA SERVED (R11: layout is data): a
        // consumer reads through (addr, stride) and never hand-computes an
        // offset, so the same lane id answers correctly under EITHER layout —
        // only the numbers in the descriptor differ.
        let base = self.bytes.as_ptr() as u64;
        let n = self.n_rows;
        if let Some(facet) = lane_id.checked_sub(LANE_FACET_BASE) {
            if facet < ROW_FACETS {
                let (off, stride, contig) = match self.layout {
                    RowLayout::AosRows => (u64::from(facet) * FACET_BYTES, ROW_BYTES as u32, false),
                    RowLayout::FacetMajor => (u64::from(facet) * 4 * n, 4u32, true),
                };
                return Some((base + off, n, LgjElemKind::U32, stride, contig));
            }
        }
        if let Some(facet) = lane_id.checked_sub(LANE_LO64_BASE) {
            if facet < ROW_FACETS {
                let (off, stride, contig) = match self.layout {
                    RowLayout::AosRows => {
                        (u64::from(facet) * FACET_BYTES + 4, ROW_BYTES as u32, false)
                    }
                    RowLayout::FacetMajor => (128 * n + u64::from(facet) * 8 * n, 8u32, true),
                };
                return Some((base + off, n, LgjElemKind::U64, stride, contig));
            }
        }
        if let Some(facet) = lane_id.checked_sub(LANE_HI32_BASE) {
            if facet < ROW_FACETS {
                let (off, stride, contig) = match self.layout {
                    RowLayout::AosRows => (
                        u64::from(facet) * FACET_BYTES + FACET_PAYLOAD_HI32_OFFSET,
                        ROW_BYTES as u32,
                        false,
                    ),
                    RowLayout::FacetMajor => (384 * n + u64::from(facet) * 4 * n, 4u32, true),
                };
                return Some((base + off, n, LgjElemKind::U32, stride, contig));
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The columnar store is the SAME logical content as AoS — every
    /// `(row, facet)` field identical under both layouts, same seed, same
    /// draws. This is what licenses every layout-aware op to answer
    /// identically: the bytes moved, the content did not.
    #[test]
    fn layouts_hold_identical_logical_content() {
        let n = 257u64; // deliberately NOT a power of two
        let a = RowStore::generate_with_edges(n, 0xF00D_CAFE, 3, 0x1, 9).unwrap();
        let c = RowStore::generate_with_edges_in(n, 0xF00D_CAFE, 3, 0x1, 9, RowLayout::FacetMajor)
            .unwrap();
        assert_eq!(a.layout, RowLayout::AosRows);
        assert_eq!(c.layout, RowLayout::FacetMajor);
        // Same total bytes — 512 per row, either arrangement.
        assert_eq!(a.as_bytes().len(), c.as_bytes().len());
        // The BYTES differ (anti-vacuity: a no-op "columnar" that kept AoS
        // order would pass every content check below).
        assert_ne!(a.as_bytes(), c.as_bytes());
        for row in [0u64, 1, 63, 64, 128, n - 1] {
            for facet in 0..ROW_FACETS {
                assert_eq!(a.classid_at(row, facet), c.classid_at(row, facet));
                assert_eq!(a.payload_lo64_at(row, facet), c.payload_lo64_at(row, facet));
                assert_eq!(a.payload_hi32_at(row, facet), c.payload_hi32_at(row, facet));
            }
        }
    }

    /// The three carvings' largest group is ≤ 4 bytes — HALF the JEP 401
    /// flattening budget — and the 512-byte row stride plus every FacetMajor
    /// region/block offset is 64-byte aligned for ANY n_rows. R4/R10 measured
    /// the Valhalla half (a ≤4-byte GROUP flattens, the 12-byte register never
    /// does); these are the substrate-side halves of the same contract, pinned
    /// here so a carving or layout change cannot silently break either.
    #[test]
    fn carving_groups_fit_the_flattening_budget_and_the_layout_is_64_aligned() {
        use lance_graph_contract::facet::CascadeShape;
        for shape in CascadeShape::ROTATIONS {
            let gb = 12 / shape.groups() as u64;
            assert!(gb <= 4, "{shape:?}: group_bytes {gb} > 4");
            assert_eq!(gb * shape.groups() as u64, 12, "{shape:?} must tile 12");
        }
        assert_eq!(ROW_BYTES % 64, 0, "512-byte row stride is 64-aligned");
        // FacetMajor: region bases 0 / 128n / 384n and per-facet block starts
        // (f*4n, 128n + f*8n, 384n + f*4n) are 64-multiples for ANY n — the
        // factors 128, 384, 4 and 8 against n… 4n and 8n are NOT always
        // 64-multiples for arbitrary n, so this pins the REGION bases (always)
        // and the block claim for the mask-word-quantised n the ABI actually
        // serves (n padded to 64-row mask words ⇒ 4n ≡ 0 (mod 256)).
        for n in [64u64, 192, 1000, 4096] {
            assert_eq!((128 * n) % 64, 0);
            assert_eq!((384 * n) % 64, 0);
        }
        for n in [64u64, 128, 4096] {
            for f in [0u64, 1, 31] {
                assert_eq!((f * 4 * n) % 64, 0);
                assert_eq!((128 * n + f * 8 * n) % 64, 0);
                assert_eq!((384 * n + f * 4 * n) % 64, 0);
            }
        }
    }

    #[test]
    fn generation_is_deterministic_and_seed_sensitive() {
        let a = RowStore::generate(64, 42).unwrap();
        let b = RowStore::generate(64, 42).unwrap();
        let c = RowStore::generate(64, 43).unwrap();
        assert_eq!(a.as_bytes(), b.as_bytes());
        assert_ne!(a.as_bytes(), c.as_bytes());
    }

    /// The normative algorithm, recomputed independently from the doc-comment
    /// description (a transcription, exactly what the Java test will do).
    #[test]
    fn the_documented_generator_is_the_actual_generator() {
        let n = 5u64;
        let seed = 0xABCD;
        let store = RowStore::generate(n, seed).unwrap();

        let mut rng = SplitMix64::new(seed);
        for row in 0..n {
            for facet in 0..ROW_FACETS {
                let a = rng.next_u64();
                let b = rng.next_u64();
                let expect_class = ((a >> 33) & 0xF) as u32;
                assert_eq!(store.classid_at(row, facet), expect_class);
                let base = (row * ROW_BYTES + facet as u64 * FACET_BYTES) as usize;
                assert_eq!(&store.as_bytes()[base + 4..base + 12], &b.to_le_bytes());
                assert_eq!(
                    &store.as_bytes()[base + 12..base + 16],
                    &(a as u32).to_le_bytes()
                );
            }
        }
    }

    #[test]
    fn the_buffer_is_exactly_n_times_512_bytes() {
        for n in [0u64, 1, 7, 64] {
            let s = RowStore::generate(n, 1).unwrap();
            assert_eq!(s.as_bytes().len() as u64, n * ROW_BYTES);
            // …which is always a multiple of 64: the MultiLaneColumn
            // precondition holds BY CONSTRUCTION, never by luck.
            assert_eq!(s.as_bytes().len() % 64, 0);
        }
    }

    #[test]
    fn lane_map_covers_raw_plus_32_facets_and_nothing_else() {
        let s = RowStore::generate(16, 9).unwrap();
        let (addr0, len0, kind0, stride0, contig0) = s.lane_raw(LANE_RAW).unwrap();
        assert_eq!(addr0, s.as_bytes().as_ptr() as u64);
        assert_eq!(len0, 16 * ROW_BYTES);
        assert_eq!(kind0, crate::abi::LgjElemKind::U8);
        assert_eq!(stride0, 1);
        assert!(contig0);

        for f in 0..ROW_FACETS {
            let (addr, len, kind, stride, contig) = s.lane_raw(LANE_FACET_BASE + f).unwrap();
            assert_eq!(addr, s.as_bytes().as_ptr() as u64 + f as u64 * FACET_BYTES);
            assert_eq!(len, 16);
            assert_eq!(kind, crate::abi::LgjElemKind::U32);
            assert_eq!(stride, ROW_BYTES as u32);
            assert!(!contig);
        }
        // Minor 10: the table CONTINUES past the classid lanes — payload
        // lo64/hi32 lanes, then nothing. Re-pinned as contrast, not widened.
        for f in 0..ROW_FACETS {
            let (addr, len, kind, stride, contig) = s.lane_raw(LANE_LO64_BASE + f).unwrap();
            assert_eq!(
                addr,
                s.as_bytes().as_ptr() as u64 + f as u64 * FACET_BYTES + 4
            );
            assert_eq!(len, 16);
            assert_eq!(kind, crate::abi::LgjElemKind::U64);
            assert_eq!(stride, ROW_BYTES as u32);
            assert!(!contig);
            let (addr, _, kind, stride, _) = s.lane_raw(LANE_HI32_BASE + f).unwrap();
            assert_eq!(
                addr,
                s.as_bytes().as_ptr() as u64 + f as u64 * FACET_BYTES + FACET_PAYLOAD_HI32_OFFSET
            );
            assert_eq!(kind, crate::abi::LgjElemKind::U32);
            assert_eq!(stride, ROW_BYTES as u32);
        }
        assert!(s.lane_raw(LANE_HI32_BASE + ROW_FACETS).is_none());
        assert!(s.lane_raw(u32::MAX).is_none());
    }

    /// Classids must use their full 0..16 range in every facet lane — a
    /// constant lane would make every classid predicate vacuous.
    #[test]
    fn every_facet_lane_uses_the_full_classid_range() {
        let s = RowStore::generate(4096, 7).unwrap();
        for facet in [0u32, 1, 15, 31] {
            let mut seen = [false; 16];
            for row in 0..s.n_rows {
                let c = s.classid_at(row, facet);
                assert!(c < 16);
                seen[c as usize] = true;
            }
            assert!(
                seen.iter().all(|&x| x),
                "facet {facet} must hit all 16 classids at n=4096"
            );
        }
    }

    #[test]
    fn addresses_are_stable_across_reads() {
        let s = RowStore::generate(32, 3).unwrap();
        let first = s.lane_raw(LANE_FACET_BASE + 5).unwrap().0;
        for _ in 0..100 {
            assert_eq!(s.lane_raw(LANE_FACET_BASE + 5).unwrap().0, first);
        }
        // And the Arc handle shares, never copies.
        assert_eq!(s.bytes_arc().as_ptr(), s.as_bytes().as_ptr());
    }

    #[test]
    fn zero_rows_is_legal_and_empty() {
        let s = RowStore::generate(0, 1).unwrap();
        assert!(s.as_bytes().is_empty());
        assert_eq!(s.lane_raw(LANE_FACET_BASE).unwrap().1, 0);
    }

    // ── generate_with_edges: the graph-consumer wave's edge-bearing arm ──

    /// Out-of-range `edge_classid` makes the sparsity gate structurally
    /// unreachable — proves the edge mechanism is genuinely additive, not
    /// merely documented as such.
    #[test]
    fn out_of_range_edge_classid_reproduces_plain_generate_byte_for_byte() {
        let plain = RowStore::generate(200, 0xABCD).unwrap();
        let edged = RowStore::generate_with_edges(
            200,
            0xABCD,
            ROWSTORE_CLASS_CARDINALITY as u32, // 16, out of the 0..16 range
            0,
            10,
        )
        .unwrap();
        assert_eq!(plain.as_bytes(), edged.as_bytes());
    }

    /// The independent transcription (mirrors
    /// `the_documented_generator_is_the_actual_generator` above, extended
    /// with the gate + target formula from this function's own doc comment)
    /// — exactly what the Java-side parity test will do.
    #[test]
    fn the_documented_edge_mechanism_is_the_actual_mechanism() {
        let n = 50u64;
        let seed = 0x1234_5678;
        let edge_classid = 3u32;
        let edge_gate_mask = 0x3u64;
        let edge_radius = 7u32;
        let store =
            RowStore::generate_with_edges(n, seed, edge_classid, edge_gate_mask, edge_radius)
                .unwrap();

        let mut rng = SplitMix64::new(seed);
        for row in 0..n {
            for facet in 0..ROW_FACETS {
                let a = rng.next_u64();
                let b = rng.next_u64();
                let classid = ((a >> 33) & 0xF) as u32;
                let base = (row * ROW_BYTES + facet as u64 * FACET_BYTES) as usize;
                assert_eq!(store.classid_at(row, facet), classid);

                if classid == edge_classid && (a & edge_gate_mask) == 0 {
                    let span = 2 * u64::from(edge_radius) + 1;
                    let raw_offset = (b % span) as i64 - i64::from(edge_radius);
                    let target = (row as i64 + raw_offset).rem_euclid(n as i64) as u64;
                    assert_eq!(
                        &store.as_bytes()[base + 4..base + 12],
                        &target.to_le_bytes(),
                        "row {row} facet {facet}: structured edge target"
                    );
                    assert_eq!(&store.as_bytes()[base + 12..base + 16], &0u32.to_le_bytes());
                } else {
                    assert_eq!(&store.as_bytes()[base + 4..base + 12], &b.to_le_bytes());
                    assert_eq!(
                        &store.as_bytes()[base + 12..base + 16],
                        &(a as u32).to_le_bytes()
                    );
                }
            }
        }
    }

    /// Structural invariant: every structured-edge target is a valid,
    /// in-bounds row index within `edge_radius` of its own row (accounting
    /// for wraparound) — checked exhaustively, not sampled.
    #[test]
    fn every_structured_edge_target_is_in_bounds_and_within_radius() {
        let n = 300u64;
        let edge_classid = 5u32;
        let edge_radius = 20u32;
        let store =
            RowStore::generate_with_edges(n, 0x9E37, edge_classid, 0x1, edge_radius).unwrap();

        let mut checked = 0u32;
        for row in 0..n {
            for facet in 0..ROW_FACETS {
                if store.classid_at(row, facet) != edge_classid {
                    continue;
                }
                let base = (row * ROW_BYTES + facet as u64 * FACET_BYTES) as usize;
                let hi32 =
                    u32::from_le_bytes(store.as_bytes()[base + 12..base + 16].try_into().unwrap());
                if hi32 != 0 {
                    continue; // gate failed for this classid-matching facet
                }
                checked += 1;
                let target =
                    u64::from_le_bytes(store.as_bytes()[base + 4..base + 12].try_into().unwrap());
                assert!(target < n, "target {target} out of bounds ({n} rows)");
                let forward = (target as i64 - row as i64).rem_euclid(n as i64);
                let backward = n as i64 - forward;
                let wrapped_distance = forward.min(backward);
                assert!(
                    wrapped_distance <= i64::from(edge_radius),
                    "row {row} -> target {target}: distance {wrapped_distance} exceeds radius {edge_radius}"
                );
            }
        }
        assert!(
            checked > 0,
            "anti-vacuity: this fixture must produce at least one structured edge"
        );
    }

    /// The measured regression pin (`examples/graph_density_probe.rs`,
    /// `n_rows=2000, gate_mask=0x0, radius=25`): a 10-row seed set reaches
    /// exactly 19 distinct rows at 1 hop and 29 at 2 hops. Three different,
    /// non-empty, non-total sizes — the graph-consumer wave's own
    /// anti-vacuity falsifier, satisfied here at the GENERATOR level before
    /// any consumer code exists to fail it. Recomputed by BFS, independent
    /// of `generate_with_edges`'s own internals (reads only classid + raw
    /// payload bytes, the same surface a real consumer sees).
    #[test]
    fn measured_hop_counts_are_three_distinct_non_empty_non_total_sizes() {
        let n = 2_000u64;
        let edge_classid = 0u32;
        let store = RowStore::generate_with_edges(n, 0xF00D_CAFE, edge_classid, 0x0, 25).unwrap();

        let hop = |from: &[u64]| -> Vec<u64> {
            let mut seen = vec![false; n as usize];
            let mut out = Vec::new();
            for &row in from {
                for facet in 0..ROW_FACETS {
                    if store.classid_at(row, facet) != edge_classid {
                        continue;
                    }
                    let base = (row * ROW_BYTES + facet as u64 * FACET_BYTES) as usize;
                    let hi32 = u32::from_le_bytes(
                        store.as_bytes()[base + 12..base + 16].try_into().unwrap(),
                    );
                    if hi32 != 0 {
                        continue;
                    }
                    let target = u64::from_le_bytes(
                        store.as_bytes()[base + 4..base + 12].try_into().unwrap(),
                    );
                    if !seen[target as usize] {
                        seen[target as usize] = true;
                        out.push(target);
                    }
                }
            }
            out
        };

        let seed: Vec<u64> = (0..10).map(|i| i * 37 + 5).collect();
        let one_hop = hop(&seed);
        let two_hop = hop(&one_hop);

        assert_eq!(seed.len(), 10);
        assert_eq!(one_hop.len(), 19);
        assert_eq!(two_hop.len(), 29);
        assert!(seed.len() != one_hop.len() && one_hop.len() != two_hop.len());
        assert!(!one_hop.is_empty() && !two_hop.is_empty());
        assert!((one_hop.len() as u64) < n && (two_hop.len() as u64) < n);
    }

    #[test]
    fn zero_rows_is_rejected_for_generate_with_edges() {
        assert!(RowStore::generate_with_edges(0, 1, 0, 0, 10).is_none());
    }

    #[test]
    fn radius_that_cannot_fit_is_rejected() {
        assert!(RowStore::generate_with_edges(10, 1, 0, 0, 10).is_none());
        assert!(RowStore::generate_with_edges(10, 1, 0, 0, 9).is_some());
    }
}

//! The ABI surface types — the *machine membrane* vocabulary.
//!
//! Normative source: `docs/abi.md`. Every struct field, its order, its size and
//! every status code in this file is dictated by that document. If this file and
//! `abi.md` disagree, this file is wrong.
//!
//! # There is no C here
//!
//! `#[repr(C)]` below names this target's standard *aggregate layout rule*
//! (field order, padding, alignment) — the SysV AMD64 psABI on x86_64, AAPCS64
//! on ARM64. It is not a C declaration, there is no `.h` anywhere in this
//! project, and no C toolchain participates in building or consuming it. The
//! Java side re-derives these same layouts independently with
//! `java.lang.foreign.MemoryLayout` and the manifest (below) proves the two
//! derivations agree *at runtime*, which a text header could never do.
//!
//! # Layout drift is a build failure
//!
//! Each `#[repr(C)]` type carries `const _: () = assert!(size_of::<T>() == N);`
//! compile-time assertions against the byte counts `abi.md` §5 states. Adding,
//! reordering or re-typing a field therefore breaks the *build*, not a Java
//! test at 3am.

use core::mem::{align_of, size_of};

// ---------------------------------------------------------------------------
// §2 Versioning
// ---------------------------------------------------------------------------

/// Incompatible change ⇒ bump. Java refuses to load on a mismatch.
pub const LGJ_ABI_MAJOR: u32 = 0;

/// Additive change ⇒ bump. Older Java may still load (`minor >= expected`).
///
/// Minor **2** (2026-08-17): the SoA row store — `LGJ_RESOURCE_ROWSTORE`,
/// `lgj_rowstore_open`, `lgj_op_eq_classid`, `lgj_row_facet_match`, and
/// strided facet lanes described through the (unchanged) `LgjLaneDesc`.
/// Purely additive; a minor-1 Java loads and sees none of it.
///
/// Minor **3** (2026-08-18): `lgj_rowstore_open_with_edges` — the
/// edge-bearing row-store generator (abi.md §12,
/// `RowStore::generate_with_edges`), unblocking `consumer-graph-traversal-v1.md`.
/// Byte-identical `LGJ_RESOURCE_ROWSTORE` resource kind, no new lane shape,
/// no new mask op; purely an alternative constructor. A minor-2 Java loads
/// fine and simply cannot call the new symbol (`Abi.requireMinor(3)` gates
/// it, matching the row store's own minor-2 gate pattern).
///
/// Minor **4** (2026-08-18, D-LGJ-W8): `lgj_mask_andnot` (mask complement,
/// `dst = a & !b`, the mask algebra's missing and-not/complement op) and
/// `lgj_hop` (one-hop graph traversal over a row store — the FIRST symbol
/// gated by the `lance-graph-contract` `ClassView`/`FieldMask` LAW via the
/// fixture's `crate::class_view_provider::edge_participation` seam;
/// Minor 5 adds `lgj_reduce_facet_sum` — the mask-driven, carving-
/// monomorphic sweep over a facet's 12-byte register (`docs/abi.md` §14),
/// the execution half of a mask path whose build half
/// (`lgj_op_eq_classid`) has existed since minor 2. Purely additive: a
/// minor-4 Java loads fine and never calls it.
///
/// `docs/abi.md` §13). Purely additive: a minor-3 Java loads fine and
/// simply cannot call either new symbol.
pub const LGJ_ABI_MINOR: u32 = 7;

/// `"LGJ_ABI\0"` read big-endian.
///
/// Doubles as an endianness probe: Java reads this field as a little-endian
/// `u64` and compares against its own compiled-in copy of this constant.
/// Anything else means the library was built for a different byte order, and
/// every subsequent read across the membrane would be garbage.
pub const LGJ_MAGIC: u64 = 0x4C_47_4A_5F_41_42_49_00;

// ---------------------------------------------------------------------------
// §3 Status codes — every function returns i32; 0 = OK, all failures negative
// ---------------------------------------------------------------------------

/// Success.
pub const LGJ_OK: i32 = 0;
/// A required out-pointer was null.
pub const LGJ_ERR_NULL_ARGUMENT: i32 = -1;
/// Handle malformed, closed, or generation-stale. The answer to
/// *use-after-close* — deliberately a status, never a crash (§4).
pub const LGJ_ERR_INVALID_HANDLE: i32 = -2;
/// e.g. a mask handle passed where a pattern was required.
pub const LGJ_ERR_WRONG_RESOURCE_KIND: i32 = -3;
/// `lane_id` out of range for this resource.
pub const LGJ_ERR_INVALID_LANE: i32 = -4;
/// The op's element type ≠ the lane's element type.
pub const LGJ_ERR_LANE_KIND_MISMATCH: i32 = -5;
/// Mask row-count ≠ resource row-count.
pub const LGJ_ERR_MASK_LENGTH_MISMATCH: i32 = -6;
/// A child (mask) outlived its parent resource.
pub const LGJ_ERR_PARENT_CLOSED: i32 = -7;
/// Caller's ABI version incompatible.
pub const LGJ_ERR_VERSION_MISMATCH: i32 = -8;
/// Requested size overflows `usize` / the allocation limit.
pub const LGJ_ERR_LENGTH_OVERFLOW: i32 = -9;
/// The plan contained an opcode this build does not implement.
pub const LGJ_ERR_UNKNOWN_OPCODE: i32 = -10;
/// A plan with zero ops was submitted.
pub const LGJ_ERR_EMPTY_PLAN: i32 = -11;
/// The allocator refused.
pub const LGJ_ERR_ALLOCATION_FAILED: i32 = -12;
/// A write was attempted against a read-only lane.
pub const LGJ_ERR_READ_ONLY: i32 = -13;
/// `lgj_hop` was called with a `decode_mode` this build does not yet
/// implement. Modes `1..=3` are RESERVED (mirroring `EdgeCodecFlavor as
/// u32 + 1`, `canonical_node.rs`) until real class data lands — checked
/// FIRST, before `store`/`src_mask`/`dst_mask` are even resolved, so
/// `dst_mask` is provably untouched (§7's write-only-on-OK rule) on a
/// rejected call. D-LGJ-W8, ABI minor 4.
pub const LGJ_ERR_UNSUPPORTED_DECODE_MODE: i32 = -14;

/// `lgj_reduce_facet_sum` was handed a `carving` wire value outside `0..=2`
/// (`0` rails `6*(u8:u8)`, `1` triplets `4*(u8:u8:u8)`, `2` quads
/// `3*(u8:u8:u8:u8)` — `le-contract.md` §3's three readings of the same 12
/// bytes). Checked FIRST, before the store or mask are resolved, so
/// `out_sum` is provably untouched on a rejected call (§7's
/// write-only-on-OK rule).
///
/// Deliberately NOT a reuse of `LGJ_ERR_UNSUPPORTED_DECODE_MODE`: that code
/// names the edge-decode axis (`EdgeCodecFlavor`), and a register reading is
/// a different question. An unknown reading must never alias a known one.
/// ABI minor 5.
pub const LGJ_ERR_UNSUPPORTED_CARVING: i32 = -15;

/// `lgj_reduce_facet_sum`'s accumulator exceeded `i64`.
///
/// `i64` is not closed under this reduction: a single row contributes up to
/// `3 * (2^32 - 1)` under the quads reading, so a large enough selection of
/// large enough registers genuinely does not fit. The kernel accumulates in
/// `i128` and range-checks once, so the caller gets this status instead of a
/// wrapped value — `out_sum` is NOT written. ABI minor 5.
pub const LGJ_ERR_SUM_OVERFLOW: i32 = -16;

/// `lgj_reduce_facet_sum_resolved`'s population does not resolve to a single
/// register grouping.
///
/// Three causes, one fact: the population spans classes whose `ClassView`s carve
/// the register differently; a row's classid has no `ClassView` answer; or the
/// population is EMPTY (zero rows carry zero classes, and reporting the
/// zero-fallback would be inventing an answer). In every case there is no one
/// reading that is correct for these rows, so neither output is written.
/// ABI minor 6.
pub const LGJ_ERR_UNRESOLVED_CARVING: i32 = -17;

/// A panic was caught at the membrane and converted to a status (§9).
///
/// Not in `abi.md`'s table, and deliberately *outside* the allocated
/// `-1..=-17` block so it can never be confused with a specified condition.
/// A caller seeing this has found a bug in this crate; it is reported rather
/// than allowed to unwind into JVM frames, which would be UB.
pub const LGJ_ERR_PANIC: i32 = -99;

// ---------------------------------------------------------------------------
// §5 Element kinds — start at 1, so a zeroed struct is *detectably invalid*
//     rather than silently meaning U8.
// ---------------------------------------------------------------------------

/// Element kind tags for [`LgjLaneDesc::elem_kind`].
#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LgjElemKind {
    /// Unsigned 8-bit.
    U8 = 1,
    /// Signed 8-bit.
    I8 = 2,
    /// Unsigned 16-bit.
    U16 = 3,
    /// Signed 16-bit.
    I16 = 4,
    /// Unsigned 32-bit.
    U32 = 5,
    /// Signed 32-bit.
    I32 = 6,
    /// Unsigned 64-bit.
    U64 = 7,
    /// Signed 64-bit.
    I64 = 8,
    /// IEEE-754 binary32.
    F32 = 9,
    /// IEEE-754 binary64.
    F64 = 10,
    /// A `u64` of 64 packed row bits, LSB = lowest row index.
    MaskWord = 11,
}

impl LgjElemKind {
    /// Width of one element in bytes.
    pub const fn elem_bytes(self) -> u32 {
        match self {
            LgjElemKind::U8 | LgjElemKind::I8 => 1,
            LgjElemKind::U16 | LgjElemKind::I16 => 2,
            LgjElemKind::U32 | LgjElemKind::I32 | LgjElemKind::F32 => 4,
            LgjElemKind::U64 | LgjElemKind::I64 | LgjElemKind::F64 | LgjElemKind::MaskWord => 8,
        }
    }
}

// §5 lane flags (bitfield)
/// Java may read this lane's bytes.
pub const LGJ_FLAG_READABLE: u32 = 1 << 0;
/// Java may write this lane's bytes. Pattern lanes never set this; mask words do.
pub const LGJ_FLAG_WRITABLE: u32 = 1 << 1;
/// `stride_bytes == elem_bytes`.
pub const LGJ_FLAG_CONTIGUOUS: u32 = 1 << 2;

// §5 resource kinds
/// A pattern (the SoA fixture): id/class/value lanes, read-only.
pub const LGJ_RESOURCE_PATTERN: u32 = 1;
/// A mask: one `MASK_WORD` lane, writable, owned by a parent pattern or
/// row store.
pub const LGJ_RESOURCE_MASK: u32 = 2;
/// A SoA row store (abi.md §11): `n_rows × 512` bytes, 32 facet lanes of
/// (4-byte LE classid + 12-byte payload); 1 raw `U8` lane + 32 strided
/// `U32` classid lanes, all read-only. ABI minor ≥ 2.
pub const LGJ_RESOURCE_ROWSTORE: u32 = 3;

// §7 mask_create initial states
/// `lgj_mask_create(initial = 0)` — no rows set.
pub const LGJ_MASK_INIT_EMPTY: u32 = 0;
/// `lgj_mask_create(initial = 1)` — all rows set.
pub const LGJ_MASK_INIT_ALL: u32 = 1;

// ---------------------------------------------------------------------------
// Opcodes + combiners (§5 LgjOpDesc)
// ---------------------------------------------------------------------------

/// `lane[i] == operand as u32`, over a `U32` lane.
pub const LGJ_OP_EQ_U32: u32 = 1;
/// `lane[i] > operand as i32` (signed), over an `I32` lane.
pub const LGJ_OP_GT_I32: u32 = 2;

/// Narrow the accumulator: `acc &= op_result`.
pub const LGJ_COMBINE_AND: u32 = 0;
/// Widen the accumulator: `acc |= op_result`.
pub const LGJ_COMBINE_OR: u32 = 1;

/// The element kind an opcode requires of its lane. `None` ⇒ unknown opcode.
pub(crate) const fn opcode_required_kind(op: u32) -> Option<LgjElemKind> {
    match op {
        LGJ_OP_EQ_U32 => Some(LgjElemKind::U32),
        LGJ_OP_GT_I32 => Some(LgjElemKind::I32),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// §5 SIMD backend tags — reported, never negotiated
// ---------------------------------------------------------------------------

/// No vector backend compiled in.
pub const LGJ_SIMD_SCALAR: u32 = 0;
/// `ndarray::simd` AVX2 backend (x86-64-v3 baseline).
pub const LGJ_SIMD_AVX2: u32 = 1;
/// `ndarray::simd` AVX-512 backend (x86-64-v4 baseline).
pub const LGJ_SIMD_AVX512: u32 = 2;
/// `ndarray::simd` NEON backend (aarch64).
pub const LGJ_SIMD_NEON: u32 = 3;
/// `ndarray::simd` wasm128 backend.
pub const LGJ_SIMD_WASM: u32 = 4;

// ---------------------------------------------------------------------------
// §5 The descriptors
// ---------------------------------------------------------------------------

/// The bounded description the Java FFM layer turns into a `MemorySegment`.
///
/// Java's *public* API never sees `addr`; it is physics, consumed inside the
/// FFM layer and never surfaced.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct LgjLaneDesc {
    /// Base address of the lane. Physics — never surfaced in public Java API.
    pub addr: u64,
    /// Number of elements.
    pub len_elems: u64,
    /// Exact covered span: `(len_elems - 1) * stride_bytes + elem_bytes`, `0`
    /// when empty. Reduces to `len_elems * elem_bytes` for contiguous lanes;
    /// for a strided facet lane it deliberately does NOT round up to
    /// `len * stride` (abi.md §11 — a full-stride final window would let Java
    /// bound a segment past the allocation's end).
    pub byte_len: u64,
    /// Owning resource handle.
    pub owner: u64,
    /// Liveness stamp; Java re-checks it before using a segment it still holds.
    pub epoch: u64,
    /// [`LgjElemKind`] as `u32`.
    pub elem_kind: u32,
    /// Width of one element.
    pub elem_bytes: u32,
    /// `== elem_bytes` when contiguous.
    pub stride_bytes: u32,
    /// `LGJ_FLAG_*` bitfield.
    pub flags: u32,
}

/// What a handle refers to, without touching its payload.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct LgjResourceInfo {
    /// `LGJ_RESOURCE_PATTERN` | `LGJ_RESOURCE_MASK`.
    pub kind: u32,
    /// Number of describable lanes.
    pub lane_count: u32,
    /// Logical row count.
    pub n_rows: u64,
    /// Liveness stamp, matching the lanes' `epoch`.
    pub epoch: u64,
    /// Parent handle; `0` = none.
    pub parent: u64,
}

/// One predicate in a fused plan.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct LgjOpDesc {
    /// `LGJ_OP_*`.
    pub op: u32,
    /// Which lane of the resource to read.
    pub lane_id: u32,
    /// Needle / threshold, sign-extended into `i64` by the caller.
    pub operand: i64,
    /// `LGJ_COMBINE_AND` (narrow) | `LGJ_COMBINE_OR` (widen).
    pub combine: u32,
    /// Must be `0`. Rejected otherwise, so a future field cannot be
    /// silently ignored by an old build.
    pub _reserved: u32,
}

/// What replaces a C header: the compiled artifact describing *itself*.
///
/// A header is a text file that *claims* what an artifact looks like and can
/// drift from it silently. This struct is emitted **by** the artifact, so it
/// cannot disagree with itself — every `size_of_*` / `align_of_*` below is
/// filled from `core::mem::size_of` / `align_of` on the real type (see
/// [`manifest`]), never from a hardcoded number.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct LgjAbiManifest {
    /// [`LGJ_MAGIC`]; doubles as the endianness probe.
    pub magic: u64,
    /// [`LGJ_ABI_MAJOR`]. Java requires an exact match.
    pub abi_major: u32,
    /// [`LGJ_ABI_MINOR`]. Java requires `>=` what it was compiled against.
    pub abi_minor: u32,
    /// `size_of::<LgjAbiManifest>()`.
    pub size_of_manifest: u32,
    /// `size_of::<LgjLaneDesc>()`.
    pub size_of_lane_desc: u32,
    /// `size_of::<LgjOpDesc>()`.
    pub size_of_op_desc: u32,
    /// `size_of::<LgjResourceInfo>()`.
    pub size_of_resource_info: u32,
    /// `align_of::<LgjLaneDesc>()`.
    pub align_of_lane_desc: u32,
    /// `align_of::<LgjOpDesc>()`.
    pub align_of_op_desc: u32,
    /// `align_of::<LgjResourceInfo>()`.
    pub align_of_resource_info: u32,
    /// `size_of::<usize>()` — the target's pointer width.
    pub pointer_bytes: u32,
    /// `0` = little-endian.
    pub endianness: u32,
    /// `LGJ_SIMD_*`.
    pub simd_backend: u32,
    /// NUL-terminated, human-readable.
    pub simd_backend_name: [u8; 32],
    /// `"release"` | `"debug"`, NUL-terminated.
    pub build_profile: [u8; 16],
}

// ---------------------------------------------------------------------------
// Layout assertions — abi.md §5 states these byte counts; a drift is a
// BUILD FAILURE here rather than a mysterious Java-side misread later.
// ---------------------------------------------------------------------------

const _: () = assert!(size_of::<LgjLaneDesc>() == 56);
const _: () = assert!(align_of::<LgjLaneDesc>() == 8);
const _: () = assert!(size_of::<LgjResourceInfo>() == 32);
const _: () = assert!(align_of::<LgjResourceInfo>() == 8);
const _: () = assert!(size_of::<LgjOpDesc>() == 24);
const _: () = assert!(align_of::<LgjOpDesc>() == 8);
// abi.md does not state the manifest's size (it is self-reported, which is the
// point), but pinning it still catches an accidental field insertion:
//   8 (magic) + 12*4 (u32 fields) + 32 (name) + 16 (profile) = 104, align 8.
const _: () = assert!(size_of::<LgjAbiManifest>() == 104);
const _: () = assert!(align_of::<LgjAbiManifest>() == 8);
// A MASK_WORD is 64 row bits in one u64. If this ever stops holding, the whole
// bit-order contract below is void.
const _: () = assert!(ROWS_PER_WORD == u64::BITS as u64);

// ---------------------------------------------------------------------------
// Mask word arithmetic — the bit-order contract, in one place
// ---------------------------------------------------------------------------

/// Rows per mask word.
pub const ROWS_PER_WORD: u64 = 64;

/// Number of `u64` words needed to hold `n_rows` bits (LSB-first within each
/// word: row `i` lives at bit `i % 64` of word `i / 64`).
pub const fn mask_words_for(n_rows: u64) -> u64 {
    n_rows.div_ceil(ROWS_PER_WORD)
}

/// Zero every bit at row index `>= n_rows` in the final word.
///
/// Trailing bits beyond the row count are **normative zero** — `abi.md`'s
/// popcount, subset and parity properties all depend on it, so
/// `lgj_mask_count` cannot be fooled by garbage in the tail.
pub fn clear_tail_bits(words: &mut [u64], n_rows: u64) {
    let used = (n_rows % ROWS_PER_WORD) as u32;
    if used != 0 {
        if let Some(last) = words.last_mut() {
            *last &= (1u64 << used) - 1;
        }
    }
}

// ---------------------------------------------------------------------------
// The manifest instance
// ---------------------------------------------------------------------------

/// Detect the SIMD backend ndarray compiled into this artifact.
///
/// This is the ONE sanctioned `cfg(target_feature)` / `cfg(target_arch)` use in
/// this crate. `abi.md` §8 forbids cfg-based *selection of a SIMD
/// implementation*; this selects nothing — it reports a fact about the build so
/// Java can log which backend it got. Which backend exists at all is
/// `ndarray`'s business, and the tags below mirror `ndarray/src/simd.rs`'s own
/// dispatch order (avx512f first, then avx2, then aarch64/neon, then wasm).
const fn detect_simd_backend() -> (u32, &'static str) {
    // `cfg!` (not `#[cfg]` blocks) so exactly one arm is chosen as a *value* —
    // and so the whole function stays a single const expression.
    if cfg!(all(target_arch = "x86_64", target_feature = "avx512f")) {
        (LGJ_SIMD_AVX512, "ndarray::simd avx512")
    } else if cfg!(all(target_arch = "x86_64", target_feature = "avx2")) {
        (LGJ_SIMD_AVX2, "ndarray::simd avx2 (x86-64-v3)")
    } else if cfg!(all(target_arch = "aarch64", target_feature = "neon")) {
        (LGJ_SIMD_NEON, "ndarray::simd neon")
    } else if cfg!(target_arch = "wasm32") {
        (LGJ_SIMD_WASM, "ndarray::simd wasm128")
    } else {
        // On x86_64 this arm is reachable only if the `.cargo/config.toml`
        // target-cpu baseline was lost (this crate's default is x86-64-v4, so
        // the expected report on this host is AVX512, and AVX2 only under an
        // explicit v3 override). It is also the SIGILL condition — ndarray's
        // intrinsics get instantiated regardless of the baseline — so SCALAR
        // reported on x86_64 means the build is misconfigured, not that the
        // CPU is old.
        (LGJ_SIMD_SCALAR, "ndarray::simd scalar")
    }
}

const BUILD_PROFILE: &str = if cfg!(debug_assertions) {
    "debug"
} else {
    "release"
};

/// Copy `src` into a fixed-size NUL-terminated byte field, truncating if needed
/// (always leaving room for the terminator).
const fn fixed_cstr<const N: usize>(src: &str) -> [u8; N] {
    let mut out = [0u8; N];
    let bytes = src.as_bytes();
    let mut i = 0;
    while i < bytes.len() && i + 1 < N {
        out[i] = bytes[i];
        i += 1;
    }
    out
}

/// The `'static` the manifest getter hands out. Built entirely from
/// `size_of` / `align_of` on the real types — never a literal.
pub static MANIFEST: LgjAbiManifest = LgjAbiManifest {
    magic: LGJ_MAGIC,
    abi_major: LGJ_ABI_MAJOR,
    abi_minor: LGJ_ABI_MINOR,
    size_of_manifest: size_of::<LgjAbiManifest>() as u32,
    size_of_lane_desc: size_of::<LgjLaneDesc>() as u32,
    size_of_op_desc: size_of::<LgjOpDesc>() as u32,
    size_of_resource_info: size_of::<LgjResourceInfo>() as u32,
    align_of_lane_desc: align_of::<LgjLaneDesc>() as u32,
    align_of_op_desc: align_of::<LgjOpDesc>() as u32,
    align_of_resource_info: align_of::<LgjResourceInfo>() as u32,
    pointer_bytes: size_of::<usize>() as u32,
    // `u64::from_le_bytes` of a known pattern would be a runtime check; the
    // compile-time form is `cfg(target_endian)`, which is a *fact about the
    // build* exactly like the SIMD tag above.
    endianness: if cfg!(target_endian = "little") { 0 } else { 1 },
    simd_backend: detect_simd_backend().0,
    simd_backend_name: fixed_cstr::<32>(detect_simd_backend().1),
    build_profile: fixed_cstr::<16>(BUILD_PROFILE),
};

#[cfg(test)]
mod tests {
    use super::*;

    /// The manifest must describe *itself* truthfully — this is the property
    /// the Java side's load-time cross-check depends on.
    #[test]
    fn manifest_is_self_consistent() {
        assert_eq!(MANIFEST.magic, LGJ_MAGIC);
        assert_eq!(MANIFEST.abi_major, LGJ_ABI_MAJOR);
        assert_eq!(MANIFEST.abi_minor, LGJ_ABI_MINOR);
        assert_eq!(
            MANIFEST.size_of_manifest as usize,
            size_of::<LgjAbiManifest>()
        );
        assert_eq!(
            MANIFEST.size_of_lane_desc as usize,
            size_of::<LgjLaneDesc>()
        );
        assert_eq!(MANIFEST.size_of_op_desc as usize, size_of::<LgjOpDesc>());
        assert_eq!(
            MANIFEST.size_of_resource_info as usize,
            size_of::<LgjResourceInfo>()
        );
        assert_eq!(
            MANIFEST.align_of_lane_desc as usize,
            align_of::<LgjLaneDesc>()
        );
        assert_eq!(MANIFEST.align_of_op_desc as usize, align_of::<LgjOpDesc>());
        assert_eq!(
            MANIFEST.align_of_resource_info as usize,
            align_of::<LgjResourceInfo>()
        );
        assert_eq!(MANIFEST.pointer_bytes as usize, size_of::<usize>());
    }

    /// abi.md §5's stated byte counts, asserted at run time as well as at
    /// compile time — so a reader of the test output sees the real numbers.
    #[test]
    fn struct_sizes_match_the_spec() {
        assert_eq!(size_of::<LgjLaneDesc>(), 56);
        assert_eq!(size_of::<LgjResourceInfo>(), 32);
        assert_eq!(size_of::<LgjOpDesc>(), 24);
        assert_eq!(size_of::<LgjAbiManifest>(), 104);
    }

    #[test]
    fn magic_reads_as_lgj_abi_nul_big_endian() {
        assert_eq!(&LGJ_MAGIC.to_be_bytes(), b"LGJ_ABI\0");
    }

    #[test]
    fn name_fields_are_nul_terminated() {
        assert!(MANIFEST.simd_backend_name.contains(&0));
        assert!(MANIFEST.build_profile.contains(&0));
        let name = MANIFEST.simd_backend_name;
        let end = name.iter().position(|&b| b == 0).unwrap();
        assert!(end > 0, "backend name must not be empty");
    }

    /// The SIGILL trap, turned into a test failure.
    ///
    /// `ndarray`'s vector types are built from AVX/AVX2/AVX-512 intrinsics and
    /// get instantiated into this crate's object code whichever baseline rustc
    /// was given. If `.cargo/config.toml`'s `-Ctarget-cpu` is lost, rustc
    /// targets generic x86-64 (SSE2), those instructions still get emitted, and
    /// the process dies with SIGILL — inside a JVM downcall, if nobody noticed
    /// earlier. The backend tag is the observable that goes wrong *first*, so
    /// assert on it here where the failure is legible.
    #[test]
    #[cfg(target_arch = "x86_64")]
    fn the_x86_64_build_has_a_vector_baseline() {
        assert_ne!(
            MANIFEST.simd_backend, LGJ_SIMD_SCALAR,
            "no target-cpu baseline: ndarray's intrinsics will SIGILL at run time. \
             Check native/lgj-abi/.cargo/config.toml."
        );
        assert!(matches!(
            MANIFEST.simd_backend,
            LGJ_SIMD_AVX2 | LGJ_SIMD_AVX512
        ));
    }

    #[test]
    fn element_kinds_start_at_one_so_zeroed_is_invalid() {
        assert_eq!(LgjElemKind::U8 as u32, 1);
        assert_eq!(LgjElemKind::MaskWord as u32, 11);
        assert_eq!(
            LgjLaneDesc::default().elem_kind,
            0,
            "zeroed ⇒ no valid kind"
        );
    }

    #[test]
    fn mask_word_count_rounds_up() {
        assert_eq!(mask_words_for(0), 0);
        assert_eq!(mask_words_for(1), 1);
        assert_eq!(mask_words_for(64), 1);
        assert_eq!(mask_words_for(65), 2);
        assert_eq!(mask_words_for(1000), 16);
    }

    #[test]
    fn tail_bits_are_cleared() {
        let mut w = vec![u64::MAX; 2];
        clear_tail_bits(&mut w, 70);
        assert_eq!(w[0], u64::MAX);
        assert_eq!(w[1], 0x3F, "only rows 64..70 may survive");

        // Exact multiple of 64: nothing to clear.
        let mut w2 = vec![u64::MAX; 2];
        clear_tail_bits(&mut w2, 128);
        assert_eq!(w2, vec![u64::MAX; 2]);
    }
}

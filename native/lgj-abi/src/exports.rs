//! The `extern "C"` surface — the membrane itself.
//!
//! # `extern "C"` is a calling convention, not a language
//!
//! Every symbol below is `extern "C"`, which on this target means the **System V
//! AMD64 psABI** (AAPCS64 on ARM64): which registers carry which argument, how
//! aggregates are passed, who saves what. It is a machine contract. There is no
//! C source, no `.h`, no C compiler, and no JNI anywhere in this project. Java
//! reaches these symbols with `Linker.nativeLinker()`, which is the JVM's own
//! implementation of the same psABI.
//!
//! # Two invariants every function here obeys
//!
//! 1. **Bulk or lifecycle** (abi.md §6). A function either does work
//!    proportional to `n_rows` or it is open/close/describe. There is no
//!    per-element crossing and no `lgj_lane_read_element` — if Java wants one
//!    row it reads the `MemorySegment` in-process, with no crossing at all.
//! 2. **Panics never cross** (abi.md §9). Every body runs inside
//!    [`guard`], which converts an unwind into [`LGJ_ERR_PANIC`]. An unwind into
//!    JVM frames is UB; a negative status is a Tuesday.
//!
//! `out_*` parameters are written **only on `OK`**, so a failed call cannot
//! leave Java reading a half-filled descriptor.

use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::abi::*;
use crate::fixture::PATTERN_LANE_COUNT;
use crate::kernels::{self, LaneView, Path};
use crate::registry::{self, ResourceEntry};

/// Run `f`, converting a panic into [`LGJ_ERR_PANIC`].
///
/// `AssertUnwindSafe` is required because the closures below capture raw
/// pointers. It is sound here for the reason that matters: on the unwind path we
/// return a status and touch *nothing* the panic could have left inconsistent —
/// the only shared state is the registry, whose locks recover via `into_inner`
/// and whose payloads carry no invariant beyond "tail bits are zero", which
/// every write path re-establishes before returning.
#[inline]
fn guard<F: FnOnce() -> i32>(f: F) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(status) => status,
        Err(_) => LGJ_ERR_PANIC,
    }
}

/// Collapse a `Result<(), i32>` into a status.
#[inline]
fn status(r: Result<(), i32>) -> i32 {
    match r {
        Ok(()) => LGJ_OK,
        Err(e) => e,
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Manifest — the one symbol with no failure mode, and therefore no status
// ───────────────────────────────────────────────────────────────────────────

/// Return a pointer to the `'static` [`LgjAbiManifest`].
///
/// Never fails, never allocates. This is what replaces a C header: the artifact
/// describing itself, so it cannot disagree with itself the way a checked-in
/// header can drift from the binary beside it.
#[no_mangle]
pub extern "C" fn lgj_abi_manifest() -> *const LgjAbiManifest {
    &MANIFEST as *const LgjAbiManifest
}

// ───────────────────────────────────────────────────────────────────────────
// Lifecycle
// ───────────────────────────────────────────────────────────────────────────

/// Build the deterministic SoA fixture and return its handle.
///
/// Bulk by construction: allocates and fills three lanes of `n_rows` elements.
/// The generation algorithm is normative — see [`crate::fixture::Fixture`].
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out_handle` must be a valid, aligned, writable `u64` (Java passes an 8-byte segment). It is written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_pattern_open(n_rows: u64, seed: u64, out_handle: *mut u64) -> i32 {
    guard(|| {
        if out_handle.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        match registry::open_pattern(n_rows, seed) {
            Ok(h) => {
                // SAFETY: non-null (checked above) and Java passes a segment of
                // at least 8 bytes; the write happens only on success, so a
                // failed open never scribbles on the caller's slot.
                unsafe { *out_handle = h };
                LGJ_OK
            }
            Err(e) => e,
        }
    })
}

/// Free a resource: its lanes are dropped, its generation is bumped, and its
/// children begin failing with `PARENT_CLOSED`.
///
/// A second close, or a fabricated handle, returns `INVALID_HANDLE` — never a
/// double free.
#[no_mangle]
pub extern "C" fn lgj_close(handle: u64) -> i32 {
    guard(|| status(registry::close(handle)))
}

/// Describe a resource without touching its payload.
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out` must be a valid, aligned, writable `LgjResourceInfo` (32 bytes, align 8 — the manifest reports both). Written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_resource_info(handle: u64, out: *mut LgjResourceInfo) -> i32 {
    guard(|| {
        if out.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        let entry = match registry::resolve(handle) {
            Ok(e) => e,
            Err(e) => return e,
        };
        // SAFETY: non-null, and `LgjResourceInfo` is `#[repr(C)]` with the exact
        // layout the manifest reports and Java's MemoryLayout mirrors.
        unsafe { *out = entry.info() };
        LGJ_OK
    })
}

// ───────────────────────────────────────────────────────────────────────────
// Lanes
// ───────────────────────────────────────────────────────────────────────────

/// Describe one lane of a **pattern**: `0 = ids (U64)`, `1 = classes (U32)`,
/// `2 = values (I32)`.
///
/// All pattern lanes are `READABLE | CONTIGUOUS` and never `WRITABLE`
/// (abi.md §7). The returned `addr` is stable until `lgj_close` — lanes are
/// allocated once and never moved or resized (§4).
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out` must be a valid, aligned, writable `LgjLaneDesc` (56 bytes, align 8 — the manifest reports both). Written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_lane_describe(
    handle: u64,
    lane_id: u32,
    out: *mut LgjLaneDesc,
) -> i32 {
    guard(|| {
        if out.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        let entry = match registry::resolve_kind(handle, LGJ_RESOURCE_PATTERN) {
            Ok(e) => e,
            Err(e) => return e,
        };
        let fixture = match entry.fixture() {
            Some(f) => f,
            None => return LGJ_ERR_WRONG_RESOURCE_KIND,
        };
        let (addr, len_elems, kind) = match fixture.lane_raw(lane_id) {
            Some(t) => t,
            None => return LGJ_ERR_INVALID_LANE,
        };
        let elem_bytes = kind.elem_bytes();
        let desc = LgjLaneDesc {
            addr,
            len_elems,
            byte_len: len_elems * elem_bytes as u64,
            owner: handle,
            epoch: entry.epoch,
            elem_kind: kind as u32,
            elem_bytes,
            stride_bytes: elem_bytes,
            flags: crate::fixture::Fixture::lane_flags(),
        };
        // SAFETY: non-null; `LgjLaneDesc` is `#[repr(C)]`, 56 bytes, and that
        // size is asserted at compile time and reported by the manifest.
        unsafe { *out = desc };
        LGJ_OK
    })
}

// ───────────────────────────────────────────────────────────────────────────
// Masks
// ───────────────────────────────────────────────────────────────────────────

/// Create a mask over `parent`. `initial`: `0` = empty, `1` = all rows set.
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out_handle` must be a valid, aligned, writable `u64`. Written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_mask_create(parent: u64, initial: u32, out_handle: *mut u64) -> i32 {
    guard(|| {
        if out_handle.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        match registry::create_mask(parent, initial) {
            Ok(h) => {
                // SAFETY: non-null, checked above; written only on success.
                unsafe { *out_handle = h };
                LGJ_OK
            }
            Err(e) => e,
        }
    })
}

/// Describe a mask's word lane: `MASK_WORD`, `READABLE | WRITABLE |
/// CONTIGUOUS`.
///
/// A `MASK_WORD` is a `u64` of 64 packed row bits, LSB = lowest row index. Java
/// may write these words directly through the segment — that is what
/// `WRITABLE` means, and it is the reason a `.where(...)` chain needs no
/// crossing per row.
///
/// Honest note on locking: Rust-side ops take the mask's inner lock, but Java's
/// direct writes go through the raw segment and are outside that discipline.
/// That is sound for the POC because its Java layer is single-threaded; a
/// concurrent Java writer would need a documented protocol, which this ABI
/// version does not define.
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out` must be a valid, aligned, writable `LgjLaneDesc`. Written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_mask_describe(mask: u64, out: *mut LgjLaneDesc) -> i32 {
    guard(|| {
        if out.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        let (entry, _parent) = match registry::resolve_mask_with_parent(mask) {
            Ok(p) => p,
            Err(e) => return e,
        };
        let g = match entry.read_mask() {
            Some(g) => g,
            None => return LGJ_ERR_WRONG_RESOURCE_KIND,
        };
        let len_elems = g.words.len() as u64;
        let desc = LgjLaneDesc {
            // The boxed slice's buffer never moves, so this address stays valid
            // for the mask's whole life — releasing the lock below does not
            // invalidate it.
            addr: g.words.as_ptr() as u64,
            len_elems,
            byte_len: len_elems * 8,
            owner: mask,
            epoch: entry.epoch,
            elem_kind: LgjElemKind::MaskWord as u32,
            elem_bytes: 8,
            stride_bytes: 8,
            flags: LGJ_FLAG_READABLE | LGJ_FLAG_WRITABLE | LGJ_FLAG_CONTIGUOUS,
        };
        drop(g);
        // SAFETY: non-null, checked above; `#[repr(C)]` 56-byte struct.
        unsafe { *out = desc };
        LGJ_OK
    })
}

/// Shared body of `lgj_mask_and` / `lgj_mask_or`.
///
/// Handles the three aliasing cases `abi.md` §7 permits (`dst` may alias `a` or
/// `b`) by **deduplicating before locking**: locking one `RwLock` twice from one
/// thread is a hang, and taking `&mut` and `&` to one payload is not
/// expressible. Distinct entries are then locked in address order
/// ([`registry::lock_masks_ordered`]) so no two threads can build a cycle.
fn mask_binop(a: u64, b: u64, dst: u64, combine: u32) -> i32 {
    let (ea, pa) = match registry::resolve_mask_with_parent(a) {
        Ok(t) => t,
        Err(e) => return e,
    };
    let (eb, _pb) = match registry::resolve_mask_with_parent(b) {
        Ok(t) => t,
        Err(e) => return e,
    };
    let (ed, _pd) = match registry::resolve_mask_with_parent(dst) {
        Ok(t) => t,
        Err(e) => return e,
    };

    // "All three must share the same parent and row count" (§7). Row count is
    // the property the kernels depend on; the shared-parent requirement is
    // checked too, and both map to MASK_LENGTH_MISMATCH — abi.md allocates no
    // distinct "different parents" code, and this is the code whose meaning
    // ("these masks do not belong together") covers it.
    if ea.n_rows != eb.n_rows || ea.n_rows != ed.n_rows {
        return LGJ_ERR_MASK_LENGTH_MISMATCH;
    }
    if ea.parent != eb.parent || ea.parent != ed.parent {
        return LGJ_ERR_MASK_LENGTH_MISMATCH;
    }
    let n_rows = pa.n_rows;

    let d_is_a = std::sync::Arc::ptr_eq(&ed, &ea);
    let d_is_b = std::sync::Arc::ptr_eq(&ed, &eb);
    let a_is_b = std::sync::Arc::ptr_eq(&ea, &eb);

    let path = Path::Simd;
    let result = if d_is_a && d_is_b {
        // dst = dst op dst — identity for both AND and OR. Still normalize the
        // tail so a hand-written Java word cannot leave garbage past n_rows.
        let mut g = registry::lock_masks_ordered(&[&ed]).map(|mut g| g[0].take().unwrap());
        match &mut g {
            Ok(gd) => {
                clear_tail_bits(&mut gd.words, n_rows);
                Ok(())
            }
            Err(e) => Err(*e),
        }
    } else if d_is_a || d_is_b {
        // dst aliases one operand ⇒ in-place: dst op= other.
        let other = if d_is_a { &eb } else { &ea };
        match registry::lock_masks_ordered(&[&ed, other]) {
            Ok(mut guards) => {
                let (gd, rest) = guards.split_at_mut(1);
                let gd = gd[0].as_mut().unwrap();
                let go = rest[0].as_ref().unwrap();
                let r = kernels::combine_into(path, combine, &mut gd.words, &go.words);
                if r.is_ok() {
                    clear_tail_bits(&mut gd.words, n_rows);
                }
                r
            }
            Err(e) => Err(e),
        }
    } else if a_is_b {
        // a and b are the same mask, dst is separate ⇒ dst = a op a = a.
        match registry::lock_masks_ordered(&[&ed, &ea]) {
            Ok(mut guards) => {
                let (gd, rest) = guards.split_at_mut(1);
                let gd = gd[0].as_mut().unwrap();
                let ga = rest[0].as_ref().unwrap();
                gd.words.copy_from_slice(&ga.words);
                clear_tail_bits(&mut gd.words, n_rows);
                Ok(())
            }
            Err(e) => Err(e),
        }
    } else {
        // Three distinct masks: dst = a op b, no copy.
        match registry::lock_masks_ordered(&[&ed, &ea, &eb]) {
            Ok(mut guards) => {
                let (gd, rest) = guards.split_at_mut(1);
                let gd = gd[0].as_mut().unwrap();
                let ga = rest[0].as_ref().unwrap();
                let gb = rest[1].as_ref().unwrap();
                let r = match combine {
                    LGJ_COMBINE_AND => {
                        kernels::simd_mask_and(&ga.words, &gb.words, &mut gd.words);
                        Ok(())
                    }
                    LGJ_COMBINE_OR => {
                        kernels::simd_mask_or(&ga.words, &gb.words, &mut gd.words);
                        Ok(())
                    }
                    _ => Err(LGJ_ERR_UNKNOWN_OPCODE),
                };
                if r.is_ok() {
                    clear_tail_bits(&mut gd.words, n_rows);
                }
                r
            }
            Err(e) => Err(e),
        }
    };
    status(result)
}

/// `dst = a & b`. `dst` may alias `a` or `b`. All three must share the same
/// parent and row count.
#[no_mangle]
pub extern "C" fn lgj_mask_and(a: u64, b: u64, dst: u64) -> i32 {
    guard(|| mask_binop(a, b, dst, LGJ_COMBINE_AND))
}

/// `dst = a | b`. Same aliasing and compatibility rules as [`lgj_mask_and`].
#[no_mangle]
pub extern "C" fn lgj_mask_or(a: u64, b: u64, dst: u64) -> i32 {
    guard(|| mask_binop(a, b, dst, LGJ_COMBINE_OR))
}

/// Population count of a mask — how many rows are selected.
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out_count` must be a valid, aligned, writable `u64`. Written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_mask_count(mask: u64, out_count: *mut u64) -> i32 {
    guard(|| {
        if out_count.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        let (entry, _parent) = match registry::resolve_mask_with_parent(mask) {
            Ok(t) => t,
            Err(e) => return e,
        };
        let g = match entry.read_mask() {
            Some(g) => g,
            None => return LGJ_ERR_WRONG_RESOURCE_KIND,
        };
        let count = kernels::popcount(Path::Simd, &g.words);
        drop(g);
        // SAFETY: non-null, checked above; written only on success.
        unsafe { *out_count = count };
        LGJ_OK
    })
}

// ───────────────────────────────────────────────────────────────────────────
// Unfused bulk predicates
// ───────────────────────────────────────────────────────────────────────────

/// Resolve `(pattern, mask)` for a predicate and check their row counts agree.
fn resolve_pattern_and_mask(
    res: u64,
    dst_mask: u64,
) -> Result<(std::sync::Arc<ResourceEntry>, std::sync::Arc<ResourceEntry>), i32> {
    let pattern = registry::resolve_kind(res, LGJ_RESOURCE_PATTERN)?;
    let (mask, _parent) = registry::resolve_mask_with_parent(dst_mask)?;
    // abi.md names only the row-count condition here (§3 MASK_LENGTH_MISMATCH),
    // so a mask from a *different but equally sized* pattern is accepted. That
    // is a deliberate reading: the kernels care about length, and the Java layer
    // never constructs such a pairing.
    if mask.n_rows != pattern.n_rows {
        return Err(LGJ_ERR_MASK_LENGTH_MISMATCH);
    }
    Ok((pattern, mask))
}

/// Read a pattern lane as a typed view.
fn lane_view<'a>(entry: &'a ResourceEntry, lane_id: u32) -> Result<LaneView<'a>, i32> {
    let f = entry.fixture().ok_or(LGJ_ERR_WRONG_RESOURCE_KIND)?;
    match lane_id {
        crate::fixture::LANE_IDS => Ok(LaneView::U64(f.ids())),
        crate::fixture::LANE_CLASSES => Ok(LaneView::U32(f.classes())),
        crate::fixture::LANE_VALUES => Ok(LaneView::I32(f.values())),
        _ => Err(LGJ_ERR_INVALID_LANE),
    }
}

/// One predicate, one crossing: **overwrites** `dst_mask` with
/// `lane[i] == needle`.
///
/// Composition is the caller's job (`lgj_mask_and`). This unfused form exists so
/// the fused plan has something to be benchmarked *against* and so parity can be
/// checked predicate-by-predicate — not because a chain should be built from it.
#[no_mangle]
pub extern "C" fn lgj_op_eq_u32(res: u64, lane_id: u32, needle: u32, dst_mask: u64) -> i32 {
    guard(|| {
        let (pattern, mask) = match resolve_pattern_and_mask(res, dst_mask) {
            Ok(t) => t,
            Err(e) => return e,
        };
        let lane = match lane_view(&pattern, lane_id) {
            Ok(l) => l,
            Err(e) => return e,
        };
        if lane.kind() != LgjElemKind::U32 {
            return LGJ_ERR_LANE_KIND_MISMATCH;
        }
        let mut g = match mask.write_mask() {
            Some(g) => g,
            None => return LGJ_ERR_WRONG_RESOURCE_KIND,
        };
        let n_rows = pattern.n_rows;
        status(kernels::eval_predicate(
            Path::Simd,
            LGJ_OP_EQ_U32,
            needle as i64,
            &lane,
            n_rows,
            &mut g.words,
        ))
    })
}

/// One predicate, one crossing: **overwrites** `dst_mask` with
/// `lane[i] > threshold`, signed.
#[no_mangle]
pub extern "C" fn lgj_op_gt_i32(res: u64, lane_id: u32, threshold: i32, dst_mask: u64) -> i32 {
    guard(|| {
        let (pattern, mask) = match resolve_pattern_and_mask(res, dst_mask) {
            Ok(t) => t,
            Err(e) => return e,
        };
        let lane = match lane_view(&pattern, lane_id) {
            Ok(l) => l,
            Err(e) => return e,
        };
        if lane.kind() != LgjElemKind::I32 {
            return LGJ_ERR_LANE_KIND_MISMATCH;
        }
        let mut g = match mask.write_mask() {
            Some(g) => g,
            None => return LGJ_ERR_WRONG_RESOURCE_KIND,
        };
        let n_rows = pattern.n_rows;
        status(kernels::eval_predicate(
            Path::Simd,
            LGJ_OP_GT_I32,
            threshold as i64,
            &lane,
            n_rows,
            &mut g.words,
        ))
    })
}

// ───────────────────────────────────────────────────────────────────────────
// The fused plan — N predicates, ONE crossing
// ───────────────────────────────────────────────────────────────────────────

/// Validate an entire plan **before** any work happens.
///
/// This is what makes "a bad op leaves `dst_mask` untouched" a property rather
/// than a hope: nothing is written until every op has passed. `abi.md`'s
/// monotonic-narrowing guarantee would otherwise be observable in a
/// half-applied state, and a Java caller retrying after an error would be
/// composing against garbage.
fn validate_plan(pattern: &ResourceEntry, ops: &[LgjOpDesc]) -> Result<(), i32> {
    if ops.is_empty() {
        return Err(LGJ_ERR_EMPTY_PLAN);
    }
    for op in ops {
        // A non-zero reserved field means the caller was compiled against a
        // newer ABI that gave it meaning. Refusing is the only safe reading;
        // ignoring it would silently drop semantics.
        if op._reserved != 0 {
            return Err(LGJ_ERR_NULL_ARGUMENT);
        }
        let required = opcode_required_kind(op.op).ok_or(LGJ_ERR_UNKNOWN_OPCODE)?;
        if op.combine != LGJ_COMBINE_AND && op.combine != LGJ_COMBINE_OR {
            return Err(LGJ_ERR_UNKNOWN_OPCODE);
        }
        if op.lane_id >= PATTERN_LANE_COUNT {
            return Err(LGJ_ERR_INVALID_LANE);
        }
        let lane = lane_view(pattern, op.lane_id)?;
        if lane.kind() != required {
            return Err(LGJ_ERR_LANE_KIND_MISMATCH);
        }
    }
    Ok(())
}

/// The body behind both `lgj_plan_eval` and `lgj_plan_eval_scalar` — one code
/// path, two symbols, so the parity test compares two *paths* rather than a
/// function against itself.
fn plan_eval_impl(
    res: u64,
    ops: *const LgjOpDesc,
    n_ops: u32,
    dst_mask: u64,
    out_count: *mut u64,
    path: Path,
) -> i32 {
    // EMPTY_PLAN is checked before the null test: `(null, 0)` is a caller
    // describing an empty plan, which has its own dedicated code, and reporting
    // NULL_ARGUMENT there would send a Java author looking for the wrong bug.
    if n_ops == 0 {
        return LGJ_ERR_EMPTY_PLAN;
    }
    if ops.is_null() || out_count.is_null() {
        return LGJ_ERR_NULL_ARGUMENT;
    }
    let (pattern, mask) = match resolve_pattern_and_mask(res, dst_mask) {
        Ok(t) => t,
        Err(e) => return e,
    };
    // SAFETY: `ops` is non-null (checked) and the caller states it points at
    // `n_ops` contiguous `LgjOpDesc`. Java builds that array with a
    // MemoryLayout whose size and alignment the manifest reports (24 / 8), and
    // the compile-time asserts in `abi.rs` guarantee this side agrees. The slice
    // is read-only and does not outlive this call.
    let ops: &[LgjOpDesc] = unsafe { std::slice::from_raw_parts(ops, n_ops as usize) };

    if let Err(e) = validate_plan(&pattern, ops) {
        return e;
    }

    let n_rows = pattern.n_rows;
    let n_words = mask_words_for(n_rows) as usize;

    // Accumulate into scratch, then publish. Two consequences, both wanted:
    // dst_mask is written exactly once, and an error at any point leaves it
    // byte-for-byte as it was.
    let mut acc = vec![0u64; n_words];
    // "the accumulator starts as all rows set" (§7).
    for w in acc.iter_mut() {
        *w = u64::MAX;
    }
    clear_tail_bits(&mut acc, n_rows);
    let mut scratch = vec![0u64; n_words];

    for op in ops {
        let lane = match lane_view(&pattern, op.lane_id) {
            Ok(l) => l,
            Err(e) => return e,
        };
        if let Err(e) =
            kernels::eval_predicate(path, op.op, op.operand, &lane, n_rows, &mut scratch)
        {
            return e;
        }
        if let Err(e) = kernels::combine_into(path, op.combine, &mut acc, &scratch) {
            return e;
        }
    }
    clear_tail_bits(&mut acc, n_rows);
    let count = kernels::popcount(path, &acc);

    let mut g = match mask.write_mask() {
        Some(g) => g,
        None => return LGJ_ERR_WRONG_RESOURCE_KIND,
    };
    if g.words.len() != acc.len() {
        return LGJ_ERR_MASK_LENGTH_MISMATCH;
    }
    g.words.copy_from_slice(&acc);
    drop(g);

    // SAFETY: non-null (checked above); written only on the success path.
    unsafe { *out_count = count };
    LGJ_OK
}

/// Evaluate `n_ops` predicates in **one** crossing.
///
/// This is the symbol that makes `.where(...).where(...).count()` cost one
/// downcall regardless of how many predicates or rows are involved — the
/// concrete form of abi.md §6's anti-JNI rule.
///
/// Semantics: the accumulator starts as all rows set; each op is evaluated and
/// combined per its `combine` field; the result lands in `dst_mask` and its
/// popcount in `out_count`. With every `combine = AND` the sequence narrows
/// monotonically by construction (`V(k+1) ⊆ V(k)`).
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `ops` must point to `n_ops` contiguous, initialized `LgjOpDesc` (24 bytes, align 8) that stay valid for the call; `out_count` must be a valid, aligned, writable `u64`. Both are read/written only after the null checks, and `out_count` only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_plan_eval(
    res: u64,
    ops: *const LgjOpDesc,
    n_ops: u32,
    dst_mask: u64,
    out_count: *mut u64,
) -> i32 {
    guard(|| plan_eval_impl(res, ops, n_ops, dst_mask, out_count, Path::Simd))
}

/// Identical semantics to [`lgj_plan_eval`], forced down the **scalar
/// reference** path.
///
/// Exists only so SIMD-vs-scalar parity is falsifiable *through the membrane*,
/// which is where the Java tests live. Not for production use.
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, Identical to [`lgj_plan_eval`]: `ops` must point to `n_ops` valid `LgjOpDesc` and `out_count` to a writable `u64`.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_plan_eval_scalar(
    res: u64,
    ops: *const LgjOpDesc,
    n_ops: u32,
    dst_mask: u64,
    out_count: *mut u64,
) -> i32 {
    guard(|| plan_eval_impl(res, ops, n_ops, dst_mask, out_count, Path::Scalar))
}

// ───────────────────────────────────────────────────────────────────────────
// Reduction
// ───────────────────────────────────────────────────────────────────────────

/// Sum an `I32` lane over the set bits of `mask`, widened to `i64`.
///
/// No overflow for `n_rows ≤ 2^32` on `i32` inputs, because each element is
/// widened *before* accumulation.
/// # Safety
///
/// A null pointer is *handled*, not UB: it returns `NULL_ARGUMENT`. Beyond
/// that, `out_sum` must be a valid, aligned, writable `i64`. Written only on success.
///
/// `unsafe` here is a note to Rust callers linking the `rlib`. The JVM,
/// which is the real caller, has no such concept — it upholds the same
/// contract by construction, because every pointer it passes comes from a
/// `MemorySegment` whose size and alignment it derived from the manifest.
#[no_mangle]
pub unsafe extern "C" fn lgj_reduce_sum_i32(
    res: u64,
    lane_id: u32,
    mask: u64,
    out_sum: *mut i64,
) -> i32 {
    guard(|| {
        if out_sum.is_null() {
            return LGJ_ERR_NULL_ARGUMENT;
        }
        let (pattern, maskr) = match resolve_pattern_and_mask(res, mask) {
            Ok(t) => t,
            Err(e) => return e,
        };
        let lane = match lane_view(&pattern, lane_id) {
            Ok(l) => l,
            Err(e) => return e,
        };
        let values = match lane {
            LaneView::I32(v) => v,
            _ => return LGJ_ERR_LANE_KIND_MISMATCH,
        };
        let g = match maskr.read_mask() {
            Some(g) => g,
            None => return LGJ_ERR_WRONG_RESOURCE_KIND,
        };
        let sum = kernels::masked_sum_i32(Path::Simd, values, &g.words);
        drop(g);
        // SAFETY: non-null, checked above; written only on success.
        unsafe { *out_sum = sum };
        LGJ_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixture::{Fixture, LANE_CLASSES, LANE_IDS, LANE_VALUES};

    /// Safe wrappers over the pointer-taking exports.
    ///
    /// These are not a second implementation: each one is a direct call into the
    /// exported symbol. They exist so the `unsafe` required by a Rust caller
    /// lives in one place and the assertions below read as the ABI contract
    /// rather than as pointer plumbing. Null pointers are passed straight
    /// through — the NULL_ARGUMENT tests depend on that.
    mod call {
        use super::*;

        pub fn pattern_open(n: u64, seed: u64, out: *mut u64) -> i32 {
            unsafe { lgj_pattern_open(n, seed, out) }
        }
        pub fn resource_info(h: u64, out: *mut LgjResourceInfo) -> i32 {
            unsafe { lgj_resource_info(h, out) }
        }
        pub fn lane_describe(h: u64, lane: u32, out: *mut LgjLaneDesc) -> i32 {
            unsafe { lgj_lane_describe(h, lane, out) }
        }
        pub fn mask_create(p: u64, initial: u32, out: *mut u64) -> i32 {
            unsafe { lgj_mask_create(p, initial, out) }
        }
        pub fn mask_describe(m: u64, out: *mut LgjLaneDesc) -> i32 {
            unsafe { lgj_mask_describe(m, out) }
        }
        pub fn mask_count(m: u64, out: *mut u64) -> i32 {
            unsafe { lgj_mask_count(m, out) }
        }
        pub fn plan_eval(r: u64, ops: *const LgjOpDesc, n: u32, dst: u64, out: *mut u64) -> i32 {
            unsafe { lgj_plan_eval(r, ops, n, dst, out) }
        }
        pub fn plan_eval_scalar(
            r: u64,
            ops: *const LgjOpDesc,
            n: u32,
            dst: u64,
            out: *mut u64,
        ) -> i32 {
            unsafe { lgj_plan_eval_scalar(r, ops, n, dst, out) }
        }
        pub fn reduce_sum_i32(r: u64, lane: u32, m: u64, out: *mut i64) -> i32 {
            unsafe { lgj_reduce_sum_i32(r, lane, m, out) }
        }
    }

    /// Open a pattern, returning its handle.
    fn open(n: u64, seed: u64) -> u64 {
        let mut h = 0u64;
        assert_eq!(call::pattern_open(n, seed, &mut h), LGJ_OK);
        h
    }

    fn mask(parent: u64, initial: u32) -> u64 {
        let mut h = 0u64;
        assert_eq!(call::mask_create(parent, initial, &mut h), LGJ_OK);
        h
    }

    fn count(m: u64) -> u64 {
        let mut c = 0u64;
        assert_eq!(call::mask_count(m, &mut c), LGJ_OK);
        c
    }

    /// Read a mask's words back out through the *described* segment — i.e. the
    /// same way Java sees them, not through a Rust-side back door.
    fn read_words(m: u64) -> Vec<u64> {
        let mut d = LgjLaneDesc::default();
        assert_eq!(call::mask_describe(m, &mut d), LGJ_OK);
        assert_eq!(d.elem_kind, LgjElemKind::MaskWord as u32);
        assert_ne!(d.flags & LGJ_FLAG_WRITABLE, 0);
        // SAFETY: the descriptor is exactly the contract Java relies on; the
        // lane is alive because `m` has not been closed in this scope.
        unsafe { std::slice::from_raw_parts(d.addr as *const u64, d.len_elems as usize).to_vec() }
    }

    fn op(op: u32, lane_id: u32, operand: i64, combine: u32) -> LgjOpDesc {
        LgjOpDesc {
            op,
            lane_id,
            operand,
            combine,
            _reserved: 0,
        }
    }

    // ── manifest ───────────────────────────────────────────────────────────

    #[test]
    fn manifest_pointer_is_stable_and_populated() {
        let p1 = lgj_abi_manifest();
        let p2 = lgj_abi_manifest();
        assert_eq!(p1, p2, "must be a 'static, not a fresh allocation");
        // SAFETY: a pointer to a 'static.
        let m = unsafe { &*p1 };
        assert_eq!(m.magic, LGJ_MAGIC);
        assert_eq!(m.size_of_lane_desc, 56);
        assert_eq!(m.endianness, 0, "this box is little-endian");
    }

    // ── lifecycle / handle safety ──────────────────────────────────────────

    #[test]
    fn open_describe_close_round_trip() {
        let p = open(1000, 42);
        let mut info = LgjResourceInfo::default();
        assert_eq!(call::resource_info(p, &mut info), LGJ_OK);
        assert_eq!(info.kind, LGJ_RESOURCE_PATTERN);
        assert_eq!(info.lane_count, 3);
        assert_eq!(info.n_rows, 1000);
        assert_eq!(info.parent, 0);
        assert!(info.epoch > 0);
        assert_eq!(lgj_close(p), LGJ_OK);
    }

    #[test]
    fn every_stale_handle_shape_is_a_status_not_a_crash() {
        let p = open(64, 1);
        let m = mask(p, LGJ_MASK_INIT_ALL);
        assert_eq!(lgj_close(m), LGJ_OK);
        assert_eq!(lgj_close(p), LGJ_OK);

        // use-after-close
        let mut info = LgjResourceInfo::default();
        assert_eq!(call::resource_info(p, &mut info), LGJ_ERR_INVALID_HANDLE);
        // double close
        assert_eq!(lgj_close(p), LGJ_ERR_INVALID_HANDLE);
        assert_eq!(lgj_close(m), LGJ_ERR_INVALID_HANDLE);
        // fabricated
        for bogus in [0u64, 1, 0xDEAD_BEEF, u64::MAX] {
            assert_eq!(
                call::resource_info(bogus, &mut info),
                LGJ_ERR_INVALID_HANDLE
            );
            let mut d = LgjLaneDesc::default();
            assert_eq!(
                call::lane_describe(bogus, 0, &mut d),
                LGJ_ERR_INVALID_HANDLE
            );
            let mut c = 0u64;
            assert_eq!(call::mask_count(bogus, &mut c), LGJ_ERR_INVALID_HANDLE);
        }
    }

    #[test]
    fn a_mask_whose_parent_closed_reports_parent_closed() {
        let p = open(500, 3);
        let m = mask(p, LGJ_MASK_INIT_ALL);
        assert_eq!(count(m), 500);
        assert_eq!(lgj_close(p), LGJ_OK);

        let mut c = 0u64;
        assert_eq!(call::mask_count(m, &mut c), LGJ_ERR_PARENT_CLOSED);
        let mut d = LgjLaneDesc::default();
        assert_eq!(call::mask_describe(m, &mut d), LGJ_ERR_PARENT_CLOSED);
        assert_eq!(lgj_mask_and(m, m, m), LGJ_ERR_PARENT_CLOSED);
        let ops = [op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND)];
        let mut n = 0u64;
        assert_eq!(
            call::plan_eval(p, ops.as_ptr(), 1, m, &mut n),
            LGJ_ERR_INVALID_HANDLE,
            "the pattern handle is stale, which is reported first"
        );
        // The mask itself still exists — it just cannot work.
        assert_eq!(lgj_close(m), LGJ_OK);
    }

    #[test]
    fn wrong_kind_is_reported_as_such() {
        let p = open(64, 1);
        let m = mask(p, 0);
        let mut d = LgjLaneDesc::default();
        // a mask where a pattern was required
        assert_eq!(
            call::lane_describe(m, 0, &mut d),
            LGJ_ERR_WRONG_RESOURCE_KIND
        );
        // a pattern where a mask was required
        assert_eq!(call::mask_describe(p, &mut d), LGJ_ERR_WRONG_RESOURCE_KIND);
        let mut c = 0u64;
        assert_eq!(call::mask_count(p, &mut c), LGJ_ERR_WRONG_RESOURCE_KIND);
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn null_out_pointers_are_rejected() {
        let p = open(64, 1);
        let m = mask(p, 0);
        assert_eq!(
            call::pattern_open(8, 1, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::resource_info(p, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::lane_describe(p, 0, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::mask_create(p, 0, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::mask_describe(m, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::mask_count(m, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::reduce_sum_i32(p, LANE_VALUES, m, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        let ops = [op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND)];
        let mut n = 0u64;
        assert_eq!(
            call::plan_eval(p, std::ptr::null(), 1, m, &mut n),
            LGJ_ERR_NULL_ARGUMENT
        );
        assert_eq!(
            call::plan_eval(p, ops.as_ptr(), 1, m, std::ptr::null_mut()),
            LGJ_ERR_NULL_ARGUMENT
        );
        lgj_close(m);
        lgj_close(p);
    }

    // ── lanes ──────────────────────────────────────────────────────────────

    #[test]
    fn pattern_lanes_are_readable_contiguous_and_never_writable() {
        let p = open(777, 9);
        for (lane, kind, bytes) in [
            (LANE_IDS, LgjElemKind::U64, 8u32),
            (LANE_CLASSES, LgjElemKind::U32, 4),
            (LANE_VALUES, LgjElemKind::I32, 4),
        ] {
            let mut d = LgjLaneDesc::default();
            assert_eq!(call::lane_describe(p, lane, &mut d), LGJ_OK);
            assert_eq!(d.elem_kind, kind as u32);
            assert_eq!(d.elem_bytes, bytes);
            assert_eq!(d.stride_bytes, bytes, "contiguous ⇒ stride == elem_bytes");
            assert_eq!(d.len_elems, 777);
            assert_eq!(d.byte_len, 777 * bytes as u64);
            assert_eq!(d.owner, p);
            assert_ne!(d.flags & LGJ_FLAG_READABLE, 0);
            assert_ne!(d.flags & LGJ_FLAG_CONTIGUOUS, 0);
            assert_eq!(
                d.flags & LGJ_FLAG_WRITABLE,
                0,
                "pattern lanes are read-only"
            );
            assert_ne!(d.addr, 0);
        }
        let mut d = LgjLaneDesc::default();
        assert_eq!(call::lane_describe(p, 3, &mut d), LGJ_ERR_INVALID_LANE);
        assert_eq!(
            call::lane_describe(p, u32::MAX, &mut d),
            LGJ_ERR_INVALID_LANE
        );
        lgj_close(p);
    }

    /// The lane bytes Java would read must be the fixture's bytes — this is the
    /// only test that crosses the descriptor boundary the way the FFM layer does.
    #[test]
    fn described_lane_bytes_match_the_generator() {
        let n = 300u64;
        let p = open(n, 0xABCD);
        let expected = Fixture::generate(n, 0xABCD).unwrap();
        let mut d = LgjLaneDesc::default();
        assert_eq!(call::lane_describe(p, LANE_VALUES, &mut d), LGJ_OK);
        // SAFETY: descriptor is live; `p` is open.
        let seen =
            unsafe { std::slice::from_raw_parts(d.addr as *const i32, d.len_elems as usize) };
        assert_eq!(seen, expected.values());
        lgj_close(p);
    }

    #[test]
    fn lane_epoch_matches_resource_epoch() {
        let p = open(10, 1);
        let mut info = LgjResourceInfo::default();
        call::resource_info(p, &mut info);
        let mut d = LgjLaneDesc::default();
        call::lane_describe(p, 0, &mut d);
        assert_eq!(d.epoch, info.epoch);
        lgj_close(p);
    }

    /// A reused slot must not reuse an epoch, or Java could mistake a dead
    /// segment for a live one.
    #[test]
    fn epochs_are_never_reused() {
        let p1 = open(10, 1);
        let mut i1 = LgjResourceInfo::default();
        call::resource_info(p1, &mut i1);
        lgj_close(p1);
        let p2 = open(10, 1);
        let mut i2 = LgjResourceInfo::default();
        call::resource_info(p2, &mut i2);
        assert_ne!(i1.epoch, i2.epoch);
        lgj_close(p2);
    }

    // ── masks ──────────────────────────────────────────────────────────────

    #[test]
    fn mask_create_initial_states() {
        let p = open(1000, 1);
        let empty = mask(p, LGJ_MASK_INIT_EMPTY);
        let all = mask(p, LGJ_MASK_INIT_ALL);
        assert_eq!(count(empty), 0);
        assert_eq!(count(all), 1000, "tail bits must not inflate the count");
        let mut h = 0u64;
        assert_ne!(call::mask_create(p, 7, &mut h), LGJ_OK);
        lgj_close(all);
        lgj_close(empty);
        lgj_close(p);
    }

    #[test]
    fn mask_and_or_over_distinct_masks() {
        let p = open(200, 1);
        let a = mask(p, LGJ_MASK_INIT_ALL);
        let b = mask(p, LGJ_MASK_INIT_EMPTY);
        let dst = mask(p, LGJ_MASK_INIT_ALL);

        assert_eq!(lgj_mask_and(a, b, dst), LGJ_OK);
        assert_eq!(count(dst), 0);
        assert_eq!(lgj_mask_or(a, b, dst), LGJ_OK);
        assert_eq!(count(dst), 200);

        for h in [dst, b, a, p] {
            lgj_close(h);
        }
    }

    /// abi.md §7: "`dst` may alias `a` or `b`." All four aliasing shapes.
    #[test]
    fn mask_binop_alias_cases() {
        let p = open(300, 5);
        let all = mask(p, LGJ_MASK_INIT_ALL);
        let empty = mask(p, LGJ_MASK_INIT_EMPTY);

        // dst == a
        let x = mask(p, LGJ_MASK_INIT_ALL);
        assert_eq!(lgj_mask_and(x, empty, x), LGJ_OK);
        assert_eq!(count(x), 0);
        assert_eq!(lgj_mask_or(x, all, x), LGJ_OK);
        assert_eq!(count(x), 300);

        // dst == b
        let y = mask(p, LGJ_MASK_INIT_ALL);
        assert_eq!(lgj_mask_and(empty, y, y), LGJ_OK);
        assert_eq!(count(y), 0);
        assert_eq!(lgj_mask_or(all, y, y), LGJ_OK);
        assert_eq!(count(y), 300);

        // dst == a == b  (identity)
        let z = mask(p, LGJ_MASK_INIT_ALL);
        assert_eq!(lgj_mask_and(z, z, z), LGJ_OK);
        assert_eq!(count(z), 300);
        assert_eq!(lgj_mask_or(z, z, z), LGJ_OK);
        assert_eq!(count(z), 300);

        // a == b, dst distinct  (copy)
        let w = mask(p, LGJ_MASK_INIT_EMPTY);
        assert_eq!(lgj_mask_and(all, all, w), LGJ_OK);
        assert_eq!(count(w), 300);

        for h in [w, z, y, x, empty, all, p] {
            lgj_close(h);
        }
    }

    #[test]
    fn mask_binop_rejects_mismatched_row_counts() {
        let p1 = open(100, 1);
        let p2 = open(200, 1);
        let a = mask(p1, LGJ_MASK_INIT_ALL);
        let b = mask(p2, LGJ_MASK_INIT_ALL);
        assert_eq!(lgj_mask_and(a, b, a), LGJ_ERR_MASK_LENGTH_MISMATCH);
        for h in [b, a, p2, p1] {
            lgj_close(h);
        }
    }

    /// Masks of equal length but different parents are still not composable —
    /// §7 requires a shared parent.
    #[test]
    fn mask_binop_rejects_different_parents() {
        let p1 = open(128, 1);
        let p2 = open(128, 2);
        let a = mask(p1, LGJ_MASK_INIT_ALL);
        let b = mask(p2, LGJ_MASK_INIT_ALL);
        assert_eq!(lgj_mask_and(a, b, a), LGJ_ERR_MASK_LENGTH_MISMATCH);
        for h in [b, a, p2, p1] {
            lgj_close(h);
        }
    }

    // ── unfused predicates ─────────────────────────────────────────────────

    #[test]
    fn unfused_predicates_match_a_hand_count() {
        let n = 5000u64;
        let p = open(n, 0x1234);
        let f = Fixture::generate(n, 0x1234).unwrap();
        let m = mask(p, LGJ_MASK_INIT_EMPTY);

        assert_eq!(lgj_op_eq_u32(p, LANE_CLASSES, 7, m), LGJ_OK);
        let want = f.classes().iter().filter(|&&c| c == 7).count() as u64;
        assert_eq!(count(m), want);
        assert!(want > 0 && want < n, "predicate must not be vacuous");

        assert_eq!(lgj_op_gt_i32(p, LANE_VALUES, 100, m), LGJ_OK);
        let want = f.values().iter().filter(|&&v| v > 100).count() as u64;
        assert_eq!(count(m), want);

        // Each op OVERWRITES, so re-running the first restores its own count.
        assert_eq!(lgj_op_eq_u32(p, LANE_CLASSES, 7, m), LGJ_OK);
        assert_eq!(
            count(m),
            f.classes().iter().filter(|&&c| c == 7).count() as u64
        );
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn predicates_reject_lane_kind_mismatch() {
        let p = open(64, 1);
        let m = mask(p, 0);
        // eq_u32 against the I32 lane, and against the U64 lane
        assert_eq!(
            lgj_op_eq_u32(p, LANE_VALUES, 1, m),
            LGJ_ERR_LANE_KIND_MISMATCH
        );
        assert_eq!(lgj_op_eq_u32(p, LANE_IDS, 1, m), LGJ_ERR_LANE_KIND_MISMATCH);
        // gt_i32 against the U32 lane
        assert_eq!(
            lgj_op_gt_i32(p, LANE_CLASSES, 1, m),
            LGJ_ERR_LANE_KIND_MISMATCH
        );
        assert_eq!(lgj_op_eq_u32(p, 9, 1, m), LGJ_ERR_INVALID_LANE);
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn predicate_rejects_a_mask_of_the_wrong_length() {
        let p1 = open(100, 1);
        let p2 = open(200, 1);
        let m2 = mask(p2, 0);
        assert_eq!(
            lgj_op_eq_u32(p1, LANE_CLASSES, 7, m2),
            LGJ_ERR_MASK_LENGTH_MISMATCH
        );
        for h in [m2, p2, p1] {
            lgj_close(h);
        }
    }

    // ── the fused plan ─────────────────────────────────────────────────────

    #[test]
    fn fused_plan_equals_the_unfused_composition() {
        let n = 8000u64;
        let p = open(n, 77);
        let a = mask(p, 0);
        let b = mask(p, 0);
        let fused = mask(p, 0);

        assert_eq!(lgj_op_eq_u32(p, LANE_CLASSES, 7, a), LGJ_OK);
        assert_eq!(lgj_op_gt_i32(p, LANE_VALUES, 100, b), LGJ_OK);
        assert_eq!(lgj_mask_and(a, b, a), LGJ_OK);

        let ops = [
            op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
            op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
        ];
        let mut c = 0u64;
        assert_eq!(call::plan_eval(p, ops.as_ptr(), 2, fused, &mut c), LGJ_OK);
        assert_eq!(c, count(a), "one crossing must equal three");
        assert_eq!(
            read_words(fused),
            read_words(a),
            "bit-for-bit, not just count"
        );
        assert!(c > 0, "an all-zero result would make this test vacuous");

        for h in [fused, b, a, p] {
            lgj_close(h);
        }
    }

    /// The headline parity property, through the same code path the Java tests
    /// exercise: SIMD and the independent scalar reference must agree exactly,
    /// including at row counts that are not multiples of 64.
    #[test]
    fn simd_and_scalar_plans_agree_bit_for_bit() {
        for n in [0u64, 1, 63, 64, 65, 127, 1000, 4097] {
            for seed in [0u64, 7, 0xFEED_FACE] {
                let p = open(n, seed);
                let ms = mask(p, 0);
                let mm = mask(p, 0);
                let ops = [
                    op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
                    op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_OR),
                    op(LGJ_OP_GT_I32, LANE_VALUES, -50, LGJ_COMBINE_AND),
                ];
                let (mut cs, mut cm) = (0u64, 0u64);
                assert_eq!(
                    call::plan_eval(p, ops.as_ptr(), 3, mm, &mut cm),
                    LGJ_OK,
                    "n={n}"
                );
                assert_eq!(
                    call::plan_eval_scalar(p, ops.as_ptr(), 3, ms, &mut cs),
                    LGJ_OK,
                    "n={n}"
                );
                assert_eq!(cm, cs, "count parity at n={n} seed={seed}");
                assert_eq!(
                    read_words(mm),
                    read_words(ms),
                    "word parity at n={n} seed={seed}"
                );
                for h in [mm, ms, p] {
                    lgj_close(h);
                }
            }
        }
    }

    /// Monotonic narrowing: with every combiner AND, each added op can only
    /// shrink the selection — and the surviving rows must be a literal *subset*,
    /// not merely fewer.
    #[test]
    fn all_and_plans_narrow_monotonically_and_by_subset() {
        let n = 20_000u64;
        let p = open(n, 0xC0FFEE);
        let m = mask(p, 0);
        let ops = [
            op(LGJ_OP_GT_I32, LANE_VALUES, -200, LGJ_COMBINE_AND), // matches all
            op(LGJ_OP_GT_I32, LANE_VALUES, 0, LGJ_COMBINE_AND),
            op(LGJ_OP_GT_I32, LANE_VALUES, 100, LGJ_COMBINE_AND),
            op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
        ];

        let mut prev_count = n;
        let mut prev_words: Option<Vec<u64>> = None;
        for k in 1..=ops.len() {
            let mut c = 0u64;
            assert_eq!(
                call::plan_eval(p, ops.as_ptr(), k as u32, m, &mut c),
                LGJ_OK
            );
            assert!(c <= prev_count, "count grew at k={k}: {c} > {prev_count}");
            let words = read_words(m);
            if let Some(prev) = &prev_words {
                for (i, (&w, &pw)) in words.iter().zip(prev.iter()).enumerate() {
                    assert_eq!(w & !pw, 0, "word {i} gained a row at k={k}: not a subset");
                }
            }
            prev_count = c;
            prev_words = Some(words);
        }
        // Non-vacuity: the chain must actually have narrowed, or "monotonic"
        // would be satisfied trivially by a predicate that changes nothing.
        assert!(prev_count > 0 && prev_count < n, "final count {prev_count}");
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn empty_plan_is_rejected() {
        let p = open(64, 1);
        let m = mask(p, 0);
        let ops = [op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND)];
        let mut c = 0u64;
        assert_eq!(
            call::plan_eval(p, ops.as_ptr(), 0, m, &mut c),
            LGJ_ERR_EMPTY_PLAN
        );
        // A null ops pointer with n_ops == 0 is still an empty plan, not a null
        // argument — the more specific diagnosis wins.
        assert_eq!(
            call::plan_eval(p, std::ptr::null(), 0, m, &mut c),
            LGJ_ERR_EMPTY_PLAN
        );
        lgj_close(m);
        lgj_close(p);
    }

    /// A plan containing a bad op must be rejected **without partially writing
    /// dst_mask**. Each bad plan puts a *valid* op first, so a naive
    /// evaluate-as-you-go implementation would have already written something.
    #[test]
    fn a_bad_plan_leaves_dst_mask_untouched() {
        let p = open(1000, 11);
        let m = mask(p, LGJ_MASK_INIT_ALL);
        // Give the mask a distinctive, non-trivial content first.
        assert_eq!(lgj_op_eq_u32(p, LANE_CLASSES, 3, m), LGJ_OK);
        let before = read_words(m);
        let before_count = count(m);
        assert!(before_count > 0, "fixture must make this non-vacuous");

        let good = op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND);
        let bad_plans: [(Vec<LgjOpDesc>, i32); 5] = [
            // unknown opcode, after a good op
            (
                vec![good, op(9999, LANE_CLASSES, 0, LGJ_COMBINE_AND)],
                LGJ_ERR_UNKNOWN_OPCODE,
            ),
            // lane out of range
            (
                vec![good, op(LGJ_OP_EQ_U32, 42, 0, LGJ_COMBINE_AND)],
                LGJ_ERR_INVALID_LANE,
            ),
            // opcode / lane element kind mismatch
            (
                vec![good, op(LGJ_OP_GT_I32, LANE_CLASSES, 0, LGJ_COMBINE_AND)],
                LGJ_ERR_LANE_KIND_MISMATCH,
            ),
            // unknown combiner
            (
                vec![good, op(LGJ_OP_EQ_U32, LANE_CLASSES, 1, 77)],
                LGJ_ERR_UNKNOWN_OPCODE,
            ),
            // non-zero _reserved
            (
                vec![
                    good,
                    LgjOpDesc {
                        op: LGJ_OP_EQ_U32,
                        lane_id: LANE_CLASSES,
                        operand: 1,
                        combine: LGJ_COMBINE_AND,
                        _reserved: 1,
                    },
                ],
                LGJ_ERR_NULL_ARGUMENT,
            ),
        ];

        for (plan, want) in bad_plans {
            let mut c = 12345u64;
            let got = call::plan_eval(p, plan.as_ptr(), plan.len() as u32, m, &mut c);
            assert_eq!(got, want, "wrong status for plan {plan:?}");
            assert_eq!(read_words(m), before, "dst_mask was modified by a bad plan");
            assert_eq!(count(m), before_count);
            assert_eq!(c, 12345, "out_count must not be written on failure");
            // The scalar symbol must validate identically.
            assert_eq!(
                call::plan_eval_scalar(p, plan.as_ptr(), plan.len() as u32, m, &mut c),
                want
            );
            assert_eq!(read_words(m), before);
        }
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn or_plans_widen() {
        let n = 4000u64;
        let p = open(n, 21);
        let f = Fixture::generate(n, 21).unwrap();
        let m = mask(p, 0);
        // acc starts all-set, so a lone OR stays all-set: OR is only meaningful
        // after a narrowing op. Narrow to class==7, then widen by value>300.
        let ops = [
            op(LGJ_OP_EQ_U32, LANE_CLASSES, 7, LGJ_COMBINE_AND),
            op(LGJ_OP_GT_I32, LANE_VALUES, 300, LGJ_COMBINE_OR),
        ];
        let mut c = 0u64;
        assert_eq!(call::plan_eval(p, ops.as_ptr(), 2, m, &mut c), LGJ_OK);
        let want = f
            .classes()
            .iter()
            .zip(f.values())
            .filter(|(&cl, &v)| cl == 7 || v > 300)
            .count() as u64;
        assert_eq!(c, want);
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn plan_count_equals_mask_count() {
        let p = open(3333, 4);
        let m = mask(p, 0);
        let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, 50, LGJ_COMBINE_AND)];
        let mut c = 0u64;
        assert_eq!(call::plan_eval(p, ops.as_ptr(), 1, m, &mut c), LGJ_OK);
        assert_eq!(c, count(m), "out_count must equal popcount(dst_mask)");
        lgj_close(m);
        lgj_close(p);
    }

    // ── reduction ──────────────────────────────────────────────────────────

    #[test]
    fn reduce_sum_matches_a_hand_sum() {
        let n = 6000u64;
        let p = open(n, 99);
        let f = Fixture::generate(n, 99).unwrap();
        let m = mask(p, LGJ_MASK_INIT_ALL);

        let mut sum = 0i64;
        assert_eq!(call::reduce_sum_i32(p, LANE_VALUES, m, &mut sum), LGJ_OK);
        let want: i64 = f.values().iter().map(|&v| v as i64).sum();
        assert_eq!(sum, want);
        // Negatives are in range, so a full-mask sum being merely "positive" is
        // not evidence; check the mixed-sign path explicitly.
        assert!(f.values().iter().any(|&v| v < 0));

        assert_eq!(lgj_op_gt_i32(p, LANE_VALUES, 100, m), LGJ_OK);
        assert_eq!(call::reduce_sum_i32(p, LANE_VALUES, m, &mut sum), LGJ_OK);
        let want: i64 = f
            .values()
            .iter()
            .filter(|&&v| v > 100)
            .map(|&v| v as i64)
            .sum();
        assert_eq!(sum, want);
        assert!(want > 0);

        // An empty mask sums to zero, not to garbage.
        assert_eq!(lgj_mask_and(m, mask(p, 0), m), LGJ_OK);
        assert_eq!(call::reduce_sum_i32(p, LANE_VALUES, m, &mut sum), LGJ_OK);
        assert_eq!(sum, 0);
        lgj_close(m);
        lgj_close(p);
    }

    #[test]
    fn reduce_sum_rejects_non_i32_lanes() {
        let p = open(64, 1);
        let m = mask(p, LGJ_MASK_INIT_ALL);
        let mut s = 0i64;
        assert_eq!(
            call::reduce_sum_i32(p, LANE_IDS, m, &mut s),
            LGJ_ERR_LANE_KIND_MISMATCH
        );
        assert_eq!(
            call::reduce_sum_i32(p, LANE_CLASSES, m, &mut s),
            LGJ_ERR_LANE_KIND_MISMATCH
        );
        assert_eq!(call::reduce_sum_i32(p, 12, m, &mut s), LGJ_ERR_INVALID_LANE);
        lgj_close(m);
        lgj_close(p);
    }

    // ── degenerate sizes ───────────────────────────────────────────────────

    #[test]
    fn zero_and_one_row_resources_behave() {
        for n in [0u64, 1] {
            let p = open(n, 1);
            let m = mask(p, LGJ_MASK_INIT_ALL);
            assert_eq!(count(m), n);
            let ops = [op(LGJ_OP_GT_I32, LANE_VALUES, -100_000, LGJ_COMBINE_AND)];
            let mut c = 0u64;
            assert_eq!(call::plan_eval(p, ops.as_ptr(), 1, m, &mut c), LGJ_OK);
            assert_eq!(c, n, "a match-everything predicate keeps all {n} rows");
            let mut s = 0i64;
            assert_eq!(call::reduce_sum_i32(p, LANE_VALUES, m, &mut s), LGJ_OK);
            let mut d = LgjLaneDesc::default();
            assert_eq!(call::lane_describe(p, LANE_VALUES, &mut d), LGJ_OK);
            assert_eq!(d.len_elems, n);
            lgj_close(m);
            lgj_close(p);
        }
    }

    // ── panic safety ───────────────────────────────────────────────────────

    /// A panic inside the membrane must surface as a status. Without the
    /// `catch_unwind` in [`guard`] this unwind would reach JVM frames, which is
    /// UB — so this test exercises the mechanism directly rather than trusting
    /// that no code path ever panics.
    #[test]
    fn a_panic_becomes_a_status() {
        let saved = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {})); // keep the test output clean
        let got = guard(|| {
            panic!("deliberate: simulating an internal invariant failure");
        });
        std::panic::set_hook(saved);
        assert_eq!(got, LGJ_ERR_PANIC);
        assert!(got < 0, "every failure is negative");
    }

    /// The panic path must not poison the registry into uselessness.
    #[test]
    fn the_registry_still_works_after_a_caught_panic() {
        let saved = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));
        let p = open(128, 1);
        let m = mask(p, LGJ_MASK_INIT_ALL);
        let _ = guard(|| {
            let e = registry::resolve(m).unwrap();
            let _g = e.write_mask().unwrap();
            panic!("panic while holding the mask lock");
        });
        std::panic::set_hook(saved);
        // The lock was poisoned; recovery via into_inner means this still works.
        assert_eq!(count(m), 128);
        assert_eq!(lgj_op_eq_u32(p, LANE_CLASSES, 7, m), LGJ_OK);
        lgj_close(m);
        lgj_close(p);
    }

    /// Every status the ABI can return is negative-or-zero and distinct.
    #[test]
    fn status_codes_are_distinct() {
        let all = [
            LGJ_OK,
            LGJ_ERR_NULL_ARGUMENT,
            LGJ_ERR_INVALID_HANDLE,
            LGJ_ERR_WRONG_RESOURCE_KIND,
            LGJ_ERR_INVALID_LANE,
            LGJ_ERR_LANE_KIND_MISMATCH,
            LGJ_ERR_MASK_LENGTH_MISMATCH,
            LGJ_ERR_PARENT_CLOSED,
            LGJ_ERR_VERSION_MISMATCH,
            LGJ_ERR_LENGTH_OVERFLOW,
            LGJ_ERR_UNKNOWN_OPCODE,
            LGJ_ERR_EMPTY_PLAN,
            LGJ_ERR_ALLOCATION_FAILED,
            LGJ_ERR_READ_ONLY,
            LGJ_ERR_PANIC,
        ];
        let mut sorted = all.to_vec();
        sorted.sort_unstable();
        let len = sorted.len();
        sorted.dedup();
        assert_eq!(sorted.len(), len, "status codes must be distinct");
        assert!(all.iter().skip(1).all(|&s| s < 0));
    }
}

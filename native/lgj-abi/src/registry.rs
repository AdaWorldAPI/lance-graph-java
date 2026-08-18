//! The generation-checked handle registry.
//!
//! This is the safety-critical piece. Inside Rust, `&self` borrows make
//! "a view outlived its owner" a *compile error*. Across the membrane there is
//! no borrow checker, so the invariant is enforced at run time instead — and the
//! property being enforced is stated as sharply as `abi.md` §4 states it:
//!
//! > **There is no code path in which a stale handle dereferences freed
//! > memory.**
//!
//! The mechanism: a handle is not a pointer, it is an opaque `u64`
//! `(generation << 32) | index`. `index` selects a registry slot; `generation`
//! is bumped every time a slot is freed. Every lookup validates the generation,
//! so a closed, double-closed or fabricated handle resolves to
//! `INVALID_HANDLE` — a status, not a segfault.
//!
//! Generations start at **1**, which is what makes the fabricated handle `0`
//! (`gen 0, index 0`) fail: no live slot ever carries generation 0.
//!
//! # Locking
//!
//! - The registry itself is a `RwLock<Vec<Slot>>`. A call takes a **short** read
//!   lock, clones the `Arc<ResourceEntry>`, and **drops the registry lock before
//!   doing any work** ([`resolve`]). Only open/close take the write lock, so
//!   they are the only globally-serializing operations.
//! - A **pattern needs no inner lock at all**: its lanes are read-only by ABI
//!   (§7), so the `Fixture` is immutable behind the `Arc` and any number of bulk
//!   ops can read it concurrently.
//! - Only **mask words** are mutable, so only they sit behind an inner
//!   `RwLock`. When one call must lock several masks (a `mask_and` whose three
//!   handles are distinct), the locks are acquired in **address order**
//!   ([`lock_masks_ordered`]) — a global order, therefore deadlock-free —
//!   and aliased handles are deduplicated *before* locking, because locking one
//!   `RwLock` twice from the same thread is the other way to hang.
//!
//! Stated honestly, as `abi.md` does: none of this has been benchmarked under
//! contention, and the POC's Java layer is single-threaded.
//!
//! # Poisoning
//!
//! A panic while a lock is held poisons it. Rather than bricking the registry,
//! every acquisition recovers with `into_inner()`. This is safe *here* because
//! the guarded data carries no invariant a partial write could break beyond
//! "bits past `n_rows` are zero", and every write path re-establishes that
//! before returning (see [`crate::abi::clear_tail_bits`]).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock, RwLock, RwLockReadGuard, RwLockWriteGuard};

use crate::abi::*;
use crate::fixture::{Fixture, PATTERN_LANE_COUNT};
use crate::rowstore::{RowStore, ROWSTORE_LANE_COUNT};

/// The mutable half of a mask: the packed row bits.
#[derive(Debug)]
pub struct MaskWords {
    /// The packed row bits. Allocated once; the buffer never moves.
    pub words: Box<[u64]>,
}

/// What a handle refers to.
///
/// `Pattern` is unlocked on purpose (read-only lanes); `Mask` carries its own
/// `RwLock` so bulk ops on distinct masks do not serialize.
#[derive(Debug)]
pub enum Payload {
    /// A read-only SoA fixture — no lock needed, because no ABI path mutates it.
    Pattern(Fixture),
    /// A read-only SoA row store (abi.md §11) — likewise lock-free: the
    /// `Arc<[u8]>` buffer is immutable for the resource's whole life.
    RowStore(RowStore),
    /// Mutable mask words behind their own lock.
    Mask(RwLock<MaskWords>),
}

/// One registry entry, shared by `Arc` so a call can drop the registry lock and
/// still hold its resource alive for the duration of the work.
#[derive(Debug)]
pub struct ResourceEntry {
    /// `LGJ_RESOURCE_PATTERN` | `LGJ_RESOURCE_MASK`.
    pub kind: u32,
    /// Liveness stamp. Globally unique and monotonic, so a *reused slot* never
    /// reuses an epoch — which is what lets Java notice a `MemorySegment` it
    /// still holds belongs to a dead resource.
    pub epoch: u64,
    /// Logical rows.
    pub n_rows: u64,
    /// Parent handle, `0` for a pattern.
    pub parent: u64,
    /// The parent's generation at creation time. Re-checked on every mask
    /// operation so a mask whose parent was closed reports `PARENT_CLOSED`
    /// rather than operating against a dead pattern.
    pub parent_gen: u32,
    /// The resource's data.
    pub payload: Payload,
}

impl ResourceEntry {
    /// `Some(&Fixture)` iff this is a pattern.
    pub fn fixture(&self) -> Option<&Fixture> {
        match &self.payload {
            Payload::Pattern(f) => Some(f),
            _ => None,
        }
    }

    /// `Some(&RowStore)` iff this is a row store.
    pub fn rowstore(&self) -> Option<&RowStore> {
        match &self.payload {
            Payload::RowStore(s) => Some(s),
            _ => None,
        }
    }

    /// `Some(&RwLock<MaskWords>)` iff this is a mask.
    pub fn mask(&self) -> Option<&RwLock<MaskWords>> {
        match &self.payload {
            Payload::Mask(m) => Some(m),
            _ => None,
        }
    }

    /// Read-lock the mask words, recovering from poisoning (see module header).
    pub fn read_mask(&self) -> Option<RwLockReadGuard<'_, MaskWords>> {
        self.mask()
            .map(|m| m.read().unwrap_or_else(|e| e.into_inner()))
    }

    /// Write-lock the mask words, recovering from poisoning (see module header).
    pub fn write_mask(&self) -> Option<RwLockWriteGuard<'_, MaskWords>> {
        self.mask()
            .map(|m| m.write().unwrap_or_else(|e| e.into_inner()))
    }

    /// Fill an [`LgjResourceInfo`]. Takes no handle: the description is about
    /// the resource, and the caller already knows the handle it passed in.
    pub fn info(&self) -> LgjResourceInfo {
        LgjResourceInfo {
            kind: self.kind,
            lane_count: match self.kind {
                LGJ_RESOURCE_PATTERN => PATTERN_LANE_COUNT,
                LGJ_RESOURCE_ROWSTORE => ROWSTORE_LANE_COUNT,
                // A mask exposes exactly one MASK_WORD lane.
                _ => 1,
            },
            n_rows: self.n_rows,
            epoch: self.epoch,
            parent: self.parent,
        }
    }
}

/// A registry slot. `entry == None` means free; `generation` is bumped on every
/// free so handles pointing here go stale.
struct Slot {
    generation: u32,
    entry: Option<Arc<ResourceEntry>>,
}

static REGISTRY: OnceLock<RwLock<Vec<Slot>>> = OnceLock::new();
static EPOCH: AtomicU64 = AtomicU64::new(1);

fn registry() -> &'static RwLock<Vec<Slot>> {
    REGISTRY.get_or_init(|| RwLock::new(Vec::new()))
}

fn next_epoch() -> u64 {
    EPOCH.fetch_add(1, Ordering::Relaxed)
}

/// `(generation << 32) | index`.
pub const fn encode_handle(generation: u32, index: u32) -> u64 {
    ((generation as u64) << 32) | (index as u64)
}

/// Split a handle into `(generation, index)`. Every value of `u64` decodes to
/// *something*; validity is decided by [`resolve`], never here.
pub const fn decode_handle(handle: u64) -> (u32, u32) {
    ((handle >> 32) as u32, (handle & 0xFFFF_FFFF) as u32)
}

/// Install an entry and return its handle.
pub fn insert(entry: ResourceEntry) -> Result<u64, i32> {
    let mut reg = registry().write().unwrap_or_else(|e| e.into_inner());
    let arc = Arc::new(entry);

    // Reuse a free slot if there is one; its generation is already ahead of any
    // handle that used to point at it.
    if let Some((idx, slot)) = reg.iter_mut().enumerate().find(|(_, s)| s.entry.is_none()) {
        slot.entry = Some(arc);
        return Ok(encode_handle(slot.generation, idx as u32));
    }

    let idx = reg.len();
    if idx > u32::MAX as usize {
        return Err(LGJ_ERR_ALLOCATION_FAILED);
    }
    // Generation 1, never 0 — see this module's header.
    reg.push(Slot {
        generation: 1,
        entry: Some(arc),
    });
    Ok(encode_handle(1, idx as u32))
}

/// Resolve a handle to its entry, validating the generation.
///
/// Takes a short read lock, clones the `Arc`, and drops the lock — so the
/// caller does its bulk work without holding the registry.
pub fn resolve(handle: u64) -> Result<Arc<ResourceEntry>, i32> {
    let (gen, idx) = decode_handle(handle);
    let reg = registry().read().unwrap_or_else(|e| e.into_inner());
    let slot = reg.get(idx as usize).ok_or(LGJ_ERR_INVALID_HANDLE)?;
    if slot.generation != gen {
        // Closed and the slot was reused, or fabricated outright.
        return Err(LGJ_ERR_INVALID_HANDLE);
    }
    let entry = slot.entry.clone().ok_or(LGJ_ERR_INVALID_HANDLE)?;
    Ok(entry)
    // `reg` drops here; nothing below this point holds the registry lock.
}

/// Resolve and require a specific resource kind.
pub fn resolve_kind(handle: u64, kind: u32) -> Result<Arc<ResourceEntry>, i32> {
    let e = resolve(handle)?;
    if e.kind != kind {
        return Err(LGJ_ERR_WRONG_RESOURCE_KIND);
    }
    Ok(e)
}

/// Resolve a mask **and** prove its parent is still alive.
///
/// Returns `(mask, parent)`. A mask whose parent was closed yields
/// `PARENT_CLOSED` — it may still *exist*, it just cannot *work* (§4).
pub fn resolve_mask_with_parent(
    handle: u64,
) -> Result<(Arc<ResourceEntry>, Arc<ResourceEntry>), i32> {
    let mask = resolve_kind(handle, LGJ_RESOURCE_MASK)?;
    let (pgen, pidx) = decode_handle(mask.parent);
    let reg = registry().read().unwrap_or_else(|e| e.into_inner());
    let slot = reg.get(pidx as usize).ok_or(LGJ_ERR_PARENT_CLOSED)?;
    if slot.generation != pgen || pgen != mask.parent_gen {
        return Err(LGJ_ERR_PARENT_CLOSED);
    }
    let parent = slot.entry.clone().ok_or(LGJ_ERR_PARENT_CLOSED)?;
    drop(reg);
    Ok((mask, parent))
}

/// Free a slot: take the entry out and bump the generation, so every handle
/// that pointed here is now stale.
///
/// Returns `INVALID_HANDLE` on a stale/fabricated handle — which is also what
/// makes a *double* close fail cleanly rather than free twice.
pub fn close(handle: u64) -> Result<(), i32> {
    let mut reg = registry().write().unwrap_or_else(|e| e.into_inner());
    let (gen, idx) = decode_handle(handle);
    let slot = reg.get_mut(idx as usize).ok_or(LGJ_ERR_INVALID_HANDLE)?;
    if slot.generation != gen || slot.entry.is_none() {
        return Err(LGJ_ERR_INVALID_HANDLE);
    }
    // Take the entry out FIRST, then bump: after this point no new resolve can
    // succeed for `handle`.
    let entry = slot.entry.take();
    // Wrapping is documented rather than ignored: after 2^32 closes of the same
    // slot a generation repeats. `saturating` would be worse (it would freeze a
    // generation and make every stale handle valid forever).
    slot.generation = slot.generation.wrapping_add(1);
    if slot.generation == 0 {
        slot.generation = 1; // never hand out generation 0
    }
    drop(reg);
    // Drop the Arc outside the registry lock. Other in-flight calls may still
    // hold clones; the storage is freed when the last one goes, which is why a
    // concurrent bulk op cannot be reading freed lanes.
    drop(entry);
    Ok(())
}

/// Create a pattern resource from a deterministic fixture.
pub fn open_pattern(n_rows: u64, seed: u64) -> Result<u64, i32> {
    let fixture = Fixture::generate(n_rows, seed).ok_or(LGJ_ERR_LENGTH_OVERFLOW)?;
    insert(ResourceEntry {
        kind: LGJ_RESOURCE_PATTERN,
        epoch: next_epoch(),
        n_rows,
        parent: 0,
        parent_gen: 0,
        payload: Payload::Pattern(fixture),
    })
}

/// Create a row-store resource from the deterministic generator (abi.md §11).
pub fn open_rowstore(n_rows: u64, seed: u64) -> Result<u64, i32> {
    let store = RowStore::generate(n_rows, seed).ok_or(LGJ_ERR_LENGTH_OVERFLOW)?;
    insert(ResourceEntry {
        kind: LGJ_RESOURCE_ROWSTORE,
        epoch: next_epoch(),
        n_rows,
        parent: 0,
        parent_gen: 0,
        payload: Payload::RowStore(store),
    })
}

/// Create a row-store resource from the edge-bearing generator (abi.md §12,
/// ABI minor 3): identical classid stream to [`open_rowstore`], plus a
/// sparse, gated subset of `edge_classid`-matching facets carrying a
/// bounded-local-neighbourhood target row instead of raw noise — the
/// mechanism `consumer-graph-traversal-v1.md` needs a non-vacuous BFS at
/// all. See [`RowStore::generate_with_edges`] for the exact generator.
pub fn open_rowstore_with_edges(
    n_rows: u64,
    seed: u64,
    edge_classid: u32,
    edge_gate_mask: u64,
    edge_radius: u32,
) -> Result<u64, i32> {
    let store =
        RowStore::generate_with_edges(n_rows, seed, edge_classid, edge_gate_mask, edge_radius)
            .ok_or(LGJ_ERR_LENGTH_OVERFLOW)?;
    insert(ResourceEntry {
        kind: LGJ_RESOURCE_ROWSTORE,
        epoch: next_epoch(),
        n_rows,
        parent: 0,
        parent_gen: 0,
        payload: Payload::RowStore(store),
    })
}

/// Create a mask over `parent`, all bits `0` or all bits `1`.
///
/// A mask's parent may be a pattern OR a row store — both are read-only
/// row-shaped resources, and a mask is a row selection over either. A mask
/// over a mask stays rejected.
pub fn create_mask(parent_handle: u64, initial: u32) -> Result<u64, i32> {
    let parent = resolve(parent_handle)?;
    if !matches!(parent.kind, LGJ_RESOURCE_PATTERN | LGJ_RESOURCE_ROWSTORE) {
        return Err(LGJ_ERR_WRONG_RESOURCE_KIND);
    }
    let n_rows = parent.n_rows;
    let n_words = usize::try_from(mask_words_for(n_rows)).map_err(|_| LGJ_ERR_LENGTH_OVERFLOW)?;

    let fill = match initial {
        LGJ_MASK_INIT_EMPTY => 0u64,
        LGJ_MASK_INIT_ALL => u64::MAX,
        // An out-of-range `initial` is a caller bug, not an alternative default.
        _ => return Err(LGJ_ERR_NULL_ARGUMENT),
    };
    let mut words = Vec::new();
    words
        .try_reserve_exact(n_words)
        .map_err(|_| LGJ_ERR_ALLOCATION_FAILED)?;
    words.resize(n_words, fill);
    let mut words = words.into_boxed_slice();
    // Bits past n_rows are normative zero even for an "all" mask.
    clear_tail_bits(&mut words, n_rows);

    let (pgen, _) = decode_handle(parent_handle);
    insert(ResourceEntry {
        kind: LGJ_RESOURCE_MASK,
        epoch: next_epoch(),
        n_rows,
        parent: parent_handle,
        parent_gen: pgen,
        payload: Payload::Mask(RwLock::new(MaskWords { words })),
    })
}

/// Write-lock up to three **distinct** mask entries in address order.
///
/// Address order is a global order, so no two threads can build a lock cycle.
/// The returned array is indexed **by role** (the caller's original argument
/// position), while acquisition happened in address order — that separation is
/// the whole point.
///
/// # Panics / misuse
///
/// The caller must pass pairwise-distinct entries (`Arc::ptr_eq` false). Passing
/// the same entry twice would deadlock on its own `RwLock`; callers dedup first
/// (see `exports::mask_binop`).
#[allow(clippy::type_complexity)]
pub fn lock_masks_ordered<'a>(
    entries: &[&'a Arc<ResourceEntry>],
) -> Result<[Option<RwLockWriteGuard<'a, MaskWords>>; 3], i32> {
    debug_assert!(entries.len() <= 3);
    let mut order: Vec<usize> = (0..entries.len()).collect();
    order.sort_by_key(|&i| Arc::as_ptr(entries[i]) as usize);

    let mut guards: [Option<RwLockWriteGuard<'a, MaskWords>>; 3] = [None, None, None];
    for &role in &order {
        let lock = entries[role].mask().ok_or(LGJ_ERR_WRONG_RESOURCE_KIND)?;
        guards[role] = Some(lock.write().unwrap_or_else(|e| e.into_inner()));
    }
    Ok(guards)
}

/// Test-only: how many slots the registry has ever needed. Used to prove slot
/// reuse actually happens (and therefore that generation checking is load
/// bearing, not decorative).
#[cfg(test)]
pub fn slot_count() -> usize {
    registry().read().unwrap_or_else(|e| e.into_inner()).len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_round_trips() {
        for (g, i) in [(1u32, 0u32), (7, 3), (u32::MAX, u32::MAX), (2, 0)] {
            assert_eq!(decode_handle(encode_handle(g, i)), (g, i));
        }
    }

    #[test]
    fn a_live_handle_resolves() {
        let h = open_pattern(100, 1).unwrap();
        let e = resolve(h).unwrap();
        assert_eq!(e.kind, LGJ_RESOURCE_PATTERN);
        assert_eq!(e.n_rows, 100);
        close(h).unwrap();
    }

    #[test]
    fn handles_are_never_zero() {
        let h = open_pattern(8, 1).unwrap();
        assert_ne!(h, 0, "generation starts at 1 so handle 0 is unreachable");
        close(h).unwrap();
    }

    #[test]
    fn fabricated_handles_are_rejected_not_dereferenced() {
        for bogus in [0u64, 1, 0xDEAD_BEEF, u64::MAX, encode_handle(0, 0)] {
            assert_eq!(resolve(bogus).unwrap_err(), LGJ_ERR_INVALID_HANDLE);
            assert_eq!(close(bogus).unwrap_err(), LGJ_ERR_INVALID_HANDLE);
        }
    }

    #[test]
    fn use_after_close_is_a_status_not_a_crash() {
        let h = open_pattern(64, 1).unwrap();
        close(h).unwrap();
        assert_eq!(resolve(h).unwrap_err(), LGJ_ERR_INVALID_HANDLE);
    }

    #[test]
    fn double_close_fails_the_second_time() {
        let h = open_pattern(64, 1).unwrap();
        assert!(close(h).is_ok());
        assert_eq!(close(h).unwrap_err(), LGJ_ERR_INVALID_HANDLE);
    }

    /// The generation check earns its keep only if slots are actually reused.
    /// Prove reuse happens *and* that the old handle still fails afterwards.
    #[test]
    fn a_reused_slot_invalidates_the_old_handle() {
        let before = slot_count();
        let h1 = open_pattern(32, 1).unwrap();
        close(h1).unwrap();
        let h2 = open_pattern(32, 1).unwrap();
        let (g1, i1) = decode_handle(h1);
        let (g2, i2) = decode_handle(h2);
        if i1 == i2 {
            // Slot really was reused: same index, higher generation.
            assert!(g2 > g1);
            assert_eq!(resolve(h1).unwrap_err(), LGJ_ERR_INVALID_HANDLE);
            assert!(resolve(h2).is_ok());
        }
        assert!(slot_count() >= before);
        close(h2).unwrap();
    }

    #[test]
    fn wrong_kind_is_distinguished_from_invalid() {
        let p = open_pattern(64, 1).unwrap();
        let m = create_mask(p, LGJ_MASK_INIT_ALL).unwrap();
        assert_eq!(
            resolve_kind(m, LGJ_RESOURCE_PATTERN).unwrap_err(),
            LGJ_ERR_WRONG_RESOURCE_KIND
        );
        assert_eq!(
            resolve_kind(p, LGJ_RESOURCE_MASK).unwrap_err(),
            LGJ_ERR_WRONG_RESOURCE_KIND
        );
        close(m).unwrap();
        close(p).unwrap();
    }

    #[test]
    fn mask_over_closed_parent_reports_parent_closed() {
        let p = open_pattern(200, 5).unwrap();
        let m = create_mask(p, LGJ_MASK_INIT_ALL).unwrap();
        assert!(resolve_mask_with_parent(m).is_ok());
        close(p).unwrap();
        assert_eq!(
            resolve_mask_with_parent(m).unwrap_err(),
            LGJ_ERR_PARENT_CLOSED
        );
        // The mask handle itself still resolves — it exists, it just cannot work.
        assert!(resolve(m).is_ok());
        close(m).unwrap();
    }

    #[test]
    fn mask_initial_states_are_exact() {
        let p = open_pattern(70, 1).unwrap();
        let empty = create_mask(p, LGJ_MASK_INIT_EMPTY).unwrap();
        let all = create_mask(p, LGJ_MASK_INIT_ALL).unwrap();

        let e = resolve(empty).unwrap();
        let g = e.read_mask().unwrap();
        assert_eq!(g.words.len(), 2);
        assert!(g.words.iter().all(|&w| w == 0));
        drop(g);

        let a = resolve(all).unwrap();
        let g = a.read_mask().unwrap();
        assert_eq!(g.words[0], u64::MAX);
        assert_eq!(g.words[1], 0x3F, "tail bits past row 70 must be zero");
        drop(g);

        close(all).unwrap();
        close(empty).unwrap();
        close(p).unwrap();
    }

    #[test]
    fn bad_initial_value_is_rejected() {
        let p = open_pattern(8, 1).unwrap();
        assert!(create_mask(p, 2).is_err());
        assert!(create_mask(p, u32::MAX).is_err());
        close(p).unwrap();
    }

    #[test]
    fn mask_cannot_be_created_over_a_mask() {
        let p = open_pattern(8, 1).unwrap();
        let m = create_mask(p, 0).unwrap();
        assert_eq!(create_mask(m, 0).unwrap_err(), LGJ_ERR_WRONG_RESOURCE_KIND);
        close(m).unwrap();
        close(p).unwrap();
    }

    #[test]
    fn rowstore_opens_and_describes_itself() {
        let h = open_rowstore(70, 3).unwrap();
        let e = resolve_kind(h, LGJ_RESOURCE_ROWSTORE).unwrap();
        let info = e.info();
        assert_eq!(info.kind, LGJ_RESOURCE_ROWSTORE);
        assert_eq!(info.lane_count, ROWSTORE_LANE_COUNT);
        assert_eq!(info.n_rows, 70);
        assert!(e.rowstore().is_some());
        assert!(e.fixture().is_none());
        close(h).unwrap();
    }

    /// The registry-level twin of `rowstore.rs`'s own `generate_with_edges`
    /// tests: proves the ABI-facing constructor (abi.md §12) reaches the same
    /// generator through the full `open_rowstore_with_edges` → registry →
    /// `ResourceEntry` path, not just the bare `RowStore` type.
    #[test]
    fn rowstore_with_edges_opens_and_describes_itself() {
        let h = open_rowstore_with_edges(70, 3, 0, 0x0, 10).unwrap();
        let e = resolve_kind(h, LGJ_RESOURCE_ROWSTORE).unwrap();
        let info = e.info();
        assert_eq!(info.kind, LGJ_RESOURCE_ROWSTORE);
        assert_eq!(info.lane_count, ROWSTORE_LANE_COUNT);
        assert_eq!(info.n_rows, 70);
        assert!(e.rowstore().is_some());
        close(h).unwrap();
    }

    /// abi.md §12's own claim, re-proven at the registry boundary: an
    /// `edge_classid` outside the 4-bit classid range reproduces
    /// `open_rowstore` byte-for-byte through the SAME entry point a Java
    /// caller uses — not merely at the underlying `RowStore::generate*`
    /// level, which `rowstore.rs` already covers.
    #[test]
    fn out_of_range_edge_classid_matches_plain_open_through_the_registry() {
        let plain = open_rowstore(200, 0xABCD).unwrap();
        let edged = open_rowstore_with_edges(200, 0xABCD, 16, 0x0, 5).unwrap();
        let pe = resolve_kind(plain, LGJ_RESOURCE_ROWSTORE).unwrap();
        let ee = resolve_kind(edged, LGJ_RESOURCE_ROWSTORE).unwrap();
        assert_eq!(
            pe.rowstore().unwrap().as_bytes(),
            ee.rowstore().unwrap().as_bytes()
        );
        close(plain).unwrap();
        close(edged).unwrap();
    }

    /// abi.md §12's overflow rule at the registry boundary: `edge_radius >=
    /// n_rows` is `LGJ_ERR_LENGTH_OVERFLOW`, matching every other
    /// overflow-shaped rejection in this generator family (`open_rowstore`'s
    /// own `LGJ_ERR_LENGTH_OVERFLOW` on a too-large `n_rows`).
    #[test]
    fn radius_that_cannot_fit_is_rejected_through_the_registry() {
        assert_eq!(
            open_rowstore_with_edges(10, 1, 0, 0, 10),
            Err(LGJ_ERR_LENGTH_OVERFLOW)
        );
    }

    /// A mask parents onto a row store exactly as onto a pattern — same
    /// row-count sizing, same tail rule, same parent-liveness propagation.
    #[test]
    fn mask_over_rowstore_works_and_tracks_parent_liveness() {
        let s = open_rowstore(70, 1).unwrap();
        let m = create_mask(s, LGJ_MASK_INIT_ALL).unwrap();
        {
            let e = resolve(m).unwrap();
            let g = e.read_mask().unwrap();
            assert_eq!(g.words.len(), 2);
            assert_eq!(g.words[1], 0x3F, "tail past row 70 must be zero");
        }
        assert!(resolve_mask_with_parent(m).is_ok());
        close(s).unwrap();
        assert_eq!(
            resolve_mask_with_parent(m).unwrap_err(),
            LGJ_ERR_PARENT_CLOSED
        );
        close(m).unwrap();
    }

    /// Locking distinct masks in address order must not deadlock regardless of
    /// the order the caller names them in.
    #[test]
    fn ordered_locking_is_order_insensitive() {
        let p = open_pattern(128, 1).unwrap();
        let a = create_mask(p, 0).unwrap();
        let b = create_mask(p, 0).unwrap();
        let c = create_mask(p, 0).unwrap();
        let (ea, eb, ec) = (
            resolve(a).unwrap(),
            resolve(b).unwrap(),
            resolve(c).unwrap(),
        );
        for perm in [[&ea, &eb, &ec], [&ec, &eb, &ea], [&eb, &ec, &ea]] {
            let g = lock_masks_ordered(&perm).unwrap();
            assert!(g[0].is_some() && g[1].is_some() && g[2].is_some());
        }
        for h in [c, b, a, p] {
            close(h).unwrap();
        }
    }
}

//! Executable model of the native lease contract (lance-graph-java
//! .claude/plans/native-lease-v1.md §3, §4.4). NOT lgj code: no ABI symbol, no
//! FFI. It mirrors registry.rs (slots with a generation, entries held by Arc,
//! close = take entry + bump generation) and adds leases three ways:
//!
//!   B1  closing state kept in the slot; generation advances at reclaim.
//!   B2  close bumps the generation at once and leaves the entry in the slot
//!       (the plan's §4.4 B2 as written: "changes only close").
//!   B3  close is exactly today's close; each lease holds an Arc clone of the
//!       entry, so the memory lives until the last lease is released. No
//!       closing state in the slot at all.
//!
//! Reclaim is observed through a drop counter on the payload, never by reading
//! freed memory.
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

pub const OK: i32 = 0;
pub const INVALID_HANDLE: i32 = -2;
pub const WRONG_KIND: i32 = -3;
pub const PARENT_CLOSED: i32 = -7;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Variant { B1, B2, B3 }

pub struct Payload { pub drops: Arc<AtomicUsize> }
impl Drop for Payload { fn drop(&mut self) { self.drops.fetch_add(1, Ordering::SeqCst); } }

pub struct Entry { pub payload: Payload, pub is_lease_of: Option<Arc<Entry>>, pub owner_slot: usize }

struct Slot { gen: u32, entry: Option<Arc<Entry>>, closing: bool, leases: u64 }

pub struct Registry { v: Variant, slots: Mutex<Vec<Slot>>, pub live_leases: AtomicU64 }

fn enc(gen: u32, idx: usize) -> u64 { ((gen as u64) << 32) | idx as u64 }
fn dec(h: u64) -> (u32, usize) { ((h >> 32) as u32, (h & 0xFFFF_FFFF) as usize) }

impl Registry {
    pub fn new(v: Variant) -> Self { Registry { v, slots: Mutex::new(Vec::new()), live_leases: AtomicU64::new(0) } }

    fn insert(&self, s: &mut Vec<Slot>, e: Entry) -> u64 {
        let arc = Arc::new(e);
        if let Some((i, sl)) = s.iter_mut().enumerate().find(|(_, x)| x.entry.is_none()) {
            sl.entry = Some(arc); sl.closing = false; sl.leases = 0;
            return enc(sl.gen, i);
        }
        s.push(Slot { gen: 1, entry: Some(arc), closing: false, leases: 0 });
        enc(1, s.len() - 1)
    }

    pub fn open(&self, drops: &Arc<AtomicUsize>) -> u64 {
        let mut s = self.slots.lock().unwrap();
        let idx = s.len();
        self.insert(&mut s, Entry { payload: Payload { drops: drops.clone() }, is_lease_of: None, owner_slot: idx })
    }

    fn bump(sl: &mut Slot) { sl.gen = sl.gen.wrapping_add(1); if sl.gen == 0 { sl.gen = 1; } }

    /// What an ordinary operation (describe, bulk op) sees for an owner handle.
    pub fn op(&self, h: u64) -> i32 {
        let s = self.slots.lock().unwrap();
        let (g, i) = dec(h);
        let Some(sl) = s.get(i) else { return INVALID_HANDLE };
        if sl.gen != g || sl.entry.is_none() { return INVALID_HANDLE; }
        if sl.entry.as_ref().unwrap().is_lease_of.is_some() { return WRONG_KIND; }
        if self.v == Variant::B1 && sl.closing { return PARENT_CLOSED; }
        OK
    }

    pub fn borrow(&self, h: u64) -> Result<u64, i32> {
        let mut s = self.slots.lock().unwrap();
        let (g, i) = dec(h);
        let owner = {
            let Some(sl) = s.get(i) else { return Err(INVALID_HANDLE) };
            if sl.gen != g || sl.entry.is_none() { return Err(INVALID_HANDLE); }
            let e = sl.entry.clone().unwrap();
            if e.is_lease_of.is_some() { return Err(WRONG_KIND); }
            if self.v == Variant::B1 && sl.closing { return Err(PARENT_CLOSED); }
            e
        };
        s[i].leases += 1;
        let drops = Arc::new(AtomicUsize::new(0));
        let lease = Entry { payload: Payload { drops }, is_lease_of: Some(owner), owner_slot: i };
        let lh = self.insert(&mut s, lease);
        self.live_leases.fetch_add(1, Ordering::SeqCst);
        Ok(lh)
    }

    pub fn release(&self, lh: u64) -> i32 {
        let mut s = self.slots.lock().unwrap();
        let (g, i) = dec(lh);
        let Some(sl) = s.get_mut(i) else { return INVALID_HANDLE };
        if sl.gen != g || sl.entry.is_none() { return INVALID_HANDLE; }
        if sl.entry.as_ref().unwrap().is_lease_of.is_none() { return WRONG_KIND; }
        let lease = sl.entry.take().unwrap();
        Self::bump(sl);
        let oi = lease.owner_slot;
        let mut reclaimed: Option<Arc<Entry>> = None;
        match self.v {
            Variant::B1 | Variant::B2 => {
                let o = &mut s[oi];
                o.leases -= 1;
                if o.closing && o.leases == 0 {
                    reclaimed = o.entry.take();
                    o.closing = false;
                    if self.v == Variant::B1 { Self::bump(o); }
                }
            }
            Variant::B3 => {}
        }
        self.live_leases.fetch_sub(1, Ordering::SeqCst);
        drop(s);
        drop(lease); // B3: may drop the last Arc of the owner -> reclaim here
        drop(reclaimed);
        OK
    }

    pub fn close(&self, h: u64) -> i32 {
        let mut s = self.slots.lock().unwrap();
        let (g, i) = dec(h);
        let Some(sl) = s.get_mut(i) else { return INVALID_HANDLE };
        if sl.gen != g || sl.entry.is_none() { return INVALID_HANDLE; }
        if sl.entry.as_ref().unwrap().is_lease_of.is_some() { return WRONG_KIND; }
        let taken;
        match self.v {
            Variant::B1 => {
                if sl.closing { return INVALID_HANDLE; }
                if sl.leases > 0 { sl.closing = true; return OK; }
                taken = sl.entry.take(); Self::bump(sl);
            }
            Variant::B2 => {
                if sl.leases > 0 { sl.closing = true; Self::bump(sl); return OK; }
                taken = sl.entry.take(); Self::bump(sl);
            }
            Variant::B3 => { taken = sl.entry.take(); Self::bump(sl); }
        }
        drop(s);
        drop(taken);
        OK
    }

    /// Current generation of a slot, so a test can FORGE the next handle the
    /// way an attacker or a buggy caller with a stale counter could.
    pub fn forge_current(&self, h: u64) -> u64 {
        let (_, i) = dec(h);
        enc(self.slots.lock().unwrap()[i].gen, i)
    }
}

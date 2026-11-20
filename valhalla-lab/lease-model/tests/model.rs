use lease_model::*;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

const ALL: [Variant; 3] = [Variant::B1, Variant::B2, Variant::B3];

fn d() -> Arc<AtomicUsize> { Arc::new(AtomicUsize::new(0)) }

#[test]
fn four_orders_reclaim_exactly_once_and_count_returns_to_zero() {
    for v in ALL {
        // view closes, then owner
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let l = r.borrow(o).unwrap();
        assert_eq!(r.release(l), OK); assert_eq!(x.load(Ordering::SeqCst), 0, "{v:?}");
        assert_eq!(r.close(o), OK); assert_eq!(x.load(Ordering::SeqCst), 1, "{v:?}");
        // owner closes, then view
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let l = r.borrow(o).unwrap();
        assert_eq!(r.close(o), OK); assert_eq!(x.load(Ordering::SeqCst), 0, "{v:?} freed early");
        assert_eq!(r.release(l), OK); assert_eq!(x.load(Ordering::SeqCst), 1, "{v:?}");
        assert_eq!(r.live_leases.load(Ordering::SeqCst), 0);
        // view never closes: pinned leak, visible
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let _l = r.borrow(o).unwrap();
        assert_eq!(r.close(o), OK);
        assert_eq!(x.load(Ordering::SeqCst), 0, "{v:?} a leak must pin, never free");
        assert_eq!(r.live_leases.load(Ordering::SeqCst), 1, "{v:?} leak visible in the count");
    }
}

#[test]
fn concurrent_close_and_release_reclaim_exactly_once() {
    for v in ALL {
        for _ in 0..2000 {
            let r = Arc::new(Registry::new(v)); let x = d(); let o = r.open(&x);
            let l = r.borrow(o).unwrap();
            let (r1, r2) = (r.clone(), r.clone());
            let a = std::thread::spawn(move || r1.close(o));
            let b = std::thread::spawn(move || r2.release(l));
            assert_eq!(a.join().unwrap(), OK); assert_eq!(b.join().unwrap(), OK);
            assert_eq!(x.load(Ordering::SeqCst), 1, "{v:?}");
            assert_eq!(r.live_leases.load(Ordering::SeqCst), 0);
        }
    }
}

#[test]
fn no_new_lease_and_no_op_on_a_closed_owner() {
    for v in ALL {
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let _l = r.borrow(o).unwrap();
        r.close(o);
        let want = if v == Variant::B1 { PARENT_CLOSED } else { INVALID_HANDLE };
        assert_eq!(r.borrow(o).unwrap_err(), want, "{v:?}");
        assert_eq!(r.op(o), want, "{v:?}");
    }
}

#[test]
fn double_release_is_refused_and_changes_nothing() {
    for v in ALL {
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let l1 = r.borrow(o).unwrap(); let _l2 = r.borrow(o).unwrap();
        r.close(o);
        assert_eq!(r.release(l1), OK);
        assert_eq!(r.release(l1), INVALID_HANDLE, "{v:?}");
        assert_eq!(x.load(Ordering::SeqCst), 0, "{v:?} second release must not reclaim");
        assert_eq!(r.live_leases.load(Ordering::SeqCst), 1);
    }
}

/// The finding: a handle forged with the slot's CURRENT generation after a
/// close with live leases. A real handle was never issued with it.
#[test]
fn forged_handle_after_close_with_live_lease() {
    let mut accepted = Vec::new();
    for v in ALL {
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let _l = r.borrow(o).unwrap();
        r.close(o);
        let forged = r.forge_current(o);
        if forged != o && r.op(forged) == OK { accepted.push(v); }
    }
    println!("variants that accept a forged handle on a closed, leased owner: {accepted:?}");
    assert_eq!(accepted, vec![Variant::B2], "only B2 as written should accept it");
}

/// Choice A. With a two-step borrow (A2: acquire, then describe) there is a
/// reachable state in which a lease is live and the describe is refused; Java
/// must then release on an error path. A1 fills the descriptor inside the
/// borrow's critical section, so that state does not exist.
#[test]
fn a2_gap_lease_live_while_describe_refused() {
    for v in ALL {
        let r = Registry::new(v); let x = d(); let o = r.open(&x);
        let lease = r.borrow(o).unwrap();       // A2 step 1: acquire
        r.close(o);                              // another thread closes the owner
        let describe = r.op(o);                  // A2 step 2: describe
        assert_ne!(describe, OK, "{v:?}");
        assert_eq!(r.live_leases.load(Ordering::SeqCst), 1, "{v:?} lease live, no descriptor");
        assert_eq!(r.release(lease), OK);        // the error path Java must not forget
        assert_eq!(x.load(Ordering::SeqCst), 1);
    }
}

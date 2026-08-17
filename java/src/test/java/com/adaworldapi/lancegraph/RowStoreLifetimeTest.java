package com.adaworldapi.lancegraph;

/**
 * Falsifiers for the SoA row store's ownership rules (docs/abi.md §4, §11), mirroring
 * {@link LifetimeTest}'s conventions for the flat {@code NativePattern} resource, applied here to
 * {@link RowStore} and the two views it produces ({@link Mask} — the same type
 * {@code NativePattern} uses — and {@link FacetMatchView}).
 *
 * <p>The claim under test is the same one {@link LifetimeTest} establishes for
 * {@code NativePattern}: there is no sequence of operations in which a stale handle dereferences
 * freed memory. Every attempt below must produce a specific Java exception — not a crash, not a
 * wrong answer, and not a silently-tolerated no-op — matching the existing conventions rather than
 * inventing new ones: a double close is an error (not idempotent), a child derived before the
 * close keeps working while its parent is open, stops working the moment the parent closes, and
 * closing that now-orphaned child afterward is tolerated (the caller's try-with-resources is
 * unwinding and must not be punished for the ordering) while closing it *twice* still throws.
 */
public final class RowStoreLifetimeTest {

    private RowStoreLifetimeTest() {}

    public static void main(String[] args) {
        System.out.println("RowStoreLifetimeTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("RowStoreLifetimeTest"));
        }
        Checks c = new Checks("RowStoreLifetimeTest");
        run(c);
        System.exit(c.report());
    }

    private static final long SEED = 0xABCDL;

    public static void run(Checks c) {
        c.section("use after close");
        RowStore closed = RowStore.open(1024, SEED);
        long before = closed.rowCount();
        c.eq("the resource works while open", 1024, before);
        closed.close();

        c.that("it reports itself closed", !closed.isOpen());
        c.throwsUp("rowCount() after close", ClosedResourceException.class, closed::rowCount);
        c.throwsUp("maskOfFacetClass() after close", ClosedResourceException.class,
                () -> closed.maskOfFacetClass(new FacetId(7), 9));
        c.throwsUp("facetMatches() after close", ClosedResourceException.class,
                () -> closed.facetMatches(9));

        c.section("double close");
        c.throwsUp("close() a second time", ClosedResourceException.class, closed::close);

        c.section("a Mask derived before the close");
        RowStore p = RowStore.open(1024, SEED);
        Mask held = p.maskOfFacetClass(new FacetId(7), 9);
        long liveCount = held.count();
        c.that("the mask works while its store is open", liveCount >= 0);
        p.close();

        // The Mask object still exists — Java cannot prevent that. What it must not do is work.
        // This is the exact contract LifetimeTest pins for a NativePattern-derived selection,
        // because it is the same Mask type: a child may outlive its parent as an object, but it
        // can never work again once the parent is gone.
        c.that("the mask knows it is no longer usable", !held.isOpen());
        c.throwsUp("count() on a mask whose store closed",
                ClosedResourceException.class, held::count);
        // Closing a mask orphaned by its store's close must be tolerated: the caller is doing the
        // right thing (their try-with-resources is unwinding) and must not be punished for the
        // ordering — same as LifetimeTest's "closing an orphaned selection is not an error".
        held.close();
        c.that("closing a mask orphaned by its store's close is not an error", true);
        c.throwsUp("but closing it twice still is",
                ClosedResourceException.class, held::close);

        c.section("a Mask closed before its store -- the ordinary ordering");
        try (RowStore q = RowStore.open(512, SEED)) {
            Mask m = q.maskOfFacetClass(new FacetId(3), 9);
            long n = m.count();
            m.close();
            c.throwsUp("count() on a closed mask", ClosedResourceException.class, m::count);
            c.that("the store is unharmed by its child's close", q.rowCount() == 512);
            try (Mask again = q.maskOfFacetClass(new FacetId(3), 9)) {
                c.that("and still answers queries", again.count() == n);
            }
        }

        c.section("a FacetMatchView derived before the close");
        RowStore r = RowStore.open(1024, SEED);
        FacetMatchView view = r.facetMatches(9);
        long liveRows = view.rowCount();
        c.eq("the view works while its store is open", 1024, liveRows);
        long liveCardinality = view.cardinality();
        c.that("cardinality() succeeds while the store is open", liveCardinality >= 0);
        r.close();

        // The guard is only required to fire BEFORE any segment access — the observable contract
        // is that every operation throws the right exception, not how close to native/segment
        // memory the check happens to sit.
        c.throwsUp("rowCount() on a view whose store closed",
                ClosedResourceException.class, view::rowCount);
        c.throwsUp("matchesOf() on a view whose store closed",
                ClosedResourceException.class, () -> view.matchesOf(0));
        c.throwsUp("cardinality() on a view whose store closed",
                ClosedResourceException.class, view::cardinality);

        c.section("the resource survives being asked the impossible");
        try (RowStore s = RowStore.open(256, SEED)) {
            c.throwsUp("an out-of-range facet is rejected before it can travel",
                    IllegalArgumentException.class, () -> new FacetId(32));
            try (Mask m = s.maskOfFacetClass(new FacetId(0), 9)) {
                c.that("and the store is still usable afterwards", m.count() >= 0);
            }
        }

        c.section("zero rows is a legal resource, not an error");
        try (RowStore empty = RowStore.open(0, SEED)) {
            c.eq("an empty resource has no rows", 0, empty.rowCount());
            c.eq("facetMatches over zero rows reports zero rows",
                    0, empty.facetMatches(9).rowCount());
            try (Mask m = empty.maskOfFacetClass(new FacetId(0), 9)) {
                c.eq("maskOfFacetClass over zero rows selects nothing", 0, m.count());
            }
        }
    }
}

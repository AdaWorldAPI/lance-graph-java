package com.adaworldapi.lancegraph;

/**
 * Falsifiers for the ownership rules (docs/abi.md §4).
 *
 * <p>These are the tests that matter most, because the property they establish is the one Java
 * cannot get from Rust for free. Inside Rust, a view outliving its owner is a compile error; across
 * the membrane there is no borrow checker, so the invariant has to be enforced at runtime and
 * therefore has to be <em>falsified</em> rather than asserted.
 *
 * <p>The claim under test: <strong>there is no sequence of operations in which a stale handle
 * dereferences freed memory.</strong> Every attempt below must produce a specific Java exception —
 * not a crash, not a wrong answer, and not a silently-tolerated no-op.
 *
 * <p>Note what a passing run does <em>not</em> prove: that the process would have crashed without
 * these guards. It proves the guards fire. The guards themselves are two-deep on purpose (Java
 * bookkeeping in front, generation-checked handles behind), so a defect in either one alone is
 * still contained.
 */
public final class LifetimeTest {

    private LifetimeTest() {}

    public static void main(String[] args) {
        System.out.println("LifetimeTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("LifetimeTest"));
        }
        Checks c = new Checks("LifetimeTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        c.section("use after close");
        NativePattern closed = NativePattern.open(1024);
        long before = closed.rowCount();
        c.eq("the resource works while open", 1024, before);
        closed.close();

        c.that("it reports itself closed", !closed.isOpen());
        c.throwsUp("rowCount() after close", ClosedResourceException.class, closed::rowCount);
        c.throwsUp("view() after close", ClosedResourceException.class, closed::view);
        c.throwsUp("rows() after close", ClosedResourceException.class, closed::rows);

        c.section("double close");
        c.throwsUp("close() a second time", ClosedResourceException.class, closed::close);

        c.section("a view derived before the close");
        NativePattern p = NativePattern.open(1024);
        View held = p.view().where(Pattern.CLASS.eq(7));
        long liveCount = held.count();
        c.that("the view works while its resource is open", liveCount >= 0);
        p.close();

        // The view object still exists — Java cannot prevent that. What it must not do is work.
        c.throwsUp("count() on a view whose resource closed",
                ClosedResourceException.class, held::count);
        c.throwsUp("where() on a view whose resource closed",
                ClosedResourceException.class, () -> held.where(Pattern.VALUE.gt(0)));
        c.throwsUp("sumOf() on a view whose resource closed",
                ClosedResourceException.class, () -> held.sumOf(Pattern.VALUE));
        c.throwsUp("select() on a view whose resource closed",
                ClosedResourceException.class, held::select);

        c.section("a selection that outlives its parent");
        NativePattern parent = NativePattern.open(1024);
        Mask orphan = parent.view().where(Pattern.CLASS.eq(7)).select();
        long orphanCount = orphan.count();
        c.that("the selection works while its parent is open", orphanCount >= 0);
        parent.close();

        c.that("the selection knows it is no longer usable", !orphan.isOpen());
        c.throwsUp("count() on a selection whose parent closed",
                ClosedResourceException.class, orphan::count);
        // Closing an orphan must be tolerated: the caller is doing the right thing (their
        // try-with-resources is unwinding) and must not be punished for the ordering.
        orphan.close();
        c.that("closing an orphaned selection is not an error", true);
        c.throwsUp("but closing it twice still is",
                ClosedResourceException.class, orphan::close);

        c.section("a selection closed before its parent -- the ordinary ordering");
        try (NativePattern q = NativePattern.open(512)) {
            Mask m = q.view().where(Pattern.CLASS.eq(3)).select();
            long n = m.count();
            m.close();
            c.throwsUp("count() on a closed selection", ClosedResourceException.class, m::count);
            c.that("the resource is unharmed by its child's close", q.rowCount() == 512);
            c.that("and still answers queries", q.view().where(Pattern.CLASS.eq(3)).count() == n);
        }

        c.section("the resource survives being asked the impossible");
        try (NativePattern q = NativePattern.open(256)) {
            c.throwsUp("a null predicate is rejected before it can travel",
                    NullPointerException.class, () -> q.view().where(null));
            c.that("and the resource is still usable afterwards", q.view().count() == 256);
        }

        c.section("zero rows is a legal resource, not an error");
        try (NativePattern empty = NativePattern.open(0)) {
            c.eq("an empty resource has no rows", 0, empty.rowCount());
            c.eq("and every query over it selects nothing",
                    0, empty.view().where(Pattern.CLASS.eq(7)).count());
        }
    }
}

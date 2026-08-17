package com.adaworldapi.lancegraph;

/**
 * Monotonic narrowing: adding a condition can never select more rows.
 *
 * <p>The property is meant to be <em>structural</em> — {@link View#where} intersects, and there is
 * no public composition that widens — so this test is not really checking arithmetic. It is
 * checking that the structure delivers what it promises, over a chain long enough that a single
 * mis-combined op would show up.
 *
 * <p>An anti-vacuity guard runs first. A chain whose conditions all match everything would satisfy
 * "never increases" trivially and prove nothing, so the test asserts the chain actually eliminates
 * a substantial fraction before it asserts the fraction only ever shrinks.
 */
public final class NarrowingTest {

    private NarrowingTest() {}

    public static void main(String[] args) {
        System.out.println("NarrowingTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("NarrowingTest"));
        }
        Checks c = new Checks("NarrowingTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        try (NativePattern data = NativePattern.open(65_536)) {

            c.section("a long chain, checked step by step");

            // Deliberately mixed: some conditions bite hard, some barely, one is a no-op. A chain
            // of only-hard conditions would collapse to zero and stop testing anything after the
            // second step.
            Predicate[] chain = {
                Pattern.VALUE.gt(-150),   // matches everything: the boundary case
                Pattern.CLASS.eq(7),      // bites hard, about 1/16
                Pattern.VALUE.gt(0),
                Pattern.VALUE.gt(100),
                Pattern.CLASS.eq(7),      // repeated: idempotent, must change nothing
                Pattern.VALUE.gt(200),
            };

            View v = data.view();
            long previous = v.count();
            c.eq("the unconditioned view selects everything", 65_536, previous);

            long first = previous;
            for (int i = 0; i < chain.length; i++) {
                v = v.where(chain[i]);
                long now = v.count();
                c.atMost("step " + (i + 1) + " (" + chain[i] + ") does not widen", previous, now);
                previous = now;
            }

            c.section("anti-vacuity: the chain must actually eliminate something");
            c.that("the final selection is far smaller than the first",
                    previous * 4 < first);
            c.that("but not empty, or the later steps proved nothing", previous > 0);
            c.note("65,536 -> " + previous + " rows across " + chain.length + " conditions");

            c.section("an idempotent repeat changes nothing");
            View once = data.view().where(Pattern.CLASS.eq(7));
            View twice = once.where(Pattern.CLASS.eq(7));
            c.eq("class == 7 twice equals class == 7 once", once.count(), twice.count());

            c.section("order does not change the answer");
            long ab = data.view().where(Pattern.CLASS.eq(7)).where(Pattern.VALUE.gt(100)).count();
            long ba = data.view().where(Pattern.VALUE.gt(100)).where(Pattern.CLASS.eq(7)).count();
            c.eq("intersection is commutative", ab, ba);

            c.section("the conjunction is bounded by each part");
            long onlyClass = data.view().where(Pattern.CLASS.eq(7)).count();
            long onlyValue = data.view().where(Pattern.VALUE.gt(100)).count();
            c.atMost("no larger than class == 7 alone", onlyClass, ab);
            c.atMost("no larger than value > 100 alone", onlyValue, ab);
            c.that("and strictly smaller than both, so neither part was ignored",
                    ab < onlyClass && ab < onlyValue);

            c.section("narrowing a shared view does not disturb the shared view");
            View shared = data.view().where(Pattern.CLASS.eq(7));
            long sharedBefore = shared.count();
            View branchA = shared.where(Pattern.VALUE.gt(100));
            View branchB = shared.where(Pattern.VALUE.gt(300));
            c.that("the branches differ", branchA.count() != branchB.count());
            c.eq("and the view they came from is untouched", sharedBefore, shared.count());
        }
    }
}

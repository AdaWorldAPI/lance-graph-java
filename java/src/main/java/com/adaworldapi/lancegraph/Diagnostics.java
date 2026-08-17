package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Downcalls;

/**
 * Measurement and comparison entry points. <strong>Not the consumer API.</strong>
 *
 * <p>Everything here exists so a claim this project makes can be falsified from the outside rather
 * than believed:
 *
 * <ul>
 *   <li>{@link #countUnfused} runs the same query one crossing per predicate, so the fused path can
 *       be benchmarked <em>against something</em>. Fusion is otherwise an assertion about code
 *       nobody measured.
 *   <li>{@link #countScalar} runs the same query through the scalar reference kernel, so
 *       SIMD-versus-scalar parity is falsifiable through the membrane — which is where these tests
 *       live.
 *   <li>{@link #crossings()} makes laziness observable: snapshot it, build a chain, and the delta
 *       must be zero.
 * </ul>
 *
 * <p>They are gathered in a separate, plainly-named class rather than added as methods on
 * {@link View} on purpose. A benchmark path sitting next to {@code count()} would eventually get
 * called by someone who thought it was an option; here it is impossible to reach by accident.
 */
public final class Diagnostics {

    private Diagnostics() {}

    /**
     * The production path, named explicitly for symmetry in a benchmark. Identical to
     * {@link View#count()}.
     */
    public static long countFused(View view) {
        return view.count();
    }

    /**
     * The same answer, computed with one crossing per predicate plus one per combine plus one to
     * count, instead of one crossing total.
     *
     * <p><strong>Comparison only.</strong> This is what the design forbids, kept executable so the
     * cost of forbidding it is a number rather than an opinion.
     */
    public static long countUnfused(View view) {
        return view.source().countUnfused(view.predicates());
    }

    /**
     * The same answer, forced down the scalar reference kernel with no SIMD.
     *
     * <p><strong>Parity checking only.</strong> If this ever disagrees with {@link #countFused},
     * the vectorised kernel is wrong — and the disagreement is visible from Java, not only from
     * inside Rust's own test suite.
     */
    public static long countScalar(View view) {
        return view.source().countScalar(view.predicates());
    }

    /**
     * Total membrane crossings since JVM start.
     *
     * <p>The instrument behind the laziness claim. Building and narrowing a view must not move
     * this number at all; a terminal operation must move it by a small constant that does not grow
     * with the number of predicates or rows.
     */
    public static long crossings() {
        return Downcalls.crossings();
    }
}

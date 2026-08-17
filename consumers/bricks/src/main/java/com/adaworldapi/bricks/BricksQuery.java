package com.adaworldapi.bricks;

import com.adaworldapi.lancegraph.Predicate;
import com.adaworldapi.lancegraph.View;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable, lazy, fail-closed description of a set of order rows.
 *
 * <h2>Security is a mask composed before execution, never a post-filter</h2>
 *
 * <p>{@link #where} and {@link #authorize(Role)} are the <em>same kind of operation</em>: both
 * return a new {@code BricksQuery} wrapping a {@link View} narrowed by one more {@link Predicate},
 * via {@link View#where}. There is no separate enforcement pass here that fetches rows and then
 * throws some away — authorization is a predicate in the identical lazy chain a caller's own {@code
 * where(...)} conditions live in, fused into the same single native crossing a terminal operation
 * pays for. See {@link Role} for how a role's constraint is itself a real, natively-evaluated
 * predicate rather than a Java-side branch.
 *
 * <h2>Fail-closed</h2>
 *
 * <p>Every terminal operation ({@link #count()}, {@link #sum}, {@link #sumBy}) checks first whether
 * {@link #authorize(Role)} was ever called on this chain, and throws {@link
 * UnauthorizedQueryException} if not — <strong>before</strong> touching the native side at all.
 * There is no default-allow path: a chain that never called {@code authorize} cannot be executed,
 * full stop.
 *
 * <h2>Aggregate-only egress</h2>
 *
 * <p>Every public method on this class returns exactly one of {@code BricksQuery}, {@code long}, or
 * {@code Map<Integer, Long>}. No method here returns, nor could return, anything row-shaped — there
 * is no public type in this consumer that represents a single order. A caller can learn how many
 * rows matched, or a sum, or a per-group breakdown of sums; a caller cannot ever get a row, a field
 * value from one row, or an iterator over rows. That is a structural fact about this package's
 * public surface, not a convention someone could accidentally violate from outside it.
 */
public final class BricksQuery {

    private final View view;
    private final boolean authorized;

    BricksQuery(View view, boolean authorized) {
        this.view = view;
        this.authorized = authorized;
    }

    /**
     * A new query narrowed by one more condition.
     *
     * <p>Crosses the membrane zero times, exactly as {@link View#where} does not. Does not affect
     * authorization: a chain built entirely from {@code where(...)} calls is still unauthorized
     * until {@link #authorize(Role)} is called.
     */
    public BricksQuery where(Predicate predicate) {
        return new BricksQuery(view.where(predicate), authorized);
    }

    /**
     * A new query, authorized under {@code role}.
     *
     * <p>If {@code role} carries an additional constraint (see {@link Role}), it is folded into the
     * same lazy chain via {@link View#where} — a real predicate, composed once, evaluated natively
     * alongside everything else. This is the single point in this package where a role's constraint
     * enters the chain; there is no other place authorization happens.
     */
    public BricksQuery authorize(Role role) {
        java.util.Objects.requireNonNull(role, "role");
        View next = role.constraint().map(view::where).orElse(view);
        return new BricksQuery(next, true);
    }

    /**
     * How many rows this query selects.
     *
     * @throws UnauthorizedQueryException if {@link #authorize(Role)} was never called on this chain
     */
    public long count() {
        requireAuthorized("count()");
        return view.count();
    }

    /**
     * Sum a signed 32-bit column over the rows this query selects.
     *
     * @throws UnauthorizedQueryException if {@link #authorize(Role)} was never called on this chain
     */
    public long sum(com.adaworldapi.lancegraph.I32Field field) {
        requireAuthorized("sum()");
        return view.sumOf(field);
    }

    /**
     * Sum {@code value} grouped by every possible value of {@code group}.
     *
     * <p>{@code group} is a {@link com.adaworldapi.lancegraph.U32Field}, and this consumer's fixture
     * gives such fields exactly 16 distinct values ({@code 0..15}) — see {@link Orders#REGION}. This
     * method issues one fused native query per group value (16 total), each narrowing this query's
     * already-authorized chain by one more {@code group.eq(v)} condition and summing {@code value}
     * over the result. Each of those 16 sums costs <em>two</em> native crossings — plan evaluation
     * into the selection mask, then the {@code lgj_reduce_sum_i32} reduction — so the measured
     * total is 32 crossings (unlike {@link #count()}, whose plan evaluation returns the count and
     * pays one). <strong>The crossing count scales with the number of groups, never with the
     * number of rows</strong> — the same laziness guarantee every other terminal operation in this
     * codebase carries, just paid per group instead of once.
     *
     * <p>Every group value {@code 0..15} appears as a key in the returned map, including groups with
     * zero matching rows (mapped to a sum of {@code 0L}): a group's absence from a real dataset is
     * itself a legitimate aggregate fact, not something to hide by omitting the key.
     *
     * <p>If measurement ever shows this 32-crossing loop is a bottleneck, a native grouped-aggregate
     * kernel (one crossing, sixteen output buckets) is the natural W6-tier follow-up — not built
     * here, because nothing has measured a need for it yet.
     *
     * @throws UnauthorizedQueryException if {@link #authorize(Role)} was never called on this chain
     */
    public Map<Integer, Long> sumBy(
            com.adaworldapi.lancegraph.U32Field group, com.adaworldapi.lancegraph.I32Field value) {
        requireAuthorized("sumBy()");
        java.util.Objects.requireNonNull(group, "group");
        java.util.Objects.requireNonNull(value, "value");
        Map<Integer, Long> result = new LinkedHashMap<>(16);
        for (int v = 0; v < 16; v++) {
            result.put(v, view.where(group.eq(v)).sumOf(value));
        }
        return result;
    }

    private void requireAuthorized(String what) {
        if (!authorized) {
            throw new UnauthorizedQueryException(
                    what + " was called on a query that was never authorize()'d. This is fail-closed"
                            + " by design: a missing role mask never falls back to emitting"
                            + " everything. Call .authorize(Role.GLOBAL) (or a narrower role) before"
                            + " any terminal operation, even when every row should be visible.");
        }
    }

    @Override
    public String toString() {
        return "BricksQuery[" + (authorized ? "authorized" : "UNAUTHORIZED") + ", " + view + "]";
    }
}

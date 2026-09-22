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
     * <p><strong>One crossing</strong>, whatever the number of groups or rows. The whole question —
     * this query's authorized chain plus the grouped fold — is a single fused native program; no
     * selection is built, nothing is asked per group, and only the totals cross. See {@link
     * com.adaworldapi.lancegraph.View#sumByGroup}.
     *
     * <p>This used to be sixteen separate queries: one {@code sumOf} over {@code
     * where(group.eq(v))} for each {@code v}, each costing two crossings (plan evaluation into a
     * selection mask, then the reduction), measured here at <strong>32</strong>. That path was
     * invariant in the number of rows but proportional to the number of groups; this one is
     * invariant in both, which is the stronger claim and is asserted as such — {@code
     * BricksAuthTest} pins the cost at 1 across two row counts <em>and</em> two group counts.
     *
     * <p>Groups come from {@link Orders#REGIONS}, the fixture's region cardinality. Every id in
     * {@code 0..REGIONS-1} appears as a key in the returned map, including ids no selected row
     * carries (mapped to {@code 0L}): a group's absence is itself a legitimate aggregate fact, not
     * something to hide by omitting the key.
     *
     * <p>The returned map is sized by the question — one entry per group — never by the data, so
     * the answer for a billion rows is the same sixteen numbers as the answer for a thousand.
     *
     * @throws UnauthorizedQueryException if {@link #authorize(Role)} was never called on this chain
     * @throws com.adaworldapi.lancegraph.AbiMismatchException if the loaded library reports ABI
     *     minor &lt; 12, which is where the fused grouped fold arrived
     */
    public Map<Integer, Long> sumBy(
            com.adaworldapi.lancegraph.U32Field group, com.adaworldapi.lancegraph.I32Field value) {
        return sumByGroupCount(group, value, Orders.REGIONS);
    }

    /**
     * {@link #sumBy} with the group count as a parameter, so a test can vary it.
     *
     * <p>Package-private on purpose. The public {@code sumBy} answers for exactly the fixture's
     * regions and a caller has no business asking for a different number; but the claim that this
     * costs one crossing <em>whatever</em> the group count is only a claim if something varies the
     * group count, and nothing else in this package can. It is not part of the aggregate-egress
     * surface the reflection guard audits — that guard reads public methods, which is the surface
     * the guarantee is about.
     */
    Map<Integer, Long> sumByGroupCount(
            com.adaworldapi.lancegraph.U32Field group,
            com.adaworldapi.lancegraph.I32Field value,
            int groups) {
        requireAuthorized("sumBy()");
        java.util.Objects.requireNonNull(group, "group");
        java.util.Objects.requireNonNull(value, "value");
        com.adaworldapi.lancegraph.GroupTotals totals = view.sumByGroup(group, value, groups);
        Map<Integer, Long> result = new LinkedHashMap<>(totals.groups());
        for (int v = 0; v < totals.groups(); v++) {
            result.put(v, totals.total(v));
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

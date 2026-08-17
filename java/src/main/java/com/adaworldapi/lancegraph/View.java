package com.adaworldapi.lancegraph;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable, lazy description of a set of rows.
 *
 * <p>A {@code View} is a <em>description</em>, not a result. {@link #where} returns a new view with
 * one more condition and does nothing else: it allocates no selection, touches no row, and crosses
 * the membrane zero times. A terminal operation — {@link #count()}, {@link #sumOf}, {@link #select}
 * — is what executes, and it executes the whole chain in a single crossing.
 *
 * <p>That is why the fluent style is affordable here when it usually is not. Each
 * {@code .where(...)} in a stream-like API normally costs another pass over the data; here it costs
 * one 24-byte descriptor appended to a list, and the passes are fused into one bulk kernel that
 * never returns to Java in between.
 *
 * <h2>Monotonic narrowing — structural, not a convention</h2>
 *
 * <p>{@code where} intersects. There is no public composition that widens a view, because
 * {@link Predicate} carries no combiner a caller can set and this class offers no {@code or}. So
 *
 * <pre>{@code
 *   V0 = every row
 *   V1 = V0 ∩ p0        V1 ⊆ V0
 *   V2 = V1 ∩ p1        V2 ⊆ V1 ⊆ V0
 * }</pre>
 *
 * <p>holds by construction: adding a condition can never increase the count. The ABI does have a
 * widening combiner; it is deliberately unreachable from here. A future union would build its own
 * plan and be a differently-named operation, so the invariant above stays readable as an invariant
 * rather than a default someone can flip.
 *
 * <h2>Sharing</h2>
 *
 * <p>Views are immutable, so a narrowed view never disturbs the one it came from and a single view
 * can be reused, held, or passed around freely. All of them refer to the same native rows; none of
 * them copies anything.
 */
public final class View {

    private final NativePattern owner;
    private final List<Predicate> predicates;

    View(NativePattern owner, List<Predicate> predicates) {
        this.owner = owner;
        this.predicates = predicates;
    }

    /**
     * A new view narrowed by one more condition.
     *
     * <p>Crosses the membrane zero times. Nothing is evaluated until a terminal operation.
     *
     * @param predicate obtained from a schema field, e.g. {@code Pattern.CLASS.eq(7)}
     * @return a new view; this one is unchanged
     */
    public View where(Predicate predicate) {
        java.util.Objects.requireNonNull(predicate, "predicate");
        owner.requireOpen("where()");
        List<Predicate> next = new ArrayList<>(predicates.size() + 1);
        next.addAll(predicates);
        next.add(predicate);
        return new View(owner, List.copyOf(next));
    }

    /**
     * How many rows this view selects.
     *
     * <p><strong>One crossing</strong>, whatever the number of conditions and whatever the number
     * of rows. The whole chain is marshalled into one descriptor array and evaluated by one fused
     * kernel that returns a single number.
     */
    public long count() {
        return owner.countOf(predicates);
    }

    /**
     * Sum a signed 32-bit column over the rows this view selects, widened to 64 bits.
     *
     * <p>Two crossings: evaluate the chain, then reduce. Still independent of the row count.
     */
    public long sumOf(I32Field field) {
        java.util.Objects.requireNonNull(field, "field");
        return owner.sumOf(predicates, field);
    }

    /**
     * A projection of one column through this view.
     *
     * <p>See {@link Lens} for what this concept is and what it is deliberately not yet.
     */
    public Lens lens(I32Field field) {
        java.util.Objects.requireNonNull(field, "field");
        owner.requireOpen("lens()");
        return new Lens(this, field);
    }

    /**
     * Materialise this view as a selection the caller owns and closes.
     *
     * <p>Useful when the same set of rows will be asked several questions: the chain is evaluated
     * once and the answer is kept natively as packed bits. A caller who only wants a number should
     * use {@link #count()} instead and never see a selection at all.
     */
    public Mask select() {
        return owner.selectInto(predicates);
    }

    /** The resource these rows live in. */
    public NativePattern source() {
        return owner;
    }

    /** How many conditions this view carries. Diagnostics. */
    public int conditionCount() {
        return predicates.size();
    }

    /** Package-private: the chain, for the execution surface. */
    List<Predicate> predicates() {
        return predicates;
    }

    @Override
    public String toString() {
        if (predicates.isEmpty()) {
            return "View[all rows]";
        }
        StringBuilder sb = new StringBuilder("View[");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(predicates.get(i));
        }
        return sb.append(']').toString();
    }
}

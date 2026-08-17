package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Engine;
import com.adaworldapi.lancegraph.internal.ffm.PlanOp;

import java.util.List;

/**
 * A set of rows held natively, opened once and closed once.
 *
 * <p>The headline claim of this project, stated concretely: <strong>64,000 logical entities do not
 * become 64,000 Java objects.</strong> They become one native lane set, one packed selection mask,
 * a handful of tiny schema descriptors, and one bulk operation. Nothing in this class hydrates a
 * row, and there is no API that could.
 *
 * <pre>{@code
 * try (var data = NativePattern.open(65_536)) {
 *     long n = data.view()
 *                  .where(Pattern.CLASS.eq(7))
 *                  .where(Pattern.VALUE.gt(100))
 *                  .count();
 * }
 * }</pre>
 *
 * <h2>Lifetime</h2>
 *
 * <p>Ordinary try-with-resources. After {@link #close()} every operation on this resource, and on
 * any {@link View} or {@link Mask} derived from it, throws {@link ClosedResourceException}. That is
 * enforced twice on purpose: this class fails fast on its own bookkeeping, and if a stale handle
 * ever did reach native code the registry's generation check rejects it rather than dereferencing
 * freed memory. Closing twice is an error, not a silent no-op.
 *
 * <h2>Threading</h2>
 *
 * <p>Terminal operations reuse one internal scratch selection, so they are serialised on this
 * instance. Distinct resources do not contend with each other.
 */
public final class NativePattern implements AutoCloseable {

    /**
     * The seed used by {@link #open(long)}.
     *
     * <p>Generation is deterministic from the seed, which is what lets a test assert exact counts
     * without shipping a data file — and lets Java recompute the expected answer independently,
     * making the assertion a genuine cross-language check rather than a tautology.
     */
    public static final long DEFAULT_SEED = 0xABCDL;

    private final long handle;
    private final long rowCount;
    private final Object lock = new Object();

    private boolean closed;
    private long scratchMask;   // reused destination for terminal operations
    private long auxMask;       // second buffer, only the unfused comparison path needs it
    private long allMask;       // every row selected; only a predicate-free reduction needs it

    private NativePattern(long handle, long rowCount) {
        this.handle = handle;
        this.rowCount = rowCount;
    }

    /** Open {@code rowCount} rows generated from {@link #DEFAULT_SEED}. */
    public static NativePattern open(long rowCount) {
        return open(rowCount, DEFAULT_SEED);
    }

    /**
     * Open {@code rowCount} rows generated deterministically from {@code seed}.
     *
     * @throws NativeLibraryNotFoundException if the native artifact is not present
     * @throws AbiMismatchException           if it is present but does not match this build
     */
    public static NativePattern open(long rowCount, long seed) {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0, was " + rowCount);
        }
        long h = Engine.openPattern(rowCount, seed);
        // Read the row count back from the resource rather than trusting the request: it is the
        // resource that decides, and caching it here means an empty View costs zero crossings.
        return new NativePattern(h, Engine.rowCount(h));
    }

    /**
     * A lazy description of every row.
     *
     * <p>Building it crosses the membrane zero times; narrowing it crosses zero times. Only a
     * terminal operation executes.
     */
    public View view() {
        requireOpen("view()");
        return new View(this, List.of());
    }

    /** How many rows this resource holds. */
    public long rowCount() {
        requireOpen("rowCount()");
        return rowCount;
    }

    /** The row span, as a value. */
    public RowRange rows() {
        requireOpen("rows()");
        return RowRange.of(rowCount);
    }

    /** False once {@link #close()} has run. */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Release the native storage.
     *
     * <p>Lanes are freed, the generation is bumped, and any child selection is orphaned — it can
     * still exist, but it can never work again.
     *
     * @throws ClosedResourceException if already closed
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                throw new ClosedResourceException(
                        "close() called on a resource that is already closed. A double close is an"
                                + " error rather than a no-op, because the second call cannot know"
                                + " whether the handle was recycled in between.");
            }
            closed = true;
            // Children first, so a mask is never left pointing at a dead parent even transiently.
            if (scratchMask != 0) {
                Engine.close(scratchMask);
                scratchMask = 0;
            }
            if (auxMask != 0) {
                Engine.close(auxMask);
                auxMask = 0;
            }
            if (allMask != 0) {
                Engine.close(allMask);
                allMask = 0;
            }
            Engine.close(handle);
        }
    }

    // ── package-private: the execution surface View and friends use ──────────────────────────

    long handle() {
        return handle;
    }

    void requireOpen(String what) {
        if (closed) {
            throw new ClosedResourceException(
                    what + " was called on a closed resource. Java rejected it before reaching"
                            + " native code; had it not, the generation-checked handle would have"
                            + " returned INVALID_HANDLE rather than touching freed memory.");
        }
    }

    /** Evaluate a plan into the reusable scratch selection and return its popcount. */
    long countOf(List<Predicate> predicates) {
        requireOpen("count()");
        if (predicates.isEmpty()) {
            // No predicate selects every row. Answering from the cached count is not a shortcut
            // around the membrane — an empty plan is rejected by the ABI (EMPTY_PLAN), and "every
            // row" is a number this resource already knows.
            return rowCount;
        }
        synchronized (lock) {
            requireOpen("count()");
            return Engine.evaluateFused(handle, plan(predicates), scratch());
        }
    }

    /** Evaluate a plan, then reduce one lane over the resulting selection. */
    long sumOf(List<Predicate> predicates, I32Field field) {
        requireOpen("sumOf()");
        synchronized (lock) {
            requireOpen("sumOf()");
            long mask;
            if (predicates.isEmpty()) {
                // Sum over everything: select all rows rather than inventing an always-true plan.
                mask = all();
            } else {
                mask = scratch();
                Engine.evaluateFused(handle, plan(predicates), mask);
            }
            return Engine.sumI32(handle, field.lane().index(), mask);
        }
    }

    /** Materialise a selection the caller owns and closes. */
    Mask selectInto(List<Predicate> predicates) {
        requireOpen("select()");
        synchronized (lock) {
            requireOpen("select()");
            long mask = Engine.createMask(handle, predicates.isEmpty());
            if (!predicates.isEmpty()) {
                Engine.evaluateFused(handle, plan(predicates), mask);
            }
            return new Mask(this, mask);
        }
    }

    /** Diagnostics: the scalar reference kernel, same semantics, no SIMD. */
    long countScalar(List<Predicate> predicates) {
        requireOpen("countScalar()");
        if (predicates.isEmpty()) {
            return rowCount;
        }
        synchronized (lock) {
            requireOpen("countScalar()");
            return Engine.evaluateScalar(handle, plan(predicates), scratch());
        }
    }

    /** Diagnostics: one crossing per predicate plus the combines. Never the default. */
    long countUnfused(List<Predicate> predicates) {
        requireOpen("countUnfused()");
        if (predicates.isEmpty()) {
            return rowCount;
        }
        synchronized (lock) {
            requireOpen("countUnfused()");
            return Engine.evaluateUnfused(handle, plan(predicates), scratch(), aux());
        }
    }

    private List<PlanOp> plan(List<Predicate> predicates) {
        return predicates.stream().map(Predicate::op).toList();
    }

    private long scratch() {
        if (scratchMask == 0) {
            scratchMask = Engine.createMask(handle, false);
        }
        return scratchMask;
    }

    private long aux() {
        if (auxMask == 0) {
            auxMask = Engine.createMask(handle, false);
        }
        return auxMask;
    }

    private long all() {
        if (allMask == 0) {
            allMask = Engine.createMask(handle, true);
        }
        return allMask;
    }

    @Override
    public String toString() {
        return "NativePattern[" + rowCount + " rows" + (closed ? ", closed" : "") + "]";
    }
}

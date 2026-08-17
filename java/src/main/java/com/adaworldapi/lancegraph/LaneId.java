package com.adaworldapi.lancegraph;

/**
 * Which column of the struct-of-arrays a field lives in.
 *
 * <p><strong>Not a consumer concept.</strong> An ordinary caller writes
 * {@code Pattern.CLASS.eq(7)} and never sees a lane id — the generated schema vocabulary carries
 * it. This type exists for the <em>generator</em> surface (code that mints {@code Field}s) and for
 * the internals that marshal an operation descriptor.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>This is written so the <em>same source</em> compiles as a {@code value record} on a JEP 401
 * JDK by changing only the modifier. It is therefore:
 *
 * <ul>
 *   <li>final and immutable;
 *   <li>free of identity dependence — never used as a lock, never compared with {@code ==},
 *       {@code equals}/{@code hashCode} derived purely from state;
 *   <li>free of any field that could be null.
 * </ul>
 *
 * <p>What identity-freedom buys: a value class can be flattened into its container and scalarised
 * in registers, so wrapping a bare {@code int} in a meaningful name stops costing a heap object
 * and a pointer chase. That is the whole point of the A/B — today the abstraction is paid for at
 * runtime, and under Valhalla it should be free. Until then this remains an ordinary record and
 * the JIT's escape analysis does most, but not all, of the same job.
 *
 * @param index zero-based lane index within its resource
 */
public record LaneId(int index) {

    public LaneId {
        if (index < 0) {
            throw new IllegalArgumentException("lane index must be >= 0, was " + index);
        }
    }

    /** Lane 0 of a pattern resource — entity ids. */
    public static LaneId of(int index) {
        return new LaneId(index);
    }

    @Override
    public String toString() {
        return "lane#" + index;
    }
}

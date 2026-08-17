package com.adaworldapi.lancegraph.lab;

/**
 * Field-flattening subjects, stable half.
 *
 * <p>{@code Descriptor} is shaped like the real thing: a {@code Field} in the production API holds
 * a {@link LaneId} and an {@link Ordinal}, and both are wrappers around a single {@code int}. On a
 * stable JDK that is three objects and two pointer hops to read two integers.
 */
final class Containers {

    private Containers() {}

    /** Two value-shaped wrappers held as ordinary references. */
    static final class Descriptor {
        final LaneId lane;
        final Ordinal ordinal;
        Descriptor(int lane, int ordinal) {
            this.lane = LaneId.of(lane);
            this.ordinal = Ordinal.of(ordinal);
        }
        int laneIndex() { return lane.index(); }
        int ordinalValue() { return ordinal.value(); }
    }

    static Descriptor make(int lane, int ordinal) { return new Descriptor(lane, ordinal); }
    static int read(Descriptor d) { return d.laneIndex() + d.ordinalValue(); }

    static String kind() { return "identity class with two reference fields"; }
    static boolean fieldsAreNullRestricted() { return false; }
}

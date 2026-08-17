package com.adaworldapi.lancegraph.lab;

/**
 * The stable-JDK half of the A/B. Everything here reports what an ordinary {@code record} on a
 * production JDK actually is.
 *
 * <p>Two of these answers are <em>unknowable</em> on a stable JDK rather than false, and they say
 * so: there is no {@code ValueClass.isFlatArray} to ask, because there is no flattening to ask
 * about. Reporting "false" would imply the question was answered; {@code UNKNOWN} says it was not.
 */
final class Platform {

    private Platform() {}

    static final String NAME = "stable";

    /** Does the runtime consider this vocabulary identity-free? */
    static boolean isValueVocabulary() {
        return isValueClass(LaneId.class);
    }

    /**
     * Is {@code type} a value class, on a platform that can even ask.
     *
     * <p>{@code Class::isValue} does not exist on this JDK at all — this is not an "unknowable
     * answer" case like {@link #arrayFlatness}, it is a missing method, confirmed by the compiler.
     * The correct answer is still knowable without the query: on a JDK with no value-class
     * concept, nothing compiled here can BE one, so {@code false} is exact, not a guess.
     */
    static boolean isValueClass(Class<?> type) {
        return false;
    }

    /** Flatness of an array, where the runtime can answer. */
    static String arrayFlatness(Object array) {
        return "UNKNOWN(no ValueClass API on a stable JDK; a reference array is never flat)";
    }

    /** Allocate the vocabulary array the way this platform can. */
    static Object[] newLaneIdArray(int n) {
        return new LaneId[n];
    }

    /** Allocate the per-entity Row array the way this platform can. */
    static Object[] newRowArray(int n) {
        return new Row[n];
    }

    /** Can this array hold null in every slot? */
    static boolean arrayAcceptsNull(Object[] array) {
        try { Object keep = array[0]; array[0] = null; array[0] = keep; return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * Allocate the densest array this platform offers for {@code component}.
     *
     * <p>On a stable JDK there is exactly one kind of object array, so this is
     * {@code Array.newInstance} and the {@code init} value is unused.
     */
    static Object[] newArrayOf(Class<?> component, int n, Object init) {
        return (Object[]) java.lang.reflect.Array.newInstance(component, n);
    }

    static String describe() {
        return "stable-record vocabulary; arrays are reference arrays; fields are references";
    }
}

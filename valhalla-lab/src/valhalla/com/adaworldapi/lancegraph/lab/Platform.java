package com.adaworldapi.lancegraph.lab;

import jdk.internal.value.ValueClass;

/**
 * The Valhalla half of the A/B. Same signatures as the stable {@code Platform}, so the shared
 * experiment sources are byte-identical across both runs.
 *
 * <p>{@link ValueClass#isFlatArray} is the measurement that matters here: it is the VM answering
 * about its own array, not an inference from a timing or a footprint estimate. That makes the
 * flattening result hard to fool, which is why it is preferred over anything derived.
 *
 * <p>Requires {@code --add-exports java.base/jdk.internal.value=ALL-UNNAMED}. That export is the
 * honest cost of the experiment: null-restricted, guaranteed-flat storage has <em>no</em>
 * supported surface in this early-access build (see reproducers/no-public-flat-array-api.md).
 */
final class Platform {

    private Platform() {}

    static final String NAME = "valhalla";

    static boolean isValueVocabulary() {
        return isValueClass(LaneId.class);
    }

    /** Is {@code type} a value class? The real, final {@code Class::isValue} query. */
    static boolean isValueClass(Class<?> type) {
        return type.isValue();
    }

    static String arrayFlatness(Object array) {
        return ValueClass.isFlatArray(array) ? "FLAT" : "NOT-FLAT";
    }

    /**
     * Null-restricted, non-atomic — the encoding with no null marker and no atomicity padding,
     * i.e. the densest layout the VM offers. This is the array the thesis experiment needs to be
     * fair to Valhalla: giving it a plain reference array would measure the old world twice.
     */
    static Object[] newLaneIdArray(int n) {
        return ValueClass.newNullRestrictedNonAtomicArray(LaneId.class, n, new LaneId(0));
    }

    static Object[] newRowArray(int n) {
        return ValueClass.newNullRestrictedNonAtomicArray(Row.class, n, new Row(0, 0, 0));
    }

    static boolean arrayAcceptsNull(Object[] array) {
        try { Object keep = array[0]; array[0] = null; array[0] = keep; return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * Allocate the densest array this platform offers for {@code component} — null-restricted and
     * non-atomic, the encoding with neither a null marker nor atomicity padding.
     */
    static Object[] newArrayOf(Class<?> component, int n, Object init) {
        return ValueClass.newNullRestrictedNonAtomicArray(component, n, init);
    }

    static String describe() {
        return "value-record vocabulary; null-restricted non-atomic arrays; @NullRestricted fields";
    }
}

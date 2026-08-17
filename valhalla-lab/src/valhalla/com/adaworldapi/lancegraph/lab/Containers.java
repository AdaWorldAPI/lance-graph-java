package com.adaworldapi.lancegraph.lab;

import jdk.internal.value.ValueClass;
import jdk.internal.vm.annotation.NullRestricted;

/**
 * Field-flattening subjects, Valhalla half. Same shape as the stable {@code Descriptor}; the two
 * differences are the {@code value} modifier and {@code @NullRestricted} on the fields.
 *
 * <p><strong>Both differences are load-bearing and that is a finding, not a detail.</strong>
 * {@code @NullRestricted} is what permits a flat encoding — a nullable value field still needs
 * somewhere to record "this one is null". And the container must itself be a {@code value class}:
 * an ordinary identity class with {@code @NullRestricted} fields fails at class load with
 * {@code VerifyError: Invalid use of strict instance fields}, because javac on this build does not
 * emit the strict initialisation order the VM demands. See
 * {@code reproducers/nullrestricted-field-in-identity-class.md}.
 */
final class Containers {

    private Containers() {}

    /** Two value-shaped wrappers held as null-restricted, flattenable fields. */
    static value class Descriptor {
        @NullRestricted final LaneId lane;
        @NullRestricted final Ordinal ordinal;
        Descriptor(int lane, int ordinal) {
            this.lane = LaneId.of(lane);
            this.ordinal = Ordinal.of(ordinal);
        }
        int laneIndex() { return lane.index(); }
        int ordinalValue() { return ordinal.value(); }
    }

    static Descriptor make(int lane, int ordinal) { return new Descriptor(lane, ordinal); }
    static int read(Descriptor d) { return d.laneIndex() + d.ordinalValue(); }

    static String kind() { return "value class with two @NullRestricted value fields"; }

    static boolean fieldsAreNullRestricted() {
        try {
            return ValueClass.isNullRestrictedField(Descriptor.class.getDeclaredField("lane"));
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}

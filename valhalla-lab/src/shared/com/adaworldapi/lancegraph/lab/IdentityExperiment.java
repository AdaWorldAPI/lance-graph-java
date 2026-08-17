package com.adaworldapi.lancegraph.lab;

/**
 * Truth (a) — <em>semantic</em>: a {@link LaneId} is a value. Its object identity should be
 * irrelevant, and nothing in the API should be able to observe it.
 *
 * <p>This experiment does not measure performance. It measures whether the runtime <em>agrees</em>
 * with that semantic claim, which is the thing that has to be true before any performance claim
 * means anything: a type whose identity is observable cannot be flattened, no matter how fast the
 * JIT is.
 *
 * <p>The interesting output is not "value classes are values". It is the list of behaviours that
 * are <em>identical</em> across the two runs — because every one of those is a place where the
 * production API can move to Valhalla without a single caller noticing.
 */
final class IdentityExperiment {

    private IdentityExperiment() {}

    static void run() {
        Lab.section("(a) SEMANTIC TRUTH — is identity observable?");
        Lab.kv("platform", Platform.NAME + " — " + Platform.describe());
        Lab.kv("LaneId.class.isValue()", Platform.isValueClass(LaneId.class));
        Lab.kv("Ordinal.class.isValue()", Platform.isValueClass(Ordinal.class));
        Lab.kv("MaskId.class.isValue()", Platform.isValueClass(MaskId.class));
        Lab.kv("RowRange.class.isValue()", Platform.isValueClass(RowRange.class));
        Lab.kv("Row.class.isValue()", Platform.isValueClass(Row.class));

        LaneId a = LaneId.of(5);
        LaneId b = LaneId.of(5);
        LaneId c = LaneId.of(6);

        // The semantic contract, restated as assertions. These must hold on BOTH platforms — that
        // is the point. If any of them differed, the migration would not be source-compatible.
        Lab.kv("equal state => equals()", a.equals(b));
        Lab.kv("different state => !equals()", !a.equals(c));
        Lab.kv("equal state => equal hashCode()", a.hashCode() == b.hashCode());
        Lab.kv("equal state => equal toString()", a.toString().equals(b.toString()));

        // Reference equality is the ONE observable that legitimately differs, and the production
        // API never uses it. Reported, not asserted, because a stable JDK is free to intern or not.
        Lab.kv("a == b  (reference equality)", a == b);
        Lab.kv("identityHashCode(a) == identityHashCode(b)",
                System.identityHashCode(a) == System.identityHashCode(b));

        // Can a variable of this type hold null? Under Valhalla a plain declared type still can —
        // null-restriction is a property of a FIELD or an ARRAY, not of the class. That surprises
        // people, so it is measured rather than described.
        LaneId maybeNull = null;
        Lab.kv("a local of this type accepts null", maybeNull == null);

        Object[] arr = Platform.newLaneIdArray(4);
        arr[0] = LaneId.of(1);
        Lab.kv("array kind", arr.getClass().getSimpleName());
        Lab.kv("array flatness", Platform.arrayFlatness(arr));
        Lab.kv("array slot accepts null", Platform.arrayAcceptsNull(arr));

        // synchronized(valueObject) does not compile under Valhalla — "required: a type with
        // identity". It is exercised reflectively-in-spirit here (as a documented fact rather than
        // live code) because writing it in shared source would break the stable compile too.
        Lab.kv("synchronized(x) legality",
                Platform.isValueVocabulary()
                        ? "COMPILE ERROR under Valhalla (required: a type with identity)"
                        : "legal but never used by the production API");
    }
}

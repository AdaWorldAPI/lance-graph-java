package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.NativeAccess;
import com.adaworldapi.lancegraph.NativePattern;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.Pattern;
import com.adaworldapi.lancegraph.internal.ffm.Engine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * One open pattern plus the handles every benchmark needs, and — more importantly — the
 * cross-check that all the implementations under comparison compute the same answer.
 *
 * <p><strong>The cross-check runs in {@code @Setup}, not in a test.</strong> A benchmark whose
 * variants disagree is measuring nothing, and the failure mode is silent: a Vector kernel with an
 * off-by-one tail is *faster* than a correct one and looks like a win. So the setup asserts
 * agreement and throws before any measurement happens.
 */
public final class Data implements AutoCloseable {

    /** The class tag selected. Matches ~1/16 of rows in the fixture. */
    public static final int CLASS_NEEDLE = 7;
    /** The signed threshold. The fixture's values span -150..361, so this straddles. */
    public static final int VALUE_THRESHOLD = 100;

    public final NativePattern pattern;
    public final int rows;

    /** Native lane 1 ({@code u32} classes), read in place. Never copied. */
    public final MemorySegment classes;
    /** Native lane 2 ({@code i32} values), read in place. Never copied. */
    public final MemorySegment values;

    /**
     * A heap copy of the values lane. Present <em>only</em> as the "data already in Java" baseline
     * for the segment-access benchmark, and used by nothing that compares against the native
     * kernel — copying into it would be exactly the serialization the ABI forbids.
     */
    public final int[] valuesHeap;

    public final long expectedCount;
    public final long expectedSum;

    public Data(int rows) {
        if (!NativeRuntime.isAvailable()) {
            throw new IllegalStateException("native library unavailable: "
                    + NativeRuntime.unavailableReason().getMessage()
                    + "\nbuild it with:  cd native/lgj-abi && "
                    + "CARGO_TARGET_DIR=<repo>/target cargo build --release");
        }
        this.rows = rows;
        this.pattern = NativePattern.open(rows);

        Engine.LaneWindow c = NativeAccess.lane(pattern, NativeAccess.LANE_CLASS);
        Engine.LaneWindow v = NativeAccess.lane(pattern, NativeAccess.LANE_VALUE);
        this.classes = c.segment();
        this.values = v.segment();

        this.valuesHeap = new int[rows];
        for (int i = 0; i < rows; i++) {
            valuesHeap[i] = values.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
        }

        this.expectedCount = pattern.view()
                .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                .count();
        this.expectedSum = pattern.view()
                .where(Pattern.CLASS.eq(CLASS_NEEDLE))
                .where(Pattern.VALUE.gt(VALUE_THRESHOLD))
                .sumOf(Pattern.VALUE);

        crossCheck();
    }

    /** Every implementation under comparison must agree before any of them is timed. */
    private void crossCheck() {
        long vec = Kernels.countVector(classes, values, rows, CLASS_NEEDLE, VALUE_THRESHOLD);
        long sca = Kernels.countScalar(classes, values, rows, CLASS_NEEDLE, VALUE_THRESHOLD);
        long vecSum = Kernels.sumVector(classes, values, rows, CLASS_NEEDLE, VALUE_THRESHOLD);
        if (vec != expectedCount || sca != expectedCount) {
            throw new AssertionError("count disagreement at rows=" + rows
                    + ": native=" + expectedCount + " vector=" + vec + " scalar=" + sca);
        }
        if (vecSum != expectedSum) {
            throw new AssertionError("sum disagreement at rows=" + rows
                    + ": native=" + expectedSum + " vector=" + vecSum);
        }
        long segSum = Kernels.sumAllScalar(values, rows);
        if (segSum != Kernels.sumAllVector(values, rows) || segSum != Kernels.sumAllHeap(valuesHeap)) {
            throw new AssertionError("full-lane sum disagreement at rows=" + rows);
        }
    }

    @Override public void close() {
        pattern.close();
    }
}

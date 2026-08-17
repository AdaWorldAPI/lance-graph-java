package com.adaworldapi.lancegraph;

import java.util.ArrayList;
import java.util.List;

/**
 * A self-contained assertion harness.
 *
 * <p>There is no JUnit here because there are no downloaded dependencies anywhere in this project —
 * {@code javac} and {@code java} are the entire Java toolchain, exactly as {@code cargo} is the
 * entire Rust one. What a test framework would give us is a runner and readable failures; that is
 * about eighty lines, and they are these.
 *
 * <p>Failures are loud and specific by design: every check carries a sentence saying what was
 * being established, and every comparison prints both numbers. A failure that only says
 * {@code expected true} costs more to diagnose than it saved to write.
 */
public final class Checks {

    private final String suite;
    private final List<String> failures = new ArrayList<>();
    private int passed;
    private String section = "";

    public Checks(String suite) {
        this.suite = suite;
    }

    /** Group the following checks under a heading, printed once. */
    public void section(String name) {
        this.section = name;
        System.out.println("  " + name);
    }

    /** Note something worth seeing in the log that is not itself a check. */
    public void note(String message) {
        System.out.println("      - " + message);
    }

    public void that(String what, boolean condition) {
        if (condition) {
            pass(what);
        } else {
            fail(what, "condition was false");
        }
    }

    public void eq(String what, long expected, long actual) {
        if (expected == actual) {
            pass(what + "  (= " + actual + ")");
        } else {
            fail(what, "expected " + expected + " but was " + actual
                    + "  (difference " + (actual - expected) + ")");
        }
    }

    public void eq(String what, Object expected, Object actual) {
        if (java.util.Objects.equals(expected, actual)) {
            pass(what + "  (= " + actual + ")");
        } else {
            fail(what, "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public void notEq(String what, long unexpected, long actual) {
        if (unexpected != actual) {
            pass(what + "  (" + actual + " != " + unexpected + ")");
        } else {
            fail(what, "expected anything other than " + unexpected + ", got exactly that");
        }
    }

    public void atMost(String what, long limit, long actual) {
        if (actual <= limit) {
            pass(what + "  (" + actual + " <= " + limit + ")");
        } else {
            fail(what, "expected at most " + limit + " but was " + actual);
        }
    }

    /**
     * Assert that {@code body} throws {@code expected}.
     *
     * <p>A falsifier that merely expects "some exception" would pass on a
     * {@code NullPointerException} from a typo, so the type is required and the actual type is
     * printed when it differs.
     */
    public void throwsUp(String what, Class<? extends Throwable> expected, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                pass(what + "  (threw " + t.getClass().getSimpleName() + ")");
            } else {
                fail(what, "expected " + expected.getSimpleName() + " but threw "
                        + t.getClass().getName() + ": " + t.getMessage());
            }
            return;
        }
        fail(what, "expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    private void pass(String what) {
        passed++;
        System.out.println("      ok   " + what);
    }

    private void fail(String what, String detail) {
        String line = (section.isEmpty() ? "" : section + " / ") + what + "\n          " + detail;
        failures.add(line);
        System.out.println("      FAIL " + what + "\n           " + detail);
    }

    public boolean anyFailed() {
        return !failures.isEmpty();
    }

    public int passedCount() {
        return passed;
    }

    public int failedCount() {
        return failures.size();
    }

    /** Print the summary and return the process exit code this suite implies. */
    public int report() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("  " + suite + ": " + passed + " checks passed");
            return 0;
        }
        System.out.println("  " + suite + ": " + failures.size() + " FAILED, " + passed + " passed");
        for (String f : failures) {
            System.out.println("    x " + f);
        }
        return 1;
    }

    /**
     * Report that the native artifact is missing, clearly enough that nobody mistakes it for a
     * test failure.
     *
     * @return the exit code to use
     */
    public static int reportUnavailable(String suite) {
        System.out.println();
        System.out.println("  " + suite + ": SKIPPED - the native library is not available.");
        System.out.println("  " + NativeRuntime.unavailableReason().getMessage());
        System.out.println();
        System.out.println("  Build it first:  cargo build --release   (in native/lgj-abi)");
        System.out.println("  Or point at it:  -Dlgj.library=/path/to/liblgj_abi.so");
        return 2;
    }
}

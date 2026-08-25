package com.adaworldapi.lancegraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs every suite in one JVM and exits non-zero if anything failed.
 *
 * <p>Exit codes are meaningful so a build script can tell the three outcomes apart:
 * {@code 0} all passed, {@code 1} something failed, {@code 2} the native artifact is not available
 * so nothing was run. A missing library reported as a failure would send whoever reads the log
 * looking for a bug that is not there.
 */
public final class AllTests {

    private AllTests() {}

    public static void main(String[] args) {
        Map<String, Consumer<Checks>> suites = new LinkedHashMap<>();
        suites.put("ApiSurfaceTest", ApiSurfaceTest::run);
        suites.put("AbiContractTest", AbiContractTest::run);
        suites.put("SmokeTest", SmokeTest::run);
        suites.put("FixtureParityTest", FixtureParityTest::run);
        suites.put("FusionParityTest", FusionParityTest::run);
        suites.put("LazinessTest", LazinessTest::run);
        suites.put("NarrowingTest", NarrowingTest::run);
        suites.put("LifetimeTest", LifetimeTest::run);
        suites.put("RowStoreParityTest", RowStoreParityTest::run);
        suites.put("RowStoreLifetimeTest", RowStoreLifetimeTest::run);
        suites.put("MaskNativeOpsTest", MaskNativeOpsTest::run);
        suites.put("FacetSumParityTest", FacetSumParityTest::run);
        suites.put("CarvingTableTest", CarvingTableTest::run);

        if (!NativeRuntime.isAvailable()) {
            // ApiSurfaceTest needs no native library — the API's shape is a compile-time property —
            // so run it anyway before reporting the skip. It is exactly the check most worth having
            // before the artifact exists.
            System.out.println();
            System.out.println("=== ApiSurfaceTest ===");
            Checks shape = new Checks("ApiSurfaceTest");
            ApiSurfaceTest.run(shape);
            int shapeCode = shape.report();
            int skipCode = Checks.reportUnavailable("AllTests");
            System.exit(shapeCode != 0 ? shapeCode : skipCode);
        }

        int totalPassed = 0;
        int totalFailed = 0;
        StringBuilder summary = new StringBuilder();

        for (Map.Entry<String, Consumer<Checks>> e : suites.entrySet()) {
            System.out.println();
            System.out.println("=== " + e.getKey() + " ===");
            Checks c = new Checks(e.getKey());
            try {
                e.getValue().accept(c);
            } catch (Throwable t) {
                // A suite that blows up mid-way must not take the run down silently: report it as a
                // failure of that suite and carry on, so one broken area does not hide the others.
                System.out.println("      FAIL suite threw " + t);
                t.printStackTrace(System.out);
                totalFailed++;
                summary.append(String.format("  %-20s THREW %s%n", e.getKey(), t));
                continue;
            }
            c.report();
            totalPassed += c.passedCount();
            totalFailed += c.failedCount();
            summary.append(String.format("  %-20s %3d passed, %d failed%n",
                    e.getKey(), c.passedCount(), c.failedCount()));
        }

        System.out.println();
        System.out.println("=== summary ===");
        System.out.print(summary);
        System.out.println();
        System.out.println("  " + NativeRuntime.describe());
        System.out.println();
        if (totalFailed == 0) {
            System.out.println("  ALL PASSED (" + totalPassed + " checks)");
            System.exit(0);
        }
        System.out.println("  " + totalFailed + " FAILED, " + totalPassed + " passed");
        System.exit(1);
    }
}

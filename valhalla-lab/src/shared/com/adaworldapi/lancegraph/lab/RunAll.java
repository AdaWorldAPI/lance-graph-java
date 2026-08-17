package com.adaworldapi.lancegraph.lab;

import com.adaworldapi.lancegraph.NativeRuntime;

/**
 * Runs every experiment and prints a machine-greppable report.
 *
 * <p>The same class is compiled twice — once against the {@code src/stable} vocabulary on the
 * production JDK, once against {@code src/valhalla} on the JEP 401 build — and the two outputs are
 * diffed. That is the whole design: one experiment source, two object models, one difference.
 *
 * <p>Exit codes: {@code 0} ran, {@code 2} the native library is unavailable (the thesis experiment
 * needs it and a fabricated number would be worse than no number).
 */
public final class RunAll {

    private RunAll() {}

    public static void main(String[] args) {
        System.out.println("lance-graph-java :: valhalla lab");
        Lab.kv("platform", Platform.NAME);
        Lab.kv("java.vm.version", System.getProperty("java.vm.version"));
        Lab.kv("java.vendor.version", System.getProperty("java.vendor.version", "-"));
        Lab.kv("jvm args", java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments());

        IdentityExperiment.run();
        FlatteningCliffExperiment.run();
        FootprintExperiment.run();

        if (!NativeRuntime.isAvailable()) {
            Lab.section("NATIVE EXPERIMENTS SKIPPED");
            Lab.kv("reason", NativeRuntime.unavailableReason().getMessage());
            System.out.println("\nno native library — the thesis experiment cannot be run, and a"
                    + " number invented for it would be worse than none.");
            System.exit(2);
        }
        Lab.kv("native runtime", NativeRuntime.describe());

        FfmAddressingExperiment.run();
        ThesisExperiment.run();

        System.out.println();
        System.out.println("lab complete.");
    }
}

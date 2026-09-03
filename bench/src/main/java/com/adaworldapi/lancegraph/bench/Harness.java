package com.adaworldapi.lancegraph.bench;

import com.adaworldapi.lancegraph.NativeRuntime;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The entry point. Prints the environment that every number has to be read against, then runs JMH.
 *
 * <p>The environment block is not decoration. A benchmark result without the JDK build, the VM
 * flags, the CPU, the SIMD backend actually compiled into the native library, and the row counts
 * is not reproducible and not reviewable, and this harness is meant to survive being read by
 * someone who does this for a living.
 *
 * <p>Usage: {@code java ... Harness [regex]} — the optional argument filters which benchmarks run,
 * e.g. {@code Harness C_} for the execution-boundary sweep alone.
 */
public final class Harness {

    private Harness() {}

    /** Resolve the native library the same way the library itself does, so both agree. */
    public static String libraryPath() {
        String path = NativeRuntime.libraryPath();
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            throw new IllegalStateException("native library not found (resolved: " + path + ")");
        }
        return path;
    }

    public static void main(String[] args) throws Exception {
        if (!NativeRuntime.isAvailable()) {
            System.err.println("FATAL: native library unavailable — "
                    + NativeRuntime.unavailableReason().getMessage());
            System.err.println("build it with:");
            System.err.println("  cd native/lgj-abi && "
                    + "CARGO_TARGET_DIR=$(git rev-parse --show-toplevel)/target cargo build --release");
            System.exit(2);
        }

        System.out.println("=".repeat(96));
        System.out.println("lance-graph-java :: benchmark harness");
        System.out.println("=".repeat(96));
        row("jdk", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        row("vm args", java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().toString());
        row("os / arch", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        row("cpu", cpuModel() + "  (" + Runtime.getRuntime().availableProcessors()
                + " logical processors)");
        row("vector species", Kernels.I32 + "  (" + Kernels.I32.length() + " int lanes, "
                + Kernels.I32.vectorBitSize() + " bit)");
        row("native runtime", NativeRuntime.describe());
        row("predicate", "class == " + Data.CLASS_NEEDLE
                + " AND value > " + Data.VALUE_THRESHOLD);
        System.out.println("=".repeat(96));
        System.out.println();

        // LGJ_BENCH_QUICK=1 shrinks forks/iterations for a smoke run. gate.py refuses such a
        // CSV (its Samples column is below 5 forks x 8 iterations), so a quick run can never be
        // mistaken for evidence.
        boolean quick = "1".equals(System.getenv("LGJ_BENCH_QUICK"));
        var options = new OptionsBuilder()
                .include(args.length > 0 ? args[0] : "com.adaworldapi.lancegraph.bench")
                .resultFormat(ResultFormatType.CSV)
                .result(System.getProperty("lgj.bench.result", "results/jmh-results.csv"))
                .shouldDoGC(true)
                .forks(quick ? 1 : -1)
                .warmupIterations(quick ? 1 : -1)
                .measurementIterations(quick ? 2 : -1)
                .build();
        new Runner(options).run();
    }

    private static void row(String k, String v) {
        System.out.printf("%-16s %s%n", k, v);
    }

    private static String cpuModel() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (Exception ignored) {
            // /proc is Linux-only; the harness runs elsewhere without a CPU model line.
        }
        return "unknown";
    }
}

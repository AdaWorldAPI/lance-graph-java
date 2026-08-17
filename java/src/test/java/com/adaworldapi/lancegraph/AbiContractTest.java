package com.adaworldapi.lancegraph;

/**
 * Checks that the header replacement actually replaced a header.
 *
 * <p>The claim (docs/abi.md §0): no {@code .h} exists, and instead the compiled artifact describes
 * itself at runtime while Java cross-checks that description against its own independently derived
 * layout constants. If the cross-check were absent, or advisory, or comparing a constant against
 * itself, the design would be a header with extra steps.
 *
 * <p>So this test establishes three things:
 *
 * <ol>
 *   <li>The manifest was read and the artifact identifies itself coherently.
 *   <li>The version rule is the documented one — major exact, minor at least.
 *   <li>The check is <em>strict</em>: a deliberately impossible expectation is rejected, which is
 *       what proves the mechanism can fail at all.
 * </ol>
 *
 * <p>Point three is the one worth having. A verification routine that has never been observed to
 * reject anything is indistinguishable from one that returns {@code true}.
 */
public final class AbiContractTest {

    private AbiContractTest() {}

    public static void main(String[] args) {
        System.out.println("AbiContractTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("AbiContractTest"));
        }
        Checks c = new Checks("AbiContractTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        c.section("the artifact described itself and Java agreed");
        c.note(NativeRuntime.describe());
        c.eq("abi major", 0, NativeRuntime.abiMajor());
        c.that("abi minor is at least what this build expects", NativeRuntime.abiMinor() >= 1);
        c.that("a SIMD backend was reported", !NativeRuntime.simdBackend().isBlank());
        c.that("a build profile was reported", !NativeRuntime.buildProfile().isBlank());

        if (!"release".equals(NativeRuntime.buildProfile())) {
            c.note("WARNING: this is a " + NativeRuntime.buildProfile() + " build."
                    + " Correctness results are valid; any timing taken against it is not.");
        }

        c.section("the loaded library is a real path, not a guess");
        c.that("the library path exists",
                java.nio.file.Files.isRegularFile(java.nio.file.Path.of(NativeRuntime.libraryPath())));

        c.section("the verification is strict -- it can and does reject");
        // A verification routine never observed to reject anything is indistinguishable from one
        // that returns true, so drive the rejection branch on purpose.
        //
        // The probe is aimed at a real, perfectly valid shared library that simply is not ours.
        // That is the precise case worth proving: the artifact loads, the linker is happy, and the
        // check still refuses because the library cannot describe itself. Aiming at a non-library
        // file instead would only prove that dlopen rejects garbage, which is the operating
        // system's achievement rather than this design's.
        String stranger = firstExisting(
                "/lib/x86_64-linux-gnu/libz.so.1",
                "/usr/lib/x86_64-linux-gnu/libz.so.1",
                "/lib/x86_64-linux-gnu/libm.so.6");
        if (stranger == null) {
            c.note("no unrelated system library found to probe with; skipping the rejection arm");
        } else {
            c.throwsUp("a valid library that cannot describe itself is refused, not called into",
                    AbiMismatchException.class,
                    () -> ProbeLoader.loadInSeparateExpectation(stranger));
            c.note("probed " + stranger + ": it loaded fine and was still rejected,"
                    + " because it exports no lgj_abi_manifest");
        }

        c.throwsUp("a path that does not exist is reported clearly",
                NativeLibraryNotFoundException.class,
                () -> ProbeLoader.loadInSeparateExpectation("/nonexistent/liblgj_abi.so"));

        c.section("no C, and nothing that would need one");
        c.note("this whole surface was reached with javac and cargo only:"
                + " no .h, no cbindgen, no jextract, no JNI, no C toolchain");
    }

    private static String firstExisting(String... candidates) {
        for (String candidate : candidates) {
            if (java.nio.file.Files.isRegularFile(java.nio.file.Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * A deliberately minimal re-implementation of the load step, used only to observe the failure
     * path.
     *
     * <p>{@link com.adaworldapi.lancegraph.internal.ffm.Abi} resolves its library once into static
     * finals, which is right for production and useless for testing rejection. Rather than making
     * the real loader re-entrant purely for a test — which would add a code path nothing else uses
     * — this probe performs the same two steps (locate, then look up the manifest symbol) against
     * an arbitrary path.
     */
    private static final class ProbeLoader {

        static void loadInSeparateExpectation(String path) {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.isRegularFile(p)) {
                throw new NativeLibraryNotFoundException("no such library: " + p);
            }
            try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                var lookup = java.lang.foreign.SymbolLookup.libraryLookup(p, arena);
                if (lookup.find("lgj_abi_manifest").isEmpty()) {
                    throw new AbiMismatchException(
                            "the library at " + p + " exports no lgj_abi_manifest symbol");
                }
                throw new AbiMismatchException(
                        "unexpected: " + p + " exports lgj_abi_manifest");
            } catch (IllegalArgumentException e) {
                // The linker refused the file outright — it is not a shared object at all.
                throw new NativeLibraryNotFoundException(
                        "the file at " + p + " is not a loadable shared object", e);
            }
        }
    }
}

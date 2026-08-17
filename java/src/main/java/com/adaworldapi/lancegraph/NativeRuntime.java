package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.internal.ffm.Abi;

/**
 * What actually got loaded — for logs, benchmark headers, and bug reports.
 *
 * <p>Everything here is <em>reported</em>, never selected. In particular a caller cannot choose a
 * SIMD backend: which one is compiled in is decided upstream, and a Java-side switch would be a
 * second place for that decision to live. Knowing which one ran matters when reading a
 * measurement; choosing it does not.
 *
 * <p>This is also the only public surface that mentions the ABI at all, and it mentions it as
 * facts about a loaded artifact — no layout, no handle, no address.
 */
public final class NativeRuntime {

    private NativeRuntime() {}

    /** True if the native artifact loaded and passed the manifest cross-check. */
    public static boolean isAvailable() {
        return Abi.isAvailable();
    }

    /**
     * Why the library is unusable, or {@code null} if it loaded.
     *
     * <p>Lets a caller (a test, a startup probe) distinguish "not built yet" from "built but
     * incompatible" without catching anything.
     */
    public static LanceGraphException unavailableReason() {
        return Abi.loadFailure();
    }

    /** Absolute path of the artifact in use. */
    public static String libraryPath() {
        return Abi.libraryPath().toString();
    }

    /** ABI major version of the loaded artifact. Must match this build exactly. */
    public static int abiMajor() {
        return Abi.manifest().abiMajor();
    }

    /** ABI minor version of the loaded artifact. Must be at least what this build expects. */
    public static int abiMinor() {
        return Abi.manifest().abiMinor();
    }

    /** Human-readable name of the compiled SIMD backend, e.g. {@code "avx2"}. */
    public static String simdBackend() {
        return Abi.manifest().simdBackendName();
    }

    /** {@code "release"} or {@code "debug"} — worth printing before believing a benchmark. */
    public static String buildProfile() {
        return Abi.manifest().buildProfile();
    }

    /** One line summarising the loaded artifact. */
    public static String describe() {
        if (!isAvailable()) {
            return "lance-graph native runtime: UNAVAILABLE ("
                    + unavailableReason().getMessage() + ")";
        }
        return "lance-graph native runtime: abi " + abiMajor() + "." + abiMinor()
                + ", simd " + simdBackend()
                + ", profile " + buildProfile()
                + ", library " + libraryPath();
    }
}

package com.adaworldapi.lancegraph.internal.ffm;

import com.adaworldapi.lancegraph.AbiMismatchException;
import com.adaworldapi.lancegraph.LanceGraphException;
import com.adaworldapi.lancegraph.NativeLibraryNotFoundException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the native artifact and <strong>verifies</strong> it against this Java build's own
 * compiled-in expectations before the first real call.
 *
 * <p>This class is the header replacement. There is no {@code .h} anywhere in this project and
 * none will be added. A header is a text file that <em>claims</em> what a compiled artifact looks
 * like and can drift from it silently — the classic ABI break. Here the artifact describes
 * <em>itself</em> at runtime via {@code lgj_abi_manifest()}, and every reported size, alignment,
 * version, pointer width and byte order is cross-checked against numbers Java derived
 * independently from {@link Layouts}. The manifest is strictly stronger than a header, because it
 * is emitted by the compiled artifact and therefore cannot disagree with itself.
 *
 * <p>The check is <em>strict, not advisory</em>: the first disagreement throws
 * {@link AbiMismatchException} naming the exact field, and no further call is made.
 *
 * <p><strong>Internal.</strong> Nothing here may appear in a public API signature.
 */
public final class Abi {

    private Abi() {}

    /** Explicit path to the shared object. Highest precedence. */
    public static final String PROP_LIBRARY = "lgj.library";

    /** Directory to search for the platform-named artifact. */
    public static final String PROP_LIBRARY_DIR = "lgj.library.dir";

    /** Environment fallback for {@link #PROP_LIBRARY}. */
    public static final String ENV_LIBRARY = "LGJ_LIBRARY";

    /**
     * The manifest, as read and validated. Field names mirror {@code LgjAbiManifest} exactly.
     *
     * <p>Immutable, identity-free, no reference-equality reliance — one of the Valhalla A/B
     * candidates described in {@code com.adaworldapi.lancegraph.LaneId}.
     */
    public record Manifest(
            long magic,
            int abiMajor,
            int abiMinor,
            int sizeOfManifest,
            int sizeOfLaneDesc,
            int sizeOfOpDesc,
            int sizeOfResourceInfo,
            int alignOfLaneDesc,
            int alignOfOpDesc,
            int alignOfResourceInfo,
            int pointerBytes,
            int endianness,
            int simdBackend,
            String simdBackendName,
            String buildProfile) {}

    private static final Path LIBRARY_PATH;
    private static final SymbolLookup LOOKUP;
    private static final Manifest MANIFEST;
    private static final LanceGraphException LOAD_FAILURE;

    static {
        Path path = null;
        SymbolLookup lookup = null;
        Manifest manifest = null;
        LanceGraphException failure = null;
        try {
            path = locateLibrary();
            // The global arena keeps the library mapped for the life of the JVM. Lane addresses
            // handed out by the ABI are stable for the life of their owning resource (abi.md §4:
            // lanes are never reallocated, resized or moved), so unloading is never desirable.
            lookup = SymbolLookup.libraryLookup(path, Arena.global());
            manifest = readAndVerifyManifest(lookup, path);
        } catch (LanceGraphException e) {
            failure = e;
        } catch (Throwable t) {
            failure = new NativeLibraryNotFoundException(
                    "failed to load the lgj native library" + (path == null ? "" : " at " + path), t);
        }
        LIBRARY_PATH = path;
        LOOKUP = lookup;
        MANIFEST = manifest;
        LOAD_FAILURE = failure;
    }

    /**
     * Throw the stored load failure if the library is unusable.
     *
     * <p>The failure is captured rather than thrown from the static initialiser so that a caller
     * (in particular a test running before the artifact has been built) sees a precise
     * {@link NativeLibraryNotFoundException} instead of a {@code NoClassDefFoundError} caused by a
     * failed class initialisation.
     */
    public static void ensureLoaded() {
        if (LOAD_FAILURE != null) {
            throw LOAD_FAILURE;
        }
    }

    /** True if the native artifact loaded and verified. */
    public static boolean isAvailable() {
        return LOAD_FAILURE == null;
    }

    /** The reason the library is unusable, or {@code null} if it loaded. */
    public static LanceGraphException loadFailure() {
        return LOAD_FAILURE;
    }

    public static SymbolLookup lookup() {
        ensureLoaded();
        return LOOKUP;
    }

    public static Manifest manifest() {
        ensureLoaded();
        return MANIFEST;
    }

    public static Path libraryPath() {
        ensureLoaded();
        return LIBRARY_PATH;
    }

    /**
     * Guard a feature introduced at a later ABI minor than this Java build's own base load gate.
     *
     * <p>The base load gate in {@link #readAndVerifyManifest} only requires {@code minor >=
     * }{@link Layouts#LGJ_ABI_MINOR} (currently {@code 1}), per docs/abi.md §2's additive-minor
     * promise: an older Java build against a newer {@code .so} always loads. That promise runs in
     * one direction only. It says nothing about a Java build that was compiled against a
     * <em>later</em> minor — e.g. the row store at minor 2 (docs/abi.md §11) — being run against an
     * {@code .so} that is old enough to clear the base gate but too old to export the symbol that
     * specific feature needs. Left unchecked, that combination would fail deep inside the downcall
     * resolution in {@link Downcalls} with a bare "no such symbol" message, after the library has
     * already loaded and after other, older features have already been used successfully. This
     * method instead fails loudly, before any downcall for the feature is attempted, naming exactly
     * which minor the feature needs against exactly which minor loaded — e.g. a row-store entry
     * point calls {@code requireMinor(2)} and, on an older library, reports "row store requires ABI
     * minor >= 2".
     *
     * <p>Does not change, gate, or replace the base load gate — {@code minor >= 1} at load time is
     * unconditional and untouched by this method.
     *
     * @param required the minimum {@code abi_minor} the calling feature needs
     * @throws AbiMismatchException if the loaded library's manifest reports a lower minor
     */
    public static void requireMinor(int required) {
        int loaded = manifest().abiMinor();
        if (loaded < required) {
            throw new AbiMismatchException(String.format(
                    "this feature requires ABI minor >= %d, but the loaded library at %s reports"
                            + " minor %d. This Java build's base load gate (minor >= %d) was"
                            + " satisfied, but this specific feature is newer than what that gate"
                            + " alone guarantees is present; rebuild or replace the native library"
                            + " with one at minor >= %d.",
                    required, libraryPath(), loaded, Layouts.LGJ_ABI_MINOR, required));
        }
    }

    /** Human-readable name of the SIMD backend the artifact was compiled with. Reported, never negotiated. */
    public static String simdBackendName() {
        return manifest().simdBackendName();
    }

    // ── location ─────────────────────────────────────────────────────────────────────────────

    private static Path locateLibrary() {
        List<String> tried = new ArrayList<>();

        // An EXPLICIT request that cannot be honoured is a hard failure, never a fallback.
        //
        // This is not pedantry. Falling through to the search path here would mean that
        // `-Dlgj.library=/path/that/moved.so` silently loads whatever artifact happens to be lying
        // in a target directory instead — so a run intended to measure one library reports numbers
        // from another, and the log says nothing. Silent substitution of a different binary is the
        // exact failure mode the self-describing manifest exists to prevent one layer down; it
        // would be absurd to reintroduce it one layer up.
        String source = PROP_LIBRARY;
        String explicit = System.getProperty(PROP_LIBRARY);
        if (explicit == null || explicit.isBlank()) {
            source = ENV_LIBRARY;
            explicit = System.getenv(ENV_LIBRARY);
        }
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
            throw new NativeLibraryNotFoundException(
                    source + " names '" + p + "', which is not a regular file.\n"
                            + "  Refusing to fall back to a search path: an explicitly requested"
                            + " library that silently becomes a different one is worse than a"
                            + " failure. Fix the path, or unset " + source + " to search.");
        }

        String fileName = platformLibraryName();

        String dir = System.getProperty(PROP_LIBRARY_DIR);
        if (dir != null && !dir.isBlank()) {
            Path p = Path.of(dir, fileName);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
            throw new NativeLibraryNotFoundException(
                    PROP_LIBRARY_DIR + " names '" + dir + "', which contains no " + fileName + ".\n"
                            + "  Refusing to fall back to a search path, for the same reason as"
                            + " above: an explicit request is honoured or it fails.");
        }

        // Walk up from the working directory looking for a cargo target dir. Release first: if both
        // exist, a debug build silently used for a benchmark would be a measurement error.
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path base = cwd; base != null; base = base.getParent()) {
            for (String profile : new String[] {"release", "debug"}) {
                Path p = base.resolve("target").resolve(profile).resolve(fileName);
                if (Files.isRegularFile(p)) {
                    return p;
                }
                tried.add(p.toString());
            }
        }

        throw new NativeLibraryNotFoundException(
                "could not find the lgj native library (" + fileName + ").\n"
                        + "  Set -D" + PROP_LIBRARY + "=/path/to/" + fileName
                        + " or -D" + PROP_LIBRARY_DIR + "=/path/to/dir"
                        + " or the " + ENV_LIBRARY + " environment variable.\n"
                        + "  Searched:\n    " + String.join("\n    ", tried));
    }

    private static String platformLibraryName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "lgj_abi.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "liblgj_abi.dylib";
        }
        return "liblgj_abi.so";
    }

    // ── manifest read + cross-check ──────────────────────────────────────────────────────────

    private static Manifest readAndVerifyManifest(SymbolLookup lookup, Path path) {
        MemorySegment symbol = lookup.find("lgj_abi_manifest").orElseThrow(() ->
                new AbiMismatchException(
                        "the library at " + path + " exports no lgj_abi_manifest symbol, so it"
                                + " cannot describe itself; refusing to call into it"));

        // The one symbol that returns a pointer rather than a status, because it has no failure
        // mode: it returns a 'static.
        MethodHandle mh = java.lang.foreign.Linker.nativeLinker()
                .downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.ADDRESS));

        MemorySegment raw;
        try {
            raw = (MemorySegment) mh.invokeExact();
        } catch (Throwable t) {
            throw new AbiMismatchException("lgj_abi_manifest() could not be called: " + t);
        }
        if (raw.equals(MemorySegment.NULL)) {
            throw new AbiMismatchException("lgj_abi_manifest() returned NULL");
        }

        // Two-stage read. The returned segment is zero-length until reinterpreted, and how many
        // bytes are legal to read is exactly what is in dispute — so read a minimal prefix first
        // (magic, versions, size_of_manifest), decide whether the rest is safe, and only then widen.
        // Fields are read by layout-derived offset, not through a struct VarHandle: such a handle
        // bounds-checks the entire enclosing layout, which would defeat the point of a prefix read.
        long prefixBytes = Layouts.OFF_SIZE_OF_MANIFEST + Integer.BYTES;
        MemorySegment prefix = raw.reinterpret(prefixBytes);

        long magic = prefix.get(ValueLayout.JAVA_LONG, Layouts.OFF_MAGIC);
        if (magic != Layouts.LGJ_MAGIC) {
            throw new AbiMismatchException(String.format(
                    "manifest field 'magic' disagrees: library reports 0x%016X, this Java build"
                            + " expects 0x%016X. Read as a little-endian u64 the magic is also the"
                            + " endianness probe, so a mismatch here means either a different byte"
                            + " order or not an lgj artifact at all. Library: %s",
                    magic, Layouts.LGJ_MAGIC, path));
        }

        int major = prefix.get(ValueLayout.JAVA_INT, Layouts.OFF_ABI_MAJOR);
        if (major != Layouts.LGJ_ABI_MAJOR) {
            throw new AbiMismatchException(String.format(
                    "manifest field 'abi_major' disagrees: library is %d, this Java build was"
                            + " compiled against %d. A major mismatch is an incompatible change and"
                            + " is a hard failure, never a warning. Library: %s",
                    major, Layouts.LGJ_ABI_MAJOR, path));
        }

        int minor = prefix.get(ValueLayout.JAVA_INT, Layouts.OFF_ABI_MINOR);
        if (minor < Layouts.LGJ_ABI_MINOR) {
            throw new AbiMismatchException(String.format(
                    "manifest field 'abi_minor' disagrees: library is %d, this Java build requires"
                            + " >= %d. Additive changes bump minor; an older library cannot satisfy"
                            + " a newer caller. Library: %s",
                    minor, Layouts.LGJ_ABI_MINOR, path));
        }

        int sizeOfManifest = prefix.get(ValueLayout.JAVA_INT, Layouts.OFF_SIZE_OF_MANIFEST);
        long expectedManifestSize = Layouts.MANIFEST.byteSize();
        if (sizeOfManifest < expectedManifestSize) {
            throw new AbiMismatchException(String.format(
                    "manifest field 'size_of_manifest' disagrees: library reports %d bytes, this"
                            + " Java build's MemoryLayout derives %d. Reading the remaining fields"
                            + " would read past the artifact's own struct, so no further field is"
                            + " read. Library: %s",
                    sizeOfManifest, expectedManifestSize, path));
        }

        MemorySegment m = raw.reinterpret(Math.max(sizeOfManifest, expectedManifestSize));

        // Every remaining check compares a number the artifact emitted against a number derived
        // from Layouts — never a constant against itself.
        expect(path, "size_of_lane_desc", Layouts.LANE_DESC.byteSize(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_SIZE_OF_LANE_DESC));
        expect(path, "align_of_lane_desc", Layouts.LANE_DESC.byteAlignment(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_ALIGN_OF_LANE_DESC));
        expect(path, "size_of_op_desc", Layouts.OP_DESC.byteSize(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_SIZE_OF_OP_DESC));
        expect(path, "align_of_op_desc", Layouts.OP_DESC.byteAlignment(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_ALIGN_OF_OP_DESC));
        expect(path, "size_of_resource_info", Layouts.RESOURCE_INFO.byteSize(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_SIZE_OF_RESOURCE_INFO));
        expect(path, "align_of_resource_info", Layouts.RESOURCE_INFO.byteAlignment(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_ALIGN_OF_RESOURCE_INFO));
        expect(path, "pointer_bytes", ValueLayout.ADDRESS.byteSize(),
                m.get(ValueLayout.JAVA_INT, Layouts.OFF_POINTER_BYTES));

        int endianness = m.get(ValueLayout.JAVA_INT, Layouts.OFF_ENDIANNESS);
        if (endianness != Layouts.LGJ_ENDIAN_LITTLE) {
            throw new AbiMismatchException(
                    "manifest field 'endianness' is " + endianness + "; only 0 (little) is defined."
                            + " Library: " + path);
        }
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new AbiMismatchException(
                    "manifest field 'endianness' reports little-endian but this JVM's native order"
                            + " is " + ByteOrder.nativeOrder() + "; every struct read would be"
                            + " byte-swapped garbage. Library: " + path);
        }

        int backend = m.get(ValueLayout.JAVA_INT, Layouts.OFF_SIMD_BACKEND);
        String backendName = cString(m, Layouts.OFF_SIMD_BACKEND_NAME, Layouts.SIMD_NAME_BYTES);
        String profile = cString(m, Layouts.OFF_BUILD_PROFILE,
                Layouts.BUILD_PROFILE_BYTES);

        return new Manifest(magic, major, minor, sizeOfManifest,
                (int) Layouts.LANE_DESC.byteSize(), (int) Layouts.OP_DESC.byteSize(),
                (int) Layouts.RESOURCE_INFO.byteSize(),
                (int) Layouts.LANE_DESC.byteAlignment(), (int) Layouts.OP_DESC.byteAlignment(),
                (int) Layouts.RESOURCE_INFO.byteAlignment(),
                (int) ValueLayout.ADDRESS.byteSize(), endianness, backend, backendName, profile);
    }

    private static void expect(Path path, String field, long javaDerived, int libraryReports) {
        if (javaDerived != libraryReports) {
            throw new AbiMismatchException(String.format(
                    "manifest field '%s' disagrees: library reports %d, this Java build's"
                            + " MemoryLayout derives %d. Refusing to call into a layout this build"
                            + " would misread. Library: %s",
                    field, libraryReports, javaDerived, path));
        }
    }

    /** Read a fixed-size NUL-terminated byte field. The only strings that cross the membrane. */
    private static String cString(MemorySegment m, long offset, int maxBytes) {
        byte[] bytes = new byte[maxBytes];
        MemorySegment.copy(m, ValueLayout.JAVA_BYTE, offset, bytes, 0, maxBytes);
        int len = 0;
        while (len < maxBytes && bytes[len] != 0) {
            len++;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}

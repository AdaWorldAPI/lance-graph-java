package com.adaworldapi.lancegraph;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Enforces the absolute API rule by reflection, so it cannot rot.
 *
 * <p>The rule: <strong>a normal Java consumer must never need to know about
 * {@code MemorySegment}, {@code Arena}, {@code Linker}, {@code FunctionDescriptor},
 * {@code MethodHandle}, native addresses, SoA layout, masks-as-u64-words, lane ids, opcodes, or
 * which SIMD backend ran.</strong> Those are implementation physics and they live in exactly one
 * package.
 *
 * <p>A rule like that is normally a paragraph in a design document, which is to say it is normally
 * broken within a year by someone who never read the paragraph. Here it is a test: every public
 * member of every public type in the consumer package is inspected, and a single FFM type anywhere
 * in a signature fails the build.
 *
 * <p>It is checked reflectively rather than by grepping source, because the question is about the
 * <em>compiled surface</em> — a return type inherited from a superclass or introduced by a bridge
 * method is just as much a leak as one written by hand, and a grep would not see it.
 */
public final class ApiSurfaceTest {

    private ApiSurfaceTest() {}

    /** Package prefixes that no public consumer-facing signature may mention. */
    private static final String[] FORBIDDEN = {
        "java.lang.foreign.",     // MemorySegment, Arena, Linker, MemoryLayout, ValueLayout, ...
        "java.lang.invoke.",      // MethodHandle, VarHandle
        "com.adaworldapi.lancegraph.internal.",  // our own membrane, including PlanOp and Layouts
    };

    /** The package a consumer imports. */
    private static final String PUBLIC_PACKAGE = "com.adaworldapi.lancegraph";

    public static void main(String[] args) {
        System.out.println("ApiSurfaceTest");
        Checks c = new Checks("ApiSurfaceTest");
        run(c);
        System.exit(c.report());
    }

    /**
     * Note: this suite needs no native library. The API's shape is a compile-time property, so it
     * is checkable before the artifact exists — which is also when it is most useful.
     */
    public static void run(Checks c) {
        List<Class<?>> types = publicTypes(c);
        if (types.isEmpty()) {
            c.that("public types were discovered (if this fails, the classpath is not a directory"
                    + " and this suite cannot scan it)", false);
            return;
        }

        c.section("scanning the consumer package");
        c.note("inspecting " + types.size() + " public types in " + PUBLIC_PACKAGE);
        c.that("the scan found the types the API is actually made of",
                types.stream().anyMatch(t -> t.getSimpleName().equals("NativePattern"))
                        && types.stream().anyMatch(t -> t.getSimpleName().equals("View"))
                        && types.stream().anyMatch(t -> t.getSimpleName().equals("Pattern")));

        c.section("no FFM type, no membrane type, in any public signature");
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : types) {
            leaks.addAll(leaksIn(type));
        }
        if (leaks.isEmpty()) {
            c.that("every public member is free of "
                    + String.join(", ", FORBIDDEN), true);
        } else {
            for (String leak : leaks) {
                c.that("LEAK: " + leak, false);
            }
        }

        c.section("the T2/T3 membrane: no raw register crosses, and every array is a named breach");
        // Doctrine: .claude/knowledge/membrane-tiers.md (T2/T3). A public consumer
        // surface may carry NAMES (handle, classid, field name, version), counts and
        // statuses — never a raw content register (byte[] = a [u8;12] rail/payload) and
        // never an un-named array population. `byte[]` is folded into FORBIDDEN above.
        // Array returns are the row-id/slot-index leak: allowed ONLY from a method whose
        // name announces the crossing (materialize* out, import* in) — the GraphHopTest
        // allowlist, enforced here on the COMPILED surface so a bridge/inherited return
        // cannot slip it past a source grep.
        List<String> unnamedArrays = new ArrayList<>();
        for (Class<?> type : types) {
            for (Method m : type.getMethods()) {
                if (!isPublicApi(m) || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (m.getReturnType().isArray() && !isNamedBreach(m.getName())) {
                    unnamedArrays.add(type.getSimpleName() + "." + m.getName()
                            + " returns " + m.getReturnType().getSimpleName()
                            + " but its name does not start with materialize/import");
                }
            }
        }
        if (unnamedArrays.isEmpty()) {
            c.that("every array-returning public method names its crossing (materialize*/import*)", true);
        } else {
            for (String u : unnamedArrays) {
                c.that("UNNAMED-BREACH: " + u, false);
            }
        }

        c.section("ledger L1: no public construction of a field mask from raw bits, by SHAPE");
        // A facet slot index is a byte position and never crosses the wall. Fencing ONE factory
        // is not a fence (codex + coderabbit P2 on #75): a public record's canonical ctor took
        // the raw bits, and ofMatchBits(int) took a raw slot bitset. So WideFieldMask is a final
        // class with a private ctor, and the pin is on the SHAPE, not a name: no public
        // constructor, not a record, and every public factory takes ZERO arguments — the only
        // public values are EMPTY and allFacets() ("let the class decide"; RowStore.hop takes
        // the classid and native narrows by edge_participation). Any future public
        // bits-in factory, whatever its name, fails here.
        Class<?> wfm = WideFieldMask.class;
        c.that("WideFieldMask has no public constructor (a record's canonical ctor would be one)",
                wfm.getConstructors().length == 0);
        c.that("WideFieldMask is not a record (its public canonical ctor would take the raw bits)",
                !wfm.isRecord());
        List<String> bitsInFactories = new ArrayList<>();
        boolean allFacetsPublic = false;
        for (Method m : wfm.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getReturnType() == wfm && m.getParameterCount() != 0) {
                bitsInFactories.add(m.getName() + "/" + m.getParameterCount());
            }
            if (m.getName().equals("allFacets")) {
                allFacetsPublic = true;
            }
            if (m.getName().equals("ofFacets") || m.getName().equals("ofMatchBits")) {
                bitsInFactories.add(m.getName() + " (must be package-private)");
            }
        }
        c.that("every public WideFieldMask factory takes zero arguments (no bits-in path): "
                + bitsInFactories, bitsInFactories.isEmpty());
        c.that("WideFieldMask.allFacets() remains the consumer's participation vocabulary",
                allFacetsPublic);
        c.that("WideFieldMask exposes no public raw-bits accessor (value())",
                java.util.Arrays.stream(wfm.getMethods()).noneMatch(m -> m.getName().equals("value")));

        c.section("the fence sees THROUGH erasure (can-it-fire / can-it-stay-silent)");
        // The prefix pin below is only as good as the scan that applies it. codex P2 on #75: a
        // getReturnType() check sees `List<Engine.LaneWindow>` as `List`. checkType() walks
        // the generic signature; prove it fires on a hidden forbidden type (and nested/array
        // generics), and stays silent on an equally generic clean one — a guard that fires on
        // everything is as uninformative as one that never fires.
        try {
            List<String> hit = new ArrayList<>();
            checkType(hit, ApiSurfaceTest.class, "fixture return",
                    ApiSurfaceTest.class.getDeclaredMethod("genericLeakFixture").getGenericReturnType());
            c.that("can-it-fire: List<java.lang.invoke.MethodHandle> is flagged through erasure: " + hit,
                    !hit.isEmpty());
            List<String> erasedOnly = new ArrayList<>();
            check(erasedOnly, ApiSurfaceTest.class, "fixture return",
                    ApiSurfaceTest.class.getDeclaredMethod("genericLeakFixture").getReturnType());
            c.that("the erased-only check would have MISSED it (so the generic walk is load-bearing)",
                    erasedOnly.isEmpty());
            List<String> nested = new ArrayList<>();
            checkType(nested, ApiSurfaceTest.class, "fixture return",
                    ApiSurfaceTest.class.getDeclaredMethod("nestedGenericLeakFixture").getGenericReturnType());
            c.that("can-it-fire: Map<String, List<MethodHandle>[]> is flagged (nested + array generics)",
                    !nested.isEmpty());
            List<String> quiet = new ArrayList<>();
            checkType(quiet, ApiSurfaceTest.class, "fixture return",
                    ApiSurfaceTest.class.getDeclaredMethod("genericCleanFixture").getGenericReturnType());
            c.that("can-it-stay-silent: List<String> is NOT flagged", quiet.isEmpty());
        } catch (NoSuchMethodException e) {
            c.that("generic fixtures are present: " + e, false);
        }

        c.section("ledger L2: lane geometry (offset/stride) is fenced by the membrane prefix");
        // The type that carries a served lane's offset+stride is Engine.LaneWindow, in
        // internal.ffm. It is used INSIDE RowStore/Mask (which read the stride from the served
        // descriptor, never compute it) and by the sanctioned internal.ffm consumers (bench,
        // valhalla-lab). Prove the prefix fence structurally covers it, so L2 is closed by the
        // gate above rather than by a promise: the class must exist and must live under a
        // FORBIDDEN prefix — then any public signature carrying it, generic or not (the walk
        // above), is already a LEAK.
        Class<?> laneWindow = null;
        try {
            laneWindow = Class.forName("com.adaworldapi.lancegraph.internal.ffm.Engine$LaneWindow");
        } catch (ClassNotFoundException e) {
            // handled below
        }
        c.that("Engine.LaneWindow (the offset+stride carrier) exists", laneWindow != null);
        boolean underFence = false;
        if (laneWindow != null) {
            for (String forbidden : FORBIDDEN) {
                if (laneWindow.getName().startsWith(forbidden)) {
                    underFence = true;
                }
            }
        }
        c.that("Engine.LaneWindow lives under a FORBIDDEN prefix, so the prefix fence covers it",
                underFence);

        c.section("the escape hatch is named, not incidental");
        // Raw native access must require deliberately reaching into an internal package. It must
        // never be something ordinary composition hands you.
        c.that("the membrane package is not exported through any public type",
                leaks.isEmpty());
        c.note("raw MemorySegment access exists only via"
                + " com.adaworldapi.lancegraph.internal.ffm.Engine, which a consumer has to name"
                + " explicitly and which is documented as internal");

        c.section("the vocabulary a consumer actually needs is small");
        c.note("open a resource, take a view, add conditions, ask for a number:"
                + " NativePattern, View, Predicate, Pattern's fields, and a count");
    }

    private static List<String> leaksIn(Class<?> type) {
        List<String> leaks = new ArrayList<>();

        // GENERIC signatures, not erased ones: `List<Engine.LaneWindow>` erases to `List` and
        // would sail past a getReturnType() check. checkType() walks ParameterizedType /
        // GenericArrayType / WildcardType / TypeVariable recursively so a forbidden type is a
        // LEAK wherever it appears in the compiled signature (codex P2 on #75).
        for (Method m : type.getMethods()) {
            if (!isPublicApi(m) || m.getDeclaringClass() == Object.class) {
                continue;
            }
            checkType(leaks, type, "method " + m.getName() + " return", m.getGenericReturnType());
            for (Type p : m.getGenericParameterTypes()) {
                checkType(leaks, type, "method " + m.getName() + " parameter", p);
            }
        }

        for (Constructor<?> ctor : type.getConstructors()) {
            if (!isPublicApi(ctor)) {
                continue;
            }
            for (Type p : ctor.getGenericParameterTypes()) {
                checkType(leaks, type, "constructor parameter", p);
            }
        }

        for (Field f : type.getFields()) {
            if (!Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            checkType(leaks, type, "field " + f.getName(), f.getGenericType());
        }

        return leaks;
    }

    /**
     * Walk a reflective {@link Type} and run {@link #check} on every {@link Class} it reaches:
     * the raw class and each actual type argument of a {@link ParameterizedType}, the component
     * of a {@link GenericArrayType}, the bounds of a {@link WildcardType} and {@link
     * TypeVariable}. Erasure hides nothing from this.
     */
    static void checkType(List<String> leaks, Class<?> owner, String where, Type t) {
        if (t instanceof Class<?> c) {
            check(leaks, owner, where, c);
        } else if (t instanceof ParameterizedType pt) {
            checkType(leaks, owner, where, pt.getRawType());
            for (Type arg : pt.getActualTypeArguments()) {
                checkType(leaks, owner, where + " <type argument>", arg);
            }
        } else if (t instanceof GenericArrayType gat) {
            checkType(leaks, owner, where, gat.getGenericComponentType());
        } else if (t instanceof WildcardType wt) {
            for (Type b : wt.getUpperBounds()) {
                checkType(leaks, owner, where + " <wildcard bound>", b);
            }
            for (Type b : wt.getLowerBounds()) {
                checkType(leaks, owner, where + " <wildcard bound>", b);
            }
        } else if (t instanceof TypeVariable<?> tv) {
            for (Type b : tv.getBounds()) {
                checkType(leaks, owner, where + " <type-variable bound>", b);
            }
        }
    }

    // ── can-it-fire fixtures for checkType (private: never part of the scanned API) ──────────
    // A forbidden type hidden behind erasure. java.lang.invoke.MethodHandle is under a FORBIDDEN
    // prefix and is plain JDK (no preview), so this compiles on every toolchain the suite runs on.
    private static java.util.List<java.lang.invoke.MethodHandle> genericLeakFixture() {
        return null;
    }

    private static java.util.Map<String, java.util.List<java.lang.invoke.MethodHandle>[]> nestedGenericLeakFixture() {
        return null;
    }

    private static java.util.List<String> genericCleanFixture() {
        return null;
    }

    private static boolean isPublicApi(Executable e) {
        return Modifier.isPublic(e.getModifiers());
    }

    private static void check(List<String> leaks, Class<?> owner, String where, Class<?> t) {
        Class<?> component = t;
        boolean isArray = t.isArray();
        while (component.isArray()) {
            component = component.getComponentType();
        }
        String name = component.getName();
        for (String forbidden : FORBIDDEN) {
            if (name.startsWith(forbidden)) {
                leaks.add(owner.getSimpleName() + "." + where + " is " + name);
            }
        }
        // T2/T3 membrane: a byte[] in any public signature is a raw content register
        // (a [u8;12] rail array / payload bytes) crossing the wall — the substrate
        // wearing a collection. Names cross; registers never do.
        // (.claude/knowledge/membrane-tiers.md ledger L6.)
        if (isArray && component == byte.class) {
            leaks.add(owner.getSimpleName() + "." + where
                    + " is byte[] (a raw content register may not cross the consumer membrane)");
        }
    }

    /** A crossing whose method name announces it — the only sanctioned materialiser/importer. */
    private static boolean isNamedBreach(String methodName) {
        return methodName.startsWith("materialize") || methodName.startsWith("import");
    }

    /** Enumerate public types by listing the compiled package directory on the classpath. */
    private static List<Class<?>> publicTypes(Checks c) {
        List<Class<?>> found = new ArrayList<>();
        try {
            Path root = Path.of(ApiSurfaceTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path pkg = root.resolve(PUBLIC_PACKAGE.replace('.', '/'));
            if (!Files.isDirectory(pkg)) {
                return found;
            }
            try (Stream<Path> files = Files.list(pkg)) {
                for (Path p : files.toList()) {
                    String file = p.getFileName().toString();
                    if (!file.endsWith(".class") || file.contains("$")) {
                        continue;
                    }
                    String simple = file.substring(0, file.length() - ".class".length());
                    Class<?> t = Class.forName(PUBLIC_PACKAGE + "." + simple);
                    // Skip the test classes themselves; they live in the same package by design so
                    // that they can exercise package-private seams, but they are not the API.
                    if (Modifier.isPublic(t.getModifiers()) && !simple.endsWith("Test")
                            && !simple.equals("Checks") && !simple.equals("AllTests")) {
                        found.add(t);
                    }
                }
            }
        } catch (Exception e) {
            c.note("could not scan the classpath: " + e);
        }
        found.sort(java.util.Comparator.comparing(Class::getSimpleName));
        return found;
    }
}

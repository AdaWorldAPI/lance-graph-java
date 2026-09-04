package com.adaworldapi.lancegraph;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

        for (Method m : type.getMethods()) {
            if (!isPublicApi(m) || m.getDeclaringClass() == Object.class) {
                continue;
            }
            check(leaks, type, "method " + m.getName() + " return", m.getReturnType());
            for (Class<?> p : m.getParameterTypes()) {
                check(leaks, type, "method " + m.getName() + " parameter", p);
            }
        }

        for (Constructor<?> ctor : type.getConstructors()) {
            if (!isPublicApi(ctor)) {
                continue;
            }
            for (Class<?> p : ctor.getParameterTypes()) {
                check(leaks, type, "constructor parameter", p);
            }
        }

        for (Field f : type.getFields()) {
            if (!Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            check(leaks, type, "field " + f.getName(), f.getType());
        }

        return leaks;
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

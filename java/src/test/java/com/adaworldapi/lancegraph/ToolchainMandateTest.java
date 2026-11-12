package com.adaworldapi.lancegraph;

import java.io.IOException;
import java.lang.foreign.Linker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The toolchain this repository requires, checked at run time: JDK 28 or later, Valhalla value
 * classes for the six vocabulary types, and Panama (FFM) as the only way across to native code.
 *
 * <p>The compiler already refuses an older JDK ({@code --release 28}) and refuses {@code value
 * record} without {@code --enable-preview}. What it cannot refuse is a quiet retreat: a vocabulary
 * type turned back into an identity record, or a JNI entry point added beside the FFM membrane.
 * Both still compile. This suite is what fails then.
 *
 * <p>Needs no native library.
 */
public final class ToolchainMandateTest {

    private ToolchainMandateTest() {}

    private static final int MIN_FEATURE = 28;

    /** The vocabulary types that must be value classes (root CLAUDE.md, "P0 — JDK 28"). */
    private static final Class<?>[] VALUE_TYPES = {
        LaneId.class, MaskId.class, Ordinal.class, FacetId.class, RowRange.class,
        WideFieldMask.class,
    };

    /** JNI entry points: a library load outside FFM, or a {@code native} method declaration. */
    private static final Pattern JNI =
            Pattern.compile("System\\.load(Library)?\\s*\\(|\\bnative\\s+[\\w<>\\[\\].]+\\s+\\w+\\s*\\(");

    public static void main(String[] args) {
        System.out.println("ToolchainMandateTest");
        Checks c = new Checks("ToolchainMandateTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        c.section("JDK");
        int feature = Runtime.version().feature();
        c.that("running on JDK " + feature + " >= " + MIN_FEATURE, feature >= MIN_FEATURE);

        c.section("Valhalla");
        for (Class<?> t : VALUE_TYPES) {
            c.that(t.getSimpleName() + " is a value class", t.isValue());
        }
        // The check can fail: an identity class answers false.
        c.that("an identity class is not reported as a value class", !Checks.class.isValue());

        c.section("Panama");
        c.that("the FFM native linker is available", Linker.nativeLinker() != null);
        c.that("the JNI pattern matches a native method",
                JNI.matcher("    private static native long open(long n);").find());
        c.that("the JNI pattern matches a library load",
                JNI.matcher("System.loadLibrary(\"lgj_abi\");").find());
        c.that("the JNI pattern ignores prose", !JNI.matcher("one native crossing (bulk)").find());

        Path repo = DoctrineFenceTest.repoRoot();
        if (repo == null) {
            c.that("the repository root was found, so the source scan ran", false);
            return;
        }
        List<Path> files = DoctrineFenceTest.sourcesUnder(repo.resolve("java/src/main/java"), ".java");
        c.that("the scan covers the production tree (" + files.size() + " files >= 20)",
                files.size() >= 20);
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f);
            } catch (IOException e) {
                hits.add(f + ": unreadable");
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                    continue;
                }
                if (JNI.matcher(line).find()) {
                    hits.add(repo.relativize(f) + ":" + (i + 1));
                }
            }
        }
        c.that("no JNI entry point in java/src/main " + hits, hits.isEmpty());
    }
}

package io.jartree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ChangeType;
import io.jartree.compare.ClassChange;
import io.jartree.compare.CompareOptions;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.JarTreeComparer;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.Limits;
import io.jartree.compare.ResourceChange;
import io.jartree.compare.ResultCache;
import io.jartree.compare.ResultReader;
import io.jartree.compare.Timings;
import io.jartree.report.HtmlReport;
import io.jartree.report.JsonReport;
import io.jartree.report.PatchReport;
import picocli.CommandLine;

class JarTreeCompareTest {

    private static final String CALC_V1 = """
            package com.acme;
            public class Calc {
                public static final String NAME = "calc";
                public int add(int a, int b) {
                    return a + b;
                }
                public int twice(int a) {
                    return new Helper().apply(a);
                }
                static class Helper {
                    int apply(int a) { return a * 2; }
                }
            }
            """;

    private static final String CALC_V2 = """
            package com.acme;
            public class Calc {
                public static final String NAME = "calculator";
                public int add(int a, int b) {
                    if (a < 0) {
                        throw new IllegalArgumentException("negative");
                    }
                    return a + b;
                }
                public int twice(int a) {
                    return new Helper().apply(a);
                }
                public long multiply(long a, long b) {
                    return a * b;
                }
                static class Helper {
                    int apply(int a) { return a << 1; }
                }
            }
            """;

    private static final String UTIL = """
            package com.acme.util;
            public final class Strings {
                private Strings() {}
                public static String upper(String s) {
                    String r = s.toUpperCase();
                    return r;
                }
            }
            """;

    private static final String SERVLET_V1 = """
            package com.acme.web;
            public class Endpoint {
                public String handle(String in) { return "hello " + in; }
            }
            """;

    private static final String SERVLET_V2 = """
            package com.acme.web;
            public class Endpoint {
                public String handle(String in) { return "hello, " + in.trim(); }
            }
            """;

    @TempDir
    static Path tmp;

    static Path oldRoot;
    static Path newRoot;
    static ComparisonResult result;

    @BeforeAll
    static void buildTrees() throws IOException {
        oldRoot = tmp.resolve("old");
        newRoot = tmp.resolve("new");

        // versioned library with source changes
        Fixtures.zip().addAll("", Fixtures.compile(Map.of("com.acme.Calc", CALC_V1)))
                .add("META-INF/maven/com.acme/core/pom.properties", "#Generated\ngroupId=com.acme\nartifactId=core\nversion=1.0\n")
                .add("config.properties", "timeout=10\nretries=3\n")
                .write(oldRoot.resolve("lib/core-1.0.jar"));
        Fixtures.zip().addAll("", Fixtures.compile(Map.of("com.acme.Calc", CALC_V2)))
                .add("META-INF/maven/com.acme/core/pom.properties", "#Generated\ngroupId=com.acme\nartifactId=core\nversion=1.1\n")
                .add("config.properties", "timeout=30\nretries=3\n")
                .add("logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2})
                .write(newRoot.resolve("lib/core-1.1.jar"));

        // rebuilt with different timestamps and manifest build attributes only
        Map<String, byte[]> util = Fixtures.compile(Map.of("com.acme.util.Strings", UTIL));
        Fixtures.zip().time(1_600_000_000_000L).addAll("", util)
                .add("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBuild-Time: 2024-01-01\nImplementation-Title: util\n")
                .write(oldRoot.resolve("lib/util.jar"));
        Fixtures.zip().time(1_700_000_000_000L).addAll("", util)
                .add("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBuild-Time: 2025-06-01\nImplementation-Title: util\n")
                .write(newRoot.resolve("lib/util.jar"));

        // same source, compiled with and without debug information
        Fixtures.zip().addAll("", Fixtures.compile(Map.of("com.acme.util.Strings", UTIL), "-g"))
                .write(oldRoot.resolve("lib/debug.jar"));
        Fixtures.zip().addAll("", Fixtures.compile(Map.of("com.acme.util.Strings", UTIL), "-g:none"))
                .write(newRoot.resolve("lib/debug.jar"));

        // identical, removed, added
        Fixtures.zip().addAll("", util).write(oldRoot.resolve("lib/same.jar"));
        Fixtures.zip().addAll("", util).write(newRoot.resolve("lib/same.jar"));
        Fixtures.zip().add("a.txt", "a").write(oldRoot.resolve("lib/gone.jar"));
        Fixtures.zip().add("b.txt", "b").write(newRoot.resolve("lib/fresh.jar"));

        // war with changed WEB-INF/classes and a nested library with a new version
        byte[] nestedOld = Fixtures.zip().addAll("", Fixtures.compile(Map.of("com.acme.Calc", CALC_V1))).bytes();
        byte[] nestedNew = Fixtures.zip().addAll("", Fixtures.compile(Map.of("com.acme.Calc", CALC_V2))).bytes();
        Fixtures.zip()
                .addAll("WEB-INF/classes/", Fixtures.compile(Map.of("com.acme.web.Endpoint", SERVLET_V1)))
                .add("WEB-INF/lib/nested-1.0.jar", nestedOld)
                .add("WEB-INF/web.xml", "<web-app/>\n")
                .write(oldRoot.resolve("deploy/app-2.0.war"));
        Fixtures.zip()
                .addAll("WEB-INF/classes/", Fixtures.compile(Map.of("com.acme.web.Endpoint", SERVLET_V2)))
                .add("WEB-INF/lib/nested-1.1.jar", nestedNew)
                .add("WEB-INF/web.xml", "<web-app/>\n")
                .write(newRoot.resolve("deploy/app-2.1.war"));

        CompareOptions options = CompareOptions.defaults();
        result = new JarTreeComparer(options, msg -> { }).compare(oldRoot, newRoot);
    }

    private static LibraryDiff lib(String newOrOldPath) {
        return result.libraries().stream()
                .filter(l -> l.primary().path().equals(newOrOldPath))
                .findFirst().orElseThrow(() -> new AssertionError("no library " + newOrOldPath + " in "
                        + result.libraries().stream().map(l -> l.primary().path()).toList()));
    }

    @Test
    void versionedLibraryIsPairedAndSourceDiffed() {
        LibraryDiff core = lib("lib/core-1.1.jar");
        assertEquals(LibraryStatus.CHANGED, core.status(), core.error());
        assertEquals(LibraryDiff.MatchedBy.NAME, core.matchedBy());
        assertEquals("1.0 -> 1.1", core.versionLabel());
        assertEquals("com.acme:core", core.newLib().mavenGa());

        assertEquals(1, core.classes().size());
        ClassChange calc = core.classes().get(0);
        assertEquals("com.acme.Calc", calc.displayName());
        assertEquals(ChangeType.MODIFIED, calc.type());
        assertEquals(ClassChange.Nature.SOURCE, calc.nature());
        assertNull(calc.decompileProblem());
        assertEquals(2, calc.changedFiles().size(), "outer and inner class changed");

        String diff = calc.sourceDiff().text();
        assertTrue(diff.contains("+++ b/lib/core-1.1.jar!/com/acme/Calc.java"), diff);
        assertTrue(diff.contains("+") && diff.contains("IllegalArgumentException(\"negative\")"), diff);
        assertTrue(diff.contains("multiply"), diff);
        assertTrue(diff.contains("<< 1"), "inner class change is part of the outer source: " + diff);

        assertTrue(calc.api().added().stream().anyMatch(m -> m.contains("long multiply(long, long)")), calc.api().toString());
        assertTrue(calc.api().changed().stream().anyMatch(m -> m.contains("\"calc\"") && m.contains("\"calculator\"")),
                calc.api().toString());
    }

    @Test
    void resourcesAreClassified() {
        LibraryDiff core = lib("lib/core-1.1.jar");
        ResourceChange config = find(core, "config.properties");
        assertEquals(ChangeType.MODIFIED, config.type());
        assertTrue(config.text());
        assertFalse(config.noise());
        assertTrue(config.diff().text().contains("+timeout=30"), config.diff().text());

        ResourceChange logo = find(core, "logo.png");
        assertEquals(ChangeType.ADDED, logo.type());
        assertFalse(logo.text());

        ResourceChange pom = find(core, "META-INF/maven/com.acme/core/pom.properties");
        assertFalse(pom.noise(), "version change in pom.properties is not noise");
    }

    @Test
    void timestampAndManifestOnlyChangesAreRebuilt() {
        LibraryDiff util = lib("lib/util.jar");
        assertEquals(LibraryStatus.REBUILT, util.status());
        assertTrue(util.classes().isEmpty());
        assertEquals(1, util.resources().size());
        assertTrue(util.resources().get(0).noise());
    }

    @Test
    void debugInfoOnlyChangesAreRebuilt() {
        LibraryDiff debug = lib("lib/debug.jar");
        assertEquals(LibraryStatus.REBUILT, debug.status());
        assertEquals(ClassChange.Nature.DEBUG_INFO_ONLY, debug.classes().get(0).nature());
    }

    @Test
    void addedRemovedAndUnchanged() {
        assertEquals(LibraryStatus.UNCHANGED, lib("lib/same.jar").status());
        assertEquals(LibraryStatus.REMOVED, lib("lib/gone.jar").status());
        assertEquals(LibraryStatus.ADDED, lib("lib/fresh.jar").status());
    }

    @Test
    void warClassesAndNestedLibrariesAreCompared() {
        LibraryDiff war = lib("deploy/app-2.1.war");
        assertEquals(LibraryStatus.CHANGED, war.status());
        assertEquals("deploy/app-2.0.war", war.oldLib().path());
        ClassChange endpoint = war.classes().get(0);
        assertEquals("WEB-INF/classes/", endpoint.entryPrefix());
        assertEquals("com.acme.web.Endpoint", endpoint.internalName().replace('/', '.'));
        assertTrue(endpoint.sourceDiff().text().contains("trim()"), endpoint.sourceDiff().text());
        assertTrue(war.resources().stream().noneMatch(r -> r.path().endsWith(".jar")),
                "nested jars are compared as separate libraries");
        assertFalse(war.notes().isEmpty());

        LibraryDiff nested = lib("deploy/app-2.1.war!/WEB-INF/lib/nested-1.1.jar");
        assertEquals(LibraryStatus.CHANGED, nested.status());
        assertEquals("deploy/app-2.0.war!/WEB-INF/lib/nested-1.0.jar", nested.oldLib().path());
        assertEquals(ClassChange.Nature.SOURCE, nested.classes().get(0).nature());
    }

    @Test
    void reportsAreWritten() throws IOException {
        Path html = tmp.resolve("report.html");
        Path json = tmp.resolve("report.json");
        Path patch = tmp.resolve("changes.diff");
        new HtmlReport(true).write(result, html);
        new JsonReport(true).write(result, json);
        new PatchReport(false, false).write(result, patch);

        String h = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(h.contains("lib/core-1.1.jar"));
        assertTrue(h.contains("IllegalArgumentException(&quot;negative&quot;)"));
        String j = Files.readString(json, StandardCharsets.UTF_8);
        assertTrue(j.contains("\"status\": \"CHANGED\""));
        String p = Files.readString(patch, StandardCharsets.UTF_8);
        assertTrue(p.contains("--- a/lib/core-1.0.jar!/com/acme/Calc.java"));
        assertFalse(p.contains("Build-Time"), "noise is excluded from the patch");
    }

    @Test
    void memberChangesAreListedAndLocated() {
        ClassChange calc = lib("lib/core-1.1.jar").classes().get(0);
        MemberChange add = member(calc, "add");
        assertEquals(MemberChange.Change.MODIFIED, add.change());
        assertTrue(add.detail().contains("body"), add.detail());
        assertNotNull(add.newLine(), "add() located in the new source");
        assertNotNull(add.oldLine(), "add() located in the old source");
        assertTrue(add.newEnd() > add.newLine());

        MemberChange multiply = member(calc, "multiply");
        assertEquals(MemberChange.Change.ADDED, multiply.change());
        assertNotNull(multiply.newLine());
        assertNull(multiply.oldLine());

        assertEquals("constant", member(calc, "NAME").detail());
        MemberChange apply = member(calc, "apply");
        assertEquals("Helper", apply.owner());
        assertEquals("body", apply.detail());
        assertTrue(calc.members().stream().noneMatch(m -> m.name().equals("twice")), "unchanged method is not listed");
    }

    private static MemberChange member(ClassChange c, String name) {
        return c.members().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + c.members()));
    }

    @Test
    void cacheReusesResults() throws IOException {
        ResultCache cache = new ResultCache(tmp.resolve("cache"));
        ComparisonResult first = new JarTreeComparer(CompareOptions.defaults(), cache, m -> { }).compare(oldRoot, newRoot);
        assertEquals(0, cache.libraryHits());
        assertTrue(cache.sourceMisses() > 0);
        ComparisonResult second = new JarTreeComparer(CompareOptions.defaults(), cache, m -> { }).compare(oldRoot, newRoot);
        assertTrue(cache.libraryHits() >= 3, "changed libraries come from the cache: " + cache.libraryHits());
        for (int i = 0; i < first.libraries().size(); i++) {
            LibraryDiff a = first.libraries().get(i);
            LibraryDiff b = second.libraries().get(i);
            assertEquals(JsonReport.toJson(a), JsonReport.toJson(b));
            if (a.status() == LibraryStatus.CHANGED) {
                assertTrue(b.fromCache(), b.primary().path());
                assertFalse(b.newLib().loadEntries().isEmpty(), "cached result keeps a live library reference");
            }
        }
        // same classes in another location: the library key differs, the decompiled sources are reused
        int sourceHits = cache.sourceHits();
        new JarTreeComparer(CompareOptions.defaults(), cache, m -> { })
                .compare(oldRoot.resolve("lib/core-1.0.jar"), newRoot.resolve("lib/core-1.1.jar"));
        assertTrue(cache.sourceHits() > sourceHits);
        assertTrue(cache.size() > 0);
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void limitsReportTheValuesNeeded() throws IOException {
        CompareOptions d = CompareOptions.defaults();
        CompareOptions limited = new CompareOptions(d.extensions(), d.classDirNames(), 0, List.of("app-*.war"),
                List.of(), List.of(), true, false, false, 0, 3, 5000, 2, 10);
        ComparisonResult r = new JarTreeComparer(limited, m -> { }).compare(oldRoot, newRoot);
        Limits limits = r.limits();
        assertEquals(1, limits.librariesOverClassLimit());
        assertEquals(1, limits.skippedClasses());
        assertEquals(1, limits.maxClassesNeeded());
        assertEquals(1, limits.nestedDepthNeeded());
        assertTrue(limits.unopenedArchives().stream().anyMatch(a -> a.endsWith("WEB-INF/lib/nested-1.1.jar")),
                limits.unopenedArchives().toString());
        assertEquals(2, limits.hints().size());
        assertTrue(limits.hints().get(0).contains("--max-classes 1"), limits.hints().get(0));
        assertTrue(r.libraries().get(0).notes().stream().anyMatch(n -> n.contains("at least 1")));

        // an archive three levels deep below a top-level archive needs depth 3
        byte[] y = Fixtures.zip().add("y.txt", "y").bytes();
        byte[] x = Fixtures.zip().add("y.jar", y).bytes();
        byte[] war = Fixtures.zip().add("WEB-INF/lib/x.jar", x).bytes();
        Path deep = tmp.resolve("deep");
        Fixtures.zip().add("lib/a.war", war).write(deep.resolve("old/bundle.zip"));
        Fixtures.zip().add("lib/a.war", war).add("extra.txt", "e").write(deep.resolve("new/bundle.zip"));
        CompareOptions shallow = new CompareOptions(d.extensions(), d.classDirNames(), 1, List.of(), List.of(),
                List.of(), true, false, false, 2000, 3, 5000, 2, 10);
        Limits deepLimits = new JarTreeComparer(shallow, m -> { })
                .compare(deep.resolve("old"), deep.resolve("new")).limits();
        assertEquals(3, deepLimits.nestedDepthNeeded());
        assertFalse(deepLimits.classLimitReached());

        // and the JSON report keeps them
        Path json = tmp.resolve("limits.json");
        new JsonReport(true).write(r, json);
        assertEquals(limits, ResultReader.read(json).limits());
    }

    @Test
    void ignoredEntriesAreNotCompared() throws IOException {
        CompareOptions d = CompareOptions.defaults();
        CompareOptions options = new CompareOptions(d.extensions(), d.classDirNames(), d.nestedDepth(),
                List.of("core-*.jar", "util.jar"), List.of("META-INF/MANIFEST.MF"), List.of(), true, false, false,
                2000, 3, 5000, 2, 10, List.of("META-INF/MANIFEST.MF", "*.png", "META-INF/maven/**"));
        ComparisonResult r = new JarTreeComparer(options, m -> { }).compare(oldRoot, newRoot);

        LibraryDiff util = r.libraries().stream().filter(l -> l.primary().path().equals("lib/util.jar")).findFirst().orElseThrow();
        assertTrue(util.resources().isEmpty(), util.resources().toString());
        assertTrue(util.notes().stream().anyMatch(n -> n.contains("ignored")), util.notes().toString());

        LibraryDiff core = r.libraries().stream().filter(l -> l.primary().path().equals("lib/core-1.1.jar")).findFirst().orElseThrow();
        assertEquals(List.of("config.properties"), core.resources().stream().map(ResourceChange::path).toList());

        // an exclude pattern meant for an entry matches no library: the user is told about "Ignore entries"
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("'META-INF/MANIFEST.MF' matched no library")
                && w.contains("Ignore entries")), r.warnings().toString());
    }

    @Test
    void timingsShowWhereTimeIsSpent() throws IOException {
        Timings.Summary t = result.timings();
        assertEquals(Timings.Phase.values().length, t.phases().size());
        assertTrue(t.phases().stream().anyMatch(e -> e.phase() == Timings.Phase.DECOMPILE && e.count() > 0));
        assertTrue(t.phases().stream().anyMatch(e -> e.phase() == Timings.Phase.UNCOMPRESS && e.count() > 0));
        assertTrue(t.millis(Timings.Phase.DECOMPILE) > 0);
        assertFalse(t.slowestLibraries().isEmpty());
        assertTrue(t.line().contains("decompile") && t.line().contains("elapsed"), t.line());

        Path json = tmp.resolve("timings.json");
        new JsonReport(true).write(result, json);
        assertEquals(t, ResultReader.read(json).timings());
    }

    @Test
    void jsonReportCanBeReadBack() throws IOException {
        Path json = tmp.resolve("roundtrip.json");
        new JsonReport(true).write(result, json);
        ComparisonResult back = ResultReader.read(json);

        assertEquals(result.libraries().size(), back.libraries().size());
        assertEquals(result.countsByStatus(), back.countsByStatus());
        assertEquals(result.oldLibraryCount(), back.oldLibraryCount());
        for (int i = 0; i < result.libraries().size(); i++) {
            LibraryDiff a = result.libraries().get(i);
            LibraryDiff b = back.libraries().get(i);
            assertEquals(a.primary().path(), b.primary().path());
            assertEquals(a.versionLabel(), b.versionLabel());
            assertEquals(a.classes().size(), b.classes().size());
            for (int c = 0; c < a.classes().size(); c++) {
                ClassChange ca = a.classes().get(c);
                ClassChange cb = b.classes().get(c);
                assertEquals(ca.displayName(), cb.displayName());
                assertEquals(ca.entryPrefix(), cb.entryPrefix());
                assertEquals(ca.nature(), cb.nature());
                assertEquals(ca.api(), cb.api());
                assertEquals(ca.members(), cb.members());
                assertEquals(ca.sourceDiff(), cb.sourceDiff());
                assertEquals(ca.bytecodeDiff(), cb.bytecodeDiff());
            }
            assertEquals(a.resources(), b.resources());
        }
    }

    @Test
    void cliReturnsExitCodes() {
        Path html = tmp.resolve("cli.html");
        int changed = new CommandLine(new Main()).execute("-q", "--fail-on-change", "--html", html.toString(),
                oldRoot.toString(), newRoot.toString());
        assertEquals(1, changed);
        assertTrue(Files.exists(html));

        int same = new CommandLine(new Main()).execute("-q", "--fail-on-change", "--include", "same.jar,util.jar",
                oldRoot.toString(), newRoot.toString());
        assertEquals(0, same);
    }

    @Test
    void noDecompileFallsBackToBytecode() throws IOException {
        CompareOptions options = CompareOptions.defaults();
        options = new CompareOptions(options.extensions(), options.classDirNames(), options.nestedDepth(),
                java.util.List.of("core-*.jar"), options.excludes(), options.packages(), false, false, false,
                options.maxClassesPerLibrary(), options.contextLines(), options.maxDiffLines(), 2, 10);
        ComparisonResult r = new JarTreeComparer(options, msg -> { }).compare(oldRoot, newRoot);
        assertEquals(1, r.libraries().size());
        ClassChange calc = r.libraries().get(0).classes().get(0);
        assertEquals(ClassChange.Nature.NOT_DECOMPILED, calc.nature());
        assertTrue(calc.sourceDiff().isEmpty());
        assertTrue(calc.bytecodeDiff().text().contains("LDC \"negative\""), calc.bytecodeDiff().text());
    }

    private static ResourceChange find(LibraryDiff lib, String path) {
        ResourceChange rc = lib.resources().stream().filter(r -> r.path().equals(path)).findFirst().orElse(null);
        assertNotNull(rc, path + " in " + lib.resources());
        return rc;
    }
}

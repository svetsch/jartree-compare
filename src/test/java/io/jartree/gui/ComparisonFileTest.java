package io.jartree.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jartree.compare.CompareOptions;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.JarTreeComparer;
import io.jartree.compare.LibraryStatus;
import io.jartree.report.JsonReport;

class ComparisonFileTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripsSettingsAndResult() throws IOException {
        Path oldRoot = Files.createDirectories(tmp.resolve("old"));
        Path newRoot = Files.createDirectories(tmp.resolve("new"));
        jar(oldRoot.resolve("app-1.0.jar"), "config.properties", "mode=old\n");
        jar(newRoot.resolve("app-1.1.jar"), "config.properties", "mode=new\n");
        ComparisonResult result = new JarTreeComparer(CompareOptions.defaults(), m -> { })
                .compare(oldRoot, newRoot);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("includes", "**/app-*.jar");
        options.put("decompile", false);
        options.put("maxClasses", 70L);
        Map<String, String> applied = Map.of("Old path", oldRoot.toString(), "Packages", "com.acme");
        Path file = tmp.resolve("release." + ComparisonFile.EXTENSION);
        new ComparisonFile(oldRoot.toString(), newRoot.toString(), options,
                EnumSet.of(LibraryStatus.CHANGED, LibraryStatus.ADDED), "config", true, List.of("archive", "version"),
                applied, result).write(file);

        ComparisonFile read = ComparisonFile.read(file);
        assertEquals(oldRoot.toString(), read.oldPath());
        assertEquals(newRoot.toString(), read.newPath());
        assertEquals(options, read.options());
        assertEquals(EnumSet.of(LibraryStatus.CHANGED, LibraryStatus.ADDED), read.statuses());
        assertEquals("config", read.search());
        assertTrue(read.showNoise());
        assertEquals(List.of("archive", "version"), read.hiddenColumns());
        assertEquals(applied, read.appliedSettings());
        assertEquals(result.libraries().size(), read.result().libraries().size());
        assertEquals(LibraryStatus.CHANGED, read.result().libraries().get(0).status());
        assertTrue(read.result().libraries().get(0).resources().get(0).diff().text().contains("+mode=new"));
    }

    @Test
    void storesSettingsWithoutResult() throws IOException {
        Path file = tmp.resolve("empty." + ComparisonFile.EXTENSION);
        new ComparisonFile("a", "b", Map.of(), EnumSet.noneOf(LibraryStatus.class), "", false, null, null, null)
                .write(file);

        ComparisonFile read = ComparisonFile.read(file);
        assertEquals("a", read.oldPath());
        assertTrue(read.statuses().isEmpty());
        assertNull(read.result());
        assertNull(read.appliedSettings());
        assertNull(read.hiddenColumns(), "files without view settings keep the current columns");
    }

    @Test
    void rejectsReports() throws IOException {
        Path report = tmp.resolve("report.json");
        JsonReport.writeJson(Map.of("libraries", List.of()), report);
        assertThrows(IOException.class, () -> ComparisonFile.read(report));
        assertTrue(ComparisonFile.isComparisonFile(Path.of("x.JTCOMPARE")));
        assertEquals("release", ComparisonPane.fileTitle(Path.of("dir", "release.jtcompare")));
    }

    @Test
    void tabsWithoutResultAreNamedAfterTheirPaths() {
        assertEquals("app-1.0 ⇄ app-1.1", ComparisonPane.pathTitle("C:\\rel\\app-1.0\\", " /rel/app-1.1 "));
        assertEquals("a.jar ⇄ ?", ComparisonPane.pathTitle("lib/a.jar", ""));
        assertEquals("New comparison", ComparisonPane.pathTitle("", null));
    }

    @Test
    void pathNamesMatchBeforeAndAfterComparing() {
        // a comparison names the tab from the absolute, normalized paths; the typed text must give the same name
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String dir = cwd.getFileName().toString();
        assertEquals(dir + " ⇄ " + dir, ComparisonPane.pathTitle(".", "lib/.."));
        assertEquals(ComparisonPane.pathTitle(".", "lib/.."), ComparisonPane.pathTitle(cwd.toString(), cwd.toString()));
        String root = cwd.getRoot().toString();
        assertEquals(root + " ⇄ " + dir, ComparisonPane.pathTitle(root, cwd.toString()), "a root is not \"null\"");
    }

    private static void jar(Path file, String entry, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}

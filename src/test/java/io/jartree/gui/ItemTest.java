package io.jartree.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ClassChange;
import io.jartree.compare.CompareOptions;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.JarTreeComparer;
import io.jartree.compare.LibraryDiff;
import io.jartree.gui.Item.ClassItem;
import io.jartree.gui.Item.GroupItem;
import io.jartree.gui.Item.LibraryItem;
import io.jartree.gui.Item.MemberItem;
import io.jartree.gui.Item.ResourceItem;

class ItemTest {

    @TempDir
    Path tmp;

    @Test
    void archiveNameIsTheDeepestArchive() throws IOException {
        Path oldRoot = Files.createDirectories(tmp.resolve("old/deploy"));
        Path newRoot = Files.createDirectories(tmp.resolve("new/deploy"));
        Files.write(oldRoot.resolve("app.war"), zip(Map.of("WEB-INF/lib/nested-1.0.jar", zip(Map.of("a.txt",
                "one\n".getBytes(StandardCharsets.UTF_8))))));
        Files.write(newRoot.resolve("app.war"), zip(Map.of("WEB-INF/lib/nested-1.1.jar", zip(Map.of("a.txt",
                "two\n".getBytes(StandardCharsets.UTF_8))))));
        ComparisonResult result = new JarTreeComparer(CompareOptions.defaults(), m -> { })
                .compare(oldRoot.getParent(), newRoot.getParent());

        LibraryDiff nested = result.libraries().stream()
                .filter(l -> l.primary().path().endsWith("nested-1.1.jar")).findFirst().orElseThrow();
        assertEquals("nested-1.1.jar", new LibraryItem(nested).archiveName());
        assertEquals("nested-1.1.jar",
                new ResourceItem(nested, nested.resources().get(0)).archiveName());
        LibraryDiff war = result.libraries().stream()
                .filter(l -> l.primary().path().endsWith("app.war")).findFirst().orElseThrow();
        assertEquals("app.war", new GroupItem(war, "Resources", 0).archiveName());
    }

    @Test
    void fileNameIsTheDeepestFile() throws IOException {
        Path oldRoot = Files.createDirectories(tmp.resolve("old"));
        Path newRoot = Files.createDirectories(tmp.resolve("new"));
        Map<String, byte[]> oldJar = new java.util.HashMap<>(compile("""
                package com.acme;
                public class Calc {
                    public int add(int a, int b) { return a + b; }
                    static class Helper { int apply(int a) { return a * 2; } }
                }
                """, "old"));
        oldJar.put("config/app.properties", "mode=old\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> newJar = new java.util.HashMap<>(compile("""
                package com.acme;
                public class Calc {
                    public int add(int a, int b) { return b + a + 0 * a; }
                    static class Helper { int apply(int a) { return a << 1; } }
                    static class Extra { }
                }
                """, "new"));
        newJar.put("config/app.properties", "mode=new\n".getBytes(StandardCharsets.UTF_8));
        Files.write(oldRoot.resolve("calc-1.0.jar"), zip(oldJar));
        Files.write(newRoot.resolve("calc-1.1.jar"), zip(newJar));
        LibraryDiff lib = new JarTreeComparer(CompareOptions.defaults(), m -> { }).compare(oldRoot, newRoot)
                .libraries().get(0);

        assertEquals("calc-1.1.jar", new LibraryItem(lib).fileName());
        assertEquals("", new GroupItem(lib, "Classes", 1).fileName());
        ClassChange calc = lib.classes().get(0);
        assertEquals("Calc.class", new ClassItem(lib, calc).fileName());
        assertEquals("Calc.class", new MemberItem(lib, calc, member(calc, "add")).fileName());
        assertEquals("Calc$Helper.class", new MemberItem(lib, calc, member(calc, "apply")).fileName());
        assertEquals("Calc$Extra.class", new MemberItem(lib, calc, member(calc, "Extra")).fileName());
        assertEquals("app.properties", new ResourceItem(lib, lib.resources().get(0)).fileName());
    }

    private static MemberChange member(ClassChange c, String name) {
        return c.members().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + c.members()));
    }

    /** Compiles one source file and returns its class files by entry name. */
    private Map<String, byte[]> compile(String source, String dir) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src-" + dir + "/com/acme")).resolve("Calc.java");
        Files.writeString(src, source);
        Path out = Files.createDirectories(tmp.resolve("classes-" + dir));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString());
        assertEquals(0, rc, "compilation");
        Map<String, byte[]> classes = new java.util.HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                classes.put(out.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
            }
        }
        return classes;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}

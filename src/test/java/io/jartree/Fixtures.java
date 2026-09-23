package io.jartree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Builds class files and archives for tests. */
final class Fixtures {

    private Fixtures() {
    }

    /** Compiles sources (class name to code) in memory and returns entry path to bytes. */
    static Map<String, byte[]> compile(Map<String, String> sources, String... options) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        StandardJavaFileManager std = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                       FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        outputs.put(className.replace('.', '/') + ".class", bos);
                        return bos;
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, code) -> units.add(
                new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return code;
                    }
                }));
        List<String> opts = new ArrayList<>(List.of("--release", "17"));
        opts.addAll(List.of(options));
        if (!compiler.getTask(null, fm, null, opts, null, units).call()) {
            throw new IllegalStateException("compilation failed");
        }
        Map<String, byte[]> result = new TreeMap<>();
        outputs.forEach((k, v) -> result.put(k, v.toByteArray()));
        return result;
    }

    static final class ZipBuilder {
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private long time = 1_600_000_000_000L;

        ZipBuilder time(long millis) {
            this.time = millis;
            return this;
        }

        ZipBuilder add(String path, byte[] content) {
            entries.put(path, content);
            return this;
        }

        ZipBuilder add(String path, String content) {
            return add(path, content.getBytes(StandardCharsets.UTF_8));
        }

        ZipBuilder addAll(String prefix, Map<String, byte[]> files) {
            files.forEach((k, v) -> add(prefix + k, v));
            return this;
        }

        byte[] bytes() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (var e : entries.entrySet()) {
                    ZipEntry ze = new ZipEntry(e.getKey());
                    ze.setTime(time);
                    zos.putNextEntry(ze);
                    zos.write(e.getValue());
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        }

        void write(Path file) throws IOException {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes());
        }
    }

    static ZipBuilder zip() {
        return new ZipBuilder();
    }
}

package io.jartree.scan;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a directory tree (or a single archive) and collects every library in it, descending into nested archives
 * such as {@code WEB-INF/lib/*.jar} inside a war, or {@code BOOT-INF/lib/*.jar} inside a Spring Boot jar.
 */
public final class TreeScanner {

    @FunctionalInterface
    private interface ByteSupplier {
        byte[] get() throws IOException;
    }

    private static final Pattern POM_PROPERTIES = Pattern.compile("META-INF/maven/([^/]+)/([^/]+)/pom\\.properties");

    private final Set<String> extensions;
    private final Set<String> classDirNames;
    private final int maxDepth;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> unopened = new ArrayList<>();
    private final AtomicInteger depthNeeded = new AtomicInteger();

    /**
     * @param extensions    archive extensions (without dot) treated as libraries
     * @param classDirNames names of exploded class directories treated as libraries (e.g. {@code classes})
     * @param maxDepth      how deep to descend into nested archives (0 = do not open nested archives)
     */
    public TreeScanner(Set<String> extensions, Set<String> classDirNames, int maxDepth) {
        this.extensions = new TreeSet<>();
        extensions.forEach(e -> this.extensions.add(e.toLowerCase(Locale.ROOT)));
        this.classDirNames = Set.copyOf(classDirNames);
        this.maxDepth = maxDepth;
        NameParser.registerExtensions(this.extensions);
    }

    public List<String> warnings() {
        synchronized (warnings) {
            return List.copyOf(warnings);
        }
    }

    /** Nested archives that were not opened because of the depth limit. */
    public List<String> unopenedArchives() {
        synchronized (unopened) {
            return List.copyOf(unopened);
        }
    }

    /** Nested archive depth needed to open every archive, or 0 if the limit was not reached. */
    public int nestedDepthNeeded() {
        return depthNeeded.get();
    }

    public List<LibraryRef> scan(Path root) throws IOException {
        ConcurrentLinkedQueue<LibraryRef> out = new ConcurrentLinkedQueue<>();
        if (Files.isRegularFile(root)) {
            String name = root.getFileName().toString();
            scanArchive(name, LibraryRef.Kind.ARCHIVE, () -> Files.readAllBytes(root), Files.readAllBytes(root), 0, out);
        } else if (Files.isDirectory(root)) {
            List<Path> archives = new ArrayList<>();
            List<Path> dirLibraries = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.forEach(p -> {
                    if (p.equals(root)) {
                        return;
                    }
                    if (Files.isRegularFile(p) && isArchiveName(p.getFileName().toString())) {
                        archives.add(p);
                    } else if (Files.isDirectory(p) && isDirectoryLibrary(p.getFileName().toString())) {
                        dirLibraries.add(p);
                    }
                });
            }
            archives.parallelStream().forEach(p -> {
                String rel = relative(root, p);
                try {
                    scanArchive(rel, LibraryRef.Kind.ARCHIVE, () -> Files.readAllBytes(p), Files.readAllBytes(p), 0, out);
                } catch (IOException e) {
                    warn("Cannot read " + rel + ": " + e.getMessage());
                }
            });
            for (Path dir : dirLibraries) {
                scanDirectory(root, dir, dirLibraries, out);
            }
        } else {
            throw new IOException("Not a file or directory: " + root);
        }
        List<LibraryRef> result = new ArrayList<>(out);
        result.sort(Comparator.comparing(LibraryRef::path));
        return result;
    }

    private void scanArchive(String path, LibraryRef.Kind kind, ByteSupplier supplier, byte[] data, int depth,
                             ConcurrentLinkedQueue<LibraryRef> out) {
        Set<String> nested = new TreeSet<>();
        List<String[]> poms = new ArrayList<>();
        int[] counts = new int[2];
        try {
            ZipUtil.forEachEntry(data, (name, content) -> {
                counts[0]++;
                if (name.endsWith(".class")) {
                    counts[1]++;
                }
                if (depth < maxDepth && isArchiveName(name)) {
                    nested.add(name);
                    ByteSupplier nestedSupplier = () -> {
                        byte[] bytes = ZipUtil.extract(supplier.get(), name);
                        if (bytes == null) {
                            throw new IOException("Nested entry vanished: " + name);
                        }
                        return bytes;
                    };
                    scanArchive(path + "!/" + name, LibraryRef.Kind.NESTED_ARCHIVE, nestedSupplier, content, depth + 1, out);
                    return;
                }
                if (isArchiveName(name)) {
                    // not opened: remember how deep the limit would have to be to open it and what it contains
                    depthNeeded.accumulateAndGet(depth + 1 + nesting(content, 0), Math::max);
                    synchronized (unopened) {
                        unopened.add(path + "!/" + name);
                    }
                }
                Matcher m = POM_PROPERTIES.matcher(name);
                if (m.matches()) {
                    Properties props = new Properties();
                    props.load(new ByteArrayInputStream(content));
                    poms.add(new String[] {
                            props.getProperty("groupId", m.group(1)),
                            props.getProperty("artifactId", m.group(2)),
                            props.getProperty("version")});
                }
            });
        } catch (IOException | RuntimeException e) {
            warn("Cannot open archive " + path + ": " + e.getMessage());
        }

        String fileName = path.substring(path.lastIndexOf('/') + 1);
        NameParser.ParsedName parsed = NameParser.parse(fileName);
        String[] pom = selectPom(poms, parsed.name());
        Set<String> nestedCopy = Set.copyOf(nested);
        LibraryRef.ContentLoader loader = () -> {
            Map<String, byte[]> entries = new TreeMap<>();
            ZipUtil.forEachEntry(supplier.get(), (name, content) -> {
                if (!nestedCopy.contains(name)) {
                    entries.put(name, content);
                }
            });
            return entries;
        };
        out.add(new LibraryRef(path, kind, parsed.name(), parsed.version(), parsed.extension(),
                pom == null ? null : pom[0] + ":" + pom[1], pom == null ? null : pom[2],
                ZipUtil.sha256(data), data.length, counts[0], counts[1], nested, loader));
    }

    /** How many levels of archives are nested inside the given archive (0 if none). */
    private int nesting(byte[] archive, int level) {
        if (level > 32) {
            return 0;
        }
        int[] max = new int[1];
        try {
            ZipUtil.forEachEntry(archive, (name, content) -> {
                if (isArchiveName(name)) {
                    max[0] = Math.max(max[0], 1 + nesting(content, level + 1));
                }
            });
        } catch (IOException | RuntimeException e) {
            return max[0];
        }
        return max[0];
    }

    private static String[] selectPom(List<String[]> poms, String artifactName) {
        if (poms.size() == 1) {
            return poms.get(0);
        }
        List<String[]> named = poms.stream().filter(p -> p[1].equals(artifactName)).toList();
        return named.size() == 1 ? named.get(0) : null;
    }

    private void scanDirectory(Path root, Path dir, List<Path> dirLibraries, ConcurrentLinkedQueue<LibraryRef> out)
            throws IOException {
        String rel = relative(root, dir);
        List<Path> files = new ArrayList<>();
        Set<String> nested = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                for (Path other : dirLibraries) {
                    if (!other.equals(dir) && other.startsWith(dir) && p.startsWith(other)) {
                        return;
                    }
                }
                if (isArchiveName(p.getFileName().toString())) {
                    nested.add(relative(dir, p));
                } else {
                    files.add(p);
                }
            });
        }
        files.sort(Comparator.comparing(p -> relative(dir, p)));

        MessageDigest md = ZipUtil.digest();
        long size = 0;
        int classes = 0;
        for (Path f : files) {
            byte[] content = Files.readAllBytes(f);
            size += content.length;
            if (f.getFileName().toString().endsWith(".class")) {
                classes++;
            }
            md.update(relative(dir, f).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(ZipUtil.sha256(content).getBytes(StandardCharsets.US_ASCII));
            md.update((byte) '\n');
        }
        LibraryRef.ContentLoader loader = () -> {
            Map<String, byte[]> entries = new TreeMap<>();
            try {
                files.forEach(f -> entries.put(relative(dir, f), ZipUtil.readFile(f)));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            return entries;
        };
        NameParser.ParsedName parsed = NameParser.parse(dir.getFileName().toString());
        out.add(new LibraryRef(rel, LibraryRef.Kind.DIRECTORY, parsed.name(), parsed.version(), parsed.extension(),
                null, null, HexFormat.of().formatHex(md.digest()), size, files.size(), classes, nested, loader));
    }

    private boolean isArchiveName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private boolean isDirectoryLibrary(String name) {
        return classDirNames.contains(name) || isArchiveName(name);
    }

    private void warn(String message) {
        synchronized (warnings) {
            warnings.add(message);
        }
    }

    private static String relative(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }
}

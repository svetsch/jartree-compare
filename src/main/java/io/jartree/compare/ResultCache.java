package io.jartree.compare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.jartree.report.JsonParser;
import io.jartree.report.JsonReport;
import io.jartree.scan.LibraryRef;
import io.jartree.scan.ZipUtil;

/**
 * Disk cache that makes repeated comparisons fast.
 * <ul>
 * <li><b>library results</b>: the complete diff of a library pair, keyed by both SHA-256 digests, both paths and the
 * options that influence the result;</li>
 * <li><b>decompiled sources</b>: the Vineflower output of a top-level class with its inner classes, keyed by the
 * class file bytes, so that a library version seen in an earlier comparison is not decompiled again.</li>
 * </ul>
 * Entries are gzip-compressed files below the cache directory. Unreadable entries are ignored.
 */
public final class ResultCache {

    /** Bump when the cached content or its interpretation changes. */
    private static final String FORMAT = "3";
    private static final String DECOMPILER = "vineflower-1.12.0";

    private final Path dir;
    private final AtomicInteger libraryHits = new AtomicInteger();
    private final AtomicInteger sourceHits = new AtomicInteger();
    private final AtomicInteger sourceMisses = new AtomicInteger();

    public ResultCache(Path dir) {
        this.dir = dir;
    }

    /** {@code ~/.jartree-compare/cache} */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".jartree-compare", "cache");
    }

    public Path directory() {
        return dir;
    }

    public int libraryHits() {
        return libraryHits.get();
    }

    public int sourceHits() {
        return sourceHits.get();
    }

    public int sourceMisses() {
        return sourceMisses.get();
    }

    // ------------------------------------------------------------------ library results

    static String libraryKey(LibraryRef oldLib, LibraryRef newLib, CompareOptions o) {
        return hash(String.join("\n", FORMAT, DECOMPILER, oldLib.sha256(), newLib.sha256(), oldLib.path(),
                newLib.path(), String.valueOf(o.nestedDepth()), String.join(",", o.extensions().stream().sorted().toList()),
                String.join(",", o.packages().stream().sorted().toList()),
                String.join(",", o.ignoredEntries().stream().sorted().toList()), String.valueOf(o.decompile()),
                String.valueOf(o.decompileAddedRemoved()), String.valueOf(o.bytecodeDiff()),
                String.valueOf(o.maxClassesPerLibrary()), String.valueOf(o.contextLines()),
                String.valueOf(o.maxDiffLines())));
    }

    LibraryDiff getLibrary(String key, LibraryRef oldLib, LibraryRef newLib, LibraryDiff.MatchedBy matchedBy) {
        String json = read(file("libraries", key, ".json.gz"));
        if (json == null) {
            return null;
        }
        try {
            LibraryDiff diff = ResultReader.library((Map<?, ?>) JsonParser.parse(json), oldLib, newLib, matchedBy);
            diff.cached = true;
            libraryHits.incrementAndGet();
            return diff;
        } catch (RuntimeException e) {
            return null;
        }
    }

    void putLibrary(String key, LibraryDiff diff) {
        write(file("libraries", key, ".json.gz"), JsonReport.toJson(diff));
    }

    // ------------------------------------------------------------------ decompiled sources

    /** Key of a class group: the class files (internal name and bytes) that are decompiled together. */
    static String sourceKey(Map<String, byte[]> files) {
        MessageDigest md = ZipUtil.digest();
        md.update((FORMAT + "\n" + DECOMPILER + "\n").getBytes(StandardCharsets.UTF_8));
        files.forEach((name, bytes) -> {
            md.update(name.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(bytes);
        });
        return HexFormat.of().formatHex(md.digest());
    }

    String getSource(String key) {
        String s = read(file("sources", key, ".java.gz"));
        if (s != null) {
            sourceHits.incrementAndGet();
        } else {
            sourceMisses.incrementAndGet();
        }
        return s;
    }

    void putSource(String key, String source) {
        write(file("sources", key, ".java.gz"), source);
    }

    // ------------------------------------------------------------------ maintenance

    /** Total size of the cache in bytes. */
    public long size() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }

    /** Deletes all entries. */
    public void clear() throws IOException {
        deleteWhere(p -> true);
    }

    /** Deletes entries not used for the given duration (use refreshes an entry's modification time). */
    public void prune(Duration maxAge) {
        Instant limit = Instant.now().minus(maxAge);
        try {
            deleteWhere(p -> Files.getLastModifiedTime(p).toInstant().isBefore(limit));
        } catch (IOException e) {
            // pruning is best effort
        }
    }

    private interface PathTest {
        boolean test(Path p) throws IOException;
    }

    private void deleteWhere(PathTest test) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) {
            if (test.test(p)) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ files

    private Path file(String kind, String key, String suffix) {
        return dir.resolve(kind).resolve(key.substring(0, 2)).resolve(key + suffix);
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "tmp-", ".gz");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // a cache that cannot be written only costs speed
        }
    }

    static String hash(String s) {
        return ZipUtil.sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}

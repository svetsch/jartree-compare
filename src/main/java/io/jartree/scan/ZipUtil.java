package io.jartree.scan;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Helpers to read zip archives. Entries are streamed one at a time: an archive is never held decompressed in
 * memory as a whole.
 */
public final class ZipUtil {

    @FunctionalInterface
    public interface EntryVisitor {
        void visit(String name, byte[] content) throws IOException;
    }

    private ZipUtil() {
    }

    /**
     * Visits every non-directory entry of an in-memory archive. Falls back to {@link ZipFile} (via a temporary
     * file) for archives that {@link ZipInputStream} cannot handle, e.g. executable jars with a prepended launch
     * script or stored entries with data descriptors; entries already visited are then skipped.
     */
    public static void forEachEntry(byte[] data, EntryVisitor visitor) throws IOException {
        Set<String> visited = new HashSet<>();
        if (looksLikeZip(data)) {
            try {
                try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
                    ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        if (!e.isDirectory()) {
                            visited.add(e.getName());
                            visitor.visit(e.getName(), zin.readAllBytes());
                        }
                    }
                }
                return;
            } catch (ZipException | UnsupportedOperationException e) {
                // fall through to the central-directory based reader
            }
        }
        Path tmp = Files.createTempFile("jartree-", ".zip");
        try {
            Files.write(tmp, data);
            forEachEntry(tmp, (name, content) -> {
                if (visited.add(name)) {
                    visitor.visit(name, content);
                }
            });
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void forEachEntry(Path file, EntryVisitor visitor) throws IOException {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                try (InputStream in = zf.getInputStream(e)) {
                    visitor.visit(e.getName(), in.readAllBytes());
                }
            }
        }
    }

    /** Reads only the named entries of an archive file, using the central directory. */
    public static Map<String, byte[]> readEntries(Path file, Set<String> names) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        if (names.isEmpty()) {
            return result;
        }
        try (ZipFile zf = new ZipFile(file.toFile())) {
            for (String name : names) {
                ZipEntry entry = zf.getEntry(name);
                if (entry != null && !entry.isDirectory()) {
                    try (InputStream in = zf.getInputStream(entry)) {
                        result.put(name, in.readAllBytes());
                    }
                }
            }
        }
        return result;
    }

    /** Reads only the named entries of an in-memory archive, in one pass. */
    public static Map<String, byte[]> readEntries(byte[] data, Set<String> names) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        if (names.isEmpty()) {
            return result;
        }
        forEachEntry(data, (name, content) -> {
            if (names.contains(name)) {
                result.put(name, content);
            }
        });
        return result;
    }

    /** Extracts a single entry, or {@code null} if absent. */
    public static byte[] extract(byte[] archive, String entryName) throws IOException {
        return readEntries(archive, Set.of(entryName)).get(entryName);
    }

    public static boolean looksLikeZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    /** Digest of a file, computed while streaming it: the file is not held in memory. */
    public static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            byte[] buffer = new byte[64 * 1024];
            while (in.read(buffer) >= 0) {
                // the digest is updated while reading
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] readFile(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package io.jartree.scan;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Helpers to stream the entries of an in-memory zip archive. */
public final class ZipUtil {

    @FunctionalInterface
    public interface EntryVisitor {
        void visit(String name, byte[] content) throws IOException;
    }

    private ZipUtil() {
    }

    /**
     * Visits every non-directory entry. Falls back to {@link ZipFile} (via a temporary file) for archives that
     * {@link ZipInputStream} cannot handle, e.g. executable jars with a prepended launch script or stored entries
     * with data descriptors.
     */
    public static void forEachEntry(byte[] data, EntryVisitor visitor) throws IOException {
        boolean startsWithLocalHeader = data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
        if (startsWithLocalHeader) {
            try {
                streamEntries(data, visitor);
                return;
            } catch (ZipException | UnsupportedOperationException e) {
                // fall through to the central-directory based reader
            }
        }
        Path tmp = Files.createTempFile("jartree-", ".zip");
        try {
            Files.write(tmp, data);
            forEachEntry(tmp, visitor);
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

    private static void streamEntries(byte[] data, EntryVisitor visitor) throws IOException {
        // collect first so that a failure half-way does not produce duplicate visits on fallback
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    entries.add(Map.entry(e.getName(), zin.readAllBytes()));
                }
            }
        }
        for (var entry : entries) {
            visitor.visit(entry.getKey(), entry.getValue());
        }
    }

    /** Extracts a single entry, or {@code null} if absent. */
    public static byte[] extract(byte[] archive, String entryName) throws IOException {
        byte[][] result = new byte[1][];
        forEachEntry(archive, (name, content) -> {
            if (result[0] == null && name.equals(entryName)) {
                result[0] = content;
            }
        });
        return result[0];
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
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

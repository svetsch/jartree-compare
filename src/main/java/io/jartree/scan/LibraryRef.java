package io.jartree.scan;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * A library found while scanning a tree: a jar (or other archive), an archive nested inside another archive, or an
 * exploded class directory.
 */
public final class LibraryRef {

    public enum Kind { ARCHIVE, NESTED_ARCHIVE, DIRECTORY }

    /** Reads single entries repeatedly, keeping the archive open in between. */
    public interface EntryReader extends AutoCloseable {
        /** The entry content, or null when the archive has no such entry. */
        byte[] read(String name) throws IOException;

        @Override
        void close() throws IOException;
    }

    /** Reads the content of a library without holding the whole archive in memory. */
    public interface ContentSource {
        /** Visits every entry, one at a time. */
        void forEach(ZipUtil.EntryVisitor visitor) throws IOException;

        /** Reads only the named entries. */
        Map<String, byte[]> read(Set<String> names) throws IOException;

        /** Opens the archive for repeated single-entry reads; close it when done. */
        EntryReader reader() throws IOException;
    }

    private final String path;
    private final Kind kind;
    private final String name;
    private final String version;
    private final String extension;
    private final String mavenGa;
    private final String mavenVersion;
    private final String sha256;
    private final long size;
    private final int entryCount;
    private final int classCount;
    private final Set<String> nestedLibraries;
    private final ContentSource source;

    public LibraryRef(String path, Kind kind, String name, String version, String extension, String mavenGa,
                      String mavenVersion, String sha256, long size, int entryCount, int classCount,
                      Set<String> nestedLibraries, ContentSource source) {
        this.path = path;
        this.kind = kind;
        this.name = name;
        this.version = version;
        this.extension = extension;
        this.mavenGa = mavenGa;
        this.mavenVersion = mavenVersion;
        this.sha256 = sha256;
        this.size = size;
        this.entryCount = entryCount;
        this.classCount = classCount;
        this.nestedLibraries = Set.copyOf(nestedLibraries);
        this.source = source;
    }

    /** Path relative to the scanned root; nested archives use {@code outer.war!/WEB-INF/lib/inner.jar}. */
    public String path() {
        return path;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    /** Version from the file name, or from Maven metadata when the file name carries none. May be null. */
    public String version() {
        if (version != null) {
            return version;
        }
        return mavenVersion;
    }

    public String extension() {
        return extension;
    }

    /** {@code groupId:artifactId} from {@code META-INF/maven/.../pom.properties} when unambiguous, otherwise null. */
    public String mavenGa() {
        return mavenGa;
    }

    public String mavenVersion() {
        return mavenVersion;
    }

    public String sha256() {
        return sha256;
    }

    public String shortSha() {
        return sha256.substring(0, 12);
    }

    public long size() {
        return size;
    }

    public int entryCount() {
        return entryCount;
    }

    public int classCount() {
        return classCount;
    }

    public Set<String> nestedLibraries() {
        return nestedLibraries;
    }

    /** Visits every entry except the nested libraries, which are compared on their own. */
    public void forEachEntry(ZipUtil.EntryVisitor visitor) throws IOException {
        source.forEach((name, content) -> {
            if (!nestedLibraries.contains(name)) {
                visitor.visit(name, content);
            }
        });
    }

    /** Reads the named entries; only these are held in memory. */
    public Map<String, byte[]> readEntries(Set<String> names) throws IOException {
        return source.read(names);
    }

    /** Opens this library for repeated single-entry reads. */
    public EntryReader reader() throws IOException {
        return source.reader();
    }

    /** All entries at once; only for small libraries and tests. */
    public Map<String, byte[]> loadEntries() throws IOException {
        Map<String, byte[]> entries = new java.util.TreeMap<>();
        forEachEntry(entries::put);
        return entries;
    }

    /** Directory part of {@link #path()} (including a trailing separator) or empty. */
    public String parentPath() {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? "" : path.substring(0, idx + 1);
    }

    public String fileName() {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /** Key used to pair libraries whose file name differs only by version. */
    public String matchKey() {
        return NameParser.normalizePath(parentPath()) + name + "." + extension;
    }

    @Override
    public String toString() {
        return path;
    }
}

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

    @FunctionalInterface
    public interface ContentLoader {
        /** Loads all entries (path to bytes), excluding directories and nested libraries. */
        Map<String, byte[]> load() throws IOException;
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
    private final ContentLoader loader;

    public LibraryRef(String path, Kind kind, String name, String version, String extension, String mavenGa,
                      String mavenVersion, String sha256, long size, int entryCount, int classCount,
                      Set<String> nestedLibraries, ContentLoader loader) {
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
        this.loader = loader;
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

    public Map<String, byte[]> loadEntries() throws IOException {
        return loader.load();
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

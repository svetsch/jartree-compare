package io.jartree.compare;

import java.util.ArrayList;
import java.util.List;

import io.jartree.scan.LibraryRef;

/** Comparison result for one pair of libraries. */
public final class LibraryDiff {

    /** How the old and the new library were paired. */
    public enum MatchedBy { PATH, NAME, MAVEN_COORDINATES, CONTENT, NONE }

    final LibraryRef oldLib;
    final LibraryRef newLib;
    final MatchedBy matchedBy;
    LibraryStatus status;
    final List<ClassChange> classes = new ArrayList<>();
    final List<ResourceChange> resources = new ArrayList<>();
    int identicalEntries;
    int skippedClasses;
    String error;
    boolean cached;
    final List<String> notes = new ArrayList<>();

    LibraryDiff(LibraryRef oldLib, LibraryRef newLib, MatchedBy matchedBy, LibraryStatus status) {
        this.oldLib = oldLib;
        this.newLib = newLib;
        this.matchedBy = matchedBy;
        this.status = status;
    }

    public LibraryRef oldLib() {
        return oldLib;
    }

    public LibraryRef newLib() {
        return newLib;
    }

    /** The new library if present, otherwise the old one. */
    public LibraryRef primary() {
        return newLib != null ? newLib : oldLib;
    }

    public MatchedBy matchedBy() {
        return matchedBy;
    }

    public LibraryStatus status() {
        return status;
    }

    public List<ClassChange> classes() {
        return classes;
    }

    public List<ResourceChange> resources() {
        return resources;
    }

    public int identicalEntries() {
        return identicalEntries;
    }

    /** Changed classes that were not decompiled because of the per-library limit. */
    public int skippedClasses() {
        return skippedClasses;
    }

    /** True if this result was taken from the {@link ResultCache}. */
    public boolean fromCache() {
        return cached;
    }

    public String error() {
        return error;
    }

    public List<String> notes() {
        return notes;
    }

    public boolean pathChanged() {
        return oldLib != null && newLib != null && !oldLib.path().equals(newLib.path());
    }

    /** "1.0", "1.0 -> 1.1" or empty. */
    public String versionLabel() {
        String o = oldLib == null ? null : oldLib.version();
        String n = newLib == null ? null : newLib.version();
        if (o == null && n == null) {
            return "";
        }
        if (o == null || n == null || o.equals(n)) {
            return o != null ? o : n;
        }
        return o + " -> " + n;
    }

    public long countClasses(ChangeType type) {
        return classes.stream().filter(c -> c.type() == type).count();
    }

    public long countClasses(ClassChange.Nature nature) {
        return classes.stream().filter(c -> c.nature() == nature).count();
    }

    public long countResources(ChangeType type) {
        return resources.stream().filter(r -> r.type() == type).count();
    }

    public long noiseResources() {
        return resources.stream().filter(ResourceChange::noise).count();
    }
}

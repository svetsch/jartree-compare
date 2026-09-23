package io.jartree.compare;

import java.util.List;
import java.util.Set;

/**
 * Options controlling the comparison.
 *
 * @param extensions            archive extensions treated as libraries
 * @param classDirNames         names of exploded class directories treated as libraries
 * @param nestedDepth           how deep to descend into nested archives
 * @param includes              glob patterns on library paths; empty means all
 * @param excludes              glob patterns on library paths
 * @param packages              package prefixes (dotted) limiting class analysis; empty means all
 * @param decompile             decompile changed classes
 * @param decompileAddedRemoved also decompile added and removed classes
 * @param bytecodeDiff          always include a bytecode diff; otherwise it is only a fallback
 * @param maxClassesPerLibrary  maximum number of classes decompiled per library
 * @param contextLines          unified diff context lines
 * @param maxDiffLines          maximum lines per individual diff (0 = unlimited)
 * @param threads               worker threads
 * @param maxSecondsPerMethod   Vineflower time limit per method
 * @param ignoredEntries        glob patterns on entry paths inside libraries (e.g. META-INF/MANIFEST.MF) whose
 *                              differences are ignored
 */
public record CompareOptions(Set<String> extensions, Set<String> classDirNames, int nestedDepth,
                             List<String> includes, List<String> excludes, List<String> packages,
                             boolean decompile, boolean decompileAddedRemoved, boolean bytecodeDiff,
                             int maxClassesPerLibrary, int contextLines, int maxDiffLines, int threads,
                             int maxSecondsPerMethod, List<String> ignoredEntries) {

    public static final Set<String> DEFAULT_EXTENSIONS = Set.of("jar", "war", "ear", "rar", "sar", "aar", "zip");

    public CompareOptions {
        ignoredEntries = ignoredEntries == null ? List.of() : List.copyOf(ignoredEntries);
    }

    /** Options without ignored entries. */
    public CompareOptions(Set<String> extensions, Set<String> classDirNames, int nestedDepth, List<String> includes,
                          List<String> excludes, List<String> packages, boolean decompile,
                          boolean decompileAddedRemoved, boolean bytecodeDiff, int maxClassesPerLibrary,
                          int contextLines, int maxDiffLines, int threads, int maxSecondsPerMethod) {
        this(extensions, classDirNames, nestedDepth, includes, excludes, packages, decompile, decompileAddedRemoved,
                bytecodeDiff, maxClassesPerLibrary, contextLines, maxDiffLines, threads, maxSecondsPerMethod, List.of());
    }

    /**
     * Describes, per option, the settings that influence the result (threads and timeouts do not), so that a
     * user interface can tell which changed options require a new comparison.
     */
    public java.util.Map<String, String> resultSettings() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("Include libraries", String.join(", ", includes));
        m.put("Exclude libraries", String.join(", ", excludes));
        m.put("Ignore entries", String.join(", ", ignoredEntries));
        m.put("Packages", String.join(", ", packages));
        m.put("Archive types", String.join(",", extensions.stream().sorted().toList()));
        m.put("Nested archive depth", String.valueOf(nestedDepth));
        m.put("Decompile changed classes", String.valueOf(decompile));
        m.put("Decompile added / removed classes", String.valueOf(decompileAddedRemoved));
        m.put("Bytecode diff", String.valueOf(bytecodeDiff));
        m.put("Max classes / library", String.valueOf(maxClassesPerLibrary));
        m.put("Diff context lines", String.valueOf(contextLines));
        return m;
    }

    public static CompareOptions defaults() {
        return new CompareOptions(DEFAULT_EXTENSIONS, Set.of("classes"), 8, List.of(), List.of(), List.of(),
                true, false, false, 2000, 3, 5000, Runtime.getRuntime().availableProcessors(), 30);
    }
}

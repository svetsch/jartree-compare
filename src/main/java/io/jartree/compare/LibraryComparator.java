package io.jartree.compare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;

import io.jartree.bytecode.ClassAnalyzer;
import io.jartree.bytecode.ClassAnalyzer.ApiDelta;
import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ClassChange.Nature;
import io.jartree.decompile.Decompiler;
import io.jartree.scan.LibraryRef;

/** Compares the content of two versions of a library entry by entry and decompiles changed classes. */
final class LibraryComparator {

    private final CompareOptions options;
    private final Decompiler decompiler;
    private final ResultCache cache;
    private final Timings timings;
    private final List<Pattern> ignoredEntries;

    /** @param cache optional cache of results and decompiled sources, may be null */
    LibraryComparator(CompareOptions options, Decompiler decompiler, ResultCache cache, Timings timings) {
        this.options = options;
        this.decompiler = decompiler;
        this.cache = cache;
        this.timings = timings;
        this.ignoredEntries = options.ignoredEntries().stream().map(JarTreeComparer::globToRegex).toList();
    }

    /** A class file entry: {@code prefix} is the directory holding the package root (e.g. WEB-INF/classes/). */
    private record ClassEntry(String prefix, String internalName, byte[] bytes) {
        String groupKey() {
            return prefix + outerName(internalName);
        }
    }

    /** One top-level class with its inner classes, on both sides. */
    private static final class Group {
        final String prefix;
        final String outer;
        final Map<String, byte[]> oldFiles = new TreeMap<>();
        final Map<String, byte[]> newFiles = new TreeMap<>();
        ClassChange change;

        Group(String prefix, String outer) {
            this.prefix = prefix;
            this.outer = outer;
        }
    }

    LibraryDiff compare(LibraryMatcher.Pair pair) {
        LibraryRef o = pair.oldLib();
        LibraryRef n = pair.newLib();
        if (o == null) {
            return new LibraryDiff(null, n, pair.matchedBy(), LibraryStatus.ADDED);
        }
        if (n == null) {
            return new LibraryDiff(o, null, pair.matchedBy(), LibraryStatus.REMOVED);
        }
        if (o.sha256().equals(n.sha256())) {
            return new LibraryDiff(o, n, pair.matchedBy(), LibraryStatus.UNCHANGED);
        }
        long libraryStart = System.nanoTime();
        String cacheKey = cache == null ? null : ResultCache.libraryKey(o, n, options);
        if (cacheKey != null) {
            long t = System.nanoTime();
            LibraryDiff cached = cache.getLibrary(cacheKey, o, n, pair.matchedBy());
            timings.since(Timings.Phase.CACHE, t);
            if (cached != null) {
                timings.library(n.path(), (System.nanoTime() - libraryStart) / 1_000_000, true);
                return cached;
            }
        }
        LibraryDiff diff = new LibraryDiff(o, n, pair.matchedBy(), LibraryStatus.CHANGED);
        try {
            long t = System.nanoTime();
            Map<String, byte[]> oldEntries = o.loadEntries();
            Map<String, byte[]> newEntries = n.loadEntries();
            timings.since(Timings.Phase.UNCOMPRESS, t);
            long compareStart = System.nanoTime();
            int ignored = removeIgnored(oldEntries) + removeIgnored(newEntries);
            if (ignored > 0) {
                diff.notes.add(ignored + " entr" + (ignored == 1 ? "y" : "ies") + " ignored by the 'Ignore entries' patterns");
            }
            Map<String, ClassEntry> oldClasses = extractClasses(oldEntries);
            Map<String, ClassEntry> newClasses = extractClasses(newEntries);
            compareResources(diff, oldEntries, newEntries);
            long decompileNanos = compareClasses(diff, oldClasses, newClasses);
            addNestedLibraryNotes(diff);
            // decompilation, diffing and cache access are recorded as their own phases
            timings.since(Timings.Phase.COMPARE, compareStart + decompileNanos);

            boolean significant = diff.classes.stream().anyMatch(ClassChange::isSignificant)
                    || diff.resources.stream().anyMatch(r -> !r.noise());
            if (!significant) {
                diff.status = LibraryStatus.REBUILT;
            }
            if (cacheKey != null) {
                long c = System.nanoTime();
                cache.putLibrary(cacheKey, diff);
                timings.since(Timings.Phase.CACHE, c);
            }
        } catch (Exception | StackOverflowError e) {
            diff.status = LibraryStatus.ERROR;
            diff.error = e.toString();
            if (System.getenv("JARTREE_DEBUG") != null) {
                e.printStackTrace();
            }
        }
        timings.library(n.path(), (System.nanoTime() - libraryStart) / 1_000_000, false);
        return diff;
    }

    /** Removes entries matching the ignore patterns (full entry path, or file name for patterns without '/'). */
    private int removeIgnored(Map<String, byte[]> entries) {
        if (ignoredEntries.isEmpty()) {
            return 0;
        }
        int before = entries.size();
        entries.keySet().removeIf(path -> {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            for (int i = 0; i < ignoredEntries.size(); i++) {
                Pattern p = ignoredEntries.get(i);
                if (p.matcher(path).matches()
                        || !options.ignoredEntries().get(i).contains("/") && p.matcher(fileName).matches()) {
                    return true;
                }
            }
            return false;
        });
        return before - entries.size();
    }

    // ------------------------------------------------------------------ resources

    private void compareResources(LibraryDiff diff, Map<String, byte[]> oldEntries, Map<String, byte[]> newEntries) {
        Set<String> paths = new TreeSet<>(oldEntries.keySet());
        paths.addAll(newEntries.keySet());
        for (String path : paths) {
            // valid class files were already moved out of the maps; anything left is compared as a resource
            byte[] a = oldEntries.get(path);
            byte[] b = newEntries.get(path);
            if (a != null && b != null && Arrays.equals(a, b)) {
                diff.identicalEntries++;
                continue;
            }
            ChangeType type = a == null ? ChangeType.ADDED : b == null ? ChangeType.REMOVED : ChangeType.MODIFIED;
            boolean text = (a == null || TextSupport.isText(a)) && (b == null || TextSupport.isText(b));
            boolean signature = TextSupport.isSignatureFile(path);
            TextSupport.DiffText textDiff = TextSupport.DiffText.NONE;
            boolean noise = signature;
            if (text && !signature) {
                String sa = a == null ? null : TextSupport.decode(a);
                String sb = b == null ? null : TextSupport.decode(b);
                if (type == ChangeType.MODIFIED) {
                    noise = TextSupport.normalizeForNoise(path, sa).equals(TextSupport.normalizeForNoise(path, sb));
                }
                textDiff = TextSupport.unifiedDiff(
                        "a/" + diff.oldLib.path() + "!/" + path, "b/" + diff.newLib.path() + "!/" + path,
                        sa, sb, options.contextLines(), options.maxDiffLines());
            }
            diff.resources.add(new ResourceChange(path, type, text, noise,
                    a == null ? -1 : a.length, b == null ? -1 : b.length, textDiff));
        }
    }

    private static void addNestedLibraryNotes(LibraryDiff diff) {
        Set<String> added = new TreeSet<>(diff.newLib.nestedLibraries());
        added.removeAll(diff.oldLib.nestedLibraries());
        Set<String> removed = new TreeSet<>(diff.oldLib.nestedLibraries());
        removed.removeAll(diff.newLib.nestedLibraries());
        if (!added.isEmpty() || !removed.isEmpty()) {
            diff.notes.add("Nested libraries: " + added.size() + " new file name(s), " + removed.size()
                    + " old file name(s) no longer present (compared separately)");
        }
    }

    // ------------------------------------------------------------------ classes

    private Map<String, ClassEntry> extractClasses(Map<String, byte[]> entries) {
        Map<String, ClassEntry> classes = new LinkedHashMap<>();
        for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!isClassFile(e.getKey())) {
                continue;
            }
            // read key and value before removing: TreeMap may recycle the entry object on removal
            String path = e.getKey();
            byte[] bytes = e.getValue();
            String internalName = className(bytes);
            String expectedSuffix = internalName + ".class";
            if (internalName == null || !path.endsWith(expectedSuffix)) {
                // not a loadable class file (corrupt, or stored under a foreign path); compare it as a resource
                continue;
            }
            it.remove();
            String prefix = path.substring(0, path.length() - expectedSuffix.length());
            classes.put(path, new ClassEntry(prefix, internalName, bytes));
        }
        return classes;
    }

    /** @return nanoseconds spent in decompilation (including its diffs and cache access) */
    private long compareClasses(LibraryDiff diff, Map<String, ClassEntry> oldClasses, Map<String, ClassEntry> newClasses) {
        Map<String, Group> groups = new TreeMap<>();
        // classes outside the package filter are skipped here but stay available as decompiler context
        for (ClassEntry c : oldClasses.values()) {
            if (!acceptedPackage(c.internalName())) {
                continue;
            }
            groups.computeIfAbsent(c.groupKey(), k -> new Group(c.prefix(), outerName(c.internalName())))
                    .oldFiles.put(c.internalName(), c.bytes());
        }
        for (ClassEntry c : newClasses.values()) {
            if (!acceptedPackage(c.internalName())) {
                continue;
            }
            groups.computeIfAbsent(c.groupKey(), k -> new Group(c.prefix(), outerName(c.internalName())))
                    .newFiles.put(c.internalName(), c.bytes());
        }

        List<Group> toDecompile = new ArrayList<>();
        for (Group g : groups.values()) {
            Set<String> names = new TreeSet<>(g.oldFiles.keySet());
            names.addAll(g.newFiles.keySet());
            List<String> changedFiles = new ArrayList<>();
            for (String name : names) {
                if (!Arrays.equals(g.oldFiles.get(name), g.newFiles.get(name))) {
                    changedFiles.add(g.prefix + name + ".class");
                } else {
                    diff.identicalEntries++;
                }
            }
            if (changedFiles.isEmpty()) {
                continue;
            }
            ChangeType type = g.oldFiles.isEmpty() ? ChangeType.ADDED
                    : g.newFiles.isEmpty() ? ChangeType.REMOVED : ChangeType.MODIFIED;
            ClassChange change = new ClassChange(g.prefix, g.outer, type, changedFiles);
            g.change = change;
            diff.classes.add(change);
            change.oldMajor = majorVersion(g, g.oldFiles);
            change.newMajor = majorVersion(g, g.newFiles);

            if (type == ChangeType.MODIFIED) {
                change.api = apiDelta(g);
                change.members = memberChanges(g);
                if (textify(g.oldFiles).equals(textify(g.newFiles))) {
                    change.nature = Nature.DEBUG_INFO_ONLY;
                    continue;
                }
                change.nature = Nature.NOT_DECOMPILED;
            } else {
                change.nature = Nature.PRESENCE;
                if (!options.decompileAddedRemoved()) {
                    continue;
                }
            }
            if (!options.decompile()) {
                if (type == ChangeType.MODIFIED) {
                    change.bytecodeDiff = bytecodeDiff(diff, g);
                }
            } else if (toDecompile.size() < options.maxClassesPerLibrary()) {
                toDecompile.add(g);
            } else {
                diff.skippedClasses++;
            }
        }
        if (diff.skippedClasses > 0) {
            diff.notes.add(diff.skippedClasses + " changed class(es) not decompiled: the limit is "
                    + options.maxClassesPerLibrary() + " per library; set it to at least "
                    + (options.maxClassesPerLibrary() + diff.skippedClasses) + " to decompile all of them");
        }
        if (toDecompile.isEmpty()) {
            return 0;
        }
        long t = System.nanoTime();
        decompileGroups(diff, toDecompile, oldClasses, newClasses);
        return System.nanoTime() - t;
    }

    private void decompileGroups(LibraryDiff diff, List<Group> groups, Map<String, ClassEntry> oldClasses,
                                 Map<String, ClassEntry> newClasses) {
        Map<Group, String> oldSources = new IdentityHashMap<>();
        Map<Group, String> newSources = new IdentityHashMap<>();
        List<String> errors = new ArrayList<>();
        Set<String> prefixes = new TreeSet<>();
        groups.forEach(g -> prefixes.add(g.prefix));
        for (String prefix : prefixes) {
            List<Group> inPrefix = groups.stream().filter(g -> g.prefix.equals(prefix)).toList();
            decompileSide(diff.oldLib.path(), prefix, inPrefix, true, oldClasses, oldSources, errors);
            decompileSide(diff.newLib.path(), prefix, inPrefix, false, newClasses, newSources, errors);
        }

        for (Group g : groups) {
            ClassChange change = g.change;
            String oldSource = oldSources.get(g);
            String newSource = newSources.get(g);
            List<String> problems = new ArrayList<>();
            if (!g.oldFiles.isEmpty() && (oldSource == null || oldSource.contains(Decompiler.FAILURE_MARKER))) {
                problems.add("old version could not be fully decompiled");
            }
            if (!g.newFiles.isEmpty() && (newSource == null || newSource.contains(Decompiler.FAILURE_MARKER))) {
                problems.add("new version could not be fully decompiled");
            }
            String relevantErrors = errors.stream()
                    .filter(e -> e.contains(g.outer) || e.contains(g.outer.replace('/', '.')))
                    .reduce((x, y) -> x + "\n" + y).orElse(null);
            if (!problems.isEmpty()) {
                change.decompileProblem = String.join("; ", problems)
                        + (relevantErrors == null ? "" : "\n" + relevantErrors);
            }

            long diffStart = System.nanoTime();
            String fileName = g.prefix + g.outer + ".java";
            change.sourceDiff = TextSupport.unifiedDiff(
                    "a/" + diff.oldLib.path() + "!/" + fileName, "b/" + diff.newLib.path() + "!/" + fileName,
                    oldSource == null && g.oldFiles.isEmpty() ? null : nullToEmpty(oldSource),
                    newSource == null && g.newFiles.isEmpty() ? null : nullToEmpty(newSource),
                    options.contextLines(), options.maxDiffLines());
            change.members = SourceLocator.locate(change.members, oldSource, newSource);
            timings.since(Timings.Phase.DIFF, diffStart);

            if (change.type() == ChangeType.MODIFIED) {
                change.nature = change.sourceDiff.isEmpty() && change.decompileProblem == null
                        ? Nature.BYTECODE_ONLY : Nature.SOURCE;
                if (options.bytecodeDiff() || change.decompileProblem != null || change.sourceDiff.isEmpty()) {
                    long b = System.nanoTime();
                    change.bytecodeDiff = bytecodeDiff(diff, g);
                    timings.since(Timings.Phase.COMPARE, b);
                }
            }
        }
    }

    /** Decompiles one side of the groups, reusing cached sources where the class files are unchanged. */
    private void decompileSide(String libPath, String prefix, List<Group> groups, boolean oldSide,
                               Map<String, ClassEntry> allClasses, Map<Group, String> out, List<String> errors) {
        List<Group> missing = new ArrayList<>();
        Map<Group, String> keys = new IdentityHashMap<>();
        for (Group g : groups) {
            Map<String, byte[]> files = oldSide ? g.oldFiles : g.newFiles;
            if (files.isEmpty()) {
                continue;
            }
            if (cache != null) {
                long c = System.nanoTime();
                String key = ResultCache.sourceKey(files);
                keys.put(g, key);
                String cached = cache.getSource(key);
                timings.since(Timings.Phase.CACHE, c);
                if (cached != null) {
                    out.put(g, cached);
                    continue;
                }
            }
            missing.add(g);
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, byte[]> sources = new TreeMap<>();
        for (Group g : missing) {
            sources.putAll(oldSide ? g.oldFiles : g.newFiles);
        }
        // context: classes under the same prefix take precedence over classes of other roots
        Map<String, byte[]> context = new HashMap<>();
        for (ClassEntry c : allClasses.values()) {
            if (c.prefix().equals(prefix)) {
                context.put(c.internalName(), c.bytes());
            }
        }
        for (ClassEntry c : allClasses.values()) {
            context.putIfAbsent(c.internalName(), c.bytes());
        }
        Decompiler.Result result = decompiler.decompile(libPath + "!/" + prefix, sources, context);
        errors.addAll(result.errors());
        for (Group g : missing) {
            String source = sourceOf(g, result.sources());
            if (source == null) {
                continue;
            }
            out.put(g, source);
            // failures may be timeouts; only complete output is worth keeping
            if (cache != null && !source.contains(Decompiler.FAILURE_MARKER)) {
                long c = System.nanoTime();
                cache.putSource(keys.get(g), source);
                timings.since(Timings.Phase.CACHE, c);
            }
        }
    }

    private static String sourceOf(Group g, Map<String, String> sources) {
        String s = sources.get(g.outer);
        if (s != null) {
            return s;
        }
        // no outer class file in this group (e.g. only Foo$Bar exists): join what was produced for its members
        String memberPrefix = g.outer + "$";
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(sources).forEach((name, source) -> {
            if (name.startsWith(memberPrefix)) {
                sb.append(source).append('\n');
            }
        });
        return sb.length() == 0 ? null : sb.toString();
    }

    private TextSupport.DiffText bytecodeDiff(LibraryDiff diff, Group g) {
        String fileName = g.prefix + g.outer + ".class.txt";
        return TextSupport.unifiedDiff(
                "a/" + diff.oldLib.path() + "!/" + fileName, "b/" + diff.newLib.path() + "!/" + fileName,
                textify(g.oldFiles), textify(g.newFiles), options.contextLines(), options.maxDiffLines());
    }

    private static String textify(Map<String, byte[]> files) {
        StringBuilder sb = new StringBuilder();
        for (byte[] bytes : files.values()) {
            sb.append(ClassAnalyzer.textify(bytes, false)).append('\n');
        }
        return sb.toString();
    }

    private static ApiDelta apiDelta(Group g) {
        ApiDelta delta = ApiDelta.EMPTY;
        Set<String> names = new TreeSet<>(g.oldFiles.keySet());
        names.addAll(g.newFiles.keySet());
        for (String name : names) {
            byte[] a = g.oldFiles.get(name);
            byte[] b = g.newFiles.get(name);
            String nested = name.equals(g.outer) ? "" : name.substring(g.outer.length() + 1);
            if (a == null) {
                delta = delta.plus(new ApiDelta(List.of(label(nested, "nested class " + nested)), List.of(), List.of()));
            } else if (b == null) {
                delta = delta.plus(new ApiDelta(List.of(), List.of(label(nested, "nested class " + nested)), List.of()));
            } else if (!Arrays.equals(a, b)) {
                ApiDelta d = ClassAnalyzer.apiDelta(ClassAnalyzer.read(a), ClassAnalyzer.read(b));
                if (!nested.isEmpty()) {
                    d = new ApiDelta(prefixed(nested, d.added()), prefixed(nested, d.removed()), prefixed(nested, d.changed()));
                }
                delta = delta.plus(d);
            }
        }
        return delta;
    }

    private static List<MemberChange> memberChanges(Group g) {
        List<MemberChange> members = new ArrayList<>();
        Set<String> names = new TreeSet<>(g.oldFiles.keySet());
        names.addAll(g.newFiles.keySet());
        for (String name : names) {
            byte[] a = g.oldFiles.get(name);
            byte[] b = g.newFiles.get(name);
            String nested = name.equals(g.outer) ? "" : name.substring(g.outer.length() + 1);
            if (a != null && b != null) {
                if (!Arrays.equals(a, b)) {
                    members.addAll(ClassAnalyzer.memberChanges(a, b, nested));
                }
                continue;
            }
            int sep = nested.lastIndexOf('$');
            String owner = sep < 0 ? "" : nested.substring(0, sep);
            String simple = nested.substring(sep + 1);
            members.add(new MemberChange(a == null ? MemberChange.Change.ADDED : MemberChange.Change.REMOVED,
                    MemberChange.Kind.NESTED_CLASS, owner, simple, "class " + simple,
                    a == null ? "added" : "removed", -1));
        }
        return members;
    }

    private static String label(String nested, String text) {
        return nested.isEmpty() ? text : "[" + nested + "] " + text;
    }

    private static List<String> prefixed(String nested, List<String> items) {
        return items.stream().map(i -> "[" + nested + "] " + i).toList();
    }

    private static Integer majorVersion(Group g, Map<String, byte[]> files) {
        byte[] bytes = files.containsKey(g.outer) ? files.get(g.outer)
                : files.values().stream().findFirst().orElse(null);
        return bytes == null || bytes.length < 8 ? null : ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    private boolean acceptedPackage(String internalName) {
        if (options.packages().isEmpty()) {
            return true;
        }
        String dotted = internalName.replace('/', '.');
        return options.packages().stream().anyMatch(p -> dotted.startsWith(p.endsWith(".") ? p : p + "."));
    }

    private static String className(byte[] bytes) {
        try {
            return new ClassReader(bytes).getClassName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isClassFile(String path) {
        return path.endsWith(".class");
    }

    /** {@code com/x/Foo$Bar$1} to {@code com/x/Foo}. */
    static String outerName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        int dollar = internalName.indexOf('$', slash + 2);
        return dollar < 0 ? internalName : internalName.substring(0, dollar);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

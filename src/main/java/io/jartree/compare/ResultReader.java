package io.jartree.compare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.jartree.bytecode.ClassAnalyzer.ApiDelta;
import io.jartree.bytecode.MemberChange;
import io.jartree.report.JsonParser;
import io.jartree.scan.LibraryRef;
import io.jartree.scan.NameParser;

/**
 * Reads a comparison result back from a {@link io.jartree.report.JsonReport} file. Library contents are not
 * available afterwards; everything needed to browse the result (statuses, API deltas, diffs) is.
 */
public final class ResultReader {

    private ResultReader() {
    }

    public static ComparisonResult read(Path file) throws IOException {
        Object parsed;
        try {
            parsed = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> root) || !root.containsKey("libraries")) {
            throw new IOException(file + " is not a jartree-compare JSON report");
        }
        try {
            return fromMap(root);
        } catch (RuntimeException e) {
            throw new IOException(file + ": unexpected report structure (" + e + ")", e);
        }
    }

    /**
     * Reads a report already parsed with {@link JsonParser}, e.g. one embedded in another file.
     *
     * @throws RuntimeException when the structure is not that of a report
     */
    public static ComparisonResult fromMap(Map<?, ?> root) {
        List<LibraryDiff> libraries = new ArrayList<>();
        for (Object o : list(root.get("libraries"))) {
            libraries.add(library(map(o)));
        }
        int oldCount = (int) number(root.get("oldLibraryCount"),
                libraries.stream().filter(l -> l.oldLib() != null).count());
        int newCount = (int) number(root.get("newLibraryCount"),
                libraries.stream().filter(l -> l.newLib() != null).count());
        return new ComparisonResult(Path.of(str(root.get("oldRoot"))), Path.of(str(root.get("newRoot"))),
                oldCount, newCount, libraries, strings(root.get("warnings")),
                root.get("started") == null ? Instant.EPOCH : Instant.parse(str(root.get("started"))),
                Duration.ofMillis(number(root.get("durationMillis"), 0)), limits(root.get("limits")),
                timings(root.get("timings"), number(root.get("durationMillis"), 0)));
    }

    private static LibraryDiff library(Map<?, ?> m) {
        return library(m, ref(m.get("old")), ref(m.get("new")), LibraryDiff.MatchedBy.valueOf(str(m.get("matchedBy"))));
    }

    /** Reads the comparison content of a library, attached to the given (possibly live) library references. */
    static LibraryDiff library(Map<?, ?> m, LibraryRef oldLib, LibraryRef newLib, LibraryDiff.MatchedBy matchedBy) {
        LibraryDiff diff = new LibraryDiff(oldLib, newLib, matchedBy, LibraryStatus.valueOf(str(m.get("status"))));
        diff.error = (String) m.get("error");
        diff.notes.addAll(strings(m.get("notes")));
        diff.identicalEntries = (int) number(m.get("identicalEntries"), 0);
        diff.skippedClasses = (int) number(m.get("skippedClasses"), 0);
        for (Object o : list(m.get("classes"))) {
            diff.classes.add(classChange(map(o)));
        }
        for (Object o : list(m.get("resources"))) {
            Map<?, ?> r = map(o);
            String text = (String) r.get("diff");
            diff.resources.add(new ResourceChange(str(r.get("path")), ChangeType.valueOf(str(r.get("type"))),
                    Boolean.TRUE.equals(r.get("text")), Boolean.TRUE.equals(r.get("noise")),
                    number(r.get("oldSize"), -1), number(r.get("newSize"), -1),
                    diffText(text, r.get("linesAdded"), r.get("linesRemoved"))));
        }
        return diff;
    }

    private static ClassChange classChange(Map<?, ?> m) {
        String prefix = m.containsKey("prefix") ? str(m.get("prefix")) : "";
        String internalName = m.containsKey("internalName") ? str(m.get("internalName"))
                : str(m.get("class")).substring(prefix.length()).replace('.', '/');
        ClassChange c = new ClassChange(prefix, internalName, ChangeType.valueOf(str(m.get("type"))),
                strings(m.get("files")));
        c.nature = ClassChange.Nature.valueOf(str(m.get("nature")));
        c.api = new ApiDelta(strings(m.get("apiAdded")), strings(m.get("apiRemoved")), strings(m.get("apiChanged")));
        c.members = list(m.get("members")).stream().map(o -> member(map(o))).toList();
        c.oldMajor = m.get("oldClassVersion") == null ? null : (int) number(m.get("oldClassVersion"), 0);
        c.newMajor = m.get("newClassVersion") == null ? null : (int) number(m.get("newClassVersion"), 0);
        c.decompileProblem = (String) m.get("decompileProblem");
        c.sourceDiff = diffText((String) m.get("sourceDiff"), m.get("sourceLinesAdded"), m.get("sourceLinesRemoved"));
        c.bytecodeDiff = diffText((String) m.get("bytecodeDiff"), m.get("bytecodeLinesAdded"),
                m.get("bytecodeLinesRemoved"));
        return c;
    }

    private static MemberChange member(Map<?, ?> m) {
        return new MemberChange(MemberChange.Change.valueOf(str(m.get("change"))),
                MemberChange.Kind.valueOf(str(m.get("kind"))), str(m.get("owner")), str(m.get("name")),
                str(m.get("declaration")), str(m.get("detail")), (int) number(m.get("paramCount"), -1),
                integer(m.get("oldLine")), integer(m.get("oldEnd")), integer(m.get("newLine")), integer(m.get("newEnd")));
    }

    private static Limits limits(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Limits.NONE;
        }
        return new Limits((int) number(m.get("maxClasses"), 0), (int) number(m.get("librariesOverClassLimit"), 0),
                (int) number(m.get("skippedClasses"), 0), (int) number(m.get("maxClassesNeeded"), 0),
                (int) number(m.get("nestedDepth"), 0), (int) number(m.get("nestedDepthNeeded"), 0),
                strings(m.get("unopenedArchives")));
    }

    private static Timings.Summary timings(Object o, long elapsed) {
        if (!(o instanceof Map<?, ?> m)) {
            return Timings.Summary.NONE;
        }
        List<Timings.Entry> phases = new ArrayList<>();
        for (Object p : list(m.get("phases"))) {
            Map<?, ?> e = map(p);
            try {
                phases.add(new Timings.Entry(Timings.Phase.valueOf(str(e.get("phase"))), number(e.get("millis"), 0),
                        number(e.get("count"), 0)));
            } catch (IllegalArgumentException unknownPhase) {
                // written by a newer version
            }
        }
        List<Timings.LibraryTime> slowest = new ArrayList<>();
        for (Object l : list(m.get("slowestLibraries"))) {
            Map<?, ?> e = map(l);
            slowest.add(new Timings.LibraryTime(str(e.get("library")), number(e.get("millis"), 0),
                    Boolean.TRUE.equals(e.get("cached"))));
        }
        return new Timings.Summary(phases, elapsed, slowest);
    }

    private static Integer integer(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static LibraryRef ref(Object o) {
        if (o == null) {
            return null;
        }
        Map<?, ?> m = map(o);
        String path = str(m.get("path"));
        String extension = m.get("extension") != null ? str(m.get("extension"))
                : NameParser.parse(path.substring(path.lastIndexOf('/') + 1)).extension();
        LibraryRef.ContentSource unavailable = new LibraryRef.ContentSource() {
            @Override
            public void forEach(io.jartree.scan.ZipUtil.EntryVisitor visitor) throws IOException {
                throw new IOException("Content of " + path + " is not available in a loaded report");
            }

            @Override
            public Map<String, byte[]> read(Set<String> names) throws IOException {
                throw new IOException("Content of " + path + " is not available in a loaded report");
            }

            @Override
            public LibraryRef.EntryReader reader() throws IOException {
                throw new IOException("Content of " + path + " is not available in a loaded report");
            }
        };
        return new LibraryRef(path, LibraryRef.Kind.valueOf(str(m.get("kind"))), str(m.get("name")),
                (String) m.get("version"), extension, (String) m.get("maven"), (String) m.get("mavenVersion"),
                str(m.get("sha256")), number(m.get("size"), 0), (int) number(m.get("entries"), 0),
                (int) number(m.get("classes"), 0), Set.of(), unavailable);
    }

    /** Uses the stored line counts, or counts +/- lines when the report predates them. */
    private static TextSupport.DiffText diffText(String text, Object added, Object removed) {
        if (text == null || text.isEmpty()) {
            return TextSupport.DiffText.NONE;
        }
        int a = 0;
        int r = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                a++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                r++;
            }
        }
        return new TextSupport.DiffText(text, (int) number(added, a), (int) number(removed, r));
    }

    private static Map<?, ?> map(Object o) {
        return (Map<?, ?>) o;
    }

    private static List<?> list(Object o) {
        return o == null ? List.of() : (List<?>) o;
    }

    private static List<String> strings(Object o) {
        return list(o).stream().map(String::valueOf).toList();
    }

    private static String str(Object o) {
        if (o == null) {
            throw new IllegalArgumentException("missing value");
        }
        return o.toString();
    }

    private static long number(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }
}

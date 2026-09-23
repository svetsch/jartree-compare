package io.jartree.report;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import io.jartree.bytecode.MemberChange;
import io.jartree.compare.ClassChange;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.Limits;
import io.jartree.compare.ResourceChange;
import io.jartree.scan.LibraryRef;

/** Machine readable report; can be read back with {@link io.jartree.compare.ResultReader}. */
public final class JsonReport {

    private final boolean includeDiffs;

    public JsonReport(boolean includeDiffs) {
        this.includeDiffs = includeDiffs;
    }

    public void write(ComparisonResult result, Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "jartree-compare/1");
        root.put("oldRoot", result.oldRoot().toString());
        root.put("newRoot", result.newRoot().toString());
        root.put("started", result.started().toString());
        root.put("durationMillis", result.duration().toMillis());
        root.put("oldLibraryCount", result.oldLibraryCount());
        root.put("newLibraryCount", result.newLibraryCount());
        Map<String, Object> counts = new LinkedHashMap<>();
        result.countsByStatus().forEach((k, v) -> counts.put(k.name(), v));
        root.put("summary", counts);
        root.put("warnings", result.warnings());
        Limits l = result.limits();
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxClasses", l.maxClasses());
        limits.put("librariesOverClassLimit", l.librariesOverClassLimit());
        limits.put("skippedClasses", l.skippedClasses());
        limits.put("maxClassesNeeded", l.maxClassesNeeded());
        limits.put("nestedDepth", l.nestedDepth());
        limits.put("nestedDepthNeeded", l.nestedDepthNeeded());
        limits.put("unopenedArchives", l.unopenedArchives());
        root.put("limits", limits);
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("phases", result.timings().phases().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", e.phase().name());
            m.put("label", e.phase().label());
            m.put("millis", e.millis());
            m.put("count", e.count());
            return m;
        }).toList());
        timings.put("slowestLibraries", result.timings().slowestLibraries().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("library", t.library());
            m.put("millis", t.millis());
            m.put("cached", t.cached());
            return m;
        }).toList());
        root.put("timings", timings);
        root.put("libraries", result.libraries().stream().map(this::library).toList());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeValue(w, root, "");
            w.write('\n');
        }
    }

    /** Serializes a single library diff including all diffs (used by the result cache). */
    public static String toJson(LibraryDiff lib) {
        StringWriter w = new StringWriter();
        try {
            writeValue(w, new JsonReport(true).library(lib), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return w.toString();
    }

    private Map<String, Object> library(LibraryDiff lib) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", lib.status().name());
        m.put("path", lib.primary().path());
        m.put("matchedBy", lib.matchedBy().name());
        m.put("old", ref(lib.oldLib()));
        m.put("new", ref(lib.newLib()));
        if (lib.error() != null) {
            m.put("error", lib.error());
        }
        if (!lib.notes().isEmpty()) {
            m.put("notes", lib.notes());
        }
        m.put("identicalEntries", lib.identicalEntries());
        m.put("skippedClasses", lib.skippedClasses());
        m.put("classes", lib.classes().stream().map(this::classChange).toList());
        m.put("resources", lib.resources().stream().map(this::resource).toList());
        return m;
    }

    private static Map<String, Object> ref(LibraryRef ref) {
        if (ref == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", ref.path());
        m.put("kind", ref.kind().name());
        m.put("name", ref.name());
        m.put("version", ref.version());
        m.put("extension", ref.extension());
        m.put("maven", ref.mavenGa());
        m.put("mavenVersion", ref.mavenVersion());
        m.put("sha256", ref.sha256());
        m.put("size", ref.size());
        m.put("entries", ref.entryCount());
        m.put("classes", ref.classCount());
        return m;
    }

    private Map<String, Object> classChange(ClassChange cc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", cc.displayName());
        m.put("prefix", cc.entryPrefix());
        m.put("internalName", cc.internalName());
        m.put("type", cc.type().name());
        m.put("nature", cc.nature().name());
        m.put("files", cc.changedFiles());
        m.put("oldClassVersion", cc.oldMajor());
        m.put("newClassVersion", cc.newMajor());
        m.put("apiAdded", cc.api().added());
        m.put("apiRemoved", cc.api().removed());
        m.put("apiChanged", cc.api().changed());
        m.put("members", cc.members().stream().map(JsonReport::member).toList());
        m.put("sourceLinesAdded", cc.sourceDiff().added());
        m.put("sourceLinesRemoved", cc.sourceDiff().removed());
        m.put("bytecodeLinesAdded", cc.bytecodeDiff().added());
        m.put("bytecodeLinesRemoved", cc.bytecodeDiff().removed());
        if (cc.decompileProblem() != null) {
            m.put("decompileProblem", cc.decompileProblem());
        }
        if (includeDiffs) {
            m.put("sourceDiff", cc.sourceDiff().text());
            m.put("bytecodeDiff", cc.bytecodeDiff().text());
        }
        return m;
    }

    private static Map<String, Object> member(MemberChange mc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change", mc.change().name());
        m.put("kind", mc.kind().name());
        m.put("owner", mc.owner());
        m.put("name", mc.name());
        m.put("declaration", mc.declaration());
        m.put("detail", mc.detail());
        m.put("paramCount", mc.paramCount());
        m.put("oldLine", mc.oldLine());
        m.put("oldEnd", mc.oldEnd());
        m.put("newLine", mc.newLine());
        m.put("newEnd", mc.newEnd());
        return m;
    }

    private Map<String, Object> resource(ResourceChange rc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rc.path());
        m.put("type", rc.type().name());
        m.put("text", rc.text());
        m.put("noise", rc.noise());
        m.put("oldSize", rc.oldSize());
        m.put("newSize", rc.newSize());
        m.put("linesAdded", rc.diff().added());
        m.put("linesRemoved", rc.diff().removed());
        if (includeDiffs && rc.text()) {
            m.put("diff", rc.diff().text());
        }
        return m;
    }

    private static void writeValue(Writer w, Object value, String indent) throws IOException {
        if (value == null) {
            w.write("null");
        } else if (value instanceof String s) {
            writeString(w, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            w.write(value.toString());
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                w.write("{}");
                return;
            }
            String inner = indent + "  ";
            w.write("{\n");
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                w.write(inner);
                writeString(w, String.valueOf(e.getKey()));
                w.write(": ");
                writeValue(w, e.getValue(), inner);
                w.write(it.hasNext() ? ",\n" : "\n");
            }
            w.write(indent + "}");
        } else if (value instanceof Collection<?> list) {
            if (list.isEmpty()) {
                w.write("[]");
                return;
            }
            String inner = indent + "  ";
            w.write("[\n");
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                w.write(inner);
                writeValue(w, it.next(), inner);
                w.write(it.hasNext() ? ",\n" : "\n");
            }
            w.write(indent + "]");
        } else {
            writeString(w, value.toString());
        }
    }

    private static void writeString(Writer w, String s) throws IOException {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        w.write(sb.append('"').toString());
    }
}

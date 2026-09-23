package io.jartree.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.jartree.compare.ComparisonResult;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.ResultReader;
import io.jartree.report.JsonParser;
import io.jartree.report.JsonReport;

/**
 * Everything a comparison tab holds, stored as a JSON file: the paths, all options, the filters and, when there
 * is one, the result together with the settings that produced it. Reopening the file restores the tab as it was.
 *
 * @param oldPath         old directory or archive, as typed
 * @param newPath         new directory or archive, as typed
 * @param options         the options, as written by {@link OptionsPane#toMap()}
 * @param statuses        library statuses shown by the filter
 * @param search          filter text
 * @param showNoise       whether build noise is shown
 * @param hiddenColumns   ids of the hidden result columns; null when the file does not say (older files)
 * @param appliedSettings paths and result-relevant options of {@code result}; null without a result
 * @param result          the displayed result; null when the comparison has not been run
 */
record ComparisonFile(String oldPath, String newPath, Map<String, Object> options, Set<LibraryStatus> statuses,
                      String search, boolean showNoise, List<String> hiddenColumns,
                      Map<String, String> appliedSettings,
                      ComparisonResult result) {

    static final String FORMAT = "jartree-compare-comparison/1";
    static final String EXTENSION = "jtcompare";

    void write(Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("oldPath", oldPath);
        root.put("newPath", newPath);
        root.put("options", options);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("statuses", statuses.stream().map(Enum::name).toList());
        filter.put("search", search);
        filter.put("showNoise", showNoise);
        root.put("filter", filter);
        if (hiddenColumns != null) {
            root.put("view", Map.of("hiddenColumns", hiddenColumns));
        }
        if (result != null) {
            root.put("appliedSettings", appliedSettings);
            root.put("result", new JsonReport(true).toMap(result));
        }
        JsonReport.writeJson(root, file);
    }

    static ComparisonFile read(Path file) throws IOException {
        Object parsed;
        try {
            parsed = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> root) || !FORMAT.equals(root.get("format"))) {
            throw new IOException(file + " is not a jartree-compare comparison file");
        }
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            if (root.get("options") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> options.put(String.valueOf(k), v));
            }
            Map<?, ?> filter = root.get("filter") instanceof Map<?, ?> m ? m : Map.of();
            Set<LibraryStatus> statuses = EnumSet.noneOf(LibraryStatus.class);
            if (filter.get("statuses") instanceof List<?> names) {
                for (Object name : names) {
                    try {
                        statuses.add(LibraryStatus.valueOf(String.valueOf(name)));
                    } catch (IllegalArgumentException e) {
                        // a status of a newer version; ignore it
                    }
                }
            } else {
                statuses.addAll(EnumSet.complementOf(EnumSet.of(LibraryStatus.UNCHANGED)));
            }
            List<String> hiddenColumns = null;
            if (root.get("view") instanceof Map<?, ?> view && view.get("hiddenColumns") instanceof List<?> ids) {
                hiddenColumns = ids.stream().map(String::valueOf).toList();
            }
            ComparisonResult result = null;
            Map<String, String> applied = null;
            if (root.get("result") instanceof Map<?, ?> r) {
                result = ResultReader.fromMap(r);
                applied = new LinkedHashMap<>();
                if (root.get("appliedSettings") instanceof Map<?, ?> a) {
                    for (Map.Entry<?, ?> e : a.entrySet()) {
                        applied.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
            return new ComparisonFile(text(root.get("oldPath")), text(root.get("newPath")), options, statuses,
                    text(filter.get("search")), Boolean.TRUE.equals(filter.get("showNoise")), hiddenColumns, applied,
                    result);
        } catch (RuntimeException e) {
            throw new IOException(file + ": unexpected comparison file structure (" + e + ")", e);
        }
    }

    /** Whether the file looks like a comparison file rather than a JSON report, judged by its name. */
    static boolean isComparisonFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + EXTENSION);
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }
}

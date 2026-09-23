package io.jartree.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses unified diff text into display lines and side-by-side rows. */
final class DiffModel {

    enum Kind { HEADER, HUNK, CONTEXT, ADDED, REMOVED, NOTE, EMPTY }

    /**
     * One line of a unified diff.
     *
     * @param oldNo   line number in the old file (null when the line does not exist there)
     * @param newNo   line number in the new file
     * @param oldPos  position of the line relative to the old file (for added lines: the next old line)
     * @param newPos  position relative to the new file (for removed lines: the next new line)
     * @param hlStart start of the changed part of the text (-1 if the whole line is shown plainly)
     * @param hlEnd   end (exclusive) of the changed part
     */
    record Line(Kind kind, Integer oldNo, Integer newNo, String text, int oldPos, int newPos, int hlStart,
                int hlEnd) {

        Line withHighlight(int start, int end) {
            return new Line(kind, oldNo, newNo, text, oldPos, newPos, start, end);
        }

        boolean isChange() {
            return kind == Kind.ADDED || kind == Kind.REMOVED;
        }
    }

    /** One row of a side-by-side view; each side is a line or null (empty cell). */
    record Row(Line leftLine, Line rightLine, Kind leftKind, Kind rightKind) {

        String left() {
            return leftLine == null ? "" : leftLine.text();
        }

        String right() {
            return rightLine == null ? "" : rightLine.text();
        }

        Integer leftNo() {
            return leftLine == null ? null : leftLine.oldNo();
        }

        Integer rightNo() {
            return rightLine == null ? null : rightLine.newNo();
        }

        int oldPos() {
            return leftLine != null ? leftLine.oldPos() : rightLine.oldPos();
        }

        int newPos() {
            return rightLine != null ? rightLine.newPos() : leftLine.newPos();
        }

        boolean isChange() {
            return leftKind == Kind.REMOVED || rightKind == Kind.ADDED;
        }
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private DiffModel() {
    }

    static List<Line> parse(String diff) {
        List<Line> lines = new ArrayList<>();
        if (diff == null || diff.isEmpty()) {
            return lines;
        }
        int oldNo = 0;
        int newNo = 0;
        for (String raw : diff.split("\n", -1)) {
            Matcher m = HUNK.matcher(raw);
            if (raw.startsWith("--- ") || raw.startsWith("+++ ")) {
                lines.add(new Line(Kind.HEADER, null, null, raw, oldNo, newNo, -1, -1));
            } else if (m.matches()) {
                oldNo = Integer.parseInt(m.group(1));
                newNo = Integer.parseInt(m.group(2));
                lines.add(new Line(Kind.HUNK, null, null, raw, oldNo, newNo, -1, -1));
            } else if (raw.startsWith("+")) {
                lines.add(new Line(Kind.ADDED, null, newNo, raw.substring(1), oldNo, newNo, -1, -1));
                newNo++;
            } else if (raw.startsWith("-")) {
                lines.add(new Line(Kind.REMOVED, oldNo, null, raw.substring(1), oldNo, newNo, -1, -1));
                oldNo++;
            } else if (raw.startsWith(" ")) {
                lines.add(new Line(Kind.CONTEXT, oldNo, newNo, raw.substring(1), oldNo, newNo, -1, -1));
                oldNo++;
                newNo++;
            } else if (!raw.isEmpty()) {
                // e.g. "... diff truncated" or "\ No newline at end of file"
                lines.add(new Line(Kind.NOTE, null, null, raw, oldNo, newNo, -1, -1));
            }
        }
        highlightChanges(lines);
        return lines;
    }

    /** Marks the differing part of removed/added line pairs, so that small edits stand out. */
    private static void highlightChanges(List<Line> lines) {
        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).kind() != Kind.REMOVED) {
                i++;
                continue;
            }
            int r0 = i;
            while (i < lines.size() && lines.get(i).kind() == Kind.REMOVED) {
                i++;
            }
            int a0 = i;
            while (i < lines.size() && lines.get(i).kind() == Kind.ADDED) {
                i++;
            }
            int pairs = Math.min(a0 - r0, i - a0);
            for (int k = 0; k < pairs; k++) {
                Line removed = lines.get(r0 + k);
                Line added = lines.get(a0 + k);
                int[] range = changedRange(removed.text(), added.text());
                if (range != null) {
                    lines.set(r0 + k, removed.withHighlight(range[0], range[1]));
                    lines.set(a0 + k, added.withHighlight(range[0], range[2]));
                }
            }
        }
    }

    /**
     * Returns {start, oldEnd, newEnd} of the part that differs between two similar lines, widened to whole
     * identifiers, or null when the lines are too different for a highlight to help.
     */
    static int[] changedRange(String a, String b) {
        int max = Math.max(a.length(), b.length());
        int prefix = 0;
        while (prefix < a.length() && prefix < b.length() && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length() - prefix && suffix < b.length() - prefix
                && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) {
            suffix++;
        }
        int indent = a.length() - a.stripLeading().length();
        if (max == 0 || prefix + suffix < 0.4 * max || prefix + suffix <= indent) {
            return null;
        }
        // widen to whole identifiers when the cut falls inside one
        while (prefix > 0 && isWordChar(a.charAt(prefix - 1))
                && (prefix < a.length() && isWordChar(a.charAt(prefix)) || prefix < b.length() && isWordChar(b.charAt(prefix)))) {
            prefix--;
        }
        int endA = a.length() - suffix;
        int endB = b.length() - suffix;
        while (endA < a.length() && isWordChar(a.charAt(endA))
                && (endA > prefix && isWordChar(a.charAt(endA - 1)) || endB > prefix && isWordChar(b.charAt(endB - 1)))) {
            endA++;
            endB++;
        }
        return new int[] {prefix, Math.max(prefix, endA), Math.max(prefix, endB)};
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Pairs removed and added blocks of each change next to each other. */
    static List<Row> sideBySide(List<Line> lines) {
        List<Row> rows = new ArrayList<>();
        List<Line> removed = new ArrayList<>();
        List<Line> added = new ArrayList<>();
        for (Line line : lines) {
            switch (line.kind()) {
                case REMOVED -> {
                    if (!added.isEmpty()) {
                        flush(rows, removed, added);
                    }
                    removed.add(line);
                }
                case ADDED -> added.add(line);
                default -> {
                    flush(rows, removed, added);
                    if (line.kind() != Kind.HEADER) {
                        rows.add(new Row(line, line, line.kind(), line.kind()));
                    }
                }
            }
        }
        flush(rows, removed, added);
        return rows;
    }

    private static void flush(List<Row> rows, List<Line> removed, List<Line> added) {
        int n = Math.max(removed.size(), added.size());
        for (int i = 0; i < n; i++) {
            Line l = i < removed.size() ? removed.get(i) : null;
            Line r = i < added.size() ? added.get(i) : null;
            rows.add(new Row(l, r, l == null ? Kind.EMPTY : Kind.REMOVED, r == null ? Kind.EMPTY : Kind.ADDED));
        }
        removed.clear();
        added.clear();
    }
}

package io.jartree.report;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.jartree.bytecode.ClassAnalyzer;
import io.jartree.compare.ChangeType;
import io.jartree.compare.ClassChange;
import io.jartree.compare.ComparisonResult;
import io.jartree.compare.LibraryDiff;
import io.jartree.compare.LibraryStatus;
import io.jartree.compare.ResourceChange;

/** Human readable plain text report. */
public final class ConsoleReport {

    private static final String ESC = String.valueOf((char) 27);
    private static final String RESET = ESC + "[0m";
    private static final String RED = ESC + "[31m";
    private static final String GREEN = ESC + "[32m";
    private static final String YELLOW = ESC + "[33m";
    private static final String BLUE = ESC + "[34m";
    private static final String CYAN = ESC + "[36m";
    private static final String DIM = ESC + "[2m";
    private static final String BOLD = ESC + "[1m";

    private final PrintStream out;
    private final boolean color;
    private final boolean showDiffs;
    private final boolean showUnchanged;
    private final boolean showNoise;

    public ConsoleReport(PrintStream out, boolean color, boolean showDiffs, boolean showUnchanged, boolean showNoise) {
        this.out = out;
        this.color = color;
        this.showDiffs = showDiffs;
        this.showUnchanged = showUnchanged;
        this.showNoise = showNoise;
    }

    public void print(ComparisonResult result) {
        out.println();
        out.println(c(BOLD, "Old: ") + result.oldRoot() + c(DIM, "  (" + result.oldLibraryCount() + " libraries)"));
        out.println(c(BOLD, "New: ") + result.newRoot() + c(DIM, "  (" + result.newLibraryCount() + " libraries)"));
        out.println();

        for (LibraryDiff lib : result.libraries()) {
            if (!showUnchanged && lib.status() == LibraryStatus.UNCHANGED) {
                continue;
            }
            printLibrary(lib);
        }

        if (!result.warnings().isEmpty()) {
            out.println(c(YELLOW, "Warnings:"));
            result.warnings().forEach(w -> out.println("  " + w));
            out.println();
        }

        printSummary(result);
    }

    /** Prints only the one-line summary, without colors. */
    public static void summaryLine(PrintStream out, ComparisonResult result) {
        new ConsoleReport(out, false, false, false, false).printSummary(result);
    }

    private void printSummary(ComparisonResult result) {
        Map<LibraryStatus, Integer> counts = result.countsByStatus();
        List<String> parts = new ArrayList<>();
        for (LibraryStatus s : LibraryStatus.values()) {
            if (counts.get(s) > 0 || s == LibraryStatus.CHANGED) {
                parts.add(c(statusColor(s), counts.get(s) + " " + s.name().toLowerCase()));
            }
        }
        out.println(c(BOLD, "Summary: ") + String.join(", ", parts)
                + c(DIM, "  (" + result.duration().toMillis() + " ms)"));
    }

    private void printLibrary(LibraryDiff lib) {
        StringBuilder line = new StringBuilder();
        line.append(c(statusColor(lib.status()), pad(lib.status().name(), 11)));
        if (lib.pathChanged()) {
            line.append(lib.oldLib().path()).append(c(DIM, " -> ")).append(lib.newLib().path());
        } else {
            line.append(lib.primary().path());
        }
        String version = lib.versionLabel();
        if (!version.isEmpty()) {
            line.append(c(CYAN, "  [" + version + "]"));
        }
        out.println(line);

        String indent = " ".repeat(11);
        if (lib.error() != null) {
            out.println(indent + c(RED, "error: " + lib.error()));
        }
        if (lib.status() == LibraryStatus.CHANGED || lib.status() == LibraryStatus.REBUILT) {
            out.println(indent + c(DIM, summary(lib)));
            lib.notes().forEach(n -> out.println(indent + c(DIM, n)));
            for (ClassChange cc : lib.classes()) {
                if (!showNoise && !cc.isSignificant()) {
                    continue;
                }
                printClass(indent + "  ", cc);
            }
            for (ResourceChange rc : lib.resources()) {
                if (!showNoise && rc.noise()) {
                    continue;
                }
                printResource(indent + "  ", rc);
            }
        }
        out.println();
    }

    private void printClass(String indent, ClassChange cc) {
        StringBuilder sb = new StringBuilder(indent);
        sb.append(c(typeColor(cc.type()), cc.type().symbol() + " " + cc.displayName()));
        List<String> info = new ArrayList<>();
        switch (cc.nature()) {
            case DEBUG_INFO_ONLY -> info.add("debug info only");
            case BYTECODE_ONLY -> info.add("bytecode only, same decompiled source");
            case NOT_DECOMPILED -> info.add("not decompiled");
            default -> {
            }
        }
        if (!cc.api().isEmpty()) {
            info.add("API +" + cc.api().added().size() + " -" + cc.api().removed().size() + " ~" + cc.api().changed().size());
        }
        if (!cc.sourceDiff().isEmpty() && cc.type() == ChangeType.MODIFIED) {
            info.add("source +" + cc.sourceDiff().added() + " -" + cc.sourceDiff().removed() + " lines");
        }
        if (cc.oldMajor() != null && cc.newMajor() != null && !cc.oldMajor().equals(cc.newMajor())) {
            info.add(ClassAnalyzer.javaRelease(cc.oldMajor()) + " -> " + ClassAnalyzer.javaRelease(cc.newMajor()));
        }
        if (cc.decompileProblem() != null) {
            info.add("decompilation incomplete");
        }
        if (!info.isEmpty()) {
            sb.append(c(DIM, "  (" + String.join(", ", info) + ")"));
        }
        out.println(sb);
        if (showDiffs) {
            String inner = indent + "    ";
            cc.api().removed().forEach(m -> out.println(inner + c(RED, "- " + m)));
            cc.api().added().forEach(m -> out.println(inner + c(GREEN, "+ " + m)));
            cc.api().changed().forEach(m -> out.println(inner + c(YELLOW, "~ " + m)));
            if (cc.decompileProblem() != null) {
                cc.decompileProblem().lines().forEach(l -> out.println(inner + c(YELLOW, l)));
            }
            printDiff(cc.sourceDiff().text());
            printDiff(cc.bytecodeDiff().text());
        }
    }

    private void printResource(String indent, ResourceChange rc) {
        StringBuilder sb = new StringBuilder(indent);
        sb.append(c(typeColor(rc.type()), rc.type().symbol() + " " + rc.path()));
        List<String> info = new ArrayList<>();
        if (rc.noise()) {
            info.add("build noise");
        }
        if (!rc.text()) {
            info.add("binary " + size(rc.oldSize()) + " -> " + size(rc.newSize()));
        } else if (!rc.diff().isEmpty()) {
            info.add("+" + rc.diff().added() + " -" + rc.diff().removed() + " lines");
        }
        if (!info.isEmpty()) {
            sb.append(c(DIM, "  (" + String.join(", ", info) + ")"));
        }
        out.println(sb);
        if (showDiffs && rc.type() == ChangeType.MODIFIED) {
            printDiff(rc.diff().text());
        }
    }

    private void printDiff(String diff) {
        if (diff == null || diff.isEmpty()) {
            return;
        }
        for (String line : diff.split("\n")) {
            String col;
            if (line.startsWith("+++") || line.startsWith("---")) {
                col = BOLD;
            } else if (line.startsWith("@@")) {
                col = BLUE;
            } else if (line.startsWith("+")) {
                col = GREEN;
            } else if (line.startsWith("-")) {
                col = RED;
            } else {
                col = null;
            }
            out.println(col == null ? line : c(col, line));
        }
        out.println();
    }

    public static String summary(LibraryDiff lib) {
        List<String> parts = new ArrayList<>();
        long added = lib.countClasses(ChangeType.ADDED);
        long removed = lib.countClasses(ChangeType.REMOVED);
        long modified = lib.countClasses(ChangeType.MODIFIED);
        List<String> insignificant = new ArrayList<>();
        long debugOnly = lib.countClasses(ClassChange.Nature.DEBUG_INFO_ONLY);
        long bytecodeOnly = lib.countClasses(ClassChange.Nature.BYTECODE_ONLY);
        if (debugOnly > 0) {
            insignificant.add(debugOnly + " debug-info only");
        }
        if (bytecodeOnly > 0) {
            insignificant.add(bytecodeOnly + " with identical source");
        }
        parts.add("classes: " + modified + " modified"
                + (insignificant.isEmpty() ? "" : " (of which " + String.join(", ", insignificant) + ")")
                + ", " + added + " added, " + removed + " removed");
        parts.add("resources: " + lib.countResources(ChangeType.MODIFIED) + " modified, "
                + lib.countResources(ChangeType.ADDED) + " added, " + lib.countResources(ChangeType.REMOVED)
                + " removed" + (lib.noiseResources() > 0 ? " (" + lib.noiseResources() + " build noise)" : ""));
        parts.add(lib.identicalEntries() + " identical entries");
        return String.join("; ", parts);
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }

    private String c(String code, String text) {
        return color ? code + text + RESET : text;
    }

    private static String statusColor(LibraryStatus s) {
        return switch (s) {
            case ADDED -> GREEN;
            case REMOVED -> RED;
            case CHANGED -> YELLOW;
            case REBUILT -> CYAN;
            case UNCHANGED -> DIM;
            case ERROR -> RED + BOLD;
        };
    }

    private static String typeColor(ChangeType t) {
        return switch (t) {
            case ADDED -> GREEN;
            case REMOVED -> RED;
            case MODIFIED -> YELLOW;
        };
    }
}

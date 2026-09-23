package io.jartree.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.jartree.bytecode.MemberChange;

/**
 * Finds the line range of fields, methods and constructors in decompiled source, so that a member change can be
 * shown at its place in the source diff. Relies on the regular formatting of Vineflower output.
 */
final class SourceLocator {

    private static final String MODIFIERS = "(?:(?:public|protected|private|static|final|abstract|synchronized|native"
            + "|default|strictfp|transient|volatile|sealed|non-sealed)\\s+)*";
    private static final String TYPE = "[\\w.$\\[\\]]+(?:<.*>)?(?:\\[\\])*";
    private static final Pattern CLASS_DECL = Pattern.compile(
            "^(\\s*)(?:@\\S+\\s+)*" + MODIFIERS + "(?:class|interface|enum|record|@interface)\\s+([\\w$]+).*");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^(\\s+)(?:@\\S+\\s+)*" + MODIFIERS + "(?:<[^>]*>\\s+)?(?:(" + TYPE + ")\\s+)?([\\w$]+)\\s*\\((.*)$");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "^(\\s+)(?:@\\S+\\s+)*" + MODIFIERS + "(" + TYPE + ")\\s+([\\w$]+)\\s*(?:=.*)?;\\s*$");
    private static final Pattern STATIC_INIT = Pattern.compile("^(\\s+)static \\{\\s*$");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "return", "new",
            "synchronized", "else", "try", "do", "throw", "super", "this", "assert", "case", "yield");

    private record Decl(int line, int indent, MemberChange.Kind kind, String name, int params) {
    }

    private final List<Decl> decls = new ArrayList<>();
    private final String[] lines;

    private SourceLocator(String source) {
        lines = source.split("\n", -1);
        Set<Integer> memberIndents = new TreeSet<>();
        for (String line : lines) {
            Matcher c = CLASS_DECL.matcher(line);
            if (c.matches()) {
                memberIndents.add(c.group(1).length() + 4);
            }
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            Matcher s = STATIC_INIT.matcher(line);
            if (s.matches() && memberIndents.contains(s.group(1).length())) {
                decls.add(new Decl(i + 1, s.group(1).length(), MemberChange.Kind.INITIALIZER, "static", -1));
                continue;
            }
            Matcher c = CLASS_DECL.matcher(line);
            if (c.matches()) {
                decls.add(new Decl(i + 1, c.group(1).length(), MemberChange.Kind.NESTED_CLASS, c.group(2), -1));
                continue;
            }
            Matcher m = METHOD_DECL.matcher(line);
            if (m.matches() && memberIndents.contains(m.group(1).length()) && !KEYWORDS.contains(m.group(3))
                    && (m.group(2) == null || !KEYWORDS.contains(m.group(2))) && !trimmed.contains("->")
                    && (trimmed.endsWith("{") || m.group(2) != null && (trimmed.endsWith(";") || !trimmed.contains(")")))) {
                decls.add(new Decl(i + 1, m.group(1).length(), MemberChange.Kind.METHOD, m.group(3),
                        countParams(m.group(4))));
                continue;
            }
            Matcher f = FIELD_DECL.matcher(line);
            if (f.matches() && memberIndents.contains(f.group(1).length()) && !KEYWORDS.contains(f.group(2))) {
                decls.add(new Decl(i + 1, f.group(1).length(), MemberChange.Kind.FIELD, f.group(3), -1));
            }
        }
    }

    /** Adds old and new source locations to the given member changes. */
    static List<MemberChange> locate(List<MemberChange> members, String oldSource, String newSource) {
        if (members.isEmpty()) {
            return members;
        }
        SourceLocator oldLoc = oldSource == null ? null : new SourceLocator(oldSource);
        SourceLocator newLoc = newSource == null ? null : new SourceLocator(newSource);
        List<MemberChange> result = new ArrayList<>(members.size());
        for (MemberChange m : members) {
            MemberChange located = m;
            if (oldLoc != null && m.change() != MemberChange.Change.ADDED) {
                int[] r = oldLoc.find(m);
                if (r != null) {
                    located = located.withOldLocation(r[0], r[1]);
                }
            }
            if (newLoc != null && m.change() != MemberChange.Change.REMOVED) {
                int[] r = newLoc.find(m);
                if (r != null) {
                    located = located.withNewLocation(r[0], r[1]);
                }
            }
            result.add(located);
        }
        return result;
    }

    private int[] find(MemberChange m) {
        MemberChange.Kind kind = m.kind() == MemberChange.Kind.CONSTRUCTOR ? MemberChange.Kind.METHOD : m.kind();
        String name = m.kind() == MemberChange.Kind.NESTED_CLASS
                ? m.name().substring(m.name().lastIndexOf('$') + 1) : m.name();
        List<Decl> candidates = decls.stream().filter(d -> d.kind() == kind && d.name().equals(name)).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        boolean nested = !m.owner().isEmpty();
        Decl best = candidates.stream().min(Comparator
                .comparingInt((Decl d) -> m.paramCount() >= 0 && d.params() == m.paramCount() ? 0 : 1)
                .thenComparingInt(d -> nested == (d.indent() > 4) ? 0 : 1)
                .thenComparingInt(Decl::line)).orElseThrow();
        return new int[] {best.line(), endOf(best)};
    }

    /** The member ends before the next member at the same level or at the closing brace of its class. */
    private int endOf(Decl decl) {
        int next = lines.length;
        for (Decl d : decls) {
            if (d.line() > decl.line() && d.indent() <= decl.indent()) {
                next = d.line() - 1;
                break;
            }
        }
        for (int i = decl.line(); i < next; i++) {
            String line = lines[i];
            if (line.strip().startsWith("}") && line.length() - line.stripLeading().length() < decl.indent()) {
                return i;
            }
        }
        return next;
    }

    /** Counts the parameters in the text after the opening parenthesis of a declaration. */
    static int countParams(String afterParen) {
        int depth = 0;
        int count = 0;
        boolean any = false;
        for (int i = 0; i < afterParen.length(); i++) {
            char c = afterParen.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            } else if (!Character.isWhitespace(c)) {
                any = true;
            }
        }
        return any ? count + 1 : 0;
    }
}

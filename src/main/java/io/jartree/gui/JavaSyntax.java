package io.jartree.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Line-by-line Java tokenizer for coloring decompiled source. Only what needs a color is reported (keywords,
 * literals, comments, annotations); block comments and text blocks carry over to the next line through the
 * {@link State}.
 */
final class JavaSyntax {

    enum Style { KEYWORD, STRING, NUMBER, COMMENT, ANNOTATION }

    /** Where a line starts: in code, inside a block comment, or inside a text block. */
    enum State { CODE, BLOCK_COMMENT, TEXT_BLOCK }

    /** A colored part of a line, from start (inclusive) to end (exclusive). */
    record Span(int start, int end, Style style) {
    }

    record Result(List<Span> spans, State end) {
    }

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "non-sealed");

    /** Keywords only in front of a declaration, otherwise ordinary names (a variable may be called record). */
    private static final Set<String> CONTEXTUAL = Set.of("record", "sealed", "permits");

    private JavaSyntax() {
    }

    static Result highlight(String line, State start) {
        List<Span> spans = new ArrayList<>();
        int n = line.length();
        int i = 0;
        State state = start;
        if (state == State.BLOCK_COMMENT) {
            int end = line.indexOf("*/");
            if (end < 0) {
                add(spans, 0, n, Style.COMMENT);
                return new Result(spans, State.BLOCK_COMMENT);
            }
            add(spans, 0, end + 2, Style.COMMENT);
            i = end + 2;
        } else if (state == State.TEXT_BLOCK) {
            int end = textBlockEnd(line, 0);
            if (end < 0) {
                add(spans, 0, n, Style.STRING);
                return new Result(spans, State.TEXT_BLOCK);
            }
            add(spans, 0, end, Style.STRING);
            i = end;
        }
        while (i < n) {
            char c = line.charAt(i);
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                add(spans, i, n, Style.COMMENT);
                return new Result(spans, State.CODE);
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                if (end < 0) {
                    add(spans, i, n, Style.COMMENT);
                    return new Result(spans, State.BLOCK_COMMENT);
                }
                add(spans, i, end + 2, Style.COMMENT);
                i = end + 2;
            } else if (line.startsWith("\"\"\"", i)) {
                int end = textBlockEnd(line, i + 3);
                if (end < 0) {
                    add(spans, i, n, Style.STRING);
                    return new Result(spans, State.TEXT_BLOCK);
                }
                add(spans, i, end, Style.STRING);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = quotedEnd(line, i + 1, c);
                add(spans, i, end, Style.STRING);
                i = end;
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1))) {
                int end = numberEnd(line, i);
                add(spans, i, end, Style.NUMBER);
                i = end;
            } else if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(line.charAt(i + 1))) {
                int end = identifierEnd(line, i + 1);
                if (line.startsWith("interface", i + 1) && end == i + 10) {
                    add(spans, i, end, Style.KEYWORD);
                } else {
                    // qualified annotation names: @java.lang.Deprecated
                    while (end + 1 < n && line.charAt(end) == '.' && Character.isJavaIdentifierStart(line.charAt(end + 1))) {
                        end = identifierEnd(line, end + 1);
                    }
                    add(spans, i, end, Style.ANNOTATION);
                }
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(line, i);
                if (line.startsWith("non-sealed", i)) {
                    end = i + "non-sealed".length();
                }
                String word = line.substring(i, end);
                if (KEYWORDS.contains(word) || CONTEXTUAL.contains(word) && isContextualKeyword(line, i, end)) {
                    add(spans, i, end, Style.KEYWORD);
                }
                i = end;
            } else {
                i++;
            }
        }
        return new Result(spans, State.CODE);
    }

    /** The state at the start of each line of a whole file. */
    static State[] lineStates(String text) {
        String[] lines = text.split("\\R", -1);
        State[] states = new State[lines.length];
        State state = State.CODE;
        for (int i = 0; i < lines.length; i++) {
            states[i] = state;
            state = highlight(lines[i], state).end();
        }
        return states;
    }

    /** A contextual keyword is followed by a name, as in {@code record Point(} or {@code permits A, B}. */
    private static boolean isContextualKeyword(String line, int start, int end) {
        if (start > 0 && line.charAt(start - 1) == '.') {
            return false;
        }
        int j = end;
        while (j < line.length() && line.charAt(j) == ' ') {
            j++;
        }
        return j > end && j < line.length() && Character.isJavaIdentifierStart(line.charAt(j));
    }

    private static void add(List<Span> spans, int start, int end, Style style) {
        if (end > start) {
            spans.add(new Span(start, end, style));
        }
    }

    private static int identifierEnd(String line, int i) {
        int j = i + 1;
        while (j < line.length() && Character.isJavaIdentifierPart(line.charAt(j))) {
            j++;
        }
        return j;
    }

    /** End (exclusive) of a string or char literal; the line end when it is not closed. */
    private static int quotedEnd(String line, int i, char quote) {
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return line.length();
    }

    /** End (exclusive) of the closing quotes of a text block, or -1 when it continues on the next line. */
    private static int textBlockEnd(String line, int i) {
        while (i < line.length()) {
            if (line.charAt(i) == '\\') {
                i += 2;
            } else if (line.startsWith("\"\"\"", i)) {
                return i + 3;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int numberEnd(String line, int i) {
        int j = i;
        while (j < line.length()) {
            char c = line.charAt(j);
            char prev = j > i ? Character.toLowerCase(line.charAt(j - 1)) : 0;
            boolean hex = line.length() > i + 1 && line.charAt(i) == '0'
                    && Character.toLowerCase(line.charAt(i + 1)) == 'x';
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.'
                    || (c == '+' || c == '-') && (prev == 'e' && !hex || prev == 'p')) {
                j++;
            } else {
                break;
            }
        }
        return j;
    }
}

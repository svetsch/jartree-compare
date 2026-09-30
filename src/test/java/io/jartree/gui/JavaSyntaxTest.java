package io.jartree.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.jartree.gui.JavaSyntax.State;

class JavaSyntaxTest {

    /** The colored parts of a line as "style:text", separated by spaces. */
    private static String colored(String line, State start) {
        JavaSyntax.Result r = JavaSyntax.highlight(line, start);
        return r.spans().stream()
                .map(s -> s.style().name().toLowerCase() + ":" + line.substring(s.start(), s.end()))
                .collect(Collectors.joining(" "));
    }

    private static String colored(String line) {
        return colored(line, State.CODE);
    }

    @Test
    void colorsKeywordsLiteralsAndComments() {
        assertEquals("keyword:public keyword:static keyword:int number:42 comment:// the answer",
                colored("public static int answer = 42; // the answer"));
        assertEquals("keyword:return string:\"a \\\"b\\\" // c\" keyword:null",
                colored("return \"a \\\"b\\\" // c\" + null;"));
        assertEquals("keyword:char string:'\\''", colored("char q = '\\'';"));
        assertEquals("number:0x1F number:1.5e-3 number:10L", colored("x(0x1F, 1.5e-3, 10L)"));
        assertEquals("annotation:@Override annotation:@java.lang.Deprecated", colored("@Override @java.lang.Deprecated"));
        assertEquals("keyword:@interface", colored("@interface Marker"));
        assertEquals("keyword:this keyword:class", colored("Outer.this.type = Foo.class;"));
    }

    @Test
    void contextualKeywordsOnlyInFrontOfADeclaration() {
        assertEquals("keyword:public keyword:record", colored("public record Point(Integer x) {"));
        assertEquals("keyword:int", colored("int record = entry.record();"));
        assertEquals("keyword:sealed keyword:interface keyword:permits", colored("sealed interface S permits A, B {"));
        assertEquals("keyword:non-sealed keyword:class keyword:implements", colored("non-sealed class A implements S {"));
    }

    @Test
    void blockCommentsAndTextBlocksContinueOnTheNextLine() {
        JavaSyntax.Result open = JavaSyntax.highlight("int a; /* start", State.CODE);
        assertEquals(State.BLOCK_COMMENT, open.end());
        assertEquals("comment: * middle", colored(" * middle", State.BLOCK_COMMENT));
        assertEquals("comment: end */ keyword:int", colored(" end */ int b;", State.BLOCK_COMMENT));

        assertEquals(State.TEXT_BLOCK, JavaSyntax.highlight("String s = \"\"\"", State.CODE).end());
        assertEquals("string:  \"quoted\" text", colored("  \"quoted\" text", State.TEXT_BLOCK));
        assertEquals("string:  \"\"\" keyword:return", colored("  \"\"\"; return;", State.TEXT_BLOCK));
    }

    @Test
    void computesTheStartStateOfEachLine() {
        State[] states = JavaSyntax.lineStates("/**\n * doc\n */\nclass A {\n  String s = \"\"\"\n    x\n    \"\"\";\n}\n");
        assertArrayEquals(new State[] {State.CODE, State.BLOCK_COMMENT, State.BLOCK_COMMENT, State.CODE, State.CODE,
                State.TEXT_BLOCK, State.TEXT_BLOCK, State.CODE, State.CODE}, states);
        assertEquals(List.of(), JavaSyntax.highlight("", State.CODE).spans());
    }
}

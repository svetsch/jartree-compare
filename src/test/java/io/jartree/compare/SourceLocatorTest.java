package io.jartree.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jartree.bytecode.MemberChange;
import io.jartree.bytecode.MemberChange.Change;
import io.jartree.bytecode.MemberChange.Kind;

class SourceLocatorTest {

    private static final String SOURCE = """
            package com.acme;

            public class Calc {
                public static final String NAME = "calculator";
                private int count;

                public Calc() {
                    this.count = 0;
                }

                public int add(int a, int b) {
                    if (a < 0) {
                        throw new IllegalArgumentException("negative");
                    }
                    return a + b;
                }

                public int add(int a, int b, int c) {
                    return add(add(a, b), c);
                }

                static class Helper {
                    int apply(int a) {
                        return a << 1;
                    }
                }
            }
            """;

    private static MemberChange member(Kind kind, String owner, String name, int params) {
        return new MemberChange(Change.MODIFIED, kind, owner, name, name, "body", params);
    }

    @Test
    void locatesMembersIncludingOverloadsAndNestedClasses() {
        List<MemberChange> located = SourceLocator.locate(List.of(
                member(Kind.METHOD, "", "add", 2),
                member(Kind.METHOD, "", "add", 3),
                member(Kind.FIELD, "", "NAME", -1),
                member(Kind.CONSTRUCTOR, "", "Calc", 0),
                member(Kind.METHOD, "Helper", "apply", 1),
                member(Kind.METHOD, "", "missing", 0)), null, SOURCE);

        assertEquals(11, located.get(0).newLine());
        assertEquals(17, located.get(0).newEnd());
        assertEquals(18, located.get(1).newLine());
        assertEquals(4, located.get(2).newLine());
        assertEquals(7, located.get(3).newLine());
        assertEquals(23, located.get(4).newLine());
        assertEquals(25, located.get(4).newEnd());
        assertNull(located.get(5).newLine());
        assertNull(located.get(0).oldLine());
    }

    @Test
    void countsParameters() {
        assertEquals(0, SourceLocator.countParams(") {"));
        assertEquals(2, SourceLocator.countParams("Map<String, Integer> m, int x) {"));
        assertEquals(1, SourceLocator.countParams("String... args) {"));
    }
}

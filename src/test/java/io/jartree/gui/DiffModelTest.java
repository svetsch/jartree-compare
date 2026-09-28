package io.jartree.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jartree.compare.TextSupport;

class DiffModelTest {

    private static final String DIFF = """
            --- a/Foo.java
            +++ b/Foo.java
            @@ -10,4 +10,5 @@
             keep
            -old one
            -old two
            +new one
            +new two
            +new three
             tail
            ... diff truncated (3 more lines)
            """;

    @Test
    void parsesLineNumbers() {
        List<DiffModel.Line> lines = DiffModel.parse(DIFF);
        assertEquals(DiffModel.Kind.HEADER, lines.get(0).kind());
        assertEquals(DiffModel.Kind.HUNK, lines.get(2).kind());
        DiffModel.Line keep = lines.get(3);
        assertEquals(DiffModel.Kind.CONTEXT, keep.kind());
        assertEquals(10, keep.oldNo());
        assertEquals(10, keep.newNo());
        assertEquals("keep", keep.text());
        assertEquals(12, lines.get(5).oldNo());
        assertEquals(13, lines.get(8).newNo());
        DiffModel.Line tail = lines.get(9);
        assertEquals(13, tail.oldNo());
        assertEquals(14, tail.newNo());
        assertEquals(DiffModel.Kind.NOTE, lines.get(lines.size() - 1).kind());
    }

    @Test
    void pairsChangesSideBySide() {
        List<DiffModel.Row> rows = DiffModel.sideBySide(DiffModel.parse(DIFF));
        // hunk, keep, 3 change rows, tail, note (headers are dropped)
        assertEquals(7, rows.size());
        DiffModel.Row first = rows.get(2);
        assertEquals("old one", first.left());
        assertEquals("new one", first.right());
        DiffModel.Row third = rows.get(4);
        assertEquals(DiffModel.Kind.EMPTY, third.leftKind());
        assertEquals("new three", third.right());
        assertEquals(13, third.rightNo());
        assertTrue(DiffModel.parse("").isEmpty());
    }

    @Test
    void highlightsTheChangedPartOfEditedLines() {
        List<DiffModel.Line> lines = DiffModel.parse("""
                @@ -1,1 +1,1 @@
                -        return a * 2;
                +        return a << 1;
                """);
        DiffModel.Line removed = lines.get(1);
        DiffModel.Line added = lines.get(2);
        assertEquals("* 2", removed.text().substring(removed.hlStart(), removed.hlEnd()));
        assertEquals("<< 1", added.text().substring(added.hlStart(), added.hlEnd()));

        int[] word = DiffModel.changedRange("int count = total;", "int count = totalSize;");
        assertEquals("total", "int count = total;".substring(word[0], word[1]));
        assertEquals("totalSize", "int count = totalSize;".substring(word[0], word[2]));
        assertEquals(null, DiffModel.changedRange("completely different", "nothing alike here"));
    }

    @Test
    void wholeFileDiffKeepsEveryLine() {
        String a = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n";
        String b = a.replace("five", "FIVE");
        TextSupport.Diffs diffs = TextSupport.unifiedDiffs("a/X", "b/X", a, b, 1, 0);
        List<DiffModel.Line> changes = DiffModel.parse(diffs.diff().text());
        List<DiffModel.Line> whole = DiffModel.parse(diffs.whole().text());
        assertEquals(1 + 3 + 1, changes.stream().filter(l -> l.kind() != DiffModel.Kind.HEADER).count());
        // hunk, 9 unchanged lines, one removed and one added
        assertEquals(1 + 9 + 2, whole.stream().filter(l -> l.kind() != DiffModel.Kind.HEADER).count());
        assertEquals(diffs.diff().added(), diffs.whole().added());
        DiffModel.Line last = whole.get(whole.size() - 1);
        assertEquals("ten", last.text());
        assertEquals(10, last.oldNo());
        assertEquals(10, last.newNo());
        assertEquals(diffs.whole().text(), TextSupport.decompress(TextSupport.compress(diffs.whole().text())));
        assertTrue(TextSupport.unifiedDiffs("a/X", "b/X", a, a, 3, 0).whole().isEmpty());
    }
}

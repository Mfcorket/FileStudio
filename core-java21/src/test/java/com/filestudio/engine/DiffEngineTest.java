package com.filestudio.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffEngineTest {

    @Test
    void identicalTextsProduceEmptyDiff() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nb\nc", "a\nb\nc");
        assertTrue(result.isEmpty());
        assertEquals(0, result.addedCount());
        assertEquals(0, result.removedCount());
        assertEquals(3, result.unchangedCount());
    }

    @Test
    void completelyDifferentTexts() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nb", "x\ny");
        assertFalse(result.isEmpty());
        assertEquals(2, result.addedCount());
        assertEquals(2, result.removedCount());
        assertEquals(0, result.unchangedCount());
    }

    @Test
    void addedLines() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nb", "a\nNEW\nb");
        assertFalse(result.isEmpty());
        assertEquals(1, result.addedCount());
        assertEquals(0, result.removedCount());
        assertEquals(2, result.unchangedCount());

        List<DiffEngine.DiffLine> lines = result.lines();
        assertEquals(DiffEngine.Type.EQUAL, lines.get(0).type());
        assertEquals("a", lines.get(0).content());
        assertEquals(DiffEngine.Type.INSERT, lines.get(1).type());
        assertEquals("NEW", lines.get(1).content());
        assertEquals(DiffEngine.Type.EQUAL, lines.get(2).type());
        assertEquals("b", lines.get(2).content());
    }

    @Test
    void removedLines() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nOLD\nb", "a\nb");
        assertFalse(result.isEmpty());
        assertEquals(0, result.addedCount());
        assertEquals(1, result.removedCount());
        assertEquals(2, result.unchangedCount());
    }

    @Test
    void modifiedLines() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nOLD\nc", "a\nNEW\nc");
        assertFalse(result.isEmpty());
        assertEquals(1, result.addedCount());
        assertEquals(1, result.removedCount());
        assertEquals(2, result.unchangedCount());
    }

    @Test
    void emptyOldText() {
        DiffEngine.DiffResult result = DiffEngine.diff("", "a\nb");
        assertFalse(result.isEmpty());
        assertEquals(2, result.addedCount());
        assertEquals(0, result.removedCount());
    }

    @Test
    void emptyNewText() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nb", "");
        assertFalse(result.isEmpty());
        assertEquals(0, result.addedCount());
        assertEquals(2, result.removedCount());
    }

    @Test
    void bothEmpty() {
        DiffEngine.DiffResult result = DiffEngine.diff("", "");
        assertTrue(result.isEmpty());
    }

    @Test
    void nullInputsTreatedAsEmpty() {
        DiffEngine.DiffResult result = DiffEngine.diff(null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void lcsPreservesCommonSubsequence() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nb\nc\nd", "a\nc\nd");
        assertEquals(0, result.addedCount());
        assertEquals(1, result.removedCount());
        assertEquals(3, result.unchangedCount());
    }

    @Test
    void multipleChanges() {
        DiffEngine.DiffResult result = DiffEngine.diff(
                "line1\nline2\nline3\nline4",
                "line1\nCHANGED\nline3\nline5");
        assertFalse(result.isEmpty());
        assertEquals(2, result.addedCount());
        assertEquals(2, result.removedCount());
        assertEquals(2, result.unchangedCount());
    }

    @Test
    void lineNumbersAreCorrect() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nb\nc", "a\nNEW\nc");
        List<DiffEngine.DiffLine> lines = result.lines();

        // 第一行 EQUAL
        assertEquals(1, lines.get(0).oldLineNumber());
        assertEquals(1, lines.get(0).newLineNumber());
        // 第二行 DELETE（old 有但 new 没有）
        assertEquals(2, lines.get(1).oldLineNumber());
        assertEquals(-1, lines.get(1).newLineNumber());
        // 第三行 INSERT（new 有但 old 没有）
        assertEquals(-1, lines.get(2).oldLineNumber());
        assertEquals(2, lines.get(2).newLineNumber());
        // 第四行 EQUAL
        assertEquals(3, lines.get(3).oldLineNumber());
        assertEquals(3, lines.get(3).newLineNumber());
    }

    @Test
    void unifiedFormatOutput() {
        DiffEngine.DiffResult result = DiffEngine.diff("a\nOLD\nc", "a\nNEW\nc");
        String unified = result.toUnifiedFormat();
        assertTrue(unified.contains("  a"));
        assertTrue(unified.contains("- OLD"));
        assertTrue(unified.contains("+ NEW"));
        assertTrue(unified.contains("  c"));
    }

    @Test
    void diffLineValidation() {
        assertThrows(NullPointerException.class, () -> new DiffEngine.DiffLine(null, "x", 1, 1));
        assertThrows(NullPointerException.class, () -> new DiffEngine.DiffLine(DiffEngine.Type.EQUAL, null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DiffEngine.DiffLine(DiffEngine.Type.EQUAL, "x", -2, 1));
    }

    @Test
    void diffLineHelpers() {
        DiffEngine.DiffLine insert = new DiffEngine.DiffLine(DiffEngine.Type.INSERT, "x", -1, 1);
        assertTrue(insert.isInsert());
        assertFalse(insert.isDelete());
        assertFalse(insert.isEqual());

        DiffEngine.DiffLine delete = new DiffEngine.DiffLine(DiffEngine.Type.DELETE, "x", 1, -1);
        assertFalse(delete.isInsert());
        assertTrue(delete.isDelete());
        assertFalse(delete.isEqual());

        DiffEngine.DiffLine equal = new DiffEngine.DiffLine(DiffEngine.Type.EQUAL, "x", 1, 1);
        assertFalse(equal.isInsert());
        assertFalse(equal.isDelete());
        assertTrue(equal.isEqual());
    }

    @Test
    void largeTextDiff() {
        StringBuilder oldText = new StringBuilder();
        StringBuilder newText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            oldText.append("line").append(i).append('\n');
            if (i != 50) {
                newText.append("line").append(i).append('\n');
            } else {
                newText.append("CHANGED").append('\n');
            }
        }
        DiffEngine.DiffResult result = DiffEngine.diff(oldText.toString(), newText.toString());
        assertFalse(result.isEmpty());
        assertEquals(1, result.addedCount());
        assertEquals(1, result.removedCount());
        assertEquals(100, result.unchangedCount()); // 99 行相同 + 1 行末尾空字符串
    }
}

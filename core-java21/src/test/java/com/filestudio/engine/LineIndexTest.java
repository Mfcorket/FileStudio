package com.filestudio.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LineIndexTest {

    @Test
    void emptyContent() {
        LineIndex idx = LineIndex.of("");
        assertEquals(1, idx.lineCount());
        assertEquals(0, idx.charCount());
        assertEquals(0, idx.lineStart(0));
        assertEquals(0, idx.lineLength(0));
        assertEquals("", idx.lineContent(0));
        assertEquals(0, idx.lineOf(0));
        assertEquals(0, idx.columnOf(0));
        assertEquals(0, idx.offsetOf(0, 0));
        assertFalse(idx.hasLineBreak());
        assertEquals(0, idx.longestLine());
    }

    @Test
    void singleLine() {
        LineIndex idx = LineIndex.of("hello");
        assertEquals(1, idx.lineCount());
        assertEquals(5, idx.charCount());
        assertEquals(0, idx.lineStart(0));
        assertEquals(5, idx.lineLength(0));
        assertEquals("hello", idx.lineContent(0));
        assertFalse(idx.hasLineBreak());
        assertEquals(5, idx.longestLine());
    }

    @Test
    void multiLineOffsetAndColumn() {
        LineIndex idx = LineIndex.of("ab\ncd\nef");
        assertEquals(3, idx.lineCount());
        assertTrue(idx.hasLineBreak());

        // 行 0: "ab", 行 1: "cd", 行 2: "ef"
        assertEquals(0, idx.lineStart(0));
        assertEquals(3, idx.lineStart(1));
        assertEquals(6, idx.lineStart(2));

        assertEquals("ab", idx.lineContent(0));
        assertEquals("cd", idx.lineContent(1));
        assertEquals("ef", idx.lineContent(2));

        assertEquals(2, idx.lineLength(0));
        assertEquals(2, idx.lineLength(1));
        assertEquals(2, idx.lineLength(2));
    }

    @Test
    void lineOfColumnOfRoundTrip() {
        String content = "ab\ncd\nef";
        LineIndex idx = LineIndex.of(content);
        for (int offset = 0; offset <= content.length(); offset++) {
            int line = idx.lineOf(offset);
            int col = idx.columnOf(offset);
            assertEquals(offset, idx.offsetOf(line, col));
        }
    }

    @Test
    void offsetOfClampsColumn() {
        LineIndex idx = LineIndex.of("ab\ncd");
        // 行 0 长度 2，列 100 应夹到 2 -> offset 2
        assertEquals(2, idx.offsetOf(0, 100));
        // 负列夹到 0
        assertEquals(0, idx.offsetOf(0, -5));
    }

    @Test
    void lineOfClampsOffset() {
        LineIndex idx = LineIndex.of("ab\ncd");
        assertEquals(0, idx.lineOf(-10));
        assertEquals(1, idx.lineOf(100));
    }

    @Test
    void longestLine() {
        LineIndex idx = LineIndex.of("a\nbbb\ncc");
        assertEquals(3, idx.longestLine());
    }

    @Test
    void trailingNewlineProducesTrailingEmptyLine() {
        LineIndex idx = LineIndex.of("ab\n");
        assertEquals(2, idx.lineCount());
        assertEquals("ab", idx.lineContent(0));
        assertEquals("", idx.lineContent(1));
    }

    @Test
    void manyLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("line").append(i).append('\n');
        }
        LineIndex idx = LineIndex.of(sb.toString());
        assertEquals(1001, idx.lineCount()); // 含末尾空行
        assertEquals("line999", idx.lineContent(999));
    }

    @Test
    void nullContentTreatedAsEmpty() {
        LineIndex idx = LineIndex.of(null);
        assertEquals(1, idx.lineCount());
        assertEquals(0, idx.charCount());
    }
}

package com.filestudio.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentStatsTest {

    @Test
    void emptyContent() {
        DocumentStats s = DocumentStats.of("");
        assertTrue(s.isEmpty());
        assertEquals(1, s.lineCount());
        assertEquals(0, s.charCount());
        assertEquals(0, s.wordCount());
        assertEquals(0, s.longestLine());
        assertEquals(DocumentStats.LineEnding.LF, s.lineEnding());
        assertEquals(0, s.totalLineBreaks());
    }

    @Test
    void lfLineEndings() {
        DocumentStats s = DocumentStats.of("one\ntwo\nthree");
        assertEquals(3, s.lineCount());
        assertEquals(13, s.charCount());
        assertEquals(3, s.wordCount());
        assertEquals(DocumentStats.LineEnding.LF, s.lineEnding());
        assertEquals(2, s.lfCount());
        assertEquals(0, s.crlfCount());
        assertEquals(0, s.crCount());
        assertEquals(2, s.totalLineBreaks());
    }

    @Test
    void crlfLineEndings() {
        DocumentStats s = DocumentStats.of("one\r\ntwo\r\nthree");
        assertEquals(3, s.lineCount());
        assertEquals(DocumentStats.LineEnding.CRLF, s.lineEnding());
        assertEquals(0, s.lfCount());
        assertEquals(2, s.crlfCount());
        assertEquals(2, s.totalLineBreaks());
    }

    @Test
    void crLineEndings() {
        DocumentStats s = DocumentStats.of("one\rtwo\rthree");
        assertEquals(DocumentStats.LineEnding.CR, s.lineEnding());
        assertEquals(0, s.lfCount());
        assertEquals(0, s.crlfCount());
        assertEquals(2, s.crCount());
        assertEquals(2, s.totalLineBreaks());
    }

    @Test
    void mixedLineEndings() {
        DocumentStats s = DocumentStats.of("a\nb\r\nc\rd");
        assertEquals(DocumentStats.LineEnding.MIXED, s.lineEnding());
        assertEquals(1, s.lfCount());
        assertEquals(1, s.crlfCount());
        assertEquals(1, s.crCount());
    }

    @Test
    void wordCountCountsWhitespaceSeparatedTokens() {
        DocumentStats s = DocumentStats.of("hello,  world   foo\tbar");
        assertEquals(4, s.wordCount());
        assertEquals(23, s.charCount());
    }

    @Test
    void wordCountIgnoresWhitespaceOnly() {
        DocumentStats s = DocumentStats.of("   \t\n   ");
        assertEquals(0, s.wordCount());
    }

    @Test
    void codePointCountHandlesEmoji() {
        // 4 个 emoji 各占 2 个 UTF-16 code unit
        String emoji = "😀😁😂🤣";
        DocumentStats s = DocumentStats.of(emoji);
        assertEquals(8, s.charCount());     // UTF-16 code units
        assertEquals(4, s.codePointCount()); // Unicode code points
    }

    @Test
    void longestLine() {
        DocumentStats s = DocumentStats.of("ab\ncdef\nghi");
        assertEquals(4, s.longestLine());
    }

    @Test
    void nullContentTreatedAsEmpty() {
        DocumentStats s = DocumentStats.of(null);
        assertTrue(s.isEmpty());
        assertEquals(0, s.charCount());
    }

    @Test
    void toStringContainsKeyFields() {
        DocumentStats s = DocumentStats.of("a\nb");
        String str = s.toString();
        assertTrue(str.contains("lines="));
        assertTrue(str.contains("eol="));
    }
}

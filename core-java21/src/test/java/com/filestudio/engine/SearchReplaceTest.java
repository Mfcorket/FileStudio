package com.filestudio.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchReplaceTest {

    private final String sample = "Hello, FileStudio! Hello, world!";

    @Test
    void findMatchesReturnsAllOccurrences() {
        List<SearchReplace.SearchMatch> m =
                SearchReplace.findMatches(sample, "Hello", SearchOptions.defaultOptions());
        assertEquals(2, m.size());
        assertEquals(0, m.get(0).start());
        assertEquals(5, m.get(0).end());
        assertEquals(19, m.get(1).start());
        assertEquals(24, m.get(1).end());
    }

    @Test
    void caseInsensitiveByDefault() {
        assertEquals(2, SearchReplace.findMatches("Hello hello", "hello",
                SearchOptions.defaultOptions()).size());
    }

    @Test
    void caseSensitiveMatchesCase() {
        List<SearchReplace.SearchMatch> m = SearchReplace.findMatches("Hello hello", "hello",
                new SearchOptions(true, false, false, true));
        assertEquals(1, m.size());
        assertEquals(6, m.get(0).start());
    }

    @Test
    void wholeWordMatchesOnlyWholeWords() {
        List<SearchReplace.SearchMatch> m = SearchReplace.findMatches("cat category", "cat",
                new SearchOptions(false, true, false, true));
        assertEquals(1, m.size());
        assertEquals(0, m.get(0).start());
    }

    @Test
    void regexMatchesPattern() {
        List<SearchReplace.SearchMatch> m = SearchReplace.findMatches("abc123def", "\\d+",
                new SearchOptions(false, false, true, true));
        assertEquals(1, m.size());
        assertEquals("123", m.get(0).text());
    }

    @Test
    void invalidRegexReturnsEmpty() {
        assertTrue(SearchReplace.findMatches("abc", "[",
                new SearchOptions(false, false, true, true)).isEmpty());
    }

    @Test
    void isValidQueryDetectsSyntaxError() {
        assertFalse(SearchReplace.isValidQuery("[",
                new SearchOptions(false, false, true, true)));
        assertTrue(SearchReplace.isValidQuery("abc",
                new SearchOptions(false, false, true, true)));
    }

    @Test
    void replaceAllReplacesAllMatches() {
        String result = SearchReplace.replaceAll("foo bar foo baz", "foo", "qux",
                SearchOptions.defaultOptions());
        assertEquals("qux bar qux baz", result);
    }

    @Test
    void replaceAtReplacesSpecificOccurrence() {
        String result = SearchReplace.replaceAt("a a a", "a", "X", 1,
                SearchOptions.defaultOptions());
        assertEquals("a X a", result);
    }

    @Test
    void replaceAtOutOfRangeIsNoop() {
        assertEquals("a a", SearchReplace.replaceAt("a a", "a", "X", 5,
                SearchOptions.defaultOptions()));
    }

    @Test
    void replaceInRangeRespectsBounds() {
        String src = "aaa|bbb|ccc";
        String result = SearchReplace.replaceInRange(src, 0, 3, "a", "X",
                SearchOptions.defaultOptions());
        assertEquals("XXX|bbb|ccc", result);
    }

    @Test
    void replacementSupportsDollarZero() {
        String result = SearchReplace.replaceAll("cat", "cat", "($0)",
                new SearchOptions(false, false, false, true));
        assertEquals("(cat)", result);
    }

    @Test
    void emptyQueryReturnsEmpty() {
        assertTrue(SearchReplace.findMatches("abc", "", SearchOptions.defaultOptions()).isEmpty());
        assertTrue(SearchReplace.findMatches("abc", null, SearchOptions.defaultOptions()).isEmpty());
    }

    @Test
    void nullTextIsSafe() {
        assertTrue(SearchReplace.findMatches(null, "x", SearchOptions.defaultOptions()).isEmpty());
        assertNull(SearchReplace.findNext(null, "x", SearchOptions.defaultOptions(), 0));
    }

    @Test
    void findNextAdvancesPastOffset() {
        String s = "ab ab ab";
        SearchReplace.SearchMatch m1 = SearchReplace.findNext(s, "ab",
                SearchOptions.defaultOptions(), 0);
        assertNotNull(m1);
        assertEquals(0, m1.start());
        SearchReplace.SearchMatch m2 = SearchReplace.findNext(s, "ab",
                SearchOptions.defaultOptions(), m1.end());
        assertNotNull(m2);
        assertEquals(3, m2.start());
    }

    @Test
    void zeroWidthRegexMatchesAreSkipped() {
        // \b 是零宽匹配，不应产生结果
        assertTrue(SearchReplace.findMatches("abc", "\\b",
                new SearchOptions(false, false, true, true)).isEmpty());
    }

    @Test
    void searchMatchRecordsAreImmutable() {
        SearchReplace.SearchMatch m = new SearchReplace.SearchMatch(1, 3, "bc");
        assertEquals(1, m.start());
        assertEquals(3, m.end());
        assertEquals("bc", m.text());
        assertEquals(2, m.length());
        assertThrows(IllegalArgumentException.class, () -> new SearchReplace.SearchMatch(3, 1, "x"));
    }
}

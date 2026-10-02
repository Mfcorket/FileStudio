package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiscTextHandlersTest {

    @Test
    void csvDetectsDelimiterAndCountsRows(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("data.csv");
        Files.writeString(p, "name,age,city\nAlice,30,Beijing\nBob,25,Shanghai\n",
                StandardCharsets.UTF_8);
        Document doc = new CsvFileHandler().parse(p.toFile());
        assertEquals("comma", doc.getMetadata().get("delimiter"));
        assertEquals(3, doc.getMetadata().get("rowCount"));
        assertEquals(3, doc.getMetadata().get("columnCount"));
        assertEquals("name", ((java.util.List<?>) doc.getMetadata().get("header")).get(0));
    }

    @Test
    void csvHandlesTabSeparated(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("data.tsv");
        Files.writeString(p, "a\tb\tc\n1\t2\t3\n", StandardCharsets.UTF_8);
        Document doc = new CsvFileHandler().parse(p.toFile());
        assertEquals("tab", doc.getMetadata().get("delimiter"));
        assertEquals(3, doc.getMetadata().get("columnCount"));
    }

    @Test
    void csvHandlesQuotedFields(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("q.csv");
        Files.writeString(p, "\"hello, world\",second\n1,2\n", StandardCharsets.UTF_8);
        Document doc = new CsvFileHandler().parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("columnCount"));
        assertEquals("hello, world",
                ((java.util.List<?>) doc.getMetadata().get("header")).get(0));
    }

    @Test
    void csvCanHandleBothExtensions() {
        CsvFileHandler h = new CsvFileHandler();
        assertTrue(h.canHandle(new java.io.File("a.csv")));
        assertTrue(h.canHandle(new java.io.File("b.tsv")));
        assertFalse(h.canHandle(new java.io.File("c.txt")));
    }

    @Test
    void iniCountsSectionsAndKeys(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.ini");
        Files.writeString(p,
                "; comment\n[main]\nkey1=val1\nkey2=val2\n\n[other]\nkey3=val3\n",
                StandardCharsets.UTF_8);
        Document doc = new IniFileHandler().parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("sectionCount"));
        assertEquals(3, doc.getMetadata().get("keyCount"));
        assertEquals(java.util.List.of("main", "other"), doc.getMetadata().get("sections"));
    }

    @Test
    void propertiesCountsKeys(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.properties");
        Files.writeString(p,
                "# header\n! another\nkey1=value1\nkey2=value2\n\n",
                StandardCharsets.UTF_8);
        Document doc = new PropertiesFileHandler().parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("keyCount"));
        assertEquals(2, doc.getMetadata().get("commentLines"));
        // 末尾两个换行产生两个空行片段
        assertEquals(2, doc.getMetadata().get("blankLines"));
    }

    @Test
    void markdownCountsHeadingsLinksLists(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("doc.md");
        Files.writeString(p,
                "# Title\n\n## Section\n\n- item 1\n- item 2\n\n" +
                        "[link](http://example.com) text `code` [![img](x.png)](y)\n\n" +
                        "```\ncode\n```\n",
                StandardCharsets.UTF_8);
        Document doc = new MarkdownFileHandler().parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("headingCount"));
        // 简易正则可以识别 [link](...) 与 [![img](x.png) 两处
        assertEquals(2, doc.getMetadata().get("linkCount"));
        assertEquals(2, doc.getMetadata().get("listItemCount"));
        assertEquals(1, doc.getMetadata().get("codeBlockCount"));
    }

    @Test
    void markdownCanHandleBothExtensions() {
        MarkdownFileHandler h = new MarkdownFileHandler();
        assertTrue(h.canHandle(new java.io.File("a.md")));
        assertTrue(h.canHandle(new java.io.File("b.markdown")));
        assertFalse(h.canHandle(new java.io.File("c.txt")));
    }

    @Test
    void csvSplitHandlesEscapedQuotes() {
        String[] fields = CsvFileHandler.splitCsvLine("\"a\"\"b\",c", ',');
        assertEquals(2, fields.length);
        assertEquals("a\"b", fields[0]);
        assertEquals("c", fields[1]);
    }

    @Test
    void csvDelimiterDetection(@TempDir Path dir) throws IOException {
        assertEquals(',', CsvFileHandler.detectDelimiter("a,b,c"));
        assertEquals('\t', CsvFileHandler.detectDelimiter("a\tb\tc"));
        assertEquals(';', CsvFileHandler.detectDelimiter("a;b;c"));
    }
}

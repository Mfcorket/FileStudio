package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RstFileHandlerTest {

    private final RstFileHandler handler = new RstFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("rst", handler.getExtension());
        assertEquals("text/x-rst", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void countsRstStructure(@TempDir Path dir) throws IOException {
        String rst = """
                ===============
                 Top Heading
                ===============

                Introduction text with `a reference <https://example.com>`_.

                Section Two
                -----------

                .. note::

                      A directed note.
                """;
        Path p = dir.resolve("doc.rst");
        Files.writeString(p, rst, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("headingCount"),
                "两行标题下划线（=== 与 ---）各计一次");
        assertEquals(1, doc.getMetadata().get("directiveCount"));
        assertEquals(1, doc.getMetadata().get("referenceCount"));
        assertTrue((int) doc.getMetadata().get("wordCount") > 0);
    }

    @Test
    void rendersTextBack(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.rst");
        Files.writeString(p, "Hello", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals("Hello", doc.getContent());
        assertTrue(doc.isEditable());
    }
}
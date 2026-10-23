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

class AsciiDocFileHandlerTest {

    private final AsciiDocFileHandler handler = new AsciiDocFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("adoc", handler.getExtension());
        assertEquals("text/x-asciidoc", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void countsAsciiDocStructure(@TempDir Path dir) throws IOException {
        String adoc = """
                = Document Title

                == Section One

                Here is `inline code` and a link https://example.com.

                ----
                block content
                ----

                == Section Two
                """;
        Path p = dir.resolve("doc.adoc");
        Files.writeString(p, adoc, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(3, doc.getMetadata().get("headingCount"), "1 个 H1 + 2 个 H2");
        assertEquals(1, doc.getMetadata().get("h1Count"));
        assertEquals(2, doc.getMetadata().get("h2Count"));
        assertTrue((int) doc.getMetadata().get("blockCount") >= 2, "---- 上下界各算一次");
        assertEquals(1, doc.getMetadata().get("linkCount"));
        assertEquals(1, doc.getMetadata().get("inlineCodeCount"));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("x.adoc")));
        assertFalse(handler.canHandle(new java.io.File("x.md")));
    }
}
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

class XmlFileHandlerTest {

    private final XmlFileHandler handler = new XmlFileHandler();

    @Test
    void declaresXmlFormat() {
        assertEquals("xml", handler.getExtension());
        assertEquals("application/xml", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void parsesWellFormedXml(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("doc.xml");
        Files.writeString(p,
                "<?xml version=\"1.0\"?>\n<root><child/><child/></root>",
                StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("root", doc.getMetadata().get("rootElement"));
        assertEquals(2, doc.getMetadata().get("rootChildCount"));
    }

    @Test
    void reportsErrorForUnclosedTag(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.xml");
        Files.writeString(p, "<root><child>", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void reportsMismatchedTags(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad2.xml");
        Files.writeString(p, "<root><child></wrong>", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void selfClosingTagsAreValid(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("ok.xml");
        Files.writeString(p, "<root><a/><b attr=\"1\"/></root>", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
    }

    @Test
    void commentsAndCdataAreSkipped() {
        var r = XmlFileHandler.scan("<!-- c --><root><![CDATA[<x>]]></root>");
        assertTrue(r.valid());
        assertEquals("root", r.rootElement());
    }

    @Test
    void processingInstructionsAreSkipped() {
        var r = XmlFileHandler.scan("<?xml version=\"1.0\"?><root/>");
        assertTrue(r.valid());
        assertEquals("root", r.rootElement());
    }

    @Test
    void depthIsTracked() {
        var r = XmlFileHandler.scan("<a><b><c/></b></a>");
        assertEquals(3, r.maxDepth());
    }

    @Test
    void emptyInputIsInvalid() {
        assertFalse(XmlFileHandler.scan("").valid());
        assertFalse(XmlFileHandler.scan("just text").valid());
    }
}

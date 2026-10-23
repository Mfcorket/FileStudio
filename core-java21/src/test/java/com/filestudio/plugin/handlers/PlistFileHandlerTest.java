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

class PlistFileHandlerTest {

    private final PlistFileHandler handler = new PlistFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("plist", handler.getExtension());
        assertEquals("application/x-plist", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void countsXmlPlistValues(@TempDir Path dir) throws IOException {
        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>name</key>
                    <string>Alice</string>
                    <key>age</key>
                    <integer>30</integer>
                    <key>height</key>
                    <real>1.75</real>
                    <key>active</key>
                    <true/>
                    <key>subscribed</key>
                    <false/>
                </dict>
                </plist>
                """;
        Path p = dir.resolve("prefs.plist");
        Files.writeString(p, plist, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals("application/x-plist", doc.getMimeType());
        assertEquals("xml", doc.getMetadata().get("encodingFormat"));
        assertEquals(5, doc.getMetadata().get("keyCount"));
        assertEquals(1, doc.getMetadata().get("stringValueCount"));
        assertEquals(1, doc.getMetadata().get("integerValueCount"));
        assertEquals(1, doc.getMetadata().get("realValueCount"));
        assertEquals(1, doc.getMetadata().get("trueCount"));
        assertEquals(1, doc.getMetadata().get("falseCount"));
        assertEquals("dict", doc.getMetadata().get("rootElement"));
        assertEquals(plist.trim(), doc.getContent().trim());
    }

    @Test
    void rejectsBinaryPlist(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bin.plist");
        Files.write(p, "bplist00".getBytes(StandardCharsets.US_ASCII), new java.nio.file.OpenOption[]{});
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.plist")));
        assertFalse(handler.canHandle(new java.io.File("b.xml")));
    }
}
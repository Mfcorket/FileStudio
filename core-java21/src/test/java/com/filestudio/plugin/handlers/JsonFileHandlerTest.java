package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileHandlerTest {

    private JsonFileHandler handler;
    private PluginManager pm;

    @BeforeEach
    void setUp() {
        handler = new JsonFileHandler();
        pm = new PluginManager();
        pm.registerHandler(handler);
    }

    @Test
    void declaresJsonFormat() {
        assertEquals("json", handler.getExtension());
        assertEquals("application/json", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void parsesValidJsonObject(@TempDir Path dir) throws IOException {
        String json = "{\"name\":\"FileStudio\",\"count\":3,\"tags\":[\"a\",\"b\"]}";
        Path p = dir.resolve("obj.json");
        Files.writeString(p, json, StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals("object", doc.getMetadata().get("rootType"));
        assertEquals(3, doc.getMetadata().get("keyCount"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(2, doc.getMetadata().get("maxDepth"));
        assertEquals(json, doc.getContent());
    }

    @Test
    void parsesValidJsonArray(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("arr.json");
        Files.writeString(p, "[1,2,3]", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals("array", doc.getMetadata().get("rootType"));
        assertEquals(3, doc.getMetadata().get("itemCount"));
        assertEquals(true, doc.getMetadata().get("valid"));
    }

    @Test
    void invalidJsonReportsError(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.json");
        Files.writeString(p, "{\"a\":}", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void jsonParserAcceptsNestedStructures() {
        var r = JsonFileHandler.scan("{\"a\":{\"b\":{\"c\":1}}},\"d\":2}");
        assertFalse(r.valid());
        // 修正后的有效 JSON
        r = JsonFileHandler.scan("{\"a\":{\"b\":{\"c\":1}},\"d\":2}");
        assertTrue(r.valid());
        assertEquals("object", r.rootType());
        assertEquals(2, r.rootSize());
        assertEquals(3, r.maxDepth());
    }

    @Test
    void jsonParserHandlesStringsAndEscapes() {
        var r = JsonFileHandler.scan("{\"s\":\"he said \\\"hi\\\"\\n\"}");
        assertTrue(r.valid());
        assertEquals("object", r.rootType());
        assertEquals(1, r.rootSize());
    }

    @Test
    void jsonParserHandlesNumbers() {
        assertTrue(JsonFileHandler.scan("42").valid());
        assertEquals("number", JsonFileHandler.scan("42").rootType());
        assertTrue(JsonFileHandler.scan("3.14").valid());
        assertTrue(JsonFileHandler.scan("-0.01").valid());
        assertTrue(JsonFileHandler.scan("1e5").valid());
    }

    @Test
    void jsonParserRejectsTrailingContent() {
        assertFalse(JsonFileHandler.scan("1 2").valid());
    }

    @Test
    void jsonParserRejectsUnterminatedArray() {
        assertFalse(JsonFileHandler.scan("[1,2").valid());
    }

    @Test
    void pluginManagerRoutesJsonByExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.json");
        Files.writeString(p, "{}", StandardCharsets.UTF_8);
        var h = pm.findHandler(p.toFile());
        assertTrue(h.isPresent());
        assertEquals("json", h.get().getExtension());
    }
}

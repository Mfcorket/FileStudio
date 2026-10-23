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

class UnityFileHandlerTest {

    private final UnityFileHandler handler = new UnityFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("unity", handler.getExtension());
        assertEquals("application/x-unity", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void countsUnityObjects(@TempDir Path dir) throws IOException {
        String yaml = """
                %YAML 1.1
                %TAG !u! tag:unity3d.com,2011:
                --- !u!29 &1
                OcclusionCullingSettings:
                  m_ObjectHideFlags: 0
                --- !u!1 &2
                GameObject:
                  m_Name: Main Camera
                --- !u!20 &3
                Camera:
                  m_NormalizedViewPortRect: {x: 0, y: 0, width: 1, height: 1}
                """;
        Path p = dir.resolve("scene.unity");
        Files.writeString(p, yaml, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals("unity", doc.getExtension());
        assertEquals(3, doc.getMetadata().get("objectCount"));
        assertEquals(3, doc.getMetadata().get("distinctClassCount"));
        assertEquals(29, doc.getMetadata().get("firstObjectClassId"));
        assertEquals(true, doc.getMetadata().get("hasUnityTag"));
        assertEquals("1.1", doc.getMetadata().get("yamlDoctype"));
        assertTrue(doc.isEditable());
    }

    @Test
    void contentSniffingRejectsOtherYaml(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("config.yml");
        Files.writeString(p, "a: 1\nb: 2\n", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.unity")));
        assertTrue(handler.canHandle(new java.io.File("b.prefab")));
        assertFalse(handler.canHandle(new java.io.File("c.txt")));
    }
}
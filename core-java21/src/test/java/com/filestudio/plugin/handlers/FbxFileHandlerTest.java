package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FbxFileHandlerTest {

    private final FbxFileHandler handler = new FbxFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("fbx", handler.getExtension());
        assertEquals("model/fbx", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesBinaryFbxVersion(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("model.fbx");
        byte[] head = ("Kaydara FBX Binary  ".getBytes(StandardCharsets.US_ASCII));
        byte[] fbx = new byte[head.length + 4 + 64];
        System.arraycopy(head, 0, fbx, 0, head.length);
        // 版本号：7400 → 7.4.0
        fbx[head.length] = 0;
        fbx[head.length + 1] = 0;
        fbx[head.length + 2] = 0x1C;
        fbx[head.length + 3] = (byte) 0xE8; // 7400 = 0x1CE8
        Files.write(p, fbx);

        assertTrue(handler.canHandle(p.toFile()));
        Document doc = handler.parse(p.toFile());
        assertEquals("binary", doc.getMetadata().get("encoding"));
        assertEquals("7.4.0", doc.getMetadata().get("version"));
        assertEquals("Kaydara FBX Binary", doc.getMetadata().get("magic"));
    }

    @Test
    void parsesAsciiFbx(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("text.fbx");
        String ascii = """
                ; FBX 7.4.0 project file
                ; Copyright (C) 1997-2010 Autodesk

                FBXHeaderExtension:  {
                        FBXVersion: 7400
                }
                Objects:  {
                        Model:  "Model::Cube", "Mesh" {
                                Vertices: 24
                        }
                }
                """;
        Files.writeString(p, ascii, StandardCharsets.UTF_8);

        assertTrue(handler.canHandle(p.toFile()));
        Document doc = handler.parse(p.toFile());
        assertEquals("ascii", doc.getMetadata().get("encoding"));
        assertEquals("7.4.0", doc.getMetadata().get("version"));
        assertTrue((int) doc.getMetadata().get("topLevelNodeCount") >= 2,
                "FBXHeaderExtension + Objects");
    }

    @Test
    void rejectsNonFbx(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.fbx");
        Files.writeString(p, "hello world", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.fbx");
        byte[] head = "Kaydara FBX Binary  ".getBytes(StandardCharsets.US_ASCII);
        Files.write(p, head);
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.fbx").toFile()));
    }
}
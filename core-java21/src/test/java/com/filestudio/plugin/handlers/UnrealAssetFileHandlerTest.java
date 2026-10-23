package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UnrealAssetFileHandlerTest {

    private final UnrealAssetFileHandler handler = new UnrealAssetFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("uasset", handler.getExtension());
        assertEquals("application/x-uasset", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesVersionedPackage(@TempDir Path dir) throws IOException {
        // 魔数 0x9E2A83C1（LE）= C1 83 2A 9E；offset8 版本号 > 0
        byte[] uasset = new byte[16];
        uasset[0] = (byte) 0xC1; uasset[1] = (byte) 0x83;
        uasset[2] = 0x2A; uasset[3] = (byte) 0x9E;
        writeLeInt(uasset, 8, 522); // UE 4.26 包版本
        Path p = dir.resolve("a.uasset");
        Files.write(p, uasset);

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("versioned"));
        assertEquals(522, doc.getMetadata().get("packageVersion"));
    }

    @Test
    void parsesUnversionedPackage(@TempDir Path dir) throws IOException {
        // 正式版常用 unversioned：offset8 = -4
        byte[] uasset = new byte[16];
        uasset[0] = (byte) 0xC1; uasset[1] = (byte) 0x83;
        uasset[2] = 0x2A; uasset[3] = (byte) 0x9E;
        writeLeInt(uasset, 8, -4);
        Path p = dir.resolve("ship.uasset");
        Files.write(p, uasset);

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(false, doc.getMetadata().get("versioned"));
    }

    @Test
    void rejectsNonUasset(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.uasset");
        Files.writeString(p, "not unreal", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.uasset");
        Files.write(p, new byte[]{(byte) 0xC1, (byte) 0x83, 0x2A, (byte) 0x9E});
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.uasset").toFile()));
    }

    private static void writeLeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
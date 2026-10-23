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

class PakFileHandlerTest {

    private final PakFileHandler handler = new PakFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("pak", handler.getExtension());
        assertEquals("application/x-pak", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesPakHeader(@TempDir Path dir) throws IOException {
        // magic 0x5A6F12E1（LE）= E1 12 6F 5A；version 9；indexOffset/indexSize
        byte[] pak = new byte[24];
        writeLeInt(pak, 0, 0x5A6F12E1);
        writeLeInt(pak, 4, 9);
        writeLeLong(pak, 8, 4096);   // indexOffset
        writeLeLong(pak, 16, 2048);  // indexSize
        Path p = dir.resolve("Game.pak");
        Files.write(p, pak);

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(9, doc.getMetadata().get("version"));
        assertEquals(4096L, doc.getMetadata().get("indexOffset"));
        assertEquals(2048L, doc.getMetadata().get("indexSize"));
    }

    @Test
    void warnsOnImplausibleVersion(@TempDir Path dir) throws IOException {
        byte[] pak = new byte[24];
        writeLeInt(pak, 0, 0x5A6F12E1);
        writeLeInt(pak, 4, 99);
        Path p = dir.resolve("odd.pak");
        Files.write(p, pak);

        Document doc = handler.parse(p.toFile());
        assertNotNull(doc.getMetadata().get("versionWarning"));
    }

    @Test
    void rejectsNonPak(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.pak");
        Files.writeString(p, "not a pak", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        byte[] pak = new byte[24];
        writeLeInt(pak, 0, 0x5A6F12E1);
        Path p = dir.resolve("r.pak");
        Files.write(p, pak);
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.pak").toFile()));
    }

    private static void writeLeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeLeLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) ((v >> (8 * i)) & 0xFF);
        }
    }
}
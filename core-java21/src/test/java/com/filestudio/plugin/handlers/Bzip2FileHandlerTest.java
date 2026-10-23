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

class Bzip2FileHandlerTest {

    private final Bzip2FileHandler handler = new Bzip2FileHandler();

    @Test
    void declaresFormat() {
        assertEquals("bz2", handler.getExtension());
        assertEquals("application/x-bzip2", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void parsesValidHeader(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("data.bz2");
        // BZh9 + 排序块魔数 0x177245385090（非随机）
        Files.write(p, new byte[]{'B', 'Z', 'h', '9',
                (byte) 0x17, 0x72, 0x45, 0x38, 0x50, (byte) 0x90});

        Document doc = handler.parse(p.toFile());

        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(9, doc.getMetadata().get("compressionLevel"));
        assertEquals(9, doc.getMetadata().get("blockSize100k"));
        assertEquals(false, doc.getMetadata().get("randomized"), "0x177245385090 = 排序块（非随机）");
    }

    @Test
    void parsesOrderedBlockMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("data.bz2");
        // 0x314159265359 = 随机初始化块
        byte[] head = {'B', 'Z', 'h', '6',
                (byte) 0x31, 0x41, 0x59, 0x26, 0x53, 0x59};
        Files.write(p, head);

        Document doc = handler.parse(p.toFile());

        assertEquals(6, doc.getMetadata().get("compressionLevel"));
        assertEquals(true, doc.getMetadata().get("randomized"));
    }

    @Test
    void rejectsMissingMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.bz2");
        Files.writeString(p, "BZh 9 not bzip", java.nio.charset.StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());

        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void canHandleByContentAndExtension() throws IOException {
        assertTrue(handler.canHandle(new java.io.File("a.bz2")));
        assertTrue(handler.canHandle(new java.io.File("a.tbz2")));
        assertFalse(handler.canHandle(new java.io.File("a.gz")));
    }

    @Test
    void contentSniffingWorks(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        Files.write(p, new byte[]{'B', 'Z', 'h', '5'});
        assertTrue(handler.canHandle(p.toFile()));

        Path bad = dir.resolve("bad");
        Files.writeString(bad, "not-bzip", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(bad.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.bz2");
        Files.write(p, new byte[]{'B', 'Z', 'h', '1'});
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.bz2").toFile()));
    }
}
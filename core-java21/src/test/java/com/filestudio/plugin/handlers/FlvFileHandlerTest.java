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

class FlvFileHandlerTest {

    private final FlvFileHandler handler = new FlvFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("flv", handler.getExtension());
        assertEquals("video/x-flv", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesHeaderFlags(@TempDir Path dir) throws IOException {
        // FLV(3) + version(1) + flags(1) + headerSize(4) + 一个 tag 头
        byte[] flv = new byte[9 + 16];
        flv[0] = 'F'; flv[1] = 'L'; flv[2] = 'V';
        flv[3] = 1;             // version
        flv[4] = 0x05;          // audio(1) + video(4)
        flv[5] = 0; flv[6] = 0; flv[7] = 0; flv[8] = 9;  // headerSize=9
        flv[9] = 9;             // video tag
        flv[10] = 0; flv[11] = 0; flv[12] = 1; // dataSize = 1 (简化)
        Path p = dir.resolve("clip.flv");
        Files.write(p, flv);

        Document doc = handler.parse(p.toFile());
        assertEquals("flv", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("hasAudio"));
        assertEquals(true, doc.getMetadata().get("hasVideo"));
        assertEquals(1, doc.getMetadata().get("containerVersion"));
        assertEquals("video", doc.getMetadata().get("firstTagType"));
    }

    @Test
    void rejectsNonFlv(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.flv");
        Files.writeString(p, "not a flash video", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.flv")));
        assertFalse(handler.canHandle(new java.io.File("b.mp4")));
    }
}
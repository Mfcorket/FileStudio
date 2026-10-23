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

class WmvFileHandlerTest {

    private final WmvFileHandler handler = new WmvFileHandler();

    /** ASF 头对象 GUID：3026B275-8E66-CF11-A6D9-00AA0062CE6C */
    private static final byte[] HEADER_GUID = {
            0x30, 0x26, (byte) 0xB2, 0x75, (byte) 0x8E, 0x66, (byte) 0xCF, 0x11,
            (byte) 0xA6, (byte) 0xD9, 0x00, (byte) 0xAA, 0x00, 0x62, (byte) 0xCE, 0x6C};

    /** File Properties Object GUID：8CABDCA1-A947-11CF-8EE4-00C00C205365 */
    private static final byte[] FILE_PROP = {
            (byte) 0xA1, (byte) 0xDC, (byte) 0xAB, (byte) 0x8C, 0x47, (byte) 0xA9, (byte) 0xCF, 0x11,
            (byte) 0x8E, (byte) 0xE4, 0x00, (byte) 0xC0, 0x0C, 0x20, 0x53, 0x65};

    private static final byte[] AUDIO_MEDIA = {
            0x40, (byte) 0x9E, 0x69, (byte) 0xF8, 0x4D, 0x5B, (byte) 0xCF, 0x11,
            (byte) 0xA8, (byte) 0xFD, 0x00, (byte) 0x80, 0x5F, 0x5C, 0x44, 0x2B};

    private static final byte[] STREAM_PROP = {
            (byte) 0x91, 0x07, (byte) 0xDC, (byte) 0xB7, (byte) 0xB7, (byte) 0xA9, (byte) 0xCF, 0x11,
            (byte) 0x8E, (byte) 0xE6, 0x00, (byte) 0xC0, 0x0C, 0x20, 0x53, 0x65};

    @Test
    void declaresFormat() {
        assertEquals("wmv", handler.getExtension());
        assertEquals("video/x-ms-wmv", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesFileProperties(@TempDir Path dir) throws IOException {
        // 头对象 + File Properties 子对象（时长 60s = 6e8 个 100ns）
        byte[] wmv = new byte[30 + 24 + 80];
        System.arraycopy(HEADER_GUID, 0, wmv, 0, 16);
        writeLeLong(wmv, 16, 30 + 24 + 80);   // header size
        writeLeInt(wmv, 24, 2);                // 对象数
        // File Properties 对象
        int fp = 30;
        System.arraycopy(FILE_PROP, 0, wmv, fp, 16);
        writeLeLong(wmv, fp + 16, 24 + 80);    // 对象大小
        writeLeLong(wmv, fp + 24 + 16, 1234);  // file size
        writeLeLong(wmv, fp + 24 + 40, 600_000_000L); // play duration = 60s
        writeLeLong(wmv, fp + 24 + 56, 0);     // preroll
        Files.write(dir.resolve("video.wmv"), wmv);

        Document doc = handler.parse(dir.resolve("video.wmv").toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(2, doc.getMetadata().get("objectCount"));
        assertEquals(1234L, doc.getMetadata().get("declaredSize"));
        assertEquals(60.0, (double) doc.getMetadata().get("durationSeconds"), 0.001);
        assertEquals("1:00", doc.getMetadata().get("duration"));
    }

    @Test
    void detectsAudioStreamGuid(@TempDir Path dir) throws IOException {
        // 头对象 + 一个 audio stream 属性对象
        byte[] wmv = new byte[30 + 24 + 24 + 16];
        System.arraycopy(HEADER_GUID, 0, wmv, 0, 16);
        writeLeLong(wmv, 16, 30 + 24 + 24 + 16);
        writeLeInt(wmv, 24, 1);
        int sp = 30;
        System.arraycopy(STREAM_PROP, 0, wmv, sp, 16);
        writeLeLong(wmv, sp + 16, 24 + 24 + 16);
        System.arraycopy(AUDIO_MEDIA, 0, wmv, sp + 24, 16);
        Files.write(dir.resolve("audio.wmv"), wmv);

        Document doc = handler.parse(dir.resolve("audio.wmv").toFile());
        assertEquals(true, doc.getMetadata().get("hasAudioStream"));
        assertEquals(false, doc.getMetadata().get("hasVideoStream"));
    }

    @Test
    void rejectsNonAsf(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.wmv");
        Files.writeString(p, "definitely not asf", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.wmv");
        Files.write(p, HEADER_GUID);
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.wmv").toFile()));
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
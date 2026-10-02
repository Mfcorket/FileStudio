package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VideoFileHandlerTest {

    private final VideoFileHandler handler = new VideoFileHandler();

    @Test
    void declaresVideoFormat() {
        assertEquals("mp4", handler.getExtension());
        assertEquals("video/mp4", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesMp4Metadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("video.mp4");
        // 构造 ftyp + moov(mvhd + tkhd) box
        byte[] mp4 = buildMp4(44100, 100, 1920, 1080);
        Files.write(p, mp4);

        Document doc = handler.parse(p.toFile());
        assertEquals("video/mp4", doc.getMimeType());
        assertEquals("mp4", doc.getMetadata().get("format"));
        assertEquals(1920, doc.getMetadata().get("width"));
        assertEquals(1080, doc.getMetadata().get("height"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesAviMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("video.avi");
        byte[] avi = buildAvi(30, 100); // 30fps, 100 frames
        Files.write(p, avi);

        Document doc = handler.parse(p.toFile());
        assertEquals("video/x-msvideo", doc.getMimeType());
        assertEquals("avi", doc.getMetadata().get("format"));
        assertEquals(3.33, (Double) doc.getMetadata().get("durationSeconds"), 0.1);
        assertEquals(30.0, (Double) doc.getMetadata().get("frameRate"), 0.1);
        assertEquals(1920, doc.getMetadata().get("width"));
        assertEquals(1080, doc.getMetadata().get("height"));
    }

    @Test
    void parsesMkvMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("video.mkv");
        // TimestampScale = 1,000,000 ns = 1ms（默认）；Duration = 5000 单位 → 5 秒
        Files.write(p, buildEbml("matroska", 1_000_000L, 5000f, 1920, 1080));

        Document doc = handler.parse(p.toFile());
        assertEquals("video/x-matroska", doc.getMimeType());
        assertEquals("mkv", doc.getMetadata().get("format"));
        assertEquals(5.0, (Double) doc.getMetadata().get("durationSeconds"), 0.01);
        assertEquals(1920, doc.getMetadata().get("width"));
        assertEquals(1080, doc.getMetadata().get("height"));
    }

    @Test
    void parsesWebmMetadataAndDetectsDocType(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("video.webm");
        // Duration = 2000 单位 × 1ms → 2 秒
        Files.write(p, buildEbml("webm", 1_000_000L, 2000f, 1280, 720));

        Document doc = handler.parse(p.toFile());
        assertEquals("video/webm", doc.getMimeType());
        assertEquals("webm", doc.getMetadata().get("format"));
        assertEquals(2.0, (Double) doc.getMetadata().get("durationSeconds"), 0.01);
        assertEquals(1280, doc.getMetadata().get("width"));
        assertEquals(720, doc.getMetadata().get("height"));
    }

    @Test
    void matroskaTimestampScaleIsHonored(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("scale.mkv");
        // TimestampScale = 1,000,000 ns（1ms）；Duration = 10 单位 → 0.01 秒
        Files.write(p, buildEbml("matroska", 1_000_000L, 10f, 640, 480));

        Document doc = handler.parse(p.toFile());
        assertEquals(0.01, (Double) doc.getMetadata().get("durationSeconds"), 0.0001);
    }

    @Test
    void rejectsContainersWithoutMetadataSupport(@TempDir Path dir) throws IOException {
        // FLV / WMV 已从 SUPPORTED 移除，避免只识别容器却报 metadata unavailable
        assertFalse(handler.canHandle(new java.io.File("a.flv")));
        assertFalse(handler.canHandle(new java.io.File("a.wmv")));

        Path flv = dir.resolve("a.flv");
        Files.write(flv, new byte[] {'F', 'L', 'V', 1, 0, 0, 0, 9, 0, 0, 0, 0});
        assertFalse(handler.canHandle(flv.toFile()));
    }

    @Test
    void canHandleByExtensionAndMagicBytes(@TempDir Path dir) throws IOException {
        assertTrue(handler.canHandle(new java.io.File("a.mp4")));
        assertTrue(handler.canHandle(new java.io.File("b.mov")));
        assertTrue(handler.canHandle(new java.io.File("c.avi")));
        assertFalse(handler.canHandle(new java.io.File("d.txt")));

        Path p = dir.resolve("noext");
        Files.write(p, buildMp4(44100, 100, 1920, 1080));
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void rejectsNonVideoContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.mp4");
        Files.writeString(p, "this is not a video file", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.mp4");
        Files.write(p, buildMp4(44100, 100, 1920, 1080));
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.mp4").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.mp4");
        Files.write(p, buildMp4(44100, 100, 1920, 1080));
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 合成文件构造 ----

    private static byte[] buildMp4(int timescale, int duration, int width, int height) {
        // mvhd box (version 0): timescale at payload offset 12, duration at 16
        byte[] mvhdPayload = new byte[28];
        mvhdPayload[0] = 0; // version 0
        putBe32(mvhdPayload, 12, timescale);
        putBe32(mvhdPayload, 16, duration);
        // tkhd box (version 0): width at payload offset 76, height at 80 (fixed 16.16)
        byte[] tkhdPayload = new byte[84];
        tkhdPayload[0] = 0; // version 0
        putBe32(tkhdPayload, 76, width << 16);
        putBe32(tkhdPayload, 80, height << 16);

        // moov = 完整子 box（含 size/type 头）
        byte[] moov = makeBox("moov", concat(makeBox("mvhd", mvhdPayload), makeBox("tkhd", tkhdPayload)));

        // ftyp box
        byte[] ftypData = new byte[8];
        ftypData[0] = 'M'; ftypData[1] = '4'; ftypData[2] = 'V'; ftypData[3] = ' ';
        byte[] ftyp = makeBox("ftyp", ftypData);

        return concat(ftyp, moov);
    }

    private static byte[] buildAvi(int fps, int totalFrames) {
        // avih chunk: dwMicroSecPerFrame at 0, dwTotalFrames at 16, dwWidth at 32, dwHeight at 36
        int microSecPerFrame = (int) (1000000.0 / fps);
        byte[] avihPayload = new byte[56];
        putLe32(avihPayload, 0, microSecPerFrame);
        putLe32(avihPayload, 16, totalFrames);
        putLe32(avihPayload, 24, 1);      // dwStreams
        putLe32(avihPayload, 32, 1920);   // dwWidth
        putLe32(avihPayload, 36, 1080);   // dwHeight
        byte[] avih = makeChunk("avih", avihPayload);

        // LIST "hdrl" 内含 avih（真实 AVI 结构）
        byte[] hdrl = makeList("hdrl", avih);
        // LIST "movi" 至少要有列表类型
        byte[] movi = makeList("movi", new byte[0]);
        byte[] idx1 = makeChunk("idx1", new byte[0]);

        byte[] body = concat(hdrl, movi, idx1);
        byte[] riff = new byte[12 + body.length];
        riff[0] = 'R'; riff[1] = 'I'; riff[2] = 'F'; riff[3] = 'F';
        putLe32(riff, 4, 4 + body.length);
        riff[8] = 'A'; riff[9] = 'V'; riff[10] = 'I'; riff[11] = ' ';
        System.arraycopy(body, 0, riff, 12, body.length);
        return riff;
    }

    /**
     * 构造最小 EBML（Matroska/WebM）文件。
     *
     * <p>结构：EBML header(含 DocType) → Segment → {Info, Tracks}。
     */
    private static byte[] buildEbml(String docType, long timestampScale, float duration,
                                    int width, int height) {
        // Info: TimestampScale(0x2AD7B1) + Duration(0x4489, 4 字节 float)
        byte[] ts = ebmlElement(0x2AD7B1L, bigEndian(timestampScale));
        byte[] durPayload = new byte[4];
        int bits = Float.floatToIntBits(duration);
        durPayload[0] = (byte) (bits >> 24);
        durPayload[1] = (byte) (bits >> 16);
        durPayload[2] = (byte) (bits >> 8);
        durPayload[3] = (byte) bits;
        byte[] dur = ebmlElement(0x4489L, durPayload);
        byte[] info = ebmlElement(0x1549A966L, concat(ts, dur));

        // Tracks → TrackEntry → Video → PixelWidth / PixelHeight
        byte[] video = ebmlElement(0xE0L, concat(
                ebmlElement(0xB0L, bigEndian((long) width)),
                ebmlElement(0xBAL, bigEndian((long) height))));
        byte[] trackEntry = ebmlElement(0xAEL, video);
        byte[] tracks = ebmlElement(0x1654AE6BL, trackEntry);

        byte[] segment = ebmlElement(0x18538067L, concat(info, tracks));

        // EBML header：EBMLVersion(0x4286) + DocType(0x4282)
        byte[] header = ebmlElement(0x1A45DFA3L, concat(
                ebmlElement(0x4286L, new byte[] {1}),
                ebmlElement(0x4282L, docType.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));

        return concat(header, segment);
    }

    /** 最小宽度 big-endian 编码。 */
    private static byte[] bigEndian(long value) {
        int len = 1;
        for (long v = value; (v >>> 8) != 0; v >>>= 8) len++;
        byte[] out = new byte[len];
        for (int i = len - 1; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    /** 编码 EBML 元素：id（含标记位，原样写入）+ 长度 + 负载。 */
    private static byte[] ebmlElement(long id, byte[] data) {
        byte[] idBytes = bigEndian(id);
        byte[] sizeBytes = ebmlSize(data.length);
        return concat(idBytes, sizeBytes, data);
    }

    /** 编码 EBML 长度字段：1 字节能放下就用 1 字节，否则用 4 字节。 */
    private static byte[] ebmlSize(int size) {
        if (size < 0x7F) {
            return new byte[] {(byte) (0x80 | size)};
        }
        return new byte[] {
                (byte) (0x10 | ((size >> 24) & 0x3F)),
                (byte) (size >> 16),
                (byte) (size >> 8),
                (byte) size
        };
    }

    private static byte[] concat(byte[]... parts) {        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static void putBe32(byte[] buf, int off, int value) {
        buf[off] = (byte) ((value >> 24) & 0xFF);
        buf[off + 1] = (byte) ((value >> 16) & 0xFF);
        buf[off + 2] = (byte) ((value >> 8) & 0xFF);
        buf[off + 3] = (byte) (value & 0xFF);
    }

    private static void putLe32(byte[] buf, int off, int value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /** RIFF chunk 布局：id 在前，size 在后（与 MP4 box 相反）。 */
    private static byte[] makeChunk(String type, byte[] data) {
        byte[] chunk = new byte[8 + data.length];
        chunk[0] = (byte) type.charAt(0);
        chunk[1] = (byte) type.charAt(1);
        chunk[2] = (byte) type.charAt(2);
        chunk[3] = (byte) type.charAt(3);
        putLe32(chunk, 4, data.length + 8);
        System.arraycopy(data, 0, chunk, 8, data.length);
        return chunk;
    }

    private static byte[] makeBox(String type, byte[] data) {
        byte[] box = new byte[8 + data.length];
        box[0] = (byte) ((data.length + 8) >> 24);
        box[1] = (byte) ((data.length + 8) >> 16);
        box[2] = (byte) ((data.length + 8) >> 8);
        box[3] = (byte) (data.length + 8);
        box[4] = (byte) type.charAt(0);
        box[5] = (byte) type.charAt(1);
        box[6] = (byte) type.charAt(2);
        box[7] = (byte) type.charAt(3);
        System.arraycopy(data, 0, box, 8, data.length);
        return box;
    }

    private static byte[] makeList(String type, byte[] data) {
        byte[] list = new byte[12 + data.length];
        list[0] = 'L'; list[1] = 'I'; list[2] = 'S'; list[3] = 'T';
        putLe32(list, 4, 4 + data.length);
        list[8] = (byte) type.charAt(0);
        list[9] = (byte) type.charAt(1);
        list[10] = (byte) type.charAt(2);
        list[11] = (byte) type.charAt(3);
        System.arraycopy(data, 0, list, 12, data.length);
        return list;
    }
}

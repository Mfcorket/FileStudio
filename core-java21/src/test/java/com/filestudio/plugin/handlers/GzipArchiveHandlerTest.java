package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GzipArchiveHandlerTest {

    private final GzipArchiveHandler handler = new GzipArchiveHandler();

    @Test
    void declaresFormat() {
        assertEquals("gz", handler.getExtension());
        assertEquals("application/gzip", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    /** 用 JDK 自身生成真实 gzip，验证解压完整性校验与大小统计。 */
    @Test
    void parsesRealGzip(@TempDir Path dir) throws IOException {
        byte[] payload = "hello gzip world, ".repeat(200).getBytes(StandardCharsets.UTF_8);
        Path p = dir.resolve("data.gz");
        Files.write(p, gzipWithJdk(payload));

        Document doc = handler.parse(p.toFile());
        assertEquals("application/gzip", doc.getMimeType());
        assertEquals("gz", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("deflate", doc.getMetadata().get("compressionMethod"));
        assertEquals("ok", doc.getMetadata().get("integrity"));
        assertEquals((long) payload.length, doc.getMetadata().get("uncompressedSize"));
        assertEquals((long) payload.length, doc.getMetadata().get("actualUncompressedSize"));
    }

    /** 手工构造带 FNAME 的头部，验证变长字段解析。 */
    @Test
    void parsesOriginalFileName(@TempDir Path dir) throws IOException {
        byte[] payload = "small".getBytes(StandardCharsets.UTF_8);
        Path p = dir.resolve("named.gz");
        Files.write(p, gzipWithName("report.txt", payload));

        Document doc = handler.parse(p.toFile());
        assertEquals("report.txt", doc.getMetadata().get("originalName"));
        assertTrue(((String) doc.getMetadata().get("flags")).contains("NAME"));
        assertEquals("ok", doc.getMetadata().get("integrity"));
    }

    @Test
    void detectsCorruptedStream(@TempDir Path dir) throws IOException {
        byte[] good = gzipWithJdk("payload".getBytes(StandardCharsets.UTF_8));
        // 破坏 deflate 数据区（跳过 12 字节头部）
        good[14] ^= 0xFF;
        good[15] ^= 0xFF;
        Path p = dir.resolve("bad.gz");
        Files.write(p, good);

        Document doc = handler.parse(p.toFile());
        // 头部仍可解析，但完整性校验应失败
        assertEquals(true, doc.getMetadata().get("valid"));
        String integrity = (String) doc.getMetadata().get("integrity");
        assertTrue(integrity.startsWith("failed"), "实际为: " + integrity);
    }

    @Test
    void rejectsNonGzipContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.gz");
        Files.writeString(p, "this is not gzip", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.gz")));
        assertTrue(handler.canHandle(new java.io.File("b.tgz")));
        assertFalse(handler.canHandle(new java.io.File("c.zip")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("d.gz");
        Files.write(p, gzipWithJdk("x".getBytes(StandardCharsets.UTF_8)));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.gz").toFile()));
    }

    // ---- 辅助 ----

    private static byte[] gzipWithJdk(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(payload);
        }
        return out.toByteArray();
    }

    /** 手工构造带 FNAME 标志的 gzip：头部 + deflate 数据 + CRC32 + ISIZE。 */
    private static byte[] gzipWithName(String name, byte[] payload) throws IOException {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(payload);
        deflater.finish();
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            deflated.write(buf, 0, n);
        }
        deflater.end();

        byte[] nameBytes = name.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1F); out.write(0x8B);   // 魔数
        out.write(8);                        // CM = deflate
        out.write(0x08);                     // FLG = FNAME
        writeLe32(out, 1_700_000_000L);      // MTIME
        out.write(0);                        // XFL
        out.write(3);                        // OS = Unix
        out.write(nameBytes);
        out.write(0);                        // NUL 终止
        out.write(deflated.toByteArray());
        writeLe32(out, crc32(payload));
        writeLe32(out, payload.length & 0xFFFFFFFFL);
        return out.toByteArray();
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void writeLe32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }
}

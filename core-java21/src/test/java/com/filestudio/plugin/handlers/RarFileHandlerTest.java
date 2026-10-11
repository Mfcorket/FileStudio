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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RarFileHandlerTest {

    private final RarFileHandler handler = new RarFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("rar", handler.getExtension());
        assertEquals("application/vnd.rar", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    // ---- RAR5 ----

    @Test
    void readsRar5Entries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.rar");
        Files.write(p, buildRar5());

        assertTrue(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals("rar", m.get("format"));
        assertEquals(5, m.get("rarVersion"));
        assertEquals(2, m.get("entryCount"));
        assertEquals(1, m.get("directoryCount"));
        assertEquals(true, m.get("hasEndBlock"));
        assertEquals(true, m.get("complete"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        assertNotNull(entries);
        assertEquals("dir", entries.get(0).get("name"));
        assertEquals(true, entries.get(0).get("directory"));
        assertEquals("readme.txt", entries.get(1).get("name"));
        assertEquals(false, entries.get(1).get("directory"));
        assertEquals(2048L, entries.get(1).get("unpackedSize"));
        assertEquals(1024L, entries.get(1).get("packedSize"));
    }

    @Test
    void decodesRar5Varints(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.rar");
        // 大于 127 的长度必须走多字节 varint
        byte[] rar = buildRar5WithSize(100_000);
        Files.write(p, rar);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(100_000L, entries.get(0).get("unpackedSize"));
    }

    @Test
    void detectsEncryptedArchive(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("enc.rar");
        Files.write(p, buildRar5WithEncryption());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals(true, m.get("encrypted"));
    }

    // ---- RAR4 ----

    @Test
    void readsRar4Entries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("old.rar");
        Files.write(p, buildRar4());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals(4, m.get("rarVersion"));
        assertEquals(2, m.get("entryCount"));
        assertEquals(true, m.get("complete"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        assertNotNull(entries);
        assertEquals("folder", entries.get(0).get("name"));
        assertEquals(true, entries.get(0).get("directory"));
        assertEquals("setup.exe", entries.get(1).get("name"));
        assertEquals(4096L, entries.get(1).get("packedSize"));
        assertEquals(16384L, entries.get(1).get("unpackedSize"));
        assertEquals("Windows", entries.get(1).get("hostOs"));
        assertEquals(29, entries.get(1).get("compressionMethod"));
        assertEquals(false, entries.get(1).get("solid"));
    }

    @Test
    void decodesRar4DosTime(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("time.rar");
        Files.write(p, buildRar4());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        // 2021-06-05 12:30:00 的 DOS 时间
        assertEquals("2021-06-05T12:30:00", entries.get(1).get("modifiedTime"));
    }

    @Test
    void detectsRar4SolidAndEncrypted(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("solid.rar");
        Files.write(p, buildRar4WithFlags(true, true));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("encrypted"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        assertEquals(1, entries.size());
        assertEquals("secret.dat", entries.get(0).get("name"));
        assertEquals(true, entries.get(0).get("solid"));
        assertEquals(true, entries.get(0).get("encrypted"));
    }

    @Test
    void readsRar4LargeFileHighWords(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("huge.rar");
        Files.write(p, buildRar4Large());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        assertNotNull(entries);
        // 高位字非零时才启用 64 位长度
        assertEquals(5_000_000_000L, entries.get(0).get("unpackedSize"));
        assertEquals(1_000_000_000L, entries.get(0).get("packedSize"));
    }

    // ---- 错误处理 ----

    @Test
    void rejectsMissingMarker(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.rar");
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }
        Files.write(p, data);

        assertFalse(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("RAR marker"));
    }

    @Test
    void rejectsUnknownVersionByte(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v9.rar");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, (byte) 0x09, 0x00});
        Files.write(p, o.toByteArray());

        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void handlesTruncatedArchive(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("trunc.rar");
        byte[] full = buildRar5();
        byte[] cut = new byte[20];
        System.arraycopy(full, 0, cut, 0, 20);
        Files.write(p, cut);

        // 不应抛出异常
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertNotNull(m.get("error"));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.rar");
        Files.write(p, buildRar5());

        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.rar").toFile()));
    }

    @Test
    void getMetadataReportsValidity(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("good.rar");
        Files.write(good, buildRar5());
        assertEquals(true, handler.getMetadata(good.toFile()).get("valid"));
        assertTrue(handler.getMetadata(dir.resolve("nope.rar").toFile()).isEmpty());
    }

    // ---- RAR5 builders ----

    /** 写入 RAR5 的变长整数（无 zigzag）。 */
    private static void vInt(ByteArrayOutputStream o, long v) {
        while (true) {
            if ((v & ~0x7FL) == 0) {
                o.write((int) v);
                return;
            }
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
    }

    /** 写入 little-endian 32 位（RAR4 的定宽字段全部是小端）。 */
    private static void le32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 24) & 0xFF));
    }

    /** RAR5 marker：52 61 72 21 1A 07 01 00。 */
    private static void marker5(ByteArrayOutputStream o) throws IOException {
        o.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00});
    }

    /**
     * 组装一个 RAR5 块。
     *
     * @param type    块类型
     * @param flags   块标志
     * @param dataSize 数据区长度（设置 flags 的 DATA 位时会写入）
     * @param body    类型相关的负载
     */
    private static void block(ByteArrayOutputStream o, int type, int flags, long dataSize,
                              byte[] body) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        vInt(head, type);
        vInt(head, flags);
        if ((flags & 0x0001) != 0) {
            vInt(head, 0);                 // extra size
        }
        if ((flags & 0x0002) != 0) {
            vInt(head, dataSize);
        }
        head.write(body);

        byte[] headBytes = head.toByteArray();

        // CRC32 覆盖长度字段与其后的全部头部内容
        ByteArrayOutputStream lenBytes = new ByteArrayOutputStream();
        vInt(lenBytes, headBytes.length);

        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(lenBytes.toByteArray());
        crc.update(headBytes);
        le32(o, crc.getValue());
        o.write(lenBytes.toByteArray(), 0, lenBytes.size());
        o.write(headBytes);
        if (dataSize > 0) {
            o.write(new byte[(int) dataSize]);
        }
    }

    /** 文件块负载：fileFlags、unpSize、attributes、[mtime]、[crc32]、compInfo、hostOs、nameLen、name。 */
    private static byte[] fileBody5(long fileFlags, long unpSize, long attributes,
                                    Long crc32, Long time, String name) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        vInt(b, fileFlags);
        vInt(b, unpSize);
        vInt(b, attributes);
        // 规范顺序：mtime 在 CRC32 之前
        if (time != null) {
            le32(b, time);
        }
        if (crc32 != null) {
            le32(b, crc32);
        }
        vInt(b, 0x0003);                   // compression info: 50/50
        vInt(b, 0);                        // host OS: Windows
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        vInt(b, nb.length);
        b.write(nb);
        return b.toByteArray();
    }

    private static byte[] buildRar5() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        marker5(o);

        // Main archive header
        block(o, 1, 0, 0, vIntBytes(0));

        // Directory entry：fileFlags 里 DIR(0x1) | CRC(0x4) | TIME(0x2)
        block(o, 2, 0x0002, 0,
                fileBody5(0x0001 | 0x0004 | 0x0002, 0, 0x10, 0L, 1_623_400_000L, "dir"));

        // File entry
        block(o, 2, 0x0002, 1024,
                fileBody5(0x0004 | 0x0002, 2048, 0x20, 0x89ABCDEFL, 1_623_400_000L, "readme.txt"));

        block(o, 5, 0, 0, new byte[0]);
        return o.toByteArray();
    }

    private static byte[] buildRar5WithSize(long size) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        marker5(o);
        block(o, 1, 0, 0, vIntBytes(0));
        block(o, 2, 0x0002, 10, fileBody5(0x0000, size, 0x20, null, null, "big.bin"));
        block(o, 5, 0, 0, new byte[0]);
        return o.toByteArray();
    }

    private static byte[] buildRar5WithEncryption() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        marker5(o);
        block(o, 1, 0, 0, vIntBytes(0));
        block(o, 4, 0, 16, new byte[16]);       // archive encryption header
        block(o, 5, 0, 0, new byte[0]);
        return o.toByteArray();
    }

    private static byte[] vIntBytes(long v) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        vInt(o, v);
        return o.toByteArray();
    }

    // ---- RAR4 builders ----

    /** RAR4 marker：52 61 72 21 1A 07 00。 */
    private static void marker4(ByteArrayOutputStream o) throws IOException {
        o.write(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00});
    }

    /** DOS 时间编码。 */
    private static long dosDateTime(int year, int month, int day, int hour, int minute, int second) {
        return ((long) (year - 1980) << 25) | ((long) month << 21)
                | ((long) day << 16) | ((long) hour << 11)
                | ((long) minute << 5) | (second / 2);
    }

    /**
     * RAR4 文件块：定长 32 字节基础头 + 名称。
     */
    private static void block4File(ByteArrayOutputStream o, String name, boolean directory,
                                   long packSize, long unpSize, int hostOs, long crc,
                                   long time, int method, long attr, int flags)
            throws IOException {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        int headSize = 32 + nb.length;
        if (flags == -1) {
            flags = directory ? 0x00E0 : 0x8000;
        }
        o.write(0x34);                                    // HEAD_CRC（低字节）
        o.write(0x12);
        o.write(0x74);                                    // 类型 = 文件头
        o.write(flags & 0xFF);                           // HEAD_FLAGS（小端）
        o.write((flags >> 8) & 0xFF);
        o.write(headSize & 0xFF);                        // HEAD_SIZE（小端）
        o.write((headSize >> 8) & 0xFF);
        le32(o, packSize);
        le32(o, unpSize);
        o.write(hostOs);
        le32(o, crc);
        le32(o, time);
        o.write(29);                                     // unpVer
        o.write(method);
        o.write(nb.length & 0xFF);                       // NAME_SIZE（小端）
        o.write((nb.length >> 8) & 0xFF);
        le32(o, attr);
        o.write(nb);
    }

    private static void block4End(ByteArrayOutputStream o) throws IOException {
        o.write(0xC4);
        o.write(0x3D);          // HEAD_CRC
        o.write(0x7B);          // 类型 = 结束块
        o.write(0x00);
        o.write(0x00);          // HEAD_FLAGS
        o.write(7);             // HEAD_SIZE（小端）
        o.write(0x00);
    }

    private static void block4Main(ByteArrayOutputStream o) throws IOException {
        // HEAD_CRC(2) TYPE(1) FLAGS(2) SIZE(2) + HighPosAV(2) + PosAV(4) = 13
        o.write(0xCF);
        o.write(0x90);          // HEAD_CRC
        o.write(0x73);          // 类型 = 主归档头
        o.write(0x00);          // HEAD_FLAGS（小端）
        o.write(0x00);
        o.write(13);            // HEAD_SIZE（小端）
        o.write(0x00);
        o.write(0);             // HighPosAV
        o.write(0);
        o.write(0);             // PosAV（保留）
        o.write(0);
        o.write(0);
        o.write(0);
    }

    private static byte[] buildRar4() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        marker4(o);
        block4Main(o);
        long t = dosDateTime(2021, 6, 5, 12, 30, 0);
        block4File(o, "folder", true, 0, 0, 2, 0L, t, 0x30, 0x10, -1);
        block4File(o, "setup.exe", false, 4096, 16384, 2, 0x12345678L, t, 29, 0x20, -1);
        block4End(o);
        return o.toByteArray();
    }

    private static byte[] buildRar4WithFlags(boolean solid, boolean encrypted) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        marker4(o);
        block4Main(o);
        int flags = 0x8000;
        if (solid) {
            flags |= 0x0010;
        }
        if (encrypted) {
            flags |= 0x0004;
        }
        // packSize 置 0：否则 ADD_SIZE 会让解析器跳过文件末尾，夹具无需附带 2KB 数据
        block4File(o, "secret.dat", false, 0, 4096, 2, 0L, 0L, 29, 0x20, flags);
        block4End(o);
        return o.toByteArray();
    }

    /** 超过 4GB 的文件：头长扩展 8 字节存放高低位。 */
    private static byte[] buildRar4Large() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        marker4(o);
        block4Main(o);
        byte[] nb = "huge.bin".getBytes(StandardCharsets.UTF_8);
        int headSize = 40 + nb.length;
        int flags = 0x8000 | 0x0100;          // ADD_SIZE | 64 位长度
        o.write(0x34);
        o.write(0x12);
        o.write(0x74);
        o.write(flags & 0xFF);
        o.write((flags >> 8) & 0xFF);
        o.write(headSize & 0xFF);
        o.write((headSize >> 8) & 0xFF);
        le32(o, 1_000_000_000L & 0xFFFFFFFFL);     // packSize 低位
        le32(o, 5_000_000_000L & 0xFFFFFFFFL);     // unpSize 低位
        o.write(2);
        le32(o, 0x89ABCDEFL);
        le32(o, 0);
        o.write(29);
        o.write(29);
        o.write(nb.length & 0xFF);
        o.write((nb.length >> 8) & 0xFF);
        le32(o, 0x20);
        le32(o, 1_000_000_000L >>> 32);            // highPackSize
        le32(o, 5_000_000_000L >>> 32);            // highUnpSize
        o.write(nb);
        // packSize 占 1GB 太大，这里截断：解析器会因越界停止，但仍应读出头部
        block4End(o);
        return o.toByteArray();
    }
}
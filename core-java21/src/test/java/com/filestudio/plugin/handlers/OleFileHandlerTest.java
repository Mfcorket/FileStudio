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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OleFileHandlerTest {

    private final OleFileHandler handler = new OleFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("ole", handler.getExtension());
        assertEquals("application/x-ole-storage", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void readsHeaderAndDirectory(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("doc.doc");
        Files.write(p, buildOle(new String[]{"WordDocument", "\u0005SummaryInformation"},
                new int[]{4096, 512}));

        assertTrue(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals("ole", m.get("format"));
        assertEquals(3, m.get("majorVersion"));
        assertEquals(512, m.get("sectorSize"));
        assertEquals(64, m.get("miniSectorSize"));
        assertEquals(4096, m.get("miniStreamCutoff"));
        assertEquals(2, m.get("streamCount"));
        assertEquals("Microsoft Word 97-2003", m.get("documentKind"));
        assertEquals(true, m.get("hasSummaryInformation"));

        @SuppressWarnings("unchecked")
        List<String> streams = (List<String>) m.get("streams");
        assertTrue(streams.contains("WordDocument"));
        assertTrue(streams.contains("\u0005SummaryInformation"));
    }

    @Test
    void reportsEntryDetails(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.xls");
        Files.write(p, buildOle(new String[]{"Workbook", "Book"}, new int[]{8192, 1024}));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("Microsoft Excel 97-2003", m.get("documentKind"));
        assertEquals(2, m.get("entryCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertEquals("Workbook", entries.get(0).get("name"));
        assertEquals("stream", entries.get(0).get("type"));
        assertEquals(8192L, entries.get(0).get("size"));
    }

    @Test
    void handlesStorageEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("s.ole");
        Files.write(p, buildOle(new String[]{"ObjectPool", "Contents"},
                new int[]{0, 2048}, new int[]{1, 2}));   // 1 = storage

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(1, m.get("storageCount"));
        assertEquals(1, m.get("streamCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        boolean sawStorage = false;
        for (Map<String, Object> e : entries) {
            if ("storage".equals(e.get("type"))) {
                sawStorage = true;
                assertEquals("ObjectPool", e.get("name"));
            }
        }
        assertTrue(sawStorage);
    }

    @Test
    void detectsPowerPoint(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("p.ppt");
        Files.write(p, buildOle(new String[]{"PowerPoint Document", "Current User"},
                new int[]{20480, 128}));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("Microsoft PowerPoint 97-2003", m.get("documentKind"));
    }

    @Test
    void handlesEmptyDirectory(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.ole");
        Files.write(p, buildOle(new String[0], new int[0]));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals(0, m.get("streamCount"));
        assertNull(m.get("documentKind"));
    }

    @Test
    void rejectsMissingSignature(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.ole");
        byte[] data = new byte[1024];
        data[0] = 0x00;
        Files.write(p, data);

        assertFalse(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("signature"));
    }

    @Test
    void rejectsTooSmallFile(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("tiny.ole");
        Files.write(p, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});

        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void rejectsBadSectorSize(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("weird.ole");
        byte[] data = buildOle(new String[]{"A"}, new int[]{10});
        // 扇区大小改成一个非法值
        data[0x1E] = 0x07;
        data[0x1F] = 0x00;
        Files.write(p, data);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("sector size"));
    }

    @Test
    void survivesCircularDirectoryLinks(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("loop.ole");
        Files.write(p, buildOle(new String[]{"A", "B"}, new int[]{10, 20}));

        // 构造完成后人为把第一个流条目的 right 指向自己
        byte[] data = Files.readAllBytes(p);
        int dirSectorOffset = 512;                 // 目录放在扇区 0
        int firstEntry = dirSectorOffset + 128;    // 跳过根目录项
        data[firstEntry + 0x48] = (byte) 0x01;     // right = 1（自身）
        data[firstEntry + 0x49] = 0x00;
        data[firstEntry + 0x4A] = 0x00;
        data[firstEntry + 0x4B] = 0x00;
        Files.write(p, data);

        // 必须正常返回而不是死循环或抛异常
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("d.doc");
        Files.write(p, buildOle(new String[]{"WordDocument"}, new int[]{100}));

        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.doc").toFile()));
    }

    @Test
    void getMetadataReportsValidity(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("good.ole");
        Files.write(good, buildOle(new String[]{"WordDocument"}, new int[]{10}));
        assertEquals(true, handler.getMetadata(good.toFile()).get("valid"));

        assertTrue(handler.getMetadata(dir.resolve("nope.ole").toFile()).isEmpty());
    }

    // ---- builder ----

    private static byte[] buildOle(String[] names, int[] sizes) throws IOException {
        int[] types = new int[names.length];
        java.util.Arrays.fill(types, 2);          // 全部为 stream
        return buildOle(names, sizes, types);
    }

    /**
     * 构造最小合法 CFB 文件。
     *
     * <p>布局：扇区 0 = 目录，扇区 1 = FAT，扇区 2 = 数据。
     * 目录项：0 = 根（child 指向 1），之后依次是各个条目，右倾链 1 → 2 → …。
     *
     * @param types 每项的对象类型：1 = storage，2 = stream
     */
    private static byte[] buildOle(String[] names, int[] sizes, int[] types) throws IOException {
        final int sectorSize = 512;

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] header = new byte[sectorSize];
        writeHeader(header);
        o.write(header);

        // 扇区 0：目录
        byte[] dirSector = new byte[sectorSize];
        writeRootEntry(dirSector, 0, names.length == 0 ? 0xFFFFFFFF : 1);
        for (int i = 0; i < Math.min(2, names.length); i++) {
            int off = 128 + i * 128;
            writeEntry(dirSector, off, names[i], sizes[i], 2, types[i]);
            // 形成右倾链：1 -> 2 -> -1
            if (i == 0 && names.length > 1) {
                putLe32(dirSector, off + 0x48, 2);
            }
        }
        o.write(dirSector);

        // 扇区 1：FAT
        byte[] fatSector = new byte[sectorSize];
        for (int i = 0; i < sectorSize / 4; i++) {
            putLe32(fatSector, i * 4, 0xFFFFFFFF);
        }
        putLe32(fatSector, 0 * 4, 0xFFFFFFFE);   // 扇区 0：目录链结束
        putLe32(fatSector, 1 * 4, 0xFFFFFFFD);   // 扇区 1：FAT 自身
        putLe32(fatSector, 2 * 4, 0xFFFFFFFE);   // 扇区 2：数据链结束
        o.write(fatSector);

        // 扇区 2：数据
        o.write(new byte[sectorSize]);

        return o.toByteArray();
    }

    private static void writeHeader(byte[] h) {
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, h, 0, 8);
        putLe16(h, 0x18, 0x003E);       // minor version
        putLe16(h, 0x1A, 0x0003);       // major version 3
        putLe16(h, 0x1C, 0xFFFE);       // byte order
        putLe16(h, 0x1E, 9);            // sector shift = 512
        putLe16(h, 0x20, 6);            // mini sector shift = 64
        putLe32(h, 0x28, 0);            // directory sector count (v3 为 0)
        putLe32(h, 0x2C, 1);            // FAT sector count
        putLe32(h, 0x30, 0);            // first directory sector = 0
        putLe32(h, 0x34, 0);            // transaction signature
        putLe32(h, 0x38, 4096);         // mini stream cutoff
        putLe32(h, 0x3C, 0xFFFFFFFE);   // first mini FAT sector
        putLe32(h, 0x40, 0);            // mini FAT sector count
        putLe32(h, 0x44, 0xFFFFFFFE);   // first DIFAT sector
        putLe32(h, 0x48, 0);            // DIFAT sector count
        // DIFAT：第 0 项指向 FAT 扇区 1，其余为空
        putLe32(h, 0x4C, 1);
        for (int i = 1; i < 109; i++) {
            putLe32(h, 0x4C + i * 4, 0xFFFFFFFF);
        }
    }

    private static void writeRootEntry(byte[] d, int off, int child) {
        putLe16(d, off + 0x40, 12);     // "Root Entry" = 5 字符 → (5+1)*2 = 12
        writeName(d, off, "Root Entry");
        d[off + 0x42] = 5;              // type = root
        d[off + 0x43] = 1;              // color = black
        putLe32(d, off + 0x44, 0xFFFFFFFF);
        putLe32(d, off + 0x48, 0xFFFFFFFF);
        putLe32(d, off + 0x4C, child);
        putLe32(d, off + 0x74, 2);      // starting sector
        putLe32(d, off + 0x78, 0);
        putLe32(d, off + 0x7C, 0);
    }

    private static void writeEntry(byte[] d, int off, String name, int size, int startSector, int type) {
        int len = (name.length() + 1) * 2;
        putLe16(d, off + 0x40, len);
        writeName(d, off, name);
        d[off + 0x42] = (byte) type;
        d[off + 0x43] = 1;
        putLe32(d, off + 0x44, 0xFFFFFFFF);   // left = -1
        putLe32(d, off + 0x48, 0xFFFFFFFF);   // right = -1
        putLe32(d, off + 0x4C, 0xFFFFFFFF);   // child = -1
        putLe32(d, off + 0x74, startSector);
        putLe32(d, off + 0x78, size);
        putLe32(d, off + 0x7C, 0);
    }

    /** 写入 UTF-16LE 名称并补 NUL 终止符。 */
    private static void writeName(byte[] d, int off, String name) {
        byte[] b = name.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(b, 0, d, off, b.length);
        d[off + b.length] = 0;
        d[off + b.length + 1] = 0;
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }
}
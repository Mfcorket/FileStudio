package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FontFileHandlerTest {

    private final FontFileHandler handler = new FontFileHandler();

    /** 构造合成 TTF 文件。 */
    private static byte[] buildTtf(String fontName, String version, String psName,
                                   int unitsPerEm, int weight) {
        // name 表
        byte[] nameBytes = fontName.getBytes(StandardCharsets.UTF_16BE);
        byte[] versionBytes = version.getBytes(StandardCharsets.UTF_16BE);
        byte[] psNameBytes = psName.getBytes(StandardCharsets.UTF_16BE);

        int stringOffset = 6 + 3 * 12; // header + 3 records
        int nameLen = nameBytes.length;
        int versionRelOffset = nameLen; // 相对于 string storage
        int psRelOffset = versionRelOffset + versionBytes.length;

        byte[] nameTable = new byte[stringOffset + psRelOffset + psNameBytes.length];
        nameTable[0] = 0; nameTable[1] = 0; // format
        nameTable[2] = 0; nameTable[3] = 3; // count
        nameTable[4] = (byte) ((stringOffset >> 8) & 0xFF);
        nameTable[5] = (byte) (stringOffset & 0xFF);
        // Record 1: Font Name (ID 4) — record 从 offset 6 开始
        writeNameRecord(nameTable, 6, 4, nameBytes.length, 0);
        // Record 2: Version (ID 5)
        writeNameRecord(nameTable, 18, 5, versionBytes.length, versionRelOffset);
        // Record 3: PostScript Name (ID 6)
        writeNameRecord(nameTable, 30, 6, psNameBytes.length, psRelOffset);
        System.arraycopy(nameBytes, 0, nameTable, stringOffset, nameLen);
        System.arraycopy(versionBytes, 0, nameTable, stringOffset + versionRelOffset, versionBytes.length);
        System.arraycopy(psNameBytes, 0, nameTable, stringOffset + psRelOffset, psNameBytes.length);

        // head 表 (54 bytes, unitsPerEm at offset 18)
        byte[] headTable = new byte[54];
        headTable[18] = (byte) ((unitsPerEm >> 8) & 0xFF);
        headTable[19] = (byte) (unitsPerEm & 0xFF);

        // OS/2 表 (78 bytes, usWeightClass at offset 4)
        byte[] os2Table = new byte[78];
        os2Table[4] = (byte) ((weight >> 8) & 0xFF);
        os2Table[5] = (byte) (weight & 0xFF);

        // 组装 TTF
        int numTables = 3;
        int headerSize = 12 + numTables * 16;
        int nameOffset = headerSize;
        int headOffset = nameOffset + nameTable.length;
        int os2Offset = headOffset + headTable.length;

        byte[] ttf = new byte[os2Offset + os2Table.length];
        // sfntVersion
        ttf[0] = 0x00; ttf[1] = 0x01; ttf[2] = 0x00; ttf[3] = 0x00;
        // numTables
        ttf[4] = 0; ttf[5] = (byte) numTables;
        // searchRange, entrySelector, rangeShift (简化)
        ttf[6] = 0; ttf[7] = (byte) 32;
        ttf[8] = 0; ttf[9] = 1;
        ttf[10] = 0; ttf[11] = 0;

        // Table records
        writeTableRecord(ttf, 12, "name", nameOffset, nameTable.length);
        writeTableRecord(ttf, 28, "head", headOffset, headTable.length);
        writeTableRecord(ttf, 44, "OS/2", os2Offset, os2Table.length);

        System.arraycopy(nameTable, 0, ttf, nameOffset, nameTable.length);
        System.arraycopy(headTable, 0, ttf, headOffset, headTable.length);
        System.arraycopy(os2Table, 0, ttf, os2Offset, os2Table.length);

        return ttf;
    }

    private static void writeNameRecord(byte[] table, int rec, int nameId, int length, int offset) {
        // platformID=3 (Windows), encodingID=1 (Unicode BMP), languageID=0x409 (English)
        table[rec] = 0; table[rec + 1] = 3;
        table[rec + 2] = 0; table[rec + 3] = 1;
        table[rec + 4] = 0x04; table[rec + 5] = 0x09;
        table[rec + 6] = (byte) ((nameId >> 8) & 0xFF);
        table[rec + 7] = (byte) (nameId & 0xFF);
        table[rec + 8] = (byte) ((length >> 8) & 0xFF);
        table[rec + 9] = (byte) (length & 0xFF);
        table[rec + 10] = (byte) ((offset >> 8) & 0xFF);
        table[rec + 11] = (byte) (offset & 0xFF);
    }

    private static void writeTableRecord(byte[] ttf, int rec, String tag, int offset, int length) {
        byte[] tagBytes = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tagBytes, 0, ttf, rec, 4);
        ttf[rec + 8] = (byte) ((offset >> 24) & 0xFF);
        ttf[rec + 9] = (byte) ((offset >> 16) & 0xFF);
        ttf[rec + 10] = (byte) ((offset >> 8) & 0xFF);
        ttf[rec + 11] = (byte) (offset & 0xFF);
        ttf[rec + 12] = (byte) ((length >> 24) & 0xFF);
        ttf[rec + 13] = (byte) ((length >> 16) & 0xFF);
        ttf[rec + 14] = (byte) ((length >> 8) & 0xFF);
        ttf[rec + 15] = (byte) (length & 0xFF);
    }

    /** 从合成 TTF 构建 WOFF：加 44 字节头 + 20 字节表记录。 */
    private static byte[] buildWoff(String fontName, String version, String psName,
                                    int unitsPerEm, int weight) {
        byte[] ttf = buildTtf(fontName, version, psName, unitsPerEm, weight);
        // 现有 TTF 中 3 个表：name / head / OS/2
        int numTables = beShort(ttf, 4);
        int headerSize = 12 + numTables * 16;
        byte[] nameTable = slice(ttf, beInt(ttf, 12 + 0 * 16 + 8), beInt(ttf, 12 + 0 * 16 + 12));
        byte[] headTable = slice(ttf, beInt(ttf, 12 + 1 * 16 + 8), beInt(ttf, 12 + 1 * 16 + 12));
        byte[] os2Table = slice(ttf, beInt(ttf, 12 + 2 * 16 + 8), beInt(ttf, 12 + 2 * 16 + 12));

        int headerBytes = 44;
        int tableDirBytes = numTables * 20;
        int nameOff = headerBytes + tableDirBytes;
        int headOff = nameOff + nameTable.length;
        int os2Off = headOff + headTable.length;
        int totalSfntSize = 12 + numTables * 16 + nameTable.length + headTable.length + os2Table.length;

        byte[] woff = new byte[os2Off + os2Table.length];
        // WOFF 头
        woff[0] = 'w'; woff[1] = 'O'; woff[2] = 'F'; woff[3] = 'F';
        woff[4] = 0x00; woff[5] = 0x01; woff[6] = 0x00; woff[7] = 0x00; // flavor
        writeBeInt(woff, 8, woff.length);   // length
        writeBeShort(woff, 12, numTables);  // numTables
        writeBeInt(woff, 16, totalSfntSize);
        // 表记录
        writeWoffRecord(woff, 44, "name", nameOff, nameTable.length);
        writeWoffRecord(woff, 64, "head", headOff, headTable.length);
        writeWoffRecord(woff, 84, "OS/2", os2Off, os2Table.length);
        // 表数据（未压缩）
        System.arraycopy(nameTable, 0, woff, nameOff, nameTable.length);
        System.arraycopy(headTable, 0, woff, headOff, headTable.length);
        System.arraycopy(os2Table, 0, woff, os2Off, os2Table.length);
        return woff;
    }

    /** 构建最小 WOFF2 头（48 字节）。目录用 Brotli 压缩，测试只读头部字段。 */
    private static byte[] buildWoff2Header(int numTables, long totalSfntSize) {
        byte[] h = new byte[48];
        h[0] = 'w'; h[1] = 'O'; h[2] = 'F'; h[3] = '2';
        h[4] = 0x00; h[5] = 0x01; h[6] = 0x00; h[7] = 0x00; // flavor
        writeBeInt(h, 8, 48);           // length
        writeBeShort(h, 12, numTables);
        writeBeInt(h, 16, (int) totalSfntSize);
        writeBeInt(h, 20, 24);          // totalCompressedSize
        return h;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private static void writeWoffRecord(byte[] b, int rec, String tag, int offset, int length) {
        byte[] tagBytes = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tagBytes, 0, b, rec, 4);
        writeBeInt(b, rec + 4, offset);   // offset
        writeBeInt(b, rec + 8, length);   // compLength
        writeBeInt(b, rec + 12, length);  // origLength
        writeBeInt(b, rec + 16, 0);       // origChecksum
    }

    private static int beShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeBeShort(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void writeBeInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    @Test
    void parsesWoffMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("font.woff");
        Files.write(p, buildWoff("Woff Sans", "WOFF 1.0", "WoffSans", 1000, 400));

        Document doc = handler.parse(p.toFile());
        assertEquals("font/woff", doc.getMimeType());
        assertEquals("woff", doc.getMetadata().get("format"));
        assertEquals("Woff Sans", doc.getMetadata().get("fontName"));
        assertEquals(1000, doc.getMetadata().get("unitsPerEm"));
        assertEquals(400, doc.getMetadata().get("weight"));
        assertEquals(3, doc.getMetadata().get("numTables"));
    }

    @Test
    void parsesWoff2Header(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("font.woff2");
        Files.write(p, buildWoff2Header(5, 40000));

        Document doc = handler.parse(p.toFile());
        assertEquals("font/woff2", doc.getMimeType());
        assertEquals("woff2", doc.getMetadata().get("format"));
        assertEquals(5, doc.getMetadata().get("numTables"));
        assertEquals(40000L, doc.getMetadata().get("totalSfntSize"));
    }

    @Test
    void woff2IsDetectedByMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext2");
        Files.write(p, buildWoff2Header(1, 100));
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void declaresFontFormat() {
        assertEquals("ttf", handler.getExtension());
        assertEquals("font/ttf", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesTtfMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("font.ttf");
        byte[] ttf = buildTtf("FileStudio Sans", "Version 1.0", "FileStudioSans", 1000, 400);
        Files.write(p, ttf);

        Document doc = handler.parse(p.toFile());
        assertEquals("font/ttf", doc.getMimeType());
        assertEquals("ttf", doc.getMetadata().get("format"));
        assertEquals(1000, doc.getMetadata().get("unitsPerEm"));
        assertEquals(400, doc.getMetadata().get("weight"));
        assertEquals("FileStudio Sans", doc.getMetadata().get("fontName"));
        assertEquals("Version 1.0", doc.getMetadata().get("version"));
        assertEquals("FileStudioSans", doc.getMetadata().get("postScriptName"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesOtfMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("font.otf");
        byte[] ttf = buildTtf("FileStudio Serif", "Version 2.0", "FileStudioSerif", 2048, 700);
        // 修改 sfntVersion 为 OTTO
        ttf[0] = 'O'; ttf[1] = 'T'; ttf[2] = 'T'; ttf[3] = 'O';
        Files.write(p, ttf);

        Document doc = handler.parse(p.toFile());
        assertEquals("font/otf", doc.getMimeType());
        assertEquals("otf", doc.getMetadata().get("format"));
        assertEquals(2048, doc.getMetadata().get("unitsPerEm"));
        assertEquals(700, doc.getMetadata().get("weight"));
    }

    @Test
    void canHandleByExtensionAndMagicBytes(@TempDir Path dir) throws IOException {
        assertTrue(handler.canHandle(new java.io.File("a.ttf")));
        assertTrue(handler.canHandle(new java.io.File("b.otf")));
        assertTrue(handler.canHandle(new java.io.File("c.woff")));
        assertFalse(handler.canHandle(new java.io.File("d.txt")));

        Path p = dir.resolve("noext");
        byte[] ttf = buildTtf("X", "1.0", "X", 1000, 400);
        Files.write(p, ttf);
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void rejectsNonFontContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.ttf");
        Files.writeString(p, "this is not a font file", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.ttf");
        Files.write(p, buildTtf("X", "1.0", "X", 1000, 400));
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.ttf").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.ttf");
        Files.write(p, buildTtf("X", "1.0", "X", 1000, 400));
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }
}

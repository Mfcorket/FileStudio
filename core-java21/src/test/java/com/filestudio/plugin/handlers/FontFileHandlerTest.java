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

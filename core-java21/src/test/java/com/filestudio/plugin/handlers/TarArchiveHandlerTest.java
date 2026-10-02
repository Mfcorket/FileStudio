package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TarArchiveHandlerTest {

    private final TarArchiveHandler handler = new TarArchiveHandler();

    @Test
    void declaresTarFormat() {
        assertEquals("tar", handler.getExtension());
        assertEquals("application/x-tar", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void parsesTarEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("archive.tar");
        byte[] tar = buildTar("file1.txt", 100, '0', "0000644");
        Files.write(p, tar);

        Document doc = handler.parse(p.toFile());
        assertEquals("application/x-tar", doc.getMimeType());
        assertEquals("tar", doc.getMetadata().get("format"));
        assertEquals(1, doc.getMetadata().get("entryCount"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));

        @SuppressWarnings("unchecked")
        List<TarArchiveHandler.TarEntryInfo> entries =
                (List<TarArchiveHandler.TarEntryInfo>) doc.getMetadata().get("entries");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals("file1.txt", entries.get(0).name());
        assertEquals(100, entries.get(0).size());
        assertEquals('0', entries.get(0).type());
    }

    @Test
    void parsesMultipleEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("multi.tar");
        byte[] entry1 = buildTarEntry("dir/", 0, '5', "0000755");
        byte[] entry2 = buildTarEntry("dir/file.txt", 50, '0', "0000644");
        byte[] tar = new byte[entry1.length + entry2.length + 1024]; // + 2 zero blocks
        System.arraycopy(entry1, 0, tar, 0, entry1.length);
        System.arraycopy(entry2, 0, tar, entry1.length, entry2.length);
        Files.write(p, tar);

        Document doc = handler.parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("entryCount"));
    }

    @Test
    void canHandleByExtensionAndMagicBytes(@TempDir Path dir) throws IOException {
        assertTrue(handler.canHandle(new java.io.File("a.tar")));
        assertFalse(handler.canHandle(new java.io.File("b.txt")));

        Path p = dir.resolve("noext");
        Files.write(p, buildTar("x.txt", 10, '0', "0000644"));
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void rejectsNonTarContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.tar");
        Files.writeString(p, "this is not a tar archive", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.tar");
        Files.write(p, buildTar("x.txt", 10, '0', "0000644"));
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.tar").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.tar");
        Files.write(p, buildTar("x.txt", 10, '0', "0000644"));
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 合成文件构造 ----

    private static byte[] buildTar(String name, long size, char type, String mode) {
        byte[] header = buildTarEntry(name, size, type, mode);
        int dataBlocks = (int) ((size + 511) / 512);
        byte[] tar = new byte[header.length + dataBlocks * 512 + 1024]; // + 2 zero blocks
        System.arraycopy(header, 0, tar, 0, header.length);
        return tar;
    }

    private static byte[] buildTarEntry(String name, long size, char type, String mode) {
        byte[] header = new byte[512];
        // name (100 bytes)
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        // mode (8 bytes)
        byte[] modeBytes = mode.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(modeBytes, 0, header, 100, Math.min(modeBytes.length, 8));
        // uid (8 bytes)
        header[108] = '0'; header[109] = '0'; header[110] = '0'; header[111] = '0';
        header[112] = '0'; header[113] = '0'; header[114] = '0'; header[115] = 0;
        // gid (8 bytes)
        header[116] = '0'; header[117] = '0'; header[118] = '0'; header[119] = '0';
        header[120] = '0'; header[121] = '0'; header[122] = '0'; header[123] = 0;
        // size (12 bytes, octal)
        String sizeStr = String.format("%011o", size);
        byte[] sizeBytes = sizeStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(sizeBytes, 0, header, 124, 11);
        header[135] = 0;
        // mtime (12 bytes, octal)
        String mtimeStr = String.format("%011o", 1000000000L);
        byte[] mtimeBytes = mtimeStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(mtimeBytes, 0, header, 136, 11);
        header[147] = 0;
        // checksum (8 bytes) - 简化为空格
        for (int i = 148; i < 156; i++) header[i] = ' ';
        // type flag
        header[156] = (byte) type;
        // magic (6 bytes)
        header[257] = 'u'; header[258] = 's'; header[259] = 't';
        header[260] = 'a'; header[261] = 'r'; header[262] = 0;
        return header;
    }
}

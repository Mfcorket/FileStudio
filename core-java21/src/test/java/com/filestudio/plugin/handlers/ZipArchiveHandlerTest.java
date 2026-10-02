package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipArchiveHandlerTest {

    private final ZipArchiveHandler handler = new ZipArchiveHandler();

    private static void writeZip(Path zipPath, String... entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (String name : entries) {
                zos.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    zos.write(("content of " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
    }

    @Test
    void declaresZipFormat() {
        assertEquals("zip", handler.getExtension());
        assertEquals("application/zip", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void canHandleExtensions() {
        assertTrue(handler.canHandle(new java.io.File("a.zip")));
        assertTrue(handler.canHandle(new java.io.File("b.jar")));
        assertFalse(handler.canHandle(new java.io.File("c.txt")));
    }

    @Test
    void listsEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("archive.zip");
        writeZip(p, "a.txt", "dir/", "dir/b.txt", "c.log");

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(4, doc.getMetadata().get("entryCount"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));

        @SuppressWarnings("unchecked")
        List<ZipArchiveHandler.ZipEntryInfo> entries =
                (List<ZipArchiveHandler.ZipEntryInfo>) doc.getMetadata().get("entries");
        assertNotNull(entries);
        assertEquals(4, entries.size());
        assertEquals("a.txt", entries.get(0).name());
        assertFalse(entries.get(0).isDirectory());
        assertTrue(entries.stream().anyMatch(e -> e.isDirectory() && e.name().equals("dir/")));
    }

    @Test
    void emptyArchiveHasZeroEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(p))) {
            // 无条目
        }
        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(0, doc.getMetadata().get("entryCount"));
    }

    @Test
    void notAZipIsReportedInvalid(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.zip");
        Files.writeString(p, "just some text, not a zip archive", java.nio.charset.StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.zip");
        writeZip(p, "x.txt");
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.zip").toFile()));
    }

    @Test
    void compressedAndUncompressedSizes(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("sizes.zip");
        String content = "hello hello hello hello hello hello hello hello";
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(p))) {
            zos.putNextEntry(new ZipEntry("data.txt"));
            zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Document doc = handler.parse(p.toFile());
        // 未压缩大小应大于压缩后大小（文本压缩有效时）
        long uncompressed = (Long) doc.getMetadata().get("uncompressedSize");
        assertEquals(content.length(), uncompressed);
        long compressed = (Long) doc.getMetadata().get("compressedSize");
        assertTrue(compressed > 0);
        assertTrue(compressed <= uncompressed);
    }

    @Test
    void canHandleDetectsZipBySignature(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        writeZip(p, "x.txt");
        // 无扩展名时通过魔数识别
        assertTrue(handler.canHandle(p.toFile()));
    }
}

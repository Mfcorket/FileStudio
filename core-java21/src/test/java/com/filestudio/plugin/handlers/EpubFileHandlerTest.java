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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class EpubFileHandlerTest {

    private final EpubFileHandler handler = new EpubFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("epub", handler.getExtension());
        assertEquals("application/epub+zip", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void extractsBookMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("book.epub");
        Files.write(p, buildEpub(defaultEntries()));

        Document doc = handler.parse(p.toFile());
        assertEquals("application/epub+zip", doc.getMimeType());
        assertEquals("epub", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("The Pragmatic Programmer", doc.getMetadata().get("title"));
        assertEquals("Andrew Hunt", doc.getMetadata().get("creator"));
        assertEquals("en", doc.getMetadata().get("language"));
        assertEquals("Addison-Wesley", doc.getMetadata().get("publisher"));
        assertEquals("1999-10-30", doc.getMetadata().get("date"));
        assertEquals("urn:isbn:9780201616224", doc.getMetadata().get("identifier"));
        assertEquals("3.0", doc.getMetadata().get("opfVersion"));
        assertEquals("OEBPS/content.opf", doc.getMetadata().get("opfPath"));
    }

    @Test
    void countsManifestAndSpine(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("count.epub");
        Files.write(p, buildEpub(defaultEntries()));

        Document doc = handler.parse(p.toFile());
        assertEquals(5, doc.getMetadata().get("manifestItemCount"), "含 3 章 + 1 样式 + 1 图片");
        assertEquals(3, doc.getMetadata().get("spineItemCount"));
        assertEquals(3, doc.getMetadata().get("chapterCount"));
        assertEquals(true, doc.getMetadata().get("hasToc"));
        assertEquals(1, doc.getMetadata().get("imageCount"));
        assertEquals(java.util.List.of("ch1.xhtml", "ch2.xhtml", "ch3.xhtml"),
                doc.getMetadata().get("chapters"));
    }

    /** XML 实体与 CDATA 常见于书名中的 & 与 <。 */
    @Test
    void decodesXmlEntities(@TempDir Path dir) throws IOException {
        Map<String, String> e = defaultEntries();
        e.put("OEBPS/content.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Tom &amp; Jerry &lt;Deluxe&gt;</dc:title>
                    <dc:creator>A &amp; B</dc:creator>
                  </metadata>
                </package>
                """);
        Path p = dir.resolve("entities.epub");
        Files.write(p, buildEpub(e));

        Document doc = handler.parse(p.toFile());
        assertEquals("Tom & Jerry <Deluxe>", doc.getMetadata().get("title"));
        assertEquals("A & B", doc.getMetadata().get("creator"));
    }

    /** 注释里出现的 </dc:title> 不应被误认为结束标签。 */
    @Test
    void ignoresCommentedOutElements(@TempDir Path dir) throws IOException {
        Map<String, String> e = defaultEntries();
        e.put("OEBPS/content.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <!-- <dc:title>Wrong Title</dc:title> -->
                    <dc:title>Real Title</dc:title>
                  </metadata>
                </package>
                """);
        Path p = dir.resolve("comment.epub");
        Files.write(p, buildEpub(e));

        Document doc = handler.parse(p.toFile());
        assertEquals("Real Title", doc.getMetadata().get("title"));
        assertEquals("2.0", doc.getMetadata().get("opfVersion"));
    }

    @Test
    void rejectsZipThatIsNotEpub(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("plain.epub");
        Files.write(p, buildPlainZip());

        assertFalse(handler.canHandle(p.toFile()));
        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"), "ZIP 本身可读");
        assertNotNull(doc.getMetadata().get("error"), "缺少 container.xml 应记录 error");
    }

    @Test
    void rejectsNonZipContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.epub");
        Files.writeString(p, "not a zip at all", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void detectsEpubWithoutExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        Files.write(p, buildEpub(defaultEntries()));
        assertTrue(handler.canHandle(p.toFile()), "mimetype 条目应能识别出 EPUB");
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.epub")));
        assertFalse(handler.canHandle(new java.io.File("b.pdf")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.epub");
        Files.write(p, buildEpub(defaultEntries()));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.epub").toFile()));
    }

    // ---- 辅助 ----

    private static byte[] buildEpub(Map<String, String> entries) throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("mimetype", "application/epub+zip");
        all.putAll(entries);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : all.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                if ("mimetype".equals(e.getKey())) {
                    // 规范要求 mimetype 不压缩且位于首个条目
                    byte[] data = e.getValue().getBytes(StandardCharsets.US_ASCII);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    entry.setCrc(crc.getValue());
                    zos.putNextEntry(entry);
                    zos.write(data);
                } else {
                    zos.putNextEntry(entry);
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] buildPlainZip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private static Map<String, String> defaultEntries() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf"
                              media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """);
        e.put("OEBPS/content.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0"
                         unique-identifier="pub-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>The Pragmatic Programmer</dc:title>
                    <dc:creator>Andrew Hunt</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:publisher>Addison-Wesley</dc:publisher>
                    <dc:date>1999-10-30</dc:date>
                    <dc:identifier id="pub-id">urn:isbn:9780201616224</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="img" href="cover.png" media-type="image/png"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                    <itemref idref="c3"/>
                  </spine>
                </package>
                """);
        e.put("OEBPS/ch1.xhtml", "<html><body><p>Chapter 1</p></body></html>");
        e.put("OEBPS/ch2.xhtml", "<html><body><p>Chapter 2</p></body></html>");
        e.put("OEBPS/ch3.xhtml", "<html><body><p>Chapter 3</p></body></html>");
        e.put("OEBPS/style.css", "body{}");
        e.put("OEBPS/cover.png", "fake-png-bytes");
        return e;
    }

}

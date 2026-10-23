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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OfficeFileHandlerTest {

    private final OfficeFileHandler handler = new OfficeFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("docx", handler.getExtension());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void extractsDocxProperties(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("report.docx");
        Files.write(p, buildDocx());

        Document doc = handler.parse(p.toFile());

        assertEquals("docx", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("Quarterly Report", doc.getMetadata().get("title"));
        assertEquals("Jane Doe", doc.getMetadata().get("creator"));
        assertEquals("John Smith", doc.getMetadata().get("lastModifiedBy"));
        assertEquals("2024-03-01T08:00:00Z", doc.getMetadata().get("created"));
        assertEquals("2024-03-15T09:30:00Z", doc.getMetadata().get("modified"));
        assertEquals("2", doc.getMetadata().get("revision"));
        assertEquals("Microsoft Office Word", doc.getMetadata().get("application"));
        assertEquals("5", doc.getMetadata().get("pages"));
        assertEquals("1234", doc.getMetadata().get("words"));
    }

    @Test
    void countsWordStructure(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("structure.docx");
        Files.write(p, buildDocx());

        Document doc = handler.parse(p.toFile());

        assertEquals(4, doc.getMetadata().get("paragraphCount"), "3 段 + 表格内 1 段 = 4");
        assertEquals(1, doc.getMetadata().get("tableCount"));
        assertEquals(1, doc.getMetadata().get("imageCount"));
    }

    @Test
    void extractsWorkbookSheetNames(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("workbook.xlsx");
        Files.write(p, buildXlsx());

        Document doc = handler.parse(p.toFile());

        assertEquals("xlsx", doc.getMetadata().get("format"));
        assertEquals(2, doc.getMetadata().get("sheetCount"));
        assertEquals(List.of("Sheet1", "Data2"), doc.getMetadata().get("sheetNames"));
    }

    @Test
    void extractsPresentationSlideCount(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("slides.pptx");
        Files.write(p, buildPptx());

        Document doc = handler.parse(p.toFile());

        assertEquals("pptx", doc.getMetadata().get("format"));
        assertEquals(3, doc.getMetadata().get("slideCount"), "presentation.xml 中 3 个 <p:sldId>");
    }

    @Test
    void extractsOdtMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("letter.odt");
        Files.write(p, buildOdt());

        Document doc = handler.parse(p.toFile());

        assertEquals("odt", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("3", doc.getMetadata().get("pageCount"), "ODT 元数据统一按字符串呈现");
        assertEquals("Carol White", doc.getMetadata().get("initialCreator"));
        assertEquals("LibreOffice 7.6", doc.getMetadata().get("generator"));
    }

    @Test
    void detectsTypeByContentNotExtension(@TempDir Path dir) throws IOException {
        // 内容是 docx，但被改成 .zip 扩展名——canHandle 依靠 SUPPORTED 扩展名集合
        // 这里改名后 parse 应仍能按内容判别为 docx。
        Path p = dir.resolve("renamed.odt");
        Files.write(p, buildDocx());

        Document doc = handler.parse(p.toFile());
        assertEquals("docx", doc.getMetadata().get("format"),
                "内容含 word/document.xml 时，即使扩展名是 .odt 也应按内容识别");
    }

    @Test
    void rejectsZipThatIsNotOffice(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("plain.docx");
        Files.write(p, buildPlainZip());

        assertFalse(handler.canHandle(p.toFile()), "无 OOXML/ODT 部件的空 ZIP 不应被接受");
    }

    @Test
    void rejectsNonZipContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.docx");
        Files.writeString(p, "not a zip at all", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.docx");
        Files.write(p, buildDocx());
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.docx").toFile()));
    }

    @Test
    void evilEntrySizesAreIgnored(@TempDir Path dir) throws IOException {
        // 条目声称巨大（越界 size），解析不应崩溃
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry e = new ZipEntry("docProps/core.xml");
            e.setSize(Long.MAX_VALUE);
            zos.putNextEntry(e);
            zos.write("""
                    <?xml version="1.0"?>
                    <cp:coreProperties xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title>Big</dc:title>
                    </cp:coreProperties>
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path p = dir.resolve("evil.docx");
        Files.write(p, out.toByteArray());

        Document doc = handler.parse(p.toFile());
        assertNotNull(doc.getMetadata().get("format"));
        assertDoesNotThrow(() -> doc.getMetadata().get("valid"));
    }

    // ---- 辅助 ----

    private static byte[] buildDocx() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
        e.put("docProps/core.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                                   xmlns:dcterms="http://purl.org/dc/terms/">
                  <dc:title>Quarterly Report</dc:title>
                  <dc:creator>Jane Doe</dc:creator>
                  <cp:lastModifiedBy>John Smith</cp:lastModifiedBy>
                  <cp:revision>2</cp:revision>
                  <dcterms:created>2024-03-01T08:00:00Z</dcterms:created>
                  <dcterms:modified>2024-03-15T09:30:00Z</dcterms:modified>
                </cp:coreProperties>
                """);
        e.put("docProps/app.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
                  <Application>Microsoft Office Word</Application>
                  <Pages>5</Pages>
                  <Words>1234</Words>
                  <Characters>6789</Characters>
                  <Lines>100</Lines>
                </Properties>
                """);
        e.put("word/document.xml", """
                <?xml version="1.0"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Hello</w:t></w:r></w:p>
                    <w:p><w:r><w:t>World</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>C1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:p><w:r><w:t>Third</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """);
        e.put("word/media/image1.png", "fake");
        return zip(e);
    }

    private static byte[] buildXlsx() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
        e.put("docProps/core.xml", """
                <?xml version="1.0"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>Sales</dc:title>
                </cp:coreProperties>
                """);
        e.put("docProps/app.xml", """
                <?xml version="1.0"?>
                <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
                  <Application>Microsoft Excel</Application>
                  <Sheets>2</Sheets>
                </Properties>
                """);
        e.put("xl/workbook.xml", """
                <?xml version="1.0"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheets>
                    <sheet name="Sheet1" sheetId="1"/>
                    <sheet name="Data2" sheetId="2"/>
                  </sheets>
                </workbook>
                """);
        e.put("xl/media/logo.png", "fake");
        return zip(e);
    }

    private static byte[] buildPptx() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
        e.put("docProps/core.xml", """
                <?xml version="1.0"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:creator>Alice</dc:creator>
                </cp:coreProperties>
                """);
        e.put("ppt/presentation.xml", """
                <?xml version="1.0"?>
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:sldIdLst>
                    <p:sldId id="1"/>
                    <p:sldId id="2"/>
                    <p:sldId id="3"/>
                  </p:sldIdLst>
                </p:presentation>
                """);
        e.put("ppt/media/pic1.jpg", "fake");
        return zip(e);
    }

    private static byte[] buildOdt() throws IOException {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("mimetype", "application/vnd.oasis.opendocument.text");
        e.put("meta.xml", """
                <?xml version="1.0"?>
                <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                      xmlns:dc="http://purl.org/dc/elements/1.1/"
                                      xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0">
                  <office:meta>
                    <dc:title>Letter</dc:title>
                    <meta:initial-creator>Carol White</meta:initial-creator>
                    <meta:creation-date>2024-01-01T10:00:00</meta:creation-date>
                    <dc:date>2024-02-01T11:00:00</dc:date>
                    <meta:generator>LibreOffice 7.6</meta:generator>
                    <meta:page-count>3</meta:page-count>
                  </office:meta>
                </office:document-meta>
                """);
        e.put("content.xml", """
                <?xml version="1.0"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0">
                  <office:text><text:p>Hi</text:p></office:text>
                </office:document-content>
                """);
        return zip(e);
    }

    private static byte[] buildPlainZip() throws IOException {
        return zip(Map.of("hello.txt", "hello"));
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
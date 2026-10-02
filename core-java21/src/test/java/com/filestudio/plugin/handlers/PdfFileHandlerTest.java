package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfFileHandlerTest {

    private final PdfFileHandler handler = new PdfFileHandler();

    @Test
    void declaresPdfFormat() {
        assertEquals("pdf", handler.getExtension());
        assertEquals("application/pdf", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void parsesPdfMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("doc.pdf");
        String pdf = """
                %PDF-1.4
                1 0 obj
                << /Title (FileStudio Test) /Author (John Doe) /CreationDate (D:20240101) /Producer (Test) >>
                endobj
                2 0 obj
                << /Type /Page /MediaBox [0 0 612 792] >>
                endobj
                3 0 obj
                << /Type /Page /MediaBox [0 0 612 792] >>
                endobj
                4 0 obj
                << /Type /Pages /Count 2 /Kids [2 0 R 3 0 R] >>
                endobj
                """;
        Files.writeString(p, pdf, StandardCharsets.ISO_8859_1);

        Document doc = handler.parse(p.toFile());
        assertEquals("application/pdf", doc.getMimeType());
        assertEquals("pdf", doc.getMetadata().get("format"));
        assertEquals(2, doc.getMetadata().get("pageCount"));
        assertEquals("FileStudio Test", doc.getMetadata().get("title"));
        assertEquals("John Doe", doc.getMetadata().get("author"));
        assertEquals("D:20240101", doc.getMetadata().get("creationDate"));
        assertEquals("Test", doc.getMetadata().get("producer"));
        assertEquals(612.0, (Double) doc.getMetadata().get("pageWidth"), 0.001);
        assertEquals(792.0, (Double) doc.getMetadata().get("pageHeight"), 0.001);
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesPdfWithoutMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("plain.pdf");
        String pdf = """
                %PDF-1.4
                1 0 obj
                << /Type /Page /MediaBox [0 0 595 842] >>
                endobj
                """;
        Files.writeString(p, pdf, StandardCharsets.ISO_8859_1);

        Document doc = handler.parse(p.toFile());
        assertEquals(1, doc.getMetadata().get("pageCount"));
        assertFalse(doc.getMetadata().containsKey("title"));
        assertFalse(doc.getMetadata().containsKey("author"));
        assertEquals(595.0, (Double) doc.getMetadata().get("pageWidth"), 0.001);
        assertEquals(842.0, (Double) doc.getMetadata().get("pageHeight"), 0.001);
    }

    @Test
    void canHandleByExtensionAndMagicBytes(@TempDir Path dir) throws IOException {
        assertTrue(handler.canHandle(new java.io.File("a.pdf")));
        assertFalse(handler.canHandle(new java.io.File("b.txt")));

        Path p = dir.resolve("noext");
        Files.writeString(p, "%PDF-1.4\n1 0 obj\n<< /Type /Page >>\nendobj\n", StandardCharsets.ISO_8859_1);
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void rejectsNonPdfContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.pdf");
        Files.writeString(p, "this is not a PDF file at all", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.pdf");
        Files.writeString(p, "%PDF-1.4\n1 0 obj\n<< /Type /Page /MediaBox [0 0 612 792] >>\nendobj\n", StandardCharsets.ISO_8859_1);
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.pdf").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.pdf");
        Files.writeString(p, "%PDF-1.4\n1 0 obj\n<< /Type /Page /MediaBox [0 0 612 792] >>\nendobj\n", StandardCharsets.ISO_8859_1);
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }
}

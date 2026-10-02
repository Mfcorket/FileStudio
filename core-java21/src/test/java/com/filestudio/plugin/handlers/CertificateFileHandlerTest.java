package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class CertificateFileHandlerTest {

    private final CertificateFileHandler handler = new CertificateFileHandler();
    private static Path certDir;

    @BeforeAll
    static void setUp(@TempDir Path tempDir) throws IOException {
        // 复制测试证书到临时目录
        certDir = tempDir.resolve("certs");
        Files.createDirectories(certDir);
        Path sourceDir = Path.of("A:\\Now\\FileStudio\\.test-certs");
        if (Files.exists(sourceDir)) {
            Files.copy(sourceDir.resolve("test.pem"), certDir.resolve("test.pem"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceDir.resolve("test.der"), certDir.resolve("test.der"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceDir.resolve("test.p12"), certDir.resolve("test.p12"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void declaresCertificateFormat() {
        assertEquals("pem", handler.getExtension());
        assertEquals("application/x-pem-file", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesPemCertificate() throws IOException {
        Path p = certDir.resolve("test.pem");
        if (!Files.exists(p)) return; // 跳过如果证书未生成

        Document doc = handler.parse(p.toFile());
        assertEquals("application/x-pem-file", doc.getMimeType());
        assertEquals("X.509", doc.getMetadata().get("type"));
        assertNotNull(doc.getMetadata().get("subject"));
        assertNotNull(doc.getMetadata().get("issuer"));
        assertNotNull(doc.getMetadata().get("serialNumber"));
        assertNotNull(doc.getMetadata().get("notBefore"));
        assertNotNull(doc.getMetadata().get("notAfter"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesDerCertificate() throws IOException {
        Path p = certDir.resolve("test.der");
        if (!Files.exists(p)) return;

        Document doc = handler.parse(p.toFile());
        assertEquals("X.509", doc.getMetadata().get("type"));
        assertNotNull(doc.getMetadata().get("subject"));
        assertNotNull(doc.getMetadata().get("issuer"));
    }

    @Test
    void canHandleByExtensionAndMagicBytes() throws IOException {
        assertTrue(handler.canHandle(new java.io.File("a.pem")));
        assertTrue(handler.canHandle(new java.io.File("b.crt")));
        assertTrue(handler.canHandle(new java.io.File("c.der")));
        assertFalse(handler.canHandle(new java.io.File("d.txt")));

        Path p = certDir.resolve("test.pem");
        if (Files.exists(p)) {
            assertTrue(handler.canHandle(p.toFile()));
        }
    }

    @Test
    void rejectsNonCertificateContent(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("fake.pem");
        Files.writeString(p, "this is not a certificate", java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path tempDir) throws IOException {
        Path p = certDir.resolve("test.pem");
        if (!Files.exists(p)) return;
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, tempDir.resolve("out.pem").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path tempDir) throws IOException {
        Path p = certDir.resolve("test.pem");
        if (!Files.exists(p)) return;
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }
}

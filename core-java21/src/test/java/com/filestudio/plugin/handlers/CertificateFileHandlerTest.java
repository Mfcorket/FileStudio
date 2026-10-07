package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.test.SelfSignedCertGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class CertificateFileHandlerTest {

    private final CertificateFileHandler handler = new CertificateFileHandler();

    private static final String CN = "FileStudio Test CA";
    private static final String ORG = "FileStudio";

    private static Path pemFile;
    private static Path derFile;
    private static Path p12File;
    private static Path keyFile;
    private static X509Certificate generated;
    private static KeyPair keyPair;

    /**
     * 运行时用纯 JDK 生成自签名证书。
     *
     * <p>不再依赖本机残留的 {@code .test-certs} 目录，因此测试在任何机器上都会真正执行断言，
     * 而不是因为"证书不存在"而静默跳过。
     */
    @BeforeAll
    static void setUp(@TempDir Path tempDir) throws Exception {
        generated = SelfSignedCertGenerator.generate(CN, ORG);
        keyPair = SelfSignedCertGenerator.generateKeyPair();

        pemFile = tempDir.resolve("test.pem");
        Files.writeString(pemFile, SelfSignedCertGenerator.toPem(generated), StandardCharsets.UTF_8);

        derFile = tempDir.resolve("test.der");
        Files.write(derFile, SelfSignedCertGenerator.toDer(generated));

        p12File = tempDir.resolve("test.p12");
        Files.write(p12File, SelfSignedCertGenerator.toPkcs12(
                generated, keyPair, "filestudio", "changeit".toCharArray()));

        keyFile = tempDir.resolve("test.key");
        Files.writeString(keyFile, SelfSignedCertGenerator.privateKeyToPem(keyPair.getPrivate()),
                StandardCharsets.UTF_8);
    }

    @Test
    void declaresCertificateFormat() {
        assertEquals("pem", handler.getExtension());
        assertEquals("application/x-pem-file", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void generatedCertificateIsSelfSignedAndValid() throws Exception {
        assertTrue(generated.getSubjectX500Principal().getName().contains("CN=" + CN));
        assertEquals(generated.getSubjectX500Principal(), generated.getIssuerX500Principal(),
                "自签名证书的 issuer 应与 subject 相同");
        assertEquals(3, generated.getVersion(), "应为 X.509 v3");
        // 用块式 lambda 而非方法引用：checkValidity 抛受检异常，方法引用会触发
        // assertDoesNotThrow(Executable) 与 assertDoesNotThrow(ThrowingSupplier) 的重载歧义
        assertDoesNotThrow(() -> {
            generated.checkValidity();
        }, "生成的证书应处于有效期内");
        // 注意：verify(key, String) 的第二个参数是签名提供方名称，不是断言消息
        assertDoesNotThrow(() -> {
            generated.verify(generated.getPublicKey());
        }, "自签名证书应能用自己的公钥验签");
    }

    @Test
    void parsesPemCertificate() {
        Document doc = handler.parse(pemFile.toFile());
        assertEquals("application/x-pem-file", doc.getMimeType());
        assertEquals("X.509", doc.getMetadata().get("type"));
        assertTrue(((String) doc.getMetadata().get("subject")).contains("CN=" + CN));
        assertTrue(((String) doc.getMetadata().get("issuer")).contains("CN=" + CN));
        assertNotNull(doc.getMetadata().get("serialNumber"));
        assertNotNull(doc.getMetadata().get("notBefore"));
        assertNotNull(doc.getMetadata().get("notAfter"));
        assertEquals("SHA256withRSA", doc.getMetadata().get("sigAlgorithm"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesDerCertificate() {
        Document doc = handler.parse(derFile.toFile());
        assertEquals("X.509", doc.getMetadata().get("type"));
        assertTrue(((String) doc.getMetadata().get("subject")).contains("CN=" + CN));
        assertTrue(((String) doc.getMetadata().get("issuer")).contains("CN=" + CN));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertNull(doc.getMetadata().get("error"), "DER 不应报错: " + doc.getMetadata().get("error"));
    }

    @Test
    void pemAndDerYieldSameIdentity() {
        Document pem = handler.parse(pemFile.toFile());
        Document der = handler.parse(derFile.toFile());
        assertEquals(pem.getMetadata().get("serialNumber"), der.getMetadata().get("serialNumber"));
        assertEquals(pem.getMetadata().get("subject"), der.getMetadata().get("subject"));
    }

    @Test
    void parsesPkcs12Keystore() {
        Document doc = handler.parse(p12File.toFile());
        assertEquals("PKCS#12 keystore", doc.getMetadata().get("type"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertNull(doc.getMetadata().get("error"), "不应报错: " + doc.getMetadata().get("error"));
        assertEquals(1, doc.getMetadata().get("entryCount"));
        assertEquals(1, doc.getMetadata().get("keyEntryCount"));
        assertEquals(true, doc.getMetadata().get("hasPrivateKey"));
        assertEquals(true, doc.getMetadata().get("passwordProtected"));
        assertEquals("filestudio", ((java.util.List<?>) doc.getMetadata().get("aliases")).get(0));
    }

    @Test
    void parsesPkcs12WithEmptyPassword(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("nopass.p12");
        Files.write(p, SelfSignedCertGenerator.toPkcs12(
                generated, keyPair, "nopass", new char[0]));

        Document doc = handler.parse(p.toFile());
        assertEquals("PKCS#12 keystore", doc.getMetadata().get("type"));
        assertEquals(true, doc.getMetadata().get("hasPrivateKey"));
        assertEquals(false, doc.getMetadata().get("passwordProtected"));
    }

    @Test
    void parsesPemPrivateKey() {
        Document doc = handler.parse(keyFile.toFile());
        assertEquals("PEM private key", doc.getMetadata().get("type"));
        assertEquals("PKCS#8", doc.getMetadata().get("keyEncoding"));
        assertEquals("RSA", doc.getMetadata().get("keyAlgorithm"));
        assertEquals(2048, doc.getMetadata().get("keySize"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertNull(doc.getMetadata().get("error"), "不应报错: " + doc.getMetadata().get("error"));
    }

    @Test
    void keyAndCertificateAreDistinguished() {
        // 证书不应被误判为私钥，反之亦然
        assertEquals("X.509", handler.parse(pemFile.toFile()).getMetadata().get("type"));
        assertEquals("PEM private key", handler.parse(keyFile.toFile()).getMetadata().get("type"));
        assertEquals("PKCS#12 keystore", handler.parse(p12File.toFile()).getMetadata().get("type"));
    }

    @Test
    void canHandleByExtensionAndMagicBytes() {
        assertTrue(handler.canHandle(new java.io.File("a.pem")));
        assertTrue(handler.canHandle(new java.io.File("b.crt")));
        assertTrue(handler.canHandle(new java.io.File("c.der")));
        assertTrue(handler.canHandle(new java.io.File("d.p12")));
        assertTrue(handler.canHandle(new java.io.File("e.pfx")));
        assertTrue(handler.canHandle(new java.io.File("f.key")));
        assertFalse(handler.canHandle(new java.io.File("g.txt")));

        // 魔数识别：PEM 以 "-----" 开头，DER 以 SEQUENCE(0x30) 开头
        assertTrue(handler.canHandle(pemFile.toFile()));
        assertTrue(handler.canHandle(derFile.toFile()));
        assertTrue(handler.canHandle(p12File.toFile()));
    }

    @Test
    void rejectsNonCertificateContent(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("fake.pem");
        Files.writeString(p, "this is not a certificate", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertNotNull(doc.getMetadata().get("error"), "非法内容应记录 error");
        assertNull(doc.getMetadata().get("subject"));
    }

    @Test
    void renderIsRejected(@TempDir Path tempDir) {
        Document doc = handler.parse(pemFile.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, tempDir.resolve("out.pem").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent() {
        Document doc = handler.parse(pemFile.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    @Test
    void getMetadataReturnsSubjectAndIssuer() {
        var meta = handler.getMetadata(pemFile.toFile());
        assertTrue(((String) meta.get("subject")).contains("CN=" + CN));
        assertTrue(((String) meta.get("issuer")).contains("CN=" + CN));
    }
}

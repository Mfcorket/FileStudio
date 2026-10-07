package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.ByteArrayInputStream;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 证书文件处理器：从 PEM/DER 格式证书提取主题、颁发者、有效期等元数据。
 *
 * <p>支持：PEM (.pem/.crt/.cer) / DER (.der) / PKCS12 (.p12/.pfx)。
 * VIEW_ONLY 能力——证书不可作为文本编辑。
 *
 * <p>实现：使用 JDK {@link CertificateFactory} 解析 X.509 证书。
 */
public class CertificateFileHandler implements FileHandler {

    private static final Set<String> SUPPORTED = Set.of("pem", "crt", "cer", "der", "p12", "pfx", "key");

    /**
     * 创建证书处理器实例。
     */
    public CertificateFileHandler() {}

    @Override
    public String getExtension() {
        return "pem";
    }

    @Override
    public String getMimeType() {
        return "application/x-pem-file";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "X.509 certificate (PEM/DER/PKCS12)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isCertificate(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Certificate file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] data = readAll(path);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        try {
            if (isPemPrivateKey(data)) {
                parsePrivateKey(data, meta);
            } else {
                X509Certificate cert = readCertificate(data);
                if (cert != null) {
                    describeCertificate(cert, meta);
                } else {
                    // 不是单张证书——尝试按 PKCS#12 密钥库解析
                    KeyStoreInfo ks = readPkcs12(data);
                    if (ks != null) {
                        describeKeyStore(ks, meta);
                    } else {
                        meta.put("error", "Failed to parse certificate, private key or PKCS#12 keystore");
                    }
                }
            }
        } catch (Exception e) {
            meta.put("error", "Failed to parse: " + e.getMessage());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Certificate files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] data = readAll(file.toPath());
            if (isPemPrivateKey(data)) {
                meta.put("type", "PEM private key");
            } else {
                X509Certificate x509 = readCertificate(data);
                if (x509 != null) {
                    meta.put("subject", x509.getSubjectX500Principal().getName());
                    meta.put("issuer", x509.getIssuerX500Principal().getName());
                } else {
                    KeyStoreInfo ks = readPkcs12(data);
                    if (ks != null) {
                        meta.put("type", "PKCS#12 keystore");
                        meta.put("entryCount", ks.aliases().size());
                    }
                }
            }
        } catch (Exception ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isCertificate(File file) {
        try {
            byte[] head = new byte[27];
            try (var in = Files.newInputStream(file.toPath())) {
                int n = in.read(head);
                if (n < 4) return false;
                // PEM: "-----BEGIN"
                if (head[0] == '-' && head[1] == '-' && head[2] == '-' && head[3] == '-') return true;
                // DER: 0x30 (SEQUENCE tag)
                if (head[0] == 0x30) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static boolean isPem(byte[] data) {
        if (data.length < 4) return false;
        return data[0] == '-' && data[1] == '-' && data[2] == '-' && data[3] == '-';
    }

    // ---- PEM 私钥 / PKCS#12 解析 ----

    /** PKCS#12 条目摘要。 */
    private record KeyStoreInfo(List<String> aliases, int keyEntryCount,
                                int certOnlyCount, List<String> subjects,
                                boolean passwordProtected) {}

    /** 解析 PEM 私钥（PKCS#8 "PRIVATE KEY" 与 PKCS#1 "RSA PRIVATE KEY"）。 */
    private static void parsePrivateKey(byte[] data, Map<String, Object> meta) throws Exception {
        String pem = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        boolean pkcs8 = pem.contains("BEGIN PRIVATE KEY");
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(base64);

        meta.put("type", "PEM private key");
        meta.put("keyEncoding", pkcs8 ? "PKCS#8" : "PKCS#1");

        // PKCS#1 需要先包一层 PKCS#8 头才能交给 KeyFactory
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8 ? der : wrapPkcs1AsPkcs8(der));
        for (String alg : new String[]{"RSA", "EC", "DSA", "Ed25519"}) {
            try {
                Key key = KeyFactory.getInstance(alg).generatePrivate(spec);
                meta.put("keyAlgorithm", key.getAlgorithm());
                meta.put("valid", true);
                if (key instanceof java.security.interfaces.RSAPrivateKey rsa) {
                    meta.put("keySize", rsa.getModulus().bitLength());
                } else if (key instanceof java.security.interfaces.ECPrivateKey ec) {
                    meta.put("keySize", ec.getParams().getCurve().getField().getFieldSize());
                }
                return;
            } catch (Exception ignored) {
                // 换下一种算法尝试
            }
        }
        meta.put("valid", false);
        meta.put("error", "Could not decode private key with RSA/EC/DSA/Ed25519");
    }

    /**
     * 把 PKCS#1 RSAPrivateKey 包装为 PKCS#8 PrivateKeyInfo。
     *
     * <p>PKCS#8 结构：version(0) + AlgorithmIdentifier(rsaEncryption 1.2.840.113549.1.1.1, NULL)
     * + OCTET STRING{ PKCS#1 内容 }。
     */
    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};                       // INTEGER 0
        byte[] algId = {0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48,
                (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octet = derTag(0x04, pkcs1);
        byte[] body = concat(version, algId, octet);
        return derTag(0x30, body);
    }

    /**
     * 按 PKCS#12 密钥库读取。先试空口令，再试常见口令；都失败则标记需口令。
     */
    private static KeyStoreInfo readPkcs12(byte[] data) {
        char[][] candidates = {new char[0], "changeit".toCharArray(), "password".toCharArray()};
        for (char[] password : candidates) {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (var in = new ByteArrayInputStream(data)) {
                    ks.load(in, password);
                }
                return describe(ks, password.length > 0);
            } catch (Exception ignored) {
                // 换下一个口令
            }
        }
        return null;
    }

    private static KeyStoreInfo describe(KeyStore ks, boolean passwordProtected) throws Exception {
        List<String> aliases = new java.util.ArrayList<>();
        List<String> subjects = new java.util.ArrayList<>();
        int keys = 0;
        int certOnly = 0;
        for (String alias : java.util.Collections.list(ks.aliases())) {
            aliases.add(alias);
            if (ks.isKeyEntry(alias)) {
                keys++;
                var chain = ks.getCertificateChain(alias);
                if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate x509) {
                    subjects.add(x509.getSubjectX500Principal().getName());
                }
            } else if (ks.isCertificateEntry(alias)) {
                certOnly++;
                var c = ks.getCertificate(alias);
                if (c instanceof X509Certificate x509) {
                    subjects.add(x509.getSubjectX500Principal().getName());
                }
            }
        }
        return new KeyStoreInfo(aliases, keys, certOnly, subjects, passwordProtected);
    }

    private static void describeKeyStore(KeyStoreInfo ks, Map<String, Object> meta) {
        meta.put("type", "PKCS#12 keystore");
        meta.put("valid", true);
        meta.put("entryCount", ks.aliases().size());
        meta.put("keyEntryCount", ks.keyEntryCount());
        meta.put("certOnlyEntryCount", ks.certOnlyCount());
        meta.put("aliases", ks.aliases());
        meta.put("hasPrivateKey", ks.keyEntryCount() > 0);
        meta.put("passwordProtected", ks.passwordProtected());
        if (!ks.subjects().isEmpty()) {
            meta.put("subjects", ks.subjects());
        }
    }

    /** 解析 PEM 或 DER 的单张 X.509 证书；不是证书时返回 {@code null}。 */
    private static X509Certificate readCertificate(byte[] data) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            byte[] der = data;
            if (isPem(data)) {
                String pem = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                        .replaceAll("-----END [A-Z ]+-----", "")
                        .replaceAll("\\s", "");
                der = java.util.Base64.getDecoder().decode(base64);
            }
            Certificate cert = cf.generateCertificate(new ByteArrayInputStream(der));
            return cert instanceof X509Certificate x509 ? x509 : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void describeCertificate(X509Certificate x509, Map<String, Object> meta) {
        meta.put("type", "X.509");
        meta.put("subject", x509.getSubjectX500Principal().getName());
        meta.put("issuer", x509.getIssuerX500Principal().getName());
        meta.put("serialNumber", x509.getSerialNumber().toString(16));
        meta.put("notBefore", formatDate(x509.getNotBefore()));
        meta.put("notAfter", formatDate(x509.getNotAfter()));
        meta.put("sigAlgorithm", x509.getSigAlgName());
        meta.put("version", x509.getVersion());
        try {
            x509.checkValidity();
            meta.put("valid", true);
        } catch (Exception e) {
            meta.put("valid", false);
        }
    }

    private static boolean isPemPrivateKey(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 64),
                java.nio.charset.StandardCharsets.US_ASCII);
        return head.contains("PRIVATE KEY");
    }

    /** 构造 DER 的 tag+length+value。 */
    private static byte[] derTag(int tag, byte[] content) {
        byte[] len;
        if (content.length < 0x80) {
            len = new byte[]{(byte) content.length};
        } else if (content.length < 0x100) {
            len = new byte[]{(byte) 0x81, (byte) content.length};
        } else {
            len = new byte[]{(byte) 0x82, (byte) (content.length >> 8), (byte) content.length};
        }
        return concat(new byte[]{(byte) tag}, len, content);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new FileStudioException("Failed reading certificate: " + path, e);
        }
    }

    private static String formatDate(Date date) {
        if (date == null) return null;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}

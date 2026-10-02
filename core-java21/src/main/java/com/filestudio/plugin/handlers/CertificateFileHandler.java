package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
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
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert;
            if (isPem(data)) {
                // PEM 格式：提取 Base64 内容
                String pem = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                String base64 = pem.replaceAll("-----BEGIN CERTIFICATE-----", "")
                        .replaceAll("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s", "");
                byte[] der = java.util.Base64.getDecoder().decode(base64);
                cert = cf.generateCertificate(new ByteArrayInputStream(der));
            } else {
                cert = cf.generateCertificate(new ByteArrayInputStream(data));
            }

            if (cert instanceof X509Certificate x509) {
                meta.put("type", "X.509");
                meta.put("subject", x509.getSubjectX500Principal().getName());
                meta.put("issuer", x509.getIssuerX500Principal().getName());
                meta.put("serialNumber", x509.getSerialNumber().toString(16));
                meta.put("notBefore", formatDate(x509.getNotBefore()));
                meta.put("notAfter", formatDate(x509.getNotAfter()));
                meta.put("sigAlgorithm", x509.getSigAlgName());
                meta.put("version", x509.getVersion());

                // 检查是否过期
                try {
                    x509.checkValidity();
                    meta.put("valid", true);
                } catch (Exception e) {
                    meta.put("valid", false);
                }
            }
        } catch (Exception e) {
            meta.put("error", "Failed to parse certificate: " + e.getMessage());
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
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert;
            if (isPem(data)) {
                String pem = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                String base64 = pem.replaceAll("-----BEGIN CERTIFICATE-----", "")
                        .replaceAll("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s", "");
                byte[] der = java.util.Base64.getDecoder().decode(base64);
                cert = cf.generateCertificate(new ByteArrayInputStream(der));
            } else {
                cert = cf.generateCertificate(new ByteArrayInputStream(data));
            }
            if (cert instanceof X509Certificate x509) {
                meta.put("subject", x509.getSubjectX500Principal().getName());
                meta.put("issuer", x509.getIssuerX500Principal().getName());
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

package com.filestudio.test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * 测试用自签名 X.509 证书生成器（纯 JDK 实现，无第三方依赖）。
 *
 * <p>JDK 公开 API 只能"使用"证书、不能"签发"证书（{@code CertificateFactory} 没有签发方法），
 * 而 {@code sun.security.x509.*} 属于 {@code java.base} 的非导出包，JDK 9+ 需要
 * {@code --add-exports} 才能访问，不适合放进测试。
 * 因此这里按 RFC 5280 手工编码 DER 结构，再用 {@link CertificateFactory} 回读校验——
 * 编码若有错误，测试会立即失败而不会静默通过。
 *
 * <p>生成的结构：
 * <pre>
 * Certificate      ::= SEQUENCE {
 *     tbsCertificate     TBSCertificate,
 *     signatureAlgorithm sha256WithRSAEncryption,
 *     signatureValue     BIT STRING
 * }
 * TBSCertificate   ::= SEQUENCE {
 *     version         [0] EXPLICIT INTEGER 2,        -- v3
 *     serialNumber        INTEGER,
 *     signature           AlgorithmIdentifier,
 *     issuer              Name,                      -- 自签名：与 subject 相同
 *     validity            Validity,
 *     subject             Name,
 *     subjectPublicKeyInfo SubjectPublicKeyInfo,     -- 直接复用 Key.getEncoded()
 *     extensions      [3] EXPLICIT Extensions        -- basicConstraints + keyUsage
 * }
 * </pre>
 */
public final class SelfSignedCertGenerator {

    private SelfSignedCertGenerator() {}

    /** 生成自签名证书的 DER 编码。 */
    public static X509Certificate generate(String commonName, String organization)
            throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        byte[] der = encodeDer(keyPair, commonName, organization);
        // 回读校验：确保手工编码的结构合法
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    /** 生成 PEM 文本（含 BEGIN/END 头尾，64 字符换行）。 */
    public static String toPem(X509Certificate cert) throws Exception {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    /** 生成 DER 字节。 */
    public static byte[] toDer(X509Certificate cert) throws Exception {
        return cert.getEncoded();
    }

    private static byte[] encodeDer(KeyPair keyPair, String commonName, String organization)
            throws Exception {
        Instant now = Instant.now();
        Instant notBefore = now.minus(1, ChronoUnit.DAYS);
        Instant notAfter = now.plus(3650, ChronoUnit.DAYS);
        BigInteger serial = new BigInteger(64, new SecureRandom()).add(BigInteger.ONE);

        byte[] sha256Rsa = algorithmIdentifier(
                "1.2.840.113549.1.1.11", derNull());   // sha256WithRSAEncryption

        byte[] name = distinguishedName(commonName, organization);
        byte[] validity = der(0x30,
                concat(utcTime(notBefore), utcTime(notAfter)));
        byte[] spki = keyPair.getPublic().getEncoded(); // 已是 X.509 SubjectPublicKeyInfo

        byte[] extensions = extensions();

        byte[] tbs = der(0x30, concat(
                der(0xA0, der(0x02, new byte[]{0x02})),        // version v3
                der(0x02, serial.toByteArray()),             // serialNumber
                sha256Rsa,
                name,                                          // issuer
                validity,
                name,                                          // subject（自签名相同）
                spki,
                der(0xA3, extensions)));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();

        return der(0x30, concat(tbs, sha256Rsa, derBitString(signature)));
    }

    /** AlgorithmIdentifier ::= SEQUENCE { algorithm OID, parameters ANY } */
    private static byte[] algorithmIdentifier(String oid, byte[] params) {
        return der(0x30, concat(derOid(oid), params));
    }

    /** CN + O 组成的 X.501 Name。 */
    private static byte[] distinguishedName(String commonName, String organization) {
        byte[] cn = der(0x30, concat(
                derOid("2.5.4.3"),                            // commonName
                der(0x0C, commonName.getBytes(StandardCharsets.UTF_8))));
        byte[] o = der(0x30, concat(
                derOid("2.5.4.10"),                           // organizationName
                der(0x0C, organization.getBytes(StandardCharsets.UTF_8))));
        // Name ::= SEQUENCE OF RelativeDistinguishedName；每个 RDN 是 SET OF AttributeTypeAndValue
        return der(0x30, concat(
                derSet(cn),
                derSet(o)));
    }

    private static byte[] extensions() {
        // basicConstraints: critical, CA:TRUE
        byte[] basicConstraints = der(0x30, concat(
                derOid("2.5.29.19"),
                der(0x01, new byte[]{(byte) 0xFF}),           // critical BOOLEAN TRUE
                der(0x04, der(0x30, der(0x01, new byte[]{(byte) 0xFF}))))); // SEQUENCE { BOOLEAN TRUE }

        // keyUsage: critical, digitalSignature + keyCertSign (bits 0 和 5)
        // BIT STRING 内部首个字节是未使用位数；digitalSignature=0x80，keyCertSign=0x04 → 0x84，未用 4 位
        byte[] keyUsage = der(0x30, concat(
                derOid("2.5.29.15"),
                der(0x01, new byte[]{(byte) 0xFF}),
                der(0x04, derBitString(new byte[]{(byte) 0x84}))));

        // Extensions ::= SEQUENCE OF Extension
        return der(0x30, concat(basicConstraints, keyUsage));
    }

    // ---- DER 基础编码 ----

    /** TLV 编码：tag + 长度 + 内容。 */
    private static byte[] der(int tag, byte[] content) {
        return concat(new byte[]{(byte) tag}, derLength(content.length), content);
    }

    private static byte[] derLength(int len) {
        if (len < 0x80) {
            return new byte[]{(byte) len};
        }
        // 长格式：0x80 | 字节数，随后是大端长度
        int byteCount = 0;
        for (int v = len; v != 0; v >>>= 8) byteCount++;
        byte[] out = new byte[byteCount + 1];
        out[0] = (byte) (0x80 | byteCount);
        for (int i = 0; i < byteCount; i++) {
            out[byteCount - i] = (byte) ((len >>> (8 * i)) & 0xFF);
        }
        return out;
    }

    private static byte[] derOid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            writeBase128(body, Long.parseLong(parts[i]));
        }
        return der(0x06, body.toByteArray());
    }

    /** OID 分量按 base-128 编码，高位为续位标志。 */
    private static void writeBase128(ByteArrayOutputStream out, long value) {
        if (value < 0) throw new IllegalArgumentException("negative OID component");
        int shift = 63;
        while (shift > 0 && (value >>> shift) == 0) shift -= 7;
        shift = Math.min(shift / 7 * 7, 63);
        while (shift > 0) {
            out.write((int) (((value >>> shift) & 0x7F) | 0x80));
            shift -= 7;
        }
        out.write((int) (value & 0x7F));
    }

    /** DER SET：内容需按编码字节序排序（X.509 要求）。 */
    private static byte[] derSet(byte[]... elements) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] e : elements) {
            body.writeBytes(e);
        }
        return der(0x31, body.toByteArray());
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    /** BIT STRING：首字节为未使用位数。 */
    private static byte[] derBitString(byte[] bits) {
        return der(0x03, concat(new byte[]{0x00}, bits));
    }

    /** UTCTime (YYMMDDHHMMSSZ)。 */
    private static byte[] utcTime(Instant instant) {
        var z = instant.atZone(ZoneOffset.UTC);
        String s = String.format("%02d%02d%02d%02d%02d%02dZ",
                z.getYear() % 100, z.getMonthValue(), z.getDayOfMonth(),
                z.getHour(), z.getMinute(), z.getSecond());
        return der(0x17, s.getBytes(StandardCharsets.US_ASCII));
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

    /** 供断言使用的有效期区间。 */
    public static Date[] validityWindow() {
        Instant now = Instant.now();
        return new Date[]{
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS))
        };
    }
}

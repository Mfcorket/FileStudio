package com.filestudio.engine;

import com.filestudio.core.FileStudioException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件头签名识别器（Magic Bytes）：从文件内容前缀判定真实格式。
 *
 * <p>对应规划文档 §5.2 的"Magic Bytes - 文件头签名，最准确"识别策略。
 * 当扩展名缺失、错误或不可信时，用它作为兜底，将文件路由到正确的处理器。
 *
 * <p>二进制格式签名按字节序列匹配；文本格式通过前缀/内容探测（UTF-8 BOM、
 * JSON/XML/HTML/Shebang 等）。匹配不到的返回 {@link Detection#UNKNOWN}。
 */
public final class MagicBytesDetector {

    /**
     * 识别结果。不可变。
     *
     * @param extension  识别出的扩展名（小写，不含点），未知时为空串
     * @param mimeType   对应 MIME 类型，未知时为 {@code application/octet-stream}
     * @param confidence 置信度，取值 0.0（未知）至 1.0（签名完全匹配）
     */
    public record Detection(String extension, String mimeType, double confidence) {
        /** 识别未成功时的哨兵值。 */
        public static final Detection UNKNOWN = new Detection("", "application/octet-stream", 0.0);

        /**
         * 是否成功识别出格式。
         *
         * @return 置信度大于 0 且扩展名非空时为 true
         */
        public boolean isKnown() {
            return confidence > 0 && !extension.isEmpty();
        }
    }

    private MagicBytesDetector() {}

    /**
     * 读取文件头部并识别格式。
     *
     * @param path 目标文件，{@code null} 时返回 {@link Detection#UNKNOWN}
     * @return 识别结果
     * @throws FileStudioException 读取失败时抛出
     */
    public static Detection detect(Path path) {
        if (path == null) return Detection.UNKNOWN;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] head = new byte[HEADER_BYTES];
            int n = in.read(head);
            return detect(head, n);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading magic bytes from " + path, e);
        }
    }

    /**
     * 从字节前缀识别格式。
     *
     * @param head 文件头字节，{@code null} 时返回未知
     * @param len  有效字节数，非正时返回未知
     * @return 识别结果；无匹配签名时为 {@link Detection#UNKNOWN}
     */
    public static Detection detect(byte[] head, int len) {
        if (head == null || len <= 0) return Detection.UNKNOWN;

        // ---- 带文本内容探测 ----
        if (len >= 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF) {
            return new Detection("txt", "text/plain", 0.9);
        }
        if (hasPrefix(head, len, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
            return new Detection("png", "image/png", 1.0);
        }
        if (len >= 3 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return new Detection("jpg", "image/jpeg", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'G', 'I', 'F', '8'})) {
            return new Detection("gif", "image/gif", 1.0);
        }
        if (len >= 2 && head[0] == 'B' && head[1] == 'M') {
            return new Detection("bmp", "image/bmp", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'R', 'I', 'F', 'F'}) && hasOffset(head, len, 8, new byte[]{'W', 'E', 'B', 'P'})) {
            return new Detection("webp", "image/webp", 0.95);
        }
        if (hasPrefix(head, len, new byte[]{'%', 'P', 'D', 'F', '-'})) {
            return new Detection("pdf", "application/pdf", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'R', 'I', 'F', 'F'}) && hasOffset(head, len, 8, new byte[]{'W', 'A', 'V', 'E'})) {
            return new Detection("wav", "audio/wav", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'P', 'K', 0x03, 0x04})
                || hasPrefix(head, len, new byte[]{'P', 'K', 0x05, 0x06})
                || hasPrefix(head, len, new byte[]{'P', 'K', 0x07, 0x08})) {
            return new Detection("zip", "application/zip", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{0x1F, (byte) 0x8B})) {
            return new Detection("gz", "application/gzip", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C})) {
            return new Detection("7z", "application/x-7z-compressed", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'R', 'a', 'r', '!'})) {
            return new Detection("rar", "application/vnd.rar", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0x00})) {
            return new Detection("sqlite", "application/vnd.sqlite3", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1})) {
            return new Detection("ole", "application/x-ole-storage", 0.95);
        }
        if (hasPrefix(head, len, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE})) {
            return new Detection("class", "application/java-vm", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'f', 'L', 'a', 'C'})) {
            return new Detection("flac", "audio/flac", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'O', 'g', 'g', 'S'})) {
            return new Detection("ogg", "audio/ogg", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'P', 'A', 'R', '1'})) {
            return new Detection("parquet", "application/vnd.parquet", 1.0);
        }
        if (hasPrefix(head, len, new byte[]{'M', 'Z'})) {
            return new Detection("exe", "application/x-msdownload", 0.9);
        }
        if (hasPrefix(head, len, new byte[]{0x7F, 'E', 'L', 'F'})) {
            return new Detection("elf", "application/x-elf", 1.0);
        }

        // ---- 文本内容探测（无 BOM） ----
        String text = decodeAsciiPrefix(head, len);
        if (text == null) return Detection.UNKNOWN;
        String t = text.stripLeading();
        if (t.startsWith("{") || t.startsWith("[")) {
            return new Detection("json", "application/json", 0.6);
        }
        if (t.startsWith("<?xml") || t.startsWith("<")) {
            return new Detection("xml", "application/xml", 0.5);
        }
        if (t.startsWith("<html") || t.startsWith("<!DOCTYPE html")) {
            return new Detection("html", "text/html", 0.7);
        }
        if (t.startsWith("#!")) {
            return new Detection("sh", "application/x-sh", 0.8);
        }

        return Detection.UNKNOWN;
    }

    /** 参与签名的头部字节数。足够覆盖所有签名的最长前缀。 */
    public static final int HEADER_BYTES = 64;

    private static boolean hasPrefix(byte[] head, int len, byte[] sig) {
        if (len < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if (head[i] != sig[i]) return false;
        }
        return true;
    }

    private static boolean hasOffset(byte[] head, int len, int offset, byte[] sig) {
        if (len < offset + sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if (head[offset + i] != sig[i]) return false;
        }
        return true;
    }

    /** 把头部解码为 ASCII 前缀；若含非 ASCII/非文本控制字符则返回 null。 */
    private static String decodeAsciiPrefix(byte[] head, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int b = head[i] & 0xFF;
            if (b == 0) return null;
            if (b >= 0x80) return null;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) return null; // 控制字符
            sb.append((char) b);
        }
        return sb.toString();
    }
}

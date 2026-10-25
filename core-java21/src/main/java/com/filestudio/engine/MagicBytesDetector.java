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
        // BZh + 1-9（块大小级别）
        if (len >= 4 && head[0] == 'B' && head[1] == 'Z' && head[2] == 'h'
                && head[3] >= '1' && head[3] <= '9') {
            return new Detection("bz2", "application/x-bzip2", 1.0);
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
            // 尝试区分 Opus 与 Vorbis：检查首个页面的识别头
            String oggExt = detectOggSubtype(head, len);
            if (oggExt != null) {
                return new Detection(oggExt, "audio/ogg", 0.95);
            }
            return new Detection("ogg", "audio/ogg", 1.0);
        }
        // AAC ADTS：同步字 0xFFF，层=00（区别于 MP3 的 0xFFE/层=01）
        if (len >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xF6) == 0xF0) {
            return new Detection("aac", "audio/aac", 0.95);
        }
        // M4A / MP4：ISO-BMFF ftyp box 位于偏移 4
        if (len >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.equals("M4A ") || brand.equals("M4B ")) {
                return new Detection("m4a", "audio/mp4", 1.0);
            }
            return new Detection("mp4", "video/mp4", 0.9);
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
        // FLV：'FLV' + 版本(1)
        if (len >= 4 && head[0] == 'F' && head[1] == 'L' && head[2] == 'V'
                && head[3] == 1) {
            return new Detection("flv", "video/x-flv", 1.0);
        }
        // WMV/WMA/ASF：头对象 GUID 3026B275-8E66-CF11-A6D9-00AA0062CE6C
        if (hasPrefix(head, len, new byte[]{0x30, 0x26, (byte) 0xB2, 0x75,
                (byte) 0x8E, 0x66, (byte) 0xCF, 0x11, (byte) 0xA6, (byte) 0xD9})) {
            return new Detection("wmv", "video/x-ms-wmv", 1.0);
        }
        // Unreal 包（uasset）：魔数 0x9E2A83C1（LE）
        if (hasPrefix(head, len, new byte[]{(byte) 0xC1, (byte) 0x83, 0x2A, (byte) 0x9E})) {
            return new Detection("uasset", "application/x-uasset", 1.0);
        }
        // Unreal Pak：魔数 0x5A6F12E1（LE）
        if (hasPrefix(head, len, new byte[]{(byte) 0xE1, 0x12, 0x6F, 0x5A})) {
            return new Detection("pak", "application/x-pak", 1.0);
        }

        // ---- 文本内容探测（无 BOM） ----
        String text = decodeAsciiPrefix(head, len);
        if (text == null) return Detection.UNKNOWN;
        String t = text.stripLeading();
        if (t.startsWith("{") || t.startsWith("[")) {
            return new Detection("json", "application/json", 0.6);
        }
        // HTML 必须排在通用 '<' 之前，否则 <html... 会被先判成 XML，
        // 下面的 html 分支就成了永远走不到的死代码
        if (t.startsWith("<html") || t.startsWith("<!DOCTYPE html")
                || t.startsWith("<!doctype html")) {
            return new Detection("html", "text/html", 0.7);
        }
        if (t.startsWith("<?xml") || t.startsWith("<")) {
            return new Detection("xml", "application/xml", 0.5);
        }
        if (t.startsWith("#!")) {
            return new Detection("sh", "application/x-sh", 0.8);
        }

        // 兜底：decodeAsciiPrefix 已经保证这里全是可打印 ASCII 且没有 NUL，
        // 因此可以安全地判定为纯文本。缺了这一步，.java / .py / .js / .go
        // 等源码文件（绝大多数不带 BOM）会落到 UNKNOWN，进而在
        // DocumentParser 里报「No handler for file」。
        // 置信度刻意压低：真正的具体格式已经在上面判完，这里只是兜底。
        return new Detection("txt", "text/plain", 0.3);
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

    /**
     * 尝试区分 Ogg 子类型（Opus/Vorbis/FLAC/Speex 等）。
     * 读取第一个 Ogg 页面，解析段表，检查识别包头。
     */
    private static String detectOggSubtype(byte[] head, int len) {
        // Ogg 页面结构：capture_pattern(4) + version(1) + header_type(1) + granule_position(8)
        // + bitstream_serial(4) + page_sequence(4) + page_checksum(4) + page_segments(1)
        // + segment_table[page_segments] + segment_data...
        if (len < 27) return null;
        if (head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') return null;
        int version = head[4] & 0xFF;
        if (version != 0) return null;
        int headerType = head[5] & 0xFF;
        if (headerType != 2) return null; // 只关心首页
        // 跳过 granule_position(8) + bitstream_serial(4) + page_sequence(4) + page_checksum(4) = 20
        int pos = 6 + 20;
        if (pos >= len) return null;
        int numSegments = head[pos++] & 0xFF;
        if (pos + numSegments > len) return null;
        // 计算第一个段的数据起始位置
        int segmentDataStart = pos + numSegments;
        if (segmentDataStart >= len) return null;
        // 第一个段的长度
        int firstSegLen = head[pos] & 0xFF;
        if (segmentDataStart + firstSegLen > len) return null;
        int identStart = segmentDataStart;
        // 检查识别头
        if (firstSegLen >= 8) {
            // OpusHead: "OpusHead" (8字节)
            if (len >= identStart + 8) {
                boolean isOpus = true;
                byte[] opusSig = "OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                for (int i = 0; i < 8; i++) {
                    if (head[identStart + i] != opusSig[i]) {
                        isOpus = false;
                        break;
                    }
                }
                if (isOpus) return "opus";
            }
            // Vorbis: 0x01 + "vorbis"
            if (firstSegLen >= 7 && head[identStart] == 1) {
                if (len >= identStart + 7) {
                    boolean isVorbis = true;
                    byte[] vorbisSig = "vorbis".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    for (int i = 0; i < 6; i++) {
                        if (head[identStart + 1 + i] != vorbisSig[i]) {
                            isVorbis = false;
                            break;
                        }
                    }
                    if (isVorbis) return "ogg"; // Vorbis 仍归类为 ogg
                }
            }
            // FLAC in Ogg: 0x7F + "FLAC"
            if (firstSegLen >= 5 && head[identStart] == (byte) 0x7F) {
                if (len >= identStart + 5) {
                    if (head[identStart + 1] == 'F' && head[identStart + 2] == 'L'
                            && head[identStart + 3] == 'A' && head[identStart + 4] == 'C') {
                        return "flac";
                    }
                }
            }
            // Speex: "Speex   " (8字节)
            if (firstSegLen >= 8) {
                if (head[identStart] == 'S' && head[identStart + 1] == 'p'
                        && head[identStart + 2] == 'e' && head[identStart + 3] == 'e'
                        && head[identStart + 4] == 'x') {
                    return "spx";
                }
            }
        }
        return null;
    }
}

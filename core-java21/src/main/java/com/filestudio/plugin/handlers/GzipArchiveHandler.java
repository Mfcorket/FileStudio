package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * GZIP 压缩文件处理器：提取压缩方式、时间戳、原始文件名与解压后大小。
 *
 * <p>PARTIAL 能力——可查看元数据与解压内容，但不作为文本编辑。
 *
 * <p>实现依据 RFC 1952 头部结构：
 * <pre>
 *   偏移  长度  含义
 *    0      2   魔数 0x1F 0x8B
 *    2      1   压缩方式 CM（8 = deflate）
 *    3      1   标志位 FLG（FTEXT/FHCRC/FEXTRA/FNAME/FCOMMENT）
 *    4      4   修改时间 MTIME（Unix 秒）
 *    8      1   额外标志 XFL
 *    9      1   操作系统 OS
 *   末尾    4   CRC32
 *   末尾    4   ISIZE（原始大小 mod 2^32）
 * </pre>
 */
public class GzipArchiveHandler implements FileHandler {

    /**
     * GZIP 压缩文件处理器。
     */
    public GzipArchiveHandler() {}

    private static final Set<String> SUPPORTED = Set.of("gz", "gzip", "tgz");

    private static final int MAGIC_0 = 0x1F;
    private static final int MAGIC_1 = 0x8B;
    private static final int CM_DEFLATE = 8;

    /** 头部最大长度：10 字节固定区 + 可选字段，给足余量。 */
    private static final int MAX_HEADER_BYTES = 1024;

    /** 解压时的字节上限，防止压缩炸弹耗尽内存。 */
    private static final int MAX_DECOMPRESS_BYTES = 64 * 1024 * 1024;

    @Override
    public String getExtension() {
        return "gz";
    }

    @Override
    public String getMimeType() {
        return "application/gzip";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "GZIP compressed file";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isGzip(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("GZIP file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, MAX_HEADER_BYTES);
        byte[] tail = readTail(path, 8);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "gz");
        meta.put("viewOnly", true);

        if (head.length < 10 || (head[0] & 0xFF) != MAGIC_0 || (head[1] & 0xFF) != MAGIC_1) {
            meta.put("valid", false);
            meta.put("error", "Missing or truncated GZIP header");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        meta.put("valid", true);
        int cm = head[2] & 0xFF;
        meta.put("compressionMethod", cm == CM_DEFLATE ? "deflate" : "unknown(" + cm + ")");
        int flg = head[3] & 0xFF;
        meta.put("flags", describeFlags(flg));
        long mtime = leUnsignedInt(head, 4);
        meta.put("modifiedAt", formatEpochSeconds(mtime));

        // 变长字段：按 FLG 依次解析 FEXTRA / FNAME / FCOMMENT
        int pos = 10;
        if ((flg & 0x04) != 0 && pos + 2 <= head.length) {
            int xlen = (head[pos] & 0xFF) | ((head[pos + 1] & 0xFF) << 8);
            pos += 2 + xlen;
        }
        if ((flg & 0x08) != 0) {
            String name = readCString(head, pos);
            if (!name.isEmpty()) meta.put("originalName", name);
            pos += name.length() + 1;
        }
        if ((flg & 0x10) != 0) {
            String comment = readCString(head, pos);
            if (!comment.isEmpty()) meta.put("comment", comment);
        }

        // 尾部：CRC32 + ISIZE
        if (tail.length == 8) {
            meta.put("crc32", String.format("0x%08x", leUnsignedInt(tail, 0)));
            long isize = leUnsignedInt(tail, 4);
            meta.put("uncompressedSize", isize);
            if (file.length() > 0) {
                meta.put("compressionRatio", round2((double) isize / file.length()));
            }
        }

        // 实际解压一次，验证完整性并给出真实大小
        DecompressResult dr = decompress(path);
        if (dr.error() != null) {
            meta.put("integrity", "failed: " + dr.error());
        } else {
            meta.put("integrity", "ok");
            meta.put("actualUncompressedSize", dr.size());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("GZIP files are read-only; decompress to a new file instead");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] tail = readTail(file.toPath(), 8);
            if (tail.length == 8) {
                meta.put("uncompressedSize", leUnsignedInt(tail, 4));
            }
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    /** 解压结果。 */
    private record DecompressResult(long size, String error) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isGzip(File file) {
        try {
            byte[] head = readHead(file.toPath(), 2);
            return head.length >= 2
                    && (head[0] & 0xFF) == MAGIC_0
                    && (head[1] & 0xFF) == MAGIC_1;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readHead(Path path, int len) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            if (off == len) return buf;
            byte[] trimmed = new byte[off];
            System.arraycopy(buf, 0, trimmed, 0, off);
            return trimmed;
        } catch (IOException e) {
            throw new FileStudioException("Failed reading GZIP header: " + path, e);
        }
    }

    /** 读取文件末尾 {@code len} 字节。 */
    private static byte[] readTail(Path path, int len) {
        try (var raf = new java.io.RandomAccessFile(path.toFile(), "r")) {
            long size = raf.length();
            if (size < len) return new byte[0];
            byte[] buf = new byte[len];
            raf.seek(size - len);
            raf.readFully(buf);
            return buf;
        } catch (IOException e) {
            throw new FileStudioException("Failed reading GZIP trailer: " + path, e);
        }
    }

    /** 解压并统计字节数，超过上限即中止。 */
    private static DecompressResult decompress(Path path) {
        try (InputStream in = Files.newInputStream(path);
             GZIPInputStream gz = new GZIPInputStream(in)) {
            long total = 0;
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                total += n;
                if (total > MAX_DECOMPRESS_BYTES) {
                    return new DecompressResult(total, "exceeded " + MAX_DECOMPRESS_BYTES + " byte limit");
                }
            }
            return new DecompressResult(total, null);
        } catch (IOException | RuntimeException e) {
            String msg = e.getMessage();
            return new DecompressResult(0, msg == null ? e.getClass().getSimpleName() : msg);
        }
    }

    private static String describeFlags(int flg) {
        StringBuilder sb = new StringBuilder();
        if ((flg & 0x01) != 0) sb.append("TEXT ");
        if ((flg & 0x02) != 0) sb.append("HCRC ");
        if ((flg & 0x04) != 0) sb.append("EXTRA ");
        if ((flg & 0x08) != 0) sb.append("NAME ");
        if ((flg & 0x10) != 0) sb.append("COMMENT ");
        return sb.toString().trim();
    }

    private static String readCString(byte[] buf, int from) {
        int start = Math.max(0, from);
        for (int i = start; i < buf.length; i++) {
            if (buf[i] == 0) {
                return new String(buf, start, i - start, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        }
        return "";
    }

    private static String formatEpochSeconds(long sec) {
        if (sec <= 0) return "unknown";
        return java.time.Instant.ofEpochSecond(sec).toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static long leUnsignedInt(byte[] h, int off) {
        if (off + 4 > h.length) return 0;
        return (h[off] & 0xFFL)
                | ((h[off + 1] & 0xFFL) << 8)
                | ((h[off + 2] & 0xFFL) << 16)
                | ((h[off + 3] & 0xFFL) << 24);
    }
}

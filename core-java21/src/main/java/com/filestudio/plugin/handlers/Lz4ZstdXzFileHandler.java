package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;

/**
 * 单文件压缩格式处理器：LZ4 / Zstandard (zstd) / XZ (LZMA2)。
 *
 * <p>这些格式通常包含单个压缩数据流，不包含归档目录结构。
 * VIEW_ONLY 能力——只能读取元数据与解压大小。
 *
 * <p>实现：
 * <ul>
 *   <li>LZ4: 魔数 04 22 4D 18 (legacy) 或 02 21 4D 18 (block)，解析帧头获取块大小、内容大小、校验标志</li>
 *   <li>Zstd: 魔数 28 B5 2F FD，解析帧头获取窗口大小、内容大小、字典 ID、校验和</li>
 *   <li>XZ: 魔数 FD 37 7A 58 5A 00，解析流头/块头获取校验类型、过滤器属性</li>
 * </ul>
 */
public class Lz4ZstdXzFileHandler implements FileHandler {

    /**
     * LZ4 / Zstd / XZ 处理器。
     */
    public Lz4ZstdXzFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("lz4", "zst", "zstd", "xz", "lzma");

    /** 最多读取的头部字节。 */
    private static final int MAX_READ = 1024 * 1024;

    /** LZ4 帧魔数。 */
    private static final int LZ4_MAGIC_LEGACY = 0x184D2204; // little-endian: 04 22 4D 18
    private static final int LZ4_MAGIC = 0x184D2102;       // 02 21 4D 18
    private static final int LZ4_MAGIC_SKIPPABLE_MASK = 0xFFFFFF00;
    private static final int LZ4_MAGIC_SKIPPABLE = 0x184D2000; // 00 20 4D 18

    /** Zstd 帧魔数。 */
    private static final int ZSTD_MAGIC = 0xFDB528; // 28 B5 2F FD (little-endian 前 3 字节)
    private static final long ZSTD_MAGIC_FULL = 0xFD2FB528L; // 4 字节

    /** XZ 魔数。 */
    private static final int[] XZ_MAGIC = {0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00};

    @Override
    public String getExtension() {
        return "lz4";
    }

    @Override
    public String getMimeType() {
        return "application/x-lz4";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "LZ4 / Zstandard / XZ compressed data";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isCompressed(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Compressed file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        byte[] data;
        try {
            data = readPrefix(path, MAX_READ);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading compressed file: " + path, e);
        }

        String format = detectFormat(data);
        meta.put("format", format);

        try {
            switch (format) {
                case "lz4" -> parseLz4(data, meta);
                case "zstd" -> parseZstd(data, meta);
                case "xz" -> parseXz(data, meta);
                default -> meta.put("error", "Unknown format");
            }
        } catch (RuntimeException e) {
            meta.put("parseError", e.getMessage());
        }

        return new Document(path, mimeFor(format), format, "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Compressed files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", doc.getMetadata().get("format"));
            meta.put("contentSize", doc.getMetadata().get("contentSize"));
            meta.put("blockCount", doc.getMetadata().get("blockCount"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isCompressed(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(8);
            return detectFormat(head) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String detectFormat(byte[] data) {
        if (data.length < 6) return null;
        // LZ4
        if (data.length >= 4) {
            int magic = ((data[0] & 0xFF) |
                    ((data[1] & 0xFF) << 8) |
                    ((data[2] & 0xFF) << 16) |
                    ((data[3] & 0xFF) << 24));
            if (magic == LZ4_MAGIC || magic == LZ4_MAGIC_LEGACY ||
                    (magic & LZ4_MAGIC_SKIPPABLE_MASK) == LZ4_MAGIC_SKIPPABLE) {
                return "lz4";
            }
        }
        // Zstd: 28 B5 2F FD
        if (data.length >= 4) {
            long magic = ((data[0] & 0xFFL) |
                    ((data[1] & 0xFFL) << 8) |
                    ((data[2] & 0xFFL) << 16) |
                    ((data[3] & 0xFFL) << 24));
            if (magic == ZSTD_MAGIC_FULL) {
                return "zstd";
            }
        }
        // XZ: FD 37 7A 58 5A 00
        if (data.length >= 6) {
            boolean match = true;
            for (int i = 0; i < 6; i++) {
                if ((data[i] & 0xFF) != XZ_MAGIC[i]) {
                    match = false;
                    break;
                }
            }
            if (match) return "xz";
        }
        return null;
    }

    private static void parseLz4(byte[] data, Map<String, Object> meta) {
        // LZ4 帧格式：magic(4) + FLG(1) + BD(1) + HC(0/1) + contentSize(0/8) + blocks...
        if (data.length < 7) {
            meta.put("error", "Truncated LZ4 frame");
            return;
        }
        int pos = 4;
        int flg = data[pos++] & 0xFF;
        int bd = data[pos++] & 0xFF;
        int version = (flg >> 6) & 0x3;
        boolean blockIndependence = (flg & 0x20) != 0;
        boolean blockChecksum = (flg & 0x10) != 0;
        boolean contentSizeFlag = (flg & 0x08) != 0;
        boolean contentChecksumFlag = (flg & 0x04) != 0;
        boolean dictIdFlag = (flg & 0x02) != 0;
        boolean reserved = (flg & 0x01) != 0;

        int blockMaxSizeCode = (bd >> 4) & 0x7;
        int blockMaxSize = blockMaxSizeToBytes(blockMaxSizeCode);

        long contentSize = -1;
        if (contentSizeFlag) {
            if (pos + 8 > data.length) {
                meta.put("error", "Truncated contentSize");
                return;
            }
            contentSize = readUInt64(data, pos);
            pos += 8;
        }

        int dictId = 0;
        if (dictIdFlag) {
            if (pos + 4 > data.length) {
                meta.put("error", "Truncated dictId");
                return;
            }
            dictId = (int) readUInt32(data, pos);
            pos += 4;
        }

        int hc = 0;
        if (version == 0 || version == 1) {
            // 版本 0/1 校验字节在 dictId 之后
            if (pos < data.length) {
                hc = data[pos] & 0xFF;
            }
        }

        meta.put("version", version);
        meta.put("blockIndependence", blockIndependence);
        meta.put("blockChecksum", blockChecksum);
        meta.put("contentChecksum", contentChecksumFlag);
        meta.put("blockMaxSize", blockMaxSize);
        if (contentSize >= 0) meta.put("contentSize", contentSize);
        if (dictId != 0) meta.put("dictId", dictId);
        meta.put("headerChecksum", hc);

        // 尝试解析第一个块头
        int blockPos = pos;
        int blockCount = 0;
        long totalCompressed = 0;
        while (blockPos + 4 <= data.length) {
            int blockHeader = (int) readUInt32(data, blockPos);
            blockPos += 4;
            boolean lastBlock = (blockHeader & 0x80000000) != 0;
            int blockSize = blockHeader & 0x7FFFFFFF;
            if (blockSize == 0) {
                // 结束标记
                break;
            }
            if (blockPos + blockSize > data.length) {
                break; // 数据不全
            }
            blockCount++;
            totalCompressed += blockSize;
            blockPos += blockSize;
            if (lastBlock) break;
        }
        if (blockCount > 0) {
            meta.put("blockCount", blockCount);
            meta.put("totalCompressedSize", totalCompressed);
        }
    }

    private static int blockMaxSizeToBytes(int code) {
        return switch (code) {
            case 4 -> 64 * 1024;
            case 5 -> 256 * 1024;
            case 6 -> 1024 * 1024;
            case 7 -> 4 * 1024 * 1024;
            default -> 0;
        };
    }

    private static void parseZstd(byte[] data, Map<String, Object> meta) {
        // Zstd 帧格式：magic(4) + frame header
        if (data.length < 6) {
            meta.put("error", "Truncated Zstd frame");
            return;
        }
        int pos = 4;
        int fhb = data[pos++] & 0xFF;
        int fcs = (fhb >> 6) & 0x3;      // Frame_Content_Size_flag
        boolean singleSegment = (fhb & 0x20) != 0;
        boolean unused = (fhb & 0x10) != 0;
        boolean dictIdFlag = (fhb & 0x08) != 0;
        boolean contentChecksumFlag = (fhb & 0x04) != 0;
        boolean reserved = (fhb & 0x03) != 0;

        long windowLog = 0;
        if (singleSegment) {
            // 单段：窗口大小编码在 fhb 的低 5 位
            windowLog = fhb & 0x1F;
        } else {
            // 多段：需要额外字节
            if (pos >= data.length) return;
            int wlb = data[pos++] & 0xFF;
            windowLog = 10 + (wlb & 0x3);
            // 其余字节用于更大的窗口，简化处理
        }

        long contentSize = -1;
        if (fcs == 1) {
            if (pos + 1 > data.length) return;
            contentSize = data[pos++] & 0xFF;
        } else if (fcs == 2) {
            if (pos + 2 > data.length) return;
            contentSize = ((data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)) + 256;
            pos += 2;
        } else if (fcs == 3) {
            if (pos + 8 > data.length) return;
            contentSize = readUInt64(data, pos);
            pos += 8;
        }

        int dictId = 0;
        if (dictIdFlag) {
            if (pos + 4 > data.length) return;
            dictId = (int) readUInt32(data, pos);
            pos += 4;
        }

        meta.put("frameContentSizeFlag", fcs);
        meta.put("singleSegment", singleSegment);
        meta.put("windowLog", windowLog);
        meta.put("contentChecksum", contentChecksumFlag);
        if (contentSize >= 0) meta.put("contentSize", contentSize);
        if (dictId != 0) meta.put("dictId", dictId);

        // 统计块数量（简化）
        int blockCount = 0;
        int blockPos = pos;
        while (blockPos + 3 <= data.length) {
            int blockHeader = ((data[blockPos] & 0xFF) |
                    ((data[blockPos + 1] & 0xFF) << 8) |
                    ((data[blockPos + 2] & 0xFF) << 16));
            blockPos += 3;
            boolean lastBlock = (blockHeader & 0x1) != 0;
            int blockType = (blockHeader >> 1) & 0x3;
            int blockSize = blockHeader >> 3;
            if (blockPos + blockSize > data.length) break;
            blockCount++;
            blockPos += blockSize;
            if (lastBlock) break;
        }
        if (blockCount > 0) {
            meta.put("blockCount", blockCount);
        }
    }

    private static void parseXz(byte[] data, Map<String, Object> meta) {
        // XZ 流格式：Stream Header(12) + Block(s) + Stream Footer(12) + optional padding
        if (data.length < 12) {
            meta.put("error", "Truncated XZ stream");
            return;
        }
        // Stream Header: magic(6) + stream_flags(2) + crc32(4)
        int pos = 6;
        int streamFlags = ((data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8));
        pos += 2;
        // CRC32
        pos += 4;

        // 解析块
        int blockCount = 0;
        long totalUncompressed = 0;
        long totalCompressed = 0;
        boolean hasCheck = false;
        int checkType = 0;

        // 读取第一个块头
        while (pos < data.length - 12) { // 留出 footer 空间
            // 块头：header size(1) + header CRC(4) + flags + compressed size + uncompressed size + filters
            if (pos >= data.length) break;
            int headerSize = (data[pos] & 0xFF) * 4; // 以 4 字节为单位
            pos++;
            if (headerSize == 0) {
                // 0 表示流结束标记
                break;
            }
            if (pos + headerSize > data.length) break;
            int headerEnd = pos + headerSize;
            int headerCrc = (int) readUInt32(data, pos);
            pos += 4;

            int flags = data[pos++] & 0xFF;
            hasCheck = (flags & 0x80) != 0;
            if (hasCheck) {
                checkType = data[pos++] & 0xFF;
            }
            // compressed size
            long compSize = -1;
            if ((flags & 0x40) != 0) {
                compSize = readVli(data, pos);
                pos += vliSize(compSize);
            }
            // uncompressed size
            long uncompSize = -1;
            if ((flags & 0x20) != 0) {
                uncompSize = readVli(data, pos);
                pos += vliSize(uncompSize);
            }

            // 过滤器属性 - 简化：只读取 filter ID
            while (pos + 1 < headerEnd) {
                int filterId = data[pos++] & 0xFF;
                if (filterId == 0) break; // 结束标记
                long filterSize = readVli(data, pos);
                pos += vliSize(filterSize);
                if (filterSize > 0 && pos + filterSize <= headerEnd) {
                    // filter properties
                    pos += filterSize;
                }
            }
            pos = headerEnd;

            // 块数据
            if (compSize >= 0 && pos + compSize <= data.length) {
                blockCount++;
                totalCompressed += compSize;
                if (uncompSize >= 0) totalUncompressed += uncompSize;
                pos += compSize;
            } else {
                break;
            }
            // 块校验和
            if (hasCheck) {
                pos += checkSize(checkType);
            }
        }

        meta.put("blockCount", blockCount);
        if (totalUncompressed > 0) meta.put("contentSize", totalUncompressed);
        if (totalCompressed > 0) meta.put("compressedSize", totalCompressed);
        meta.put("hasCheck", hasCheck);
        if (hasCheck) meta.put("checkType", checkType);
    }

    // VLI (Variable Length Integer) 读取
    private static long readVli(byte[] data, int pos) {
        long value = 0;
        for (int i = 0; i < 9; i++) {
            if (pos + i >= data.length) break;
            int b = data[pos + i] & 0xFF;
            value |= (b & 0x7FL) << (7 * i);
            if ((b & 0x80) == 0) break;
        }
        return value;
    }

    private static int vliSize(long vli) {
        if (vli < 0) return 0;
        if (vli == 0) return 1;
        int size = 0;
        while (vli > 0) {
            vli >>>= 7;
            size++;
        }
        return size;
    }

    private static int checkSize(int checkType) {
        return switch (checkType) {
            case 1 -> 4;  // CRC32
            case 4 -> 8;  // CRC64
            case 10 -> 16; // SHA-256
            default -> 0;
        };
    }

    private static long readUInt32(byte[] d, int off) {
        if (off + 4 > d.length) return 0;
        return ((long) (d[off] & 0xFF) |
                ((d[off + 1] & 0xFFL) << 8) |
                ((d[off + 2] & 0xFFL) << 16) |
                ((d[off + 3] & 0xFFL) << 24));
    }

    private static long readUInt64(byte[] d, int off) {
        if (off + 8 > d.length) return 0;
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (d[off + i] & 0xFFL)) << (8 * i);
        }
        return v;
    }

    private static String mimeFor(String format) {
        return switch (format) {
            case "lz4" -> "application/x-lz4";
            case "zstd" -> "application/zstd";
            case "xz" -> "application/x-xz";
            default -> "application/octet-stream";
        };
    }

    private static byte[] readPrefix(Path path, int limit) throws IOException {
        long size = Files.size(path);
        int len = (int) Math.min(size, limit);
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(len);
        }
    }
}
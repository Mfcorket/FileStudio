package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;

import org.brotli.dec.BrotliInputStream;

/**
 * 字体文件处理器：从 TTF/OTF 文件头提取字体名、版本、字重、unitsPerEm 等元数据。
 *
 * <p>支持：TTF / OTF / WOFF / WOFF2。
 * VIEW_ONLY 能力——字体不可作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>解析表目录，定位 name / head / OS/2 表</li>
 *   <li>从 name 表提取字体名（ID 4）、版本（ID 5）、PostScript 名（ID 6）</li>
 *   <li>从 head 表提取 unitsPerEm</li>
 *   <li>从 OS/2 表提取字重（usWeightClass）</li>
 *   <li>WOFF2: 解压 Brotli 压缩的表目录并提取元数据</li>
 * </ul>
 */
public class FontFileHandler implements FileHandler {

    /**
     * 字体格式处理器。
     */
    public FontFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("ttf", "otf", "woff", "woff2");

    @Override
    public String getExtension() {
        return "ttf";
    }

    @Override
    public String getMimeType() {
        return "font/ttf";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Font file (TTF/OTF/WOFF/WOFF2)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isFont(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Font file not readable: " + file);
        }
        Path path = file.toPath();
        FontInfo info = parseFont(path);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", info.format());
        meta.put("viewOnly", true);
        if (info.unitsPerEm() > 0) meta.put("unitsPerEm", info.unitsPerEm());
        if (info.weight() > 0) meta.put("weight", info.weight());
        if (info.fontName() != null) meta.put("fontName", info.fontName());
        if (info.version() != null) meta.put("version", info.version());
        if (info.postScriptName() != null) meta.put("postScriptName", info.postScriptName());
        if (info.numTables() > 0) meta.put("numTables", info.numTables());
        if (info.totalSfntSize() > 0) meta.put("totalSfntSize", info.totalSfntSize());

        return new Document(path, mimeFor(info.format()), info.format(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Font files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            FontInfo info = parseFont(file.toPath());
            meta.put("format", info.format());
            if (info.fontName() != null) meta.put("fontName", info.fontName());
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private record FontInfo(String format, int unitsPerEm, int weight,
                          String fontName, String version, String postScriptName,
                          int numTables, long totalSfntSize) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isFont(File file) {
        try {
            byte[] head = new byte[4];
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                int n = in.read(head);
                if (n < 4) return false;
                // TTF: 0x00010000, OTF: "OTTO", WOFF: "wOFF", WOFF2: "wOF2"
                return (head[0] == 0x00 && head[1] == 0x01 && head[2] == 0x00 && head[3] == 0x00)
                        || (head[0] == 'O' && head[1] == 'T' && head[2] == 'T' && head[3] == 'O')
                        || (head[0] == 'w' && head[1] == 'O' && head[2] == 'F'
                        && (head[3] == 'F' || head[3] == '2'));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析字体元数据。 */
    static FontInfo parseFont(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            if (data.length < 12) throw new FileStudioException("Truncated font header");
            String format = detectFormat(data);
            switch (format) {
                case "woff2" -> {
                    return parseWoff2Header(data);
                }
                default -> {
                    // ttf / otf / woff：表目录可直接读取
                    int numTables = beShort(data, 4);
                    int unitsPerEm = 0;
                    int weight = 0;
                    String fontName = null;
                    String version = null;
                    String postScriptName = null;
                    long totalSfntSize = 0;

                    if ("woff".equals(format)) {
                        // WOFF 头：flavor(4) length(4) numTables(2) reserved(2) totalSfntSize(4) ...
                        numTables = beShort(data, 12);
                        totalSfntSize = beInt(data, 16) & 0xFFFFFFFFL;
                        // 表记录从偏移 44 开始，每条 20 字节：
                        // tag(4) offset(4) compLength(4) origLength(4) origChecksum(4)
                        int tableDirOffset = 44;
                        for (int i = 0; i < numTables; i++) {
                            int entryOffset = tableDirOffset + i * 20;
                            if (entryOffset + 20 > data.length) break;
                            int tag = beInt(data, entryOffset);
                            int offset = beInt(data, entryOffset + 4);
                            int compLength = beInt(data, entryOffset + 8);
                            int origLength = beInt(data, entryOffset + 12);
                            // 提取表标签
                            String tagStr = new String(new byte[]{
                                (byte)((tag >> 24) & 0xFF),
                                (byte)((tag >> 16) & 0xFF),
                                (byte)((tag >> 8) & 0xFF),
                                (byte)(tag & 0xFF)
                            });
                            if (compLength == origLength) {
                                if (offset + origLength <= data.length) {
                                    byte[] tableData = new byte[origLength];
                                    System.arraycopy(data, offset, tableData, 0, origLength);
                                    if ("name".equals(tagStr) && tableData.length > 0) {
                                        fontName = extractName(tableData, 4);
                                        version = extractName(tableData, 5);
                                        postScriptName = extractName(tableData, 6);
                                    } else if ("head".equals(tagStr) && tableData.length >= 18) {
                                        unitsPerEm = beShort(tableData, 18);
                                    } else if ("OS/2".equals(tagStr) && tableData.length >= 6) {
                                        weight = beShort(tableData, 4);
                                    }
                                }
                            }
                        }
                    } else {
                        // TTF/OTF: 表记录从偏移 12 开始，每条 16 字节
                        int tableDirOffset = 12;
                        for (int i = 0; i < numTables; i++) {
                            int entryOffset = tableDirOffset + i * 16;
                            if (entryOffset + 16 > data.length) break;
                            int tag = beInt(data, entryOffset);
                            int offset = beInt(data, entryOffset + 8);
                            int length = beInt(data, entryOffset + 12);
                            String tagStr = new String(new byte[]{
                                (byte)((tag >> 24) & 0xFF),
                                (byte)((tag >> 16) & 0xFF),
                                (byte)((tag >> 8) & 0xFF),
                                (byte)(tag & 0xFF)
                            });
                            if (offset + length <= data.length) {
                                byte[] tableData = new byte[length];
                                System.arraycopy(data, offset, tableData, 0, length);
                                if ("name".equals(tagStr) && tableData.length > 0) {
                                    fontName = extractName(tableData, 4);
                                    version = extractName(tableData, 5);
                                    postScriptName = extractName(tableData, 6);
                                } else if ("head".equals(tagStr) && tableData.length >= 18) {
                                    unitsPerEm = beShort(tableData, 18);
                                } else if ("OS/2".equals(tagStr) && tableData.length >= 6) {
                                    weight = beShort(tableData, 4);
                                }
                            }
                        }
                    }
                    return new FontInfo(format, unitsPerEm, weight, fontName, version, postScriptName, numTables, totalSfntSize);
                }
            }
        } catch (IOException e) {
            throw new FileStudioException("Failed to read font file: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 WOFF2 头部并解压 Brotli 压缩的表目录。
     * WOFF2 结构：
     * - 4 字节 signature "wOF2"
     * - 4 字节 flavor
     * - 4 字节 length
     * - 4 字节 numTables
     * - 4 字节 reserved
     * - 4 字节 totalSfntSize
     * - 4 字节 totalCompressedSize
     * - 2 字节 majorVersion, 2 字节 minorVersion
     * - 4 字节 metaOffset, 4 字节 metaLength, 4 字节 metaOrigLength
     * - 4 字节 privOffset, 4 字节 privLength
     * 然后是表目录（压缩），每条：tag(4) flags(1) index(3) offset(varint) length(varint)
     * 最后是压缩的表数据
     */
    private static FontInfo parseWoff2Header(byte[] data) {
        if (data.length < 48) {
            throw new FileStudioException("Truncated WOFF2 header");
        }
        // WOFF2 头部结构：
        // 0-3: signature "wOF2"
        // 4-7: flavor
        // 8-11: length
        // 12-13: numTables (uint16)
        // 14-15: reserved (uint16)
        // 16-19: totalSfntSize (uint32)
        // 20-23: totalCompressedSize (uint32)
        // 24-25: majorVersion (uint16)
        // 26-27: minorVersion (uint16)
        // 28-31: metaOffset (uint32)
        // 32-35: metaLength (uint32)
        // 36-39: metaOrigLength (uint32)
        // 40-43: privOffset (uint32)
        // 44-47: privLength (uint32)
        // 表目录从偏移 48 开始
        int numTables = beShort(data, 12);
        long totalSfntSize = beInt(data, 16) & 0xFFFFFFFFL;
        long totalCompressedSize = beInt(data, 20) & 0xFFFFFFFFL;

        // 如果只有头部（无压缩表目录），直接返回基本信息
        if (data.length < 48 + totalCompressedSize) {
            return new FontInfo("woff2", 0, 0, null, null, null, numTables, totalSfntSize);
        }

        // 解压表目录
        byte[] tableDirCompressed = new byte[(int) totalCompressedSize];
        System.arraycopy(data, 48, tableDirCompressed, 0, tableDirCompressed.length);

        byte[] tableDirData;
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(tableDirCompressed);
             BrotliInputStream brotliIn = new BrotliInputStream(bis)) {
            tableDirData = brotliIn.readAllBytes();
        } catch (IOException e) {
            throw new FileStudioException("Failed to decompress WOFF2 table directory: " + e.getMessage(), e);
        }

        // 解析表目录
        int pos = 0;
        int unitsPerEm = 0;
        int weight = 0;
        String fontName = null;
        String version = null;
        String postScriptName = null;
        int[] posRef = new int[]{0};

        for (int i = 0; i < numTables; i++) {
            if (posRef[0] + 4 > tableDirData.length) break;
            int tag = beInt(tableDirData, posRef[0]);
            posRef[0] += 4;
            int flags = tableDirData[posRef[0]++] & 0xFF;
            // index (3 bytes)
            posRef[0] += 3;
            // offset and length are variable-length encoded
            long offset = readVarInt(tableDirData, posRef);
            long length = readVarInt(tableDirData, posRef);
            if (posRef[0] < 0) break;

            // 读取表标签
            String tagStr = String.format("%04X", tag).replace(' ', '0');

            // 读取表数据（需要解压）
            if (offset >= 0 && length > 0 && offset + length <= data.length) {
                byte[] compressedTable = new byte[(int) length];
                System.arraycopy(data, 48 + tableDirCompressed.length + (int) offset, compressedTable, 0, (int) length);

                byte[] tableData;
                try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(compressedTable);
                     BrotliInputStream brotliIn = new BrotliInputStream(bis)) {
                    tableData = brotliIn.readAllBytes();
                } catch (IOException e) {
                    // 解压失败，跳过此表
                    continue;
                }

                if ("name".equals(tagStr) && tableData.length > 0) {
                    fontName = extractName(tableData, 4);
                    version = extractName(tableData, 5);
                    postScriptName = extractName(tableData, 6);
                } else if ("head".equals(tagStr) && tableData.length >= 18) {
                    unitsPerEm = beShort(tableData, 18);
                } else if ("OS/2".equals(tagStr) && tableData.length >= 6) {
                    weight = beShort(tableData, 4);
                } else if ("post".equals(tagStr) && tableData.length >= 4) {
                    // post table 可能包含版本信息
                }
            }
        }

        return new FontInfo("woff2", unitsPerEm, weight, fontName, version, postScriptName, numTables, totalSfntSize);
    }

    /** 读取 WOFF2 变长整数 (base-128)。通过 int[] 传递位置引用。 */
    private static long readVarInt(byte[] data, int[] pos) {
        long value = 0;
        int shift = 0;
        int p = pos[0];
        for (int i = 0; i < 10; i++) {
            if (p >= data.length) break;
            int b = data[p++] & 0xFF;
            value |= (long)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        pos[0] = p;
        return value;
    }

    private static String detectFormat(byte[] head) {
        if (head[0] == 0x00 && head[1] == 0x01 && head[2] == 0x00 && head[3] == 0x00) return "ttf";
        if (head[0] == 'O' && head[1] == 'T' && head[2] == 'T' && head[3] == 'O') return "otf";
        if (head[0] == 'w' && head[1] == 'O' && head[2] == 'F' && head[3] == 'F') return "woff";
        if (head[0] == 'w' && head[1] == 'O' && head[2] == 'F' && head[3] == '2') return "woff2";
        return "ttf";
    }

    /** 从 name 表提取指定 nameID 的字符串。 */
    private static String extractName(byte[] nameTable, int targetId) {
        try {
            int count = beShort(nameTable, 2);
            int stringOffset = beShort(nameTable, 4);
            for (int i = 0; i < count; i++) {
                int rec = 6 + i * 12; // name 表头占 6 字节
                int nameId = beShort(nameTable, rec + 6);
                int length = beShort(nameTable, rec + 8);
                int offset = beShort(nameTable, rec + 10);
                if (nameId == targetId && offset + length <= nameTable.length) {
                    int absOffset = stringOffset + offset;
                    if (absOffset + length <= nameTable.length) {
                        // 检测编码：platformID 3 (Windows) 通常为 UTF-16BE
                        int platformId = beShort(nameTable, rec);
                        if (platformId == 3 && length >= 2 && (length % 2 == 0)) {
                            return new String(nameTable, absOffset, length, StandardCharsets.UTF_16BE);
                        }
                        return new String(nameTable, absOffset, length, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        return null;
    }

    private static int beShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String mimeFor(String ext) {
        return switch (ext) {
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> "font/*";
        };
    }
}
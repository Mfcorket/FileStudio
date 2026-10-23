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

/**
 * 字体文件处理器：从 TTF/OTF 文件头提取字体名、版本、字重、unitsPerEm 等元数据。
 *
 * <p>支持：TTF / OTF / WOFF。
 * VIEW_ONLY 能力——字体不可作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>解析表目录，定位 name / head / OS/2 表</li>
 *   <li>从 name 表提取字体名（ID 4）、版本（ID 5）、PostScript 名（ID 6）</li>
 *   <li>从 head 表提取 unitsPerEm</li>
 *   <li>从 OS/2 表提取字重（usWeightClass）</li>
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
        return "Font file (TTF/OTF/WOFF)";
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
                    // WOFF2 表目录使用 Brotli 压缩，JDK 无 Brotli 解码器；
                    // 头部仍含 flavor / 版本 / 总展开大小，可安全提取。
                    return parseWoff2Header(data);
                }
                default -> {
                    // ttf / otf / woff：表目录可直接读取
                    int numTables = beShort(data, 4); // sfnt 与 WOFF 头部 offset 相同
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
                        for (int i = 0; i < numTables; i++) {
                            int rec = 44 + i * 20;
                            if (rec + 20 > data.length) break;
                            String tag = new String(data, rec, 4, StandardCharsets.US_ASCII);
                            int offset = beInt(data, rec + 4);
                            int compLength = beInt(data, rec + 8);
                            int origLength = beInt(data, rec + 12);
                            byte[] table = woffTable(data, offset, compLength, origLength);
                            if (table == null) continue;
                            if ("name".equals(tag)) {
                                fontName = extractName(table, 4);
                                version = extractName(table, 5);
                                postScriptName = extractName(table, 6);
                            } else if ("head".equals(tag) && table.length >= 18) {
                                unitsPerEm = beShort(table, 18);
                            } else if ("OS/2".equals(tag) && table.length >= 6) {
                                weight = beShort(table, 4);
                            }
                        }
                    } else {
                        for (int i = 0; i < numTables; i++) {
                            int rec = 12 + i * 16;
                            if (rec + 16 > data.length) break;
                            String tag = new String(data, rec, 4, StandardCharsets.US_ASCII);
                            int offset = beInt(data, rec + 8);
                            int length = beInt(data, rec + 12);

                            if ("name".equals(tag) && offset + length <= data.length) {
                                byte[] nameTable = new byte[length];
                                System.arraycopy(data, offset, nameTable, 0, length);
                                fontName = extractName(nameTable, 4);
                                version = extractName(nameTable, 5);
                                postScriptName = extractName(nameTable, 6);
                            } else if ("head".equals(tag) && offset + 18 <= data.length) {
                                unitsPerEm = beShort(data, offset + 18);
                            } else if ("OS/2".equals(tag) && offset + 6 <= data.length) {
                                weight = beShort(data, offset + 4);
                            }
                        }
                    }
                    return new FontInfo(format, unitsPerEm, weight, fontName, version,
                            postScriptName, numTables, totalSfntSize);
                }
            }
        } catch (IOException e) {
            throw new FileStudioException("Failed reading font: " + path, e);
        }
    }

    /** 解包 WOFF 表数据（可能是 zlib 压缩）。压缩时为 origLength 大。 */
    private static byte[] woffTable(byte[] data, int offset, int compLength, int origLength) {
        if (offset < 0 || compLength < 0 || origLength < 0
                || offset + compLength > data.length) {
            return null;
        }
        byte[] comp = new byte[compLength];
        System.arraycopy(data, offset, comp, 0, compLength);
        if (compLength >= origLength) {
            // 未压缩（或相同长度）：直接取 origLength（可能 <= compLength，截断到 origLength）
            int n = Math.min(origLength, compLength);
            byte[] out = new byte[n];
            System.arraycopy(comp, 0, out, 0, n);
            return out;
        }
        try {
            var inflater = new java.util.zip.Inflater();
            inflater.setInput(comp);
            byte[] out = new byte[Math.max(origLength, 0)];
            int n = inflater.inflate(out);
            inflater.end();
            byte[] trimmed = new byte[n];
            System.arraycopy(out, 0, trimmed, 0, n);
            return trimmed;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 提取 WOFF2 头部可读信息；Broti 目录不展开。 */
    private static FontInfo parseWoff2Header(byte[] data) {
        if (data.length < 48) throw new FileStudioException("Truncated WOFF2 header");
        long totalSfntSize = beInt(data, 16) & 0xFFFFFFFFL;
        int numTables = beShort(data, 12);
        return new FontInfo("woff2", 0, 0, null, null, null, numTables, totalSfntSize);
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

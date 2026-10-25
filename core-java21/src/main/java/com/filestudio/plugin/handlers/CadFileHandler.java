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

/**
 * CAD 文件处理器：支持 DWG (AutoCAD 二进制) 与 DXF (AutoCAD 交换格式)。
 *
 * <p>VIEW_ONLY 能力——CAD 文件不可作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>DWG: 魔数 "AC" + 版本字符串 (如 "AC1032" 对应 AutoCAD 2018)</li>
 *   <li>DXF: ASCII 文本以 "0\nSECTION\n2\nHEADER\n" 开头，或二进制 DXF 以 "AutoCAD Binary DXF" 开头</li>
 * </ul>
 */
public class CadFileHandler implements FileHandler {

    /**
     * CAD 文件处理器。
     */
    public CadFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("dwg", "dxf", "dwt", "dws");

    /** 最多读取的头部字节。 */
    private static final int MAX_READ = 1024 * 1024;

    @Override
    public String getExtension() {
        return "dwg";
    }

    @Override
    public String getMimeType() {
        return "image/vnd.dwg";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "CAD file (DWG/DXF)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isCad(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("CAD file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        byte[] data;
        try {
            data = readPrefix(path, MAX_READ);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading CAD file: " + path, e);
        }

        String format = detectFormat(data);
        meta.put("format", format);

        try {
            if ("dwg".equals(format)) {
                parseDwg(data, meta);
            } else if ("dxf".equals(format)) {
                parseDxf(data, meta);
            }
        } catch (RuntimeException e) {
            meta.put("parseError", e.getMessage());
        }

        return new Document(path, mimeFor(format), format, "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("CAD files are view-only");
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
            meta.put("version", doc.getMetadata().get("version"));
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

    private static boolean isCad(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(32);
            return detectFormat(head) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String detectFormat(byte[] data) {
        if (data.length < 6) return null;
        // DWG: "AC" + 版本号 (如 AC1032, AC1027 等)
        if (data[0] == 'A' && data[1] == 'C') {
            if (data.length >= 6) {
                String ver = new String(data, 2, 4, StandardCharsets.US_ASCII);
                if (ver.matches("10\\d{2}")) {
                    return "dwg";
                }
            }
        }
        // DXF ASCII: 以 "0\nSECTION\n2\nHEADER\n" 或类似开头
        if (data.length >= 20) {
            String head = new String(data, 0, Math.min(50, data.length), StandardCharsets.US_ASCII);
            if (head.startsWith("0\nSECTION\n") || head.startsWith("0\r\nSECTION\r\n")) {
                return "dxf";
            }
        }
        // DXF Binary: "AutoCAD Binary DXF" 魔数
        if (data.length >= 22) {
            String head = new String(data, 0, 22, StandardCharsets.US_ASCII);
            if (head.startsWith("AutoCAD Binary DXF")) {
                return "dxf";
            }
        }
        return null;
    }

    private static void parseDwg(byte[] data, Map<String, Object> meta) {
        // DWG 头部：AC + 版本(4字节) + 文件头信息
        // 版本字符串映射：
        // AC1032 = AutoCAD 2018/2019/2020/2021
        // AC1027 = AutoCAD 2013/2014/2015/2016/2017
        // AC1024 = AutoCAD 2010/2011/2012
        // AC1021 = AutoCAD 2007/2008/2009
        // AC1018 = AutoCAD 2004/2005/2006
        // AC1015 = AutoCAD 2000/2000i/2002
        // AC1014 = AutoCAD R14
        // AC1012 = AutoCAD R13
        if (data.length >= 6) {
            String versionCode = new String(data, 2, 4, StandardCharsets.US_ASCII);
            meta.put("versionCode", versionCode);
            String version = switch (versionCode) {
                case "1032" -> "AutoCAD 2018-2021";
                case "1027" -> "AutoCAD 2013-2017";
                case "1024" -> "AutoCAD 2010-2012";
                case "1021" -> "AutoCAD 2007-2009";
                case "1018" -> "AutoCAD 2004-2006";
                case "1015" -> "AutoCAD 2000-2002";
                case "1014" -> "AutoCAD R14";
                case "1012" -> "AutoCAD R13";
                case "1009" -> "AutoCAD R11/R12";
                default -> "Unknown (" + versionCode + ")";
            };
            meta.put("version", version);
        }
        // 尝试读取文件头中的其他信息（简化）
        if (data.length >= 100) {
            // DWG 头部包含更多信息，但结构复杂，这里只提取版本
        }
    }

    private static void parseDxf(byte[] data, Map<String, Object> meta) {
        String text = new String(data, 0, Math.min(data.length, 10000), StandardCharsets.US_ASCII);
        
        // 查找 $ACADVER 变量 (DXF 版本)
        int verPos = text.indexOf("$ACADVER");
        if (verPos >= 0) {
            // 格式: 9\n$ACADVER\n1\nAC1032\n
            int valStart = text.indexOf('\n', verPos + 8);
            if (valStart >= 0) {
                int valEnd = text.indexOf('\n', valStart + 1);
                if (valEnd > valStart) {
                    String ver = text.substring(valStart + 1, valEnd).trim();
                    meta.put("versionCode", ver);
                    String version = switch (ver) {
                        case "AC1032" -> "AutoCAD 2018-2021";
                        case "AC1027" -> "AutoCAD 2013-2017";
                        case "AC1024" -> "AutoCAD 2010-2012";
                        case "AC1021" -> "AutoCAD 2007-2009";
                        case "AC1018" -> "AutoCAD 2004-2006";
                        case "AC1015" -> "AutoCAD 2000-2002";
                        case "AC1014" -> "AutoCAD R14";
                        case "AC1012" -> "AutoCAD R13";
                        case "AC1009" -> "AutoCAD R11/R12";
                        default -> "Unknown (" + ver + ")";
                    };
                    meta.put("version", version);
                }
            }
        }

        // 统计实体数量 (简化：搜索 ENTITIES 段中的实体类型)
        int entitiesPos = text.indexOf("ENTITIES");
        if (entitiesPos >= 0) {
            int endSecPos = text.indexOf("ENDSEC", entitiesPos);
            if (endSecPos < 0) endSecPos = text.length();
            String entitiesSection = text.substring(entitiesPos, endSecPos);
            
            int lineCount = 0;
            int circleCount = 0;
            int arcCount = 0;
            int textCount = 0;
            int dimCount = 0;
            int blockCount = 0;
            int insertCount = 0;
            
            // 简单计数
            lineCount = countOccurrences(entitiesSection, "\nLINE\n");
            circleCount = countOccurrences(entitiesSection, "\nCIRCLE\n");
            arcCount = countOccurrences(entitiesSection, "\nARC\n");
            textCount = countOccurrences(entitiesSection, "\nTEXT\n");
            textCount += countOccurrences(entitiesSection, "\nMTEXT\n");
            dimCount = countOccurrences(entitiesSection, "\nDIMENSION\n");
            blockCount = countOccurrences(entitiesSection, "\nBLOCK\n");
            insertCount = countOccurrences(entitiesSection, "\nINSERT\n");
            
            if (lineCount > 0) meta.put("lineCount", lineCount);
            if (circleCount > 0) meta.put("circleCount", circleCount);
            if (arcCount > 0) meta.put("arcCount", arcCount);
            if (textCount > 0) meta.put("textCount", textCount);
            if (dimCount > 0) meta.put("dimensionCount", dimCount);
            if (blockCount > 0) meta.put("blockCount", blockCount);
            if (insertCount > 0) meta.put("insertCount", insertCount);
        }

        // 查找图层
        int layerPos = text.indexOf("LAYER");
        if (layerPos >= 0) {
            // 简化：统计 LAYER 出现次数
            int layerCount = countOccurrences(text, "\nLAYER\n");
            if (layerCount > 0) meta.put("layerCount", layerCount);
        }
    }

    private static int countOccurrences(String text, String substr) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substr, idx)) >= 0) {
            count++;
            idx += substr.length();
        }
        return count;
    }

    private static String mimeFor(String format) {
        return switch (format) {
            case "dwg" -> "image/vnd.dwg";
            case "dxf" -> "image/vnd.dxf";
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
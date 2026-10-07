package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * STL 处理器：统计三角面数量与模型包围盒，支持二进制与 ASCII 两种编码。
 *
 * <p>VIEW_ONLY 能力——模型文件不作为文本编辑。
 *
 * <p>二进制 STL 结构：
 * <pre>
 *   偏移   长度  含义
 *    0     80   头部（任意内容，常以 "solid" 开头，不能据此判断格式）
 *   80      4   三角面数量（uint32，小端）
 *   84     50   每个三角面：法线 3×float32 + 顶点 3×3×float32 + 属性 uint16
 * </pre>
 *
 * <p>注意：二进制 STL 的 80 字节头部经常以 {@code "solid"} 开头，因此<b>不能</b>
 * 用它判断是否为 ASCII；正确做法是先按长度校验二进制结构，失败再回退 ASCII。
 */
public class StlFileHandler implements FileHandler {

    /**
     * STL 模型处理器。
     */
    public StlFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("stl");

    /** 二进制 STL 头部与计数字段长度。 */
    private static final int BINARY_HEADER_SIZE = 80;
    private static final int COUNT_FIELD_SIZE = 4;
    /** 每个三角面占 50 字节。 */
    private static final int TRIANGLE_SIZE = 50;

    /** 二进制 STL 允许的最大三角面数（按长度反推的上限，防止读取超大数组）。 */
    private static final long MAX_TRIANGLES = 50_000_000L;

    /** ASCII STL 扫描上限。 */
    private static final int MAX_ASCII_LINES = 2_000_000;

    @Override
    public String getExtension() {
        return "stl";
    }

    @Override
    public String getMimeType() {
        return "model/stl";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "STL 3D model (binary or ASCII)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isStl(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("STL file not readable: " + file);
        }
        Path path = file.toPath();
        long size = file.length();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", size);
        meta.put("format", "stl");
        meta.put("viewOnly", true);

        BinaryInfo bin = tryReadBinary(path, size);
        if (bin != null) {
            meta.put("valid", true);
            meta.put("encoding", "binary");
            meta.put("triangleCount", bin.triangles());
            meta.put("vertexCount", bin.triangles() * 3L);
            if (!bin.headerText().isEmpty()) {
                meta.put("headerText", bin.headerText());
            }
            meta.put("hasNormals", bin.hasNormals());
            putBounds(meta, bin.min(), bin.max());
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    size, getEditCapability());
        }

        AsciiInfo ascii = tryReadAscii(path);
        if (ascii != null) {
            meta.put("valid", true);
            meta.put("encoding", "ascii");
            meta.put("solidName", ascii.solidName());
            meta.put("triangleCount", ascii.triangles());
            meta.put("vertexCount", ascii.triangles() * 3L);
            meta.put("hasNormals", ascii.hasNormals());
            putBounds(meta, ascii.min(), ascii.max());
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    size, getEditCapability());
        }

        meta.put("valid", false);
        meta.put("error", "Neither a valid binary nor ASCII STL");
        return new Document(path, getMimeType(), getExtension(), "", meta,
                size, getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("STL files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("encoding", doc.getMetadata().get("encoding"));
            meta.put("triangleCount", doc.getMetadata().get("triangleCount"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private record BinaryInfo(long triangles, String headerText, boolean hasNormals,
                              float[] min, float[] max) {}

    private record AsciiInfo(String solidName, long triangles, boolean hasNormals,
                             float[] min, float[] max) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /**
     * 判断是否为 STL。
     *
     * <p>优先按二进制结构校验（长度必须等于 {@code 84 + 50*n}），否则看是否以 {@code solid} 开头。
     */
    private static boolean isStl(File file) {
        long size = file.length();
        if (size >= BINARY_HEADER_SIZE + COUNT_FIELD_SIZE) {
            try {
                byte[] head = readPrefix(file.toPath(), BINARY_HEADER_SIZE + COUNT_FIELD_SIZE);
                if (hasExactBinarySize(size, head)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 继续尝试 ASCII 判定
            }
        }
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line = r.readLine();
            return line != null && line.stripLeading().startsWith("solid");
        } catch (Exception e) {
            return false;
        }
    }

    /** 二进制 STL 的文件长度必须恰好等于 {@code 84 + 50 * triangleCount}。 */
    private static boolean hasExactBinarySize(long fileSize, byte[] headWithCount) {
        if (headWithCount.length < BINARY_HEADER_SIZE + COUNT_FIELD_SIZE) return false;
        long n = leUnsignedInt(headWithCount, BINARY_HEADER_SIZE);
        if (n <= 0 || n > MAX_TRIANGLES) return false;
        long expected = BINARY_HEADER_SIZE + COUNT_FIELD_SIZE + n * TRIANGLE_SIZE;
        return expected == fileSize;
    }

    /** 尝试按二进制解析。 */
    private static BinaryInfo tryReadBinary(Path path, long size) {
        if (size < BINARY_HEADER_SIZE + COUNT_FIELD_SIZE) return null;
        try {
            byte[] head = readPrefix(path, BINARY_HEADER_SIZE + COUNT_FIELD_SIZE);
            if (!hasExactBinarySize(size, head)) return null;
            long triangles = leUnsignedInt(head, BINARY_HEADER_SIZE);

            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            boolean hasNormals = false;

            // 逐块读取，避免把大文件整个载入内存
            try (var in = Files.newInputStream(path)) {
                in.skipNBytes(BINARY_HEADER_SIZE + COUNT_FIELD_SIZE);
                byte[] tri = new byte[TRIANGLE_SIZE];
                for (long i = 0; i < triangles; i++) {
                    int read = readFully(in, tri);
                    if (read < TRIANGLE_SIZE) {
                        // 文件被截断：按已读到的面数返回
                        triangles = i;
                        break;
                    }
                    ByteBuffer bb = ByteBuffer.wrap(tri).order(ByteOrder.LITTLE_ENDIAN);
                    // 每条记录恰好是「法线 3 float + 3 个顶点 × 3 float + 2 字节属性」，
                    // 即 12 个 float。注意这里不能再套一层"3 个面"的循环——
                    // 一条记录就是一个三角面。
                    float nx = bb.getFloat();
                    float ny = bb.getFloat();
                    float nz = bb.getFloat();
                    if (nx != 0f || ny != 0f || nz != 0f) {
                        hasNormals = true;
                    }
                    for (int v = 0; v < 3; v++) {
                        float x = bb.getFloat();
                        float y = bb.getFloat();
                        float z = bb.getFloat();
                        min[0] = Math.min(min[0], x);
                        min[1] = Math.min(min[1], y);
                        min[2] = Math.min(min[2], z);
                        max[0] = Math.max(max[0], x);
                        max[1] = Math.max(max[1], y);
                        max[2] = Math.max(max[2], z);
                    }
                }
            }
            return new BinaryInfo(triangles, headerText(head), hasNormals,
                    triangles > 0 ? min : null, triangles > 0 ? max : null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 尝试按 ASCII 解析。 */
    private static AsciiInfo tryReadAscii(Path path) {
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String solidName = null;
            long triangles = 0;
            boolean hasNormals = false;
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            int lines = 0;
            boolean sawSolid = false;

            String line;
            while ((line = r.readLine()) != null && lines++ < MAX_ASCII_LINES) {
                String t = line.stripLeading();
                if (t.startsWith("solid")) {
                    sawSolid = true;
                    solidName = t.length() > 5 ? t.substring(5).strip() : "";
                    if (solidName.isEmpty()) solidName = null;
                } else if (t.startsWith("facet normal")) {
                    triangles++;
                    // "facet normal nx ny nz" → 分量下标为 2/3/4，须全部检查：
                    // 只看某一个分量会让 normal 0 0 1 这类常见法线被误判为"无法线"
                    String[] parts = t.split("\\s+");
                    if (parts.length >= 5) {
                        try {
                            float nx = Float.parseFloat(parts[2]);
                            float ny = Float.parseFloat(parts[3]);
                            float nz = Float.parseFloat(parts[4]);
                            if (nx != 0f || ny != 0f || nz != 0f) {
                                hasNormals = true;
                            }
                        } catch (NumberFormatException ignored) {
                            // 法线非数值，忽略
                        }
                    }
                } else if (t.startsWith("vertex")) {
                    String[] parts = t.split("\\s+");
                    if (parts.length >= 4) {
                        try {
                            float x = Float.parseFloat(parts[1]);
                            float y = Float.parseFloat(parts[2]);
                            float z = Float.parseFloat(parts[3]);
                            min[0] = Math.min(min[0], x);
                            min[1] = Math.min(min[1], y);
                            min[2] = Math.min(min[2], z);
                            max[0] = Math.max(max[0], x);
                            max[1] = Math.max(max[1], y);
                            max[2] = Math.max(max[2], z);
                        } catch (NumberFormatException ignored) {
                            // 顶点非数值，跳过
                        }
                    }
                }
            }
            if (!sawSolid) return null;
            return new AsciiInfo(solidName, triangles, hasNormals,
                    triangles > 0 ? min : null, triangles > 0 ? max : null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void putBounds(Map<String, Object> meta, float[] min, float[] max) {
        if (min == null || max == null) return;
        meta.put("boundingBoxMin", Arrays.copyOf(min, 3));
        meta.put("boundingBoxMax", Arrays.copyOf(max, 3));
        meta.put("sizeX", round(max[0] - min[0]));
        meta.put("sizeY", round(max[1] - min[1]));
        meta.put("sizeZ", round(max[2] - min[2]));
    }

    private static double round(float v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String headerText(byte[] head) {
        int len = Math.min(BINARY_HEADER_SIZE, head.length);
        int end = len;
        while (end > 0 && head[end - 1] == 0) end--;
        if (end == 0) return "";
        String s = new String(head, 0, end, StandardCharsets.UTF_8).strip();
        // 头部内容无规范约定，只保留可打印且较短的部分
        return s.length() <= 120 && s.chars().allMatch(c -> c >= 32 && c < 127) ? s : "";
    }

    private static byte[] readPrefix(Path path, int len) throws IOException {
        byte[] buf = new byte[len];
        try (var in = Files.newInputStream(path)) {
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
        }
    }

    private static int readFully(java.io.InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static long leUnsignedInt(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}

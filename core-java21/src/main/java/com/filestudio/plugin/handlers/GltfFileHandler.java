package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * glTF 2.0 处理器：提取版本、生成器与网格/材质/动画等资源数量。
 *
 * <p>VIEW_ONLY 能力——模型文件不作为文本编辑。
 *
 * <p>支持两种容器：
 * <ul>
 *   <li>{@code .gltf} —— 纯 JSON</li>
 *   <li>{@code .glb} —— 二进制容器：{@code "glTF"(4) + version(4) + length(4)}，
 *       之后是若干 chunk：{@code chunkLength(4) + chunkType(4) + data}，
 *       其中 chunkType {@code 0x4E4F534A} 为 JSON</li>
 * </ul>
 */
public class GltfFileHandler implements FileHandler {

    /**
     * glTF 处理器。
     */
    public GltfFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("gltf", "glb");

    /** glb 魔数。 */
    private static final int GLB_MAGIC = 0x46546C67; // "glTF" 小端
    /** JSON chunk 类型。 */
    private static final int CHUNK_JSON = 0x4E4F534A; // "JSON" 小端
    private static final int CHUNK_BIN = 0x004E4942;  // "BIN\0" 小端

    /** 读取 glb 头部的上限，足够容纳头部与第一个 JSON chunk。 */
    private static final int MAX_READ = 32 * 1024 * 1024;

    @Override
    public String getExtension() {
        return "gltf";
    }

    @Override
    public String getMimeType() {
        return "model/gltf+json";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "glTF 2.0 model (JSON or binary)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isGltf(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("glTF file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        String ext = extensionOf(file.getName());
        try {
            byte[] json;
            if ("glb".equals(ext)) {
                meta.put("format", "glb");
                GlbInfo glb = readGlb(path);
                json = glb.json();
                meta.put("containerVersion", glb.version());
                meta.put("declaredLength", glb.declaredLength());
                meta.put("hasBinaryChunk", glb.hasBinaryChunk());
                meta.put("binaryChunkLength", glb.binaryChunkLength());
            } else {
                meta.put("format", "gltf");
                json = Files.readAllBytes(path);
            }
            describeJson(new String(json, StandardCharsets.UTF_8), meta);
            meta.putIfAbsent("valid", true);
        } catch (Exception e) {
            meta.put("valid", false);
            meta.put("error", "Failed to parse glTF: " + e.getMessage());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("glTF files are view-only");
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
            meta.put("generator", doc.getMetadata().get("generator"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private record GlbInfo(byte[] json, int version, long declaredLength,
                           boolean hasBinaryChunk, long binaryChunkLength) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isGltf(File file) {
        try {
            byte[] head = readPrefix(file.toPath(), 4);
            if (head.length == 4) {
                int magic = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (magic == GLB_MAGIC) return true;
            }
        } catch (Exception ignored) {
            // 继续按文本判定
        }
        // .gltf 是 JSON：首字符为 '{' 即可
        try {
            byte[] head = readPrefix(file.toPath(), 1);
            return head.length == 1 && head[0] == '{';
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 glb 容器，取出 JSON chunk。 */
    private static GlbInfo readGlb(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < 12) {
            throw new IOException("glb too short: " + data.length + " bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        if (magic != GLB_MAGIC) {
            throw new IOException("bad glb magic 0x" + Integer.toHexString(magic));
        }
        int version = bb.getInt();
        long declared = bb.getInt() & 0xFFFFFFFFL;

        byte[] json = null;
        boolean hasBin = false;
        long binLength = 0;
        while (bb.remaining() >= 8) {
            int chunkLength = bb.getInt();
            int chunkType = bb.getInt();
            if (chunkLength < 0 || chunkLength > bb.remaining()) {
                break;
            }
            if (chunkType == CHUNK_JSON && json == null) {
                byte[] buf = new byte[chunkLength];
                bb.get(buf);
                json = buf;
            } else if (chunkType == CHUNK_BIN) {
                hasBin = true;
                binLength = chunkLength;
                bb.position(bb.position() + chunkLength);
            } else {
                bb.position(bb.position() + chunkLength);
            }
            // chunk 按 4 字节对齐
            int padding = (4 - (chunkLength % 4)) % 4;
            if (bb.remaining() >= padding) bb.position(bb.position() + padding);
        }
        if (json == null) {
            throw new IOException("glb contains no JSON chunk");
        }
        return new GlbInfo(json, version, declared, hasBin, binLength);
    }

    /**
     * 提取 JSON 中的关键字段。
     *
     * <p>刻意不做"统计各数组元素个数"之类的推导：glTF 的三角形数需要沿
     * {@code mesh→primitive→indices→accessor.count} 逐层求值，简单的文本扫描
     * 得不出正确结果，与其给出一个看似合理实则错误的数字，不如只报告能确定的字段。
     * 需要完整解析时应引入 JSON 库或复用引擎内的解析器。
     */
    private static void describeJson(String json, Map<String, Object> meta) {
        String trimmed = json.stripLeading();
        if (!trimmed.startsWith("{")) {
            throw new FileStudioException("glTF JSON must be a top-level object");
        }
        if (!isBalanced(trimmed)) {
            throw new FileStudioException("glTF JSON is not well-formed (unbalanced braces)");
        }
        // glTF 规范要求顶层必须有 "asset" 对象；缺少它说明这只是个普通 JSON
        if (!trimmed.contains("\"asset\"")) {
            throw new FileStudioException("glTF JSON has no top-level \"asset\" object");
        }
        meta.put("assetVersion", matchString(json, "asset", "version"));
        meta.put("generator", matchString(json, "asset", "generator"));
        meta.put("copyright", matchString(json, "asset", "copyright"));
    }

    /** 校验花括号/方括号是否配对，并忽略字符串内的括号。 */
    private static boolean isBalanced(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth < 0) return false;
                }
                default -> {
                    // 忽略
                }
            }
        }
        return depth == 0 && !inString;
    }

    /** 提取 {@code "parent": { "key": "value" }} 形式的字符串字段。 */
    private static String matchString(String json, String parent, String key) {
        int p = json.indexOf("\"" + parent + "\"");
        if (p < 0) return null;
        int k = json.indexOf("\"" + key + "\"", p);
        if (k < 0 || k > p + 4000) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
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
}

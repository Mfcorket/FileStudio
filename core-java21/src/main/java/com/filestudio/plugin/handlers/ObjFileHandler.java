package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Wavefront OBJ 处理器：统计顶点/法线/纹理坐标/面，并列出对象、组与材质。
 *
 * <p>VIEW_ONLY 能力——模型文件不作为文本编辑，但内容本身是纯文本，UI 可按需展示源码。
 *
 * <p>实现按 OBJ 规范逐行扫描语句前缀：
 * <pre>
 *   v  x y z [w]      顶点
 *   vn x y z          法线
 *   vt u [v]          纹理坐标
 *   f  v/vt/vn ...    面（索引可为负，表示相对当前位置）
 *   o / g / usemtl / mtllib
 *   # ...             注释
 * </pre>
 */
public class ObjFileHandler implements FileHandler {

    /**
     * Wavefront OBJ 处理器。
     */
    public ObjFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("obj");

    /** 统计名字类时最多保留的条目数。 */
    private static final int MAX_NAMES = 64;

    @Override
    public String getExtension() {
        return "obj";
    }

    @Override
    public String getMimeType() {
        return "model/obj";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Wavefront OBJ 3D model";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isObj(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("OBJ file not readable: " + file);
        }
        Path path = file.toPath();
        ObjStats stats = scan(path);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "obj");
        meta.put("viewOnly", true);
        meta.put("valid", stats.valid);
        meta.put("vertexCount", stats.vertices);
        meta.put("normalCount", stats.normals);
        meta.put("textureCount", stats.textures);
        meta.put("faceCount", stats.faces);
        // 面的索引总数（三角面为 faceCount 的 3 倍）
        meta.put("polygonCount", stats.polygonIndices);
        meta.put("hasNormals", stats.normals > 0);
        meta.put("hasTextureCoords", stats.textures > 0);
        meta.put("lineCount", stats.lines);
        if (!stats.objects.isEmpty()) meta.put("objects", new ArrayList<>(stats.objects));
        if (!stats.groups.isEmpty()) meta.put("groups", new ArrayList<>(stats.groups));
        if (!stats.materials.isEmpty()) meta.put("materials", new ArrayList<>(stats.materials));
        if (!stats.materialLibraries.isEmpty()) {
            meta.put("mtllib", new ArrayList<>(stats.materialLibraries));
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("OBJ files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            ObjStats stats = scan(file.toPath());
            meta.put("vertexCount", stats.vertices);
            meta.put("faceCount", stats.faces);
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    /** 扫描结果。 */
    private static final class ObjStats {
        int vertices;
        int normals;
        int textures;
        int faces;
        int polygonIndices;
        int lines;
        boolean valid;
        final Set<String> objects = new LinkedHashSet<>();
        final Set<String> groups = new LinkedHashSet<>();
        final Set<String> materials = new LinkedHashSet<>();
        final Set<String> materialLibraries = new LinkedHashSet<>();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /**
     * 判断是否为 OBJ。
     *
     * <p>OBJ 是无魔数的纯文本格式，因此要求前若干行内出现 OBJ 专有语句前缀，
     * 仅凭扩展名不足以避免误判。
     */
    private static boolean isObj(File file) {
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            int checked = 0;
            String line;
            while (checked < 200 && (line = r.readLine()) != null) {
                checked++;
                String t = line.stripLeading();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.startsWith("v ") || t.startsWith("vn ") || t.startsWith("vt ")
                        || t.startsWith("f ") || t.startsWith("o ") || t.startsWith("g ")
                        || t.startsWith("usemtl ") || t.startsWith("mtllib ")) {
                    return true;
                }
                return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static ObjStats scan(Path path) {
        ObjStats stats = new ObjStats();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            // 流式逐行读取，内存占用与文件大小无关
            while ((line = r.readLine()) != null) {
                stats.lines++;
                String t = line.stripLeading();
                if (t.isEmpty() || t.charAt(0) == '#') {
                    continue;
                }
                // 关键字与参数之间的空白可能不止一个空格/制表符
                int sp = indexOfWhitespace(t);
                if (sp <= 0) {
                    continue;
                }
                String keyword = t.substring(0, sp);
                String rest = t.substring(sp).strip();

                switch (keyword) {
                    case "v" -> {
                        stats.vertices++;
                        stats.valid = true;
                    }
                    case "vn" -> {
                        stats.normals++;
                        stats.valid = true;
                    }
                    case "vt" -> {
                        stats.textures++;
                        stats.valid = true;
                    }
                    case "f" -> {
                        stats.faces++;
                        stats.polygonIndices += countFaceIndices(rest);
                        stats.valid = true;
                    }
                    case "o" -> {
                        addLimited(stats.objects, rest);
                        stats.valid = true;
                    }
                    case "g" -> {
                        addLimited(stats.groups, rest);
                        stats.valid = true;
                    }
                    case "usemtl" -> {
                        addLimited(stats.materials, rest);
                        stats.valid = true;
                    }
                    case "mtllib" -> {
                        addLimited(stats.materialLibraries, rest);
                        stats.valid = true;
                    }
                    default -> {
                        // s / l / p / 其他扩展语句，不足以判定为 OBJ
                    }
                }
            }
        } catch (IOException e) {
            throw new FileStudioException("Failed reading OBJ: " + path, e);
        }
        return stats;
    }

    /** 计算一条面语句的索引个数，如 {@code f 1 2 3} 为 3，{@code f 1/2/3 4/5/6 7/8/9} 亦为 3。 */
    private static int countFaceIndices(String rest) {
        int n = 0;
        for (String token : rest.strip().split("\\s+")) {
            if (!token.isEmpty()) n++;
        }
        return n;
    }

    private static void addLimited(Set<String> set, String value) {
        if (!value.isEmpty() && set.size() < MAX_NAMES) {
            set.add(value);
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}

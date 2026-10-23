package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unity 场景/预置（.unity/.prefab/.asset）处理器。
 *
 * <p>Unity 资源本质是带 {@code %TAG !u! tag:unity3d.com,2011} 的 YAML：
 * 每个对象形如 {@code --- !u!<classId> &<fileId>}。元数据：对象总数、
 * 类 ID 种类数、首个对象类型与 unity 版本标记。
 *
 * <p>FULL 编辑能力——内容原样回写。
 */
public class UnityFileHandler extends AbstractTextHandler {

    private static final Pattern OBJECT = Pattern.compile("(?m)^--- !u!(\\d+) &");
    private static final Pattern CLASS_IDS = Pattern.compile("(?m)^--- !u!(\\d+) &");

    @Override
    public String getExtension() {
        return "unity";
    }

    @Override
    public String getMimeType() {
        return "application/x-unity";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "Unity scene/prefab (YAML)";
    }

    @Override
    public boolean canHandle(java.io.File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        if (ext.equals("unity") || ext.equals("prefab") || ext.equals("asset")) {
            return true;
        }
        // 其它扩展名按内容判定：必须是带 unity3d TAG 的 YAML
        if (!file.isFile()) return false;
        try {
            byte[] head = new byte[512];
            int n;
            try (var in = java.nio.file.Files.newInputStream(file.toPath())) {
                n = in.read(head);
            }
            String s = new String(head, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
            return s.contains("unity3d.com") && (s.contains("%YAML") || s.contains("!u!"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isBlank()) return;
        meta.put("format", "unity-yaml");

        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        int objects = 0;
        Matcher m = CLASS_IDS.matcher(content);
        while (m.find()) {
            objects++;
            distinct.add(m.group(1));
        }
        meta.put("objectCount", objects);
        meta.put("distinctClassCount", distinct.size());
        if (objects > 0) {
            Matcher first = OBJECT.matcher(content);
            if (first.find()) {
                meta.put("firstObjectClassId", Integer.parseInt(first.group(1)));
            }
        }
        meta.put("hasUnityTag", content.contains("%TAG !u! tag:unity3d.com")
                || content.contains("tag:unity3d.com"));
        meta.put("yamlDoctype", content.contains("%YAML 1.1") ? "1.1"
                : content.contains("%YAML") ? "other" : "none");
    }
}
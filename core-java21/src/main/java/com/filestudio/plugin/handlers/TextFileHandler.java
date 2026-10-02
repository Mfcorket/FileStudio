package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.io.File;
import java.util.Set;

/**
 * 默认文本文件处理器：支持 .txt/.log/.ini/.cfg 等纯文本格式。
 *
 * <p>特点：
 * <ul>
 *   <li>自动识别 BOM 与回退 UTF-8</li>
 *   <li>大文件采用流式读取上限保护（见 {@link AbstractTextHandler#MAX_IN_MEMORY_BYTES}）</li>
 *   <li>FULL 编辑能力，可原样回写</li>
 * </ul>
 *
 * <p>继承自 {@link AbstractTextHandler}，仅声明扩展名集合与 MIME 映射。
 */
public class TextFileHandler extends AbstractTextHandler {

    private static final Set<String> SUPPORTED = Set.of(
            "txt", "log", "ini", "cfg", "conf", "properties", "md", "csv", "tsv", "text");

    private final String extension;
    private final String description;

    public TextFileHandler() {
        this("txt", "Plain text file");
    }

    public TextFileHandler(String extension, String description) {
        this.extension = extension == null || extension.isBlank() ? "txt" : extension.toLowerCase();
        this.description = description == null ? "Plain text file" : description;
    }

    @Override
    public String getExtension() {
        return extension;
    }

    @Override
    public String getMimeType() {
        return switch (extension) {
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "md" -> "text/markdown";
            default -> "text/plain";
        };
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        if (ext.isEmpty()) return false;
        // 单扩展名实例优先按自身声明匹配；默认实例则覆盖整组文本扩展名
        return SUPPORTED.contains(ext);
    }
}

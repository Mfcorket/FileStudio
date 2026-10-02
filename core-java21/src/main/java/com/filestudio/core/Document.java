package com.filestudio.core;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 统一文档模型：所有文件格式经 {@code FileHandler.parse} 后都归一为该模型。
 *
 * <p>不可变值类型。修改流程应通过 {@link #withContent(String)} 等方法产生新实例。
 */
public final class Document {

    private final Path filePath;
    private final String mimeType;
    private final String extension;
    private final String content;
    private final Map<String, Object> metadata;
    private final long size;
    private final EditCapability editCapability;

    public Document(Path filePath,
                    String mimeType,
                    String extension,
                    String content,
                    Map<String, Object> metadata,
                    long size,
                    EditCapability editCapability) {
        this.filePath = filePath;
        this.mimeType = Objects.requireNonNullElse(mimeType, "application/octet-stream");
        this.extension = Objects.requireNonNullElse(extension, "");
        this.content = Objects.requireNonNullElse(content, "");
        this.metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
        this.size = size;
        this.editCapability = editCapability == null ? EditCapability.VIEW_ONLY : editCapability;
    }

    public static Document empty() {
        return new Document(null, "", "", "", Collections.emptyMap(), 0L, EditCapability.VIEW_ONLY);
    }

    public Path getFilePath() {
        return filePath;
    }

    public File getFile() {
        return filePath == null ? null : filePath.toFile();
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getExtension() {
        return extension;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public long getSize() {
        return size;
    }

    public EditCapability getEditCapability() {
        return editCapability;
    }

    public boolean isEditable() {
        return editCapability == EditCapability.FULL;
    }

    public Document withContent(String newContent) {
        return new Document(filePath, mimeType, extension, newContent, metadata,
                newContent == null ? 0 : newContent.length(), editCapability);
    }

    /** 返回指向新路径的副本（用于另存为 / 保存后更新路径）。 */
    public Document withFilePath(Path newFilePath) {
        return new Document(newFilePath, mimeType, extension, content, metadata, size, editCapability);
    }

    /** 返回指向新路径的副本（字符串路径重载）。 */
    public Document withFilePath(String newPath) {
        return new Document(newPath == null ? null : Path.of(newPath), mimeType, extension,
                content, metadata, size, editCapability);
    }

    @Override
    public String toString() {
        return "Document{path=" + filePath + ", mime=" + mimeType + ", ext=" + extension
                + ", size=" + size + ", cap=" + editCapability + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Document d)) return false;
        return size == d.size
                && Objects.equals(filePath, d.filePath)
                && Objects.equals(mimeType, d.mimeType)
                && Objects.equals(extension, d.extension)
                && Objects.equals(content, d.content)
                && Objects.equals(metadata, d.metadata)
                && editCapability == d.editCapability;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath, mimeType, extension, content, metadata, size, editCapability);
    }
}

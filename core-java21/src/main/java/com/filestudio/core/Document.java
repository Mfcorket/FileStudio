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

    /**
     * 构造文档。
     *
     * @param filePath       源文件路径，可为 {@code null}（未落盘文档）
     * @param mimeType       MIME 类型，{@code null} 时归一化为 {@code application/octet-stream}
     * @param extension      扩展名（小写，不含点），{@code null} 时归一化为空串
     * @param content        文本内容，{@code null} 时归一化为空串
     * @param metadata       元数据表，{@code null} 时使用空表；非空时包装为不可变
     * @param size           文件字节大小
     * @param editCapability 编辑能力，{@code null} 时归一化为 {@link EditCapability#VIEW_ONLY}
     */
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

    /**
     * 创建空文档（无路径、VIEW_ONLY）。
     *
     * @return 空文档实例
     */
    public static Document empty() {
        return new Document(null, "", "", "", Collections.emptyMap(), 0L, EditCapability.VIEW_ONLY);
    }

    /**
     * 源文件路径。
     *
     * @return 文件路径，未落盘时为 {@code null}
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * 源文件。
     *
     * @return {@link java.io.File}，未落盘时为 {@code null}
     */
    public File getFile() {
        return filePath == null ? null : filePath.toFile();
    }

    /**
     * MIME 类型。
     *
     * @return MIME 类型，非空
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * 扩展名。
     *
     * @return 扩展名（小写，不含点），无扩展名时为空串
     */
    public String getExtension() {
        return extension;
    }

    /**
     * 文本内容。
     *
     * @return 内容文本，不可为 {@code null}；二进制/只读格式为空串
     */
    public String getContent() {
        return content;
    }

    /**
     * 元数据。
     *
     * @return 不可变元数据表，不会为 {@code null}
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 文件大小。
     *
     * @return 字节数；内存文档为内容字符数
     */
    public long getSize() {
        return size;
    }

    /**
     * 编辑能力。
     *
     * @return 编辑能力等级，不会为 {@code null}
     */
    public EditCapability getEditCapability() {
        return editCapability;
    }

    /**
     * 是否可作为文本编辑。
     *
     * @return 仅当能力为 {@link EditCapability#FULL} 时为 true
     */
    public boolean isEditable() {
        return editCapability == EditCapability.FULL;
    }

    /**
     * 返回替换内容后的副本。
     *
     * @param newContent 新内容，{@code null} 视为空串
     * @return 新文档实例，其余字段保持不变
     */
    public Document withContent(String newContent) {
        return new Document(filePath, mimeType, extension, newContent, metadata,
                newContent == null ? 0 : newContent.length(), editCapability);
    }

    /**
     * 返回指向新路径的副本（用于另存为 / 保存后更新路径）。
     *
     * @param newFilePath 新文件路径
     * @return 新文档实例，其余字段保持不变
     */
    public Document withFilePath(Path newFilePath) {
        return new Document(newFilePath, mimeType, extension, content, metadata, size, editCapability);
    }

    /**
     * 返回指向新路径的副本（字符串路径重载）。
     *
     * @param newPath 新文件路径字符串，{@code null} 时路径置为 {@code null}
     * @return 新文档实例，其余字段保持不变
     */
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

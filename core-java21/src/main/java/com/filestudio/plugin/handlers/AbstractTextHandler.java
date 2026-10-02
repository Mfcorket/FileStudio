package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.engine.EncodingDetector;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本类处理器的公共基类：统一实现 BOM 检测、大文件上限保护、UTF-8 回写，
 * 以及基于扩展名的 {@code canHandle} 默认判定。
 *
 * <p>子类只需声明 {@link #getExtension()} / {@link #getMimeType()} / {@link #getDescription()}
 * / {@link #getEditCapability()}，并可选覆写：
 * <ul>
 *   <li>{@link #enrichMetadata(Map, String)} —— 在通用元数据之上追加格式特定元数据</li>
 *   <li>{@link #canHandle(File)} —— 自定义匹配规则（如多扩展名或基于内容的嗅探）</li>
 *   <li>{@link #render(Document, File)} —— 自定义序列化（如缩进 / 转义）</li>
 * </ul>
 *
 * <p>{@code enrichMetadata} 的入参 {@code content} 为已解码内容，
 * 当文件超过 {@link #MAX_IN_MEMORY_BYTES} 时为空字符串，子类应做空串保护。
 */
public abstract class AbstractTextHandler implements FileHandler {

    /** 超出该阈值的内容不载入内存，仅填充元数据，避免 OOM。 */
    protected static final long MAX_IN_MEMORY_BYTES = 16L * 1024 * 1024;

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return getExtension().equalsIgnoreCase(name.substring(dot + 1));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Text file not readable: " + file);
        }
        Path path = file.toPath();
        long size = file.length();
        EncodingDetector.CharsetAndBom cab = EncodingDetector.detect(path);
        Charset charset = cab.charset();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("encoding", charset.name());
        meta.put("bomBytes", cab.bomBytes());
        meta.put("size", size);

        String content;
        if (size > MAX_IN_MEMORY_BYTES) {
            content = "";
            meta.put("truncated", true);
        } else {
            try {
                byte[] all = Files.readAllBytes(path);
                int skip = cab.bomBytes();
                content = new String(all, skip, Math.max(0, all.length - skip), charset);
                meta.put("lines", countLines(content));
            } catch (IOException e) {
                throw new FileStudioException("Failed reading text file: " + path, e);
            }
        }

        // 在构建 Document 之前让子类向可变的 meta 追加格式特定元数据
        enrichMetadata(meta, content);

        return new Document(path, getMimeType(), getExtension(), content, meta,
                size, getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        if (doc == null || output == null) {
            throw new FileStudioException("render: doc/output required");
        }
        try {
            Files.writeString(output.toPath(),
                    doc.getContent() == null ? "" : doc.getContent(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FileStudioException("Failed writing text file: " + output, e);
        }
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        EncodingDetector.CharsetAndBom cab = EncodingDetector.detect(file.toPath());
        meta.put("encoding", cab.charset().name());
        return meta;
    }

    /**
     * 子类可覆写以追加格式特定元数据。
     *
     * @param meta    通用元数据表，在 Document 构建前传入，可自由写入
     * @param content 已解码内容；文件超过 {@link #MAX_IN_MEMORY_BYTES} 时为空串
     */
    protected void enrichMetadata(Map<String, Object> meta, String content) {}

    /**
     * 计算行数（换行符数量 + 1）。空串计为 1 行。
     *
     * @param content 待统计内容
     * @return 行数
     */
    protected static int countLines(String content) {
        if (content.isEmpty()) return 1;
        int n = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') n++;
        }
        return n + 1;
    }

    /**
     * 通用元数据访问器，便于子类在 {@link #enrichMetadata} 中读取。
     *
     * @param name 文件名
     * @return 小写扩展名（不含点），无扩展名时为空串
     */
    protected static String getExtensionFromName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}

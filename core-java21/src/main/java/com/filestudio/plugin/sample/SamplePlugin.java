package com.filestudio.plugin.sample;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;
import com.filestudio.plugin.FileHandlerFactory;
import com.filestudio.plugin.FileStudioPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例插件：演示如何为 FileStudio 扩展一个自定义格式 (.sample)。
 *
 * <p>.sample 文件被当作纯文本处理，作为插件系统最小可运行示例。
 * 通过 SPI 注册：见 {@code META-INF/services/com.filestudio.plugin.FileStudioPlugin}。
 */
public final class SamplePlugin implements FileStudioPlugin {

    /**
     * 创建示例插件实例。
     */
    public SamplePlugin() {}

    @Override
    public String getName() {
        return "com.filestudio.sample";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<FileHandlerFactory> getHandlers() {
        return List.of(SampleHandler::new);
    }

    /** .sample 格式处理器。 */
    public static final class SampleHandler implements FileHandler {

        /**
         * 创建处理器实例。
         */
        public SampleHandler() {}


        @Override
        public String getExtension() {
            return "sample";
        }

        @Override
        public String getMimeType() {
            return "text/x-sample";
        }

        @Override
        public EditCapability getEditCapability() {
            return EditCapability.FULL;
        }

        @Override
        public String getDescription() {
            return "FileStudio sample plugin format";
        }

        @Override
        public boolean canHandle(File file) {
            return file != null && file.getName().toLowerCase().endsWith(".sample");
        }

        @Override
        public Document parse(File file) {
            if (file == null || !file.isFile()) {
                throw new FileStudioException("sample file not readable: " + file);
            }
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("size", file.length());
                meta.put("source", "sample-plugin");
                return new Document(file.toPath(), getMimeType(), getExtension(),
                        content, meta, file.length(), getEditCapability());
            } catch (IOException e) {
                throw new FileStudioException("Failed reading .sample file", e);
            }
        }

        @Override
        public void render(Document doc, File output) {
            try {
                Files.writeString(output.toPath(),
                        doc.getContent() == null ? "" : doc.getContent(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new FileStudioException("Failed writing .sample file", e);
            }
        }

        @Override
        public Map<String, Object> getMetadata(File file) {
            if (file == null || !file.isFile()) return Map.of();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("size", file.length());
            meta.put("source", "sample-plugin");
            return meta;
        }
    }
}

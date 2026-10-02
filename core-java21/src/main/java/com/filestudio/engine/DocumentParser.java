package com.filestudio.engine;

import com.filestudio.core.Document;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;
import com.filestudio.plugin.PluginManager;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 文档解析引擎：协调 {@link PluginManager} 与具体 {@link FileHandler}，
 * 将"一个文件路径"转化为统一 {@link Document}。
 *
 * <p>对调用方（UI/控制器）屏蔽插件查找细节，是核心层向外暴露的主入口之一。
 */
public final class DocumentParser {

    private final PluginManager pluginManager;

    public DocumentParser(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 解析指定路径的文件。无可用处理器时抛出 {@link FileStudioException}。 */
    public Document parse(File file) {
        if (file == null || !file.exists()) {
            throw new FileStudioException("File not found: " + file);
        }
        Optional<FileHandler> h = pluginManager.findHandler(file);
        if (h.isEmpty()) {
            // 兜底：按文件头魔数识别格式后路由到对应处理器
            MagicBytesDetector.Detection det = MagicBytesDetector.detect(file.toPath());
            if (det.isKnown()) {
                h = pluginManager.findHandlerByExtension(det.extension());
            }
        }
        if (h.isEmpty()) {
            throw new FileStudioException("No handler for file: " + file.getName());
        }
        return h.get().parse(file);
    }

    public Document parse(Path path) {
        return parse(path.toFile());
    }

    /** 保存文档到目标路径。 */
    public void save(Document doc, File output) {
        if (doc == null) throw new FileStudioException("doc is null");
        if (output == null) throw new FileStudioException("output is null");
        Optional<FileHandler> h = pluginManager.findHandler(output);
        FileHandler target = h.orElseGet(() -> pluginManager.findHandler(doc.getFile())
                .orElseThrow(() -> new FileStudioException("No handler to save: " + output.getName())));
        target.render(doc, output);
    }

    public void save(Document doc, Path output) {
        save(doc, output.toFile());
    }
}

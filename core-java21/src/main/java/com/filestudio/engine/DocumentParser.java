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

    /**
     * 构造解析引擎。
     *
     * @param pluginManager 插件管理器，提供处理器查找
     */
    public DocumentParser(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 解析指定路径的文件。无可用处理器时抛出 {@link FileStudioException}。
     *
     * <p>处理器查找顺序：先按扩展名/MIME 匹配；失败时用
     * {@link MagicBytesDetector} 嗅探文件头后重试。
     *
     * @param file 目标文件
     * @return 解析后的文档
     * @throws FileStudioException 文件不存在或无可用处理器时抛出
     */
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

    /**
     * 解析指定路径的文件。
     *
     * @param path 目标路径
     * @return 解析后的文档
     * @throws FileStudioException 路径为空或无可用处理器时抛出
     */
    public Document parse(Path path) {
        return parse(path.toFile());
    }

    /**
     * 保存文档到目标路径。
     *
     * <p>优先按输出路径的格式选择处理器；无法确定时回退到源文档的处理器。
     *
     * @param doc    待保存的文档
     * @param output 目标文件
     * @throws FileStudioException 参数为 {@code null} 或无可用处理器时抛出
     */
    public void save(Document doc, File output) {
        if (doc == null) throw new FileStudioException("doc is null");
        if (output == null) throw new FileStudioException("output is null");
        Optional<FileHandler> h = pluginManager.findHandler(output);
        FileHandler target = h.orElseGet(() -> pluginManager.findHandler(doc.getFile())
                .orElseThrow(() -> new FileStudioException("No handler to save: " + output.getName())));
        target.render(doc, output);
    }

    /**
     * 保存文档到目标路径。
     *
     * @param doc    待保存的文档
     * @param output 目标路径
     * @throws FileStudioException 路径为空或无可用处理器时抛出
     */
    public void save(Document doc, Path output) {
        save(doc, output.toFile());
    }
}

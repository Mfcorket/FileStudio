package com.filestudio.core;

import com.filestudio.engine.DocumentParser;
import com.filestudio.engine.EditorEngine;
import com.filestudio.engine.EditorSessionManager;
import com.filestudio.plugin.PluginManager;
import com.filestudio.plugin.FileHandler;
import com.filestudio.plugin.handlers.AudioFileHandler;
import com.filestudio.plugin.handlers.CertificateFileHandler;
import com.filestudio.plugin.handlers.CsvFileHandler;
import com.filestudio.plugin.handlers.CssFileHandler;
import com.filestudio.plugin.handlers.HtmlFileHandler;
import com.filestudio.plugin.handlers.ImageFileHandler;
import com.filestudio.plugin.handlers.FontFileHandler;
import com.filestudio.plugin.handlers.IniFileHandler;
import com.filestudio.plugin.handlers.JsonFileHandler;
import com.filestudio.plugin.handlers.MarkdownFileHandler;
import com.filestudio.plugin.handlers.PdfFileHandler;
import com.filestudio.plugin.handlers.PropertiesFileHandler;
import com.filestudio.plugin.handlers.TextFileHandler;
import com.filestudio.plugin.handlers.TarArchiveHandler;
import com.filestudio.plugin.handlers.TomlFileHandler;
import com.filestudio.plugin.handlers.VideoFileHandler;
import com.filestudio.plugin.handlers.XmlFileHandler;
import com.filestudio.plugin.handlers.YamlFileHandler;
import com.filestudio.plugin.handlers.ZipArchiveHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FileStudio 核心引擎入口。聚合插件管理、文档解析、编辑器引擎，是 UI/控制层唯一的依赖对象。
 *
 * <p>典型生命周期：
 * <pre>
 *   FileStudioCore core = new FileStudioCore();
 *   core.init();                          // 加载插件 + 注册内置处理器
 *   Document doc = core.parseFile("a.json");
 *   core.saveDocument(doc, "b.json");
 *
 *   EditorEngine ed = core.newEditor();   // 新建编辑会话
 *   ed.open(core.parseFile("a.txt"));
 *   ed.edit("Hello, world!");
 *   ed.undo();
 *   ed.save("out.txt");
 *
 *   core.shutdown();
 * </pre>
 */
public final class FileStudioCore {

    /**
     * 创建未初始化的核心实例，需先调用 {@link #init()}。
     */
    public FileStudioCore() {}

    private final PluginManager pluginManager = new PluginManager();
    private final DocumentParser parser = new DocumentParser(pluginManager);
    private volatile boolean initialized;

    /**
     * 初始化核心：先扫描 SPI 插件，再注册内置处理器作为兜底格式支持。幂等。
     *
     * <p>注册顺序刻意将内置处理器放在 SPI 之后——{@code loadPlugins()} 内部会先
     * 清空注册表，若内置处理器先注册则会被清空。
     */
    public synchronized void init() {
        if (initialized) return;
        pluginManager.loadPlugins();
        for (FileHandler h : builtinHandlers()) {
            pluginManager.registerHandler(h);
        }
        initialized = true;
    }

    /** 内置处理器列表（不含 SPI 插件）。顺序即注册顺序。 */
    static List<FileHandler> builtinHandlers() {
        List<FileHandler> list = new ArrayList<>();
        list.add(new JsonFileHandler());
        list.add(new XmlFileHandler());
        list.add(new PropertiesFileHandler());
        list.add(new IniFileHandler());
        list.add(new CsvFileHandler());
        list.add(new MarkdownFileHandler());
        list.add(new YamlFileHandler());
        list.add(new TomlFileHandler());
        list.add(new HtmlFileHandler());
        list.add(new CssFileHandler());
        list.add(new ImageFileHandler());
        list.add(new ZipArchiveHandler());
        list.add(new AudioFileHandler());
        list.add(new PdfFileHandler());
        list.add(new FontFileHandler());
        list.add(new CertificateFileHandler());
        list.add(new VideoFileHandler());
        list.add(new TarArchiveHandler());
        list.add(new TextFileHandler());
        return Collections.unmodifiableList(list);
    }

    /**
     * 解析文件路径为统一文档模型。
     *
     * @param path 文件路径
     * @return 文档实例
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public Document parseFile(String path) {
        ensureInit();
        return parser.parse(new File(path));
    }

    /**
     * 解析文件为统一文档模型。
     *
     * @param file 目标文件
     * @return 文档实例
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public Document parseFile(File file) {
        ensureInit();
        return parser.parse(file);
    }

    /**
     * 保存文档到指定路径。
     *
     * @param doc  待保存文档
     * @param path 目标路径
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public void saveDocument(Document doc, String path) {
        ensureInit();
        parser.save(doc, new File(path));
    }

    /**
     * 保存文档到指定文件。
     *
     * @param doc    待保存文档
     * @param output 目标文件
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public void saveDocument(Document doc, File output) {
        ensureInit();
        parser.save(doc, output);
    }

    /**
     * 列出所有已注册格式处理器信息。
     *
     * @return 处理器描述列表（含 SPI 插件）
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public List<PluginHandlerInfo> listHandlers() {
        ensureInit();
        return pluginManager.listHandlers();
    }

    /**
     * 创建一个与当前核心绑定的编辑会话。
     *
     * @return 编辑器引擎实例
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public EditorEngine newEditor() {
        ensureInit();
        return new EditorEngine(parser);
    }

    /**
     * 创建一个与当前核心绑定的多标签会话管理器。
     *
     * @return 会话管理器实例
     * @throws IllegalStateException 核心未初始化时抛出
     */
    public EditorSessionManager newSessionManager() {
        ensureInit();
        return new EditorSessionManager(parser);
    }

    /**
     * 暴露插件管理器，便于高级用法（动态安装插件等）。
     *
     * @return 插件管理器实例
     */
    public PluginManager getPluginManager() {
        return pluginManager;
    }

    /**
     * 暴露文档解析器。
     *
     * @return 文档解析器实例
     */
    public DocumentParser getParser() {
        return parser;
    }

    /** 释放核心资源。 */
    public synchronized void shutdown() {
        pluginManager.shutdown();
        initialized = false;
    }

    private void ensureInit() {
        if (!initialized) {
            throw new IllegalStateException("FileStudioCore not initialized; call init() first");
        }
    }
}

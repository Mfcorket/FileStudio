package com.filestudio.plugin;

import com.filestudio.core.FileStudioException;
import com.filestudio.core.PluginHandlerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件管理器：负责发现、加载、查询格式处理器。
 *
 * <p>加载分两阶段：
 * <ol>
 *   <li>{@link #loadPlugins()} —— 通过 SPI 加载类路径上的所有 {@link FileStudioPlugin}，
 *       并调用其 {@code initialize()}</li>
 *   <li>{@link #installPlugin(File)} —— 运行时从外部 JAR 动态加载插件</li>
 * </ol>
 *
 * <p>线程安全：内部使用 {@link CopyOnWriteArrayList}，查询/注册可并发进行。
 */
public final class PluginManager {

    /**
     * 创建空的插件管理器，需先调用 {@link #loadPlugins()} 或注册处理器。
     */
    public PluginManager() {}

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** 单条注册记录：处理器 + 来源插件描述。 */
    private record Entry(FileHandler handler, String pluginName, String pluginVersion) {}

    private final List<FileStudioPlugin> plugins = new CopyOnWriteArrayList<>();
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * 通过 SPI 加载所有类路径上的插件。幂等：重复调用会先清空已注册插件。
     */
    public void loadPlugins() {
        shutdown();
        ServiceLoader<FileStudioPlugin> loader = ServiceLoader.load(FileStudioPlugin.class);
        for (FileStudioPlugin plugin : loader) {
            registerPlugin(plugin);
        }
        log.info("Loaded {} plugin(s), {} handler(s)", plugins.size(), entries.size());
    }

    /**
     * 注册一个已构造的插件实例，并提取其所有处理器。
     *
     * <p>{@link FileStudioPlugin#initialize()} 抛出的异常会被记录并忽略，
     * 后续仍尝试读取其处理器列表。
     *
     * @param plugin 插件实例
     * @throws NullPointerException 插件为 {@code null} 时抛出
     */
    public void registerPlugin(FileStudioPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        plugins.add(plugin);
        try {
            plugin.initialize();
        } catch (RuntimeException e) {
            log.warn("Plugin {} initialize() failed: {}", plugin.getName(), e.getMessage(), e);
        }
        List<FileHandlerFactory> factories = plugin.getHandlers();
        if (factories != null) {
            for (FileHandlerFactory f : factories) {
                FileHandler h = f.createHandler();
                entries.add(new Entry(h, plugin.getName(), plugin.getVersion()));
            }
        }
        log.debug("Registered plugin {} v{} ({} handler(s))",
                plugin.getName(), plugin.getVersion(),
                factories == null ? 0 : factories.size());
    }

    /**
     * 直接注册一个处理器实例（不经过插件包装，便于测试与内置格式）。
     *
     * @param handler 处理器实例
     * @throws NullPointerException 处理器为 {@code null} 时抛出
     */
    public void registerHandler(FileHandler handler) {
        Objects.requireNonNull(handler, "handler");
        entries.add(new Entry(handler, "builtin", "0.0.0"));
    }

    /**
     * 动态安装外部 JAR 包形式的插件。读取其 SPI 声明并加载。
     *
     * @param jarFile 插件 JAR 文件
     */
    public void installPlugin(File jarFile) {
        Objects.requireNonNull(jarFile, "jarFile");
        if (!jarFile.isFile()) {
            throw new FileStudioException("Plugin jar not found: " + jarFile);
        }
        try {
            URL[] urls = {jarFile.toURI().toURL()};
            URLClassLoader cl = new URLClassLoader(urls, getClass().getClassLoader());
            ServiceLoader<FileStudioPlugin> loader =
                    ServiceLoader.load(FileStudioPlugin.class, cl);
            int before = entries.size();
            for (FileStudioPlugin plugin : loader) {
                registerPlugin(plugin);
            }
            log.info("Installed {} handler(s) from {}", entries.size() - before, jarFile.getName());
        } catch (Exception e) {
            throw new FileStudioException("Failed to install plugin: " + jarFile, e);
        }
    }

    /**
     * 按优先级查找可处理目标文件的处理器。
     *
     * <p>先遍历扩展名精确匹配，未命中再逐个调用 {@link FileHandler#canHandle}。
     *
     * @param file 目标文件，{@code null} 时返回空
     * @return 首个匹配的处理器；无匹配时为 {@link Optional#empty()}
     */
    public Optional<FileHandler> findHandler(File file) {
        if (file == null) return Optional.empty();
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        for (Entry e : entries) {
            if (!ext.isEmpty() && ext.equalsIgnoreCase(e.handler().getExtension())) {
                return Optional.of(e.handler());
            }
        }
        for (Entry e : entries) {
            try {
                if (e.handler().canHandle(file)) return Optional.of(e.handler());
            } catch (RuntimeException ex) {
                log.warn("canHandle failed on {}: {}", e.handler().getClass().getName(), ex.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * 按扩展名精确查找处理器（用于魔数检测后的兜底路由）。
     *
     * @param extension 扩展名，忽略大小写
     * @return 对应处理器；无匹配或参数为空时为 {@link Optional#empty()}
     */
    public Optional<FileHandler> findHandlerByExtension(String extension) {
        if (extension == null || extension.isEmpty()) return Optional.empty();
        for (Entry e : entries) {
            if (extension.equalsIgnoreCase(e.handler().getExtension())) {
                return Optional.of(e.handler());
            }
        }
        return Optional.empty();
    }

    /**
     * 列出所有已注册处理器的描述信息，用于 UI 格式列表展示。
     *
     * @return 不可变描述列表，按注册顺序
     */
    public List<PluginHandlerInfo> listHandlers() {
        List<PluginHandlerInfo> result = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            FileHandler h = e.handler();
            result.add(new PluginHandlerInfo(
                    e.pluginName(), e.pluginVersion(),
                    h.getExtension(), h.getMimeType(),
                    h.getEditCapability(), h.getDescription()));
        }
        return List.copyOf(result);
    }

    /**
     * 所有已加载插件。
     *
     * @return 不可变插件列表
     */
    public List<FileStudioPlugin> getPlugins() {
        return List.copyOf(plugins);
    }

    /**
     * 所有已注册处理器。
     *
     * @return 不可变处理器列表，按注册顺序
     */
    public List<FileHandler> getHandlers() {
        List<FileHandler> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.handler());
        return List.copyOf(out);
    }

    /** 释放所有插件资源。 */
    public void shutdown() {
        for (FileStudioPlugin p : plugins) {
            try {
                p.shutdown();
            } catch (RuntimeException e) {
                log.warn("Plugin {} shutdown() failed: {}", p.getName(), e.getMessage());
            }
        }
        plugins.clear();
        entries.clear();
    }
}

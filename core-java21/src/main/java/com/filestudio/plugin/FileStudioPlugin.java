package com.filestudio.plugin;

import java.util.List;

/**
 * 插件生命周期接口。一个插件可提供一种或多种文件格式支持。
 *
 * <p>插件通过 Java SPI 机制注册：在 JAR 的
 * {@code META-INF/services/com.filestudio.plugin.FileStudioPlugin} 文件中声明实现类全限定名，
 * 由 {@link PluginManager} 通过 {@link java.util.ServiceLoader} 加载。
 */
public interface FileStudioPlugin {

    /**
     * 插件名称。
     *
     * @return 唯一标识，建议使用反向域名风格
     */
    String getName();

    /**
     * 插件版本。
     *
     * @return 语义化版本号
     */
    String getVersion();

    /**
     * 该插件提供的所有处理器工厂。
     *
     * @return 工厂列表，可为 {@code null} 表示不提供处理器
     */
    List<FileHandlerFactory> getHandlers();

    /** 插件被加载后调用，用于初始化资源。 */
    default void initialize() {}

    /** 插件被卸载/应用关闭时调用，用于释放资源。 */
    default void shutdown() {}
}

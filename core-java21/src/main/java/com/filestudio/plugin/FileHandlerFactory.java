package com.filestudio.plugin;

/**
 * 处理器工厂：插件通过它向核心暴露其 {@link FileHandler} 的构造方式。
 *
 * <p>使用工厂而非直接注册实例，便于：
 * <ul>
 *   <li>插件延迟实例化（按需创建，节省资源）</li>
 *   <li>为同一插件暴露多个格式处理器</li>
 *   <li>在热加载/卸载时控制生命周期</li>
 * </ul>
 */
@FunctionalInterface
public interface FileHandlerFactory {

    /**
     * 创建一个新的处理器实例。
     *
     * @return 处理器实例；每次调用应返回相互独立的实例
     */
    FileHandler createHandler();
}

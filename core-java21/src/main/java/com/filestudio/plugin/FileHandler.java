package com.filestudio.plugin;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;

import java.io.File;
import java.util.Map;

/**
 * 文件格式处理器接口 —— 所有具体格式（文本、JSON、图片、压缩包…）实现的统一契约。
 *
 * <p>实现类应为无状态、线程安全；同一实例可被 {@link PluginManager} 复用于多个文件。
 * 实现类通常通过 {@link FileHandlerFactory} 创建，以支持插件化加载。
 */
public interface FileHandler {

    /**
     * 该处理器负责的文件扩展名（不含点，小写），如 {@code "txt"}、{@code "json"}。
     *
     * @return 扩展名
     */
    String getExtension();

    /**
     * 对应的 MIME 类型，如 {@code "text/plain"}。未知返回 {@code "application/octet-stream"}。
     *
     * @return MIME 类型
     */
    String getMimeType();

    /**
     * 该格式的编辑能力。
     *
     * @return 编辑能力等级
     */
    EditCapability getEditCapability();

    /**
     * 人类可读的格式描述，用于 UI 展示。
     *
     * @return 格式描述
     */
    String getDescription();

    /**
     * 快速判断该处理器是否能处理目标文件。优先基于扩展名/MIME，不应进行重 IO。
     * 返回 false 时 {@link PluginManager} 会继续尝试其它处理器。
     *
     * @param file 目标文件
     * @return 是否能处理该文件
     */
    boolean canHandle(File file);

    /**
     * 将文件解析为统一 {@link Document} 模型。实现应保证：
     * <ul>
     *   <li>对大文件采用流式/分块策略，避免一次性读入内存</li>
     *   <li>读取失败抛出 {@code FileStudioException}，由核心层统一处理</li>
     * </ul>
     *
     * @param file 目标文件
     * @return 解析后的文档模型
     * @throws com.filestudio.core.FileStudioException 文件不可读或格式不受支持时抛出
     */
    Document parse(File file);

    /**
     * 将文档写回文件。仅 {@link EditCapability#FULL} 的处理器需要可靠实现；
     * {@link EditCapability#PARTIAL}/{@code VIEW_ONLY} 的处理器可抛出
     * {@link UnsupportedOperationException}。
     *
     * @param doc    待写回的文档
     * @param output 目标文件
     * @throws UnsupportedOperationException 处理器不支持回写时抛出
     */
    void render(Document doc, File output);

    /**
     * 提取文件元数据（大小、编码、行数、图片尺寸…），用于信息面板展示。
     *
     * @param file 目标文件
     * @return 元数据键值表，文件不可读时返回空表
     */
    Map<String, Object> getMetadata(File file);
}

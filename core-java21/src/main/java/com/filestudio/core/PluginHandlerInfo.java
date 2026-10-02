package com.filestudio.core;

import java.util.List;
import java.util.Objects;

/**
 * 单个格式处理器注册后的静态描述信息，用于 UI 展示与格式列表查询，
 * 不持有实际处理逻辑（避免误用导致非托管实例）。
 *
 * @param pluginName      提供该处理器的插件名
 * @param pluginVersion   插件版本号
 * @param extension       主扩展名（小写，不含点）
 * @param mimeType        对应的 MIME 类型
 * @param editCapability  编辑能力等级
 * @param description     人类可读描述，为 {@code null} 时归一化为空串
 */
public record PluginHandlerInfo(String pluginName,
                                String pluginVersion,
                                String extension,
                                String mimeType,
                                EditCapability editCapability,
                                String description) {

    /** 紧凑构造器：校验必填字段并归一化可选描述。 */
    public PluginHandlerInfo {
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(editCapability, "editCapability");
        description = description == null ? "" : description;
    }

    /**
     * 空处理器列表常量。
     *
     * @return 空列表
     */
    public static List<PluginHandlerInfo> emptyList() {
        return List.of();
    }
}

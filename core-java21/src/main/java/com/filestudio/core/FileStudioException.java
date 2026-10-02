package com.filestudio.core;

/**
 * FileStudio 核心层统一异常。所有插件/引擎在解析、渲染、注册过程中
 * 抛出的受检异常均包装为本类型，便于上层一致处理。
 */
public class FileStudioException extends RuntimeException {

    /**
     * 以错误消息构造。
     *
     * @param message 错误描述
     */
    public FileStudioException(String message) {
        super(message);
    }

    /**
     * 以错误消息和根因构造。
     *
     * @param message 错误描述
     * @param cause   底层异常
     */
    public FileStudioException(String message, Throwable cause) {
        super(message, cause);
    }
}

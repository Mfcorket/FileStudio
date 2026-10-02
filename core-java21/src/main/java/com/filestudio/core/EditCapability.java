package com.filestudio.core;

/**
 * 编辑能力分级。决定某个文件格式在 FileStudio 中可被操作的程度。
 */
public enum EditCapability {
    /** 完整编辑：可读取、修改、保存，覆盖原文件语义不变。 */
    FULL,
    /** 部分编辑：可提取/导出部分内容，但不能无损回写原格式。 */
    PARTIAL,
    /** 仅查看：解析与展示，不可写回。 */
    VIEW_ONLY
}

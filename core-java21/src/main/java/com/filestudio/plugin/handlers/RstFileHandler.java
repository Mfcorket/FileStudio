package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * reStructuredText 文档处理器。
 *
 * <p>元数据：章节数量（标题采用装饰线 = / - / ^ / ~，且下划线紧跟文本行）、
 * 指令数量、链接数量与近似字数。轻量正则匹配，不构建 AST。
 */
public class RstFileHandler extends AbstractTextHandler {

    /**
     * reStructuredText 处理器。
     */
    public RstFileHandler() {}

    /** 指令（显式标记）：.. name:: 或 .. |sub| name:: 形式。 */
    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^\\s*\\.\\.\\s+(?:\\|[^|]*\\|\\s+)?([a-zA-Z0-9_-]+)::");

    /** 行内链接 `text <url>`_ 或独立链接。 */
    private static final Pattern REFERENCE = Pattern.compile("`[^`]+`_");

    /** 一行是否为纯字符重复（标题上/下划线）。 */
    private static final Pattern DECORATION = Pattern.compile("^[=\\-^~`\"'*+#]{3,}$");

    @Override
    public String getExtension() {
        return "rst";
    }

    @Override
    public String getMimeType() {
        return "text/x-rst";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "reStructuredText document";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        // 标题计数：只统计「跟在文本行之后的装饰线」（underline 风格），
        // 这样上划线 + 下划线成对出现时只算一次。
        int headings = 0;
        String[] lines = content.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            String prev = lines[i - 1].trim();
            if (DECORATION.matcher(line).matches()
                    && !prev.isEmpty()
                    && !DECORATION.matcher(prev).matches()) {
                headings++;
            }
        }
        meta.put("headingCount", headings);

        Matcher dm = DIRECTIVE.matcher(content);
        int directives = 0;
        while (dm.find()) {
            directives++;
        }
        meta.put("directiveCount", directives);

        Matcher rm = REFERENCE.matcher(content);
        int refs = 0;
        while (rm.find()) {
            refs++;
        }
        meta.put("referenceCount", refs);

        meta.put("wordCount", content.trim().isEmpty()
                ? 0 : content.trim().split("\\s+").length);
    }
}
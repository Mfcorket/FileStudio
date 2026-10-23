package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AsciiDoc 文档处理器。
 *
 * <p>元数据：标题数量（= 或 == 开头，按级别）、块定界符数量（---- / ==== /
 * .... 等）、链接数量、行内代码与近似字数。轻量正则，不构建 AST。
 */
public class AsciiDocFileHandler extends AbstractTextHandler {

    /**
     * AsciiDoc 处理器。
     */
    public AsciiDocFileHandler() {}

    /** 标题：行首一个或多个 = 加空格。 */
    private static final Pattern HEADING = Pattern.compile("(?m)^(=+)\\s+(.+?)\\s*$");

    /** 块定界符：连续 4 个及以上相同字符（- = _ . * + ~ ^）。 */
    private static final Pattern BLOCK_DELIMITER = Pattern.compile(
            "(?m)^\\s*([=\\-_~^+*.])\\1{3,}\\s*$");

    private static final Pattern LINK = Pattern.compile("(?:https?://|mailto:)[^\\s\\[\\]<>]+");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]+`");

    @Override
    public String getExtension() {
        return "adoc";
    }

    @Override
    public String getMimeType() {
        return "text/x-asciidoc";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "AsciiDoc document";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        int h1 = 0;
        int h2 = 0;
        int h3 = 0;
        Matcher hm = HEADING.matcher(content);
        while (hm.find()) {
            int len = hm.group(1).length();
            if (len <= 1) h1++;
            else if (len == 2) h2++;
            else h3++;
        }
        meta.put("h1Count", h1);
        meta.put("h2Count", h2);
        meta.put("h3Count", h3);
        meta.put("headingCount", h1 + h2 + h3);

        Matcher bm = BLOCK_DELIMITER.matcher(content);
        int blocks = 0;
        while (bm.find()) {
            blocks++;
        }
        meta.put("blockCount", blocks);

        Matcher lm = LINK.matcher(content);
        int links = 0;
        while (lm.find()) {
            links++;
        }
        meta.put("linkCount", links);

        Matcher icm = INLINE_CODE.matcher(content);
        int code = 0;
        while (icm.find()) {
            code++;
        }
        meta.put("inlineCodeCount", code);

        meta.put("wordCount", content.trim().isEmpty()
                ? 0 : content.trim().split("\\s+").length);
    }
}
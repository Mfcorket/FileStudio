package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档处理器。
 *
 * <p>元数据：标题数量（按级别）、链接数量、列表项数量、代码块数量、字数（近似，按空白切分）。
 * 使用轻量正则匹配，不构建 AST。
 */
public class MarkdownFileHandler extends AbstractTextHandler {

    /**
     * Markdown 处理器。
     */
    public MarkdownFileHandler() {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$", Pattern.MULTILINE);
    private static final Pattern LINK = Pattern.compile("!?\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[\\s]*[-*+]|\\s*\\d+\\.)\\s+", Pattern.MULTILINE);
    private static final Pattern CODE_FENCE = Pattern.compile("```", Pattern.MULTILINE);

    @Override
    public String getExtension() {
        return "md";
    }

    @Override
    public String getMimeType() {
        return "text/markdown";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "Markdown document";
    }

    @Override
    public boolean canHandle(java.io.File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        return ext.equals("md") || ext.equals("markdown");
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        // 标题分级计数
        int[] levels = new int[7];
        Matcher hm = HEADING.matcher(content);
        while (hm.find()) {
            int lvl = hm.group(1).length();
            if (lvl >= 1 && lvl <= 6) levels[lvl + 1]++;
        }
        int headingCount = 0;
        for (int i = 1; i < levels.length; i++) headingCount += levels[i];
        meta.put("headingCount", headingCount);

        int totalLinks = 0;
        Matcher lm = LINK.matcher(content);
        while (lm.find()) totalLinks++;
        meta.put("linkCount", totalLinks);

        int listItems = 0;
        Matcher lm2 = LIST_ITEM.matcher(content);
        while (lm2.find()) listItems++;
        meta.put("listItemCount", listItems);

        int fences = 0;
        Matcher fm = CODE_FENCE.matcher(content);
        while (fm.find()) fences++;
        meta.put("codeBlockCount", fences / 2);

        // 近似字数：按空白切分后非空片段计数
        int words = content.trim().split("\\s+").length;
        meta.put("wordCount", content.trim().isEmpty() ? 0 : words);
    }
}

package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 格式处理器。
 *
 * <p>元数据：文档数（多文档用 {@code ---} 分隔）、顶层键数、列表项数、缩进层级深度。
 * 不构建 YAML AST，仅做行级结构扫描——足以让 UI 展示结构概览与校验提示。
 *
 * <p>FULL 编辑能力：内容原样回写（不重排缩进）。
 */
public class YamlFileHandler extends AbstractTextHandler {

    private static final Pattern DOC_SEP = Pattern.compile("(?m)^---\\s*$");
    private static final Pattern TOP_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.\\-]*\\s*:");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*-\\s+");

    @Override
    public String getExtension() {
        return "yaml";
    }

    @Override
    public String getMimeType() {
        return "application/yaml";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "YAML document";
    }

    @Override
    public boolean canHandle(java.io.File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        return ext.equals("yaml") || ext.equals("yml");
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        Matcher sep = DOC_SEP.matcher(content);
        int docCount = 1;
        while (sep.find()) docCount++;

        int topLevelKeys = 0;
        int listItems = 0;
        int maxIndent = 0;
        for (String line : content.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int indent = leadingWhitespace(line);
            if (indent > maxIndent) maxIndent = indent;
            if (TOP_KEY.matcher(line).find()) topLevelKeys++;
            if (LIST_ITEM.matcher(line).find()) listItems++;
        }

        meta.put("documentCount", docCount);
        meta.put("topLevelKeyCount", topLevelKeys);
        meta.put("listItemCount", listItems);
        meta.put("maxIndent", maxIndent);
    }

    private static int leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }
}

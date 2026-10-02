package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS 格式处理器。
 *
 * <p>元数据：规则块数量、选择器数量、属性数量、@规则数量、已识别的 @规则名（最多 16 个）。
 *
 * <p>采用轻量扫描：以花括号配对识别规则块，块内按 {@code property: value} 计数属性。
 * 不处理嵌套 @media 内的子规则归属（仅扁平计数）。
 */
public class CssFileHandler extends AbstractTextHandler {

    /**
     * CSS 样式表处理器。
     */
    public CssFileHandler() {}

    private static final int MAX_AT_RULES = 16;
    private static final Pattern AT_RULE = Pattern.compile("@([a-zA-Z-]+)");
    private static final Pattern PROPERTY = Pattern.compile(
            "^[\\s\\w\\-]+\\s*:\\s*[^;}]+", Pattern.CASE_INSENSITIVE);

    @Override
    public String getExtension() {
        return "css";
    }

    @Override
    public String getMimeType() {
        return "text/css";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "CSS stylesheet";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        int rules = 0;
        int properties = 0;
        int depth = 0;
        StringBuilder selector = new StringBuilder();
        List<String> atRules = new ArrayList<>();

        int i = 0;
        int n = content.length();
        // 扫描注释并跳过
        boolean inBlockComment = false;

        while (i < n) {
            char c = content.charAt(i);

            // 块注释
            if (!inBlockComment && i + 1 < n && c == '/' && content.charAt(i + 1) == '*') {
                inBlockComment = true;
                i += 2;
                continue;
            }
            if (inBlockComment) {
                if (i + 1 < n && c == '*' && content.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }

            if (c == '@') {
                Matcher m = AT_RULE.matcher(content);
                m.region(i, n);
                if (m.find(i)) {
                    String name = "@" + m.group(1);
                    if (atRules.size() < MAX_AT_RULES) {
                        atRules.add(name);
                    } else if (atRules.size() == MAX_AT_RULES) {
                        atRules.set(MAX_AT_RULES - 1, name + "…");
                    }
                }
            }

            if (c == '{') {
                rules++;
                depth++;
                selector.setLength(0);
                i++;
                // 读取属性行直到 '}'
                while (i < n && content.charAt(i) != '}') {
                    int lineEnd = content.indexOf('\n', i);
                    if (lineEnd < 0) lineEnd = n;
                    String line = content.substring(i, lineEnd).strip();
                    if (!line.isEmpty() && !line.startsWith("@") && PROPERTY.matcher(line).find()) {
                        properties++;
                    }
                    i = lineEnd + 1;
                }
                if (i < n && content.charAt(i) == '}') {
                    i++;
                    depth--;
                }
            } else {
                i++;
            }
        }

        meta.put("ruleCount", rules);
        meta.put("propertyCount", properties);
        if (!atRules.isEmpty()) {
            meta.put("atRules", List.copyOf(atRules));
        }
    }
}

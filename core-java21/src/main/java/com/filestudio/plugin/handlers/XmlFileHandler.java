package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * XML 格式处理器：校验基本格式正确性（标签闭合）并提供结构元数据。
 *
 * <p>不构建 DOM，仅做标签栈扫描以判断 well-formed 并统计根元素、根层子元素数与最大深度。
 * 不处理实体引用（{@code &amp;...;}）的展开，也不做命名空间解析——目标为元数据展示，
 * 而非严格的 XSD/XPath 校验。
 *
 * <p>FULL 编辑能力：内容原样回写。
 */
public class XmlFileHandler extends AbstractTextHandler {

    /**
     * XML / plist 处理器。
     */
    public XmlFileHandler() {}

    @Override
    public String getExtension() {
        return "xml";
    }

    @Override
    public String getMimeType() {
        return "application/xml";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "XML document";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isBlank()) return;
        XmlScanResult r = scan(content);
        meta.put("valid", r.valid);
        meta.put("rootElement", r.rootElement);
        meta.put("maxDepth", r.maxDepth);
        meta.put("rootChildCount", r.rootChildCount);
        if (!r.valid) {
            meta.put("error", r.error);
        }
    }

    /** 扫描结果。 */
    record XmlScanResult(boolean valid, String rootElement, int maxDepth, int rootChildCount,
                         String error) {
        static XmlScanResult invalid(String err) {
            return new XmlScanResult(false, null, 0, 0, err);
        }
    }

    static XmlScanResult scan(String s) {
        ArrayDeque<String> stack = new ArrayDeque<>();
        int depth = 0;
        int maxDepth = 0;
        String root = null;
        int rootChildCount = 0;
        int i = 0;
        int n = s.length();

        while (i < n) {
            if (s.startsWith("<!--", i)) {
                int end = s.indexOf("-->", i + 4);
                if (end < 0) return XmlScanResult.invalid("Unterminated comment at offset " + i);
                i = end + 3;
                continue;
            }
            if (s.startsWith("<![CDATA[", i)) {
                int end = s.indexOf("]]>", i + 9);
                if (end < 0) return XmlScanResult.invalid("Unterminated CDATA at offset " + i);
                i = end + 3;
                continue;
            }
            if (s.startsWith("<?", i)) {
                int end = s.indexOf("?>", i + 2);
                if (end < 0) return XmlScanResult.invalid("Unterminated PI at offset " + i);
                i = end + 2;
                continue;
            }
            if (s.charAt(i) == '<') {
                if (i + 1 >= n) return XmlScanResult.invalid("Trailing '<'");
                if (s.charAt(i + 1) == '/') {
                    // 结束标签
                    int end = s.indexOf('>', i);
                    if (end < 0) return XmlScanResult.invalid("Unterminated closing tag at " + i);
                    String name = s.substring(i + 2, end).trim();
                    if (stack.isEmpty()) {
                        return XmlScanResult.invalid("Unexpected closing tag '" + name + "'");
                    }
                    String top = stack.pop();
                    if (!top.equals(name)) {
                        return XmlScanResult.invalid("Mismatched tag: expected </" + top
                                + ">, got </" + name + ">");
                    }
                    depth--;
                    i = end + 1;
                    continue;
                } else {
                    // 开始标签
                    int end = s.indexOf('>', i + 1);
                    if (end < 0) return XmlScanResult.invalid("Unterminated opening tag at " + i);
                    String raw = s.substring(i + 1, end);
                    boolean selfClosing = raw.endsWith("/");
                    if (selfClosing) raw = raw.substring(0, raw.length() - 1);
                    String tag = tagName(raw);
                    if (!tag.isEmpty()) {
                        if (root == null) root = tag;
                        depth++;
                        if (depth > maxDepth) maxDepth = depth;
                        // 栈中仅根元素时，当前标签即根的直属子元素（含自闭合）
                        if (stack.size() == 1) rootChildCount++;
                        if (!selfClosing) {
                            stack.push(tag);
                        }
                    } else if (!selfClosing) {
                        return XmlScanResult.invalid("Empty tag name at offset " + i);
                    }
                    i = end + 1;
                    continue;
                }
            }
            i++;
        }

        if (!stack.isEmpty()) {
            return XmlScanResult.invalid("Unclosed tag: <" + stack.peek() + ">");
        }
        if (root == null) {
            return XmlScanResult.invalid("No root element found");
        }
        return new XmlScanResult(true, root, maxDepth, rootChildCount, null);
    }

    /** 从标签字符串中提取标签名（第一个空白或 '/' 之前的部分）。 */
    private static String tagName(String raw) {
        String t = raw.trim();
        int end = 0;
        while (end < t.length() && !Character.isWhitespace(t.charAt(end)) && t.charAt(end) != '/') {
            end++;
        }
        return t.substring(0, end);
    }
}

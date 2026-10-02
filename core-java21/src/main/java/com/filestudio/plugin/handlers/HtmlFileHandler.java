package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 格式处理器。
 *
 * <p>元数据：页面标题、是否含 {@code <head>}/{@code <body>}、标签总数、注释数、
 * 标签最大嵌套深度。
 *
 * <p>不构建 DOM，仅做标签栈扫描——足以让 UI 展示结构概览。
 * 自闭合标签与 void 元素（{@code br}/@code img} 等）不压栈，避免深度误判。
 */
public class HtmlFileHandler extends AbstractTextHandler {

    /**
     * HTML 文档处理器。
     */
    public HtmlFileHandler() {}

    /** HTML5 void 元素：无结束标签。 */
    private static final Set<String> VOID_ELEMENTS = new HashSet<>(Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"));

    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEAD = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY = Pattern.compile("<body\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    /** 起始标签（含自闭合），排除结束标签与注释。 */
    private static final Pattern START_TAG = Pattern.compile("<(\\w+)(\\s[^>]*)?(/?)>",
            Pattern.CASE_INSENSITIVE);
    /** 结束标签。 */
    private static final Pattern END_TAG = Pattern.compile("</(\\w+)\\s*>", Pattern.CASE_INSENSITIVE);
    /** 注释。 */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @Override
    public String getExtension() {
        return "html";
    }

    @Override
    public String getMimeType() {
        return "text/html";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "HTML document";
    }

    @Override
    public boolean canHandle(java.io.File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        return ext.equals("html") || ext.equals("htm") || ext.equals("xhtml");
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        Matcher tm = TITLE.matcher(content);
        if (tm.find()) {
            meta.put("title", tm.group(1).trim());
        }
        meta.put("hasHead", HEAD.matcher(content).find());
        meta.put("hasBody", BODY.matcher(content).find());

        int comments = 0;
        Matcher cm = COMMENT.matcher(content);
        while (cm.find()) comments++;
        meta.put("commentCount", comments);

        meta.put("tagCount", countAllTags(content));
        meta.put("maxDepth", computeDepth(content));
    }

    private int countAllTags(String content) {
        int count = 0;
        Matcher sm = START_TAG.matcher(content);
        while (sm.find()) count++;
        Matcher em = END_TAG.matcher(content);
        while (em.find()) count++;
        return count;
    }

    private int computeDepth(String content) {
        Deque<String> stack = new ArrayDeque<>();
        int maxDepth = 0;

        // 合并遍历：按位置处理起始与结束标签
        int i = 0;
        while (i < content.length()) {
            int lt = content.indexOf('<', i);
            if (lt < 0) break;

            // 注释
            if (content.startsWith("<!--", lt)) {
                int end = content.indexOf("-->", lt + 4);
                i = (end < 0) ? content.length() : end + 3;
                continue;
            }
            // 处理指令
            if (lt + 1 < content.length() && content.charAt(lt + 1) == '?') {
                int end = content.indexOf("?>", lt + 2);
                i = (end < 0) ? content.length() : end + 2;
                continue;
            }
            // 结束标签
            if (lt + 1 < content.length() && content.charAt(lt + 1) == '/') {
                int gt = content.indexOf('>', lt);
                if (gt < 0) break;
                String name = content.substring(lt + 2, gt).trim().toLowerCase();
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                i = gt + 1;
                continue;
            }
            // 起始标签
            int gt = content.indexOf('>', lt);
            if (gt < 0) break;
            String inner = content.substring(lt + 1, gt);
            int sp = inner.indexOf(' ');
            int end = sp >= 0 ? sp : inner.length();
            String name = inner.substring(0, end).trim().toLowerCase();
            boolean selfClosing = inner.endsWith("/");
            if (!name.isEmpty() && !selfClosing && !VOID_ELEMENTS.contains(name)) {
                stack.push(name);
                if (stack.size() > maxDepth) maxDepth = stack.size();
            }
            i = gt + 1;
        }
        return maxDepth;
    }
}

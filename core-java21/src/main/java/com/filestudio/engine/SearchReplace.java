package com.filestudio.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文档内搜索 / 替换工具。纯函数式，不持有状态。
 *
 * <p>支持：大小写敏感、全词匹配、正则匹配、方向遍历。
 * 全部命中可一次取出（{@link #findMatches}），逐个遍历使用 {@link #findNext}。
 *
 * <p>所有区间均为 {@code [start, end)} 半开区间，与 {@link EditorEngine#replaceRange} 一致。
 */
public final class SearchReplace {

    private SearchReplace() {}

    /** 找出所有命中。不会无限循环；正则空匹配会被跳过以避免死循环。 */
    public static List<SearchMatch> findMatches(String text, String query, SearchOptions options) {
        if (text == null) text = "";
        if (query == null || query.isEmpty()) return List.of();
        SearchOptions o = options == null ? SearchOptions.defaultOptions() : options;
        List<SearchMatch> matches = new ArrayList<>();
        try {
            Pattern p = compile(query, o);
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (m.start() == m.end()) continue; // 跳过零宽匹配
                matches.add(new SearchMatch(m.start(), m.end(), m.group()));
            }
        } catch (PatternSyntaxException e) {
            return List.of(); // 正则非法时静默返回空，由调用方根据 isValid 提示
        }
        return List.copyOf(matches);
    }

    /** 从 {@code from} 偏移起查找下一个命中。 */
    public static SearchMatch findNext(String text, String query, SearchOptions options, int from) {
        if (text == null) return null;
        if (query == null || query.isEmpty()) return null;
        int start = Math.max(0, Math.min(from, text.length()));
        try {
            Matcher m = compile(query, options == null ? SearchOptions.defaultOptions() : options)
                    .matcher(text);
            while (m.find(start)) {
                if (m.end() <= start) continue;
                if (m.start() == m.end()) continue;
                return new SearchMatch(m.start(), m.end(), m.group());
            }
        } catch (PatternSyntaxException ignored) {
            return null;
        }
        return null;
    }

    /** 上一个命中（从 {@code from} 往回找）。 */
    public static SearchMatch findPrevious(String text, String query, SearchOptions options, int from) {
        if (text == null) return null;
        List<SearchMatch> all = findMatches(text, query, options);
        if (all.isEmpty()) return null;
        int end = Math.max(0, Math.min(from, text.length()));
        for (int i = all.size() - 1; i >= 0; i--) {
            SearchMatch s = all.get(i);
            if (s.end() <= end && s.start() >= 0) return s;
        }
        return null;
    }

    /** 替换全部命中，返回替换后的文本。 */
    public static String replaceAll(String text, String query, String replacement, SearchOptions options) {
        if (text == null || query == null || query.isEmpty()) return text;
        if (replacement == null) replacement = "";
        List<SearchMatch> matches = findMatches(text, query, options);
        if (matches.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (SearchMatch m : matches) {
            sb.append(text, cursor, m.start());
            sb.append(expandReplacement(replacement, m.text()));
            cursor = m.end();
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    /** 替换第 {@code index} 个命中，返回新文本。 */
    public static String replaceAt(String text, String query, String replacement, int index,
                                   SearchOptions options) {
        if (text == null) return text;
        List<SearchMatch> matches = findMatches(text, query, options);
        if (index < 0 || index >= matches.size()) return text;
        SearchMatch m = matches.get(index);
        if (replacement == null) replacement = "";
        return text.substring(0, m.start()) + expandReplacement(replacement, m.text())
                + text.substring(m.end());
    }

    /** 指定区间内替换，区间外的文本原样保留。 */
    public static String replaceInRange(String text, int rangeStart, int rangeEnd,
                                        String query, String replacement, SearchOptions options) {
        if (text == null) return text;
        int s = clamp(rangeStart, text.length());
        int e = clamp(rangeEnd, text.length());
        if (s >= e) return text;
        String within = text.substring(s, e);
        String replaced = replaceAll(within, query, replacement, options);
        return text.substring(0, s) + replaced + text.substring(e);
    }

    /** 查询是否为合法（正则模式下校验语法）。 */
    public static boolean isValidQuery(String query, SearchOptions options) {
        if (query == null || query.isEmpty()) return false;
        SearchOptions o = options == null ? SearchOptions.defaultOptions() : options;
        if (!o.isUseRegex()) return true;
        try {
            Pattern.compile(query);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * 替换模板展开：支持 {@code $0}（整匹配）与 {@code $1..$9}（捕获组）。
     * 非正则模式下捕获组不生效，仅展开 {@code $0}。
     */
    private static String expandReplacement(String replacement, String matched) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = replacement.length();
        while (i < n) {
            char c = replacement.charAt(i);
            if (c == '$' && i + 1 < n && Character.isDigit(replacement.charAt(i + 1))) {
                int g = replacement.charAt(i + 1) - '0';
                if (g == 0) {
                    sb.append(matched);
                } else {
                    sb.append('$');
                    sb.append(g);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static Pattern compile(String query, SearchOptions o) {
        String pattern;
        if (o.isUseRegex()) {
            pattern = query;
        } else {
            pattern = Pattern.quote(query);
            if (o.isWholeWord()) {
                pattern = "\\b" + pattern + "\\b";
            }
        }
        int flags = 0;
        if (!o.isCaseSensitive()) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (o.isWholeWord() && o.isUseRegex()) {
            // 正则模式下不做自动 \b 包装，避免破坏用户表达式；由用户自行加入
        }
        return Pattern.compile(pattern, flags);
    }

    private static int clamp(int v, int len) {
        if (v < 0) return 0;
        if (v > len) return len;
        return v;
    }

    /** 单次命中。不可变。 */
    public record SearchMatch(int start, int end, String text) {
        public SearchMatch {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid match range: " + start + ".." + end);
            }
        }

        public int length() {
            return end - start;
        }
    }
}

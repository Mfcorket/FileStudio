package com.filestudio.engine;

import java.util.Arrays;

/**
 * 文档行索引：在字符偏移与（行, 列）坐标之间做双向映射。
 *
 * <p>编辑器光标定位、书签锚定、语法高亮行切分都依赖此结构。索引在构造时一次性
 * 构建（O(n)），后续查询为 O(log n)（二分）或 O(1)（列计算）。
 *
 * <p>坐标约定：
 * <ul>
 *   <li>行号从 0 开始，列号从 0 开始</li>
 *   <li>空文档为 1 行（行 0，长度 0），与大多数编辑器的行为一致</li>
 *   <li>{@code offsetOf} 的列越界会被夹到该行长度内</li>
 * </ul>
 *
 * <p>不可变。文档内容变化时重建新实例。
 */
public final class LineIndex {

    /** 空内容对应的空索引。 */
    public static final LineIndex EMPTY = of("");

    private final String content;
    private final int[] lineStarts;

    private LineIndex(String content, int[] lineStarts) {
        this.content = content;
        this.lineStarts = lineStarts;
    }

    /** 构建行索引。content 为 null 时按空串处理。 */
    public static LineIndex of(String content) {
        String c = content == null ? "" : content;
        int n = c.length();
        int[] starts = new int[n + 1];
        int count = 1;
        for (int i = 0; i < n; i++) {
            if (c.charAt(i) == '\n') {
                starts[count++] = i + 1;
            }
        }
        return new LineIndex(c, Arrays.copyOf(starts, count));
    }

    /** 总行数。空文档返回 1。 */
    public int lineCount() {
        return lineStarts.length;
    }

    /** 文档总字符数。 */
    public int charCount() {
        return content.length();
    }

    /** 指定行的起始偏移。行号越界时夹到 [0, lineCount-1]。 */
    public int lineStart(int line) {
        return lineStarts[clampLine(line)];
    }

    /** 指定行的结束偏移（不含换行符，不含下一行的起始字符）。 */
    private int lineEnd(int line) {
        int l = clampLine(line);
        int start = lineStarts[l];
        int end = (l + 1 < lineStarts.length) ? lineStarts[l + 1] : content.length();
        // 排除行尾换行符（CRLF / LF / CR）
        if (end > start && content.charAt(end - 1) == '\n') {
            end--;
            if (end > start && content.charAt(end - 1) == '\r') {
                end--;
            }
        }
        return end;
    }

    /** 指定行的字符长度（不含换行符）。 */
    public int lineLength(int line) {
        return lineEnd(clampLine(line)) - lineStart(clampLine(line));
    }

    /** 指定行的内容（不含换行符）。 */
    public String lineContent(int line) {
        int l = clampLine(line);
        return content.substring(lineStart(l), lineEnd(l));
    }

    /** 偏移对应的行号（0-based）。越界夹到 [0, lineCount-1]。 */
    public int lineOf(int offset) {
        if (offset <= 0) return 0;
        if (offset >= content.length()) return clampLine(lineStarts.length - 1);
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 偏移对应的列号（0-based）。越界夹到该行长度。 */
    public int columnOf(int offset) {
        int o = clampOffset(offset);
        return o - lineStart(lineOf(o));
    }

    /** 由（行, 列）计算偏移。列越界夹到该行长度。 */
    public int offsetOf(int line, int column) {
        int l = clampLine(line);
        int start = lineStarts[l];
        int col = clampCol(column, lineEnd(l) - start);
        return start + col;
    }

    /** 最长行的字符长度（不含换行符）。 */
    public int longestLine() {
        int max = 0;
        for (int i = 0; i < lineStarts.length; i++) {
            max = Math.max(max, lineLength(i));
        }
        return max;
    }

    /** 是否包含换行符。 */
    public boolean hasLineBreak() {
        return lineStarts.length > 1;
    }

    private int clampLine(int line) {
        if (line < 0) return 0;
        if (line >= lineStarts.length) return lineStarts.length - 1;
        return line;
    }

    private int clampOffset(int offset) {
        if (offset < 0) return 0;
        if (offset > content.length()) return content.length();
        return offset;
    }

    private int clampCol(int col, int len) {
        if (col < 0) return 0;
        if (col > len) return len;
        return col;
    }
}

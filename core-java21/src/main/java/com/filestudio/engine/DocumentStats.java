package com.filestudio.engine;

/**
 * 文档统计信息：供 UI 状态栏展示的行数/字数/换行风格等指标。
 *
 * <p>纯计算，不持有内容。所有统计项均可在单次扫描中得出。
 *
 * <p>字数采用"按空白切分非空片段"的近似定义（含标点、数字），
 * 与编辑器常见的 {@code word count} 一致，而非语言学意义上的词。
 */
public final class DocumentStats {

    /** 换行符风格。 */
    public enum LineEnding {
        /** Unix / Linux / macOS 默认。 */
        LF,
        /** Windows 默认。 */
        CRLF,
        /** 旧式 Mac。 */
        CR,
        /** 混合多种风格。 */
        MIXED
    }

    private final int lineCount;
    private final int charCount;
    private final int wordCount;
    private final int codePointCount;
    private final int longestLine;
    private final LineEnding lineEnding;
    private final int lfCount;
    private final int crlfCount;
    private final int crCount;

    private DocumentStats(int lineCount, int charCount, int wordCount, int codePointCount,
                          int longestLine, LineEnding lineEnding,
                          int lfCount, int crlfCount, int crCount) {
        this.lineCount = lineCount;
        this.charCount = charCount;
        this.wordCount = wordCount;
        this.codePointCount = codePointCount;
        this.longestLine = longestLine;
        this.lineEnding = lineEnding;
        this.lfCount = lfCount;
        this.crlfCount = crlfCount;
        this.crCount = crCount;
    }

    /** 计算内容统计。content 为 null 时按空串处理。 */
    public static DocumentStats of(String content) {
        String c = content == null ? "" : content;
        LineIndex idx = LineIndex.of(c);

        int lf = 0, crlf = 0, cr = 0;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '\r') {
                if (i + 1 < c.length() && c.charAt(i + 1) == '\n') {
                    crlf++;
                    i++; // 跳过 \n
                } else {
                    cr++;
                }
            } else if (ch == '\n') {
                lf++;
            }
        }

        LineEnding ending;
        if (lf == 0 && crlf == 0 && cr == 0) {
            ending = LineEnding.LF; // 单行文档无换行符，按 LF 报
        } else if (lf > 0 && crlf > 0) {
            ending = LineEnding.MIXED;
        } else if (crlf > 0 && cr == 0) {
            ending = LineEnding.CRLF;
        } else if (cr > 0 && lf == 0 && crlf == 0) {
            ending = LineEnding.CR;
        } else if (lf > 0 && crlf == 0 && cr == 0) {
            ending = LineEnding.LF;
        } else {
            ending = LineEnding.MIXED;
        }

        int words = c.trim().isEmpty() ? 0 : c.trim().split("\\s+").length;

        return new DocumentStats(
                idx.lineCount(),
                c.length(),
                words,
                c.codePointCount(0, c.length()),
                idx.longestLine(),
                ending,
                lf, crlf, cr);
    }

    /** 总行数（与 LineIndex.lineCount 一致）。 */
    public int lineCount() { return lineCount; }

    /** 字符数（UTF-16 code unit 数）。 */
    public int charCount() { return charCount; }

    /** 近似字数：按空白切分的非空片段计数。 */
    public int wordCount() { return wordCount; }

    /** Unicode 码点数（正确处理 emoji 等补充平面字符）。 */
    public int codePointCount() { return codePointCount; }

    /** 最长行的字符长度（不含换行符）。 */
    public int longestLine() { return longestLine; }

    /** 主导换行符风格。 */
    public LineEnding lineEnding() { return lineEnding; }

    /** LF 换行符数量（不含 CRLF 中的 LF）。 */
    public int lfCount() { return lfCount; }

    /** CRLF 换行符数量。 */
    public int crlfCount() { return crlfCount; }

    /** 单独的 CR 换行符数量。 */
    public int crCount() { return crCount; }

    /** 总换行符数量（按逻辑行计：LF + CRLF + CR）。 */
    public int totalLineBreaks() { return lfCount + crlfCount + crCount; }

    /** 是否为空文档。 */
    public boolean isEmpty() { return charCount == 0; }

    @Override
    public String toString() {
        return "DocumentStats{lines=" + lineCount + ", chars=" + charCount
                + ", words=" + wordCount + ", cps=" + codePointCount
                + ", longest=" + longestLine + ", eol=" + lineEnding + '}';
    }
}

package com.filestudio.engine.highlight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于扫描器（lexer）的语法高亮分词器。
 *
 * <p>采用贪心扫描：在每个位置按优先级尝试匹配
 * 预处理 → 注释 → 字符串/字符 → 数值 → 标识符（含关键字、注解）→ 运算符 → 标点。
 * 空白不作为独立 token 输出，仅用于推进位置（调用方可按需自行切分）。
 *
 * <p>分词结果不含空白，调用方若要保留完整文本需自行按 {@code offset} 重排。
 *
 * <p>不保证语义正确性（例如 C 的 {@code /*} 与除法歧义），目标是覆盖常见场景的视觉高亮，
 * 而非作为编译器级词法分析器。
 */
public final class SyntaxHighlighter {

    private final SyntaxProfile profile;

    /**
     * 构造高亮器。
     *
     * @param profile 语言配置
     * @throws NullPointerException 配置为 {@code null} 时抛出
     */
    public SyntaxHighlighter(SyntaxProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * 语言配置。
     *
     * @return 配置实例
     */
    public SyntaxProfile getProfile() {
        return profile;
    }

    /**
     * 对文本进行分词。
     *
     * @param text 源文本，{@code null} 或空串时返回空列表
     * @return 有序 token 列表，不含空白 token
     */
    public List<Token> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<Token> tokens = new ArrayList<>();
        int n = text.length();
        int i = 0;
        int line = 0;
        int col = 0;

        while (i < n) {
            char c = text.charAt(i);

            // 跳过空白，但不产生 token
            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    line++;
                    col = 0;
                } else {
                    col++;
                }
                i++;
                continue;
            }

            // 预处理 / 指令
            if (profile.preprocessorPrefix != null
                    && text.startsWith(profile.preprocessorPrefix, i)) {
                int end = lineEnd(text, i);
                tokens.add(new Token(TokenKind.PREPROCESSOR, text.substring(i, end), i, line, col));
                col += end - i;
                i = end;
                continue;
            }

            // 单行注释
            if (profile.lineComment != null && text.startsWith(profile.lineComment, i)) {
                int end = lineEnd(text, i);
                tokens.add(new Token(TokenKind.COMMENT, text.substring(i, end), i, line, col));
                col += end - i;
                i = end;
                continue;
            }

            // 块注释
            if (profile.blockCommentStart != null
                    && text.startsWith(profile.blockCommentStart, i)) {
                int end = blockCommentEnd(text, i + profile.blockCommentStart.length(),
                        profile.blockCommentEnd);
                tokens.add(new Token(TokenKind.COMMENT, text.substring(i, end), i, line, col));
                updateLineCol(text, i, end, new int[]{line, col});
                int[] lc = updateLineColResult(text, i, end, line, col);
                line = lc[0];
                col = lc[1];
                i = end;
                continue;
            }

            // 字符字面量（仅在语言支持时）
            if (profile.hasCharLiterals && c == '\'') {
                int end = literalEnd(text, i, '\'', true);
                tokens.add(new Token(TokenKind.CHAR, text.substring(i, end), i, line, col));
                col += end - i;
                i = end;
                continue;
            }
            if (isStringQuote(c, profile)) {
                int end = literalEnd(text, i, c, false);
                tokens.add(new Token(TokenKind.STRING, text.substring(i, end), i, line, col));
                col += end - i;
                i = end;
                continue;
            }

            // 数值
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                int end = numberEnd(text, i);
                tokens.add(new Token(TokenKind.NUMBER, text.substring(i, end), i, line, col));
                col += end - i;
                i = end;
                continue;
            }

            // 注解（@ 开头）
            if (profile.hasAnnotations && c == '@') {
                int end = i + 1;
                while (end < n && (Character.isJavaIdentifierPart(text.charAt(end)))) {
                    end++;
                }
                if (end > i + 1) {
                    tokens.add(new Token(TokenKind.ANNOTATION, text.substring(i, end), i, line, col));
                    col += end - i;
                    i = end;
                    continue;
                }
            }

            // 标识符 / 关键字
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end);
                TokenKind kind = profile.keywords.contains(word)
                        ? TokenKind.KEYWORD : TokenKind.IDENTIFIER;
                tokens.add(new Token(kind, word, i, line, col));
                col += end - i;
                i = end;
                continue;
            }

            // 运算符 vs 标点
            TokenKind kind = isOperator(c) ? TokenKind.OPERATOR : TokenKind.PUNCTUATION;
            tokens.add(new Token(kind, String.valueOf(c), i, line, col));
            col++;
            i++;
        }
        return tokens;
    }

    /**
     * 仅做空白感知的分词，适合行内高亮场景。
     *
     * @param text 单行文本，{@code null} 或空串时返回空列表
     * @return 有序 token 列表
     */
    public List<Token> tokenizeLine(String text) {
        return tokenize(text);
    }

    // ---- helpers ----

    private static boolean isStringQuote(char c, SyntaxProfile p) {
        for (char q : p.stringQuotes) {
            if (q == c) return true;
        }
        return false;
    }

    private static int lineEnd(String text, int from) {
        int n = text.length();
        for (int i = from; i < n; i++) {
            if (text.charAt(i) == '\n') return i;
        }
        return n;
    }

    private static int blockCommentEnd(String text, int from, String endMarker) {
        int idx = text.indexOf(endMarker, from);
        return idx < 0 ? text.length() : idx + endMarker.length();
    }

    /** 返回更新后的 [line, col]。 */
    private static int[] updateLineColResult(String text, int from, int to, int startLine, int startCol) {
        int line = startLine;
        int col = startCol;
        for (int i = from; i < to && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    private static void updateLineCol(String text, int from, int to, int[] lc) {
        int[] r = updateLineColResult(text, from, to, lc[0], lc[1]);
        lc[0] = r[0];
        lc[1] = r[1];
    }

    private static int literalEnd(String text, int start, char quote, boolean single) {
        int n = text.length();
        int i = start + 1; // skip opening quote
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2; // skip escaped char
                continue;
            }
            if (c == quote) return i + 1;
            if (!single && c == '\n') return i; // 非跨行字符串遇换行结束
            i++;
        }
        return n;
    }

    private static int numberEnd(String text, int start) {
        int n = text.length();
        int i = start;
        boolean dot = false;
        boolean exp = false;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == 'x' || c == 'X'
                    || c == 'e' || c == 'E' || c == 'p' || c == 'P') {
                if (c == '.') {
                    if (dot) break;
                    dot = true;
                } else if ((c == 'e' || c == 'E') && !exp && dot) {
                    exp = true;
                } else if ((c == 'e' || c == 'E') && !exp && i == start) {
                    break; // 单独的 e 不是数字的一部分
                } else if (c == 'x' || c == 'X') {
                    // 十六进制
                }
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                || c == '=' || c == '!' || c == '<' || c == '>' || c == '&'
                || c == '|' || c == '^' || c == '~' || c == '?';
    }
}

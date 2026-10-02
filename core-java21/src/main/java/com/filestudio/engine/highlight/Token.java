package com.filestudio.engine.highlight;

import java.util.Objects;

/**
 * 一个语法高亮 token：带类别的文本片段及其在源文本中的位置。
 *
 * <p>不可变值类型。{@code offset} 为字符偏移（非字节），
 * {@code line} 与 {@code column} 均从 0 开始，便于直接喂给编辑器坐标系统。
 *
 * @param kind   token 类别
 * @param text   token 文本内容
 * @param offset 在源文本中的起始字符偏移
 * @param line   起始行号，0-based
 * @param column 起始列号，0-based
 */
public record Token(TokenKind kind, String text, int offset, int line, int column) {

    public Token {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        if (offset < 0 || line < 0 || column < 0) {
            throw new IllegalArgumentException("offset/line/column must be non-negative");
        }
    }

    /** 结束偏移（不含）。 */
    public int end() {
        return offset + text.length();
    }

    public boolean isComment() {
        return kind == TokenKind.COMMENT;
    }

    public boolean isStringLiteral() {
        return kind == TokenKind.STRING || kind == TokenKind.CHAR;
    }

    public boolean isWhitespace() {
        return kind == TokenKind.TEXT && text.isBlank();
    }

    @Override
    public String toString() {
        return kind + "(" + line + ":" + column + ") " + escape(text);
    }

    private static String escape(String s) {
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }
}

package com.filestudio.engine.highlight;

/**
 * 词法分析产生的 token 类别，对应 UI 侧的语法高亮颜色映射。
 */
public enum TokenKind {
    /** 普通文本 / 空白。 */
    TEXT,
    /** 关键字。 */
    KEYWORD,
    /** 字符串字面量。 */
    STRING,
    /** 字符字面量（如 C/Java 的 'a'）。 */
    CHAR,
    /** 数值字面量。 */
    NUMBER,
    /** 注释。 */
    COMMENT,
    /** 预处理 / 指令（如 C 的 #include、PHP 的 &lt;?php）。 */
    PREPROCESSOR,
    /** 注解 / 装饰器（Java @Override、Python @staticmethod）。 */
    ANNOTATION,
    /** 普通标识符。 */
    IDENTIFIER,
    /** 运算符。 */
    OPERATOR,
    /** 标点符号。 */
    PUNCTUATION
}

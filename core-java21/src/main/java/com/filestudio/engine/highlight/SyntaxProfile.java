package com.filestudio.engine.highlight;

import java.util.List;
import java.util.Set;

/**
 * 一种语言的高亮规则描述。由 {@link SyntaxHighlighter} 消费。
 *
 * <p>规则按字段逐项匹配；字段为 {@code null} 或空集表示不启用该类别。
 * 关键字匹配区分大小写（多数语言的关键字是大小写敏感的）。
 *
 * <p>构造函数按固定顺序接收：id、mimeType、extensions、keywords、
 * lineComment、blockCommentStart、blockCommentEnd、stringQuotes、
 * hasAnnotations、preprocessorPrefix、hasCharLiterals。
 * 各语言的具体配置见 {@link SyntaxLanguageRegistry}。
 */
public final class SyntaxProfile {

    /** 语言标识（小写，如 {@code "java"}、{@code "python"}）。 */
    public final String id;
    /** MIME 类型。 */
    public final String mimeType;
    /** 该语言支持的扩展名（小写，不含点）。 */
    public final Set<String> extensions;
    /** 关键字集合。 */
    public final Set<String> keywords;
    /** 单行注释前缀（如 {@code "//"}、{@code "#"}）。可为空。 */
    public final String lineComment;
    /** 块注释起始标记（如 {@code "/*"}）。为空表示不支持。 */
    public final String blockCommentStart;
    /** 块注释结束标记。为空表示不支持。 */
    public final String blockCommentEnd;
    /** 字符串引号字符，如 {@code "\""}。多个引号按数组遍历。 */
    public final char[] stringQuotes;
    /** 是否把以 {@code @} 开头的标识符视为注解。 */
    public final boolean hasAnnotations;
    /** 预处理前缀（如 C 的 {@code "#"}）。空表示不启用。 */
    public final String preprocessorPrefix;
    /** 是否识别字符字面量（单引号）。 */
    public final boolean hasCharLiterals;

    /**
     * 构造语言配置。集合参数为 {@code null} 时按空集处理并做防御性拷贝。
     *
     * @param id                  语言 id
     * @param mimeType            对应 MIME 类型
     * @param extensions          关联扩展名集合
     * @param keywords            关键字集合
     * @param lineComment         单行注释前缀，无则传 {@code null}
     * @param blockCommentStart   块注释起始符，无则传 {@code null}
     * @param blockCommentEnd     块注释结束符，无则传 {@code null}
     * @param stringQuotes        字符串引号字符，无则传 {@code null}
     * @param hasAnnotations      是否识别注解 / 装饰器
     * @param preprocessorPrefix  预处理指令前缀，无则传 {@code null}
     * @param hasCharLiterals     是否识别单引号字符字面量
     */
    public SyntaxProfile(String id,
                         String mimeType,
                         Set<String> extensions,
                         Set<String> keywords,
                         String lineComment,
                         String blockCommentStart,
                         String blockCommentEnd,
                         char[] stringQuotes,
                         boolean hasAnnotations,
                         String preprocessorPrefix,
                         boolean hasCharLiterals) {
        this.id = id;
        this.mimeType = mimeType;
        this.extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
        this.keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        this.lineComment = lineComment;
        this.blockCommentStart = blockCommentStart;
        this.blockCommentEnd = blockCommentEnd;
        this.stringQuotes = stringQuotes == null ? new char[0] : stringQuotes.clone();
        this.hasAnnotations = hasAnnotations;
        this.preprocessorPrefix = preprocessorPrefix;
        this.hasCharLiterals = hasCharLiterals;
    }

    /**
     * 以数组形式返回扩展名。
     *
     * @return 扩展名数组副本
     */
    public String[] extensionsArray() {
        return extensions.toArray(String[]::new);
    }
}

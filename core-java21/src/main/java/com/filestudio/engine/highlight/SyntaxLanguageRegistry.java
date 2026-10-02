package com.filestudio.engine.highlight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内置语言注册表：按扩展名 / MIME / 语言 id 查找 {@link SyntaxProfile}。
 *
 * <p>覆盖 Phase 0 的常用语言，后续可扩展。查找全部失败时返回
 * {@link #PLAIN_TEXT} 作为兜底（纯文本仍可高亮，只是无关键字规则）。
 */
public final class SyntaxLanguageRegistry {

    /** 纯文本兜底配置。 */
    public static final SyntaxProfile PLAIN_TEXT = new SyntaxProfile(
            "plaintext", "text/plain", Set.of("txt", "log", "text", "sample"),
            Set.of(), null, null, null, new char[0], false, null, false);

    private static final Map<String, SyntaxProfile> BY_ID = new LinkedHashMap<>();
    private static final Map<String, SyntaxProfile> BY_EXT = new LinkedHashMap<>();

    static {
        register(java());
        register(python());
        register(javascript());
        register(typescript());
        register(c());
        register(cpp());
        register(go());
        register(rust());
        register(shell());
        register(sql());
        register(json());
        register(xml());
        register(css());
        register(html());
        register(markdown());
        register(yaml());
        register(PLAIN_TEXT);
    }

    private SyntaxLanguageRegistry() {}

    /**
     * 所有已注册配置（按注册顺序）。
     *
     * @return 不可变配置列表
     */
    public static List<SyntaxProfile> all() {
        return List.copyOf(BY_ID.values());
    }

    /**
     * 按语言 id 查找。
     *
     * @param id 语言 id，如 {@code "java"}
     * @return 对应配置；未注册时为 {@link Optional#empty()}
     */
    public static Optional<SyntaxProfile> findById(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * 按扩展名查找。
     *
     * @param extension 扩展名，可带前导点，忽略大小写
     * @return 对应配置；未匹配时返回纯文本兜底
     */
    public static SyntaxProfile byExtension(String extension) {
        String key = extension == null ? "" : extension.toLowerCase().strip();
        if (!key.isEmpty() && key.startsWith(".")) key = key.substring(1);
        SyntaxProfile p = BY_EXT.get(key);
        return p != null ? p : PLAIN_TEXT;
    }

    /**
     * 按 MIME 查找。
     *
     * @param mimeType MIME 类型，忽略大小写
     * @return 对应配置；未匹配或参数为 {@code null} 时返回纯文本兜底
     */
    public static SyntaxProfile byMime(String mimeType) {
        if (mimeType == null) return PLAIN_TEXT;
        for (SyntaxProfile p : BY_ID.values()) {
            if (p.mimeType.equalsIgnoreCase(mimeType)) return p;
        }
        return PLAIN_TEXT;
    }

    /**
     * 为指定路径创建高亮器。
     *
     * @param path 文件路径或文件名，据扩展名推断语言
     * @return 语法高亮器实例
     */
    public static SyntaxHighlighter forPath(String path) {
        String name = path == null ? "" : path;
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return new SyntaxHighlighter(byExtension(ext));
    }

    // ---- language definitions ----

    private static SyntaxProfile java() {
        return new SyntaxProfile("java", "text/x-java-source",
                Set.of("java"),
                Set.of(
                        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                        "char", "class", "const", "continue", "default", "do", "else",
                        "enum", "extends", "final", "finally", "float", "for", "goto", "if",
                        "implements", "import", "instanceof", "int", "interface", "long",
                        "native", "new", "package", "private", "protected", "public",
                        "record", "return", "sealed", "short", "static", "strictfp", "super",
                        "switch", "synchronized", "this", "throw", "throws", "transient",
                        "try", "var", "void", "volatile", "while", "yield",
                        "true", "false", "null"
                ),
                "//", "/*", "*/",
                new char[]{'"', '\''}, true, null, true);
    }

    private static SyntaxProfile python() {
        return new SyntaxProfile("python", "text/x-python",
                Set.of("py"),
                Set.of(
                        "and", "as", "assert", "async", "await", "break", "class", "continue",
                        "def", "del", "elif", "else", "except", "finally", "for", "from",
                        "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
                        "or", "pass", "raise", "return", "try", "while", "with", "yield",
                        "True", "False", "None"
                ),
                "#", null, null,
                new char[]{'"', '\''}, true, null, false);
    }

    private static SyntaxProfile javascript() {
        return new SyntaxProfile("javascript", "text/javascript",
                Set.of("js", "mjs", "cjs", "jsx"),
                Set.of(
                        "async", "await", "break", "case", "catch", "class", "const",
                        "continue", "debugger", "default", "delete", "do", "else", "enum",
                        "export", "extends", "false", "finally", "for", "function", "if",
                        "import", "in", "instanceof", "let", "new", "null", "of", "package",
                        "private", "protected", "public", "return", "static", "super",
                        "switch", "this", "throw", "true", "try", "typeof", "undefined",
                        "var", "void", "while", "with", "yield", "as", "from"
                ),
                "//", "/*", "*/",
                new char[]{'"', '\'', '`'}, true, null, false);
    }

    private static SyntaxProfile typescript() {
        return new SyntaxProfile("typescript", "text/typescript",
                Set.of("ts", "tsx", "cts", "mts"),
                Set.of(
                        "abstract", "as", "async", "await", "boolean", "break", "case",
                        "catch", "class", "const", "continue", "debugger", "declare",
                        "default", "delete", "do", "else", "enum", "export", "extends",
                        "false", "finally", "for", "from", "function", "implements",
                        "import", "in", "infer", "instanceof", "interface", "keyof", "let",
                        "namespace", "never", "new", "null", "number", "object", "of",
                        "package", "private", "protected", "public", "readonly", "return",
                        "satisfies", "string", "super", "switch", "symbol", "this", "throw",
                        "true", "try", "type", "typeof", "undefined", "unknown", "var",
                        "void", "while", "with", "yield"
                ),
                "//", "/*", "*/",
                new char[]{'"', '\'', '`'}, true, null, false);
    }

    private static SyntaxProfile c() {
        return new SyntaxProfile("c", "text/x-csrc",
                Set.of("c", "h"),
                Set.of(
                        "auto", "break", "case", "char", "const", "continue", "default", "do",
                        "double", "else", "enum", "extern", "float", "for", "goto", "if",
                        "inline", "int", "long", "register", "restrict", "return", "short",
                        "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
                        "unsigned", "void", "volatile", "while", "_Bool", "_Complex", "_Imaginary"
                ),
                "//", "/*", "*/",
                new char[]{'"', '\''}, false, "#", true);
    }

    private static SyntaxProfile cpp() {
        return new SyntaxProfile("cpp", "text/x-c++src",
                Set.of("cpp", "cc", "cxx", "hpp", "hh", "hxx"),
                Set.of(
                        "alignas", "alignof", "auto", "break", "case", "catch", "char",
                        "char8_t", "char16_t", "char32_t", "class", "const", "consteval",
                        "constexpr", "constinit", "const_cast", "continue", "co_await",
                        "co_return", "co_yield", "decltype", "default", "delete", "do",
                        "double", "dynamic_cast", "else", "enum", "explicit", "export",
                        "extern", "false", "float", "for", "friend", "goto", "if", "inline",
                        "int", "long", "mutable", "namespace", "new", "noexcept", "nullptr",
                        "operator", "override", "private", "protected", "public", "register",
                        "reinterpret_cast", "requires", "return", "short", "signed", "sizeof",
                        "static", "static_assert", "static_cast", "struct", "switch", "template",
                        "this", "thread_local", "throw", "true", "try", "typedef", "typeid",
                        "typename", "union", "unsigned", "using", "virtual", "void",
                        "volatile", "wchar_t", "while"
                ),
                "//", "/*", "*/",
                new char[]{'"', '\''}, false, "#", true);
    }

    private static SyntaxProfile go() {
        return new SyntaxProfile("go", "text/x-go",
                Set.of("go"),
                Set.of(
                        "break", "case", "chan", "const", "continue", "default", "defer",
                        "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
                        "interface", "map", "package", "range", "return", "select", "struct",
                        "switch", "type", "var", "true", "false", "nil", "iota"
                ),
                "//", "/*", "*/",
                new char[]{'"', '`'}, false, null, true);
    }

    private static SyntaxProfile rust() {
        return new SyntaxProfile("rust", "text/rust",
                Set.of("rs"),
                Set.of(
                        "as", "async", "await", "break", "const", "continue", "crate", "dyn",
                        "else", "enum", "fn", "for", "if", "impl", "in", "let", "loop", "match",
                        "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
                        "struct", "super", "trait", "true", "false", "type", "use", "where",
                        "while"
                ),
                "//", "/*", "*/",
                new char[]{'"', '\''}, true, "#", true);
    }

    private static SyntaxProfile shell() {
        return new SyntaxProfile("shell", "application/x-sh",
                Set.of("sh", "bash", "zsh", "ksh"),
                Set.of(
                        "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
                        "done", "case", "esac", "in", "function", "return", "exit", "select",
                        "time", "coproc"
                ),
                "#", null, null,
                new char[]{'"', '\''}, false, null, false);
    }

    private static SyntaxProfile sql() {
        return new SyntaxProfile("sql", "application/sql",
                Set.of("sql"),
                Set.of(
                        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE",
                        "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "INDEX", "VIEW",
                        "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "ON", "AS",
                        "AND", "OR", "NOT", "NULL", "ORDER", "BY", "GROUP", "HAVING",
                        "LIMIT", "OFFSET", "DISTINCT", "COUNT", "SUM", "AVG", "MIN", "MAX",
                        "CASE", "WHEN", "THEN", "ELSE", "END", "EXISTS", "IN", "LIKE",
                        "BETWEEN", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE",
                        "DEFAULT", "CASCADE", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "BEGIN"
                ),
                "--", "/*", "*/",
                new char[]{'"'}, false, null, false);
    }

    private static SyntaxProfile json() {
        return new SyntaxProfile("json", "application/json",
                Set.of("json"),
                Set.of("true", "false", "null"),
                null, null, null,
                new char[]{'"'}, false, null, false);
    }

    private static SyntaxProfile xml() {
        return new SyntaxProfile("xml", "application/xml",
                Set.of("xml", "xsd", "xsl", "plist"),
                Set.of(),
                null, "<!--", "-->",
                new char[]{'"', '\''}, false, null, false);
    }

    private static SyntaxProfile css() {
        return new SyntaxProfile("css", "text/css",
                Set.of("css"),
                Set.of("important", "inherit", "initial", "unset", "auto", "none"),
                null, "/*", "*/",
                new char[]{'"', '\''}, false, null, false);
    }

    private static SyntaxProfile html() {
        return new SyntaxProfile("html", "text/html",
                Set.of("html", "htm", "xhtml"),
                Set.of(),
                null, "<!--", "-->",
                new char[]{'"', '\''}, false, null, false);
    }

    private static SyntaxProfile markdown() {
        return new SyntaxProfile("markdown", "text/markdown",
                Set.of("md", "markdown"),
                Set.of(),
                null, null, null,
                new char[]{'"', '`'}, false, null, false);
    }

    private static SyntaxProfile yaml() {
        return new SyntaxProfile("yaml", "application/yaml",
                Set.of("yaml", "yml"),
                Set.of("true", "false", "null", "yes", "no", "on", "off", "~"),
                "#", null, null,
                new char[]{'"', '\''}, false, null, false);
    }

    private static void register(SyntaxProfile p) {
        BY_ID.put(p.id, p);
        for (String ext : p.extensions) {
            BY_EXT.put(ext.toLowerCase(), p);
        }
    }
}

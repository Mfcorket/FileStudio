package com.filestudio.desktop

/**
 * 主题模式。
 *
 * [SYSTEM] 表示跟随操作系统；桌面端 Compose 未提供可靠的系统外观探测，
 * 因此目前回落到浅色，等有稳定方案再接。
 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * 用户可自定义的配置。
 *
 * 所有字段都有默认值，因此配置文件缺字段、字段非法都不会导致启动失败——
 * 读取时逐项校验并回落到默认，而不是整体拒绝。
 */
data class Settings(
    val theme: ThemeMode = ThemeMode.LIGHT,
    val fontSizeSp: Int = 13,
    val showLineNumbers: Boolean = true,
    val syntaxHighlight: Boolean = true,
    val wrapLines: Boolean = false,
    val showDetailsPanel: Boolean = true,
    val tabWidth: Int = 4,
    val recentFiles: List<String> = emptyList(),
    val windowWidth: Int = 1280,
    val windowHeight: Int = 820,
) {
    /**
     * 把越界的取值收敛到合理区间。
     *
     * 配置文件是用户手写的，写错一个数字不应该让应用起不来。
     */
    fun sanitized(): Settings = copy(
        fontSizeSp = fontSizeSp.coerceIn(8, 48),
        tabWidth = tabWidth.coerceIn(1, 16),
        windowWidth = windowWidth.coerceIn(640, 10000),
        windowHeight = windowHeight.coerceIn(400, 10000),
        recentFiles = recentFiles.filter { it.isNotBlank() }.distinct().take(MAX_RECENT),
    )

    /** 记录一次打开过的文件：新的排最前，去重后截断。 */
    fun withRecentFile(path: String): Settings {
        if (path.isBlank()) return this
        return copy(recentFiles = (listOf(path) + recentFiles.filter { it != path })
            .take(MAX_RECENT))
    }

    companion object {
        /** 最近文件最多保留条数。 */
        const val MAX_RECENT = 12

        /** 默认配置。 */
        val DEFAULT = Settings()
    }
}

/**
 * 极简 JSON 编解码，只覆盖 [Settings] 用到的结构。
 *
 * 自己写而不是引入依赖，是因为这��配置结构固定（对象 + 基本类型 + 字符串数组），
 * 用完整 JSON 库反而增加了桌面端的体积与依赖面。
 *
 * 解析失败一律返回 {@code null}，由调用方回落到默认配置，
 * 保证「配置文件写坏了」只是丢设置，而不是打不开应用。
 */
object SettingsJson {

    /** 序列化为多行可读 JSON，便于用户手工编辑。 */
    fun encode(settings: Settings): String {
        val s = settings.sanitized()
        return buildString {
            appendLine("{")
            appendLine("  \"theme\": \"${s.theme}\",")
            appendLine("  \"fontSizeSp\": ${s.fontSizeSp},")
            appendLine("  \"showLineNumbers\": ${s.showLineNumbers},")
            appendLine("  \"syntaxHighlight\": ${s.syntaxHighlight},")
            appendLine("  \"wrapLines\": ${s.wrapLines},")
            appendLine("  \"showDetailsPanel\": ${s.showDetailsPanel},")
            appendLine("  \"tabWidth\": ${s.tabWidth},")
            appendLine("  \"windowWidth\": ${s.windowWidth},")
            appendLine("  \"windowHeight\": ${s.windowHeight},")
            appendLine("  \"recentFiles\": ${encodeStringArray(s.recentFiles)}")
            appendLine("}")
        }
    }

    /** 解析；任何异常或非法内容都返回 {@code null}。 */
    fun decode(text: String): Settings? = try {
        val map = JsonReader(text).parseObject() ?: return null
        Settings(
            theme = map.enum("theme", ThemeMode.LIGHT) { ThemeMode.valueOf(it.uppercase()) },
            fontSizeSp = map.int("fontSizeSp", 13),
            showLineNumbers = map.bool("showLineNumbers", true),
            syntaxHighlight = map.bool("syntaxHighlight", true),
            wrapLines = map.bool("wrapLines", false),
            showDetailsPanel = map.bool("showDetailsPanel", true),
            tabWidth = map.int("tabWidth", 4),
            recentFiles = map.stringList("recentFiles"),
            windowWidth = map.int("windowWidth", 1280),
            windowHeight = map.int("windowHeight", 820),
        ).sanitized()
    } catch (e: RuntimeException) {
        null
    }

    private fun encodeStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { encodeString(it) }

    private fun encodeString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

/**
 * 解析后的 JSON 对象，提供带默认值的取值方法。
 */
private class JsonObject(private val values: Map<String, Any?>) {

    fun int(key: String, default: Int): Int = (values[key] as? Number)?.toInt() ?: default

    fun bool(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    fun stringList(key: String): List<String> =
        (values[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    inline fun <reified T : Enum<T>> enum(key: String, default: T, parse: (String) -> T): T {
        val raw = values[key] as? String ?: return default
        return try {
            parse(raw)
        } catch (e: IllegalArgumentException) {
            default
        }
    }
}

/**
 * 迷你 JSON 读取器，支持对象、字符串、数字、布尔、null 与字符串数组。
 *
 * 只接受我们写出的那种子集；遇到不支持的语法直接抛异常，由上层回落到默认值。
 */
private class JsonReader(private val src: String) {
    private var pos = 0

    fun parseObject(): JsonObject? {
        skipWs()
        val map = parseValue() ?: return null
        return map as? JsonObject
    }

    private fun parseValue(): Any? {
        skipWs()
        if (pos >= src.length) return null
        return when (src[pos]) {
            '{' -> parseObj()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObj(): JsonObject {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos++
            return JsonObject(map)
        }
        while (true) {
            skipWs()
            val key = parseString() ?: throw IllegalArgumentException("bad key at $pos")
            skipWs()
            expect(':')
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonObject(map)
                }
                else -> throw IllegalArgumentException("expected , or } at $pos")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = mutableListOf<Any?>()
        skipWs()
        if (peek() == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> throw IllegalArgumentException("expected , or ] at $pos")
            }
        }
    }

    private fun parseString(): String? {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw IllegalArgumentException("bad escape")
                    when (val e = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("bad \\u")
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("bad escape \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun parseNumber(): Double {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
        if (start == pos) throw IllegalArgumentException("bad number at $pos")
        return src.substring(start, pos).toDoubleOrNull()
            ?: throw IllegalArgumentException("bad number at $start")
    }

    private fun <T> parseLiteral(literal: String, value: T): T {
        if (!src.startsWith(literal, pos)) throw IllegalArgumentException("bad literal at $pos")
        pos += literal.length
        return value
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else ' '

    private fun expect(c: Char) {
        if (peek() != c) throw IllegalArgumentException("expected $c at $pos")
        pos++
    }
}
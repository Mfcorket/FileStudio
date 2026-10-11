package com.filestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用户配置的序列化与持久化测试。
 *
 * 配置文件是用户手写的，任何一处解析出错都不允许导致应用打不开，
 * 所以「损坏输入必须安全回落到默认值」在这里被反复验证。
 */
class SettingsTest {

    private val dir = Files.createTempDirectory("fs-settings")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun store(name: String = "settings.json") = SettingsStore(dir.resolve(name))

    // ---- JSON 往返 ----

    @Test
    fun `round trip preserves every field`() {
        val original = Settings(
            theme = ThemeMode.DARK,
            fontSizeSp = 18,
            showLineNumbers = false,
            syntaxHighlight = false,
            wrapLines = true,
            showDetailsPanel = false,
            tabWidth = 8,
            recentFiles = listOf("C:/a.txt", "C:/b.txt"),
            windowWidth = 1600,
            windowHeight = 900,
        )

        val decoded = SettingsJson.decode(SettingsJson.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `default settings round trip`() {
        assertEquals(Settings.DEFAULT, SettingsJson.decode(SettingsJson.encode(Settings.DEFAULT)))
    }

    @Test
    fun `encoded json is human readable`() {
        val text = SettingsJson.encode(Settings.DEFAULT)
        assertTrue(text.contains("\"theme\": \"LIGHT\""))
        assertTrue(text.contains("\"fontSizeSp\": 13"))
        assertTrue(text.contains("\n"), "应输出多行以便手工编辑")
    }

    @Test
    fun `empty object yields all defaults`() {
        val decoded = SettingsJson.decode("{}")
        assertNotNull(decoded)
        assertEquals(Settings.DEFAULT, decoded)
    }

    @Test
    fun `missing fields fall back individually`() {
        val decoded = SettingsJson.decode("""{ "fontSizeSp": 20 }""")
        assertNotNull(decoded)
        assertEquals(20, decoded.fontSizeSp)
        assertEquals(Settings.DEFAULT.tabWidth, decoded.tabWidth)
        assertEquals(Settings.DEFAULT.theme, decoded.theme)
    }

    @Test
    fun `unknown fields are ignored`() {
        val decoded = SettingsJson.decode("""{ "fontSizeSp": 15, "futureOption": "x" }""")
        assertNotNull(decoded)
        assertEquals(15, decoded.fontSizeSp)
    }

    // ---- 容错 ----

    @Test
    fun `corrupt input returns null instead of throwing`() {
        val broken = listOf(
            "", "{", "}", "[]", "not json", "{\"a\":}", "{\"a\" \"b\"}",
            "{'a':1}", "{\"a\":1,}", "null",
        )
        for (text in broken) {
            assertNull(SettingsJson.decode(text), "以下内容应解析失败：$text")
        }
    }

    @Test
    fun `invalid enum falls back per field instead of discarding file`() {
        // 单个字段非法不应该连累其它字段——用户改错一个主题值不该丢掉全部设置
        val decoded = SettingsJson.decode("""{ "theme": "NEON", "fontSizeSp": 19 }""")
        assertNotNull(decoded)
        assertEquals(ThemeMode.LIGHT, decoded.theme)
        assertEquals(19, decoded.fontSizeSp)
    }

    @Test
    fun `out of range numbers are clamped`() {
        val decoded = SettingsJson.decode(
            """{ "fontSizeSp": 9999, "tabWidth": -5, "windowWidth": 1 }"""
        )
        assertNotNull(decoded)
        assertEquals(48, decoded.fontSizeSp)
        assertEquals(1, decoded.tabWidth)
        assertEquals(640, decoded.windowWidth)
    }

    @Test
    fun `wrong types fall back to defaults`() {
        val decoded = SettingsJson.decode(
            """{ "fontSizeSp": "big", "showLineNumbers": "yes", "recentFiles": 5 }"""
        )
        assertNotNull(decoded)
        assertEquals(Settings.DEFAULT.fontSizeSp, decoded.fontSizeSp)
        assertEquals(Settings.DEFAULT.showLineNumbers, decoded.showLineNumbers)
        assertTrue(decoded.recentFiles.isEmpty())
    }

    @Test
    fun `strings with quotes and newlines survive round trip`() {
        val original = Settings(
            recentFiles = listOf("""C:/a"b.txt""", "C:/line\nbreak.txt", "C:/back\\slash.txt")
        )
        val decoded = SettingsJson.decode(SettingsJson.encode(original))
        assertEquals(original.recentFiles, decoded?.recentFiles)
    }

    @Test
    fun `unicode is preserved`() {
        val original = Settings(recentFiles = listOf("C:/文档/说明.txt"))
        assertEquals(original.recentFiles, SettingsJson.decode(SettingsJson.encode(original))?.recentFiles)
    }

    // ---- 边界收敛 ----

    @Test
    fun `sanitized clamps and cleans`() {
        val s = Settings(
            fontSizeSp = 1,
            tabWidth = 999,
            windowWidth = 10,
            windowHeight = 10,
            recentFiles = listOf("a", "a", "", "  ", "b"),
        ).sanitized()

        assertEquals(8, s.fontSizeSp)
        assertEquals(16, s.tabWidth)
        assertEquals(640, s.windowWidth)
        assertEquals(400, s.windowHeight)
        assertEquals(listOf("a", "b"), s.recentFiles, "应去重并丢弃空白项")
    }

    @Test
    fun `recent files are capped and most recent first`() {
        var s = Settings.DEFAULT
        repeat(20) { i -> s = s.withRecentFile("file$i.txt") }

        assertEquals(Settings.MAX_RECENT, s.recentFiles.size)
        assertEquals("file19.txt", s.recentFiles.first())

        // 再次打开已存在的文件应移到最前而不是重复
        s = s.withRecentFile("file15.txt")
        assertEquals("file15.txt", s.recentFiles.first())
        assertEquals(s.recentFiles.distinct().size, s.recentFiles.size)
    }

    @Test
    fun `blank recent file is ignored`() {
        val s = Settings.DEFAULT.withRecentFile("  ")
        assertTrue(s.recentFiles.isEmpty())
    }

    // ---- 持久化 ----

    @Test
    fun `save then load round trips`() {
        val s = store()
        assertFalse(s.exists())

        val settings = Settings(theme = ThemeMode.DARK, fontSizeSp = 22, wrapLines = true)
        assertTrue(s.save(settings))

        assertTrue(s.exists())
        assertEquals(settings, s.load())
    }

    @Test
    fun `load returns defaults when file missing`() {
        assertEquals(Settings.DEFAULT, store("nope.json").load())
    }

    @Test
    fun `load returns defaults when file is corrupt`() {
        val s = store()
        Files.writeString(s.file, "{ this is not json")
        assertEquals(Settings.DEFAULT, s.load())
    }

    @Test
    fun `load returns defaults for empty file`() {
        val s = store()
        Files.writeString(s.file, "")
        assertEquals(Settings.DEFAULT, s.load())
    }

    @Test
    fun `save creates missing directories`() {
        val nested = SettingsStore(dir.resolve("a").resolve("b").resolve("settings.json"))
        assertTrue(nested.save(Settings.DEFAULT.copy(fontSizeSp = 16)))
        assertTrue(Files.isRegularFile(nested.file))
        assertEquals(16, nested.load().fontSizeSp)
    }

    @Test
    fun `save overwrites previous content`() {
        val s = store()
        s.save(Settings(fontSizeSp = 10))
        s.save(Settings(fontSizeSp = 20))
        assertEquals(20, s.load().fontSizeSp)
    }

    @Test
    fun `delete removes the file`() {
        val s = store()
        s.save(Settings.DEFAULT)
        assertTrue(s.delete())
        assertFalse(s.exists())
    }

    @Test
    fun `default path is absolute and ends with json`() {
        val p = SettingsStore.defaultPath()
        assertTrue(p.isAbsolute, "配置路径应为绝对路径：$p")
        assertEquals("settings.json", p.fileName.toString())
        assertTrue(p.toString().contains("FileStudio"))
    }

    @Test
    fun `save failure is reported not thrown`() {
        // 父路径是一个已存在的普通文件，createDirectories 必然失败
        val blocker = dir.resolve("blocker.txt")
        Files.writeString(blocker, "x")
        val bad = SettingsStore(blocker.resolve("settings.json"))

        assertFalse(bad.save(Settings.DEFAULT))
        assertEquals(Settings.DEFAULT, bad.load(), "写入失败时读取仍应给出默认值")
    }

    // ---- 与 AppState 的联动 ----

    @Test
    fun `app state persists view flags through settings`() {
        val s = store()
        val state = AppState(store = s)
        try {
            state.showLineNumbers = false
            state.syntaxHighlightEnabled = false
            state.showDetailsPanel = false

            assertFalse(s.load().showLineNumbers)
            assertFalse(s.load().syntaxHighlight)
            assertFalse(s.load().showDetailsPanel)

            // 重新构造应读回同样的配置
            val reloaded = AppState(store = s)
            assertFalse(reloaded.showLineNumbers)
            assertFalse(reloaded.syntaxHighlightEnabled)
            assertFalse(reloaded.showDetailsPanel)
            reloaded.shutdown()
        } finally {
            state.shutdown()
        }
    }

    @Test
    fun `opening a file records it in recent files`() {
        val s = store()
        val state = AppState(store = s)
        try {
            val f = dir.resolve("recent.txt")
            Files.writeString(f, "x")
            state.openFile(f.toFile())

            assertEquals(listOf(f.toAbsolutePath().toString()), state.recentFiles)
            assertEquals(state.recentFiles, s.load().recentFiles)

            state.clearRecentFiles()
            assertTrue(state.recentFiles.isEmpty())
        } finally {
            state.shutdown()
        }
    }

    @Test
    fun `reset restores defaults`() {
        val s = store()
        val state = AppState(store = s)
        try {
            state.updateSettings { it.copy(fontSizeSp = 30, theme = ThemeMode.DARK) }
            state.resetSettings()

            assertEquals(Settings.DEFAULT, state.settings)
            assertEquals(Settings.DEFAULT, s.load())
        } finally {
            state.shutdown()
        }
    }
}
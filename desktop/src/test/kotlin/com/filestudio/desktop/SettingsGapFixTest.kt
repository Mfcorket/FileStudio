package com.filestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 补三个之前「有配置但不生效」的缺口时的回归测试。
 */
class SettingsGapFixTest {

    private val dir = Files.createTempDirectory("fs-gaps")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    // ---- 窗口尺寸回写 ----

    @Test
    fun `update persists window size`() {
        val s = SettingsStore(dir.resolve("settings.json"))
        s.save(Settings.DEFAULT)

        val next = s.update { it.copy(windowWidth = 1600, windowHeight = 950) }

        assertEquals(1600, next.windowWidth)
        assertEquals(1600, s.load().windowWidth)
        assertEquals(950, s.load().windowHeight)
    }

    @Test
    fun `update keeps other fields`() {
        val s = SettingsStore(dir.resolve("settings.json"))
        s.save(Settings(fontSizeSp = 20, theme = ThemeMode.DARK))

        s.update { it.copy(windowWidth = 1000) }

        val loaded = s.load()
        assertEquals(20, loaded.fontSizeSp)
        assertEquals(ThemeMode.DARK, loaded.theme)
        assertEquals(1000, loaded.windowWidth)
    }

    @Test
    fun `update clamps absurd window sizes`() {
        val s = SettingsStore(dir.resolve("settings.json"))

        val next = s.update { it.copy(windowWidth = 99999, windowHeight = 1) }

        assertEquals(10000, next.windowWidth)
        assertEquals(400, next.windowHeight)
    }

    @Test
    fun `update works when no config exists yet`() {
        val s = SettingsStore(dir.resolve("fresh.json"))
        assertFalse(s.exists())

        s.update { it.copy(windowWidth = 1400) }

        assertTrue(s.exists())
        assertEquals(1400, s.load().windowWidth)
    }

    // ---- 自动换行配置确实被编辑器读取 ----

    @Test
    fun `word wrap setting is readable by the editor`() {
        val state = AppState()
        try {
            assertFalse(state.settings.wrapLines)

            state.updateSettings { it.copy(wrapLines = true) }
            assertTrue(state.settings.wrapLines)

            state.updateSettings { it.copy(wrapLines = false) }
            assertFalse(state.settings.wrapLines)
        } finally {
            state.shutdown()
        }
    }

    @Test
    fun `font size setting is readable by the editor`() {
        val state = AppState()
        try {
            assertEquals(13, state.settings.fontSizeSp)
            state.updateSettings { it.copy(fontSizeSp = 22) }
            assertEquals(22, state.settings.fontSizeSp)
        } finally {
            state.shutdown()
        }
    }

    // ---- 扩展名显示 ----

    @Test
    fun `metadata exposes the real file extension for source files`() {
        val state = AppState()
        try {
            val f = dir.resolve("Main.java")
            Files.writeString(f, "public class Main { }")
            state.openFile(f.toFile())

            val meta = state.activeMetadata()
            assertEquals("java", meta["fileExtension"],
                "详情面板需要真实后缀，而不是文本处理器归一化出的 txt")
        } finally {
            state.shutdown()
        }
    }

    @Test
    fun `metadata extension survives edits`() {
        val state = AppState()
        try {
            val f = dir.resolve("script.py")
            Files.writeString(f, "print(1)")
            state.openFile(f.toFile())
            state.replaceActiveContent("print(2)")

            assertEquals("py", state.activeMetadata()["fileExtension"])
        } finally {
            state.shutdown()
        }
    }

    @Test
    fun `window size round trips through disk`() {
        val s = SettingsStore(dir.resolve("rt.json"))
        s.save(Settings.DEFAULT.copy(windowWidth = 1234, windowHeight = 567))

        // 模拟重启：新建 store 再读
        val reopened = SettingsStore(s.file)
        val loaded = reopened.load()
        assertEquals(1234, loaded.windowWidth)
        assertEquals(567, loaded.windowHeight)
    }
}
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
 * [AppState] 的单元测试。
 *
 * 这里刻意不启动 Compose 窗口：应用状态本身不依赖任何 Compose 类型，
 * 因此可以在无图形环境的 CI 上完整跑通主链路。
 */
class AppStateTest {

    private val state = AppState()

    @AfterTest
    fun tearDown() {
        state.shutdown()
    }

    @Test
    fun `core registers handlers`() {
        assertTrue(state.handlers.isNotEmpty(), "核心应至少注册一个处理器")
        // 每个处理器都必须有非空扩展名，UI 直接用它做展示
        assertTrue(state.handlers.all { it.extension().isNotBlank() })
    }

    @Test
    fun `opening text file creates an editable tab`() {
        val file = writeTemp("notes.txt", "hello\nworld\n")

        assertTrue(state.openFile(file.toFile()), "打开失败：${state.error}")

        val tab = assertNotNull(state.activeTab)
        assertEquals("notes.txt", tab.fileName)
        assertFalse(tab.readOnly, "文本文件应为可编辑")
        assertEquals("hello\nworld\n", tab.content)
        assertEquals(1, state.tabs.size)
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun `opening same file twice activates existing tab`() {
        val file = writeTemp("dup.txt", "x")

        assertTrue(state.openFile(file.toFile()))
        assertTrue(state.openFile(file.toFile()))

        assertEquals(1, state.tabs.size, "重复打开不应新增标签页")
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun `multiple files create separate tabs`() {
        assertTrue(state.openFile(writeTemp("a.txt", "a").toFile()))
        assertTrue(state.openFile(writeTemp("b.txt", "b").toFile()))

        assertEquals(2, state.tabs.size)
        assertEquals(1, state.activeIndex, "新打开的标签页应成为当前标签页")
        assertEquals("b.txt", state.activeTab?.fileName)
    }

    @Test
    fun `binary formats open as read-only`() {
        // ZIP 魔数即可让核心识别，无需真实压缩包内容
        val file = Files.createTempFile("sample", ".zip")
        Files.write(file, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))

        assertTrue(state.openFile(file.toFile()))
        assertTrue(state.activeTab!!.readOnly, "二进制格式应为只读")
    }

    @Test
    fun `opening missing file reports error`() {
        val missing = Files.createTempDirectory("empty").resolve("nope.txt")

        assertFalse(state.openFile(missing.toFile()))
        assertNotNull(state.error, "打不开的文件应产生错误提示")
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun `editing marks tab as modified`() {
        state.openFile(writeTemp("edit.txt", "before").toFile())
        assertFalse(state.hasUnsavedChanges())

        assertTrue(state.replaceActiveContent("after"))

        assertTrue(state.activeTab!!.modified)
        assertTrue(state.hasUnsavedChanges())
        assertEquals("after", state.activeTab!!.content)
    }

    @Test
    fun `read-only tab rejects edits`() {
        val file = Files.createTempFile("ro", ".zip")
        Files.write(file, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        state.openFile(file.toFile())

        assertFalse(state.replaceActiveContent("hacked"))
        assertNotNull(state.error)
    }

    @Test
    fun `save clears the modified flag`() {
        val file = writeTemp("save.txt", "one")
        state.openFile(file.toFile())
        state.replaceActiveContent("two")

        assertTrue(state.saveActive())

        assertFalse(state.hasUnsavedChanges())
        assertEquals("two", Files.readString(file))
    }

    @Test
    fun `closing tab with unsaved changes is refused without force`() {
        state.openFile(writeTemp("dirty.txt", "x").toFile())
        state.replaceActiveContent("changed")

        assertFalse(state.closeTab(0, force = false))
        assertEquals(1, state.tabs.size)
        assertNotNull(state.error)

        assertTrue(state.closeTab(0, force = true))
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun `active index moves correctly after closing`() {
        state.openFile(writeTemp("one.txt", "1").toFile())
        state.openFile(writeTemp("two.txt", "2").toFile())
        state.openFile(writeTemp("three.txt", "3").toFile())
        assertEquals(2, state.activeIndex)

        // 关掉当前标签页后，应停在相邻的最后一个
        assertTrue(state.closeTab(2))
        assertEquals(1, state.activeIndex)
        assertEquals("two.txt", state.activeTab?.fileName)

        // 关掉前面的标签页后，下标应左移并保持指向同一个文件
        assertTrue(state.closeTab(0))
        assertEquals(0, state.activeIndex)
        assertEquals("two.txt", state.activeTab?.fileName)
    }

    @Test
    fun `setting active index validates range`() {
        assertFalse(state.setActive(0))

        state.openFile(writeTemp("one.txt", "1").toFile())
        assertTrue(state.setActive(0))
        assertFalse(state.setActive(5))
        assertFalse(state.setActive(-1))
    }

    @Test
    fun `closing last tab leaves no active tab`() {
        state.openFile(writeTemp("one.txt", "1").toFile())

        assertTrue(state.closeTab(0))

        assertTrue(state.tabs.isEmpty())
        assertEquals(-1, state.activeIndex)
        assertNull(state.activeTab)
    }

    @Test
    fun `undo restores previous content`() {
        state.openFile(writeTemp("undo.txt", "one").toFile())
        state.replaceActiveContent("two")

        assertTrue(state.undo())
        assertEquals("one", state.activeTab!!.content)
    }

    @Test
    fun `metadata and stats available for active tab`() {
        state.openFile(writeTemp("meta.json", "{\"a\":1,\"b\":2}").toFile())

        val meta = state.activeMetadata()
        // JSON 处理器提供的是结构信息而非文件名信息
        assertTrue(meta.containsKey("rootType"), "JSON 应提供 rootType，实际=$meta")
        assertEquals(true, meta["valid"])
        assertNotNull(state.activeStats())
        assertTrue(state.activeStats()!!.contains("行"))
    }

    @Test
    fun `no active tab yields empty details`() {
        assertNull(state.activeStats())
        assertTrue(state.activeMetadata().isEmpty())
    }

    @Test
    fun `closeAll clears every tab`() {
        state.openFile(writeTemp("a.txt", "a").toFile())
        state.openFile(writeTemp("b.txt", "b").toFile())

        assertTrue(state.closeAll(force = true))

        assertTrue(state.tabs.isEmpty())
        assertNull(state.activeTab)
    }

    @Test
    fun `open path argument opens a file`() {
        val file = writeTemp("arg.txt", "from-arg")

        assertTrue(state.openPathArg(file.toAbsolutePath().toString()))
        assertEquals("arg.txt", state.activeTab?.fileName)
    }

private fun writeTemp(name: String, content: String): Path {
    val dir = Files.createTempDirectory("fs-test")
    dir.toFile().deleteOnExit()
    val file = dir.resolve(name)
    Files.writeString(file, content)
    file.toFile().deleteOnExit()
    return file
}
}
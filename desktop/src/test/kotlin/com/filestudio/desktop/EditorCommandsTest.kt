package com.filestudio.desktop

import com.filestudio.engine.SearchOptions
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
 * 编辑器命令的单元测试：搜索替换、语法高亮、Diff、书签。
 *
 * 这些能力都在 core 里实现过，这里验证的是「接线是否正确」——
 * 比如替换后匹配数是否重算、Diff 方向是否正确、光标行是否收敛。
 */
class EditorCommandsTest {

    private val state = AppState()

    @AfterTest
    fun tearDown() {
        state.shutdown()
    }

    // ---- 搜索 ----

    @Test
    fun `search finds all occurrences`() {
        // "the cat sat on the mat" 中只有两处 "the"，注意 sat/mat 并不含
        state.openFile(write("s.txt", "the cat sat on the mat").toFile())

        state.setSearchQuery("the")

        assertEquals(2, state.matches.size)
        assertEquals(0, state.currentMatchIndex)
        assertNotNull(state.currentMatch())
        assertEquals("the", state.currentMatch()!!.text())
    }

    @Test
    fun `search counts overlapping-free repeats`() {
        state.openFile(write("rep3.txt", "x x x").toFile())
        state.setSearchQuery("x")
        assertEquals(3, state.matches.size)
    }

    @Test
    fun `empty query clears matches`() {
        state.openFile(write("s2.txt", "hello hello").toFile())

        state.setSearchQuery("hello")
        assertEquals(2, state.matches.size)

        state.setSearchQuery("")
        assertTrue(state.matches.isEmpty())
        assertEquals(-1, state.currentMatchIndex)
    }

    @Test
    fun `case sensitivity is honoured`() {
        state.openFile(write("case.txt", "Cat cat CAT").toFile())

        state.setSearchOptions(SearchOptions(true, false, false, true))
        state.setSearchQuery("cat")

        assertEquals(1, state.matches.size, "区分大小写时只应匹配一个")

        state.setSearchOptions(SearchOptions(false, false, false, true))
        assertEquals(3, state.matches.size, "不区分大小写时应匹配三个")
    }

    @Test
    fun `regex search works`() {
        state.openFile(write("re.txt", "a1 b22 c333").toFile())

        state.setSearchOptions(SearchOptions(true, false, true, true))
        state.setSearchQuery("[a-z]\\d+")

        assertEquals(3, state.matches.size)
    }

    @Test
    fun `invalid regex does not crash`() {
        state.openFile(write("bad.txt", "content").toFile())

        state.setSearchOptions(SearchOptions(true, false, true, true))
        state.setSearchQuery("[unclosed")

        assertTrue(state.matches.isEmpty())
    }

    @Test
    fun `next and previous match wrap around`() {
        state.openFile(write("wrap.txt", "x x x").toFile())
        state.setSearchQuery("x")
        assertEquals(3, state.matches.size)

        assertTrue(state.selectNextMatch())
        assertEquals(1, state.currentMatchIndex)
        assertTrue(state.selectNextMatch())
        assertEquals(2, state.currentMatchIndex)
        // 到末尾后回到开头
        assertTrue(state.selectNextMatch())
        assertEquals(0, state.currentMatchIndex)
        // 到开头前跳到末尾
        assertTrue(state.selectPreviousMatch())
        assertEquals(2, state.currentMatchIndex)
    }

    // ---- 替换 ----

    @Test
    fun `replace all updates content and match count`() {
        state.openFile(write("rep.txt", "a a a").toFile())
        state.setSearchQuery("a")
        state.setSearchReplacement("bb")

        assertEquals(3, state.replaceAllMatches())

        assertEquals("bb bb bb", state.activeTab!!.content)
        assertTrue(state.matches.isEmpty(), "替换后不应再有旧匹配")
        assertTrue(state.activeTab!!.modified)
    }

    @Test
    fun `replace current only affects selected match`() {
        state.openFile(write("one.txt", "keep keep").toFile())
        state.setSearchQuery("keep")
        state.setSearchReplacement("X")

        assertTrue(state.replaceCurrentMatch())

        assertEquals("X keep", state.activeTab!!.content)
        assertEquals(1, state.matches.size)
    }

    @Test
    fun `replace respects regex and case options`() {
        state.openFile(write("rr.txt", "Cat cat").toFile())
        state.setSearchOptions(SearchOptions(true, false, false, true))
        state.setSearchQuery("cat")
        state.setSearchReplacement("dog")

        assertEquals(1, state.replaceAllMatches())
        assertEquals("Cat dog", state.activeTab!!.content)
    }

    @Test
    fun `replace on read-only tab is refused`() {
        val zip = Files.createTempFile("ro", ".zip")
        Files.write(zip, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        state.openFile(zip.toFile())
        state.setSearchQuery("anything")

        assertEquals(0, state.replaceAllMatches())
        assertNotNull(state.error)
    }

    @Test
    fun `clear search resets everything`() {
        state.openFile(write("clr.txt", "a a").toFile())
        state.setSearchQuery("a")
        state.setSearchReplacement("b")

        state.clearSearch()

        assertEquals("", state.searchQuery)
        assertEquals("", state.searchReplacement)
        assertTrue(state.matches.isEmpty())
    }

    // ---- 语法高亮 ----

    @Test
    fun `tokens produced for known language`() {
        state.openFile(write("Main.java", "public class Main { }").toFile())
        val tab = state.activeTab
        assertNotNull(tab, "文件应已打开")

        val tokens = state.highlightedTokens()

        assertTrue(
            tokens.isNotEmpty(),
            "Java 应能产生高亮 token；path=${tab.path} enabled=${state.syntaxHighlightEnabled} " +
                "lang=${state.syntaxLanguageName()}",
        )
        assertTrue(tokens.any { it.text().contains("public") })
    }

    @Test
    fun `unknown extension falls back to plain text profile`() {
        state.openFile(write("data.unknownext", "plain text here").toFile())

        // 未注册的扩展名会落到 plaintext 兜底配置，而不是「没有配置」
        assertEquals("plaintext", state.syntaxLanguageName())
        val tokens = state.highlightedTokens()
        assertTrue(tokens.none { it.kind() == com.filestudio.engine.highlight.TokenKind.KEYWORD },
            "纯文本不应产生关键字高亮，实际=${tokens.map { it.kind() }}")
    }

    @Test
    fun `disabling highlight returns no tokens`() {
        state.openFile(write("Off.java", "int x = 1;").toFile())

        state.syntaxHighlightEnabled = false
        assertTrue(state.highlightedTokens().isEmpty())

        state.syntaxHighlightEnabled = true
        assertTrue(state.highlightedTokens().isNotEmpty())
    }

    @Test
    fun `highlight reflects edited content`() {
        state.openFile(write("Live.java", "int a = 1;").toFile())
        state.replaceActiveContent("class Foo {}")

        val text = state.highlightedTokens().joinToString("") { it.text() }
        assertTrue(text.contains("class"))
    }

    // ---- Diff ----

    @Test
    fun `diff against disk detects unsaved edits`() {
        val file = write("d.txt", "line1\nline2\n")
        state.openFile(file.toFile())
        state.replaceActiveContent("line1\nline2 changed\nline3\n")

        val result = state.diffWithDisk()

        assertNotNull(result)
        assertTrue(result.addedCount() > 0)
        assertTrue(result.removedCount() > 0)
        assertTrue(result.unchangedCount() > 0)
    }

    @Test
    fun `diff is empty when nothing changed`() {
        val file = write("same.txt", "identical\n")
        state.openFile(file.toFile())

        val result = state.diffWithDisk()

        assertNotNull(result)
        assertEquals(0, result.addedCount())
        assertEquals(0, result.removedCount())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `diff against arbitrary text`() {
        state.openFile(write("any.txt", "second").toFile())

        val result = state.diffWith("first")

        assertNotNull(result)
        assertEquals(1, result.addedCount())
        assertEquals(1, result.removedCount())
    }

    @Test
    fun `diff with missing disk file reports error`() {
        val file = write("gone.txt", "x")
        state.openFile(file.toFile())
        Files.delete(file)

        assertNull(state.diffWithDisk())
        assertNotNull(state.error)
    }

    // ---- 书签 ----

    @Test
    fun `bookmark toggles on and off`() {
        state.openFile(write("bm.txt", "one\ntwo\nthree\n").toFile())

        assertTrue(state.toggleBookmark(1, "第二行"))
        assertTrue(state.hasBookmark(1))
        assertEquals(1, state.bookmarks().size)

        assertFalse(state.toggleBookmark(1))
        assertFalse(state.hasBookmark(1))
        assertTrue(state.bookmarks().isEmpty())
    }

    @Test
    fun `bookmarks are per tab`() {
        state.openFile(write("t1.txt", "a").toFile())   // 标签 0
        state.openFile(write("t2.txt", "b").toFile())   // 标签 1，当前激活

        // 在当前（t2）标签上打书签
        state.toggleBookmark(0, "在 t2")
        assertTrue(state.hasBookmark(0))

        // 切到 t1，不应看到 t2 的书签
        state.setActive(0)
        assertFalse(state.hasBookmark(0), "另一个标签页不应看到该书签")
        assertTrue(state.bookmarks().isEmpty())

        // 切回 t2，书签仍在
        state.setActive(1)
        assertTrue(state.hasBookmark(0))
        assertEquals(1, state.bookmarks().size)
    }

    @Test
    fun `no active tab yields no bookmarks`() {
        assertTrue(state.bookmarks().isEmpty())
        assertFalse(state.hasBookmark(0))
    }

    // ---- 光标 ----

    @Test
    fun `cursor line is clamped to document`() {
        state.openFile(write("cur.txt", "a\nb\nc").toFile())

        state.moveCursorToLine(99)
        assertEquals(2, state.cursorLine, "应收敛到最后一行")

        state.moveCursorToLine(-5)
        assertEquals(0, state.cursorLine)
    }

    @Test
    fun `offset maps to line`() {
        state.openFile(write("off.txt", "aaa\nbbb\nccc").toFile())

        state.moveCursorToOffset(5)
        assertEquals(1, state.cursorLine, "偏移 5 落在第二行")

        state.moveCursorToOffset(0)
        assertEquals(0, state.cursorLine)
    }

    // ---- 面板开关 ----

    @Test
    fun `view toggles flip state`() {
        val before = state.showDetailsPanel

        state.showSearchPanel = true
        assertTrue(state.showSearchPanel)
        state.showSearchPanel = false
        assertFalse(state.showSearchPanel)

        state.showLineNumbers = false
        assertFalse(state.showLineNumbers)

        state.toggleDiffPanel()
        assertTrue(state.showDiffPanel)
        state.toggleDiffPanel()
        assertFalse(state.showDiffPanel)

        assertEquals(before, state.showDetailsPanel)
    }

    private fun write(name: String, content: String): Path {
        val dir = Files.createTempDirectory("fs-cmd")
        dir.toFile().deleteOnExit()
        val file = dir.resolve(name)
        Files.writeString(file, content)
        file.toFile().deleteOnExit()
        return file
    }
}
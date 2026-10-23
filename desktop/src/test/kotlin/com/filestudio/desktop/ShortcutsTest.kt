package com.filestudio.desktop

import androidx.compose.ui.input.key.Key
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
 * 快捷键绑定表与分发器的单元测试。
 *
 * 这里的价值在于：绑定表一旦出现「两个动作抢同一个键」，或者某个动作
 * 的绑定在分发时被写反，都能在不开窗口的情况下被发现。
 */
class ShortcutsTest {

    private val state = AppState()
    private var quitCalled = false
    private val dispatcher = ShortcutDispatcher(state) { quitCalled = true }

    @AfterTest
    fun tearDown() {
        state.shutdown()
    }

    // ---- 绑定表完整性 ----

    @Test
    fun `no shortcut is bound to two different actions`() {
        // reverse 的构建会检测冲突，这里断言它确实构造成功
        assertNotNull(Keymap.reverse)
        assertEquals(Keymap.bindings.size, Keymap.reverse.size)
    }

    @Test
    fun `every binding resolves back to its action`() {
        for (b in Keymap.bindings) {
            assertEquals(b.action, Keymap.actionFor(b.shortcut),
                "组合 ${b.shortcut.label()} 反查结果不一致")
        }
    }

    @Test
    fun `unbound combinations are not handled`() {
        assertNull(Keymap.actionFor(Shortcut(Key.F9, ctrl = true, alt = true)))
        val r = dispatcher.handle(Key.F9, ctrl = true, alt = true)
        assertFalse(r.handled, "未绑定的组合不应被消费，否则会吞掉正常按键")
    }

    @Test
    fun `modifier keys are distinguished`() {
        val plain = Shortcut(Key.S)
        val ctrl = Shortcut(Key.S, ctrl = true)
        val ctrlShift = Shortcut(Key.S, ctrl = true, shift = true)

        assertNull(Keymap.actionFor(plain))
        assertEquals(ShortcutAction.SAVE, Keymap.actionFor(ctrl))
        assertNull(Keymap.actionFor(ctrlShift))
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("Ctrl+S", Shortcut(Key.S, ctrl = true).label())
        assertEquals("Ctrl+Shift+Z", Shortcut(Key.Z, ctrl = true, shift = true).label())
        assertEquals("Shift+F3", Shortcut(Key.F3, shift = true).label())
        assertEquals("Ctrl+Alt+Enter", Shortcut(Key.Enter, ctrl = true, alt = true).label())
    }

    @Test
    fun `undo supports both z and y`() {
        assertEquals(ShortcutAction.UNDO, Keymap.actionFor(Shortcut(Key.Z, ctrl = true)))
        assertEquals(ShortcutAction.UNDO, Keymap.actionFor(Shortcut(Key.Y, ctrl = true)))
        assertEquals("Ctrl+Z / Ctrl+Y", Keymap.allLabels(ShortcutAction.UNDO))
    }

    @Test
    fun `all actions have at least one binding`() {
        for (action in ShortcutAction.entries) {
            assertTrue(Keymap.bindings.any { it.action == action },
                "动作 ${action.name} 没有绑定任何快捷键")
        }
    }

    @Test
    fun `grouped covers every action exactly once`() {
        val grouped = Keymap.grouped()
        val covered = grouped.flatMap { it.second }.map { it.first }.toSet()
        assertEquals(ShortcutAction.entries.toSet(), covered)
    }

    // ---- 分发行为 ----

    @Test
    fun `ctrl-s saves the active document`() {
        val file = write("s.txt", "one")
        state.openFile(file.toFile())
        state.replaceActiveContent("two")

        assertTrue(dispatcher.handle(Key.S, ctrl = true).handled)

        assertEquals("two", Files.readString(file))
        assertFalse(state.hasUnsavedChanges())
    }

    @Test
    fun `ctrl-z undoes and ctrl-shift-z redoes`() {
        state.openFile(write("u.txt", "one").toFile())
        state.replaceActiveContent("two")

        dispatcher.handle(Key.Z, ctrl = true)
        assertEquals("one", state.activeTab!!.content)

        dispatcher.handle(Key.Z, ctrl = true, shift = true)
        assertEquals("two", state.activeTab!!.content)

        dispatcher.handle(Key.Y, ctrl = true)
        assertEquals("one", state.activeTab!!.content)
    }

    @Test
    fun `ctrl-f opens search panel and requests query focus`() {
        dispatcher.handle(Key.F, ctrl = true)

        assertTrue(state.showSearchPanel)
        assertEquals(SearchFocusTarget.QUERY, state.pendingFocus)
    }

    @Test
    fun `ctrl-h focuses replacement field`() {
        dispatcher.handle(Key.H, ctrl = true)

        assertTrue(state.showSearchPanel)
        assertEquals(SearchFocusTarget.REPLACEMENT, state.pendingFocus)
    }

    @Test
    fun `f3 and shift-f3 move between matches`() {
        state.openFile(write("m.txt", "a a a").toFile())
        state.updateSearchQuery("a")
        assertEquals(3, state.matches.size)

        dispatcher.handle(Key.F3)
        assertEquals(1, state.currentMatchIndex)

        dispatcher.handle(Key.F3, shift = true)
        assertEquals(0, state.currentMatchIndex)
    }

    @Test
    fun `ctrl-alt-enter replaces all`() {
        state.openFile(write("r.txt", "x x x").toFile())
        state.updateSearchQuery("x")
        state.updateSearchReplacement("y")

        dispatcher.handle(Key.Enter, ctrl = true, alt = true)

        assertEquals("y y y", state.activeTab!!.content)
    }

    @Test
    fun `ctrl-tab cycles tabs`() {
        state.openFile(write("t1.txt", "1").toFile())
        state.openFile(write("t2.txt", "2").toFile())
        assertEquals(1, state.activeIndex)

        dispatcher.handle(Key.Tab, ctrl = true)
        assertEquals(0, state.activeIndex, "应回到第一个标签")

        dispatcher.handle(Key.Tab, ctrl = true, shift = true)
        assertEquals(1, state.activeIndex)
    }

    @Test
    fun `f2 toggles bookmark at cursor`() {
        state.openFile(write("b.txt", "l1\nl2\nl3").toFile())
        state.moveCursorToLine(1)

        dispatcher.handle(Key.F2)
        assertTrue(state.hasBookmark(1))

        dispatcher.handle(Key.F2)
        assertFalse(state.hasBookmark(1))
    }

    @Test
    fun `view shortcuts flip flags`() {
        val syntax = state.syntaxHighlightEnabled
        dispatcher.handle(Key.K, ctrl = true, shift = true)
        assertEquals(!syntax, state.syntaxHighlightEnabled)

        val numbers = state.showLineNumbers
        dispatcher.handle(Key.L, ctrl = true, shift = true)
        assertEquals(!numbers, state.showLineNumbers)

        val details = state.showDetailsPanel
        dispatcher.handle(Key.D, ctrl = true, shift = true)
        assertEquals(!details, state.showDetailsPanel)

        assertFalse(state.showSearchPanel)
        dispatcher.handle(Key.P, ctrl = true, shift = true)
        assertTrue(state.showSearchPanel)

        dispatcher.handle(Key.I, ctrl = true, shift = true)
        assertTrue(state.showDiffPanel)
    }

    @Test
    fun `f1 toggles help`() {
        assertFalse(dispatcher.showHelp)
        dispatcher.handle(Key.F1)
        assertTrue(dispatcher.showHelp)
        dispatcher.handle(Key.F1)
        assertFalse(dispatcher.showHelp)
    }

    @Test
    fun `ctrl-o opens the open dialog`() {
        dispatcher.handle(Key.O, ctrl = true)
        assertTrue(state.showOpenDialog)
    }

    @Test
    fun `ctrl-q quits`() {
        dispatcher.handle(Key.Q, ctrl = true)
        assertTrue(quitCalled)
    }

    @Test
    fun `ctrl-w closes tab but refuses unsaved changes`() {
        state.openFile(write("c.txt", "x").toFile())
        state.replaceActiveContent("y")

        dispatcher.handle(Key.W, ctrl = true)
        assertEquals(1, state.tabs.size, "有未保存修改时不应关闭")
        assertNotNull(state.error)
    }

    @Test
    fun `shortcuts on empty state do not crash`() {
        // 没有打开任何文件时，大部分快捷键都应安全地什么都不做
        for (action in ShortcutAction.entries) {
            dispatcher.dispatch(action)
        }
        assertTrue(state.tabs.isEmpty())
    }

    private fun write(name: String, content: String): Path {
        val dir = Files.createTempDirectory("fs-keys")
        dir.toFile().deleteOnExit()
        val file = dir.resolve(name)
        Files.writeString(file, content)
        file.toFile().deleteOnExit()
        return file
    }
}
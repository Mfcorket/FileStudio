package com.filestudio.desktop

import androidx.compose.ui.input.key.Key

/**
 * 快捷键执行结果。
 *
 * [handled] 为 true 时事件被消费，不再向下传给输入框——
 * 否则 Ctrl+F 之类的组合会在文本框里插入字符或触发 IME 行为。
 */
data class ShortcutResult(val handled: Boolean) {
    companion object {
        /** 未处理，继续传递。 */
        val NotHandled = ShortcutResult(false)

        /** 已处理。 */
        val Handled = ShortcutResult(true)
    }
}

/**
 * 把快捷键动作落到 [AppState] 上。
 *
 * 与 UI 完全解耦：既由键盘处理器调用，也可以在测试里直接调用，
 * 因此「按了 Ctrl+S 会不会真的保存」这类问题不需要开窗口就能验证。
 */
class ShortcutDispatcher(
    /** 状态对象；开放给对话框使用。 */
    val state: AppState,
    private val onQuit: () -> Unit,
) {

    /** 请求打开「打开文件」对话框。 */
    fun requestOpenDialog() {
        state.showOpenDialog = true
    }

    fun dispatch(action: ShortcutAction): ShortcutResult = when (action) {
        ShortcutAction.OPEN -> {
            requestOpenDialog()
            ShortcutResult.Handled
        }

        ShortcutAction.SAVE -> {
            state.saveActive()
            ShortcutResult.Handled
        }

        ShortcutAction.CLOSE_TAB -> {
            val idx = state.activeIndex
            if (idx >= 0) state.closeTab(idx)
            ShortcutResult.Handled
        }

        ShortcutAction.CLOSE_ALL -> {
            state.closeAll(force = false)
            ShortcutResult.Handled
        }

        ShortcutAction.QUIT -> {
            onQuit()
            ShortcutResult.Handled
        }

        ShortcutAction.UNDO -> {
            state.undo()
            ShortcutResult.Handled
        }

        ShortcutAction.REDO -> {
            state.redo()
            ShortcutResult.Handled
        }

        ShortcutAction.FIND -> {
            state.showSearchPanel = true
            state.pendingFocus = SearchFocusTarget.QUERY
            ShortcutResult.Handled
        }

        ShortcutAction.FIND_REPLACE -> {
            state.showSearchPanel = true
            state.pendingFocus = SearchFocusTarget.REPLACEMENT
            ShortcutResult.Handled
        }

        ShortcutAction.FIND_NEXT -> {
            state.selectNextMatch()
            ShortcutResult.Handled
        }

        ShortcutAction.FIND_PREVIOUS -> {
            state.selectPreviousMatch()
            ShortcutResult.Handled
        }

        ShortcutAction.REPLACE_ALL -> {
            state.replaceAllMatches()
            ShortcutResult.Handled
        }

        ShortcutAction.NEXT_TAB -> {
            if (state.tabs.isNotEmpty()) {
                state.setActive((state.activeIndex + 1) % state.tabs.size)
            }
            ShortcutResult.Handled
        }

        ShortcutAction.PREVIOUS_TAB -> {
            if (state.tabs.isNotEmpty()) {
                val prev = if (state.activeIndex <= 0) {
                    state.tabs.size - 1
                } else {
                    state.activeIndex - 1
                }
                state.setActive(prev)
            }
            ShortcutResult.Handled
        }

        ShortcutAction.TOGGLE_BOOKMARK -> {
            state.toggleBookmark(state.cursorLine, "标记")
            ShortcutResult.Handled
        }

        ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT -> {
            state.syntaxHighlightEnabled = !state.syntaxHighlightEnabled
            ShortcutResult.Handled
        }

        ShortcutAction.TOGGLE_LINE_NUMBERS -> {
            state.showLineNumbers = !state.showLineNumbers
            ShortcutResult.Handled
        }

        ShortcutAction.TOGGLE_DETAILS_PANEL -> {
            state.showDetailsPanel = !state.showDetailsPanel
            ShortcutResult.Handled
        }

        ShortcutAction.TOGGLE_SEARCH_PANEL -> {
            state.showSearchPanel = !state.showSearchPanel
            ShortcutResult.Handled
        }

        ShortcutAction.TOGGLE_DIFF_PANEL -> {
            state.toggleDiffPanel()
            ShortcutResult.Handled
        }

        ShortcutAction.SHOW_HELP -> {
            showHelp = !showHelp
            ShortcutResult.Handled
        }
    }

    /** 是否显示快捷键帮助面板。 */
    var showHelp: Boolean = false

    /** 根据按键事件解析动作并执行。 */
    fun handle(shortcut: Shortcut): ShortcutResult {
        val action = Keymap.actionFor(shortcut) ?: return ShortcutResult.NotHandled
        return dispatch(action)
    }

    /** 便捷入口：直接把「按键 + 修饰键」交给分发器。 */
    fun handle(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        handle(Shortcut(key, ctrl = ctrl, shift = shift, alt = alt))
}
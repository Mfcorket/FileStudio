package com.filestudio.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.filestudio.desktop.Keymap
import com.filestudio.desktop.Shortcut
import com.filestudio.desktop.ShortcutAction
import com.filestudio.desktop.ShortcutDispatcher

/**
 * 全局快捷键入口。
 *
 * 这里只做一件事：把 Compose 按键事件转成 [Shortcut] 交给分发器。
 * 组合→动作的查表在 [Keymap]，动作→状态的改动在 [ShortcutDispatcher]，
 * 两者都能脱离窗口单测。
 */
@Composable
fun ShortcutHandler(
    dispatcher: ShortcutDispatcher,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                return@onPreviewKeyEvent false
            }
            dispatcher.handle(
                Shortcut(
                    key = event.key,
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    meta = event.isMetaPressed,
                )
            ).handled
        }
    ) {
        ShortcutHelpDialog(dispatcher)
        OpenFileDialog(dispatcher)
        content()
    }
}

/**
 * 快捷键帮助面板（F1）。
 */
@Composable
fun ShortcutHelpDialog(dispatcher: ShortcutDispatcher) {
    if (!dispatcher.showHelp) return

    AlertDialog(
        onDismissRequest = { dispatcher.showHelp = false },
        title = { Text("键盘快捷键") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Keymap.grouped().forEach { (category, entries) ->
                    Text(
                        category.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    entries.forEach { (action, labels) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                labels,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(end = 14.dp),
                            )
                            Text(
                                actionLabel(action),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dispatcher.showHelp = false }) { Text("关闭 (F1)") }
        },
    )
}

/**
 * 「打开文件」对话框（Ctrl+O）。
 */
@Composable
private fun OpenFileDialog(dispatcher: ShortcutDispatcher) {
    val state = dispatcher.state
    if (!state.showOpenDialog) return

    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { state.showOpenDialog = false },
        title = { Text("打开文件") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("文件路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = path.isNotBlank(),
                onClick = {
                    state.openPathArg(path.trim())
                    path = ""
                    state.showOpenDialog = false
                },
            ) {
                Text("打开")
            }
        },
        dismissButton = {
            TextButton(onClick = { state.showOpenDialog = false }) { Text("取消") }
        },
    )
}

/** 动作的中文名称。 */
fun actionLabel(action: ShortcutAction): String = when (action) {
    ShortcutAction.OPEN -> "打开文件"
    ShortcutAction.SAVE -> "保存"
    ShortcutAction.CLOSE_TAB -> "关闭标签页"
    ShortcutAction.CLOSE_ALL -> "关闭全部标签页"
    ShortcutAction.QUIT -> "退出"
    ShortcutAction.UNDO -> "撤销"
    ShortcutAction.REDO -> "重做"
    ShortcutAction.FIND -> "查找"
    ShortcutAction.FIND_REPLACE -> "查找并替换"
    ShortcutAction.FIND_NEXT -> "查找下一个"
    ShortcutAction.FIND_PREVIOUS -> "查找上一个"
    ShortcutAction.REPLACE_ALL -> "全部替换"
    ShortcutAction.NEXT_TAB -> "下一个标签页"
    ShortcutAction.PREVIOUS_TAB -> "上一个标签页"
    ShortcutAction.TOGGLE_BOOKMARK -> "切换书签"
    ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT -> "语法高亮"
    ShortcutAction.TOGGLE_LINE_NUMBERS -> "行号"
    ShortcutAction.TOGGLE_DETAILS_PANEL -> "详情面板"
    ShortcutAction.TOGGLE_SEARCH_PANEL -> "查找替换面板"
    ShortcutAction.TOGGLE_DIFF_PANEL -> "Diff 面板"
    ShortcutAction.SHOW_HELP -> "显示本帮助"
}
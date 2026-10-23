package com.filestudio.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestudio.core.PluginHandlerInfo
import com.filestudio.desktop.AppState
import com.filestudio.desktop.Keymap
import com.filestudio.desktop.ShortcutAction
import com.filestudio.desktop.ShortcutDispatcher
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text

/**
 * 顶部菜单栏的一个菜单（文件 / 编辑 / 视图 / 工具 / 插件 / 帮助）。
 */
data class TopMenu(val title: String)

/** 顶部菜单的固定顺序。 */
val TOP_MENUS = listOf(
    TopMenu("文件"),
    TopMenu("编辑"),
    TopMenu("视图"),
    TopMenu("工具"),
    TopMenu("插件"),
    TopMenu("帮助"),
)

/**
 * 顶部菜单栏。
 *
 * 菜单项与快捷键共用 [ShortcutDispatcher]——「点保存」和「按 Ctrl+S」走的是
 * 同一条代码路径，两者不可能出现行为漂移；菜单右侧同时显示快捷键。
 */
@Composable
fun MenuBar(
    state: AppState,
    dispatcher: ShortcutDispatcher,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TOP_MENUS.forEach { menu ->
                MenuTitle(menu.title, open == menu.title) {
                    open = if (open == menu.title) null else menu.title
                }
            }
        }
    }

    MenuContent(open, state, dispatcher) { open = null }
}

@Composable
private fun MenuTitle(title: String, expanded: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(if (expanded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun MenuContent(
    open: String?,
    state: AppState,
    dispatcher: ShortcutDispatcher,
    close: () -> Unit,
) {
    val tab = state.activeTab
    val editable = tab != null && !tab.readOnly

    when (open) {
        "文件" -> DropdownMenu(expanded = true, onDismissRequest = close) {
            Item(ShortcutAction.OPEN, "打开…") { dispatcher.requestOpenDialog() }
            Item(ShortcutAction.SAVE, "保存", enabled = editable) {
                dispatcher.dispatch(ShortcutAction.SAVE)
            }
            Item(null, "打开最近的文件") {
                // 最近文件在设置面板中列出，这里直接展开子菜单
            }
            if (state.recentFiles.isNotEmpty()) {
                state.recentFiles.take(8).forEach { path ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        onClick = {
                            state.openPathArg(path)
                            close()
                        },
                    )
                }
            }
            HorizontalDivider()
            Item(ShortcutAction.CLOSE_TAB, "关闭标签页", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.CLOSE_TAB)
            }
            Item(ShortcutAction.CLOSE_ALL, "关闭全部标签页", enabled = state.tabs.isNotEmpty()) {
                dispatcher.dispatch(ShortcutAction.CLOSE_ALL)
            }
            HorizontalDivider()
            Item(ShortcutAction.QUIT, "退出") { dispatcher.dispatch(ShortcutAction.QUIT) }
        }

        "编辑" -> DropdownMenu(expanded = true, onDismissRequest = close) {
            Item(ShortcutAction.UNDO, "撤销", enabled = tab != null && tab.modified) {
                dispatcher.dispatch(ShortcutAction.UNDO)
            }
            Item(ShortcutAction.REDO, "重做", enabled = tab != null && tab.modified) {
                dispatcher.dispatch(ShortcutAction.REDO)
            }
            HorizontalDivider()
            Item(ShortcutAction.FIND, "查找…", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.FIND)
            }
            Item(ShortcutAction.FIND_REPLACE, "查找并替换…", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.FIND_REPLACE)
            }
            Item(ShortcutAction.FIND_NEXT, "查找下一个", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.FIND_NEXT)
            }
            Item(ShortcutAction.FIND_PREVIOUS, "查找上一个", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.FIND_PREVIOUS)
            }
            Item(ShortcutAction.REPLACE_ALL, "全部替换",
                enabled = tab != null && state.matches.isNotEmpty()) {
                dispatcher.dispatch(ShortcutAction.REPLACE_ALL)
            }
            HorizontalDivider()
            Item(ShortcutAction.TOGGLE_BOOKMARK, "切换书签", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_BOOKMARK)
            }
            HorizontalDivider()
            Item(null, "复制全部内容", enabled = tab != null) {
                // 剪贴板需要系统权限，此处明确告知而不是静默失败
                state.updateStatus("请使用编辑框自身的 Ctrl+A / Ctrl+C")
                close()
            }
        }

        "视图" -> DropdownMenu(expanded = true, onDismissRequest = close) {
            Item(ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT, mark(state.settings.syntaxHighlight, "语法高亮")) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT)
            }
            Item(ShortcutAction.TOGGLE_LINE_NUMBERS, mark(state.settings.showLineNumbers, "行号")) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_LINE_NUMBERS)
            }
            Item(null, mark(state.settings.wrapLines, "自动换行")) {
                state.updateSettings { it.copy(wrapLines = !it.wrapLines) }
                close()
            }
            Item(ShortcutAction.TOGGLE_DETAILS_PANEL, mark(state.settings.showDetailsPanel, "详情面板")) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_DETAILS_PANEL)
            }
            HorizontalDivider()
            Stepper("放大字号", state.settings.fontSizeSp, 8..32) { v ->
                state.updateSettings { it.copy(fontSizeSp = v) }
            }
            Stepper("缩小字号", state.settings.fontSizeSp, 8..32) { v ->
                state.updateSettings { it.copy(fontSizeSp = v) }
            }
            HorizontalDivider()
            Item(ShortcutAction.TOGGLE_SEARCH_PANEL, mark(state.showSearchPanel, "查找替换面板")) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_SEARCH_PANEL)
            }
            Item(ShortcutAction.TOGGLE_DIFF_PANEL, mark(state.showDiffPanel, "Diff 面板")) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_DIFF_PANEL)
            }
        }

        "工具" -> DropdownMenu(expanded = true, onDismissRequest = close) {
            Item(ShortcutAction.TOGGLE_DIFF_PANEL, "与磁盘比较（Diff）", enabled = tab != null) {
                dispatcher.dispatch(ShortcutAction.TOGGLE_DIFF_PANEL)
            }
            HorizontalDivider()
            Item(null, "设置…") {
                state.showSettingsDialog = true
                close()
            }
        }

        "插件" -> DropdownMenu(expanded = true, onDismissRequest = close) {
            Text(
                "已注册 ${state.handlers.size} 个格式处理器",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider()
            state.handlers.take(18).forEach { HandlerMenuRow(it) }
            if (state.handlers.size > 18) {
                Text(
                    "…另有 ${state.handlers.size - 18} 个",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        "帮助" -> DropdownMenu(expanded = true, onDismissRequest = close) {
            Item(ShortcutAction.SHOW_HELP, "键盘快捷键") {
                dispatcher.dispatch(ShortcutAction.SHOW_HELP)
            }
            Item(null, "关于 FileStudio") {
                dispatcher.showAbout = true
                close()
            }
        }
    }
}

/**
 * 带勾选标记的菜单项文本。
 */
private fun mark(checked: Boolean, label: String): String = if (checked) "$label ✓" else label

@Composable
private fun Item(
    action: ShortcutAction?,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (action != null) {
                Text(
                    Keymap.allLabels(action),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * 「放大/缩小字号」这类动作型菜单项。
 */
@Composable
private fun Stepper(label: String, current: Int, range: IntRange, apply: (Int) -> Unit) {
    DropdownMenuItem(
        text = { Text("$label（当前 $current）") },
        onClick = {
            val delta = if (label.startsWith("放大")) 1 else -1
            apply((current + delta).coerceIn(range))
        },
    )
}

@Composable
private fun HandlerMenuRow(info: PluginHandlerInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            info.extension().ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp),
        )
        Text(
            info.description().ifBlank { info.mimeType() },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            info.editCapability().name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「关于」对话框。 */
@Composable
fun AboutDialog(dispatcher: ShortcutDispatcher) {
    if (!dispatcher.showAbout) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { dispatcher.showAbout = false },
        title = { Text("关于 FileStudio") },
        text = {
            Column {
                Text("跨平台文件查看与编辑器", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "核心为 Java 21，桌面端为 Kotlin + Compose Multiplatform。" +
                        "支持通过插件扩展的多种格式元数据查看与文本编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { dispatcher.showAbout = false }) {
                Text("关闭")
            }
        },
    )
}
package com.filestudio.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filestudio.core.PluginHandlerInfo
import com.filestudio.desktop.AppState
import com.filestudio.desktop.Keymap
import com.filestudio.desktop.SearchFocusTarget
import com.filestudio.desktop.ShortcutAction
import com.filestudio.desktop.ShortcutDispatcher
import com.filestudio.engine.SearchOptions

/**
 * 顶部的文件 / 编辑 / 视图 / 插件四个菜单。
 *
 * 菜单项与快捷键共用 [ShortcutDispatcher]，因此「点保存」和「按 Ctrl+S」
 * 走的是同一条代码路径，不会出现两者行为不一致的情况。
 * 每个菜单项右侧显示对应快捷键。
 */
@Composable
fun MenuBar(
    state: AppState,
    dispatcher: ShortcutDispatcher,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuLabel("文件", menu == "文件") { menu = if (menu == "文件") null else "文件" }
            MenuLabel("编辑", menu == "编辑") { menu = if (menu == "编辑") null else "编辑" }
            MenuLabel("视图", menu == "视图") { menu = if (menu == "视图") null else "视图" }
            MenuLabel("插件 (${state.handlers.size})", menu == "插件") {
                menu = if (menu == "插件") null else "插件"
            }
        }
    }

    DropdownMenu(expanded = menu == "文件", onDismissRequest = { menu = null }) {
        MenuAction(ShortcutAction.OPEN, "打开…") { dispatcher.requestOpenDialog() }
        MenuAction(ShortcutAction.SAVE, "保存") {
            dispatcher.dispatch(ShortcutAction.SAVE)
        }.also { }
        MenuAction(ShortcutAction.CLOSE_TAB, "关闭当前标签") {
            dispatcher.dispatch(ShortcutAction.CLOSE_TAB)
        }
        MenuAction(ShortcutAction.CLOSE_ALL, "关闭全部标签") {
            dispatcher.dispatch(ShortcutAction.CLOSE_ALL)
        }
        HorizontalDivider()
        MenuAction(ShortcutAction.QUIT, "退出") { dispatcher.dispatch(ShortcutAction.QUIT) }
    }

    DropdownMenu(expanded = menu == "编辑", onDismissRequest = { menu = null }) {
        val tab = state.activeTab
        MenuAction(ShortcutAction.UNDO, "撤销", enabled = tab != null && tab.modified) {
            dispatcher.dispatch(ShortcutAction.UNDO)
        }
        MenuAction(ShortcutAction.REDO, "重做", enabled = tab != null && tab.modified) {
            dispatcher.dispatch(ShortcutAction.REDO)
        }
        HorizontalDivider()
        MenuAction(ShortcutAction.FIND, "查找", enabled = tab != null) {
            dispatcher.dispatch(ShortcutAction.FIND)
        }
        MenuAction(ShortcutAction.FIND_REPLACE, "查找并替换", enabled = tab != null) {
            dispatcher.dispatch(ShortcutAction.FIND_REPLACE)
        }
        MenuAction(ShortcutAction.REPLACE_ALL, "全部替换",
            enabled = tab != null && state.matches.isNotEmpty()) {
            dispatcher.dispatch(ShortcutAction.REPLACE_ALL)
        }
        HorizontalDivider()
        MenuAction(ShortcutAction.TOGGLE_DIFF_PANEL, "与磁盘比较（Diff）",
            enabled = tab != null) {
            dispatcher.dispatch(ShortcutAction.TOGGLE_DIFF_PANEL)
        }
        MenuAction(ShortcutAction.TOGGLE_BOOKMARK, "切换书签", enabled = tab != null) {
            dispatcher.dispatch(ShortcutAction.TOGGLE_BOOKMARK)
        }
    }

    DropdownMenu(expanded = menu == "视图", onDismissRequest = { menu = null }) {
        MenuAction(ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT,
            if (state.syntaxHighlightEnabled) "语法高亮 ✓" else "语法高亮") {
            dispatcher.dispatch(ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT)
        }
        MenuAction(ShortcutAction.TOGGLE_LINE_NUMBERS,
            if (state.showLineNumbers) "行号 ✓" else "行号") {
            dispatcher.dispatch(ShortcutAction.TOGGLE_LINE_NUMBERS)
        }
        MenuAction(ShortcutAction.TOGGLE_DETAILS_PANEL,
            if (state.showDetailsPanel) "详情面板 ✓" else "详情面板") {
            dispatcher.dispatch(ShortcutAction.TOGGLE_DETAILS_PANEL)
        }
        MenuAction(ShortcutAction.TOGGLE_SEARCH_PANEL,
            if (state.showSearchPanel) "查找替换面板 ✓" else "查找替换面板") {
            dispatcher.dispatch(ShortcutAction.TOGGLE_SEARCH_PANEL)
        }
        MenuAction(ShortcutAction.TOGGLE_DIFF_PANEL,
            if (state.showDiffPanel) "Diff 面板 ✓" else "Diff 面板") {
            dispatcher.dispatch(ShortcutAction.TOGGLE_DIFF_PANEL)
        }
        HorizontalDivider()
        MenuAction(ShortcutAction.NEXT_TAB, "下一个标签") {
            dispatcher.dispatch(ShortcutAction.NEXT_TAB)
        }
        MenuAction(ShortcutAction.PREVIOUS_TAB, "上一个标签") {
            dispatcher.dispatch(ShortcutAction.PREVIOUS_TAB)
        }
        HorizontalDivider()
        MenuAction(ShortcutAction.SHOW_HELP, "键盘快捷键") {
            dispatcher.dispatch(ShortcutAction.SHOW_HELP)
        }
    }

    DropdownMenu(expanded = menu == "插件", onDismissRequest = { menu = null }) {
        Text(
            "已注册 ${state.handlers.size} 个格式处理器",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
        state.handlers.take(20).forEach { HandlerMenuRow(it) }
        if (state.handlers.size > 20) {
            Text(
                "…另有 ${state.handlers.size - 20} 个",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 带快捷键提示的菜单项。
 */
@Composable
private fun MenuAction(
    action: ShortcutAction,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            Text(
                Keymap.allLabels(action),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun MenuLabel(text: String, expanded: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(
                if (expanded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
        onClick = { onChange(!checked) },
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

/**
 * 查找与替换面板。
 *
 * 通过 [AppState.pendingFocus] 接收焦点请求：按下 Ctrl+F 时面板可能刚刚
 * 才被展开，此时直接 requestFocus 会打在不存在的节点上，因此把请求记在
 * 状态里，等本组件重组后再兑现。
 */
@Composable
fun SearchPanel(state: AppState, modifier: Modifier = Modifier) {
    val queryFocus = remember { FocusRequester() }
    val replacementFocus = remember { FocusRequester() }

    LaunchedEffect(state.pendingFocus) {
        when (state.pendingFocus) {
            SearchFocusTarget.QUERY -> queryFocus.requestFocus()
            SearchFocusTarget.REPLACEMENT -> replacementFocus.requestFocus()
            null -> return@LaunchedEffect
        }
        state.pendingFocus = null
    }

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { state.setSearchQuery(it) },
                    label = { Text("查找") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(queryFocus),
                )
                OutlinedTextField(
                    value = state.searchReplacement,
                    onValueChange = { state.setSearchReplacement(it) },
                    label = { Text("替换为") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(replacementFocus),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    if (state.matches.isEmpty()) "无匹配" else
                        "第 ${state.currentMatchIndex + 1}/${state.matches.size} 处",
                    style = MaterialTheme.typography.labelMedium,
                )
                Button(onClick = { state.selectPreviousMatch() }, enabled = state.matches.isNotEmpty()) {
                    Text("上一个")
                }
                Button(onClick = { state.selectNextMatch() }, enabled = state.matches.isNotEmpty()) {
                    Text("下一个")
                }
                Button(
                    onClick = { state.replaceCurrentMatch() },
                    enabled = state.matches.isNotEmpty() && state.currentMatch() != null,
                ) {
                    Text("替换")
                }
                Button(
                    onClick = { state.replaceAllMatches() },
                    enabled = state.matches.isNotEmpty(),
                ) {
                    Text("全部替换")
                }
                Button(onClick = { state.clearSearch() }) { Text("清除") }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                OptionCheck("区分大小写", state.searchOptions.isCaseSensitive) {
                    state.setSearchOptions(
                        SearchOptions(
                            it, state.searchOptions.isWholeWord,
                            state.searchOptions.isUseRegex, state.searchOptions.isForward
                        )
                    )
                }
                OptionCheck("全词匹配", state.searchOptions.isWholeWord) {
                    state.setSearchOptions(
                        SearchOptions(
                            state.searchOptions.isCaseSensitive, it,
                            state.searchOptions.isUseRegex, state.searchOptions.isForward
                        )
                    )
                }
                OptionCheck("正则表达式", state.searchOptions.isUseRegex) {
                    state.setSearchOptions(
                        SearchOptions(
                            state.searchOptions.isCaseSensitive,
                            state.searchOptions.isWholeWord, it,
                            state.searchOptions.isForward
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Diff 面板：展示「磁盘上的内容」与「当前编辑内容」的差异。
 */
@Composable
fun DiffPanel(state: AppState, modifier: Modifier = Modifier) {
    val result = remember(state.activeTab?.key, state.activeTab?.content) {
        state.diffWithDisk()
    }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(Modifier.padding(8.dp)) {
            if (result == null) {
                Text("无法比较（文件不可读）", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                "与磁盘比较：+${result.addedCount()} 新增 / -${result.removedCount()} 删除 / " +
                    "${result.unchangedCount()} 未变",
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Column(
                Modifier
                    .height(180.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                result.lines().forEach { line ->
                    val color = when {
                        line.isInsert -> MaterialTheme.colorScheme.tertiaryContainer
                        line.isDelete -> MaterialTheme.colorScheme.errorContainer
                        else -> Color.Transparent
                    }
                    Row(Modifier.fillMaxWidth().background(color)) {
                        Text(
                            line.toString().padEnd(2).take(2),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            line.content(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filestudio.core.PluginHandlerInfo
import com.filestudio.desktop.AppState
import com.filestudio.engine.SearchOptions

/**
 * 顶部的文件 / 编辑 / 视图 / 插件四个菜单。
 *
 * 菜单项直接调用 [AppState] 上的编辑器命令——核心里的撤销、搜索替换、
 * Diff、书签能力原本就存在，这里只是把它们接到界面上。
 */
@Composable
fun MenuBar(
    state: AppState,
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
        var pathInput by remember { mutableStateOf("") }
        OutlinedTextField(
            value = pathInput,
            onValueChange = { pathInput = it },
            label = { Text("文件路径") },
            singleLine = true,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        )
        DropdownMenuItem(
            text = { Text("打开") },
            enabled = pathInput.isNotBlank(),
            onClick = {
                state.openPathArg(pathInput.trim())
                pathInput = ""
                menu = null
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("保存") },
            enabled = state.activeTab != null && !state.activeTab!!.readOnly,
            onClick = {
                state.saveActive()
                menu = null
            },
        )
        DropdownMenuItem(
            text = { Text("关闭当前标签") },
            enabled = state.activeTab != null,
            onClick = {
                val idx = state.activeIndex
                if (idx >= 0) state.closeTab(idx)
                menu = null
            },
        )
    }

    DropdownMenu(expanded = menu == "编辑", onDismissRequest = { menu = null }) {
        val tab = state.activeTab
        DropdownMenuItem(
            text = { Text("撤销") },
            enabled = tab != null && tab.modified,
            onClick = {
                state.undo()
                menu = null
            },
        )
        DropdownMenuItem(
            text = { Text("重做") },
            enabled = tab != null && tab.modified,
            onClick = {
                state.redo()
                menu = null
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("查找与替换…") },
            enabled = tab != null,
            onClick = {
                state.showSearchPanel = !state.showSearchPanel
                menu = null
            },
        )
        DropdownMenuItem(
            text = { Text("与磁盘比较（Diff）") },
            enabled = tab != null,
            onClick = {
                state.toggleDiffPanel()
                menu = null
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("在当前行切换书签") },
            enabled = tab != null,
            onClick = {
                state.toggleBookmark(state.cursorLine, "标记")
                menu = null
            },
        )
    }

    DropdownMenu(expanded = menu == "视图", onDismissRequest = { menu = null }) {
        ToggleItem("语法高亮", state.syntaxHighlightEnabled) {
            state.syntaxHighlightEnabled = it
        }
        ToggleItem("行号", state.showLineNumbers) { state.showLineNumbers = it }
        ToggleItem("详情面板", state.showDetailsPanel) { state.showDetailsPanel = it }
        ToggleItem("查找替换面板", state.showSearchPanel) { state.showSearchPanel = it }
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
 */
@Composable
fun SearchPanel(state: AppState, modifier: Modifier = Modifier) {
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
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.searchReplacement,
                    onValueChange = { state.setSearchReplacement(it) },
                    label = { Text("替换为") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
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
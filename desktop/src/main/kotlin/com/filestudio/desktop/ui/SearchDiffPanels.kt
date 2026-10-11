package com.filestudio.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filestudio.desktop.AppState
import com.filestudio.desktop.SearchFocusTarget
import com.filestudio.engine.SearchOptions

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    if (state.matches.isEmpty()) "无匹配" else
                        "第 ${state.currentMatchIndex + 1}/${state.matches.size} 处",
                    style = MaterialTheme.typography.labelMedium,
                )
                Button(
                    onClick = { state.selectPreviousMatch() },
                    enabled = state.matches.isNotEmpty(),
                ) { Text("上一个 (Shift+F3)") }
                Button(
                    onClick = { state.selectNextMatch() },
                    enabled = state.matches.isNotEmpty(),
                ) { Text("下一个 (F3)") }
                Button(
                    onClick = { state.replaceCurrentMatch() },
                    enabled = state.currentMatch() != null,
                ) { Text("替换") }
                Button(
                    onClick = { state.replaceAllMatches() },
                    enabled = state.matches.isNotEmpty(),
                ) { Text("全部替换 (Ctrl+Alt+Enter)") }
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
    val tab = state.activeTab
    val content = tab?.content
    val result = remember(tab?.key, content) {
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
                            if (line.isInsert) "+" else if (line.isDelete) "-" else " ",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(18.dp),
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
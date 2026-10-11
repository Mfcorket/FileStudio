package com.filestudio.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filestudio.desktop.AppState
import com.filestudio.desktop.Settings
import com.filestudio.desktop.ThemeMode

/**
 * 设置对话框。
 *
 * 所有改动直接写回 [AppState.settings] 并落盘——这里没有「确定 / 取消」，
 * 改一项就生效一项，与编辑器实际行为一致，避免用户以为没保存。
 */
@Composable
fun SettingsDialog(state: AppState, onDismiss: () -> Unit) {
    if (!state.showSettingsDialog) return

    var themeMenuOpen by remember { mutableStateOf(false) }
    val s = state.settings

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SectionHeader("外观")

                // 主题
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("主题", Modifier.weight(1f))
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { themeMenuOpen = true }) {
                            Text(themeLabel(s.theme))
                        }
                        DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(themeLabel(mode)) },
                                    onClick = {
                                        state.updateSettings { it.copy(theme = mode) }
                                        themeMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }

                StepperRow(
                    label = "字号",
                    value = s.fontSizeSp,
                    range = 8..32,
                    suffix = " pt",
                    onChange = { v -> state.updateSettings { it.copy(fontSizeSp = v) } },
                )

                SwitchRow("自动换行", s.wrapLines) { v ->
                    state.updateSettings { it.copy(wrapLines = v) }
                }
                SwitchRow("语法高亮", s.syntaxHighlight) { v ->
                    state.updateSettings { it.copy(syntaxHighlight = v) }
                }
                SwitchRow("显示行号", s.showLineNumbers) { v ->
                    state.updateSettings { it.copy(showLineNumbers = v) }
                }
                SwitchRow("显示详情面板", s.showDetailsPanel) { v ->
                    state.updateSettings { it.copy(showDetailsPanel = v) }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader("其他")

                OutlinedTextField(
                    value = s.tabWidth.toString(),
                    onValueChange = { text ->
                        val n = text.filter { it.isDigit() }.toIntOrNull()
                        if (n != null) state.updateSettings { it.copy(tabWidth = n) }
                    },
                    label = { Text("Tab 显示宽度") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader("最近文件 (${state.recentFiles.size})")

                if (state.recentFiles.isEmpty()) {
                    Text(
                        "暂无记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.recentFiles.forEach { path ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            TextButton(onClick = {
                                state.openPathArg(path)
                                onDismiss()
                            }) { Text("打开") }
                        }
                    }
                    OutlinedButton(
                        onClick = { state.clearRecentFiles() },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("清空最近文件") }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        state.resetSettings()
                    }) { Text("恢复默认") }
                    Text(
                        state.settingsPathHint(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 带 −/+ 的数值调节行。
 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = value > range.first,
        ) { Text("−") }
        Text(
            "$value$suffix",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.SYSTEM -> "跟随系统"
}
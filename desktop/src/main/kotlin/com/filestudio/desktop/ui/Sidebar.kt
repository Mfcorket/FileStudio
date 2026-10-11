package com.filestudio.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestudio.core.PluginHandlerInfo
import com.filestudio.desktop.Tab

/**
 * 左侧栏：已打开的标签页 + 处理器清单。
 */
@Composable
fun Sidebar(
    tabs: List<Tab>,
    activeIndex: Int,
    handlers: List<PluginHandlerInfo>,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxSize()) {
            SectionTitle("标签页 (${tabs.size})")
            if (tabs.isEmpty()) {
                Text(
                    "没有打开的文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                tabs.forEachIndexed { index, tab ->
                    TabRow(
                        tab = tab,
                        active = index == activeIndex,
                        onSelect = { onSelect(index) },
                        onClose = { onClose(index) },
                    )
                }
            }

            HorizontalDivider()

            SectionTitle("已注册处理器 (${handlers.size})")
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                handlers.forEach { info ->
                    HandlerRow(info)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun TabRow(tab: Tab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tab.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (tab.readOnly) "只读" else "可编辑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tab.modified) {
            Text(
                "●",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Text(
            "✕",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .clickable(onClick = onClose),
        )
    }
}

@Composable
private fun HandlerRow(info: PluginHandlerInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            info.extension().ifBlank { "(无扩展名)" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
        )
        Text(
            info.mimeType(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 右侧栏：当前文档的元数据与统计。
 */
@Composable
fun DetailsPanel(
    metadata: Map<String, Any>,
    stats: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("文档信息")
            if (stats != null) {
                KeyValue("统计", stats)
            }
            if (metadata.isEmpty()) {
                Text(
                    "无元数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                metadata.entries.sortedBy { it.key }.forEach { (k, v) ->
                    KeyValue(k, formatValue(v))
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                key,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 把元数据值转成一行可读文本。
 *
 * 集合与数组会展开成逗号分隔的短列表，避免一屏塞进一整段 JSON。
 */
fun formatValue(value: Any?): String = when (value) {
    null -> "—"
    is String -> value.ifBlank { "—" }
    is Boolean -> if (value) "是" else "否"
    is Collection<*> -> {
        if (value.isEmpty()) {
            "（空）"
        } else {
            value.take(12).joinToString(", ") { formatValue(it) } +
                if (value.size > 12) " …共 ${value.size} 项" else ""
        }
    }
    is Map<*, *> -> {
        if (value.isEmpty()) {
            "（空）"
        } else {
            value.entries.take(12).joinToString("; ") { "${it.key}=${formatValue(it.value)}" } +
                if (value.size > 12) " …共 ${value.size} 项" else ""
        }
    }
    else -> value.toString()
}

/**
 * 底部状态栏。
 */
@Composable
fun StatusBar(
    status: String,
    error: String?,
    tabCount: Int,
    handlerCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                error ?: status,
                style = MaterialTheme.typography.labelMedium,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$tabCount 标签 · $handlerCount 处理器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空状态占位。 */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FileStudio", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "用「文件 → 打开」选择一个文件，或从命令行传入路径",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
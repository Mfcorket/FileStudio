package com.filestudio.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filestudio.desktop.AppState
import com.filestudio.desktop.Tab

/**
 * 菜单栏。MVP 阶段先做最常用的几项，避免一上来就堆一堆无效菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuBar(
    state: AppState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                "文件",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { menuOpen = !menuOpen }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "编辑",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "视图",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "插件 (${state.handlers.size})",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    if (menuOpen) {
        FileMenu(onOpenPath = { state.openPathArg(it) }, onExit = onExit, onDismiss = { menuOpen = false })
    }
}

/** 下拉菜单内容：按路径打开与退出。 */
@Composable
private fun FileMenu(
    onOpenPath: (String) -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pathInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text("文件路径") },
                singleLine = true,
            )
            DropdownMenuItem(
                text = { Text("打开") },
                enabled = pathInput.isNotBlank(),
                onClick = {
                    onOpenPath(pathInput.trim())
                    pathInput = ""
                    onDismiss()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("退出") },
                onClick = {
                    onExit()
                    onDismiss()
                },
            )
        }
    }
}

/**
 * 应用根布局：菜单栏 + 左中右三栏 + 状态栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileStudioApp(
    state: AppState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = state.tabs
    val active = state.activeTab
    val handlers = state.handlers

    // 每次状态消息变化都提示一次，错误用红色由 StatusBar 呈现
    LaunchedEffect(state.status, state.error) {
        if (state.error == null && state.status.isNotBlank()) {
            snackbarHostState.showSnackbar(state.status)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                MenuBar(
                    state = state,
                    onExit = onExit,
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            StatusBar(
                status = state.status,
                error = state.error,
                tabCount = tabs.size,
                handlerCount = handlers.size,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Sidebar(
                tabs = tabs,
                activeIndex = state.activeIndex,
                handlers = handlers,
                onSelect = { state.setActive(it) },
                onClose = { state.closeTab(it) },
                modifier = Modifier.weight(0.24f),
            )
            VerticalDivider()
            EditorArea(
                tab = active,
                onChange = { state.replaceActiveContent(it) },
                modifier = Modifier.weight(0.56f),
            )
            VerticalDivider()
            DetailsPanel(
                metadata = state.activeMetadata(),
                stats = state.activeStats(),
                modifier = Modifier.weight(0.20f),
            )
        }
    }
}

/**
 * 主区域：可编辑文本或只读预览。
 *
 * 只读格式展示内容与提示，不提供输入框——避免用户误以为能改二进制文件。
 */
@Composable
private fun EditorArea(
    tab: Tab?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tab == null) {
        EmptyState(modifier)
        return
    }

    if (tab.readOnly) {
        Column(modifier.fillMaxSize()) {
            ReadOnlyBanner(tab)
            Surface(Modifier.fillMaxSize()) {
                Text(
                    tab.content,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
        return
    }

    var text by remember(tab.key) { mutableStateOf(tab.content) }
    TextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        modifier = Modifier.fillMaxSize(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun ReadOnlyBanner(tab: Tab) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            "${tab.fileName} · 只读格式，解析结果仅供查看",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
package com.filestudio.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.filestudio.desktop.AppState
import com.filestudio.desktop.ShortcutDispatcher

/**
 * 应用根布局：菜单栏 + 左中右三栏 + 可折叠面板 + 状态栏。
 *
 * [ShortcutHandler] 挂在整棵树的根部，事件先于输入框被截获，
 * 这样在编辑器里按 Ctrl+S 不会变成「插入一个特殊字符」。
 */
@Composable
fun FileStudioApp(
    state: AppState,
    dispatcher: ShortcutDispatcher,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = state.tabs
    val active = state.activeTab
    val handlers = state.handlers

    // 状态消息变化时提示一次；错误交给底部状态栏用红色呈现
    LaunchedEffect(state.status, state.error) {
        if (state.error == null && state.status.isNotBlank()) {
            snackbarHostState.showSnackbar(state.status)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().then(Modifier),
        topBar = {
            Column {
                MenuBar(state = state, dispatcher = dispatcher)
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
        ShortcutHandler(dispatcher = dispatcher) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.showSearchPanel) {
                    SearchPanel(state)
                    HorizontalDivider()
                }
                if (state.showDiffPanel) {
                    DiffPanel(state)
                    HorizontalDivider()
                }

                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        tabs = tabs,
                        activeIndex = state.activeIndex,
                        handlers = handlers,
                        onSelect = { state.setActive(it) },
                        onClose = { state.closeTab(it) },
                        modifier = Modifier.weight(0.22f),
                    )
                    VerticalDivider()
                    EditorArea(
                        state = state,
                        tab = active,
                        onChange = { state.replaceActiveContent(it) },
                        modifier = Modifier.weight(0.48f),
                    )
                    if (state.showDetailsPanel) {
                        VerticalDivider()
                        DetailsPanel(
                            metadata = state.activeMetadata(),
                            stats = state.activeStats(),
                            modifier = Modifier.weight(0.30f),
                        )
                    }
                }
            }
        }
    }
}
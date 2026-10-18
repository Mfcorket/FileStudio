package com.filestudio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.filestudio.desktop.ShortcutDispatcher
import com.filestudio.desktop.ui.FileStudioApp

/**
 * 桌面端入口。
 *
 * 支持两种启动方式：
 * - 无参数：打开空白窗口
 * - 带路径：`filestudio README.md`，用于文件关联与「用其它应用打开」
 *
 * 主题可用系统属性覆盖：`-Dfilestudio.theme=light|dark`。
 */
fun main(args: Array<String>) {
    val initialPaths = args.filter { it.isNotBlank() && !it.startsWith("-") }

    // 启动时先读一次配置，用于决定窗口尺寸与主题
    val store = SettingsStore(SettingsStore.defaultPath())
    val settings = store.load()

    application {
        val windowState = rememberWindowState(
            size = DpSize(settings.windowWidth.dp, settings.windowHeight.dp)
        )
        Window(
            onCloseRequest = {
                // 退出前把窗口尺寸写回配置，否则下次启动永远回到默认大小
                store.update {
                    it.copy(
                        windowWidth = windowState.size.width.value.toInt().coerceIn(640, 10000),
                        windowHeight = windowState.size.height.value.toInt().coerceIn(400, 10000),
                    )
                }
                exitApplication()
            },
            title = "FileStudio",
            state = windowState,
        ) {
            AppRoot(
                initialPaths = initialPaths,
                store = store,
                onExit = ::exitApplication,
            )
        }
    }
}

/**
 * 应用根节点。
 *
 * 抽出这一层是为了让启动参数、主题与配置可以脱离窗口单独构建与验证。
 */
@Composable
fun AppRoot(
    initialPaths: List<String> = emptyList(),
    store: SettingsStore = SettingsStore(SettingsStore.defaultPath()),
    onExit: () -> Unit = {},
) {
    // remember：重组时不会丢掉已打开的标签页
    val state = remember { AppState(store = store) }
    remember { initialPaths.forEach { state.openPathArg(it) } }

    val colors = when (state.settings.theme) {
        ThemeMode.DARK -> darkColorScheme()
        ThemeMode.LIGHT, ThemeMode.SYSTEM -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        val dispatcher = remember { ShortcutDispatcher(state, onExit) }
        FileStudioApp(
            state = state,
            dispatcher = dispatcher,
            onExit = onExit,
        )
    }
}
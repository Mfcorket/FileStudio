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
    val dark = darkThemeRequested()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "FileStudio",
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            AppRoot(initialPaths = initialPaths, darkTheme = dark)
        }
    }
}

/**
 * 应用根节点。
 *
 * 抽出这一层是为了让启动参数与主题可以脱离窗口单独构建与验证。
 */
@Composable
fun AppRoot(
    initialPaths: List<String> = emptyList(),
    darkTheme: Boolean = false,
    onExit: () -> Unit = {},
) {
    // remember：重组时不会丢掉已打开的标签页
    val state = remember { AppState() }
    remember { initialPaths.forEach { state.openPathArg(it) } }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        val dispatcher = remember { ShortcutDispatcher(state, onExit) }
        FileStudioApp(
            state = state,
            dispatcher = dispatcher,
            onExit = onExit,
        )
    }
}

/** 读取主题偏好；默认浅色，仅显式指定时才切深色。 */
private fun darkThemeRequested(): Boolean =
    System.getProperty("filestudio.theme", "").equals("dark", ignoreCase = true)
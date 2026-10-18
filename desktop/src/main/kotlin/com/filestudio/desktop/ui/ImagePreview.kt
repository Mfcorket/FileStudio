package com.filestudio.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.filestudio.desktop.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

/**
 * 图片预览。
 *
 * 核心的 [ImageFileHandler] 只给元数据、content 为空串，所以这里按路径
 * 重新读字节并交给 Skia 解码——解码与读盘都放到 IO 线程，避免大图卡住界面。
 *
 * Skia 支持 PNG / JPEG / WebP / GIF / BMP / ICO；HEIC、AVIF、TIFF 等格式
 * Skia 解不了，此时明确显示「无法预览」并保留元数据，而不是留一块空白。
 */
@Composable
fun ImagePreview(
    tab: Tab,
    modifier: Modifier = Modifier,
) {
    var image by remember(tab.path) { mutableStateOf<Image?>(null) }
    var loading by remember(tab.path) { mutableStateOf(true) }
    var failure by remember(tab.path) { mutableStateOf<String?>(null) }
    var manualScale by remember(tab.path) { mutableFloatStateOf(1f) }
    var fitMode by remember(tab.path) { mutableStateOf(true) }

    // 离开界面时释放 skia 图像，否则原生内存一直被占着
    DisposableEffect(tab.path) {
        onDispose {
            image?.close()
            image = null
        }
    }

    LaunchedEffect(tab.path) {
        loading = true
        failure = null
        image?.close()
        image = null

        val result = withContext(Dispatchers.IO) {
            try {
                val bytes = Files.readAllBytes(Path.of(tab.path))
                val decoded = Image.makeFromEncoded(bytes)
                if (decoded == null) {
                    Result.failure(IllegalStateException("该格式无法解码预览"))
                } else {
                    Result.success(decoded)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        result.fold(
            onSuccess = {
                image = it
                loading = false
            },
            onFailure = {
                failure = it.message ?: "无法加载图片"
                loading = false
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        PreviewToolbar(
            fileName = tab.fileName,
            fitMode = fitMode,
            canZoom = image != null,
            onFit = { fitMode = true },
            onActualSize = {
                fitMode = false
                manualScale = 1f
            },
            onZoomIn = {
                fitMode = false
                manualScale = (manualScale * 1.25f).coerceAtMost(16f)
            },
            onZoomOut = {
                fitMode = false
                manualScale = (manualScale / 1.25f).coerceAtLeast(0.05f)
            },
            scalePercent = if (fitMode) null else (manualScale * 100).toInt(),
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF2B2B2B)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(color = Color.White)

                failure != null -> PreviewMessage(
                    "无法预览此图片",
                    "$failure\n\n仍可在右侧查看文件元数据。",
                )

                image == null -> PreviewMessage("没有可显示的内容", null)

                else -> {
                    val img = image!!
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val fit = with(density) {
                            min(
                                maxWidth.toPx() / img.width.coerceAtLeast(1),
                                maxHeight.toPx() / img.height.coerceAtLeast(1),
                            )
                        }
                        val scale = if (fitMode) max(fit, 0.02f) else manualScale

                        val scrollV = rememberScrollState()
                        val scrollH = rememberScrollState()

                        Box(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollV)
                                .horizontalScroll(scrollH)
                                // Ctrl+滚轮缩放，符合常见看图习惯
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            // 注意修饰键状态在 keyboardModifiers 上，buttons 只是鼠标按键
                                            if (event.type == PointerEventType.Scroll &&
                                                event.keyboardModifiers.isCtrlPressed
                                            ) {
                                                val delta = event.changes.first().scrollDelta.y
                                                fitMode = false
                                                manualScale = (manualScale *
                                                    if (delta > 0) 0.9f else 1.1f)
                                                    .coerceIn(0.05f, 16f)
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val w = img.width * scale
                            val h = img.height * scale
                            Canvas(
                                Modifier
                                    .size(
                                        with(density) { w.toDp() },
                                        with(density) { h.toDp() },
                                    )
                            ) {
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawImageRect(
                                        img,
                                        Rect.makeWH(size.width, size.height),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewToolbar(
    fileName: String,
    fitMode: Boolean,
    canZoom: Boolean,
    onFit: () -> Unit,
    onActualSize: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    scalePercent: Int?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fileName,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (scalePercent != null) {
                Text("$scalePercent%", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.size(8.dp))
            }
            TextButton(onClick = onFit, enabled = canZoom && !fitMode) { Text("适应窗口") }
            TextButton(onClick = onActualSize, enabled = canZoom) { Text("100%") }
            IconButton(onClick = onZoomOut, enabled = canZoom) { Text("−") }
            IconButton(onClick = onZoomIn, enabled = canZoom) { Text("+") }
        }
    }
}

@Composable
private fun PreviewMessage(title: String, detail: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
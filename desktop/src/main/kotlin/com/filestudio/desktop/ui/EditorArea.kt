package com.filestudio.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestudio.desktop.AppState
import com.filestudio.desktop.Tab
import com.filestudio.engine.highlight.Token
import com.filestudio.engine.highlight.TokenKind

/**
 * 主编辑区。
 *
 * 可编辑格式走 [BasicTextField]，并通过 [VisualTransformation] 套用语法高亮；
 * 只读格式直接渲染解析结果，不提供输入框——避免让人误以为能改二进制。
 */
@Composable
fun EditorArea(
    state: AppState,
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
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        tab.content,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        return
    }

    var value by remember(tab.key) { mutableStateOf(TextFieldValue(tab.content)) }
    val tokens = state.highlightedTokens()
    val language = state.syntaxLanguageName()

    Column(modifier.fillMaxSize()) {
        EditorHeader(tab, language, state.bookmarks().size)
        Row(Modifier.fillMaxSize()) {
            if (state.showLineNumbers) {
                Gutter(
                    lines = state.activeTab?.session?.lineIndex()?.lineCount() ?: 0,
                    currentLine = state.cursorLine,
                    bookmarked = state.bookmarks().map { it.line() }.toSet(),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { newValue ->
                    value = newValue
                    state.moveCursorToOffset(newValue.selection.end)
                    // 选区变化不影响内容，跳过等长替换以减少无谓的状态写入
                    if (newValue.text != value.text) {
                        onChange(newValue.text)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary
                ),
                visualTransformation = if (tokens.isEmpty()) {
                    VisualTransformation.None
                } else {
                    SyntaxTransformation(tokens, state)
                },
            )
        }
    }
}

@Composable
private fun EditorHeader(tab: Tab, language: String?, bookmarkCount: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Text(
                tab.fileName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (language != null) {
                Text("语法：$language", style = MaterialTheme.typography.labelSmall)
            }
            if (bookmarkCount > 0) {
                Text("书签 $bookmarkCount", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
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

/** 行号栏，标记当前行与书签行。 */
@Composable
private fun Gutter(lines: Int, currentLine: Int, bookmarked: Set<Int>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier
                .width(52.dp)
                .height(400.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.End,
        ) {
            repeat(lines.coerceAtMost(5000)) { i ->
                val bg = if (i == currentLine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
                Text(
                    text = (if (i in bookmarked) "◆" else " ") + (i + 1).toString().padStart(4),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(bg).padding(end = 6.dp),
                )
            }
        }
    }
}

/**
 * 把语法高亮结果套到文本上。
 *
 * [VisualTransformation] 的契约是「偏移量一一对应」，因此这里用
 * [OffsetMapping.Identity]，只改变样式而不改变文本与光标位置。
 */
private class SyntaxTransformation(
    private val tokens: List<Token>,
    private val state: AppState,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val colors = scheme()
        val highlighted = buildAnnotatedString {
            append(text)
            tokens.forEach { token ->
                val start = token.offset()
                val end = start + token.text().length
                if (start < 0 || end > text.length || start >= end) {
                    return@forEach
                }
                addStyle(
                    SpanStyle(color = colors[token.kind()] ?: Color.Unspecified),
                    start,
                    end,
                )
            }
            // 当前选中的搜索匹配用高亮背景标出
            state.currentMatch()?.let { m ->
                if (m.start() in 0 until text.length && m.end() <= text.length) {
                    addStyle(
                        SpanStyle(
                            background = androidx.compose.ui.graphics.Color.Yellow.copy(alpha = 0.35f)
                        ),
                        m.start(),
                        m.end(),
                    )
                }
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    private fun scheme(): Map<TokenKind, Color> = mapOf(
        TokenKind.KEYWORD to Color(0xFF0033B3),
        TokenKind.STRING to Color(0xFF067D17),
        TokenKind.CHAR to Color(0xFF067D17),
        TokenKind.NUMBER to Color(0xFF1750EB),
        TokenKind.COMMENT to Color(0xFF8C8C8C),
        TokenKind.PREPROCESSOR to Color(0xFF9E880D),
        TokenKind.ANNOTATION to Color(0xFF9E880D),
        TokenKind.OPERATOR to Color(0xFF000000),
        TokenKind.IDENTIFIER to Color(0xFF000000),
        TokenKind.TEXT to Color(0xFF000000),
    )
}

/** 空状态占位。 */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FileStudio", style = MaterialTheme.typography.headlineMedium)
        Text(
            "在「文件」菜单里输入路径即可打开文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
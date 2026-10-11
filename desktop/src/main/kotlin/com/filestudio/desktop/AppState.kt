package com.filestudio.desktop

import com.filestudio.core.FileStudioCore
import com.filestudio.core.FileStudioException
import com.filestudio.core.PluginHandlerInfo
import com.filestudio.engine.Bookmark
import com.filestudio.engine.DiffEngine
import com.filestudio.engine.EditorSession
import com.filestudio.engine.EditorSessionManager
import com.filestudio.engine.SearchOptions
import com.filestudio.engine.SearchReplace
import com.filestudio.engine.SearchReplace.SearchMatch
import com.filestudio.engine.highlight.SyntaxHighlighter
import com.filestudio.engine.highlight.SyntaxLanguageRegistry
import com.filestudio.engine.highlight.Token
import java.io.File

/** 需要把焦点移到哪个输入框。 */
enum class SearchFocusTarget { QUERY, REPLACEMENT }

/**
 * 一个已打开的标签页。
 *
 * 标签页与 [EditorSession] 一一对应：session 持有编辑引擎、书签、统计等信息，
 * 本类只负责 UI 需要的展示字段，避免 UI 直接依赖引擎内部结构。
 */
data class Tab(
    val key: String,
    val fileName: String,
    val path: String,
    val session: EditorSession,
) {
    /** 是否处于修改未保存状态。 */
    val modified: Boolean get() = session.isModified

    /** 是否只读（能力不足 FULL 时不可编辑）。 */
    val readOnly: Boolean get() = !session.editor.isWritable

    /** 当前文本内容。 */
    val content: String get() = session.editor.content
}

/**
 * 桌面端的应用状态。
 *
 * 这里刻意不依赖任何 Compose 类型，因此可以在没有图形环境的单元测试里
 * 完整验证「打开 / 编辑 / 撤销 / 保存 / 关闭」这条主链路。
 */
class AppState(
    private val core: FileStudioCore = FileStudioCore(),
) {
    private val _tabs = mutableListOf<Tab>()
    private var _active = -1

    /** 最近一条状态消息，显示在底部状态栏。 */
    var status: String = "就绪"
        private set

    /** 最近一次错误消息，为 {@code null} 表示没有错误。 */
    var error: String? = null
        private set

    init {
        core.init()
    }

    /** 当前所有标签页。 */
    val tabs: List<Tab> get() = _tabs.toList()

    /** 当前激活的标签页；没有时为 {@code null}。 */
    val activeTab: Tab? get() = _tabs.getOrNull(_active)

    /** 当前激活标签页的下标；没有时为 -1。 */
    val activeIndex: Int get() = _active

    /** 已注册的处理器列表，供「插件」菜单展示。 */
    val handlers: List<PluginHandlerInfo> get() = core.listHandlers()

    /**
     * 打开一个文件，若已打开则激活既有标签页。
     *
     * @return 是否成功打开
     */
    fun openFile(file: File): Boolean {
        val path = try {
            file.absolutePath
        } catch (e: SecurityException) {
            error = "无法访问该路径：$file"
            return false
        }

        val existing = _tabs.indexOfFirst { it.path == path }
        if (existing >= 0) {
            _active = existing
            status = "已切换到 ${file.name}"
            error = null
            return true
        }

        return try {
            val session = core.newSessionManager().open(file)
            _tabs.add(Tab(session.key, session.fileName, path, session))
            _active = _tabs.size - 1
            error = null
            status = "已打开 ${session.fileName}（${_tabs.size} 个标签页）"
            true
        } catch (e: FileStudioException) {
            error = EditorSessionManager.describeError(file, e)
            status = "打开失败"
            false
        } catch (e: java.io.IOException) {
            error = "读取失败：${e.message}"
            status = "打开失败"
            false
        }
    }

    /** 打开命令行给出的第一个文件；失败时返回 false。 */
    fun openPathArg(path: String): Boolean = openFile(File(path))

    /**
     * 切换到指定标签页。
     *
     * @return 下标是否有效
     */
    fun setActive(index: Int): Boolean {
        if (index !in _tabs.indices) return false
        _active = index
        error = null
        return true
    }

    /**
     * 关闭标签页。
     *
     * @param index 标签页下标
     * @param force 为 true 时即使有未保存修改也关闭
     * @return 是否成功关闭
     */
    fun closeTab(index: Int, force: Boolean = false): Boolean {
        val tab = _tabs.getOrNull(index) ?: return false
        if (tab.modified && !force) {
            error = "${tab.fileName} 有未保存的修改"
            status = "已取消关闭"
            return false
        }
        try {
            tab.session.close()
        } catch (e: RuntimeException) {
            // 关闭失败不应阻断 UI
        }
        _tabs.removeAt(index)
        _active = when {
            _tabs.isEmpty() -> -1
            index < _active -> _active - 1
            index == _active -> minOf(index, _tabs.size - 1)
            else -> _active
        }
        error = null
        status = "已关闭 ${tab.fileName}"
        return true
    }

    /** 关闭全部标签页。 */
    fun closeAll(force: Boolean = false): Boolean {
        val targets = _tabs.indices.toList()
        var allClosed = true
        for (i in targets.reversed()) {
            if (!closeTab(i, force)) allClosed = false
        }
        return allClosed
    }

    /** 是否存在未保存的修改。 */
    fun hasUnsavedChanges(): Boolean = _tabs.any { it.modified }

    /** 用新内容替换当前文档，并走编辑引擎（保留撤销栈）。 */
    fun replaceActiveContent(newContent: String): Boolean {
        val tab = activeTab ?: return false
        if (tab.readOnly) {
            error = "${tab.fileName} 是只读格式"
            status = "无法编辑"
            return false
        }
        return try {
            tab.session.editor.edit(newContent)
            error = null
            status = "已修改 ${tab.fileName}"
            true
        } catch (e: RuntimeException) {
            error = "编辑失败：${e.message}"
            status = "编辑失败"
            false
        }
    }

    /** 撤销当前标签页的上一次编辑。 */
    fun undo(): Boolean = applyToActive { it.session.editor.undo(); "已撤销" }

    /** 重做当前标签页的上一次撤销。 */
    fun redo(): Boolean = applyToActive { it.session.editor.redo(); "已重做" }

    /** 保存当前标签页。 */
    fun saveActive(): Boolean {
        val tab = activeTab ?: return false
        return try {
            tab.session.save()
            error = null
            status = "已保存 ${tab.fileName}"
            true
        } catch (e: FileStudioException) {
            error = "保存失败：${e.message}"
            status = "保存失败"
            false
        } catch (e: java.io.IOException) {
            error = "保存失败：${e.message}"
            status = "保存失败"
            false
        }
    }

    /** 当前文档的元数据，供详情面板展示。 */
    fun activeMetadata(): Map<String, Any> {
        val tab = activeTab ?: return emptyMap()
        return tab.session.document.metadata
    }

    /** 当前文档的统计信息（行数、字数等）。 */
    fun activeStats(): String? {
        val tab = activeTab ?: return null
        val s = tab.session.stats()
        return "${s.lineCount()} 行 · ${s.wordCount()} 词 · ${s.charCount()} 字符 · ${s.lineEnding()}"
    }

    private inline fun applyToActive(block: (Tab) -> String): Boolean {
        val tab = activeTab ?: return false
        if (tab.readOnly) {
            error = "${tab.fileName} 是只读格式"
            return false
        }
        return try {
            status = block(tab)
            error = null
            true
        } catch (e: RuntimeException) {
            error = "操作失败：${e.message}"
            false
        }
    }

    /** 释放核心资源。 */
    fun shutdown() {
        closeAll(force = true)
        core.shutdown()
    }

    // ============================================================
    // 以下为编辑器命令：搜索替换、语法高亮、Diff、书签
    // 这些能力核心早已存在，这里只负责把它们接到界面上。
    // ============================================================

    // ---- 搜索与替换 ----

    /** 当前搜索关键字。 */
    var searchQuery: String = ""
        private set

    /** 当前替换文本。 */
    var searchReplacement: String = ""
        private set

    /** 当前搜索选项（大小写敏感 / 全词 / 正则）。 */
    var searchOptions: SearchOptions = SearchOptions.defaultOptions()
        private set

    /** 当前匹配列表，随文档内容与选项变化而重算。 */
    var matches: List<SearchMatch> = emptyList()
        private set

    /** 当前高亮的匹配下标；-1 表示没有选中项。 */
    var currentMatchIndex: Int = -1
        private set

    /** 设置搜索关键字并立即重新计算匹配。 */
    fun setSearchQuery(query: String) {
        searchQuery = query
        refreshMatches()
    }

    /** 设置替换文本。 */
    fun setSearchReplacement(replacement: String) {
        searchReplacement = replacement
    }

    /** 设置搜索选项并重新计算匹配。 */
    fun setSearchOptions(options: SearchOptions) {
        searchOptions = options
        refreshMatches()
    }

    /** 清空搜索状态。 */
    fun clearSearch() {
        searchQuery = ""
        searchReplacement = ""
        searchOptions = SearchOptions.defaultOptions()
        matches = emptyList()
        currentMatchIndex = -1
    }

    /** 选中下一个匹配；到末尾后回到开头。 */
    fun selectNextMatch(): Boolean {
        if (matches.isEmpty()) return false
        currentMatchIndex = (currentMatchIndex + 1) % matches.size
        return true
    }

    /** 选中上一个匹配；到开头后跳到末尾。 */
    fun selectPreviousMatch(): Boolean {
        if (matches.isEmpty()) return false
        currentMatchIndex =
            if (currentMatchIndex <= 0) matches.size - 1 else currentMatchIndex - 1
        return true
    }

    /** 当前选中的匹配。 */
    fun currentMatch(): SearchMatch? = matches.getOrNull(currentMatchIndex)

    /**
     * 替换当前选中的匹配，并重新计算匹配。
     *
     * @return 是否发生了替换
     */
    fun replaceCurrentMatch(): Boolean {
        val match = currentMatch() ?: return false
        val tab = activeTab ?: return false
        if (tab.readOnly) {
            error = "${tab.fileName} 是只读格式"
            return false
        }
        if (searchQuery.isEmpty()) return false

        val text = tab.content
        if (match.start < 0 || match.end > text.length) {
            refreshMatches()
            return false
        }
        val updated = text.substring(0, match.start) + searchReplacement + text.substring(match.end)
        if (!replaceActiveContent(updated)) return false

        // 替换后同一位置可能仍是匹配，保持在相近位置而不是跳回第一个
        refreshMatches()
        val sameIndex = matches.indexOfFirst { it.start >= match.start }
        currentMatchIndex = if (sameIndex >= 0) sameIndex else (matches.size - 1).coerceAtLeast(-1)
        status = "已替换 1 处"
        return true
    }

    /**
     * 替换全部匹配。
     *
     * @return 被替换的处数
     */
    fun replaceAllMatches(): Int {
        val tab = activeTab ?: return 0
        if (tab.readOnly) {
            error = "${tab.fileName} 是只读格式"
            return 0
        }
        if (searchQuery.isEmpty() || !SearchReplace.isValidQuery(searchQuery, searchOptions)) {
            return 0
        }
        val count = matches.size
        if (count == 0) return 0

        val updated = SearchReplace.replaceAll(
            tab.content, searchQuery, searchReplacement, searchOptions
        )
        if (!replaceActiveContent(updated)) return 0
        refreshMatches()
        status = "已替换 $count 处"
        return count
    }

    // ---- 语法高亮 ----

    /** 是否启用语法高亮。 */
    var syntaxHighlightEnabled: Boolean = true

    /**
     * 对当前文档做语法高亮。
     *
     * 没有匹配到语言配置时返回空列表，界面退化为纯文本显示。
     */
    fun highlightedTokens(): List<Token> {
        if (!syntaxHighlightEnabled) return emptyList()
        val tab = activeTab ?: return emptyList()
        val highlighter = highlighterFor(tab) ?: return emptyList()
        return highlighter.tokenize(tab.content)
    }

    /** 当前文档使用的语法配置标识；没有配置时返回 null。 */
    fun syntaxLanguageName(): String? =
        activeTab?.let { highlighterFor(it)?.profile?.id }

    private fun highlighterFor(tab: Tab): SyntaxHighlighter? =
        SyntaxLanguageRegistry.forPath(tab.path)

    // ---- Diff ----

    /**
     * 与磁盘上的当前文件比较，展示「已保存 vs 已修改」的差异。
     *
     * @return 差异结果；文件不存在或不可读时返回 null
     */
    fun diffWithDisk(): DiffEngine.DiffResult? {
        val tab = activeTab ?: return null
        val file = File(tab.path)
        if (!file.isFile) {
            error = "文件已不在磁盘上：${tab.fileName}"
            return null
        }
        return try {
            DiffEngine.diff(file.readText(), tab.content)
        } catch (e: java.io.IOException) {
            error = "读取磁盘文件失败：${e.message}"
            null
        }
    }

    /**
     * 与任意文本比较。
     */
    fun diffWith(other: String): DiffEngine.DiffResult? {
        val tab = activeTab ?: return null
        return DiffEngine.diff(other, tab.content)
    }

    // ---- 书签 ----

    /** 当前文档的书签列表。 */
    fun bookmarks(): List<Bookmark> =
        activeTab?.session?.bookmarks?.list() ?: emptyList()

    /** 某一行是否已有书签。 */
    fun hasBookmark(line: Int): Boolean =
        activeTab?.session?.bookmarks?.has(line) ?: false

    /**
     * 在指定行切换书签。
     *
     * @param line 从 0 开始的行号
     * @return 操作后该行是否有书签
     */
    fun toggleBookmark(line: Int, label: String = ""): Boolean {
        val tab = activeTab ?: return false
        val bookmarks = tab.session.bookmarks
        val nowPresent = if (bookmarks.has(line)) {
            bookmarks.remove(line)
            false
        } else {
            bookmarks.add(line, label)
            true
        }
        status = if (nowPresent) "已在第 ${line + 1} 行添加书签" else "已移除第 ${line + 1} 行书签"
        return nowPresent
    }

    // ---- 视图开关 ----

    /** 是否显示右侧详情面板。 */
    var showDetailsPanel: Boolean = true

    /** 是否显示搜索替换面板。 */
    var showSearchPanel: Boolean = false

    /** 是否显示 Diff 面板。 */
    var showDiffPanel: Boolean = false

    /** 是否显示行号。 */
    var showLineNumbers: Boolean = true

    /** 是否弹出「打开文件」对话框。 */
    var showOpenDialog: Boolean = false

    /** 需要把焦点移到哪个输入框。 */
    var pendingFocus: SearchFocusTarget? = null

    /** 光标所在行（从 0 开始）。 */
    var cursorLine: Int = 0

    /**
     * 把光标移到指定行，超出范围时收敛到有效区间。
     */
    fun moveCursorToLine(line: Int) {
        val lines = activeTab?.session?.lineIndex()?.lineCount() ?: return
        cursorLine = line.coerceIn(0, (lines - 1).coerceAtLeast(0))
    }

    /**
     * 根据文本偏移量更新光标行。
     */
    fun moveCursorToOffset(offset: Int) {
        val index = activeTab?.session?.lineIndex() ?: return
        cursorLine = index.lineOf(offset.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /** 切换 Diff 面板时同步刷新搜索结果，避免替换后统计过期。 */
    fun toggleDiffPanel() {
        showDiffPanel = !showDiffPanel
        if (showDiffPanel) {
            refreshMatches()
        }
    }

    /** 根据当前文档重新计算搜索匹配。 */
    private fun refreshMatches() {
        val tab = activeTab
        if (tab == null || searchQuery.isEmpty()) {
            matches = emptyList()
            currentMatchIndex = -1
            return
        }
        if (!SearchReplace.isValidQuery(searchQuery, searchOptions)) {
            matches = emptyList()
            currentMatchIndex = -1
            return
        }
        matches = try {
            SearchReplace.findMatches(tab.content, searchQuery, searchOptions)
        } catch (e: RuntimeException) {
            // 正则表达式非法时不能让界面崩掉
            emptyList()
        }
        currentMatchIndex = if (matches.isEmpty()) -1 else 0
    }
}
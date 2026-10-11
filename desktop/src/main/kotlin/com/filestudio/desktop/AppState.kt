package com.filestudio.desktop

import com.filestudio.core.FileStudioCore
import com.filestudio.core.FileStudioException
import com.filestudio.core.PluginHandlerInfo
import com.filestudio.engine.EditorSession
import com.filestudio.engine.EditorSessionManager
import java.io.File

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
}
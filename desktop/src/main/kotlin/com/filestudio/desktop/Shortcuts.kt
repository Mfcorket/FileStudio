package com.filestudio.desktop

import androidx.compose.ui.input.key.Key

/**
 * 一个快捷键组合。
 *
 * 刻意用 Compose 的 [Key] 作为按键类型：它是值类型，可以在没有图形环境的
 * 单元测试里直接构造与比较，不必伪造平台原生事件。
 */
data class Shortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /** 生成用于展示的文本，例如 `Ctrl+S`、`Ctrl+Shift+Z`。 */
    fun label(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        if (meta) append("Meta+")
        append(keyLabel(key))
    }

    companion object {
        /**
         * 按键到显示名称的映射。
         *
         * Compose 1.7 起 [Key] 不再暴露 keyCharacter，只能显式维护表；
         * 绑定表里的按键全部收录在内，未收录的按键会退化为「Key」。
         */
        private val KEY_LABELS: Map<Key, String> = mapOf(
            Key.Q to "Q", Key.W to "W", Key.E to "E", Key.R to "R", Key.T to "T",
            Key.Y to "Y", Key.U to "U", Key.I to "I", Key.O to "O", Key.P to "P",
            Key.A to "A", Key.S to "S", Key.D to "D", Key.F to "F", Key.G to "G",
            Key.H to "H", Key.J to "J", Key.K to "K", Key.L to "L", Key.Z to "Z",
            Key.X to "X", Key.C to "C", Key.V to "V", Key.B to "B", Key.N to "N",
            Key.M to "M",
            Key.F1 to "F1", Key.F2 to "F2", Key.F3 to "F3", Key.F4 to "F4",
            Key.F5 to "F5", Key.F11 to "F11", Key.F12 to "F12",
            Key.Escape to "Esc", Key.Enter to "Enter", Key.Tab to "Tab",
            Key.Spacebar to "Space", Key.Backspace to "Backspace",
            Key.Delete to "Delete",
            Key.DirectionUp to "↑", Key.DirectionDown to "↓",
            Key.DirectionLeft to "←", Key.DirectionRight to "→",
        )

        /** 把按键映射为可读名称。 */
        fun keyLabel(key: Key): String = KEY_LABELS[key] ?: "Key"
    }
}

/**
 * 界面可触发的动作。
 */
enum class ShortcutAction {
    OPEN,
    SAVE,
    CLOSE_TAB,
    CLOSE_ALL,
    QUIT,

    UNDO,
    REDO,
    FIND,
    FIND_REPLACE,
    FIND_NEXT,
    FIND_PREVIOUS,
    REPLACE_ALL,

    NEXT_TAB,
    PREVIOUS_TAB,
    TOGGLE_BOOKMARK,

    TOGGLE_SYNTAX_HIGHLIGHT,
    TOGGLE_LINE_NUMBERS,
    TOGGLE_DETAILS_PANEL,
    TOGGLE_SEARCH_PANEL,
    TOGGLE_DIFF_PANEL,

    SHOW_HELP,
}

/** 快捷键分组，用于帮助面板归类。 */
enum class ShortcutCategory(val title: String) {
    FILE("文件"),
    EDIT("编辑"),
    FIND("查找"),
    VIEW("视图"),
    APP("应用"),
}

/** 一条「动作 → 按键」绑定。 */
data class Binding(val action: ShortcutAction, val shortcut: Shortcut)

/**
 * 默认快捷键表。
 *
 * 用 `List<Binding>` 而不是 `Map<动作, 快捷键>`：撤销同时支持 Ctrl+Z 与
 * Ctrl+Y，Map 会把前一个覆盖掉。这里保持列表形态，反查表另行建立，
 * 并在构建时检测「两个动作抢同一个组合」这类配置错误。
 */
object Keymap {

    val bindings: List<Binding> = listOf(
        // 文件
        Binding(ShortcutAction.OPEN, Shortcut(Key.O, ctrl = true)),
        Binding(ShortcutAction.SAVE, Shortcut(Key.S, ctrl = true)),
        Binding(ShortcutAction.CLOSE_TAB, Shortcut(Key.W, ctrl = true)),
        Binding(ShortcutAction.CLOSE_ALL, Shortcut(Key.W, ctrl = true, shift = true)),
        Binding(ShortcutAction.QUIT, Shortcut(Key.Q, ctrl = true)),

        // 编辑
        Binding(ShortcutAction.UNDO, Shortcut(Key.Z, ctrl = true)),
        Binding(ShortcutAction.UNDO, Shortcut(Key.Y, ctrl = true)),
        Binding(ShortcutAction.REDO, Shortcut(Key.Z, ctrl = true, shift = true)),

        // 查找
        Binding(ShortcutAction.FIND, Shortcut(Key.F, ctrl = true)),
        Binding(ShortcutAction.FIND_REPLACE, Shortcut(Key.H, ctrl = true)),
        Binding(ShortcutAction.FIND_NEXT, Shortcut(Key.F3)),
        Binding(ShortcutAction.FIND_PREVIOUS, Shortcut(Key.F3, shift = true)),
        Binding(ShortcutAction.REPLACE_ALL, Shortcut(Key.Enter, ctrl = true, alt = true)),

        // 标签页
        Binding(ShortcutAction.NEXT_TAB, Shortcut(Key.Tab, ctrl = true)),
        Binding(ShortcutAction.PREVIOUS_TAB, Shortcut(Key.Tab, ctrl = true, shift = true)),
        Binding(ShortcutAction.TOGGLE_BOOKMARK, Shortcut(Key.F2)),

        // 视图
        Binding(ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT, Shortcut(Key.K, ctrl = true, shift = true)),
        Binding(ShortcutAction.TOGGLE_LINE_NUMBERS, Shortcut(Key.L, ctrl = true, shift = true)),
        Binding(ShortcutAction.TOGGLE_DETAILS_PANEL, Shortcut(Key.D, ctrl = true, shift = true)),
        Binding(ShortcutAction.TOGGLE_SEARCH_PANEL, Shortcut(Key.P, ctrl = true, shift = true)),
        Binding(ShortcutAction.TOGGLE_DIFF_PANEL, Shortcut(Key.I, ctrl = true, shift = true)),

        // 应用
        Binding(ShortcutAction.SHOW_HELP, Shortcut(Key.F1)),
    )

    /**
     * 组合 → 动作。
     *
     * 同一组合被两个动作占用属于配置错误，直接在首次访问时抛出，
     * 而不是等到界面上某个快捷键莫名其妙失灵才发现。
     */
    val reverse: Map<Shortcut, ShortcutAction> by lazy {
        val map = LinkedHashMap<Shortcut, ShortcutAction>()
        val conflicts = mutableListOf<String>()
        for (b in bindings) {
            val existing = map.put(b.shortcut, b.action)
            if (existing != null && existing != b.action) {
                conflicts += "${b.shortcut.label()} 同时绑定到 $existing 与 ${b.action}"
            }
        }
        if (conflicts.isNotEmpty()) {
            error("快捷键冲突：\n" + conflicts.joinToString("\n"))
        }
        map
    }

    /** 动作所属分组，用于帮助面板。 */
    fun categoryOf(action: ShortcutAction): ShortcutCategory = when (action) {
        ShortcutAction.OPEN, ShortcutAction.SAVE, ShortcutAction.CLOSE_TAB,
        ShortcutAction.CLOSE_ALL, ShortcutAction.QUIT -> ShortcutCategory.FILE

        ShortcutAction.UNDO, ShortcutAction.REDO,
        ShortcutAction.TOGGLE_BOOKMARK -> ShortcutCategory.EDIT

        ShortcutAction.FIND, ShortcutAction.FIND_REPLACE, ShortcutAction.FIND_NEXT,
        ShortcutAction.FIND_PREVIOUS, ShortcutAction.REPLACE_ALL -> ShortcutCategory.FIND

        ShortcutAction.TOGGLE_SYNTAX_HIGHLIGHT, ShortcutAction.TOGGLE_LINE_NUMBERS,
        ShortcutAction.TOGGLE_DETAILS_PANEL, ShortcutAction.TOGGLE_SEARCH_PANEL,
        ShortcutAction.TOGGLE_DIFF_PANEL -> ShortcutCategory.VIEW

        ShortcutAction.NEXT_TAB, ShortcutAction.PREVIOUS_TAB,
        ShortcutAction.SHOW_HELP -> ShortcutCategory.APP
    }

    /** 查找组合对应的动作；没有绑定时返回 null。 */
    fun actionFor(shortcut: Shortcut): ShortcutAction? = reverse[shortcut]

    /** 动作的主显示键（菜单提示用），同一动作多键时取第一个。 */
    fun primaryLabel(action: ShortcutAction): String =
        bindings.firstOrNull { it.action == action }?.shortcut?.label() ?: ""

    /** 动作的全部绑定文本。 */
    fun allLabels(action: ShortcutAction): String =
        bindings.filter { it.action == action }.joinToString(" / ") { it.shortcut.label() }

    /** 按分组整理后的绑定，供帮助面板直接渲染。 */
    fun grouped(): List<Pair<ShortcutCategory, List<Pair<ShortcutAction, String>>>> =
        ShortcutCategory.entries.map { category ->
            category to bindings
                .filter { categoryOf(it.action) == category }
                .map { it.action to allLabels(it.action) }
                .distinctBy { it.first }
        }
}
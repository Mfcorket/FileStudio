package com.filestudio.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 编辑历史管理器：为单个文档维护撤销/重做栈。
 *
 * <p>设计要点：
 * <ul>
 *   <li>按内容快照（字符串）而非操作日志存储，实现简单、恢复确定，
 *       代价是内存与文档大小线性相关；大文件应配合 {@link #maxDepth} 限深</li>
 *   <li>连续未修改状态不会重复入栈（内容相同的快照被跳过）</li>
 *   <li>任一撤销后的新编辑会清空重做栈（标准编辑器语义）</li>
 *   <li>{@link #pushCoalesced(String)} 用于把高频操作（逐字输入）合并为一次撤销点</li>
 * </ul>
 */
public final class HistoryManager {

    /** 默认最大历史深度。 */
    public static final int DEFAULT_MAX_DEPTH = 128;

    private final int maxDepth;
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();

    /** 当前活动文档内容；{@code null} 表示尚未打开文档。 */
    private String current;

    /**
     * 构造历史管理器，使用 {@link #DEFAULT_MAX_DEPTH}。
     */
    public HistoryManager() {
        this(DEFAULT_MAX_DEPTH);
    }

    /**
     * 构造历史管理器。
     *
     * @param maxDepth 撤销栈最大深度，必须为正
     * @throws IllegalArgumentException 深度非正时抛出
     */
    public HistoryManager(int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive, got " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    /**
     * 记录当前内容为一个撤销点。
     *
     * @param content 新内容
     * @return 是否记录了新点（内容未变化时返回 false）
     */
    public boolean snapshot(String content) {
        return push(content, /*clearRedo=*/ true);
    }

    /**
     * 合并快照：不清空重做栈，用于聚合高频编辑为单一撤销点。
     *
     * @param content 新内容
     * @return 是否记录了新点（内容未变化时返回 false）
     */
    public boolean pushCoalesced(String content) {
        return push(content, false);
    }

    private boolean push(String content, boolean clearRedo) {
        if (current != null && current.equals(content)) {
            return false; // 内容未变，不产生新撤销点
        }
        if (current != null) {
            undoStack.push(current);
            if (undoStack.size() > maxDepth) {
                undoStack.removeLast();
            }
        }
        if (clearRedo) {
            redoStack.clear();
        }
        current = content;
        return true;
    }

    /**
     * 撤销一步。
     *
     * @return 恢复后的内容
     * @throws IllegalStateException 无可撤销内容时抛出
     */
    public String undo() {
        if (undoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to undo");
        }
        redoStack.push(current);
        current = undoStack.pop();
        return current;
    }

    /**
     * 重做一步。
     *
     * @return 恢复后的内容
     * @throws IllegalStateException 无可重做内容时抛出
     */
    public String redo() {
        if (redoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to redo");
        }
        undoStack.push(current);
        current = redoStack.pop();
        return current;
    }

    /**
     * 是否可撤销。
     *
     * @return 撤销栈非空时为 true
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * 是否可重做。
     *
     * @return 重做栈非空时为 true
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * 当前活动文档内容。
     *
     * @return 当前内容；尚未打开文档时为 {@code null}
     */
    public String getCurrent() {
        return current;
    }

    /**
     * 撤销栈深度。
     *
     * @return 可撤销步数
     */
    public int getUndoDepth() {
        return undoStack.size();
    }

    /**
     * 重做栈深度。
     *
     * @return 可重做步数
     */
    public int getRedoDepth() {
        return redoStack.size();
    }

    /**
     * 撤销栈容量上限。
     *
     * @return 最大深度
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** 清空全部历史。 */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        current = null;
    }

    /**
     * 所有可撤销状态。仅用于调试 / 测试。
     *
     * @return 可变列表，元素顺序为栈顶在前
     */
    public List<String> snapshotStates() {
        return new ArrayList<>(undoStack);
    }

    /**
     * 只读地查看历史规模，不拷贝内容。
     *
     * @return 历史规模快照
     */
    public HistoryStats stats() {
        return new HistoryStats(undoStack.size(), redoStack.size(), current, maxDepth);
    }

    /**
     * 历史规模快照。不可变。
     *
     * @param undoDepth 撤销栈深度
     * @param redoDepth 重做栈深度
     * @param current   当前内容，尚未打开文档时为 {@code null}
     * @param maxDepth  撤销栈容量上限
     */
    public record HistoryStats(int undoDepth, int redoDepth, String current, int maxDepth) {
        /**
         * 历史是否完全为空。
         *
         * @return 无撤销、无重做且无当前内容时为 true
         */
        public boolean isEmpty() {
            return undoDepth == 0 && redoDepth == 0 && current == null;
        }
    }
}

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

    public HistoryManager() {
        this(DEFAULT_MAX_DEPTH);
    }

    public HistoryManager(int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive, got " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    /** 记录当前内容为一个撤销点。返回是否记录了新点（内容未变化时返回 false）。 */
    public boolean snapshot(String content) {
        return push(content, /*clearRedo=*/ true);
    }

    /** 合并快照：不清空重做栈，用于聚合高频编辑为单一撤销点。 */
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

    /** 撤销一步，返回恢复后的内容；无可撤销时抛出 {@link IllegalStateException}。 */
    public String undo() {
        if (undoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to undo");
        }
        redoStack.push(current);
        current = undoStack.pop();
        return current;
    }

    /** 重做一步，返回内容；无可重做时抛出 {@link IllegalStateException}。 */
    public String redo() {
        if (redoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to redo");
        }
        undoStack.push(current);
        current = redoStack.pop();
        return current;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** 当前活动文档内容。 */
    public String getCurrent() {
        return current;
    }

    public int getUndoDepth() {
        return undoStack.size();
    }

    public int getRedoDepth() {
        return redoStack.size();
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    /** 清空全部历史。 */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        current = null;
    }

    /** 所有可撤销状态（栈底为最早）。仅用于调试/测试。 */
    public List<String> snapshotStates() {
        return new ArrayList<>(undoStack);
    }

    /** 只读地查看历史规模，不拷贝内容。 */
    public HistoryStats stats() {
        return new HistoryStats(undoStack.size(), redoStack.size(), current, maxDepth);
    }

    public record HistoryStats(int undoDepth, int redoDepth, String current, int maxDepth) {
        public boolean isEmpty() {
            return undoDepth == 0 && redoDepth == 0 && current == null;
        }
    }
}

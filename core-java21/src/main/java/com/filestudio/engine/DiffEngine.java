package com.filestudio.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 行级 diff 引擎：比较两个文本，生成差异行列表。
 *
 * <p>基于最长公共子序列（LCS）算法，时间复杂度 O(m*n)，空间复杂度 O(m*n)。
 * 适用于小到中等大小的文本对比。
 *
 * <p>差异行类型：
 * <ul>
 *   <li>{@link Type#EQUAL} — 两文本相同的行</li>
 *   <li>{@link Type#INSERT} — 仅存在于新文本的行</li>
 *   <li>{@link Type#DELETE} — 仅存在于旧文本的行</li>
 * </ul>
 */
public final class DiffEngine {

    /** 差异行类型。 */
    public enum Type {
        /** 两文本相同的行。 */
        EQUAL,
        /** 仅存在于新文本的行。 */
        INSERT,
        /** 仅存在于旧文本的行。 */
        DELETE
    }

    private DiffEngine() {}

    /**
     * 比较两个文本，返回 diff 结果。
     *
     * @param oldText 旧文本，{@code null} 视为空串
     * @param newText 新文本，{@code null} 视为空串
     * @return diff 结果，含差异行与统计
     */
    public static DiffResult diff(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        // 空文本特殊处理：避免 split 产生多余的空字符串行
        if (oldText.isEmpty() && newText.isEmpty()) {
            return new DiffResult(List.of());
        }
        if (oldText.isEmpty()) {
            String[] newLines = newText.split("\n", -1);
            List<DiffLine> lines = new ArrayList<>();
            for (int j = 0; j < newLines.length; j++) {
                lines.add(new DiffLine(Type.INSERT, newLines[j], -1, j + 1));
            }
            return new DiffResult(lines);
        }
        if (newText.isEmpty()) {
            String[] oldLines = oldText.split("\n", -1);
            List<DiffLine> lines = new ArrayList<>();
            for (int i = 0; i < oldLines.length; i++) {
                lines.add(new DiffLine(Type.DELETE, oldLines[i], i + 1, -1));
            }
            return new DiffResult(lines);
        }

        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        int m = oldLines.length;
        int n = newLines.length;

        // LCS 动态规划表
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // 回溯生成 diff
        List<DiffLine> lines = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                lines.add(new DiffLine(Type.EQUAL, oldLines[i - 1], i, j));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                lines.add(new DiffLine(Type.INSERT, newLines[j - 1], -1, j));
                j--;
            } else {
                lines.add(new DiffLine(Type.DELETE, oldLines[i - 1], i, -1));
                i--;
            }
        }

        // 反转为正序
        java.util.Collections.reverse(lines);
        return new DiffResult(lines);
    }

    /** diff 结果。不可变。 */
    public static final class DiffResult {
        private final List<DiffLine> lines;
        private final int addedCount;
        private final int removedCount;
        private final int unchangedCount;

        private DiffResult(List<DiffLine> lines) {
            int added = 0, removed = 0, unchanged = 0;
            for (DiffLine line : lines) {
                switch (line.type()) {
                    case INSERT -> added++;
                    case DELETE -> removed++;
                    case EQUAL -> unchanged++;
                }
            }
            this.lines = List.copyOf(lines);
            this.addedCount = added;
            this.removedCount = removed;
            this.unchangedCount = unchanged;
        }

        /**
         * 差异行列表（正序）。
         *
         * @return 不可变差异行列表
         */
        public List<DiffLine> lines() {
            return lines;
        }

        /**
         * 新增行数。
         *
         * @return INSERT 行数
         */
        public int addedCount() {
            return addedCount;
        }

        /**
         * 删除行数。
         *
         * @return DELETE 行数
         */
        public int removedCount() {
            return removedCount;
        }

        /**
         * 未变行数。
         *
         * @return EQUAL 行数
         */
        public int unchangedCount() {
            return unchangedCount;
        }

        /**
         * 两文本是否完全相同。
         *
         * @return 无新增且无删除行时为 true
         */
        public boolean isEmpty() {
            return addedCount == 0 && removedCount == 0;
        }

        /**
         * 生成统一的 diff 格式（类似 unified diff 的简化版）。
         *
         * @return 每行以两个空格（未变）、{@code "+ "}（新增）或 {@code "- "}（删除）开头
         */
        public String toUnifiedFormat() {
            StringBuilder sb = new StringBuilder();
            for (DiffLine line : lines) {
                switch (line.type()) {
                    case EQUAL -> sb.append("  ").append(line.content()).append('\n');
                    case INSERT -> sb.append("+ ").append(line.content()).append('\n');
                    case DELETE -> sb.append("- ").append(line.content()).append('\n');
                }
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "DiffResult{added=" + addedCount + ", removed=" + removedCount
                    + ", unchanged=" + unchangedCount + '}';
        }
    }

    /** 单个差异行。不可变。
     *
     * @param type          差异类型
     * @param content       行内容
     * @param oldLineNumber 旧文本行号（1-based），该行不存在时为 -1
     * @param newLineNumber 新文本行号（1-based），该行不存在时为 -1
     */
    public record DiffLine(Type type, String content, int oldLineNumber, int newLineNumber) {

        /** 紧凑构造器：校验类型、内容非空且行号不小于 -1。 */
        public DiffLine {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(content, "content");
            if (oldLineNumber < -1 || newLineNumber < -1) {
                throw new IllegalArgumentException("line numbers must be >= -1");
            }
        }

        /**
         * 是否为新增行。
         *
         * @return true 表示 INSERT
         */
        public boolean isInsert() {
            return type == Type.INSERT;
        }

        /**
         * 是否为删除行。
         *
         * @return true 表示 DELETE
         */
        public boolean isDelete() {
            return type == Type.DELETE;
        }

        /**
         * 是否为未变行。
         *
         * @return true 表示 EQUAL
         */
        public boolean isEqual() {
            return type == Type.EQUAL;
        }

        @Override
        public String toString() {
            return type + "(" + (oldLineNumber >= 0 ? oldLineNumber : newLineNumber)
                    + ") " + content;
        }
    }
}

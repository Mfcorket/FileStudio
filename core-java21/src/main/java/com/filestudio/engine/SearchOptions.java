package com.filestudio.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索选项。所有布尔项均有默认值，用 {@link #defaultOptions()} 获取。
 */
public final class SearchOptions {

    private final boolean caseSensitive;
    private final boolean wholeWord;
    private final boolean useRegex;
    private final boolean forward;

    /**
     * 构造搜索选项。
     *
     * @param caseSensitive 是否区分大小写
     * @param wholeWord     是否仅匹配完整单词
     * @param useRegex      是否将查询视为正则表达式
     * @param forward       是否正向搜索（反向时为 false）
     */
    public SearchOptions(boolean caseSensitive, boolean wholeWord, boolean useRegex, boolean forward) {
        this.caseSensitive = caseSensitive;
        this.wholeWord = wholeWord;
        this.useRegex = useRegex;
        this.forward = forward;
    }

    /**
     * 默认选项：不区分大小写、非全词、非正则、正向。
     *
     * @return 默认选项实例
     */
    public static SearchOptions defaultOptions() {
        return new SearchOptions(false, false, false, true);
    }

    /**
     * 是否区分大小写。
     *
     * @return 区分大小写时为 true
     */
    public boolean isCaseSensitive() { return caseSensitive; }

    /**
     * 是否仅匹配完整单词。
     *
     * @return 启用全词匹配时为 true
     */
    public boolean isWholeWord() { return wholeWord; }

    /**
     * 是否将查询视为正则表达式。
     *
     * @return 启用正则模式时为 true
     */
    public boolean isUseRegex() { return useRegex; }

    /**
     * 是否正向搜索。
     *
     * @return 正向时为 true，反向为 false
     */
    public boolean isForward() { return forward; }
}

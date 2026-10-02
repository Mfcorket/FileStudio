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

    public SearchOptions(boolean caseSensitive, boolean wholeWord, boolean useRegex, boolean forward) {
        this.caseSensitive = caseSensitive;
        this.wholeWord = wholeWord;
        this.useRegex = useRegex;
        this.forward = forward;
    }

    public static SearchOptions defaultOptions() {
        return new SearchOptions(false, false, false, true);
    }

    public boolean isCaseSensitive() { return caseSensitive; }
    public boolean isWholeWord() { return wholeWord; }
    public boolean isUseRegex() { return useRegex; }
    public boolean isForward() { return forward; }
}

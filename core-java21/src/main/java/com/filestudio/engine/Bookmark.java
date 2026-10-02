package com.filestudio.engine;

import java.util.Objects;

/**
 * 书签：文档中某行的标记，带标签与创建时间。
 *
 * <p>不可变值类型。按行号（0-based）锚定；行号在文档编辑后可能失效，
 * 由 {@link BookmarkManager} 在使用时根据当前文档重新校验。
 *
 * @param line             锚定行号，0-based，必须非负
 * @param label            书签标签，必须非空
 * @param createdAtMillis  创建时间（epoch 毫秒），必须非负
 */
public record Bookmark(int line, String label, long createdAtMillis) {

    /** 紧凑构造器：校验行号、标签与时间戳的合法性。 */
    public Bookmark {
        if (line < 0) {
            throw new IllegalArgumentException("line must be >= 0, got " + line);
        }
        Objects.requireNonNull(label, "label");
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must be non-empty");
        }
        if (createdAtMillis < 0) {
            throw new IllegalArgumentException("createdAtMillis must be >= 0");
        }
    }

    /**
     * 以当前时间创建书签。
     *
     * @param line  锚定行号，0-based
     * @param label 书签标签
     * @return 新建的书签
     */
    public static Bookmark of(int line, String label) {
        return new Bookmark(line, label, System.currentTimeMillis());
    }

    /**
     * 仅行号 + 标签，创建时间归零（用于反序列化）。
     *
     * @param line             锚定行号，0-based
     * @param label            书签标签
     * @param createdAtMillis  创建时间（epoch 毫秒）
     * @return 新建的书签
     */
    public static Bookmark of(int line, String label, long createdAtMillis) {
        return new Bookmark(line, label, createdAtMillis);
    }

    @Override
    public String toString() {
        return "Bookmark{line=" + line + ", label='" + label + "', at=" + createdAtMillis + "}";
    }
}

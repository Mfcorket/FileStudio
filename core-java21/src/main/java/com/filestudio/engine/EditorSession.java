package com.filestudio.engine;

import com.filestudio.core.Document;

import java.io.File;
import java.util.Objects;

/**
 * 单个打开的文档会话：将文档、编辑器引擎、书签与派生索引（行索引/统计）捆绑。
 *
 * <p>由 {@link EditorSessionManager} 创建与管理。一个会话对应一个打开的文件（标签页）。
 * 会话自身的编辑操作委托给 {@link EditorEngine}，书签委托给 {@link BookmarkManager}。
 *
 * <p>线程安全：非线程安全，应在 UI 线程内串行使用。
 */
public final class EditorSession {

    private final File file;
    private final EditorEngine editor;
    private final BookmarkManager bookmarks;

    private boolean closed;

    EditorSession(File file, EditorEngine editor, BookmarkManager bookmarks) {
        this.file = Objects.requireNonNull(file, "file");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.bookmarks = Objects.requireNonNull(bookmarks, "bookmarks");
        this.bookmarks.load(file);
    }

    /**
     * 会话对应的文件。
     *
     * @return 文件对象
     */
    public File getFile() {
        return file;
    }

    /**
     * 规范化后的路径标识（用于会话查找）。
     *
     * @return 规范化路径键
     */
    public String getKey() {
        return normalize(file.getAbsolutePath());
    }

    /**
     * 绝对路径。
     *
     * @return 文件绝对路径
     */
    public String getPath() {
        return file.getAbsolutePath();
    }

    /**
     * 文件名（含扩展名）。
     *
     * @return 文件名
     */
    public String getFileName() {
        return file.getName();
    }

    /**
     * 编辑器引擎。
     *
     * @return 编辑器引擎实例
     */
    public EditorEngine getEditor() {
        return editor;
    }

    /**
     * 书签管理器。
     *
     * @return 书签管理器实例
     */
    public BookmarkManager getBookmarks() {
        return bookmarks;
    }

    /**
     * 当前文档模型（始终反映编辑器当前内容）。
     *
     * @return 文档实例
     */
    public Document getDocument() {
        return editor.getDocument();
    }

    /**
     * 当前内容的行索引（每次调用按需重建，反映最新编辑）。
     *
     * @return 行索引实例
     */
    public LineIndex lineIndex() {
        return LineIndex.of(editor.getContent());
    }

    /**
     * 当前内容的统计信息。
     *
     * @return 统计结果
     */
    public DocumentStats stats() {
        return DocumentStats.of(editor.getContent());
    }

    /**
     * 内容是否已被修改且未保存。
     *
     * @return 存在未保存修改时为 true
     */
    public boolean isModified() {
        return editor.isModified();
    }

    /** 保存当前内容到会话文件，并持久化书签。 */
    public void save() {
        editor.save(file);
        bookmarks.save(file);
    }

    /**
     * 是否已关闭。
     *
     * @return 会话已关闭时为 true
     */
    public boolean isClosed() {
        return closed;
    }

    /** 关闭会话：持久化书签并释放引用。 */
    public void close() {
        if (closed) return;
        bookmarks.save(file);
        closed = true;
    }

    /** 路径规范化：统一大小写与分隔符，便于跨平台会话键匹配。 */
    static String normalize(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').toLowerCase();
    }
}

package com.filestudio.engine;

import com.filestudio.core.Document;
import com.filestudio.core.FileStudioException;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多标签会话管理器：管理所有打开的文档会话，并维护"当前活动"会话。
 *
 * <p>对应 Phase 1 的标签页管理需求。键为规范化路径，同一文件多次打开会返回既有会话。
 * 线程安全：非线程安全，应在 UI 线程内串行调用。
 */
public final class EditorSessionManager {

    private final DocumentParser parser;
    private final Map<String, EditorSession> sessions = new LinkedHashMap<>();
    private String activeKey;

    public EditorSessionManager(DocumentParser parser) {
        this.parser = parser;
    }

    /** 打开文件并创建会话；已打开则返回既有会话。 */
    public EditorSession open(File file) {
        if (file == null) throw new IllegalArgumentException("file is null");
        String key = EditorSession.normalize(file.getAbsolutePath());
        EditorSession existing = sessions.get(key);
        if (existing != null && !existing.isClosed()) {
            activeKey = key;
            return existing;
        }
        Document doc = parser.parse(file);
        EditorEngine editor = new EditorEngine(parser);
        editor.open(doc);
        EditorSession session = new EditorSession(file, editor, new BookmarkManager());
        sessions.put(key, session);
        activeKey = key;
        return session;
    }

    /** 按路径查找会话（规范化后）。 */
    public Optional<EditorSession> get(String path) {
        return Optional.ofNullable(sessions.get(EditorSession.normalize(path)));
    }

    public Optional<EditorSession> get(File file) {
        return file == null ? Optional.empty() : get(file.getAbsolutePath());
    }

    /** 当前活动会话。 */
    public Optional<EditorSession> active() {
        if (activeKey == null) return Optional.empty();
        EditorSession s = sessions.get(activeKey);
        return s != null && !s.isClosed() ? Optional.of(s) : Optional.empty();
    }

    /** 设置活动会话。会话不存在或已关闭时忽略。 */
    public boolean setActive(String path) {
        EditorSession s = sessions.get(EditorSession.normalize(path));
        if (s == null || s.isClosed()) return false;
        activeKey = EditorSession.normalize(path);
        return true;
    }

    /** 设置活动会话（File 重载）。 */
    public boolean setActive(File file) {
        return file != null && setActive(file.getAbsolutePath());
    }

    /** 所有打开会话（按打开顺序）。 */
    public List<EditorSession> list() {
        List<EditorSession> result = new ArrayList<>();
        for (EditorSession s : sessions.values()) {
            if (!s.isClosed()) result.add(s);
        }
        return List.copyOf(result);
    }

    /** 打开会话数量。 */
    public int size() {
        int n = 0;
        for (EditorSession s : sessions.values()) {
            if (!s.isClosed()) n++;
        }
        return n;
    }

    /** 是否已打开指定路径。 */
    public boolean isOpen(String path) {
        EditorSession s = sessions.get(EditorSession.normalize(path));
        return s != null && !s.isClosed();
    }

    /** 关闭指定会话。返回是否成功。 */
    public boolean close(String path) {
        EditorSession s = sessions.remove(EditorSession.normalize(path));
        if (s == null || s.isClosed()) return false;
        s.close();
        if (EditorSession.normalize(path).equals(activeKey)) {
            activeKey = sessions.isEmpty() ? null : sessions.keySet().iterator().next();
        }
        return true;
    }

    public boolean close(File file) {
        return file != null && close(file.getAbsolutePath());
    }

    /** 关闭全部会话。 */
    public void closeAll() {
        for (EditorSession s : sessions.values()) {
            s.close();
        }
        sessions.clear();
        activeKey = null;
    }

    /** 是否所有会话都已保存（无未保存修改）。 */
    public boolean hasUnsavedChanges() {
        for (EditorSession s : sessions.values()) {
            if (!s.isClosed() && s.isModified()) return true;
        }
        return false;
    }

    /** 由解析异常返回的文件打开失败信息。 */
    public static String describeError(File file, FileStudioException e) {
        return "Failed to open " + file.getName() + ": " + e.getMessage();
    }
}

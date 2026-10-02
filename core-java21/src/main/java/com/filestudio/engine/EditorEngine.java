package com.filestudio.engine;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 编辑器引擎：在统一 {@link Document} 之上提供可编辑、可撤销、可保存的能力。
 *
 * <p>对应规划文档 {@code EditorEngine} 组件。它不关心 UI，只负责文档状态机：
 * <ul>
 *   <li>打开文档 / 新建文档</li>
 *   <li>整段替换、区间替换、插入、删除（均产生撤销点）</li>
 *   <li>撤销 / 重做</li>
 *   <li>保存（经 {@link DocumentParser} 调用对应 {@code FileHandler.render}）</li>
 *   <li>修改状态跟踪（与上次打开/保存的内容比对）</li>
 * </ul>
 *
 * <p>线程安全：非线程安全，应在单一编辑会话内串行调用（UI 层负责调度）。
 */
public final class EditorEngine {

    private final DocumentParser parser;
    private final HistoryManager history;

    /** 基准内容：上次打开或保存时的内容，用于判定是否已修改。 */
    private String baseline;
    private Document document;

    public EditorEngine(DocumentParser parser) {
        this(parser, new HistoryManager());
    }

    public EditorEngine(DocumentParser parser, HistoryManager history) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.history = Objects.requireNonNull(history, "history");
    }

    /** 从已解析的文档打开编辑会话。 */
    public void open(Document doc) {
        Objects.requireNonNull(doc, "doc");
        this.document = doc;
        this.baseline = doc.getContent();
        history.clear();
        // 初始状态不作为可撤销点，首次编辑后才会产生
        history.pushCoalesced(this.baseline);
    }

    /** 以纯内容新建一个编辑会话（未关联文件路径）。 */
    public void openContent(String content, String mimeType, String extension) {
        Document doc = new Document(null, mimeType, extension, content, java.util.Map.of(),
                content == null ? 0 : content.length(), EditCapability.FULL);
        open(doc);
    }

    /** 当前文档内容。未打开文档时为 null。 */
    public String getContent() {
        return history.getCurrent();
    }

    public Document getDocument() {
        return document;
    }

    /** 整段替换内容。记录一个撤销点。 */
    public void edit(String newContent) {
        requireOpen();
        String safe = newContent == null ? "" : newContent;
        ensureWritable();
        history.snapshot(safe);
        commit(safe);
    }

    /** 区间替换：[start, end) 的内容替换为 replacement。 */
    public void replaceRange(int start, int end, String replacement) {
        requireOpen();
        ensureWritable();
        String src = history.getCurrent();
        int len = src.length();
        int s = clampIndex(start, len);
        int e = clampIndex(end, len);
        if (s > e) {
            throw new IllegalArgumentException("start (" + start + ") must be <= end (" + end + ")");
        }
        if (s == e && (replacement == null || replacement.isEmpty())) {
            return; // no-op
        }
        String result = src.substring(0, s) + (replacement == null ? "" : replacement) + src.substring(e);
        history.snapshot(result);
        commit(result);
    }

    /** 在指定位置插入文本。 */
    public void insertAt(int position, String text) {
        if (text == null || text.isEmpty()) return;
        replaceRange(position, position, text);
    }

    /** 删除指定区间。 */
    public void deleteRange(int start, int end) {
        replaceRange(start, end, "");
    }

    public String undo() {
        requireOpen();
        String restored = history.undo();
        commit(restored);
        return restored;
    }

    public String redo() {
        requireOpen();
        String restored = history.redo();
        commit(restored);
        return restored;
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /** 文档是否相对基准（上次打开/保存）被修改。 */
    public boolean isModified() {
        String cur = history.getCurrent();
        return baseline != null && !baseline.equals(cur);
    }

    /** 把当前内容落盘，并更新基准内容。 */
    public void save(String path) {
        save(new File(path));
    }

    public void save(File output) {
        requireOpen();
        ensureWritable();
        Document current = getDocument();
        parser.save(current, output);
        this.document = current.withFilePath(output.toPath());
        this.baseline = history.getCurrent();
    }

    public void save(Path path) {
        save(path.toFile());
    }

    /** 保存后调用以标记未修改（不调用 save 时也可手动重置基准）。 */
    public void markSaved() {
        this.baseline = history.getCurrent();
    }

    public HistoryManager history() {
        return history;
    }

    /** 当前文档是否为可编辑能力。 */
    public boolean isWritable() {
        return document != null && document.getEditCapability() == EditCapability.FULL;
    }

    // ---- internals ----

    private void commit(String content) {
        Document base = document;
        document = new Document(base.getFilePath(), base.getMimeType(), base.getExtension(),
                content, base.getMetadata(), content.length(), base.getEditCapability());
    }

    private void requireOpen() {
        if (document == null) {
            throw new IllegalStateException("No document open; call open() first");
        }
    }

    private void ensureWritable() {
        if (document.getEditCapability() != EditCapability.FULL) {
            throw new FileStudioException("Document is not fully editable (capability="
                    + document.getEditCapability() + ")");
        }
    }

    private static int clampIndex(int index, int len) {
        if (index < 0) return 0;
        if (index > len) return len;
        return index;
    }
}

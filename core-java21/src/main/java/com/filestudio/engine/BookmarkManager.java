package com.filestudio.engine;

import com.filestudio.core.FileStudioException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 书签管理器：为单个文档维护按行号锚定的书签集合。
 *
 * <p>线程安全：非线程安全，应在单一编辑会话内串行调用。
 *
 * <p>持久化采用旁挂文件（sidecar）：
 * {@code <文档路径>.bookmarks}，每行格式
 * {@code <line>\t<createdAtMillis>\t<label>}。
 * 标签为最后一个字段，因此标签内含 {@code \t} 也不会破坏解析。
 *
 * <p>行号锚定：文档编辑后行号可能失效，UI 层应在渲染书签前
 * 调用 {@link #filterValid(int)} 过滤越界书签。
 */
public final class BookmarkManager {

    private final SortedSet<Bookmark> bookmarks = new TreeSet<>(
            Comparator.comparingInt(Bookmark::line).thenComparingLong(Bookmark::createdAtMillis));

    /** 创建空的书签管理器。 */
    public BookmarkManager() {
    }

    /**
     * 文档文件对应的书签旁挂文件。
     *
     * @param document 文档文件
     * @return 旁挂文件路径，命名为 {@code <name><ext>.bookmarks}
     */
    public static File sidecarFile(File document) {
        java.util.Objects.requireNonNull(document, "document");
        String name = document.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        return new File(document.getParentFile(), base + ext + ".bookmarks");
    }

    /**
     * 添加书签。若该行已有书签则替换其标签。
     *
     * @param line  锚定行号，0-based
     * @param label 书签标签，必须非空
     * @return 新建的书签
     * @throws IllegalArgumentException 行号为负时抛出
     */
    public Bookmark add(int line, String label) {
        if (line < 0) throw new IllegalArgumentException("line must be >= 0");
        bookmarks.stream().filter(b -> b.line() == line).findFirst().ifPresent(bookmarks::remove);
        Bookmark b = Bookmark.of(line, label);
        bookmarks.add(b);
        return b;
    }

    /**
     * 按行号移除书签。
     *
     * @param line 锚定行号，0-based
     * @return 是否移除成功
     */
    public boolean remove(int line) {
        Bookmark[] arr = bookmarks.stream().filter(b -> b.line() == line).toArray(Bookmark[]::new);
        if (arr.length == 0) return false;
        bookmarks.remove(arr[0]);
        return true;
    }

    /**
     * 查询该行的书签。
     *
     * @param line 锚定行号，0-based
     * @return 该行书签，不存在时为 {@link Optional#empty()}
     */
    public Optional<Bookmark> find(int line) {
        return bookmarks.stream().filter(b -> b.line() == line).findFirst();
    }

    /**
     * 该行是否有书签。
     *
     * @param line 锚定行号，0-based
     * @return 存在书签时为 true
     */
    public boolean has(int line) {
        return find(line).isPresent();
    }

    /**
     * 所有书签，按行号升序。
     *
     * @return 不可变列表快照
     */
    public List<Bookmark> list() {
        return List.copyOf(bookmarks);
    }

    /**
     * 过滤出行号在范围内的书签（含）。用于文档缩短后清理越界书签。
     *
     * @param maxLine 允许的最大行号（含）
     * @return 行号未越界的书签
     */
    public List<Bookmark> filterValid(int maxLine) {
        List<Bookmark> valid = new ArrayList<>();
        for (Bookmark b : bookmarks) {
            if (b.line() <= maxLine) valid.add(b);
        }
        return valid;
    }

    /** 清空所有书签。 */
    public void clear() {
        bookmarks.clear();
    }

    /**
     * 当前书签数量。
     *
     * @return 书签数
     */
    public int size() {
        return bookmarks.size();
    }

    /**
     * 持久化到文档的旁挂文件。
     *
     * @param document 文档文件
     * @throws com.filestudio.core.FileStudioException 写入失败时抛出
     */
    public void save(File document) {
        java.util.Objects.requireNonNull(document, "document");
        File sidecar = sidecarFile(document);
        try (BufferedWriter w = Files.newBufferedWriter(sidecar.toPath(), StandardCharsets.UTF_8)) {
            for (Bookmark b : bookmarks) {
                w.write(String.valueOf(b.line()));
                w.write('\t');
                w.write(String.valueOf(b.createdAtMillis()));
                w.write('\t');
                w.write(b.label());
                w.write('\n');
            }
        } catch (IOException e) {
            throw new FileStudioException("Failed writing bookmarks to " + sidecar, e);
        }
    }

    /**
     * 从文档的旁挂文件加载书签。文件不存在时保持空集合。
     *
     * @param document 文档文件
     * @throws com.filestudio.core.FileStudioException 读取失败时抛出
     */
    public void load(File document) {
        java.util.Objects.requireNonNull(document, "document");
        File sidecar = sidecarFile(document);
        if (!sidecar.isFile()) return;
        try (BufferedReader r = Files.newBufferedReader(sidecar.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                try {
                    int ln = Integer.parseInt(parts[0].trim());
                    long at = Long.parseLong(parts[1].trim());
                    String label = parts[2];
                    if (label.isEmpty()) label = "书签";
                    bookmarks.add(Bookmark.of(ln, label, at));
                } catch (NumberFormatException ignored) {
                    // 跳过格式错误的行
                }
            }
        } catch (IOException e) {
            throw new FileStudioException("Failed reading bookmarks from " + sidecar, e);
        }
    }

    /**
     * 返回所有书签行号集合（升序）。便于 UI 一次性渲染标记。
     *
     * @return 不可变行号集合
     */
    public Collection<Integer> bookmarkedLines() {
        List<Integer> lines = new ArrayList<>(bookmarks.size());
        for (Bookmark b : bookmarks) lines.add(b.line());
        return List.copyOf(lines);
    }
}

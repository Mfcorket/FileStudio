package com.filestudio.core;

import com.filestudio.engine.BookmarkManager;
import com.filestudio.engine.DocumentStats;
import com.filestudio.engine.EditorEngine;
import com.filestudio.engine.LineIndex;
import com.filestudio.engine.SearchReplace;
import com.filestudio.engine.SearchOptions;
import com.filestudio.engine.highlight.SyntaxHighlighter;
import com.filestudio.engine.highlight.SyntaxLanguageRegistry;
import com.filestudio.engine.highlight.Token;
import com.filestudio.engine.highlight.TokenKind;

import java.util.List;

/**
 * FileStudio 命令行演示入口。用于冒烟验证核心引擎可用性，
 * 后续由桌面/移动启动器接管入口职责。
 *
 * <p>不带参数时运行内置自演示；带一个文件路径参数时解析该文件。
 */
public final class FileStudioMain {

    /**
     * 创建命令行演示入口实例。
     */
    public FileStudioMain() {}

    /**
     * 命令行演示入口。
     *
     * <p>无参数时运行内置功能演示（撤销/重做、高亮、搜索替换、格式处理器、
     * 行索引、统计、书签）；传入文件路径时解析并打印该文件。
     *
     * @param args 可选的单个参数：要解析的文件路径
     */
    public static void main(String[] args) {
        FileStudioCore core = new FileStudioCore();
        core.init();
        try {
            System.out.println("=== FileStudio core initialized ===");
            System.out.println("Registered handlers:");
            core.listHandlers().forEach(i ->
                    System.out.println("  - " + i.extension()
                            + " (" + i.mimeType() + ") " + i.editCapability()
                            + " via " + i.pluginName() + " v" + i.pluginVersion()));

            if (args.length > 0) {
                Document doc = core.parseFile(args[0]);
                System.out.println("\nParsed " + args[0] + " -> " + doc);
                System.out.println("Metadata: " + doc.getMetadata());
                String preview = doc.getContent();
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "...";
                }
                System.out.println("Preview:\n" + preview);
            } else {
                runSelfDemo(core);
            }
        } finally {
            core.shutdown();
        }
    }

    private static void runSelfDemo(FileStudioCore core) {
        System.out.println("\n=== Editor engine (undo/redo) ===");
        EditorEngine ed = core.newEditor();
        ed.openContent("Hello, world!", "text/plain", "txt");
        ed.edit("Hello, FileStudio!");
        ed.edit("Hello, FileStudio v0.1!");
        System.out.println("After 2 edits: " + ed.getContent() + " (undoDepth=" + ed.history().getUndoDepth() + ")");
        ed.undo();
        System.out.println("After undo:    " + ed.getContent());
        ed.undo();
        System.out.println("After undo:    " + ed.getContent());
        ed.redo();
        System.out.println("After redo:    " + ed.getContent());

        System.out.println("\n=== Syntax highlighting ===");
        SyntaxHighlighter hl = SyntaxLanguageRegistry.forPath("Sample.java");
        String code = "public class Sample {\n    // greeting\n    String s = \"hi\"; int n = 42;\n}";
        List<Token> tokens = hl.tokenize(code);
        long kw = tokens.stream().filter(t -> t.kind() == TokenKind.KEYWORD).count();
        long str = tokens.stream().filter(t -> t.kind() == TokenKind.STRING).count();
        long com = tokens.stream().filter(t -> t.kind() == TokenKind.COMMENT).count();
        System.out.println("Profile: " + hl.getProfile().id + " tokens=" + tokens.size()
                + " keywords=" + kw + " strings=" + str + " comments=" + com);

        System.out.println("\n=== Search / replace ===");
        String text = "foo bar foo baz foo";
        System.out.println("Source:  " + text);
        List<SearchReplace.SearchMatch> matches =
                SearchReplace.findMatches(text, "foo", SearchOptions.defaultOptions());
        System.out.println("Matches 'foo': " + matches.size() + " at "
                + matches.stream().map(m -> m.start() + "-" + m.end()).toList());
        System.out.println("Replace: " + SearchReplace.replaceAll(text, "foo", "qux",
                SearchOptions.defaultOptions()));
        System.out.println("Regex:   " + SearchReplace.replaceAll("a1b22c333", "\\d+", "N",
                new SearchOptions(false, false, true, true)));

        System.out.println("\n=== Format handlers ===");
        // 直接演示各格式处理器的元数据提取
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fs_demo", ".json");
            java.nio.file.Files.writeString(tmp, "{\"name\":\"FileStudio\",\"tags\":[\"a\",\"b\"]}");
            Document d = core.parseFile(tmp.toString());
            System.out.println("JSON meta: " + d.getMetadata());
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception e) {
            System.out.println("JSON demo skipped: " + e.getMessage());
        }
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fs_demo", ".yaml");
            java.nio.file.Files.writeString(tmp, "name: FileStudio\ntags:\n  - java\n  - editor");
            Document d = core.parseFile(tmp.toString());
            System.out.println("YAML meta: " + d.getMetadata());
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception e) {
            System.out.println("YAML demo skipped: " + e.getMessage());
        }

        System.out.println("\n=== LineIndex (offset <-> line/col) ===");
        LineIndex idx = LineIndex.of("ab\ncd\nef");
        System.out.println("lines=" + idx.lineCount()
                + " offset(0,2)=" + idx.offsetOf(0, 2)
                + " -> line=" + idx.lineOf(2) + " col=" + idx.columnOf(2)
                + " line[1]='" + idx.lineContent(1) + "'");

        System.out.println("\n=== DocumentStats ===");
        DocumentStats stats = DocumentStats.of("one\ntwo\nthree");
        System.out.println(stats);

        System.out.println("\n=== BookmarkManager (sidecar persistence) ===");
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fs_demo", ".txt");
            java.nio.file.Files.writeString(tmp, "a\nb\nc\nd\ne");
            java.io.File doc = tmp.toFile();
            BookmarkManager bm = new BookmarkManager();
            bm.add(0, "start");
            bm.add(4, "end");
            bm.save(doc);
            BookmarkManager reloaded = new BookmarkManager();
            reloaded.load(doc);
            System.out.println("sidecar=" + BookmarkManager.sidecarFile(doc).getName()
                    + " loaded=" + reloaded.list());
            java.nio.file.Files.deleteIfExists(tmp);
            java.nio.file.Files.deleteIfExists(BookmarkManager.sidecarFile(doc).toPath());
        } catch (Exception e) {
            System.out.println("Bookmark demo skipped: " + e.getMessage());
        }
    }
}

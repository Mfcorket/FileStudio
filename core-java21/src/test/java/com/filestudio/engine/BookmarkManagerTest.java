package com.filestudio.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BookmarkManagerTest {

    @Test
    void addAndFind() {
        BookmarkManager bm = new BookmarkManager();
        Bookmark b = bm.add(5, "todo");
        assertEquals(5, b.line());
        assertEquals("todo", b.label());
        assertTrue(bm.has(5));
        assertTrue(bm.find(5).isPresent());
        assertEquals(1, bm.size());
    }

    @Test
    void addSameLineReplacesLabel() {
        BookmarkManager bm = new BookmarkManager();
        bm.add(3, "first");
        bm.add(3, "second");
        assertEquals(1, bm.size());
        assertEquals("second", bm.find(3).orElseThrow().label());
    }

    @Test
    void removeReturnsFalseWhenAbsent() {
        BookmarkManager bm = new BookmarkManager();
        assertFalse(bm.remove(99));
        bm.add(99, "x");
        assertTrue(bm.remove(99));
        assertFalse(bm.has(99));
        assertEquals(0, bm.size());
    }

    @Test
    void listIsSortedByLine() {
        BookmarkManager bm = new BookmarkManager();
        bm.add(10, "c");
        bm.add(1, "a");
        bm.add(5, "b");
        List<Bookmark> list = bm.list();
        assertEquals(List.of(1, 5, 10),
                list.stream().map(Bookmark::line).toList());
    }

    @Test
    void filterValidRemovesOutOfRange() {
        BookmarkManager bm = new BookmarkManager();
        bm.add(1, "a");
        bm.add(5, "b");
        bm.add(20, "c");
        List<Bookmark> valid = bm.filterValid(10);
        assertEquals(2, valid.size());
        // 原集合不变
        assertEquals(3, bm.size());
    }

    @Test
    void bookmarkedLinesReturnsSorted() {
        BookmarkManager bm = new BookmarkManager();
        bm.add(7, "x");
        bm.add(2, "y");
        bm.add(0, "z");
        assertEquals(List.of(0, 2, 7), List.copyOf(bm.bookmarkedLines()));
    }

    @Test
    void clearResetsAll() {
        BookmarkManager bm = new BookmarkManager();
        bm.add(1, "a");
        bm.add(2, "b");
        bm.clear();
        assertEquals(0, bm.size());
        assertFalse(bm.has(1));
    }

    @Test
    void negativeLineThrows() {
        BookmarkManager bm = new BookmarkManager();
        assertThrows(IllegalArgumentException.class, () -> bm.add(-1, "bad"));
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) throws IOException {
        File doc = dir.resolve("notes.txt").toFile();
        Files.writeString(doc.toPath(), "hello", StandardCharsets.UTF_8);

        BookmarkManager bm = new BookmarkManager();
        bm.add(0, "start");
        bm.add(3, "important");
        bm.save(doc);

        File sidecar = BookmarkManager.sidecarFile(doc);
        assertTrue(sidecar.exists());
        assertEquals("notes.txt.bookmarks", sidecar.getName());

        BookmarkManager loaded = new BookmarkManager();
        loaded.load(doc);
        assertEquals(2, loaded.size());
        assertEquals("start", loaded.find(0).orElseThrow().label());
        assertEquals("important", loaded.find(3).orElseThrow().label());
    }

    @Test
    void loadNonexistentFileLeavesEmpty(@TempDir Path dir) {
        File doc = dir.resolve("missing.txt").toFile();
        BookmarkManager bm = new BookmarkManager();
        assertDoesNotThrow(() -> bm.load(doc));
        assertEquals(0, bm.size());
    }

    @Test
    void labelWithTabsIsPreserved(@TempDir Path dir) throws IOException {
        File doc = dir.resolve("tab.txt").toFile();
        Files.writeString(doc.toPath(), "x", StandardCharsets.UTF_8);

        BookmarkManager bm = new BookmarkManager();
        bm.add(2, "has\ta\ttab");
        bm.save(doc);

        BookmarkManager loaded = new BookmarkManager();
        loaded.load(doc);
        assertEquals("has\ta\ttab", loaded.find(2).orElseThrow().label());
    }

    @Test
    void corruptSidecarLinesAreSkipped(@TempDir Path dir) throws IOException {
        File doc = dir.resolve("doc.txt").toFile();
        Files.writeString(doc.toPath(), "x", StandardCharsets.UTF_8);

        File sidecar = BookmarkManager.sidecarFile(doc);
        Files.writeString(sidecar.toPath(),
                "bad\n" +          // 缺字段
                "3\t999\tok\n" +   // 正常
                "notanumber\t999\tbad\n" + // 行号无效
                "4\t999\tgood\n",
                StandardCharsets.UTF_8);

        BookmarkManager bm = new BookmarkManager();
        bm.load(doc);
        assertEquals(2, bm.size());
        assertTrue(bm.has(3));
        assertTrue(bm.has(4));
    }

    @Test
    void bookmarkValidation() {
        assertThrows(IllegalArgumentException.class, () -> Bookmark.of(-1, "x"));
        assertThrows(IllegalArgumentException.class, () -> Bookmark.of(1, ""));
        assertThrows(NullPointerException.class, () -> Bookmark.of(1, null));
        assertThrows(IllegalArgumentException.class, () -> new Bookmark(1, "x", -1));
    }
}

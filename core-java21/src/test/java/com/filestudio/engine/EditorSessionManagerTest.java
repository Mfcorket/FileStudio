package com.filestudio.engine;

import com.filestudio.plugin.PluginManager;
import com.filestudio.plugin.handlers.TextFileHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EditorSessionManagerTest {

    private EditorSessionManager manager;
    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        PluginManager pm = new PluginManager();
        pm.registerHandler(new TextFileHandler());
        parser = new DocumentParser(pm);
        manager = new EditorSessionManager(parser);
    }

    @AfterEach
    void tearDown() {
        manager.closeAll();
    }

    @Test
    void opensSessionAndMakesItActive(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "hello", StandardCharsets.UTF_8);
        EditorSession s = manager.open(p.toFile());
        assertNotNull(s);
        assertEquals(1, manager.size());
        assertTrue(manager.active().isPresent());
        assertEquals(s.getPath(), manager.active().get().getPath());
    }

    @Test
    void openingSameFileTwiceReturnsSameSession(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "hello", StandardCharsets.UTF_8);
        EditorSession s1 = manager.open(p.toFile());
        EditorSession s2 = manager.open(p.toFile());
        assertSame(s1, s2);
        assertEquals(1, manager.size());
    }

    @Test
    void getByPathReturnsSession(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "hello", StandardCharsets.UTF_8);
        manager.open(p.toFile());
        assertTrue(manager.get(p.toString()).isPresent());
        assertTrue(manager.isOpen(p.toString()));
    }

    @Test
    void setActiveSwitchesSession(@TempDir Path dir) throws IOException {
        Path p1 = dir.resolve("a.txt");
        Path p2 = dir.resolve("b.txt");
        Files.writeString(p1, "a", StandardCharsets.UTF_8);
        Files.writeString(p2, "b", StandardCharsets.UTF_8);
        manager.open(p1.toFile());
        manager.open(p2.toFile());
        manager.setActive(p1.toString());
        assertEquals(p1.toAbsolutePath().normalize().toString(),
                manager.active().get().getPath());
    }

    @Test
    void closeRemovesSession(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "hello", StandardCharsets.UTF_8);
        manager.open(p.toFile());
        assertTrue(manager.close(p.toFile()));
        assertEquals(0, manager.size());
        assertTrue(manager.active().isEmpty());
        assertFalse(manager.close(p.toFile()));
    }

    @Test
    void editingSessionReflectsInDocument(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "original", StandardCharsets.UTF_8);
        EditorSession s = manager.open(p.toFile());
        s.getEditor().edit("edited");
        assertEquals("edited", s.getDocument().getContent());
        assertTrue(s.isModified());
    }

    @Test
    void lineIndexAndStatsReflectCurrentContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "one\ntwo\nthree", StandardCharsets.UTF_8);
        EditorSession s = manager.open(p.toFile());
        assertEquals(3, s.lineIndex().lineCount());
        assertEquals(3, s.stats().lineCount());
        assertEquals("two", s.lineIndex().lineContent(1));
    }

    @Test
    void bookmarksPersistAcrossReopen(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "one\ntwo\nthree", StandardCharsets.UTF_8);

        EditorSession s1 = manager.open(p.toFile());
        s1.getBookmarks().add(1, "mark");
        manager.close(p.toFile());
        assertFalse(s1.getBookmarks().list().isEmpty());

        EditorSession s2 = manager.open(p.toFile());
        assertTrue(s2.getBookmarks().has(1));
        assertEquals("mark", s2.getBookmarks().find(1).orElseThrow().label());
    }

    @Test
    void savePersistsContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.txt");
        Files.writeString(p, "original", StandardCharsets.UTF_8);
        EditorSession s = manager.open(p.toFile());
        s.getEditor().edit("saved content");
        s.save();
        assertFalse(s.isModified());
        assertEquals("saved content", Files.readString(p, StandardCharsets.UTF_8));
    }

    @Test
    void hasUnsavedChangesDetectsModifications(@TempDir Path dir) throws IOException {
        Path p1 = dir.resolve("a.txt");
        Path p2 = dir.resolve("b.txt");
        Files.writeString(p1, "a", StandardCharsets.UTF_8);
        Files.writeString(p2, "b", StandardCharsets.UTF_8);
        manager.open(p1.toFile());
        manager.open(p2.toFile());
        assertFalse(manager.hasUnsavedChanges());
        manager.get(p1.toString()).get().getEditor().edit("changed");
        assertTrue(manager.hasUnsavedChanges());
    }

    @Test
    void closeAllClosesEverySession(@TempDir Path dir) throws IOException {
        Path p1 = dir.resolve("a.txt");
        Path p2 = dir.resolve("b.txt");
        Files.writeString(p1, "a", StandardCharsets.UTF_8);
        Files.writeString(p2, "b", StandardCharsets.UTF_8);
        manager.open(p1.toFile());
        manager.open(p2.toFile());
        manager.closeAll();
        assertEquals(0, manager.size());
        assertTrue(manager.active().isEmpty());
        assertFalse(manager.hasUnsavedChanges());
    }

    @Test
    void normalizeIsCaseInsensitive() {
        assertEquals(EditorSession.normalize("C:\\Temp\\File.TXT"),
                EditorSession.normalize("c:/temp/file.txt"));
    }
}

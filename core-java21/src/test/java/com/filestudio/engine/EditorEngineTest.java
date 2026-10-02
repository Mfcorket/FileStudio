package com.filestudio.engine;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
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

class EditorEngineTest {

    private EditorEngine engine;
    private DocumentParser parser;

    @BeforeEach
    void setUp() {
        PluginManager pm = new PluginManager();
        pm.registerHandler(new TextFileHandler());
        parser = new DocumentParser(pm);
        engine = new EditorEngine(parser);
    }

    @Test
    void openContentThenEdit() {
        engine.openContent("Hello, world!", "text/plain", "txt");
        assertEquals("Hello, world!", engine.getContent());
        assertFalse(engine.isModified());

        engine.edit("Goodbye, world!");
        assertEquals("Goodbye, world!", engine.getContent());
        assertTrue(engine.isModified());
    }

    @Test
    void undoRedoRoundTrip() {
        engine.openContent("a", "text/plain", "txt");
        engine.edit("ab");
        engine.edit("abc");
        assertEquals("abc", engine.getContent());

        engine.undo();
        assertEquals("ab", engine.getContent());
        engine.undo();
        assertEquals("a", engine.getContent());
        assertFalse(engine.canUndo());

        engine.redo();
        assertEquals("ab", engine.getContent());
        engine.redo();
        assertEquals("abc", engine.getContent());
        assertFalse(engine.canRedo());
    }

    @Test
    void replaceRangeInsertsText() {
        engine.openContent("Hello World", "text/plain", "txt");
        engine.replaceRange(6, 11, "FileStudio");
        assertEquals("Hello FileStudio", engine.getContent());
    }

    @Test
    void replaceRangeDeletesRange() {
        engine.openContent("Hello World", "text/plain", "txt");
        engine.replaceRange(5, 11, "");
        assertEquals("Hello", engine.getContent());
    }

    @Test
    void insertAtAppendsText() {
        engine.openContent("ac", "text/plain", "txt");
        engine.insertAt(1, "b");
        assertEquals("abc", engine.getContent());
    }

    @Test
    void deleteRangeRemovesRange() {
        engine.openContent("abc", "text/plain", "txt");
        engine.deleteRange(1, 2);
        assertEquals("ac", engine.getContent());
    }

    @Test
    void clampOutOfRangeIndices() {
        engine.openContent("abc", "text/plain", "txt");
        // 负索引被夹到 0，等价于在开头插入
        engine.replaceRange(-10, -5, "X");
        assertEquals("Xabc", engine.getContent());
        engine.undo();
        assertEquals("abc", engine.getContent());

        // 越界高索引被夹到末尾，等价于追加
        engine.replaceRange(100, 200, "Y");
        assertEquals("abcY", engine.getContent());
    }

    @Test
    void replaceRangeWithInvalidOrderThrows() {
        engine.openContent("abc", "text/plain", "txt");
        assertThrows(IllegalArgumentException.class, () -> engine.replaceRange(3, 1, "x"));
    }

    @Test
    void noopReplaceIsNotRecorded() {
        engine.openContent("abc", "text/plain", "txt");
        engine.replaceRange(1, 1, "");
        assertFalse(engine.canUndo());
    }

    @Test
    void savePersistsContent(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out.txt");
        engine.openContent("Hello, saved!", "text/plain", "txt");
        engine.save(out);
        assertEquals("Hello, saved!", Files.readString(out, StandardCharsets.UTF_8));
        assertFalse(engine.isModified());
    }

    @Test
    void markSavedResetsModifiedFlag() {
        engine.openContent("a", "text/plain", "txt");
        engine.edit("b");
        assertTrue(engine.isModified());
        engine.markSaved();
        assertFalse(engine.isModified());
    }

    @Test
    void viewOnlyDocumentCannotBeEdited() {
        Document doc = new Document(null, "image/png", "png", "data",
                java.util.Map.of(), 4L, EditCapability.VIEW_ONLY);
        engine.open(doc);
        assertFalse(engine.isWritable());
        assertThrows(FileStudioException.class, () -> engine.edit("x"));
    }

    @Test
    void editWithoutOpenThrows() {
        assertThrows(IllegalStateException.class, () -> engine.edit("x"));
    }

    @Test
    void openDocumentTruncatesHistory() {
        engine.openContent("a", "text/plain", "txt");
        engine.edit("b");
        engine.edit("c");
        assertTrue(engine.canUndo());
        engine.openContent("z", "text/plain", "txt");
        assertFalse(engine.canUndo());
        assertEquals("z", engine.getContent());
    }

    @Test
    void documentPathIsUpdatedOnSave(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("result.txt");
        engine.openContent("x", "text/plain", "txt");
        engine.save(out);
        assertEquals(out, engine.getDocument().getFilePath());
    }
}

package com.filestudio.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HistoryManagerTest {

    @Test
    void snapshotThenUndoRestoresPrevious() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        h.snapshot("b");
        assertEquals("b", h.getCurrent());
        assertTrue(h.canUndo());
        assertEquals("a", h.undo());
        assertEquals("a", h.getCurrent());
        assertTrue(h.canRedo());
    }

    @Test
    void redoRestoresAfterUndo() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        h.snapshot("b");
        h.undo();
        assertEquals("b", h.redo());
        assertTrue(h.canUndo());
        assertFalse(h.canRedo());
    }

    @Test
    void newEditClearsRedoStack() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        h.snapshot("b");
        h.undo();
        assertTrue(h.canRedo());
        h.snapshot("c");
        assertFalse(h.canRedo());
        assertEquals(0, h.getRedoDepth());
        assertEquals("c", h.getCurrent());
    }

    @Test
    void identicalContentIsNotRecorded() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        boolean recorded = h.snapshot("a");
        assertFalse(recorded);
        assertEquals("a", h.getCurrent());
        assertFalse(h.canUndo());
    }

    @Test
    void maxDepthIsRespected() {
        HistoryManager h = new HistoryManager(3);
        h.pushCoalesced("0");
        h.snapshot("1");
        h.snapshot("2");
        h.snapshot("3");
        h.snapshot("4");
        assertEquals(3, h.getUndoDepth());
        // 最旧的 "1" 应已被丢弃
        h.undo(); h.undo(); h.undo();
        assertEquals("1", h.getCurrent());
        assertFalse(h.canUndo());
    }

    @Test
    void undoWithoutHistoryThrows() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        assertThrows(IllegalStateException.class, h::undo);
    }

    @Test
    void redoWithoutHistoryThrows() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        assertThrows(IllegalStateException.class, h::redo);
    }

    @Test
    void statsReflectsCurrentState() {
        HistoryManager h = new HistoryManager();
        HistoryManager.HistoryStats empty = h.stats();
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.undoDepth());

        h.pushCoalesced("a");
        h.snapshot("b");
        HistoryManager.HistoryStats s = h.stats();
        assertFalse(s.isEmpty());
        assertEquals(1, s.undoDepth());
        assertEquals("b", s.current());
        assertEquals(128, s.maxDepth());
    }

    @Test
    void clearResetsEverything() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        h.snapshot("b");
        h.undo();
        h.clear();
        assertNull(h.getCurrent());
        assertFalse(h.canUndo());
        assertFalse(h.canRedo());
        assertTrue(h.stats().isEmpty());
    }

    @Test
    void invalidMaxDepthThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryManager(0));
        assertThrows(IllegalArgumentException.class, () -> new HistoryManager(-5));
    }

    @Test
    void defaultConstructorUsesDefaultDepth() {
        HistoryManager h = new HistoryManager();
        assertEquals(HistoryManager.DEFAULT_MAX_DEPTH, h.getMaxDepth());
    }

    @Test
    void coalescedDoesNotClearRedo() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        h.snapshot("b");
        h.undo();
        assertTrue(h.canRedo());
        h.pushCoalesced("b");
        assertTrue(h.canRedo());
    }

    @Test
    void multipleUndoRedoCycle() {
        HistoryManager h = new HistoryManager();
        h.pushCoalesced("a");
        h.snapshot("b");
        h.snapshot("c");
        h.snapshot("d");
        assertEquals("d", h.getCurrent());
        assertEquals(3, h.getUndoDepth());
        h.undo();
        assertEquals("c", h.getCurrent());
        h.undo();
        assertEquals("b", h.getCurrent());
        h.redo();
        assertEquals("c", h.getCurrent());
        h.redo();
        assertEquals("d", h.getCurrent());
        assertFalse(h.canRedo());
    }
}

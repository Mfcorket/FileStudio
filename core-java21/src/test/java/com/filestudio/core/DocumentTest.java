package com.filestudio.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTest {

    @Test
    void defaultsAreAppliedWhenNullsProvided() {
        Document d = new Document(null, null, null, null, null, 0L, null);
        assertEquals("application/octet-stream", d.getMimeType());
        assertEquals("", d.getExtension());
        assertEquals("", d.getContent());
        assertEquals(EditCapability.VIEW_ONLY, d.getEditCapability());
        assertTrue(d.getMetadata().isEmpty());
    }

    @Test
    void metadataIsImmutable() {
        Document d = new Document(Path.of("a.txt"), "text/plain", "txt", "hi",
                new java.util.HashMap<>(Map.of("k", "v")), 2L, EditCapability.FULL);
        assertThrows(UnsupportedOperationException.class, () -> d.getMetadata().put("x", "y"));
    }

    @Test
    void isEditableReflectsCapability() {
        Document full = Document.empty().withContent("x");
        assertFalse(full.isEditable());
        Document editable = new Document(null, "", "", "", Map.of(), 0, EditCapability.FULL);
        assertTrue(editable.isEditable());
    }

    @Test
    void withContentProducesEqualExceptContentAndSize() {
        Document a = new Document(Path.of("a"), "t", "txt", "old", Map.of(), 3, EditCapability.FULL);
        Document b = a.withContent("new");
        assertNotEquals(a, b);
        assertEquals("new", b.getContent());
        assertEquals(3, b.getSize());
    }
}

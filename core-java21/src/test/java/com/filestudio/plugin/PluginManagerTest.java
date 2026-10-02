package com.filestudio.plugin;

import com.filestudio.core.EditCapability;
import com.filestudio.core.PluginHandlerInfo;
import com.filestudio.plugin.handlers.TextFileHandler;
import com.filestudio.plugin.sample.SamplePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    private PluginManager pm;

    @BeforeEach
    void setUp() {
        pm = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        pm.shutdown();
    }

    @Test
    void spiLoadsSamplePlugin() {
        pm.loadPlugins();
        assertFalse(pm.getPlugins().isEmpty(), "SPI should load SamplePlugin");
        assertTrue(pm.getHandlers().stream()
                .anyMatch(h -> "sample".equals(h.getExtension())));
    }

    @Test
    void registerHandlerAddsEntry() {
        pm.registerHandler(new TextFileHandler());
        assertEquals(1, pm.getHandlers().size());
    }

    @Test
    void findHandlerByExtensionPreferred() throws IOException {
        pm.registerHandler(new TextFileHandler());
        File txt = Files.createFile(Path.of(System.getProperty("java.io.tmpdir"), "fs_test.txt")).toFile();
        try {
            assertTrue(pm.findHandler(txt).isPresent());
        } finally {
            txt.delete();
        }
    }

    @Test
    void findHandlerFallsBackToCanHandle() throws IOException {
        // 注册一个无扩展名匹配的 handler，但 canHandle 返回 true
        FileHandler generic = new FileHandler() {
            @Override public String getExtension() { return "zzz"; }
            @Override public String getMimeType() { return "application/zzz"; }
            @Override public EditCapability getEditCapability() { return EditCapability.VIEW_ONLY; }
            @Override public String getDescription() { return "generic"; }
            @Override public boolean canHandle(File file) { return true; }
            @Override public com.filestudio.core.Document parse(File f) { return null; }
            @Override public void render(com.filestudio.core.Document d, File o) {}
            @Override public java.util.Map<String, Object> getMetadata(File f) { return java.util.Map.of(); }
        };
        pm.registerHandler(generic);
        File any = Path.of(System.getProperty("java.io.tmpdir"), "fs_unknown.dat").toFile();
        assertTrue(pm.findHandler(any).isPresent());
    }

    @Test
    void listHandlersDescribesRegisteredEntries() {
        pm.registerPlugin(new SamplePlugin());
        var infos = pm.listHandlers();
        assertEquals(1, infos.size());
        PluginHandlerInfo info = infos.get(0);
        assertEquals("sample", info.extension());
        assertEquals(EditCapability.FULL, info.editCapability());
        assertEquals("com.filestudio.sample", info.pluginName());
    }
}

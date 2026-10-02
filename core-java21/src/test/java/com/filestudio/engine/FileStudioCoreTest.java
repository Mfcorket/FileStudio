package com.filestudio.engine;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioCore;
import com.filestudio.core.FileStudioException;
import com.filestudio.core.PluginHandlerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStudioCoreTest {

    private FileStudioCore core;

    @BeforeEach
    void setUp() {
        core = new FileStudioCore();
        core.init();
    }

    @AfterEach
    void tearDown() {
        core.shutdown();
    }

    @Test
    void initIsIdempotent() {
        assertDoesNotThrow(() -> core.init());
    }

    @Test
    void listHandlersIncludesBuiltinTextAndSamplePlugin() {
        var infos = core.listHandlers();
        assertTrue(infos.stream().anyMatch(i -> "txt".equals(i.extension())));
        assertTrue(infos.stream().anyMatch(i -> "sample".equals(i.extension())));
    }

    @Test
    void allDeclaredBuiltinFormatsAreRegistered() {
        var extensions = core.listHandlers().stream()
                .map(PluginHandlerInfo::extension)
                .collect(java.util.stream.Collectors.toSet());
        // 所有内置处理器应已注册
        var expected = new java.util.HashSet<String>();
        expected.add("json");
        expected.add("xml");
        expected.add("properties");
        expected.add("ini");
        expected.add("csv");
        expected.add("md");
        expected.add("yaml");
        expected.add("toml");
        expected.add("html");
        expected.add("css");
        expected.add("png");
        expected.add("zip");
        expected.add("txt");
        for (String e : expected) {
            assertTrue(extensions.contains(e), "Missing handler for extension: " + e);
        }
    }

    @Test
    void newEditorIsUsable() {
        var ed = core.newEditor();
        assertNotNull(ed);
        ed.openContent("hello", "text/plain", "txt");
        ed.edit("hello world");
        assertEquals("hello world", ed.getContent());
    }

    @Test
    void jsonFileIsRoutedToJsonHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.json");
        Files.writeString(p, "{\"k\":1}", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("application/json", doc.getMimeType());
        assertEquals("object", doc.getMetadata().get("rootType"));
    }

    @Test
    void xmlFileIsRoutedToXmlHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.xml");
        Files.writeString(p, "<root/>", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("application/xml", doc.getMimeType());
        assertEquals(true, doc.getMetadata().get("valid"));
    }

    @Test
    void csvFileIsRoutedToCsvHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.csv");
        Files.writeString(p, "x,y\n1,2\n", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("comma", doc.getMetadata().get("delimiter"));
        assertEquals(2, doc.getMetadata().get("columnCount"));
    }

    @Test
    void markdownFileIsRoutedToMarkdownHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.md");
        Files.writeString(p, "# Title\n\nhello\n", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("text/markdown", doc.getMimeType());
        assertEquals(1, doc.getMetadata().get("headingCount"));
    }

    @Test
    void yamlFileIsRoutedToYamlHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.yaml");
        Files.writeString(p, "key: value\n  sub: x\n", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("application/yaml", doc.getMimeType());
        assertEquals(1, doc.getMetadata().get("documentCount"));
        assertEquals(1, doc.getMetadata().get("topLevelKeyCount"));
    }

    @Test
    void ymlExtensionAlsoRoutesToYamlHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.yml");
        Files.writeString(p, "k: v\n", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("application/yaml", doc.getMimeType());
    }

    @Test
    void tomlFileIsRoutedToTomlHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.toml");
        Files.writeString(p, "[section]\nkey = \"value\"\n", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("application/toml", doc.getMimeType());
        assertEquals(1, doc.getMetadata().get("tableCount"));
    }

    @Test
    void htmlFileIsRoutedToHtmlHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.html");
        Files.writeString(p, "<html><head><title>T</title></head><body></body></html>",
                StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("text/html", doc.getMimeType());
        assertEquals("T", doc.getMetadata().get("title"));
    }

    @Test
    void cssFileIsRoutedToCssHandler(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.css");
        Files.writeString(p, "body { color: red; }\n", StandardCharsets.UTF_8);
        Document doc = core.parseFile(p.toString());
        assertEquals("text/css", doc.getMimeType());
        assertEquals(1, doc.getMetadata().get("ruleCount"));
    }

    @Test
    void parseTextFileRoundTrip(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("hello.txt");
        Files.writeString(src, "Hello, FileStudio!", StandardCharsets.UTF_8);

        Document doc = core.parseFile(src.toString());
        assertEquals("text/plain", doc.getMimeType());
        assertEquals("txt", doc.getExtension());
        assertEquals(EditCapability.FULL, doc.getEditCapability());
        assertEquals("Hello, FileStudio!", doc.getContent());

        Path out = dir.resolve("out.txt");
        core.saveDocument(doc, out.toString());
        assertEquals("Hello, FileStudio!",
                Files.readString(out, StandardCharsets.UTF_8));
    }

    @Test
    void parseSampleFormatViaPlugin(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("demo.sample");
        Files.writeString(f, "sample-content", StandardCharsets.UTF_8);
        Document doc = core.parseFile(f.toString());
        assertEquals("text/x-sample", doc.getMimeType());
        assertEquals("sample-content", doc.getContent());
        assertEquals("sample-plugin", doc.getMetadata().get("source"));
    }

    @Test
    void parseNonexistentFileThrows() {
        assertThrows(FileStudioException.class,
                () -> core.parseFile("no/such/file.txt"));
    }

    @Test
    void parseUnsupportedFormatThrows(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("unknown.xyz");
        Files.writeString(f, "data", StandardCharsets.UTF_8);
        assertThrows(FileStudioException.class, () -> core.parseFile(f.toString()));
    }

    @Test
    void notInitializedThrows() {
        FileStudioCore c = new FileStudioCore();
        assertThrows(IllegalStateException.class, () -> c.parseFile("a.txt"));
    }

    @Test
    void magicBytesRoutesExtensionlessPng(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("logo"); // 无扩展名
        byte[] png = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x20, 8,
        };
        Files.write(p, png);
        Document doc = core.parseFile(p.toFile());
        assertEquals("image/png", doc.getMimeType());
        assertEquals("png", doc.getExtension());
        assertEquals(64, doc.getMetadata().get("width"));
    }

    @Test
    void magicBytesRoutesExtensionlessZip(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bundle"); // 无扩展名
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(p))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("inner.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Document doc = core.parseFile(p.toFile());
        assertEquals("application/zip", doc.getMimeType());
        assertEquals("zip", doc.getExtension());
        assertEquals(1, doc.getMetadata().get("entryCount"));
    }

    @Test
    void sessionManagerOpensAndEdits(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("notes.txt");
        Files.writeString(p, "original", StandardCharsets.UTF_8);
        var sm = core.newSessionManager();
        var s = sm.open(p.toFile());
        s.getEditor().edit("changed");
        assertEquals("changed", s.getDocument().getContent());
        assertTrue(sm.hasUnsavedChanges());
        s.save();
        assertFalse(sm.hasUnsavedChanges());
        assertEquals("changed", Files.readString(p, StandardCharsets.UTF_8));
    }
}

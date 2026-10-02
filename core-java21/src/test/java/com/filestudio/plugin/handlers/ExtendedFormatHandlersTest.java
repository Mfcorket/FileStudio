package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExtendedFormatHandlersTest {

    private final YamlFileHandler yaml = new YamlFileHandler();
    private final TomlFileHandler toml = new TomlFileHandler();
    private final HtmlFileHandler html = new HtmlFileHandler();
    private final CssFileHandler css = new CssFileHandler();

    // ---- YAML ----

    @Test
    void yamlDeclaresFormat() {
        assertEquals("yaml", yaml.getExtension());
        assertEquals("application/yaml", yaml.getMimeType());
        assertEquals(EditCapability.FULL, yaml.getEditCapability());
    }

    @Test
    void yamlCanHandleBothExtensions() {
        assertTrue(yaml.canHandle(new java.io.File("a.yaml")));
        assertTrue(yaml.canHandle(new java.io.File("b.yml")));
        assertFalse(yaml.canHandle(new java.io.File("c.txt")));
    }

    @Test
    void yamlCountsDocumentsAndKeys(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("doc.yaml");
        Files.writeString(p,
                "# comment\n" +
                        "name: FileStudio\n" +
                        "version: 1.0\n" +
                        "tags:\n" +
                        "  - java\n" +
                        "  - editor\n" +
                        "---\n" +
                        "other: doc\n",
                StandardCharsets.UTF_8);
        Document d = yaml.parse(p.toFile());
        assertEquals(2, d.getMetadata().get("documentCount"));
        assertEquals(4, d.getMetadata().get("topLevelKeyCount")); // name, version, tags, other
        assertEquals(2, d.getMetadata().get("listItemCount"));    // - java, - editor
        assertEquals(2, d.getMetadata().get("maxIndent"));
    }

    @Test
    void yamlEmptyContentSkipsEnrichment(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.yaml");
        Files.writeString(p, "", StandardCharsets.UTF_8);
        Document d = yaml.parse(p.toFile());
        assertFalse(d.getMetadata().containsKey("documentCount"));
    }

    // ---- TOML ----

    @Test
    void tomlDeclaresFormat() {
        assertEquals("toml", toml.getExtension());
        assertEquals("application/toml", toml.getMimeType());
        assertEquals(EditCapability.FULL, toml.getEditCapability());
    }

    @Test
    void tomlCountsTablesAndKeys(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("config.toml");
        Files.writeString(p,
                "# top-level\n" +
                        "title = \"FileStudio\"\n" +
                        "version = 1\n" +
                        "\n" +
                        "[package]\n" +
                        "name = \"filestudio\"\n" +
                        "\n" +
                        "[[deps]]\n" +
                        "name = \"dep1\"\n" +
                        "\n" +
                        "[[deps]]\n" +
                        "name = \"dep2\"\n",
                StandardCharsets.UTF_8);
        Document d = toml.parse(p.toFile());
        assertEquals(1, d.getMetadata().get("tableCount"));        // [package]
        assertEquals(2, d.getMetadata().get("arrayTableCount"));  // [[deps]] x2
        assertEquals(5, d.getMetadata().get("keyCount"));         // title, version, name, name, name
        assertTrue(((java.util.List<?>) d.getMetadata().get("tables")).contains("package"));
        assertTrue(((java.util.List<?>) d.getMetadata().get("tables")).contains("deps"));
    }

    @Test
    void tomlIgnoresCommentsAndInlineComments(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("c.toml");
        Files.writeString(p,
                "# comment\n" +
                        "key = \"value\" # inline comment\n" +
                        "[section]\n" +
                        "x = 1\n",
                StandardCharsets.UTF_8);
        Document d = toml.parse(p.toFile());
        assertEquals(1, d.getMetadata().get("tableCount"));
        assertEquals(2, d.getMetadata().get("keyCount"));
    }

    // ---- HTML ----

    @Test
    void htmlDeclaresFormat() {
        assertEquals("html", html.getExtension());
        assertEquals("text/html", html.getMimeType());
        assertEquals(EditCapability.FULL, html.getEditCapability());
    }

    @Test
    void htmlCanHandleExtensions() {
        assertTrue(html.canHandle(new java.io.File("a.html")));
        assertTrue(html.canHandle(new java.io.File("b.htm")));
        assertTrue(html.canHandle(new java.io.File("c.xhtml")));
        assertFalse(html.canHandle(new java.io.File("d.css")));
    }

    @Test
    void htmlExtractsTitleAndChecksSections(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("page.html");
        Files.writeString(p,
                "<!DOCTYPE html>\n" +
                        "<html>\n" +
                        "<head><title>FileStudio</title></head>\n" +
                        "<body>\n" +
                        "  <h1>Hello</h1>\n" +
                        "  <p>World</p>\n" +
                        "</body>\n" +
                        "</html>",
                StandardCharsets.UTF_8);
        Document d = html.parse(p.toFile());
        assertEquals("FileStudio", d.getMetadata().get("title"));
        assertEquals(true, d.getMetadata().get("hasHead"));
        assertEquals(true, d.getMetadata().get("hasBody"));
        assertEquals(0, d.getMetadata().get("commentCount"));
    }

    @Test
    void htmlCountsTagsAndDepth(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("nested.html");
        Files.writeString(p,
                "<html><body><div><p>text</p></div></body></html>",
                StandardCharsets.UTF_8);
        Document d = html.parse(p.toFile());
        // tags: html, body, div, p, /p, /div, /body, /html = 8
        assertEquals(8, d.getMetadata().get("tagCount"));
        assertEquals(4, d.getMetadata().get("maxDepth")); // html > body > div > p
    }

    @Test
    void htmlVoidElementsDoNotAffectDepth(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("void.html");
        Files.writeString(p, "<div><br><img src=\"x\"><hr></div>", StandardCharsets.UTF_8);
        Document d = html.parse(p.toFile());
        assertEquals(1, d.getMetadata().get("maxDepth")); // only div
    }

    @Test
    void htmlSelfClosingTagDoesNotPushStack(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("sc.html");
        Files.writeString(p, "<div><custom/>text</div>", StandardCharsets.UTF_8);
        Document d = html.parse(p.toFile());
        assertEquals(1, d.getMetadata().get("maxDepth"));
    }

    @Test
    void htmlCountsComments(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("c.html");
        Files.writeString(p, "<!-- a -->text<!-- b -->", StandardCharsets.UTF_8);
        Document d = html.parse(p.toFile());
        assertEquals(2, d.getMetadata().get("commentCount"));
    }

    // ---- CSS ----

    @Test
    void cssDeclaresFormat() {
        assertEquals("css", css.getExtension());
        assertEquals("text/css", css.getMimeType());
        assertEquals(EditCapability.FULL, css.getEditCapability());
    }

    @Test
    void cssCountsRulesAndProperties(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("style.css");
        Files.writeString(p,
                "body {\n" +
                        "  color: red;\n" +
                        "  margin: 0;\n" +
                        "}\n" +
                        "h1 {\n" +
                        "  font-size: 2em;\n" +
                        "}\n",
                StandardCharsets.UTF_8);
        Document d = css.parse(p.toFile());
        assertEquals(2, d.getMetadata().get("ruleCount"));
        assertEquals(3, d.getMetadata().get("propertyCount"));
    }

    @Test
    void cssExtractsAtRules(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("media.css");
        Files.writeString(p,
                "@import url(\"other.css\");\n" +
                        "@media screen {\n" +
                        "  body { color: blue; }\n" +
                        "}\n" +
                        "@keyframes spin { from { transform: rotate(0) } to { transform: rotate(360deg) } }\n",
                StandardCharsets.UTF_8);
        Document d = css.parse(p.toFile());
        java.util.List<?> at = (java.util.List<?>) d.getMetadata().get("atRules");
        assertNotNull(at);
        assertTrue(at.contains("@import"));
        assertTrue(at.contains("@media"));
        assertTrue(at.contains("@keyframes"));
    }

    @Test
    void cssIgnoresComments(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("c.css");
        Files.writeString(p,
                "/* fake { rule: value; } */\n" +
                        "body { color: red; }\n",
                StandardCharsets.UTF_8);
        Document d = css.parse(p.toFile());
        assertEquals(1, d.getMetadata().get("ruleCount"));
        assertEquals(1, d.getMetadata().get("propertyCount"));
    }
}

package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaClassFileHandlerTest {

    private final JavaClassFileHandler handler = new JavaClassFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("class", handler.getExtension());
        assertEquals("application/java-vm", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    /**
     * 用本项目自身编译产物做解析，验证常量池遍历在真实字节码上正确。
     */
    @Test
    void parsesRealClassFile(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("Document.class");
        copyClassResource("/com/filestudio/core/Document.class", p);

        Document doc = handler.parse(p.toFile());
        assertEquals("application/java-vm", doc.getMimeType());
        assertEquals("class", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("com/filestudio/core/Document", doc.getMetadata().get("className"));

        // Java 21 → major 65
        int major = (int) doc.getMetadata().get("majorVersion");
        assertEquals(65, major);
        assertEquals(JavaClassFileHandler.javaVersionName(major), doc.getMetadata().get("javaVersion"));

        assertTrue((int) doc.getMetadata().get("methodCount") > 0, "应解析出方法数量");
        assertTrue((int) doc.getMetadata().get("constantPoolCount") > 0, "应解析出常量池大小");
        assertEquals(false, doc.getMetadata().get("isInterface"));
    }

    /** 真实类文件中 public final class 的访问标志应为 0x001F。 */
    @Test
    void resolvesModifiersOfRealClass(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("Document.class");
        copyClassResource("/com/filestudio/core/Document.class", p);

        Document doc = handler.parse(p.toFile());
        String mods = (String) doc.getMetadata().get("modifiers");
        assertTrue(mods.contains("public"), "实际为: " + mods);
        assertTrue(mods.contains("final"), "实际为: " + mods);
        assertEquals("java/lang/Object", doc.getMetadata().get("superClassName"));
    }

    /** 接口类应被识别为 interface。 */
    @Test
    void detectsInterface(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("FileHandler.class");
        copyClassResource("/com/filestudio/plugin/FileHandler.class", p);

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("isInterface"));
        assertEquals(true, doc.getMetadata().get("isAbstract"));
        assertEquals("com/filestudio/plugin/FileHandler", doc.getMetadata().get("className"));
    }

    @Test
    void mapsMajorVersionsToJavaReleases() {
        assertEquals("8", JavaClassFileHandler.javaVersionName(52));
        assertEquals("11", JavaClassFileHandler.javaVersionName(55));
        assertEquals("17", JavaClassFileHandler.javaVersionName(61));
        assertEquals("21", JavaClassFileHandler.javaVersionName(65));
        assertTrue(JavaClassFileHandler.javaVersionName(3).startsWith("unknown"));
    }

    @Test
    void parsesSyntheticHeader(@TempDir Path dir) throws IOException {
        // major 52 (Java 8), minor 0, 常量池仅含一个 Utf8 "A" 与一个 Class
        Path p = dir.resolve("Synthetic.class");
        Files.write(p, buildSyntheticClass(52, 0));

        Document doc = handler.parse(p.toFile());
        assertEquals(52, doc.getMetadata().get("majorVersion"));
        assertEquals("8", doc.getMetadata().get("javaVersion"));
        assertEquals("A", doc.getMetadata().get("className"));
    }

    @Test
    void rejectsNonClassContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.class");
        Files.writeString(p, "not a class file", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void canHandleByExtension(@TempDir Path dir) {
        assertTrue(handler.canHandle(new java.io.File("Main.class")));
        assertFalse(handler.canHandle(new java.io.File("Main.java")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("Document.class");
        copyClassResource("/com/filestudio/core/Document.class", p);
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.class").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("Document.class");
        copyClassResource("/com/filestudio/core/Document.class", p);
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 辅助 ----

    private static void copyClassResource(String resource, Path target) throws IOException {
        try (InputStream in = JavaClassFileHandlerTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "classpath 中找不到 " + resource);
            Files.copy(in, target);
        }
    }

    /**
     * 构造仅含最小常量池的合法 class。
     *
     * <p>布局：magic / minor / major / constant_pool_count / constant_pool /
     * access_flags / this_class / super_class ...
     */
    private static byte[] buildSyntheticClass(int major, int minor) {
        // constant_pool_count = 3：#1 Utf8 "A"，#2 Class -> #1
        byte[] b = new byte[40];
        int p = 0;
        b[p++] = (byte) 0xCA; b[p++] = (byte) 0xFE; b[p++] = (byte) 0xBA; b[p++] = (byte) 0xBE;
        b[p++] = (byte) ((minor >> 8) & 0xFF); b[p++] = (byte) (minor & 0xFF);
        b[p++] = (byte) ((major >> 8) & 0xFF); b[p++] = (byte) (major & 0xFF);
        b[p++] = 0; b[p++] = 3;          // constant_pool_count = 3

        // 常量池从偏移 10 开始
        b[p++] = 1;                        // #1 CONSTANT_Utf8
        b[p++] = 0; b[p++] = 1;            // length = 1
        b[p++] = 'A';                       // "A"
        b[p++] = 7;                        // #2 CONSTANT_Class
        b[p++] = 0; b[p++] = 1;            // name_index = #1

        // 常量池结束后的固定字段
        b[p++] = 0x00; b[p++] = 0x21;       // access_flags = ACC_PUBLIC | ACC_SUPER
        b[p++] = 0; b[p++] = 2;            // this_class = #2
        b[p++] = 0; b[p++] = 0;            // super_class = 0
        b[p++] = 0; b[p++] = 0;            // interfaces_count = 0
        b[p++] = 0; b[p++] = 0;            // fields_count = 0
        b[p++] = 0; b[p++] = 0;            // methods_count = 0
        b[p++] = 0; b[p++] = 0;            // attributes_count = 0
        return b;
    }
}

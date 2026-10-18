package com.filestudio.core;

import com.filestudio.plugin.handlers.JsonFileHandler;
import com.filestudio.plugin.handlers.TextFileHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本类处理器的 {@code fileExtension} 元数据。
 *
 * <p>背景：{@link Document#getExtension()} 返回的是处理器归一化后的类型
 * （通用文本处理器一律是 {@code txt}），用户真正关心的是文件本身的后缀。
 * 这里锁定该行为，防止后续改动又把它丢掉。
 */
class FileExtensionMetadataTest {

    @Test
    void parseExposesRealFileExtension(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Main.java");
        Files.writeString(f, "public class Main { }", StandardCharsets.UTF_8);

        // 用通用文本处理器打开 .java：它的归一化类型是 txt
        Document doc = new TextFileHandler().parse(f.toFile());

        assertEquals("java", doc.getMetadata().get("fileExtension"));
        // Document.extension 仍是处理器的归一化类型，不因本改动而变化
        assertEquals("txt", doc.getExtension());
    }

    @Test
    void structuredHandlerStillReportsItsOwnExtension(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.json");
        Files.writeString(f, "{\"a\":1}", StandardCharsets.UTF_8);

        Document doc = new JsonFileHandler().parse(f.toFile());

        assertEquals("json", doc.getExtension());
        assertEquals("json", doc.getMetadata().get("fileExtension"));
    }

    @Test
    void extensionIsLowercased(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("README.MD");
        Files.writeString(f, "# hi", StandardCharsets.UTF_8);

        Document doc = new TextFileHandler().parse(f.toFile());

        assertEquals("md", doc.getMetadata().get("fileExtension"));
    }

    @Test
    void filesWithoutExtensionOmitTheKey(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("LICENSE");
        Files.writeString(f, "text", StandardCharsets.UTF_8);

        Document doc = new TextFileHandler().parse(f.toFile());

        assertFalse(doc.getMetadata().containsKey("fileExtension"));
    }

    @Test
    void getMetadataAlsoExposesExtension(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("notes.txt");
        Files.writeString(f, "hello", StandardCharsets.UTF_8);

        var meta = new TextFileHandler().getMetadata(f.toFile());

        assertEquals("txt", meta.get("fileExtension"));
    }

    @Test
    void existingMetadataIsPreserved(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("data.json");
        Files.writeString(f, "{\"a\":1}", StandardCharsets.UTF_8);

        Document doc = new JsonFileHandler().parse(f.toFile());

        // 新增键不应挤掉原有的解码与结构信息
        assertTrue(doc.getMetadata().containsKey("encoding"));
        assertTrue(doc.getMetadata().containsKey("size"));
        assertTrue(doc.getMetadata().containsKey("rootType"));
        assertTrue(doc.getMetadata().containsKey("keyCount"));
    }
}
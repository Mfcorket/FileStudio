package com.filestudio.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MagicBytesDetectorTest {

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
            'I', 'H', 'D', 'R'
    };

    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04, 0x14, 0x00};

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    private static final byte[] GZIP = {0x1F, (byte) 0x8B, 0x08, 0x00};

    @Test
    void detectsPng() {
        var d = MagicBytesDetector.detect(PNG, PNG.length);
        assertEquals("png", d.extension());
        assertEquals("image/png", d.mimeType());
        assertEquals(1.0, d.confidence());
        assertTrue(d.isKnown());
    }

    @Test
    void detectsJpeg() {
        var d = MagicBytesDetector.detect(JPG, JPG.length);
        assertEquals("jpg", d.extension());
        assertEquals("image/jpeg", d.mimeType());
    }

    @Test
    void detectsZip() {
        var d = MagicBytesDetector.detect(ZIP, ZIP.length);
        assertEquals("zip", d.extension());
        assertEquals("application/zip", d.mimeType());
    }

    @Test
    void detectsPdf() {
        var d = MagicBytesDetector.detect(PDF, PDF.length);
        assertEquals("pdf", d.extension());
    }

    @Test
    void detectsGzip() {
        var d = MagicBytesDetector.detect(GZIP, GZIP.length);
        assertEquals("gz", d.extension());
    }

    @Test
    void detectsGif() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0};
        var d = MagicBytesDetector.detect(gif, gif.length);
        assertEquals("gif", d.extension());
    }

    @Test
    void detectsSqlite() {
        byte[] sqlite = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var d = MagicBytesDetector.detect(sqlite, sqlite.length);
        assertEquals("sqlite", d.extension());
    }

    @Test
    void detectsClassFile() {
        byte[] cls = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00};
        var d = MagicBytesDetector.detect(cls, cls.length);
        assertEquals("class", d.extension());
    }

    @Test
    void detectsTextJsonByContent() {
        byte[] json = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var d = MagicBytesDetector.detect(json, json.length);
        assertEquals("json", d.extension());
        assertTrue(d.confidence() < 1.0);
    }

    @Test
    void detectsXmlByContent() {
        byte[] xml = "<?xml version=\"1.0\"?>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var d = MagicBytesDetector.detect(xml, xml.length);
        assertEquals("xml", d.extension());
    }

    @Test
    void unknownReturnsUnknown() {
        // 真正无法判定的二进制内容：含 NUL 与高位字节
        byte[] unknown = {0x00, 0x01, (byte) 0x8B, 0x7F, 0x00, (byte) 0xC0, 0x10, 0x20};
        var d = MagicBytesDetector.detect(unknown, unknown.length);
        assertFalse(d.isKnown());
        assertEquals("", d.extension());
    }

    @Test
    void plainAsciiIsDetectedAsText() {
        // 没有 BOM 的纯文本源码也必须能打开，否则 .java/.py/.js 等会被判为无处理器
        byte[] source = "public class Main { }".getBytes(StandardCharsets.UTF_8);
        var d = MagicBytesDetector.detect(source, source.length);
        assertTrue(d.isKnown());
        assertEquals("txt", d.extension());
        assertEquals("text/plain", d.mimeType());
        // 置信度刻意压低，避免盖过具体格式
        assertTrue(d.confidence() < 0.5, "兜底文本检测的置信度应低于具体格式，实际=" + d.confidence());
    }

    @Test
    void sourceFilesWithoutBomAreDetectedAsText() {
        // 无 BOM 的源码必须能识别；其中带明确标记的仍应命中更具体的格式
        String[][] cases = {
                {"def f():\n    return 1\n", "txt"},
                {"function f() { return 1 }", "txt"},
                {"package main", "txt"},
                {"<html><body>hi</body></html>", "html"},
                {"<?xml version=\"1.0\"?><a/>", "xml"},
                {"{\"k\":1}", "json"},
                {"#!/bin/sh\necho hi", "sh"},
        };
        for (String[] c : cases) {
            byte[] b = c[0].getBytes(StandardCharsets.UTF_8);
            assertEquals(c[1], MagicBytesDetector.detect(b, b.length).extension(),
                    "识别错误的内容：" + c[0]);
        }
    }

    @Test
    void emptyOrNullReturnsUnknown() {
        assertEquals("", MagicBytesDetector.detect(new byte[0], 0).extension());
        assertEquals("", MagicBytesDetector.detect(null, 0).extension());
    }

    @Test
    void detectFromFile(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("logo.png");
        Files.write(p, PNG);
        var d = MagicBytesDetector.detect(p);
        assertEquals("png", d.extension());
    }

    @Test
    void detectFromFileWithoutExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        Files.write(p, PDF);
        var d = MagicBytesDetector.detect(p);
        assertEquals("pdf", d.extension());
    }

    @Test
    void ut8BomDetectedAsText() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        var d = MagicBytesDetector.detect(bom, bom.length);
        assertEquals("txt", d.extension());
    }

    @Test
    void binaryControlCharactersAreNotText() {
        byte[] bin = {0x00, 0x01, 0x02, 'a', 'b', 'c'};
        var d = MagicBytesDetector.detect(bin, bin.length);
        assertFalse(d.isKnown());
    }
}

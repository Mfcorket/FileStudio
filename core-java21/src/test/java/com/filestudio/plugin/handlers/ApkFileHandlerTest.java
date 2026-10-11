package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ApkFileHandlerTest {

    private final ApkFileHandler handler = new ApkFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("apk", handler.getExtension());
        assertEquals("application/vnd.android.package-archive", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void readsPackageStructure(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.apk");
        Files.write(p, buildApk());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("apk", m.get("format"));
        assertEquals(true, m.get("valid"));
        assertEquals(true, m.get("hasAndroidManifest"));
        assertEquals(3, m.get("dexCount"));
        assertEquals(List.of("classes.dex", "classes2.dex", "classes3.dex"), m.get("dexFiles"));
        assertEquals(2, m.get("nativeLibraryCount"));
        assertEquals(List.of("arm64-v8a", "armeabi-v7a"), m.get("abis"));
        assertEquals(true, m.get("v1Signed"));
        assertEquals(List.of("CERT.RSA"), m.get("signers"));
        assertEquals(80L, m.get("assetBytes"));
        assertEquals(200L, m.get("manifestSize"));
        assertEquals(800L, m.get("resourcesArscSize"));
        assertEquals(true, m.get("viewOnly"));
        assertEquals(false, m.get("manifestParsed"));
    }

    @Test
    void countsEntriesAndListsLargest(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("res.apk");
        Files.write(p, buildApk());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(9, m.get("entryCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> largest = (List<Map<String, Object>>) m.get("largestEntries");
        assertNotNull(largest);
        assertEquals(9, largest.size());
        // 最大条目是 resources.arsc (800 字节)
        assertEquals("resources.arsc", largest.get(0).get("name"));
    }

    @Test
    void readsAppBundleLayout(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bundle.aab");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("base/manifest/AndroidManifest.xml", bytes(64));
        entries.put("base/dex/classes.dex", bytes(900));
        entries.put("base/resources.pb", bytes(120));
        entries.put("BundleConfig.pb", bytes(16));
        writeZip(p, entries);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("aab", m.get("format"));
        assertEquals(true, m.get("hasAndroidManifest"));
        assertEquals(true, m.get("bundleLayout"));
        assertEquals(1, m.get("dexCount"));
        assertEquals(List.of("base/dex/classes.dex"), m.get("dexFiles"));
    }

    @Test
    void reportsPlainZipWithoutAppManifest(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("plain.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", bytes(4));
        entries.put("b.txt", bytes(5));
        writeZip(p, entries);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("zip", m.get("format"));
        assertEquals(true, m.get("valid"));
        assertEquals(false, m.get("hasAndroidManifest"));
        assertEquals(0, m.get("dexCount"));
        assertEquals(0, m.get("nativeLibraryCount"));
        assertEquals(false, m.get("v1Signed"));
    }

    @Test
    void handlesIpaPackage(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.ipa");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Payload/", new byte[0]);
        entries.put("Payload/app.app/Info.plist", bytes(40));
        writeZip(p, entries);

        assertTrue(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("ipa", m.get("format"));
        assertEquals(false, m.get("hasAndroidManifest"));
    }

    @Test
    void doesNotClaimOrdinaryZip(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("doc.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", bytes(10));
        writeZip(p, entries);

        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void detectsApkByContentRegardlessOfExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("renamed.bin");
        Files.write(p, buildApk());

        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void rejectsMissingFile(@TempDir Path dir) {
        // 与其它处理器一致：canHandle 回答「该路径该用哪个处理器」，
        // 因此尚未创建的 *.apk 仍按扩展名匹配；真正的读取必须抛异常。
        assertTrue(handler.canHandle(dir.resolve("nope.apk").toFile()));
        assertThrows(FileStudioException.class,
                () -> handler.parse(dir.resolve("nope.apk").toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.apk");
        Files.write(p, buildApk());

        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.apk").toFile()));
    }

    @Test
    void getMetadataReportsSummary(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.apk");
        Files.write(p, buildApk());

        Map<String, Object> meta = handler.getMetadata(p.toFile());
        assertEquals(9, meta.get("entryCount"));
        assertEquals(3, meta.get("dexCount"));
    }

    @Test
    void handlesCorruptPackage(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("broken.apk");
        Files.write(p, "not a zip at all".getBytes());

        // 扩展名已知，因此可以处理，但解析会报告无效而不是抛出
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertNotNull(m.get("error"));
    }

    // ---- helpers ----

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i & 0xFF);
        }
        return b;
    }

    private static void writeZip(Path p, Map<String, byte[]> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(p); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (e.getValue().length > 0) {
                    zos.write(e.getValue());
                }
                zos.closeEntry();
            }
        }
    }

    private static byte[] buildApk() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("AndroidManifest.xml", bytes(200));
        entries.put("classes.dex", bytes(500));
        entries.put("classes2.dex", bytes(300));
        entries.put("classes3.dex", bytes(120));
        entries.put("resources.arsc", bytes(800));
        entries.put("lib/arm64-v8a/libnative.so", bytes(400));
        entries.put("lib/armeabi-v7a/libnative.so", bytes(350));
        entries.put("META-INF/CERT.RSA", bytes(64));
        entries.put("assets/config.json", bytes(80));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeZipInto(bos, entries);
        return bos.toByteArray();
    }

    private static void writeZipInto(OutputStream os, Map<String, byte[]> entries) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(os);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            zos.putNextEntry(new ZipEntry(e.getKey()));
            if (e.getValue().length > 0) {
                zos.write(e.getValue());
            }
            zos.closeEntry();
        }
        zos.close();
    }
}
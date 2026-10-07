package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GltfFileHandlerTest {

    private final GltfFileHandler handler = new GltfFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("gltf", handler.getExtension());
        assertEquals("model/gltf+json", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesJsonGltf(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("scene.gltf");
        Files.writeString(p, """
                {
                  "asset": { "version": "2.0", "generator": "FileStudio test" },
                  "scenes": [ { "nodes": [0] } ],
                  "meshes": [ { "primitives": [] } ],
                  "materials": [ {}, {} ]
                }
                """, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals("model/gltf+json", doc.getMimeType());
        assertEquals("gltf", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("2.0", doc.getMetadata().get("assetVersion"));
        assertEquals("FileStudio test", doc.getMetadata().get("generator"));
    }

    @Test
    void parsesBinaryGlb(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("scene.glb");
        Files.write(p, buildGlb("""
                {"asset":{"version":"2.0","generator":"glb test"}}""", true));

        Document doc = handler.parse(p.toFile());
        assertEquals("glb", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(2, doc.getMetadata().get("containerVersion"));
        assertEquals("2.0", doc.getMetadata().get("assetVersion"));
        assertEquals("glb test", doc.getMetadata().get("generator"));
        assertEquals(true, doc.getMetadata().get("hasBinaryChunk"));
        assertEquals(16L, doc.getMetadata().get("binaryChunkLength"));
    }

    @Test
    void parsesGlbWithoutBinaryChunk(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("nobin.glb");
        Files.write(p, buildGlb("{\"asset\":{\"version\":\"2.0\"}}", false));

        Document doc = handler.parse(p.toFile());
        assertEquals("glb", doc.getMetadata().get("format"));
        assertEquals("2.0", doc.getMetadata().get("assetVersion"));
        assertEquals(false, doc.getMetadata().get("hasBinaryChunk"));
    }

    @Test
    void rejectsMalformedJson(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.gltf");
        Files.writeString(p, "{ \"asset\": { \"version\": \"2.0\" ", StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void rejectsNonGltfJson(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("other.json");
        Files.writeString(p, "{\"name\": \"not a gltf\"}", StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void rejectsGlbWithWrongMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.glb");
        byte[] glb = buildGlb("{\"asset\":{\"version\":\"2.0\"}}", false);
        glb[0] = 'X'; // 破坏 "glTF" 魔数
        Files.write(p, glb);

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void detectsByContentWithoutExtension(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("noext");
        Files.writeString(json, "{\"asset\":{\"version\":\"2.0\"}}", StandardCharsets.UTF_8);
        assertTrue(handler.canHandle(json.toFile()));

        Path bin = dir.resolve("noext_bin");
        Files.write(bin, buildGlb("{\"asset\":{\"version\":\"2.0\"}}", false));
        assertTrue(handler.canHandle(bin.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.gltf")));
        assertTrue(handler.canHandle(new java.io.File("b.glb")));
        assertFalse(handler.canHandle(new java.io.File("c.obj")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.gltf");
        Files.writeString(p, "{\"asset\":{\"version\":\"2.0\"}}", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.gltf").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v.gltf");
        Files.writeString(p, "{\"asset\":{\"version\":\"2.0\"}}", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 辅助 ----

    /**
     * 构造 glb 容器。
     *
     * <p>结构：{@code "glTF" + version(4) + length(4)}，随后 JSON chunk
     * （{@code len + "JSON" + data}，按 4 字节对齐），可选 BIN chunk。
     */
    private static byte[] buildGlb(String json, boolean withBinary) throws IOException {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPad = (4 - (jsonBytes.length % 4)) % 4;

        byte[] binBytes = new byte[16];
        for (int i = 0; i < binBytes.length; i++) binBytes[i] = (byte) i;
        int binPad = (4 - (binBytes.length % 4)) % 4;

        int total = 12 + 8 + jsonBytes.length + jsonPad
                + (withBinary ? 8 + binBytes.length + binPad : 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('g');
        out.write('l');
        out.write('T');
        out.write('F');
        writeLe32(out, 2);       // version
        writeLe32(out, total);   // declared total length

        writeLe32(out, jsonBytes.length);
        writeLe32(out, 0x4E4F534A); // "JSON"
        out.write(jsonBytes);
        out.write(new byte[jsonPad]);

        if (withBinary) {
            writeLe32(out, binBytes.length);
            writeLe32(out, 0x004E4942); // "BIN\0"
            out.write(binBytes);
            out.write(new byte[binPad]);
        }
        return out.toByteArray();
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }
}

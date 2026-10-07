package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StlFileHandlerTest {

    private final StlFileHandler handler = new StlFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("stl", handler.getExtension());
        assertEquals("model/stl", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesBinaryStl(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("cube.stl");
        // 单位立方体所在平面的两个三角面
        Files.write(p, buildBinaryStl("binary cube header", new Tri[]{
                tri(0f, 0f, 1f, 0, 0, 0, 1, 0, 0, 0, 1, 0),
                tri(0f, 0f, 1f, 0, 0, 0, 0, 1, 0, 0, 0, 1)
        }));

        Document doc = handler.parse(p.toFile());
        assertEquals("model/stl", doc.getMimeType());
        assertEquals("stl", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("binary", doc.getMetadata().get("encoding"));
        assertEquals(2L, doc.getMetadata().get("triangleCount"));
        assertEquals(6L, doc.getMetadata().get("vertexCount"));
        assertEquals(true, doc.getMetadata().get("hasNormals"));
        assertEquals("binary cube header", doc.getMetadata().get("headerText"));

        assertEquals(1.0, (Double) doc.getMetadata().get("sizeX"), 1e-6);
        assertEquals(1.0, (Double) doc.getMetadata().get("sizeY"), 1e-6);
        assertEquals(1.0, (Double) doc.getMetadata().get("sizeZ"), 1e-6);
    }

    /**
     * 二进制 STL 的 80 字节头部常常以 "solid" 开头，此时不能误判为 ASCII。
     */
    @Test
    void binaryStlStartingWithSolidIsNotMisreadAsAscii(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("tricky.stl");
        Files.write(p, buildBinaryStl("solid exported by some tool", new Tri[]{
                tri(0f, 0f, 1f, 0, 0, 0, 1, 0, 0, 0, 1, 0)
        }));

        Document doc = handler.parse(p.toFile());
        assertEquals("binary", doc.getMetadata().get("encoding"),
                "长度精确匹配时应判为二进制");
        assertEquals(1L, doc.getMetadata().get("triangleCount"));
    }

    @Test
    void parsesAsciiStl(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("pyramid.stl");
        Files.writeString(p, """
                solid pyramid
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 4 0 0
                      vertex 0 4 0
                    endloop
                  endfacet
                  facet normal 0 0 -1
                    outer loop
                      vertex 1 1 0
                      vertex 0 1 0
                      vertex 1 0 0
                    endloop
                  endfacet
                endsolid pyramid
                """, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals("ascii", doc.getMetadata().get("encoding"));
        assertEquals("pyramid", doc.getMetadata().get("solidName"));
        assertEquals(2L, doc.getMetadata().get("triangleCount"));
        assertEquals(6L, doc.getMetadata().get("vertexCount"));
        assertEquals(true, doc.getMetadata().get("hasNormals"));
        // 顶点最大坐标为 (4,4,0)，故 x/y 尺寸均为 4，z 为 0
        assertEquals(4.0, (Double) doc.getMetadata().get("sizeX"), 1e-6);
        assertEquals(4.0, (Double) doc.getMetadata().get("sizeY"), 1e-6);
        assertEquals(0.0, (Double) doc.getMetadata().get("sizeZ"), 1e-6);
    }

    @Test
    void rejectsContentThatIsNeitherEncoding(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.stl");
        Files.writeString(p, "this is not a model at all, just prose", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void rejectsBinaryHeaderWithInconsistentSize(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.stl");
        // 声明 10 个三角面，但文件长度对不上
        byte[] stl = buildBinaryStl("hdr", new Tri[]{
                tri(0f, 0f, 1f, 0, 0, 0, 1, 0, 0, 0, 1, 0)
        });
        byte[] tampered = stl.clone();
        ByteBuffer.wrap(tampered).order(ByteOrder.LITTLE_ENDIAN).putInt(80, 10);
        Files.write(p, tampered);

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void truncatedBinaryFileIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("truncated.stl");
        byte[] stl = buildBinaryStl("hdr", new Tri[]{
                tri(0f, 0f, 1f, 0, 0, 0, 1, 0, 0, 0, 1, 0),
                tri(0f, 0f, 1f, 1, 0, 0, 0, 1, 0, 1, 1, 0)
        });
        byte[] cut = new byte[stl.length - 20];
        System.arraycopy(stl, 0, cut, 0, cut.length);
        Files.write(p, cut);

        Document doc = handler.parse(p.toFile());
        // 长度不再精确匹配，且不以 solid 开头 → 判为无效
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.stl")));
        assertFalse(handler.canHandle(new java.io.File("b.obj")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.stl");
        Files.write(p, buildBinaryStl("hdr", new Tri[]{
                tri(0f, 0f, 1f, 0, 0, 0, 1, 0, 0, 0, 1, 0)
        }));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.stl").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v.stl");
        Files.write(p, buildBinaryStl("hdr", new Tri[]{
                tri(0f, 0f, 1f, 0, 0, 0, 1, 0, 0, 0, 1, 0)
        }));
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 辅助 ----

    /** 一个三角面：法线 + 三个顶点。 */
    private record Tri(float[] normal, float[][] vertices) {}

    private static Tri tri(float nx, float ny, float nz,
                           float x1, float y1, float z1,
                           float x2, float y2, float z2,
                           float x3, float y3, float z3) {
        return new Tri(new float[]{nx, ny, nz},
                new float[][]{{x1, y1, z1}, {x2, y2, z2}, {x3, y3, z3}});
    }

    /**
     * 构造二进制 STL。
     *
     * @param header    80 字节头部的文本内容
     * @param triangles 三角面列表
     */
    private static byte[] buildBinaryStl(String header, Tri[] triangles) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[80];
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(headerBytes, 0, head, 0, Math.min(headerBytes.length, 80));
        out.write(head);
        writeLe32(out, triangles.length);
        for (Tri t : triangles) {
            for (float c : t.normal()) {
                writeFloatLe(out, c);
            }
            for (float[] v : t.vertices()) {
                writeFloatLe(out, v[0]);
                writeFloatLe(out, v[1]);
                writeFloatLe(out, v[2]);
            }
            writeLe16(out, 0); // attribute byte count
        }
        return out.toByteArray();
    }

    private static void writeFloatLe(ByteArrayOutputStream out, float f) {
        writeLe32(out, Float.floatToIntBits(f));
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }
}

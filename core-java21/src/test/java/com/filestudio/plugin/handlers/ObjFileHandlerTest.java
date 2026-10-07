package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ObjFileHandlerTest {

    private final ObjFileHandler handler = new ObjFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("obj", handler.getExtension());
        assertEquals("model/obj", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void countsGeometry(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("cube.obj");
        Files.writeString(p, SAMPLE_OBJ, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals("model/obj", doc.getMimeType());
        assertEquals("obj", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(8, doc.getMetadata().get("vertexCount"));
        assertEquals(4, doc.getMetadata().get("normalCount"));
        assertEquals(4, doc.getMetadata().get("textureCount"));
        assertEquals(6, doc.getMetadata().get("faceCount"), "立方体 6 个面");
        assertEquals(18, doc.getMetadata().get("polygonCount"), "6 个三角面共 18 个索引");
        assertEquals(true, doc.getMetadata().get("hasNormals"));
        assertEquals(true, doc.getMetadata().get("hasTextureCoords"));
    }

    @Test
    void collectsObjectsGroupsAndMaterials(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("named.obj");
        Files.writeString(p, """
                # a comment line
                mtllib scene.mtl

                o Cube
                g CubeGroup
                usemtl Red
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3

                o Sphere
                usemtl Blue
                v 0 0 1
                f 1 2 4
                """, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(java.util.List.of("Cube", "Sphere"), doc.getMetadata().get("objects"));
        assertEquals(java.util.List.of("CubeGroup"), doc.getMetadata().get("groups"));
        assertEquals(java.util.List.of("Red", "Blue"), doc.getMetadata().get("materials"));
        assertEquals(java.util.List.of("scene.mtl"), doc.getMetadata().get("mtllib"));
    }

    @Test
    void handlesQuadsAndWhitespaceVariants(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("quad.obj");
        Files.writeString(p, """
                v\t0 0 0
                v   1 0 0
                v   0 1 0
                v   0 0 1
                f\t1/1/1 2/2/1 3/3/1 4/4/1
                """, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(4, doc.getMetadata().get("vertexCount"));
        assertEquals(1, doc.getMetadata().get("faceCount"));
        assertEquals(4, doc.getMetadata().get("polygonCount"), "四边形有 4 个索引");
        assertEquals(false, doc.getMetadata().get("hasNormals"));
    }

    @Test
    void ignoresCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("commented.obj");
        Files.writeString(p, """
                # leading comment

                # another
                v 0 0 0

                #v 9 9 9  <- commented out, must not count
                v 1 1 1
                f 1 1
                """, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("vertexCount"), "注释里的 v 不应被统计");
    }

    @Test
    void rejectsNonObjContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.obj");
        Files.writeString(p, "{\"json\": true}\n", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void detectsObjWithoutExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        Files.writeString(p, "v 0 0 0\nf 1 1 1\n", StandardCharsets.UTF_8);
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.obj")));
        assertFalse(handler.canHandle(new java.io.File("b.stl")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.obj");
        Files.writeString(p, SAMPLE_OBJ, StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.obj").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v.obj");
        Files.writeString(p, SAMPLE_OBJ, StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    /** 立方体：8 顶点 / 6 法线 / 4 纹理 / 6 三角面。 */
    private static final String SAMPLE_OBJ = """
            # cube
            mtllib cube.mtl
            o Cube
            v -1 -1 -1
            v  1 -1 -1
            v  1  1 -1
            v -1  1 -1
            v -1 -1  1
            v  1 -1  1
            v  1  1  1
            v -1  1  1
            vn 0 0 -1
            vn 0 0 1
            vn -1 0 0
            vn 1 0 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            usemtl CubeMaterial
            f 1/1/1 2/2/1 3/3/1
            f 1/1/1 3/3/1 4/4/1
            f 5/1/2 6/2/2 7/3/2
            f 5/1/2 7/3/2 8/4/2
            f 1/1/3 5/2/3 8/3/3
            f 1/1/3 8/3/3 4/4/3
            """;
}

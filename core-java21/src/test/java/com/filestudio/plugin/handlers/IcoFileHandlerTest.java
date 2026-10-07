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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IcoFileHandlerTest {

    private final IcoFileHandler handler = new IcoFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("ico", handler.getExtension());
        assertEquals("image/x-icon", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void listsImageEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.ico");
        Files.write(p, buildIco(1, new int[][]{
                {16, 16, 32, 1240},
                {32, 32, 32, 4096},
                {48, 48, 32, 9216}
        }));

        Document doc = handler.parse(p.toFile());
        assertEquals("image/x-icon", doc.getMimeType());
        assertEquals("ico", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("icon", doc.getMetadata().get("type"));
        assertEquals(3, doc.getMetadata().get("imageCount"));
        assertEquals(48, doc.getMetadata().get("maxDimension"));
        assertEquals(32, doc.getMetadata().get("maxBitDepth"));
        assertEquals(1240L + 4096L + 9216L, doc.getMetadata().get("payloadBytes"));

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> images =
                (List<java.util.Map<String, Object>>) doc.getMetadata().get("images");
        assertEquals(3, images.size());
        assertEquals(16, images.get(0).get("width"));
        assertEquals(16, images.get(0).get("height"));
        assertEquals(1240, images.get(0).get("bytes"));
    }

    /** 目录项宽/高为 0 表示 256。 */
    @Test
    void decodesZeroDimensionAs256(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.ico");
        Files.write(p, buildIco(1, new int[][]{{0, 0, 32, 40960}}));

        Document doc = handler.parse(p.toFile());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> images =
                (List<java.util.Map<String, Object>>) doc.getMetadata().get("images");
        assertEquals(256, images.get(0).get("width"));
        assertEquals(256, images.get(0).get("height"));
        assertEquals(256, doc.getMetadata().get("maxDimension"));
    }

    @Test
    void recognisesCursorType(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("cur.cur");
        Files.write(p, buildIco(2, new int[][]{{32, 32, 32, 1024}}));

        Document doc = handler.parse(p.toFile());
        assertEquals("cursor", doc.getMetadata().get("type"));
        assertEquals("cur", doc.getMetadata().get("format"));
    }

    @Test
    void rejectsContentWithoutIcoStructure(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.ico");
        Files.writeString(p, "just text, not an icon resource", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    /** 偏移越界的"伪 ICO"应被 canHandle 拒绝。 */
    @Test
    void rejectsDirectoryEntryWithOutOfRangeOffset(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.ico");
        byte[] ico = buildIco(1, new int[][]{{16, 16, 32, 64}});
        // 把首个条目的 imageOffset 改到文件末尾之外
        ico[6 + 12] = (byte) 0xFF;
        ico[6 + 13] = (byte) 0xFF;
        Files.write(p, ico);

        assertFalse(handler.canHandle(p.toFile()), "偏移越界不应判定为 ICO");
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.ico")));
        assertTrue(handler.canHandle(new java.io.File("b.cur")));
        assertFalse(handler.canHandle(new java.io.File("c.png")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.ico");
        Files.write(p, buildIco(1, new int[][]{{16, 16, 32, 64}}));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.ico").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v.ico");
        Files.write(p, buildIco(1, new int[][]{{16, 16, 32, 64}}));
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 辅助 ----

    /**
     * 构造 ICO 字节。
     *
     * @param type 1=ICO，2=CUR
     * @param entries 每项为 {宽, 高, 位深, 字节数}
     */
    private static byte[] buildIco(int type, int[][] entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(le16(0));                 // 保留
        out.write(le16(type));
        out.write(le16(entries.length));

        int offset = 6 + entries.length * 16;
        for (int[] e : entries) {
            out.write(e[0] & 0xFF);         // 宽（0 表示 256）
            out.write(e[1] & 0xFF);         // 高
            out.write(0);                   // 调色板颜色数
            out.write(0);                   // 保留
            out.write(le16(1));             // 颜色平面
            out.write(le16(e[2]));          // 位深
            out.write(le32(e[3]));          // 图像字节数
            out.write(le32(offset));        // 图像偏移
            offset += e[3];
        }
        // 追加各图像的负载，保证偏移/长度不越界
        for (int[] e : entries) {
            out.write(new byte[e[3]]);
        }
        return out.toByteArray();
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF)};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF),
                (byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 24) & 0xFF)};
    }
}

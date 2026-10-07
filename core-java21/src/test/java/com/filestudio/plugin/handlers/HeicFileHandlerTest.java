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

class HeicFileHandlerTest {

    private final HeicFileHandler handler = new HeicFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("heic", handler.getExtension());
        assertEquals("image/heic", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesHeicContainer(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("photo.heic");
        Files.write(p, buildHeif("heic", 4032, 3024, "hvc1", 1));

        Document doc = handler.parse(p.toFile());
        assertEquals("image/heic", doc.getMimeType());
        assertEquals("heic", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("heic", doc.getMetadata().get("majorBrand"));
        assertEquals(4032, doc.getMetadata().get("width"));
        assertEquals(3024, doc.getMetadata().get("height"));
        assertEquals(12.19, (Double) doc.getMetadata().get("megapixels"), 0.02);
        assertEquals(1, doc.getMetadata().get("itemCount"));
        assertEquals(List.of("hvc1"), doc.getMetadata().get("itemTypes"));
        assertEquals("HEVC (H.265)", doc.getMetadata().get("codec"));
        assertEquals(true, doc.getMetadata().get("parsedFromHeaderOnly"));
    }

    /** 同一容器族也覆盖 AVIF。 */
    @Test
    void parsesAvif(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("image.avif");
        Files.write(p, buildHeif("avif", 1920, 1080, "av01", 1));

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("avif", doc.getMetadata().get("format"));
        assertEquals(1920, doc.getMetadata().get("width"));
        assertEquals("AV1", doc.getMetadata().get("codec"));
    }

    /** 渐变图由多个 image item 组成，meta 中会含 grid 盒。 */
    @Test
    void detectsGridDerivedImages(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("grid.heic");
        Files.write(p, buildHeif("mif1", 8192, 4096, "grid", 4));

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("gridDerived"));
        assertEquals(1, doc.getMetadata().get("gridDescriptorCount"));
        assertEquals(4, doc.getMetadata().get("itemCount"));
        assertEquals("grid (derived)", doc.getMetadata().get("codec"));
    }

    @Test
    void rejectsNonIsoBmffContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.heic");
        Files.writeString(p, "this is definitely not an ISO-BMFF container", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    /** MP4 也有 ftyp 盒，但品牌不属 HEIF 家族，不应被认作 HEIC。 */
    @Test
    void rejectsMp4WithFtypBox(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("clip.mp4");
        Files.write(p, buildFtypOnly("isom"));

        assertFalse(handler.canHandle(p.toFile()), "MP4 的 ftyp 品牌不属 HEIF");
    }

    @Test
    void detectsHeifWithoutExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        Files.write(p, buildHeif("heic", 640, 480, "hvc1", 1));
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.heic")));
        assertTrue(handler.canHandle(new java.io.File("b.heif")));
        assertFalse(handler.canHandle(new java.io.File("c.png")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.heic");
        Files.write(p, buildHeif("heic", 64, 64, "hvc1", 1));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.heic").toFile()));
    }

    // ---- 辅助 ----

    /** 构造 ftyp 盒。 */
    private static byte[] box(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w32(o, payload.length + 8);
        o.write(type.getBytes(StandardCharsets.US_ASCII));
        o.write(payload);
        return o.toByteArray();
    }

    /** 只含 ftyp 的最小文件。 */
    private static byte[] buildFtypOnly(String majorBrand) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(majorBrand.getBytes(StandardCharsets.US_ASCII));
        w32(p, 0);                         // minor_version
        p.write(majorBrand.getBytes(StandardCharsets.US_ASCII)); // compatible brand
        return box("ftyp", p.toByteArray());
    }

    /** 构造 ftyp + meta{hdlr, iinf{infe}, iprp{ipco{ispe}}} 的最小 HEIF。 */
    private static byte[] buildHeif(String majorBrand, int width, int height,
                                    String itemType, int itemCount) throws IOException {
        ByteArrayOutputStream ftypPayload = new ByteArrayOutputStream();
        ftypPayload.write(majorBrand.getBytes(StandardCharsets.US_ASCII));
        w32(ftypPayload, 0);
        ftypPayload.write("mif1".getBytes(StandardCharsets.US_ASCII));
        ftypPayload.write(majorBrand.getBytes(StandardCharsets.US_ASCII));
        byte[] ftyp = box("ftyp", ftypPayload.toByteArray());

        // infe (version 2): version+flags(4) + item_ID(2) + protection(2) + item_type(4)
        ByteArrayOutputStream infePayload = new ByteArrayOutputStream();
        infePayload.write(2);                       // version
        infePayload.write(0); infePayload.write(0); infePayload.write(0);
        w16(infePayload, 1);                        // item_ID
        w16(infePayload, 0);                        // item_protection_index
        infePayload.write(itemType.getBytes(StandardCharsets.US_ASCII));
        byte[] infe = box("infe", infePayload.toByteArray());

        // iinf: version+flags(4) + entry_count(2) + infe...
        ByteArrayOutputStream iinfPayload = new ByteArrayOutputStream();
        iinfPayload.write(0);
        iinfPayload.write(0); iinfPayload.write(0); iinfPayload.write(0);
        w16(iinfPayload, itemCount);
        for (int i = 0; i < itemCount; i++) {
            iinfPayload.write(infe);
        }
        byte[] iinf = box("iinf", iinfPayload.toByteArray());

        // ispe: version+flags(4) + width(4) + height(4)
        ByteArrayOutputStream ispePayload = new ByteArrayOutputStream();
        ispePayload.write(0);
        ispePayload.write(0); ispePayload.write(0); ispePayload.write(0);
        w32(ispePayload, width);
        w32(ispePayload, height);
        byte[] ispe = box("ispe", ispePayload.toByteArray());

        // 渐变图：ipco 内除 ispe 外还有一个 grid 描述盒
        ByteArrayOutputStream ipcoPayload = new ByteArrayOutputStream();
        ipcoPayload.write(ispe);
        if ("grid".equals(itemType)) {
            ByteArrayOutputStream gridPayload = new ByteArrayOutputStream();
            gridPayload.write(0);
            gridPayload.write(0); gridPayload.write(0); gridPayload.write(0);
            gridPayload.write(1);   // rows_minus_one
            gridPayload.write(1);   // columns_minus_one
            ipcoPayload.write(box("grid", gridPayload.toByteArray()));
        }
        byte[] ipco = box("ipco", ipcoPayload.toByteArray());
        byte[] iprp = box("iprp", ipco);

        // hdlr: version+flags(4) + pre_defined(4) + handler_type(4)
        ByteArrayOutputStream hdlrPayload = new ByteArrayOutputStream();
        hdlrPayload.write(0);
        hdlrPayload.write(0); hdlrPayload.write(0); hdlrPayload.write(0);
        w32(hdlrPayload, 0);
        hdlrPayload.write("pict".getBytes(StandardCharsets.US_ASCII));
        byte[] hdlr = box("hdlr", hdlrPayload.toByteArray());

        ByteArrayOutputStream metaPayload = new ByteArrayOutputStream();
        // meta 是 FullBox：先写 4 字节 version+flags，子盒紧随其后
        metaPayload.write(0);
        metaPayload.write(0); metaPayload.write(0); metaPayload.write(0);
        metaPayload.write(hdlr);
        metaPayload.write(iinf);
        metaPayload.write(iprp);
        byte[] meta = box("meta", metaPayload.toByteArray());

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(ftyp);
        all.write(meta);
        return all.toByteArray();
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

}

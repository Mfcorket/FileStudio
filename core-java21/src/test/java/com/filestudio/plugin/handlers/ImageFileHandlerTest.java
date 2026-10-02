package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImageFileHandlerTest {

    private final ImageFileHandler handler = new ImageFileHandler();

    @Test
    void declaresImageFormat() {
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
        assertEquals("image/png", handler.getMimeType());
        assertTrue(handler.getDescription().contains("image"));
    }

    @Test
    void canHandleKnownExtensions() {
        assertTrue(handler.canHandle(new java.io.File("a.png")));
        assertTrue(handler.canHandle(new java.io.File("b.jpg")));
        assertTrue(handler.canHandle(new java.io.File("c.jpeg")));
        assertTrue(handler.canHandle(new java.io.File("d.gif")));
        assertTrue(handler.canHandle(new java.io.File("e.bmp")));
        assertTrue(handler.canHandle(new java.io.File("f.webp")));
        assertFalse(handler.canHandle(new java.io.File("g.txt")));
    }

    @Test
    void parsesPngDimensions(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.png");
        // 签名 + IHDR: width=64, height=32, bitDepth=8
        byte[] png = new byte[25];
        System.arraycopy(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, // 签名
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',         // IHDR
        }, 0, png, 0, 16);
        png[16] = 0x00; png[17] = 0x00; png[18] = 0x00; png[19] = 0x40; // width=64
        png[20] = 0x00; png[21] = 0x00; png[22] = 0x00; png[23] = 0x20; // height=32
        png[24] = 8;                                                  // bitDepth
        Files.write(p, png);

        Document doc = handler.parse(p.toFile());
        assertEquals(64, doc.getMetadata().get("width"));
        assertEquals(32, doc.getMetadata().get("height"));
        assertEquals(8, doc.getMetadata().get("bitsPerPixel"));
        assertEquals("png", doc.getMetadata().get("format"));
        assertEquals("image/png", doc.getMimeType());
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesGifDimensions(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("anim.gif");
        byte[] gif = new byte[11];
        System.arraycopy("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, gif, 0, 6);
        gif[6] = 10; gif[7] = 0;  // width=10 LE
        gif[8] = 20; gif[9] = 0;  // height=20 LE
        gif[10] = (byte) 0x87;    // packed: GCT present, 8 bits
        Files.write(p, gif);

        Document doc = handler.parse(p.toFile());
        assertEquals(10, doc.getMetadata().get("width"));
        assertEquals(20, doc.getMetadata().get("height"));
        assertEquals("gif", doc.getMetadata().get("format"));
    }

    @Test
    void parsesBmpDimensions(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("pic.bmp");
        byte[] bmp = new byte[30];
        bmp[0] = 'B'; bmp[1] = 'M';
        bmp[2] = 0x36; bmp[3] = 0; bmp[4] = 0; bmp[5] = 0; // file size
        bmp[10] = 54; bmp[11] = 0; bmp[12] = 0; bmp[13] = 0; // data offset
        bmp[14] = 40; bmp[15] = 0; bmp[16] = 0; bmp[17] = 0; // header size
        bmp[18] = 0x20; bmp[19] = 0; bmp[20] = 0; bmp[21] = 0; // width=32 LE
        bmp[22] = 0x10; bmp[23] = 0; bmp[24] = 0; bmp[25] = 0; // height=16 LE
        bmp[26] = 1; bmp[27] = 0;                            // planes
        bmp[28] = 24; bmp[29] = 0;                            // bits per pixel
        Files.write(p, bmp);

        Document doc = handler.parse(p.toFile());
        assertEquals(32, doc.getMetadata().get("width"));
        assertEquals(16, doc.getMetadata().get("height"));
        assertEquals(24, doc.getMetadata().get("bitsPerPixel"));
        assertEquals("bmp", doc.getMetadata().get("format"));
    }

    @Test
    void parsesJpegDimensions(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("photo.jpg");
        // FF D8 FF C0, segLen=0x0011, precision=8, height=0x0100(256), width=0x0200(512)
        byte[] jpg = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xC0,
                0x00, 0x11,
                0x08,
                0x01, 0x00,
                0x02, 0x00,
                0x03,
                0x01, 0x11, 0x00,
                0x02, 0x11, 0x00,
        };
        Files.write(p, jpg);

        Document doc = handler.parse(p.toFile());
        assertEquals(512, doc.getMetadata().get("width"));
        assertEquals(256, doc.getMetadata().get("height"));
        assertEquals("jpg", doc.getMetadata().get("format"));
        assertEquals("image/jpeg", doc.getMimeType());
    }

    @Test
    void unknownImageContentCannotBeParsed(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("broken.png");
        byte[] junk = "this is not a png at all, just some random text".getBytes();
        Files.write(p, junk);
        // canHandle 应拒绝（既非扩展名匹配也非魔数匹配）
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.png");
        byte[] png = new byte[25];
        System.arraycopy(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
        }, 0, png, 0, 16);
        Files.write(p, png);
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.png").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.png");
        byte[] png = new byte[25];
        System.arraycopy(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
        }, 0, png, 0, 16);
        Files.write(p, png);
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }
}

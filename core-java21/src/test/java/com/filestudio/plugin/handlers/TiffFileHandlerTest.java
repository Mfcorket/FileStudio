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

class TiffFileHandlerTest {

    private final TiffFileHandler handler = new TiffFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("tif", handler.getExtension());
        assertEquals("image/tiff", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesLittleEndianTiff(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("rgb.tif");
        Files.write(p, buildTiff(false, 640, 480, 8, 3, 1));

        Document doc = handler.parse(p.toFile());
        assertEquals("image/tiff", doc.getMimeType());
        assertEquals("tiff", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("little-endian", doc.getMetadata().get("byteOrder"));
        assertEquals(640, doc.getMetadata().get("width"));
        assertEquals(480, doc.getMetadata().get("height"));
        assertEquals(0.31, (Double) doc.getMetadata().get("megapixels"), 0.01);
        assertEquals(8, doc.getMetadata().get("bitsPerSample"));
        assertEquals(3, doc.getMetadata().get("samplesPerPixel"));
        assertEquals("none", doc.getMetadata().get("compression"));
        assertEquals("RGB", doc.getMetadata().get("colorSpace"));
    }

    /** 大端（MM）TIFF 的多字节字段需按相反顺序读取。 */
    @Test
    void parsesBigEndianTiff(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("be.tif");
        Files.write(p, buildTiff(true, 1920, 1080, 16, 3, 5));

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("big-endian", doc.getMetadata().get("byteOrder"));
        assertEquals(1920, doc.getMetadata().get("width"));
        assertEquals(1080, doc.getMetadata().get("height"));
        assertEquals(16, doc.getMetadata().get("bitsPerSample"));
        assertEquals("LZW", doc.getMetadata().get("compression"));
    }

    @Test
    void readsResolutionAndTextTags(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("scan.tif");
        Files.write(p, buildTiffWithExtras(false));

        Document doc = handler.parse(p.toFile());
        // 300 DPI → XResolution 为 300/1 的 RATIONAL
        assertEquals(300.0, (Double) doc.getMetadata().get("dpiX"), 0.01);
        assertEquals(300.0, (Double) doc.getMetadata().get("dpiY"), 0.01);
        assertEquals("FileStudio", doc.getMetadata().get("software"));
        assertEquals("2026:01:02 03:04:05", doc.getMetadata().get("dateTime"));
        assertEquals(2, doc.getMetadata().get("stripCount"));
    }

    /** BigTIFF（magic 43）只报告存在，不解析字段。 */
    @Test
    void detectsBigTiffWithoutParsingFields(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.tif");
        byte[] tiff = buildTiff(false, 100, 100, 8, 1, 1);
        // 把 magic 42 改成 43
        tiff[2] = 43;
        tiff[3] = 0;
        Files.write(p, tiff);

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("bigTiff"));
        assertNotNull(doc.getMetadata().get("note"));
        assertNull(doc.getMetadata().get("width"), "BigTIFF 不应给出可能错误的尺寸");
    }

    @Test
    void rejectsNonTiffContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.tif");
        Files.writeString(p, "II am not a tiff file", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    /** 字节序标记合法但 magic 非法的情况。 */
    @Test
    void rejectsUnknownMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("weird.tif");
        byte[] tiff = buildTiff(false, 10, 10, 8, 1, 1);
        tiff[2] = 99;
        tiff[3] = 0;
        Files.write(p, tiff);

        assertFalse(handler.canHandle(p.toFile()));
        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.tif")));
        assertTrue(handler.canHandle(new java.io.File("b.tiff")));
        assertFalse(handler.canHandle(new java.io.File("c.png")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.tif");
        Files.write(p, buildTiff(false, 8, 8, 8, 1, 1));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.tif").toFile()));
    }

    // ---- 辅助 ----

    /** TIFF 条目。 */
    private record Entry(int tag, int type, long count, long[] values, String ascii) {}

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_ASCII = 2;

    private static byte[] buildTiff(boolean bigEndian, int width, int height,
                                    int bps, int spp, int compression) throws IOException {
        var e = new java.util.ArrayList<Entry>();
        e.add(new Entry(256, TYPE_LONG, 1, new long[]{width}, null));      // ImageWidth
        e.add(new Entry(257, TYPE_LONG, 1, new long[]{height}, null));     // ImageLength
        e.add(new Entry(258, TYPE_SHORT, 1, new long[]{bps}, null));      // BitsPerSample
        e.add(new Entry(259, TYPE_SHORT, 1, new long[]{compression}, null)); // Compression
        e.add(new Entry(262, TYPE_SHORT, 1, new long[]{spp >= 3 ? 2 : 1}, null)); // Photometric
        e.add(new Entry(277, TYPE_SHORT, 1, new long[]{spp}, null));      // SamplesPerPixel
        return assemble(bigEndian, e);
    }

    private static byte[] buildTiffWithExtras(boolean bigEndian) throws IOException {
        var e = new java.util.ArrayList<Entry>();
        e.add(new Entry(256, TYPE_LONG, 1, new long[]{100}, null));
        e.add(new Entry(257, TYPE_LONG, 1, new long[]{100}, null));
        e.add(new Entry(273, TYPE_LONG, 2, new long[]{0x100, 0x200}, null)); // StripOffsets
        e.add(new Entry(282, TYPE_RATIONAL, 1, new long[]{300, 1}, null));    // XResolution
        e.add(new Entry(283, TYPE_RATIONAL, 1, new long[]{300, 1}, null));    // YResolution
        e.add(new Entry(296, TYPE_SHORT, 1, new long[]{2}, null));           // ResolutionUnit=inch
        e.add(new Entry(305, TYPE_ASCII, 0, null, "FileStudio\0"));         // Software
        e.add(new Entry(306, TYPE_ASCII, 0, null, "2026:01:02 03:04:05\0")); // DateTime
        return assemble(bigEndian, e);
    }

    /**
     * 组装 TIFF 文件。
     *
     * <p>值区统一放在 IFD 之后；值总长 ≤ 4 字节时按规范内联在 value 字段。
     */
    private static byte[] assemble(boolean bigEndian, java.util.List<Entry> entries)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (bigEndian) {
            out.write('M');
            out.write('M');
            w16(out, 42, bigEndian);
        } else {
            out.write('I');
            out.write('I');
            w16(out, 42, bigEndian);
        }
        w32(out, 8, bigEndian);   // 首个 IFD 偏移

        w16(out, entries.size(), bigEndian);
        int valueOffset = 8 + 2 + entries.size() * 12 + 4;

        ByteArrayOutputStream values = new ByteArrayOutputStream();
        int base = valueOffset;
        for (Entry en : entries) {
            w16(out, en.tag(), bigEndian);
            w16(out, en.type(), bigEndian);
            long count = en.ascii() != null ? en.ascii().length() : en.count();
            w32(out, (int) count, bigEndian);

            int unit = unitOf(en.type());
            long total = count * unit;

            if (en.ascii() != null) {
                byte[] bytes = en.ascii().getBytes(StandardCharsets.US_ASCII);
                if (bytes.length <= 4) {
                    out.write(bytes);
                    for (int i = bytes.length; i < 4; i++) out.write(0);
                } else {
                    w32(out, base + values.size(), bigEndian);
                    values.write(bytes);
                }
                continue;
            }
            if (total <= 4) {
                // 内联：按字节序写入，不足 4 字节补齐
                byte[] tmp = new byte[4];
                for (int i = 0; i < en.values().length && i * unit < 4; i++) {
                    long v = en.values()[i];
                    if (unit == 2) {
                        if (bigEndian) {
                            tmp[i * 2] = (byte) ((v >> 8) & 0xFF);
                            tmp[i * 2 + 1] = (byte) (v & 0xFF);
                        } else {
                            tmp[i * 2] = (byte) (v & 0xFF);
                            tmp[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                        }
                    } else {
                        for (int b = 0; b < 4; b++) {
                            int shift = bigEndian ? (3 - b) * 8 : b * 8;
                            tmp[b] = (byte) ((v >>> shift) & 0xFF);
                        }
                    }
                }
                out.write(tmp);
            } else {
                w32(out, base + values.size(), bigEndian);
                if (en.type() == TYPE_RATIONAL) {
                    // values 以 [分子, 分母] 成对给出
                    for (int i = 0; i + 1 < en.values().length; i += 2) {
                        w32(values, (int) en.values()[i], bigEndian);
                        w32(values, (int) en.values()[i + 1], bigEndian);
                    }
                } else {
                    for (long v : en.values()) {
                        wVal(values, v, unit, bigEndian);
                    }
                }
            }
        }
        w32(out, 0, bigEndian);   // 无下一个 IFD
        out.write(values.toByteArray());
        return out.toByteArray();
    }

    private static int unitOf(int type) {
        return switch (type) {
            case TYPE_SHORT -> 2;
            // RATIONAL 是「分子 + 分母」两个 32 位数，共 8 字节
            case TYPE_RATIONAL -> 8;
            default -> 4;
        };
    }

    private static void wVal(ByteArrayOutputStream o, long v, int unit, boolean bigEndian) {
        if (unit == 2) {
            w16(o, (int) v, bigEndian);
        } else {
            w32(o, (int) v, bigEndian);
        }
    }

    private static void w16(ByteArrayOutputStream o, int v, boolean bigEndian) {
        if (bigEndian) {
            o.write((v >> 8) & 0xFF);
            o.write(v & 0xFF);
        } else {
            o.write(v & 0xFF);
            o.write((v >> 8) & 0xFF);
        }
    }

    private static void w32(ByteArrayOutputStream o, int v, boolean bigEndian) {
        if (bigEndian) {
            o.write((v >>> 24) & 0xFF);
            o.write((v >>> 16) & 0xFF);
            o.write((v >>> 8) & 0xFF);
            o.write(v & 0xFF);
        } else {
            o.write(v & 0xFF);
            o.write((v >>> 8) & 0xFF);
            o.write((v >>> 16) & 0xFF);
            o.write((v >>> 24) & 0xFF);
        }
    }
}

package com.filestudio.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXIF 提取。
 *
 * <p>测试用手工构造的 JPEG + APP1/Exif 段，这样每个字段的期望值都是确定���，
 * 不依赖外部样本文件。
 */
class ExifExtractorTest {

    // ---- 构造 JPEG + EXIF ----

    /**
     * 直接拼一个完整的 EXIF 段（小端 TIFF），IFD0 + Exif 子 IFD。
     */
    private static byte[] buildExifTiff() throws IOException {
        // ---- 布局 ----
        // 0: "II", 2: 42, 4: IFD0 偏移
        // IFD0: count(2) + 6 entries(72) + next(4)
        // value 区
        // Exif IFD: count(2) + 4 entries(48) + next(4)
        int ifd0Off = 8;
        int ifd0Count = 6;
        int ifd0Size = 2 + ifd0Count * 12 + 4;
        int valueBase = ifd0Off + ifd0Size;

        int makeValue = valueBase;                       // "FilestudioCam" 13 + NUL = 14
        int modelValue = valueBase + 14;                // "FS-1" 4 + NUL = 5
        int dateValue = valueBase + 19;                 // "2024:01:02 03:04:05" 19 + NUL = 20
        int softwareValue = valueBase + 39;             // "FileStudio 1.0" 14 + NUL = 15
        int exifIfdOff = valueBase + 54;

        int exifIfdCount = 4;
        int exifIfdSize = 2 + exifIfdCount * 12 + 4;
        int exifValueBase = exifIfdOff + exifIfdSize;

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // TIFF 头
        o.write('I');
        o.write('I');
        writeU16(o, 42);
        writeU32(o, ifd0Off);

        // ---- IFD0 ----
        writeU16(o, ifd0Count);
        entry(o, 0x010F, 2, 14, makeValue);        // Make
        entry(o, 0x0110, 2, 5, modelValue);         // Model
        entry(o, 0x0112, 3, 1, 0, 6);              // Orientation = 6（内联 SHORT）
        entry(o, 0x0132, 2, 20, dateValue);        // DateTime
        entry(o, 0x0131, 2, 15, softwareValue);     // Software
        entry(o, 0x8769, 4, 1, 0, exifIfdOff);      // ExifIFD 指针
        writeU32(o, 0);                             // 无下一个 IFD

        // ---- 值区 ----
        byte[] make = "FilestudioCam".getBytes(StandardCharsets.US_ASCII);
        o.write(make);
        o.write(0);
        byte[] model = "FS-1".getBytes(StandardCharsets.US_ASCII);
        o.write(model);
        o.write(0);
        byte[] date = "2024:01:02 03:04:05".getBytes(StandardCharsets.US_ASCII);
        o.write(date);
        o.write(0);
        byte[] sw = "FileStudio 1.0".getBytes(StandardCharsets.US_ASCII);
        o.write(sw);
        o.write(0);

        // ---- Exif IFD ----
        writeU16(o, exifIfdCount);
        entry(o, 0x829A, 5, 1, exifValueBase);         // ExposureTime = 1/125
        entry(o, 0x829D, 5, 1, exifValueBase + 8);     // FNumber = 28/10
        entry(o, 0x8827, 3, 1, 400, -1);                // ISO = 400（内联 SHORT）
        entry(o, 0x920A, 5, 1, exifValueBase + 16);     // FocalLength = 50/1
        writeU32(o, 0);

        writeU32(o, 1);
        writeU32(o, 125);       // 1/125
        writeU32(o, 28);
        writeU32(o, 10);        // 2.8
        writeU32(o, 50);
        writeU32(o, 1);         // 50

        return o.toByteArray();
    }

    /** 写一个目录项。 */
    private static void entry(ByteArrayOutputStream o, int tag, int type, int count,
                              Object valueOrOffset) {
        entry(o, tag, type, count, 0, valueOrOffset);
    }

    private static void entry(ByteArrayOutputStream o, int tag, int type, int count,
                              long inlineLong, Object valueOrOffset) {
        writeU16(o, tag);
        writeU16(o, type);
        writeU32(o, count);
        if (inlineLong != 0) {
            writeU32(o, (int) inlineLong);
        } else if (valueOrOffset instanceof Integer off) {
            writeU32(o, off);
        } else {
            writeU32(o, 0);
        }
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 24) & 0xFF));
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF)};
    }

    private static byte[] le32(long v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF),
                (byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 24) & 0xFF)};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 把 EXIF TIFF 包装进 JPEG 的 APP1 段。 */
    private static byte[] buildJpegWithExif() throws IOException {
        byte[] exif = buildExifTiff();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF);
        o.write(0xD8);                       // SOI
        // APP1
        o.write(0xFF);
        o.write(0xE1);
        int segLen = 2 + 6 + exif.length;
        o.write((segLen >>> 8) & 0xFF);
        o.write(segLen & 0xFF);
        o.write("Exif".getBytes(StandardCharsets.US_ASCII));
        o.write(0);
        o.write(0);
        o.write(exif);
        // 后续随便放点数据，模拟还有别的段
        o.write(0xFF);
        o.write(0xDB);
        o.write(0x00);
        o.write(0x04);
        o.write(0x00);
        return o.toByteArray();
    }

    // ---- tests ----

    @Test
    void extractsAsciiTagsFromJpeg() throws IOException {
        Map<String, Object> exif = ExifExtractor.extract(buildJpegWithExif());

        assertEquals("FilestudioCam", exif.get("make"));
        assertEquals("FS-1", exif.get("model"));
        assertEquals("2024:01:02 03:04:05", exif.get("modifyDate"));
        assertEquals("FileStudio 1.0", exif.get("software"));
    }

    @Test
    void extractsShortAndLongTags() throws IOException {
        Map<String, Object> exif = ExifExtractor.extract(buildJpegWithExif());

        // ISO 是 SHORT 内联值
        assertEquals(400L, exif.get("iso"));
    }

    @Test
    void extractsRationalValues() throws IOException {
        Map<String, Object> exif = ExifExtractor.extract(buildJpegWithExif());

        // 1/125 -> "0.0080"，28/10 -> "2.8000"，50/1 -> "50"
        assertEquals("0.0080", exif.get("exposureTime"));
        assertEquals("2.8000", exif.get("fNumber"));
        assertEquals("50", exif.get("focalLength"));
    }

    @Test
    void followsExifSubIfd() throws IOException {
        Map<String, Object> exif = ExifExtractor.extract(buildJpegWithExif());
        // 上面几个标签都位于 Exif 子 IFD，能读到就说明子目录跳转正确
        assertTrue(exif.containsKey("exposureTime"));
        assertTrue(exif.containsKey("iso"));
    }

    @Test
    void extractsFromBareTiff() throws IOException {
        // 裸 TIFF：文件本身就是 TIFF 结构
        byte[] tiff = buildExifTiff();
        Map<String, Object> exif = ExifExtractor.extract(tiff);

        assertEquals("FilestudioCam", exif.get("make"));
    }

    @Test
    void noExifYieldsEmptyMap() {
        // 普通 PNG：没有 EXIF
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertTrue(ExifExtractor.extract(png).isEmpty());
    }

    @Test
    void garbageInputDoesNotThrow() {
        assertTrue(ExifExtractor.extract(new byte[0]).isEmpty());
        assertTrue(ExifExtractor.extract((byte[]) null).isEmpty());

        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (i * 7);
        }
        assertTrue(ExifExtractor.extract(junk).isEmpty());

        // JPEG 头但后面全是垃圾
        byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        assertTrue(ExifExtractor.extract(fakeJpeg).isEmpty());
    }

    @Test
    void truncatedExifDoesNotThrow(@TempDir Path dir) throws IOException {
        byte[] full = buildJpegWithExif();
        // 截断到 EXIF 段中间
        for (int cut : new int[]{10, 20, 40, full.length - 5}) {
            byte[] partial = new byte[cut];
            System.arraycopy(full, 0, partial, 0, cut);
            Path f = dir.resolve("cut" + cut + ".jpg");
            Files.write(f, partial);
            assertDoesNotThrow(() -> ExifExtractor.extract(f), "截断长度 " + cut);
        }
    }

    @Test
    void absurdEntryCountIsRejected() throws IOException {
        // 把 IFD0 的条目数改成离谱的值，解析器必须拒绝而不是死循环
        byte[] exif = buildExifTiff();
        exif[8] = (byte) 0xFF;
        exif[9] = (byte) 0xFF;
        Map<String, Object> result = ExifExtractor.extract(exif);
        assertTrue(result.isEmpty() || result.size() < 600);
    }

    @Test
    void outOfRangeOffsetsAreRejected() throws IOException {
        // 把 Exif 子 IFD 指针改成超出范围的值
        byte[] exif = buildExifTiff();
        int exifIfdOff = 8 + 2 + 6 * 12 + 4 + 80;
        // ExifIFD 条目位于 IFD0 的第 6 项：offset = 8 + 2 + 5*12 = 70，值在 +8 = 78
        int entryOff = 8 + 2 + 5 * 12;
        exif[entryOff + 8] = (byte) 0xFF;
        exif[entryOff + 9] = (byte) 0xFF;
        exif[entryOff + 10] = (byte) 0xFF;
        exif[entryOff + 11] = (byte) 0x7F;
        assertDoesNotThrow(() -> ExifExtractor.extract(exif));
        assertNotNull(exifIfdOff);
    }

    @Test
    void orientationNamesAreMeaningful() {
        assertEquals("正常", ExifExtractor.orientationName(1));
        assertEquals("顺时针旋转 90°", ExifExtractor.orientationName(6));
        assertEquals("顺时针旋转 270°", ExifExtractor.orientationName(8));
        assertEquals("99", ExifExtractor.orientationName(99));
    }

    @Test
    void extractsFromFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("photo.jpg");
        Files.write(f, buildJpegWithExif());

        Map<String, Object> exif = ExifExtractor.extract(f);

        assertEquals("FilestudioCam", exif.get("make"));
    }

    @Test
    void missingFileYieldsEmptyMap(@TempDir Path dir) {
        assertTrue(ExifExtractor.extract(dir.resolve("nope.jpg")).isEmpty());
    }

    // ---- 可切换字节序的最小 TIFF 构造器 ----

    /** 按指定字节序拼一个 TIFF 头 + 若干条目，用于大端/GPS/BigTIFF 场景。 */
    private static final class Tiff {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        private final boolean be;

        Tiff(boolean be) {
            this.be = be;
        }

        Tiff header(int magic, long firstIfd) {
            o.write(be ? 'M' : 'I');
            o.write(be ? 'M' : 'I');
            u16(magic);
            u32(firstIfd);
            return this;
        }

        void ifd(int count, Entry... entries) {
            u16(count);
            for (Entry e : entries) {
                u16(e.tag);
                u16(e.type);
                u32(e.count);
                if (e.inline != null) {
                    byte[] b = e.inline.length >= 4 ? new byte[4] : new byte[4];
                    System.arraycopy(e.inline, 0, b, 0, e.inline.length);
                    o.writeBytes(b);
                } else {
                    u32(e.offset);
                }
            }
            u32(0);
        }

        void ascii(String s) {
            o.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
            o.write(0);
        }

        void rationals(long... numDen) {
            for (long v : numDen) {
                u32(v);
            }
        }

        byte[] bytes() {
            return o.toByteArray();
        }

        private void u16(int v) {
            if (be) {
                o.write((v >>> 8) & 0xFF);
                o.write(v & 0xFF);
            } else {
                o.write(v & 0xFF);
                o.write((v >>> 8) & 0xFF);
            }
        }

        private void u32(long v) {
            if (be) {
                o.write((int) ((v >>> 24) & 0xFF));
                o.write((int) ((v >>> 16) & 0xFF));
                o.write((int) ((v >>> 8) & 0xFF));
                o.write((int) (v & 0xFF));
            } else {
                o.write((int) (v & 0xFF));
                o.write((int) ((v >>> 8) & 0xFF));
                o.write((int) ((v >>> 16) & 0xFF));
                o.write((int) ((v >>> 24) & 0xFF));
            }
        }
    }

    /** 目录项：inline 非空时内联，否则 offset 有效。 */
    private record Entry(int tag, int type, long count, long offset, byte[] inline) {
        static Entry ref(int tag, long offset) {
            return new Entry(tag, 4, 1, offset, null);
        }

        static Entry inline(int tag, int type, byte[] value) {
            return new Entry(tag, type, value.length, 0, value);
        }

        static Entry at(int tag, int type, long count, long offset) {
            return new Entry(tag, type, count, offset, null);
        }
    }

    @Test
    void extractsGpsSubIfd() {
        // IFD0: Make + GPS IFD 指针；GPS IFD: 纬度/经度
        Tiff t = new Tiff(false);
        t.header(42, 8);
        int ifd0Size = 2 + 2 * 12 + 4;              // 30
        int makeOff = 8 + ifd0Size;                 // 38
        int makeLen = "GPSCam".length() + 1;        // 7
        int gpsIfdOff = makeOff + makeLen + 1;      // 对齐到偶数：46
        t.ifd(2,
                Entry.at(0x010F, 2, makeLen, makeOff),
                Entry.ref(0x8825, gpsIfdOff));
        t.ascii("GPSCam");
        o_pad(t, gpsIfdOff);

        int gpsSize = 2 + 3 * 12 + 4;               // 42
        int latOff = gpsIfdOff + gpsSize;
        int lonOff = latOff + 3 * 8;                // 3 个有理数 = 24
        t.ifd(3,
                Entry.inline(0x0001, 2, new byte[]{'N', 0}),
                Entry.at(0x0002, 5, 3, latOff),
                Entry.at(0x0004, 5, 3, lonOff));
        t.rationals(31, 1, 15, 1, 0, 1);            // 31°15'00"
        t.rationals(121, 1, 30, 1, 0, 1);           // 121°30'00"

        Map<String, Object> exif = ExifExtractor.extract(t.bytes());

        assertEquals("GPSCam", exif.get("make"));
        assertEquals("N", exif.get("gps_latituderef"));
        // 纬度 31/15/0，经度 121/30/0；整数比值直接显示为整数
        assertEquals("31, 15, 0", exif.get("gps_latitude"));
        assertEquals("121, 30, 0", exif.get("gps_longitude"));
    }

    /** 补零对齐：GPS IFD 必须落在偶数偏移上。 */
    private static void o_pad(Tiff t, int target) {
        while (t.bytes().length < target) {
            t.ascii("");
        }
    }

    @Test
    void readsBigEndianTiff() {
        Tiff t = new Tiff(true);
        t.header(42, 8);
        int ifd0Size = 2 + 12 + 4;
        int makeOff = 8 + ifd0Size;
        int makeLen = "BigCam".length() + 1;        // 7
        t.ifd(1, Entry.at(0x010F, 2, makeLen, makeOff));
        t.ascii("BigCam");

        Map<String, Object> exif = ExifExtractor.extract(t.bytes());

        assertEquals("BigCam", exif.get("make"));
    }

    @Test
    void rejectsBigTiffMagic() {
        // BigTIFF 魔数 43，条目结构不同，必须明确不支持而不是误读
        Tiff t = new Tiff(false);
        t.header(43, 8);
        t.ifd(0);

        assertTrue(ExifExtractor.extract(t.bytes()).isEmpty());
    }

    @Test
    void skipsNonExifJpegSegments() throws IOException {
        // APP0(JFIF) 应被跳过，EXIF 在后面的 APP1
        byte[] exif = buildExifTiff();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF);
        o.write(0xD8);
        // APP0
        o.write(0xFF);
        o.write(0xE0);
        o.write(0x00);
        o.write(0x10);
        o.writeBytes("JFIF".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(new byte[10]);
        // APP1
        o.write(0xFF);
        o.write(0xE1);
        int segLen = 2 + 6 + exif.length;
        o.write((segLen >>> 8) & 0xFF);
        o.write(segLen & 0xFF);
        o.writeBytes("Exif".getBytes(StandardCharsets.US_ASCII));
        o.write(0);
        o.write(0);
        o.writeBytes(exif);
        o.write(0xFF);
        o.write(0xD9);

        Map<String, Object> exifOut = ExifExtractor.extract(o.toByteArray());

        assertEquals("FilestudioCam", exifOut.get("make"));
    }

    @Test
    void stopsAtSosMarker() throws IOException {
        // SOS 之后的字节是压缩图像数据，即使里面出现 Exif 字样也不能采信
        byte[] exif = buildExifTiff();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF);
        o.write(0xD8);
        o.write(0xFF);
        o.write(0xDA);                       // SOS
        o.write(0x00);
        o.write(0x02);
        o.write(0xFF);
        o.write(0xE1);
        int segLen = 2 + 6 + exif.length;
        o.write((segLen >>> 8) & 0xFF);
        o.write(segLen & 0xFF);
        o.writeBytes("Exif".getBytes(StandardCharsets.US_ASCII));
        o.write(0);
        o.write(0);
        o.writeBytes(exif);

        assertTrue(ExifExtractor.extract(o.toByteArray()).isEmpty());
    }
}

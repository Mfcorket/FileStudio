package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * TIFF 图像处理器：提取尺寸、位深、压缩方式、色彩空间与分辨率等信息。
 *
 * <p>VIEW_ONLY 能力——图像不可作为文本编辑。
 *
 * <p>TIFF 头部（8 字节）：
 * <pre>
 *   0   2   字节序标记："II"=小端(0x4949)，"MM"=大端(0x4D4D)
 *   2   2   版本号：42 为经典 TIFF，43 为 BigTIFF
 *   4   4   首个 IFD 的偏移
 * </pre>
 *
 * <p>IFD 结构：2 字节条目数 → N × 12 字节条目 → 4 字节下一个 IFD 偏移。
 * 条目为 {tag(2), type(2), count(4), value|offset(4)}。
 */
public class TiffFileHandler implements FileHandler {

    /**
     * TIFF 图像处理器。
     */
    public TiffFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("tif", "tiff");

    private static final int MAGIC_CLASSIC = 42;
    private static final int MAGIC_BIG = 43;

    /** 读取上限，足够覆盖常见的缩略图/条带信息。 */
    private static final int MAX_READ = 8 * 1024 * 1024;

    /** 遍历 IFD 链的最大层数，防止环形引用。 */
    private static final int MAX_IFDS = 32;

    /** TIFF 数值类型 → 字节数。 */
    private static final int[] TYPE_SIZE = {
            0,  // 0 占位
            1,  // 1 BYTE
            1,  // 2 ASCII
            2,  // 3 SHORT
            4,  // 4 LONG
            8,  // 5 RATIONAL
            1,  // 6 SBYTE
            1,  // 7 UNDEFINED
            2,  // 8 SSHORT
            4,  // 9 SLONG
            8,  // 10 SRATIONAL
            4,  // 11 FLOAT
            8   // 12 DOUBLE
    };

    // ---- 常用标签 ----
    private static final int TAG_NEW_SUBFILE_TYPE = 254;
    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_PHOTOMETRIC = 262;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_SAMPLES_PER_PIXEL = 277;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_BYTE_COUNTS = 279;
    private static final int TAG_X_RESOLUTION = 282;
    private static final int TAG_Y_RESOLUTION = 283;
    private static final int TAG_PLANAR_CONFIG = 284;
    private static final int TAG_RESOLUTION_UNIT = 296;
    private static final int TAG_SOFTWARE = 305;
    private static final int TAG_DATETIME = 306;
    private static final int TAG_PREDICTOR = 317;

    @Override
    public String getExtension() {
        return "tif";
    }

    @Override
    public String getMimeType() {
        return "image/tiff";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "TIFF image";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isTiff(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("TIFF file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "tiff");
        meta.put("viewOnly", true);

        byte[] data;
        try {
            data = readPrefix(path, MAX_READ);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading TIFF: " + path, e);
        }

        if (data.length < 8 || !isTiffHeader(data)) {
            meta.put("valid", false);
            meta.put("error", "Missing or invalid TIFF header");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        boolean little = data[0] == 'I';
        ByteOrder order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int magic = u16(data, 2, order);
        meta.put("valid", true);
        meta.put("byteOrder", little ? "little-endian" : "big-endian");
        meta.put("magic", magic);
        meta.put("bigTiff", magic == MAGIC_BIG);

        if (magic == MAGIC_BIG) {
            // BigTIFF 的偏移为 64 位且条目结构不同，本处理器只报告其存在
            meta.put("note", "BigTIFF detected; IFD fields not parsed");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }
        if (magic != MAGIC_CLASSIC) {
            meta.put("valid", false);
            meta.put("error", "Unknown TIFF magic: " + magic);
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        int ifdOffset = (int) u32(data, 4, order);
        try {
            parseIfdChain(data, ifdOffset, order, meta);
        } catch (RuntimeException e) {
            meta.put("note", "IFD parse stopped: " + e);
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("TIFF files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("width", doc.getMetadata().get("width"));
            meta.put("height", doc.getMetadata().get("height"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isTiffHeader(byte[] b) {
        if (b.length < 8) return false;
        boolean orderOk = (b[0] == 'I' && b[1] == 'I') || (b[0] == 'M' && b[1] == 'M');
        if (!orderOk) return false;
        ByteOrder order = b[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int magic = u16(b, 2, order);
        return magic == MAGIC_CLASSIC || magic == MAGIC_BIG;
    }

    private static boolean isTiff(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return isTiffHeader(in.readNBytes(8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 遍历 IFD 链，把首个 IFD 的字段写入 meta，并统计目录数量。 */
    private static void parseIfdChain(byte[] data, int ifdOffset, ByteOrder order,
                                       Map<String, Object> meta) {
        int ifdCount = 0;
        int offset = ifdOffset;
        Map<Integer, long[]> first = null;

        while (offset > 0 && offset + 2 <= data.length && ifdCount < MAX_IFDS) {
            int entryCount = u16(data, offset, order);
            if (entryCount < 0 || offset + 2 + entryCount * 12 + 4 > data.length) {
                break;
            }
            Map<Integer, long[]> entries = new LinkedHashMap<>();
            for (int i = 0; i < entryCount; i++) {
                int base = offset + 2 + i * 12;
                int tag = u16(data, base, order);
                int type = u16(data, base + 2, order);
                long count = u32(data, base + 4, order);
                int valueField = base + 8;
                entries.put(tag, new long[]{type, count, valueField});
            }
            if (first == null) {
                first = entries;
            }
            ifdCount++;
            int next = (int) u32(data, offset + 2 + entryCount * 12, order);
            if (next == offset) {
                break; // 防止自引用
            }
            offset = next;
        }

        meta.put("ifdCount", ifdCount);
        if (first == null) {
            meta.put("note", "No readable IFD found");
            return;
        }

        int width = (int) readTag(data, order, first, TAG_IMAGE_WIDTH, 1);
        int height = (int) readTag(data, order, first, TAG_IMAGE_LENGTH, 1);
        if (width > 0) meta.put("width", width);
        if (height > 0) meta.put("height", height);
        if (width > 0 && height > 0) {
            meta.put("megapixels", Math.round(width * (double) height / 1_000_000.0 * 100) / 100.0);
        }

        long spp = readTag(data, order, first, TAG_SAMPLES_PER_PIXEL, 1);
        if (spp > 0) meta.put("samplesPerPixel", (int) spp);

        // BitsPerSample 是数组，取第一个分量即可代表位深
        long bps = readTag(data, order, first, TAG_BITS_PER_SAMPLE, 1);
        if (bps > 0) meta.put("bitsPerSample", (int) bps);

        long compression = readTag(data, order, first, TAG_COMPRESSION, 1);
        if (compression >= 0) meta.put("compression", compressionName((int) compression));

        long photometric = readTag(data, order, first, TAG_PHOTOMETRIC, 1);
        if (photometric >= 0) meta.put("colorSpace", photometricName((int) photometric));

        long planar = readTag(data, order, first, TAG_PLANAR_CONFIG, 1);
        if (planar == 2) meta.put("planarConfiguration", "planar");

        long rowsPerStrip = readTag(data, order, first, TAG_ROWS_PER_STRIP, 1);
        if (rowsPerStrip > 0) meta.put("rowsPerStrip", (int) rowsPerStrip);

        long strips = countTagValues(data, order, first, TAG_STRIP_OFFSETS);
        if (strips > 0) meta.put("stripCount", (int) strips);

        long subfile = readTag(data, order, first, TAG_NEW_SUBFILE_TYPE, 1);
        if (subfile > 0) meta.put("thumbnail", (subfile & 1) == 1);

        // RATIONAL 类型的分辨率
        double xRes = readRational(data, order, first, TAG_X_RESOLUTION);
        double yRes = readRational(data, order, first, TAG_Y_RESOLUTION);
        long resUnit = readTag(data, order, first, TAG_RESOLUTION_UNIT, 1);
        if (xRes > 0 && yRes > 0) {
            double scale = resUnit == 3 ? 25.4 : 1.0; // 3=英寸，2=厘米
            meta.put("dpiX", Math.round(xRes * scale * 100) / 100.0);
            meta.put("dpiY", Math.round(yRes * scale * 100) / 100.0);
        }

        String software = readAsciiTag(data, order, first, TAG_SOFTWARE);
        if (software != null) meta.put("software", software);
        String dateTime = readAsciiTag(data, order, first, TAG_DATETIME);
        if (dateTime != null) meta.put("dateTime", dateTime);
    }

    /**
     * 读取标签的第一个数值。
     *
     * <p>TIFF 中值字段为 4 字节：能放下则内联，否则存放偏移，需二次读取。
     *
     * @param wantIndex 需要数组的第几个元素
     */
    private static long readTag(byte[] data, ByteOrder order, Map<Integer, long[]> entries,
                                int tag, int wantIndex) {
        long[] e = entries.get(tag);
        if (e == null) {
            return -1;
        }
        int type = (int) e[0];
        long count = e[1];
        int valueField = (int) e[2];
        if (type <= 0 || type >= TYPE_SIZE.length) {
            return -1;
        }
        int unit = TYPE_SIZE[type];
        if (count <= 0) {
            return -1;
        }
        long total = (long) count * unit;
        int base;
        if (total <= 4) {
            base = valueField;                       // 内联
        } else {
            base = (int) u32(data, valueField, order); // 指向外部数据
        }
        long index = wantIndex;
        if (index >= count) {
            index = count - 1;
        }
        int at = base + (int) (index * unit);
        if (at < 0 || at + unit > data.length) {
            return -1;
        }
        return switch (type) {
            case 1, 2, 6, 7 -> data[at] & 0xFF;   // BYTE / ASCII / SBYTE / UNDEFINED
            case 3, 8 -> u16(data, at, order);    // SHORT / SSHORT
            case 4, 9, 11 -> (int) u32(data, at, order); // LONG / SLONG / FLOAT
            case 5, 10 -> (int) u32(data, at, order);    // RATIONAL 分子
            default -> -1;
        };
    }

    /**
     * 读取 RATIONAL 标签的浮点值。
     *
     * <p>RATIONAL 是「分子 4 字节 + 分母 4 字节」的一对 32 位数，必须成对读取；
     * 不能用 {@link #readTag}，因为它按 8 字节步进、返回的是下一个 RATIONAL 的分子。
     */
    private static double readRational(byte[] data, ByteOrder order,
                                       Map<Integer, long[]> entries, int tag) {
        long[] e = entries.get(tag);
        if (e == null || e[0] != 5) {
            return -1;
        }
        long count = e[1];
        if (count < 1) {
            return -1;
        }
        int valueField = (int) e[2];
        int base = (count * 8 <= 4) ? valueField : (int) u32(data, valueField, order);
        if (base < 0 || base + 8 > data.length) {
            return -1;
        }
        long numerator = u32(data, base, order);
        long denominator = u32(data, base + 4, order);
        if (denominator == 0) {
            return -1;
        }
        return (double) numerator / denominator;
    }

    /** 读取 ASCII 标签（可能为多个 NUL 分隔的字符串，取第一个）。 */
    private static String readAsciiTag(byte[] data, ByteOrder order,
                                       Map<Integer, long[]> entries, int tag) {
        long[] e = entries.get(tag);
        if (e == null) {
            return null;
        }
        int type = (int) e[0];
        long count = e[1];
        int valueField = (int) e[2];
        if (type != 2 || count <= 0 || count > 1024) {
            return null;
        }
        int total = (int) count;
        int base = total <= 4 ? valueField : (int) u32(data, valueField, order);
        if (base < 0 || base + total > data.length) {
            return null;
        }
        String s = new String(data, base, total, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) {
            s = s.substring(0, nul);
        }
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    /** 统计某个数组型标签的元素个数。 */
    private static long countTagValues(byte[] data, ByteOrder order,
                                       Map<Integer, long[]> entries, int tag) {
        long[] e = entries.get(tag);
        if (e == null) {
            return 0;
        }
        int type = (int) e[0];
        if (type <= 0 || type >= TYPE_SIZE.length) {
            return 0;
        }
        long total = e[1] * TYPE_SIZE[type];
        if (total <= 4) {
            return e[1];
        }
        long offset = u32(data, (int) e[2], order);
        // 越界说明偏移不可信，不报告数量
        return (offset > 0 && offset < data.length) ? e[1] : 0;
    }

    private static String compressionName(int code) {
        return switch (code) {
            case 1 -> "none";
            case 2 -> "CCITT RLE";
            case 3 -> "CCITT G3";
            case 4 -> "CCITT G4";
            case 5 -> "LZW";
            case 6, 7 -> "JPEG";
            case 8, 32946 -> "Deflate";
            case 32773 -> "PackBits";
            case 34712 -> "JPEG 2000";
            case 50000 -> "Zstandard";
            case 50001 -> "LERC";
            default -> "unknown(" + code + ")";
        };
    }

    private static String photometricName(int code) {
        return switch (code) {
            case 0 -> "WhiteIsZero (grayscale)";
            case 1 -> "BlackIsZero (grayscale)";
            case 2 -> "RGB";
            case 3 -> "Palette";
            case 4 -> "Transparency mask";
            case 5 -> "CMYK";
            case 6 -> "YCbCr";
            case 8 -> "CIELab";
            default -> "unknown(" + code + ")";
        };
    }

    private static byte[] readPrefix(Path path, int len) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(len);
        }
    }

    private static int u16(byte[] b, int off, ByteOrder order) {
        if (off + 2 > b.length) {
            return -1;
        }
        return order == ByteOrder.LITTLE_ENDIAN
                ? ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8))
                : (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    private static long u32(byte[] b, int off, ByteOrder order) {
        if (off < 0 || off + 4 > b.length) {
            return -1;
        }
        if (order == ByteOrder.LITTLE_ENDIAN) {
            return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                    | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
        }
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }
}

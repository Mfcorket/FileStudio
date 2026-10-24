package com.filestudio.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EXIF 元数据提取器。
 *
 * <p>EXIF 有两种载体：
 * <ul>
 *   <li><b>JPEG</b>：APP1 段以 {@code Exif\0\0} 开头，其后是 TIFF 结构</li>
 *   <li><b>TIFF</b>：文件本身就是 TIFF 结构，IFD0 里直接带 EXIF 标签</li>
 * </ul>
 *
 * <p>TIFF 结构为：小端/大端标记 → 魔数 42 → IFD0 偏移 → 若干 12 字节目录项，
 * 其中某些项（Exif/GPS 子目录）本身又是偏移。
 *
 * <p>解析全程做边界校验：任何越界、类型不认识或条目数离谱都直接停止，
 * 因此损坏文件只会得到部分结果，不会抛异常。
 */
public final class ExifExtractor {

    /** 最多读取的头部字节数；EXIF 通常在文件开头。 */
    public static final int HEADER_BYTES = 256 * 1024;

    /** IFD 条目数上限，防止畸形偏移导致死循环。 */
    private static final int MAX_ENTRIES = 512;

    /** 子目录嵌套深度上限。 */
    private static final int MAX_DEPTH = 4;

    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_GPS_IFD = 0x8825;

    private ExifExtractor() {}

    /**
     * 从文件提取 EXIF。
     *
     * @param file 目标文件路径
     * @return EXIF 标签表；没有 EXIF 或无法解析时返回空表
     */
    public static Map<String, Object> extract(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return extract(in.readNBytes(HEADER_BYTES));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * 从字节数组提取 EXIF。
     *
     * @param head 文件头部
     * @return EXIF 标签表；没有 EXIF 或无法解析时返回空表
     */
    public static Map<String, Object> extract(byte[] head) {
        if (head == null || head.length < 8) {
            return Map.of();
        }
        // JPEG 的魔数是 FF D8；TIFF ��是 II*\0 或 MM\0*
        boolean jpeg = (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8;
        boolean tiff = isTiffHeader(head, 0);
        if (!jpeg && !tiff) {
            return Map.of();
        }
        int tiffStart = jpeg ? findExifInJpeg(head) : 0;
        if (tiffStart < 0) {
            return Map.of();
        }
        return new Reader(head, tiffStart).readIfd0();
    }

    // ---- JPEG 段扫描 ----

    /** 在 JPEG 段序列里找 APP1(Exif)，返回其 TIFF 结构起始偏移，没有则 -1。 */
    private static int findExifInJpeg(byte[] d) {
        int p = 2;      // 跳过 SOI
        while (p + 4 <= d.length) {
            if ((d[p] & 0xFF) != 0xFF) {
                return -1;
            }
            int marker = d[p + 1] & 0xFF;
            // 填充字节
            if (marker == 0xFF) {
                p++;
                continue;
            }
            // 无长度字段的标记
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                p += 2;
                continue;
            }
            // 到了 SOS，后面是压缩数据，不再有 EXIF
            if (marker == 0xDA || marker == 0xD9) {
                return -1;
            }
            if (p + 4 > d.length) {
                return -1;
            }
            int segLen = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (segLen < 2) {
                return -1;
            }
            int segStart = p + 4;
            if (marker == 0xE1 && segStart + 6 <= d.length
                    && d[segStart] == 'E' && d[segStart + 1] == 'x' && d[segStart + 2] == 'i'
                    && d[segStart + 3] == 'f' && d[segStart + 4] == 0) {
                return segStart + 6;
            }
            p = segStart + segLen - 2;
        }
        return -1;
    }

    private static boolean isTiffHeader(byte[] d, int off) {
        if (off + 4 > d.length) {
            return false;
        }
        boolean le = d[off] == 'I' && d[off + 1] == 'I';
        boolean be = d[off] == 'M' && d[off + 1] == 'M';
        return le || be;
    }

    // ---- TIFF 结构读取 ----

    /** 定位在 TIFF 结构上的游标。 */
    private static final class Reader {
        private final byte[] d;
        private final int base;
        private final boolean little;

        Reader(byte[] data, int tiffStart) {
            this.d = data;
            this.base = tiffStart;
            this.little = data[tiffStart] == 'I';
        }

        Map<String, Object> readIfd0() {
            // 这里的偏移是相对 TIFF 结构起点的，而 u16/u32 取绝对位置
            int magic = u16(base + 2);
            if (magic != 42) {
                // BigTIFF 的魔数是 43，条目结构不同，这里明确不支持
                return Map.of();
            }
            long ifd0 = u32(base + 4);
            return readIfd(ifd0, 0);
        }

        /**
         * 读一个 IFD（含其 Exif/GPS 子目录）。
         */
        private Map<String, Object> readIfd(long rel, int depth) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (depth > MAX_DEPTH || rel < 0) {
                return out;
            }
            // rel 来自文件内容，必须防止相加溢出后变成合法但错误的绝对位置
            int abs = safeOffset(base + rel);
            if (abs < 0) {
                return out;
            }
            int count = u16(abs);
            if (count <= 0 || count > MAX_ENTRIES) {
                return out;
            }

            long exifIfd = -1;
            long gpsIfd = -1;

            for (int i = 0; i < count; i++) {
                int entry = abs + 2 + i * 12;
                if (entry + 12 > d.length) {
                    break;
                }
                int tag = u16(entry);
                int type = u16(entry + 2);
                long num = u32(entry + 4);

                if (tag == TAG_EXIF_IFD) {
                    exifIfd = u32(entry + 8);
                    continue;
                }
                if (tag == TAG_GPS_IFD) {
                    gpsIfd = u32(entry + 8);
                    continue;
                }

                Object value = readValue(entry + 8, type, num);
                if (value == null) {
                    continue;
                }
                String key = tagName(tag);
                if (key == null) {
                    // 未知标签用 0x 前缀保留下来，仍比丢弃信息强
                    out.put(String.format("tag_0x%04X", tag), value);
                } else {
                    out.put(key, value);
                }
            }

            if (exifIfd > 0) {
                out.putAll(readIfd(exifIfd, depth + 1));
            }
            if (gpsIfd > 0) {
                Map<String, Object> gps = readIfd(gpsIfd, depth + 1);
                gps.keySet().stream()
                        .filter(k -> k.startsWith("GPS_"))
                        .forEach(k -> out.put("gps_" + k.substring(4).toLowerCase(), gps.get(k)));
            }
            return out;
        }

        /** 读取条目值；数值超过 4 字节时按 TIFF 规定存放在 valueOffset 处。 */
        private Object readValue(int valueOff, int type, long num) {
            if (num <= 0 || num > 4096) {
                return null;
            }
            int size = typeSize(type);
            if (size <= 0) {
                return null;
            }
            long total = num * size;
            int dataOff;
            if (total <= 4) {
                dataOff = valueOff;
            } else {
                dataOff = safeOffset(base + u32(valueOff));
                if (dataOff < 0 || dataOff + total > d.length) {
                    return null;
                }
            }

            return switch (type) {
                case 2 -> readAscii(dataOff, (int) num);
                case 1, 7 -> readBytes(dataOff, (int) num);
                case 3 -> num == 1 ? (long) u16(dataOff)
                        : join(widen(u16Array(dataOff, (int) num)));
                case 4 -> num == 1 ? u32(dataOff)
                        : join(u32Array(dataOff, (int) num));
                case 9 -> num == 1 ? (long) i32(dataOff)
                        : join(widen(i32Array(dataOff, (int) num)));
                // RATIONAL/SRATIONAL 的 count 是「有理数个数」，每个占两个 32 位字
                case 5 -> rational(u32Array(dataOff, (int) num * 2));
                case 10 -> signedRational(i32Array(dataOff, (int) num * 2));
                default -> null;
            };
        }

        private Object readAscii(int off, int num) {
            int end = Math.min(off + num, d.length);
            int stop = off;
            while (stop < end && d[stop] != 0) {
                stop++;
            }
            if (stop == off) {
                return "";
            }
            return new String(d, off, stop - off, StandardCharsets.UTF_8).trim();
        }

        private Object readBytes(int off, int num) {
            int end = Math.min(off + num, d.length);
            if (end <= off) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = off; i < end; i++) {
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        }

        /** 有理数转可读文本：整数直接给整数，小数保留 4 位。 */
        private Object rational(long[] values) {
            if (values.length == 0) {
                return null;
            }
            if (values.length == 1) {
                return formatRational(values[0], 1L);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < values.length; i += 2) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(formatRational(values[i], values[i + 1]));
            }
            return sb.toString();
        }

        private Object signedRational(int[] values) {
            long[] longs = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                longs[i] = values[i];
            }
            return rational(longs);
        }

        private String formatRational(long numerator, long denominator) {
            if (denominator == 0) {
                return String.valueOf(numerator);
            }
            if (numerator % denominator == 0) {
                return String.valueOf(numerator / denominator);
            }
            return String.format("%.4f", (double) numerator / denominator);
        }

        private Object longs(long first, long... rest) {
            long[] all = new long[rest.length + 1];
            all[0] = first;
            System.arraycopy(rest, 0, all, 1, rest.length);
            return all;
        }

        /** 多值数值的可读形式：单值直接给数，多值用逗号分隔。 */
        private Object join(long[] values) {
            if (values.length == 1) {
                return values[0];
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(values[i]);
            }
            return sb.toString();
        }

        private long[] widen(int[] values) {
            long[] out = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                out[i] = values[i];
            }
            return out;
        }

        private int[] u16Array(int start, int count) {
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = u16(start + i * 2);
            }
            return out;
        }

        private long[] u32Array(int start, int count) {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = u32(start + i * 4);
            }
            return out;
        }

        private static int typeSize(int type) {
            return switch (type) {
                case 1, 2, 6, 7 -> 1;
                case 3, 8 -> 2;
                case 4, 9, 11 -> 4;
                case 5, 10, 12 -> 8;
                default -> 0;
            };
        }

        // ---- 带字节序的原始读取 ----

        /** 绝对偏移必须落在数据区内，否则返回 -1 让调用方放弃该条目。 */

        private int safeOffset(long abs) {
            // 只校验范围与溢出；每个读取方法自己检查自己的字节跨度，
            // 这里不能留 8 字节余量，否则贴近文件末尾的 ASCII 值会被误杀
            return abs < 0 || abs >= d.length ? -1 : (int) abs;
        }

        private int u16(int off) {
            return u16(off, off, 1);
        }

        private int u16(int off, int start, int count) {
            if (start < 0 || start + count * 2 > d.length) {
                return 0;
            }
            int v = 0;
            for (int i = 0; i < count; i++) {
                int p = start + i * 2;
                int b = little ? ((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8))
                        : (((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF));
                v |= b << (16 * i);
            }
            return v;
        }

        private long u32(int off) {
            return u32(off, off, 1);
        }

        private long u32(int off, int start, int count) {
            if (start < 0 || start + count * 4 > d.length) {
                return 0;
            }
            long v = 0;
            for (int i = 0; i < count; i++) {
                int p = start + i * 4;
                long b = little
                        ? ((d[p] & 0xFFL) | ((d[p + 1] & 0xFFL) << 8)
                                | ((d[p + 2] & 0xFFL) << 16) | ((d[p + 3] & 0xFFL) << 24))
                        : (((d[p] & 0xFFL) << 24) | ((d[p + 1] & 0xFFL) << 16)
                                | ((d[p + 2] & 0xFFL) << 8) | (d[p + 3] & 0xFFL));
                v |= b << (32 * i);
            }
            return v;
        }

        private int i32(int off) {
            return (int) u32(off);
        }

        /** 读取有符号 32 位值数组。 */
        private int[] i32Array(int start, int count) {
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = (int) u32(start + i * 4);
            }
            return out;
        }
    }

    // ---- 标签名 ----

    /**
     * EXIF 标签 → 可读名称。
     *
     * @return 名称；未知标签返回 null
     */
    private static String tagName(int tag) {
        return switch (tag) {
            case 0x010E -> "imageDescription";
            case 0x010F -> "make";
            case 0x0110 -> "model";
            case 0x0112 -> "orientation";
            case 0x011A -> "xResolution";
            case 0x011B -> "yResolution";
            case 0x0131 -> "software";
            case 0x0132 -> "modifyDate";
            case 0x013B -> "artist";
            case 0x8298 -> "copyright";
            case 0x829A -> "exposureTime";
            case 0x829D -> "fNumber";
            case 0x8822 -> "exposureProgram";
            case 0x8827 -> "iso";
            case 0x9003 -> "dateTimeOriginal";
            case 0x9004 -> "dateTimeDigitized";
            case 0x9201 -> "shutterSpeedValue";
            case 0x9202 -> "apertureValue";
            case 0x9204 -> "exposureBias";
            case 0x9207 -> "meteringMode";
            case 0x9209 -> "flash";
            case 0x920A -> "focalLength";
            case 0x9286 -> "userComment";
            case 0xA002 -> "pixelWidth";
            case 0xA003 -> "pixelHeight";
            case 0xA405 -> "focalLength35mm";
            case 0xA432 -> "lensInfo";
            case 0xA433 -> "lensMake";
            case 0xA434 -> "lensModel";
            case 0x0100 -> "width";
            case 0x0101 -> "height";
            case 0x0102 -> "bitsPerSample";
            case 0x0103 -> "compression";
            case 0x0106 -> "photometricInterpretation";
            case 0x8769 -> "_exifIfd";
            case 0x8825 -> "_gpsIfd";
            // GPS
            case 0x0001 -> "GPS_LatitudeRef";
            case 0x0002 -> "GPS_Latitude";
            case 0x0003 -> "GPS_LongitudeRef";
            case 0x0004 -> "GPS_Longitude";
            case 0x0005 -> "GPS_AltitudeRef";
            case 0x0006 -> "GPS_Altitude";
            case 0x001D -> "GPS_DateStamp";
            default -> null;
        };
    }

    /**
     * 方向值对应的含义。
     *
     * @param value EXIF 方向值（1-8）
     * @return 对应的中文描述；未知值返回数字字符串
     */
    public static String orientationName(long value) {
        return switch ((int) value) {
            case 1 -> "正常";
            case 2 -> "水平翻转";
            case 3 -> "旋转 180°";
            case 4 -> "垂直翻转";
            case 5 -> "转置";
            case 6 -> "顺时针旋转 90°";
            case 7 -> "反转置";
            case 8 -> "顺时针旋转 270°";
            default -> String.valueOf(value);
        };
    }
}
package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 视频格式处理器：从文件头解析时长、分辨率、帧率等元数据。
 *
 * <p>支持：MP4 / MOV / AVI。
 * VIEW_ONLY 能力——视频不可作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>MP4/MOV：解析 ftyp / moov / mvhd / tkhd box（区分 v0/v1 布局）</li>
 *   <li>AVI：递归查找 RIFF / LIST hdrl / avih 块</li>
 *   <li>MKV/WebM：递归解析 EBML Segment / Info / Tracks 元素</li>
 * </ul>
 *
 * <p>不解析 FLV / WMV 等其余容器：{@link #isVideo(File)} 亦不认可以免误导。
 */
public class VideoFileHandler implements FileHandler {

    private static final Set<String> SUPPORTED =
            Set.of("mp4", "mov", "mkv", "webm", "avi");

    @Override
    public String getExtension() {
        return "mp4";
    }

    @Override
    public String getMimeType() {
        return "video/mp4";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Video file (MP4/MOV/AVI)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isVideo(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Video file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, 65536);
        String realExt = detectFormat(head);
        String mime = mimeFor(realExt);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", realExt);
        meta.put("viewOnly", true);

        VideoInfo info = parseVideoInfo(realExt, head);
        if (info != null) {
            if (info.durationSeconds() > 0) {
                meta.put("durationSeconds", info.durationSeconds());
                meta.put("duration", formatDuration(info.durationSeconds()));
            }
            if (info.width() > 0) meta.put("width", info.width());
            if (info.height() > 0) meta.put("height", info.height());
            if (info.frameRate() > 0) meta.put("frameRate", info.frameRate());
        } else {
            meta.put("metadata", "unavailable");
        }

        return new Document(path, mime, realExt, "", meta, file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Video files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] head = readHead(file.toPath(), 65536);
            String ext = detectFormat(head);
            meta.put("format", ext);
            VideoInfo info = parseVideoInfo(ext, head);
            if (info != null && info.durationSeconds() > 0) {
                meta.put("durationSeconds", info.durationSeconds());
            }
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private record VideoInfo(double durationSeconds, int width, int height, double frameRate) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isVideo(File file) {
        try {
            byte[] head = new byte[12];
            try (var in = Files.newInputStream(file.toPath())) {
                int n = in.read(head);
                if (n < 8) return false;
                // MP4/MOV: ftyp box at offset 0
                if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return true;
                // AVI: RIFF....AVI
                if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                        && head[8] == 'A' && head[9] == 'V' && head[10] == 'I') return true;
                // MKV/WebM: EBML magic 1A 45 DF A3
                // 注意：byte 为有符号，0xDF/0xA3 需用 & 0xFF 后比较，否则恒不成立
                if ((head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                        && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static byte[] readHead(Path path, int len) {
        byte[] head = new byte[len];
        try (var in = Files.newInputStream(path)) {
            int n = in.read(head);
            if (n < 0) n = 0;
            byte[] trimmed = new byte[n];
            System.arraycopy(head, 0, trimmed, 0, n);
            return trimmed;
        } catch (IOException e) {
            throw new FileStudioException("Failed reading video header: " + path, e);
        }
    }

    /** 依据文件头检测视频格式。 */
    static String detectFormat(byte[] h) {
        if (h.length < 12) return "";
        // MP4/MOV: ftyp box
        if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            // 检查 brand
            if (h.length >= 12) {
                String brand = new String(h, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (brand.equals("qt  ")) return "mov";
                if (brand.equals("M4V ")) return "mp4";
            }
            return "mp4";
        }
        // AVI: RIFF....AVI
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'A' && h[9] == 'V' && h[10] == 'I') {
            return "avi";
        }
        // MKV/WebM: EBML magic 1A 45 DF A3
        // 注意：byte 为有符号，0xDF/0xA3 需用 & 0xFF 后比较，否则恒不成立
        if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            return detectEbmlDocType(h);
        }
        return "";
    }

    /**
     * 读取 EBML DocType 区分 matroska / webm。
     * DocType(0x4282) 是 EBML 头(0x1A45DFA3)的子元素，需下钻一层。
     */
    private static String detectEbmlDocType(byte[] h) {
        try {
            int limit = Math.min(h.length, 512);
            int pos = 0;
            while (pos < limit) {
                long[] id = readEbmlVint(h, pos, true);
                if (id == null) return "mkv";
                int idLen = (int) id[1];
                long[] size = readEbmlVint(h, pos + idLen, false);
                if (size == null) return "mkv";
                int sizeLen = (int) size[1];
                int dataStart = pos + idLen + sizeLen;
                int dataEnd = (int) Math.min(dataStart + size[0], limit);

                if (id[0] == 0x1A45DFA3L) {
                    // 进入 EBML 头内部查找 DocType
                    String t = findDocType(h, dataStart, dataEnd);
                    if (t != null) return t;
                }
                if (dataEnd <= pos) return "mkv";
                pos = dataEnd;
            }
        } catch (Exception ignored) {
            // 尽力而为，默认为 matroska
        }
        return "mkv";
    }

    /** 在 EBML 头内查找 DocType(0x4282)。 */
    private static String findDocType(byte[] h, int start, int end) {
        int pos = start;
        int guard = 0;
        while (pos < end && guard++ < 64) {
            long[] id = readEbmlVint(h, pos, true);
            if (id == null) return null;
            int idLen = (int) id[1];
            long[] size = readEbmlVint(h, pos + idLen, false);
            if (size == null) return null;
            int sizeLen = (int) size[1];
            int dataStart = pos + idLen + sizeLen;
            int dataEnd = (int) Math.min(dataStart + size[0], end);

            if (id[0] == 0x4282L) {
                int len = Math.min(8, Math.max(0, dataEnd - dataStart));
                if (len >= 4) {
                    String docType = new String(h, dataStart, len,
                            java.nio.charset.StandardCharsets.US_ASCII).trim();
                    if (docType.equals("webm")) return "webm";
                    if (docType.startsWith("matroska")) return "mkv";
                }
                return null;
            }
            if (dataEnd <= pos) return null;
            pos = dataEnd;
        }
        return null;
    }

    /**
     * 解析 Matroska/WebM（EBML）元数据。
     *
     * <p>结构：Segment(0x18538067) → Info(0x1549A966){TimestampScale, Duration}、
     * Tracks(0x1654AE6B) → TrackEntry(0xAE) → Video(0xE0){PixelWidth, PixelHeight}。
     * Duration 为 float，乘以 TimestampScale（纳秒）得到秒。
     */
    private static VideoInfo parseEbml(byte[] h) {
        EbmlInfo info = new EbmlInfo();
        scanEbml(h, 0, h.length, info, 0);
        double seconds = info.durationUnits * info.timestampScale / 1_000_000_000.0;
        return new VideoInfo(seconds, info.width, info.height, 0);
    }

    /** EBML 扫描累加器。 */
    private static final class EbmlInfo {
        double timestampScale = 1_000_000.0; // 纳秒/单位，默认 1ms
        double durationUnits;
        int width;
        int height;
    }

    /** 递归遍历 EBML 元素，遇到需要的叶子节点即记录。 */
    private static void scanEbml(byte[] h, int start, int end, EbmlInfo info, int depth) {
        if (depth > 6) return;
        int pos = start;
        int guard = 0;
        while (pos < end && guard++ < 8192) {
            long[] id = readEbmlVint(h, pos, true);
            if (id == null) return;
            int idLen = (int) id[1];
            long[] size = readEbmlVint(h, pos + idLen, false);
            if (size == null) return;
            int sizeLen = (int) size[1];
            int dataStart = pos + idLen + sizeLen;
            if (dataStart > end) return;
            int dataEnd = (int) Math.min(dataStart + size[0], end);
            int dataLen = dataEnd - dataStart;

            switch ((int) id[0]) {
                case 0x2AD7B1 -> { // TimestampScale
                    if (dataLen >= 1) info.timestampScale = readEbmlUint(h, dataStart, dataLen);
                }
                case 0x4489 -> { // Duration (float)
                    if (dataLen >= 4) info.durationUnits = readEbmlFloat(h, dataStart, dataLen);
                }
                case 0xB0 -> { // PixelWidth
                    if (dataLen >= 1) info.width = (int) readEbmlUint(h, dataStart, dataLen);
                }
                case 0xBA -> { // PixelHeight
                    if (dataLen >= 1) info.height = (int) readEbmlUint(h, dataStart, dataLen);
                }
                // 需要下钻的容器元素
                case 0x18538067, // Segment
                     0x1549A966, // Info
                     0x1654AE6B, // Tracks
                     0xAE,       // TrackEntry
                     0xE0        // Video
                        -> scanEbml(h, dataStart, dataEnd, info, depth + 1);
                default -> {
                    // 其余元素跳过
                }
            }

            if (dataEnd <= pos) return;
            pos = dataEnd;
        }
    }

    /**
     * 读取 EBML 可变长度整数。
     *
     * @return {@code {value, byteLength}}，读取失败返回 {@code null}
     */
    private static long[] readEbmlVint(byte[] h, int off, boolean keepMarker) {
        if (off < 0 || off >= h.length) return null;
        int first = h[off] & 0xFF;
        if (first == 0) return null;
        int length = 1;
        int mask = 0x80;
        while ((first & mask) == 0) {
            mask >>= 1;
            length++;
            if (length > 8 || mask == 0) return null;
        }
        if (off + length > h.length) return null;
        long value = keepMarker ? first : (first & (mask - 1));
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (h[off + i] & 0xFFL);
        }
        return new long[] {value, length};
    }

    private static long readEbmlUint(byte[] h, int off, int len) {
        long v = 0;
        for (int i = 0; i < len && off + i < h.length; i++) {
            v = (v << 8) | (h[off + i] & 0xFFL);
        }
        return v;
    }

    private static double readEbmlFloat(byte[] h, int off, int len) {
        if (len >= 8) {
            long bits = 0;
            for (int i = 0; i < 8; i++) bits = (bits << 8) | (h[off + i] & 0xFFL);
            return Double.longBitsToDouble(bits);
        }
        if (len == 4) {
            int bits = 0;
            for (int i = 0; i < 4; i++) bits = (bits << 8) | (h[off + i] & 0xFF);
            return Float.intBitsToFloat(bits);
        }
        return 0;
    }

    /** 解析视频元数据。 */
    static VideoInfo parseVideoInfo(String ext, byte[] h) {
        if (h.length == 0) return null;
        try {
            return switch (ext) {
                case "mp4", "mov" -> parseMp4(h);
                case "avi" -> parseAvi(h);
                case "mkv", "webm" -> parseEbml(h);
                default -> null;
            };
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static VideoInfo parseMp4(byte[] h) {
        // 扫描 moov box
        int pos = 0;
        while (pos + 8 <= h.length) {
            int boxSize = beInt(h, pos);
            String boxType = new String(h, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (boxSize < 8 || pos + boxSize > h.length) break;

            if (boxType.equals("moov")) {
                // 在 moov 内扫描 mvhd 和 tkhd
                int moovEnd = pos + boxSize;
                int sub = pos + 8;
                double duration = 0;
                int timescale = 0;
                int width = 0;
                int height = 0;
                while (sub + 8 <= moovEnd) {
                    int subSize = beInt(h, sub);
                    String subType = new String(h, sub + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                    if (subSize < 8 || sub + subSize > moovEnd) break;

                    if (subType.equals("mvhd")) {
                        int v = h[sub + 8] & 0xFF;
                        if (v == 1) {
                            // 64 位 creation/modification，timescale 在 20，duration 在 24（8 字节）
                            if (sub + 8 + 28 > h.length) break;
                            timescale = beInt(h, sub + 8 + 20);
                            long dur = beLong(h, sub + 8 + 24);
                            if (timescale > 0) duration = (double) dur / timescale;
                        } else {
                            // 32 位 creation/modification，timescale 在 12，duration 在 16
                            if (sub + 8 + 20 > h.length) break;
                            timescale = beInt(h, sub + 8 + 12);
                            int dur = beInt(h, sub + 8 + 16);
                            if (timescale > 0) duration = (double) dur / timescale;
                        }
                    } else if (subType.equals("tkhd")) {
                        int v = h[sub + 8] & 0xFF;
                        // width/height 为 16.16 定点数，位于 payload 末尾
                        int wOff = (v == 1) ? 88 : 76;
                        if (sub + 8 + wOff + 8 > h.length) break;
                        width = beInt(h, sub + 8 + wOff) >> 16;
                        height = beInt(h, sub + 8 + wOff + 4) >> 16;
                    }
                    sub += subSize;
                }
                return new VideoInfo(duration, width, height, 0);
            }
            pos += boxSize;
        }
        return null;
    }

    private static VideoInfo parseAvi(byte[] h) {
        // RIFF header: "RIFF"[size:4]"AVI "
        // avih 位于 LIST "hdrl" 之内，需要递归进入列表查找
        return findAvi(h, 12);
    }

    private static VideoInfo findAvi(byte[] h, int start) {
        int pos = start;
        while (pos + 8 <= h.length) {
            String chunkId = new String(h, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = leInt(h, pos + 4);
            if (chunkSize < 0 || pos + 8 + chunkSize > h.length) break;

            if (chunkId.equals("avih")) {
                // dwMicroSecPerFrame at offset 0, dwTotalFrames at offset 16
                if (chunkSize < 20) return null;
                int microSecPerFrame = leInt(h, pos + 8);
                int totalFrames = leInt(h, pos + 8 + 16);
                double frameRate = (microSecPerFrame > 0)
                        ? 1000000.0 / microSecPerFrame : 0;
                double duration = (frameRate > 0)
                        ? (double) totalFrames / frameRate : 0;
                // dwWidth/dwHeight at offset 32/36
                int width = chunkSize >= 40 ? leInt(h, pos + 8 + 32) : 0;
                int height = chunkSize >= 40 ? leInt(h, pos + 8 + 36) : 0;
                return new VideoInfo(duration, width, height, frameRate);
            }

            if (chunkId.equals("LIST")) {
                String listType = new String(h, pos + 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (listType.equals("hdrl") || listType.equals("movi")) {
                    VideoInfo info = findAvi(h, pos + 12);
                    if (info != null) return info;
                }
            }
            pos += 8 + chunkSize + (chunkSize & 1);
        }
        return null;
    }

    private static int beInt(byte[] h, int off) {
        return ((h[off] & 0xFF) << 24) | ((h[off + 1] & 0xFF) << 16)
                | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
    }

    private static long beLong(byte[] h, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[off + i] & 0xFFL);
        }
        return v;
    }

    private static int leInt(byte[] h, int off) {
        return (h[off] & 0xFF) | ((h[off + 1] & 0xFF) << 8)
                | ((h[off + 2] & 0xFF) << 16) | ((h[off + 3] & 0xFF) << 24);
    }

    private static String formatDuration(double seconds) {
        int total = (int) Math.round(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private static String mimeFor(String ext) {
        return switch (ext) {
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            default -> "video/*";
        };
    }
}

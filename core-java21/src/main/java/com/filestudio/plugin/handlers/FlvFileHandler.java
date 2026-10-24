package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * FLV（Flash Video）处理器：解析容器头与文件体标记，提取版本、
 * 是否存在音频/视频流、编码类型（onMetaData 中的 videocodecid）与时长。
 *
 * <p>VIEW_ONLY 能力。
 */
public class FlvFileHandler implements FileHandler {

    /** FLV 处理器。 */
    public FlvFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("flv");

    /** 头对象 + 首 Tag 头（含 header size 域）。 */
    private static final int MAX_READ = 64 * 1024;

    @Override
    public String getExtension() {
        return "flv";
    }

    @Override
    public String getMimeType() {
        return "video/x-flv";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Flash Video (FLV)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isFlv(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("FLV file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, MAX_READ);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "flv");
        meta.put("viewOnly", true);

        // "FLV" + 版本(1) + flags(1) + headerSize(4, big-endian)
        if (head.length < 9 || head[0] != 'F' || head[1] != 'L' || head[2] != 'V') {
            meta.put("valid", false);
            meta.put("error", "Missing FLV signature");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }
        meta.put("valid", true);
        meta.put("containerVersion", head[3] & 0xFF);
        int flags = head[4] & 0xFF;
        meta.put("hasAudio", (flags & 0x01) != 0);
        meta.put("hasVideo", (flags & 0x04) != 0);
        meta.put("flags", flags);
        int headerSize = beInt(head, 5);
        meta.put("headerSize", headerSize);

        // 首个 Tag：type(1) + dataSize(3) + timestamp(3) + timestampExt(1) + streamID(3)
        int tag = headerSize;
        if (tag + 11 <= head.length) {
            int type = head[tag] & 0xFF;
            int dataSize = (head[tag + 1] & 0xFF) << 16
                    | (head[tag + 2] & 0xFF) << 8
                    | (head[tag + 3] & 0xFF);
            int ts = (head[tag + 4] & 0xFF) << 16
                    | (head[tag + 5] & 0xFF) << 8
                    | (head[tag + 6] & 0xFF);
            int tsExt = head[tag + 7] & 0xFF;
            meta.put("firstTagType", typeName(type));
            meta.put("firstTagSize", dataSize);
            meta.put("firstTimestampMs", ((tsExt << 24) | ts));
        }

        // 尝试从 onMetaData 中解析 duration / 编码
        MetaInfo mi = parseMeta(head);
        if (mi.duration() > 0) {
            meta.put("durationSeconds", mi.duration());
            meta.put("duration", formatTime(mi.duration()));
        }
        if (mi.videoCodec() > 0) {
            meta.put("videoCodecId", mi.videoCodec());
        }
        if (mi.audioCodec() > 0) {
            meta.put("audioCodecId", mi.audioCodec());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("FLV files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", "flv");
            meta.put("hasAudio", doc.getMetadata().get("hasAudio"));
            meta.put("hasVideo", doc.getMetadata().get("hasVideo"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private record MetaInfo(double duration, int videoCodec, int audioCodec) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isFlv(File file) {
        try {
            byte[] head = new byte[3];
            try (InputStream in = Files.newInputStream(file.toPath())) {
                int n = in.read(head);
                return n == 3 && head[0] == 'F' && head[1] == 'L' && head[2] == 'V';
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readHead(Path path, int len) {
        byte[] buf = new byte[len];
        try (InputStream in = Files.newInputStream(path)) {
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            if (off == len) return buf;
            byte[] trimmed = new byte[off];
            System.arraycopy(buf, 0, trimmed, 0, off);
            return trimmed;
        } catch (IOException e) {
            throw new FileStudioException("Failed reading FLV header: " + path, e);
        }
    }

    /**
     * 在头部字节中定位 {@code onMetaData} 脚本，提取 duration 与编码 ID。
     *
     * <p>AMF0 中一段属性为：字符串长度(2BE) + "duration" + 类型字节(0=number) + 8 字节大端 double。
     * 附带扫描 videocodecid / audiocodecid。
     */
    static MetaInfo parseMeta(byte[] h) {
        int start = indexOf(h, "onMetaData".getBytes(StandardCharsets.US_ASCII), 0);
        if (start < 0) {
            return new MetaInfo(0, 0, 0);
        }
        double duration = amfNumber(h, indexOf(h, "duration".getBytes(StandardCharsets.US_ASCII), start));
        int vcodec = (int) amfNumber(h, indexOf(h, "videocodecid".getBytes(StandardCharsets.US_ASCII), start));
        int acodec = (int) amfNumber(h, indexOf(h, "audiocodecid".getBytes(StandardCharsets.US_ASCII), start));
        return new MetaInfo(duration, vcodec, acodec);
    }

    /** 若关键词后紧接 AMF0 number（0x00 + 8 字节大端 double）则返回其值，否则 0。 */
    private static double amfNumber(byte[] h, int at) {
        if (at < 0 || at + 9 >= h.length) {
            return 0;
        }
        // AMF 字符串前应有 2 字节长度在前；此处关键词本身是 value 名。
        int amfType = h[at + 8] & 0xFF; // 关键词 = 8 字节（duration）
        if (amfType != 0x00) {
            return 0;
        }
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (h[at + 9 + i] & 0xFFL);
        }
        double d = Double.longBitsToDouble(bits);
        return d > 0 && !Double.isNaN(d) && !Double.isInfinite(d) ? d : 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String typeName(int t) {
        return switch (t) {
            case 8 -> "audio";
            case 9 -> "video";
            case 18 -> "script/metadata";
            default -> "unknown(" + t + ")";
        };
    }

    private static String formatTime(double s) {
        int total = (int) Math.round(s);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int sec = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec)
                : String.format("%d:%02d", m, sec);
    }
}
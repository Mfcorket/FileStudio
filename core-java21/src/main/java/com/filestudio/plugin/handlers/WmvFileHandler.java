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
 * WMV / WMA（ASF，Advanced Systems Format）处理器。
 *
 * <p>ASF 是 Microsoft 的媒体容器：头对象（GUID {@code 30 26 B2 75 ...}）下挂了若干
 * 子对象。这里解析 File Properties Object（时长，单位 100ns）与属性元数据。
 *
 * <p>VIEW_ONLY 能力。
 */
public class WmvFileHandler implements FileHandler {

    private static final Set<String> SUPPORTED = Set.of("wmv", "asf", "wma");

    /** ASF 头对象 GUID：3026B275-8E66-CF11-A6D9-00AA0062CE6C */
    private static final byte[] ASF_HEADER_GUID = {
            0x30, 0x26, (byte) 0xB2, 0x75, (byte) 0x8E, 0x66, (byte) 0xCF, 0x11,
            (byte) 0xA6, (byte) 0xD9, 0x00, (byte) 0xAA, 0x00, 0x62, (byte) 0xCE, 0x6C};

    /** File Properties Object GUID：8CABDCA1-A947-11CF-8EE4-00C00C205365 */
    private static final byte[] FILE_PROPERTIES_GUID = {
            (byte) 0xA1, (byte) 0xDC, (byte) 0xAB, (byte) 0x8C, 0x47, (byte) 0xA9, (byte) 0xCF, 0x11,
            (byte) 0x8E, (byte) 0xE4, 0x00, (byte) 0xC0, 0x0C, 0x20, 0x53, 0x65};

    /** Stream Properties Object GUID：B7DC0791-A9B7-11CF-8EE6-00C00C205365 */
    private static final byte[] STREAM_PROPERTIES_GUID = {
            (byte) 0x91, 0x07, (byte) 0xDC, (byte) 0xB7, (byte) 0xB7, (byte) 0xA9, (byte) 0xCF, 0x11,
            (byte) 0x8E, (byte) 0xE6, 0x00, (byte) 0xC0, 0x0C, 0x20, 0x53, 0x65};

    /** Audio Media GUID：F8699E40-5B4D-11CF-A8FD-00805F5C442B */
    private static final byte[] AUDIO_MEDIA_GUID = {
            0x40, (byte) 0x9E, 0x69, (byte) 0xF8, 0x4D, 0x5B, (byte) 0xCF, 0x11,
            (byte) 0xA8, (byte) 0xFD, 0x00, (byte) 0x80, 0x5F, 0x5C, 0x44, 0x2B};

    /** Video Media GUID：BC19EFC0-5B4D-11CF-A8FD-00805F5C442B */
    private static final byte[] VIDEO_MEDIA_GUID = {
            (byte) 0xC0, (byte) 0xEF, 0x19, (byte) 0xBC, 0x4D, 0x5B, (byte) 0xCF, 0x11,
            (byte) 0xA8, (byte) 0xFD, 0x00, (byte) 0x80, 0x5F, 0x5C, 0x44, 0x2B};

    private static final int MAX_READ = 64 * 1024;

    @Override
    public String getExtension() {
        return "wmv";
    }

    @Override
    public String getMimeType() {
        return "video/x-ms-wmv";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Windows Media Video/Audio (ASF)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isAsf(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("ASF file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, MAX_READ);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", detectExt(file.getName()));
        meta.put("viewOnly", true);

        if (head.length < 30 || !startsWith(head, 0, ASF_HEADER_GUID)) {
            meta.put("valid", false);
            meta.put("error", "Missing ASF header object GUID");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }
        meta.put("valid", true);

        // 头对象：GUID(16) + size(8, LE) + numObjects(4, LE) + reserved(2)
        long headerSize = leLong(head, 16);
        int numObjects = leInt(head, 24);
        meta.put("objectCount", numObjects);
        meta.put("headerSize", headerSize);

        // 扫描子对象：GUID(16) + size(8, LE) + data
        long pos = 30;
        boolean audio = false;
        boolean video = false;
        double duration = 0;      // 100ns 单位
        while (pos + 24 <= Math.min(head.length, headerSize)) {
            byte[] guid = new byte[16];
            System.arraycopy(head, (int) pos, guid, 0, 16);
            long size = leLong(head, (int) pos + 16);
            if (size < 24) break;
            int dataStart = (int) pos + 24;

            if (equals(guid, FILE_PROPERTIES_GUID)) {
                // File Properties: FileID(16) + FileSize(8) + Creation(8) + Packets(8)
                // + PlayDuration(8) + SendDuration(8) + Preroll(8) + Flags(4) ...
                int off = dataStart;
                if (off + 64 <= head.length) {
                    long fileSize = leLong(head, off + 16);
                    long playDuration100ns = leLong(head, off + 40);
                    long preroll = leLong(head, off + 56);
                    meta.put("declaredSize", fileSize);
                    if (playDuration100ns > 0) {
                        duration = playDuration100ns / 10_000_000.0;
                    }
                    meta.put("prerollMs", preroll);
                }
            } else if (equals(guid, STREAM_PROPERTIES_GUID)) {
                // 流对象 payload 起始为 stream type GUID
                int streamType = dataStart;
                if (streamType + 16 <= head.length) {
                    byte[] typeGuid = new byte[16];
                    System.arraycopy(head, streamType, typeGuid, 0, 16);
                    if (equals(typeGuid, AUDIO_MEDIA_GUID)) {
                        audio = true;
                    } else if (equals(typeGuid, VIDEO_MEDIA_GUID)) {
                        video = true;
                    }
                }
            }
            pos += size;
        }

        meta.put("hasAudioStream", audio);
        meta.put("hasVideoStream", video);
        if (duration > 0) {
            meta.put("durationSeconds", duration);
            meta.put("duration", formatTime(duration));
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("ASF files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", detectExt(file.getName()));
            meta.put("valid", doc.getMetadata().get("valid"));
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

    private static String detectExt(String name) {
        String e = extensionOf(name);
        return SUPPORTED.contains(e) ? e : "wmv";
    }

    private static boolean isAsf(File file) {
        try {
            byte[] head = new byte[16];
            try (InputStream in = Files.newInputStream(file.toPath())) {
                int n = in.read(head);
                return n == 16 && startsWith(head, 0, ASF_HEADER_GUID);
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
            throw new FileStudioException("Failed reading ASF header: " + path, e);
        }
    }

    private static boolean startsWith(byte[] data, int off, byte[] sig) {
        if (off + sig.length > data.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if (data[off + i] != sig[i]) return false;
        }
        return true;
    }

    private static boolean equals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static long leLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (b[off + i] & 0xFF)) << (8 * i);
        }
        return v;
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
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
package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 图像格式处理器：从文件头解析尺寸、位深等元数据（不加载像素）。
 *
 * <p>支持：PNG / JPEG / GIF / BMP / WebP。
 * VIEW_ONLY 能力——图像不可作为文本编辑。
 *
 * <p>尺寸解析：
 * <ul>
 *   <li>PNG：IHDR 块，字节 16-19 宽、20-23 高（大端）</li>
 *   <li>JPEG：扫描 SOF 标记（C0-CF）取宽高</li>
 *   <li>GIF：字节 6-7 宽、8-9 高（小端）</li>
 *   <li>BMP：BITMAPINFOHEADER 偏移 18/22（小端）</li>
 *   <li>WebP：VP8 帧，字节 26-29 宽、30-33 高（小端，14 位）</li>
 * </ul>
 */
public class ImageFileHandler implements FileHandler {

    /**
     * 图片格式处理器。
     */
    public ImageFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    @Override
    public String getExtension() {
        return "png";
    }

    @Override
    public String getMimeType() {
        return "image/png";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Raster image (PNG/JPEG/GIF/BMP/WebP)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            // 文件存在：按魔数校验内容（覆盖扩展名，拒绝伪装/损坏文件）
            return MagicBytesBridge.isImage(file);
        }
        // 文件不存在（假设路径 / 新建对话框场景）：按扩展名
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Image file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, 64);
        String realExt = MagicBytesBridge.imageExtension(head);
        String mime = mimeFor(realExt);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", realExt.isEmpty() ? extensionOf(file.getName()) : realExt);
        meta.put("viewOnly", true);

        ImageInfo info = parseDimensions(realExt, head);
        if (info != null) {
            meta.put("width", info.width());
            meta.put("height", info.height());
            if (info.bitsPerPixel() > 0) {
                meta.put("bitsPerPixel", info.bitsPerPixel());
            }
        } else {
            meta.put("dimensions", "unavailable");
        }

        // 图像无文本内容，Document 以空 content + 元数据呈现
        return new Document(path, mime, realExt.isEmpty() ? extensionOf(file.getName()) : realExt,
                "", meta, file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        // VIEW_ONLY：不支持回写为文本。若输出路径与源路径不同则原样复制源文件字节。
        throw new FileStudioException("Image files are view-only; cannot save as text");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] head = readHead(file.toPath(), 64);
            String ext = MagicBytesBridge.imageExtension(head);
            meta.put("format", ext.isEmpty() ? extensionOf(file.getName()) : ext);
            ImageInfo info = parseDimensions(ext, head);
            if (info != null) {
                meta.put("width", info.width());
                meta.put("height", info.height());
            }
        } catch (FileStudioException ignored) {
            // 元数据尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static byte[] readHead(Path path, int len) {
        byte[] head = new byte[len];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int n = in.read(head);
            if (n < 0) n = 0;
            byte[] trimmed = new byte[n];
            System.arraycopy(head, 0, trimmed, 0, n);
            return trimmed;
        } catch (IOException e) {
            throw new FileStudioException("Failed reading image header: " + path, e);
        }
    }

    private record ImageInfo(int width, int height, int bitsPerPixel) {}

    /** 依据魔数判断图像类型并解析尺寸。返回 null 表示无法解析。 */
    static ImageInfo parseDimensions(String ext, byte[] h) {
        if (h.length == 0) return null;
        try {
            switch (ext) {
                case "png":
                    if (h.length >= 24 && h[0] == (byte) 0x89 && h[1] == 'P') {
                        int w = bigEndian(h, 16);
                        int ht = bigEndian(h, 20);
                        int bpp = h[24] & 0xFF;
                        return w > 0 && ht > 0 ? new ImageInfo(w, ht, bpp) : null;
                    }
                    return null;
                case "jpg":
                case "jpeg":
                    return parseJpeg(h);
                case "gif":
                    if (h.length >= 10 && h[0] == 'G') {
                        int w = shortLE(h, 6);
                        int ht = shortLE(h, 8);
                        int packed = h[10] & 0xFF;
                        int bpp = (packed & 0x07) + 1;
                        return w > 0 && ht > 0 ? new ImageInfo(w, ht, bpp) : null;
                    }
                    return null;
                case "bmp":
                    if (h.length >= 26 && h[0] == 'B' && h[1] == 'M') {
                        int w = littleEndian(h, 18);
                        int ht = Math.abs(littleEndian(h, 22)); // 高度可为负（自顶向下）
                        int bpp = shortLE(h, 28); // bpp 为 2 字节
                        return w > 0 && ht > 0 ? new ImageInfo(w, ht, bpp) : null;
                    }
                    return null;
                case "webp":
                    if (h.length >= 34 && h[0] == 'R' && h[1] == 'I') {
                        // VP8: 字节 23=0x9D/0x01/0x2A 帧标志, 26-29 宽, 30-33 高
                        int w = littleEndian14(h, 26);
                        int ht = littleEndian14(h, 30);
                        return w > 0 && ht > 0 ? new ImageInfo(w, ht, 24) : null;
                    }
                    return null;
                default:
                    return null;
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static ImageInfo parseJpeg(byte[] h) {
        int i = 2; // 跳过 FF D8
        while (i + 8 < h.length) {
            if ((h[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = h[i + 1] & 0xFF;
            if (i + 3 >= h.length) break;
            int segLen = ((h[i + 2] & 0xFF) << 8) | (h[i + 3] & 0xFF); // 16 位段长
            if (segLen < 2) break;
            // SOF 标记：C0-CF 排除 C4/DHT、C8/JPG、CC/DAC
            boolean isSof = (marker >= 0xC0 && marker <= 0xCF)
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof && i + 8 < h.length) {
                int ht = ((h[i + 5] & 0xFF) << 8) | (h[i + 6] & 0xFF);
                int w = ((h[i + 7] & 0xFF) << 8) | (h[i + 8] & 0xFF);
                int precision = h[i + 4] & 0xFF;
                return w > 0 && ht > 0 ? new ImageInfo(w, ht, precision) : null;
            }
            i += 2 + segLen;
        }
        return null;
    }

    private static int bigEndian(byte[] h, int off) {
        return ((h[off] & 0xFF) << 24) | ((h[off + 1] & 0xFF) << 16)
                | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
    }

    private static int littleEndian(byte[] h, int off) {
        return (h[off] & 0xFF) | ((h[off + 1] & 0xFF) << 8)
                | ((h[off + 2] & 0xFF) << 16) | ((h[off + 3] & 0xFF) << 24);
    }

    /** WebP VP8 尺寸为 14 位小端。 */
    private static int littleEndian14(byte[] h, int off) {
        return (h[off] & 0xFF) | ((h[off + 1] & 0xFF) << 8) | ((h[off + 2] & 0x3F) << 16);
    }

    /** 2 字节小端。 */
    private static int shortLE(byte[] h, int off) {
        return (h[off] & 0xFF) | ((h[off + 1] & 0xFF) << 8);
    }

    private static String mimeFor(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "image/*";
        };
    }

    /** 桥接 {@link MagicBytesDetector}，避免依赖方向混乱。 */
    private static final class MagicBytesBridge {
        static boolean isImage(File file) {
            try {
                var d = com.filestudio.engine.MagicBytesDetector.detect(file.toPath());
                String ext = d.extension();
                return ext.equals("png") || ext.equals("jpg") || ext.equals("gif")
                        || ext.equals("bmp") || ext.equals("webp");
            } catch (RuntimeException e) {
                // 读取失败（权限/IO）时不视为可处理，避免 canHandle 抛异常
                return false;
            }
        }

        static String imageExtension(byte[] head) {
            var d = com.filestudio.engine.MagicBytesDetector.detect(head, head.length);
            String ext = d.extension();
            return SUPPORTED.contains(ext) ? ext : "";
        }
    }
}

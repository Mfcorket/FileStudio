package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Windows 图标处理器（ICO / CUR）：列出每个图像的尺寸与位深。
 *
 * <p>VIEW_ONLY 能力——图标为二进制资源，不作为文本编辑。
 *
 * <p>结构：
 * <pre>
 *   偏移  长度  含义
 *    0     2   保留，必须为 0
 *    2     2   类型：1=ICO，2=CUR
 *    4     2   图像条目数
 *    6    16×n 目录项：宽、高、颜色数、保留、平面数、位深、字节数、偏移
 * </pre>
 *
 * <p>目录项中的宽/高为单字节，{@code 0} 表示 256。
 */
public class IcoFileHandler implements FileHandler {

    /**
     * ICO / CUR 处理器。
     */
    public IcoFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("ico", "cur");

    /** 头部 + 目录项的读取上限。 */
    private static final int MAX_READ = 1024 * 1024;

    @Override
    public String getExtension() {
        return "ico";
    }

    @Override
    public String getMimeType() {
        return "image/x-icon";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Windows icon/cursor";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isIco(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("ICO file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", extensionOf(file.getName()).equals("cur") ? "cur" : "ico");
        meta.put("viewOnly", true);

        byte[] head;
        try {
            head = readPrefix(path, MAX_READ);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading ICO: " + path, e);
        }

        if (head.length < 6 || leShort(head, 0) != 0) {
            meta.put("valid", false);
            meta.put("error", "Missing or truncated ICO header");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        int type = leShort(head, 2);
        int count = leShort(head, 4);
        meta.put("valid", true);
        meta.put("type", type == 2 ? "cursor" : "icon");
        meta.put("imageCount", count);

        if (type != 1 && type != 2) {
            meta.put("valid", false);
            meta.put("error", "Unknown ICO type: " + type);
        }

        List<Map<String, Object>> images = new ArrayList<>();
        int maxDim = 0;
        int maxBitDepth = 0;
        long payloadBytes = 0;
        for (int i = 0; i < count; i++) {
            int off = 6 + i * 16;
            if (off + 16 > head.length) {
                meta.put("truncated", true);
                break;
            }
            int w = head[off] & 0xFF;
            int h = head[off + 1] & 0xFF;
            int colors = head[off + 2] & 0xFF;
            int planes = leShort(head, off + 4);
            int bitCount = leShort(head, off + 6);
            int bytes = (int) leUnsignedInt(head, off + 8);
            int imageOffset = (int) leUnsignedInt(head, off + 12);

            // 宽/高为 0 表示 256
            int width = w == 0 ? 256 : w;
            int height = h == 0 ? 256 : h;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("width", width);
            entry.put("height", height);
            entry.put("bitDepth", bitCount);
            entry.put("colors", colors);
            entry.put("bytes", bytes);
            entry.put("offset", imageOffset);
            images.add(entry);

            maxDim = Math.max(maxDim, Math.max(width, height));
            maxBitDepth = Math.max(maxBitDepth, bitCount);
            payloadBytes += bytes;
        }

        if (!images.isEmpty()) {
            meta.put("images", images);
            meta.put("maxDimension", maxDim);
            meta.put("maxBitDepth", maxBitDepth);
            meta.put("payloadBytes", payloadBytes);
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("ICO files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("imageCount", doc.getMetadata().get("imageCount"));
            meta.put("maxDimension", doc.getMetadata().get("maxDimension"));
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

    /**
     * 判断是否为 ICO / CUR。
     *
     * <p>无独立魔数：靠「保留字段为 0」+「类型为 1/2」+「条目数合理」+ 偏移不越界
     * 这一组约束来判定，避免把任意以 {@code 00 00} 开头的小文件误判为图标。
     */
    private static boolean isIco(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(6 + 16 * 32);
            if (head.length < 6) {
                return false;
            }
            if (leShort(head, 0) != 0) {
                return false;
            }
            int type = leShort(head, 2);
            int count = leShort(head, 4);
            if (type != 1 && type != 2) {
                return false;
            }
            if (count <= 0 || count > 32) {
                return false;
            }
            // 至少要能容纳全部目录项
            if (head.length < 6 + count * 16) {
                return false;
            }
            // 每个条目的偏移应落在文件范围内
            long fileSize = Files.size(file.toPath());
            for (int i = 0; i < count; i++) {
                long off = leUnsignedInt(head, 6 + i * 16 + 12);
                long len = leUnsignedInt(head, 6 + i * 16 + 8);
                if (off < 6 || off >= fileSize || len == 0 || off + len > fileSize) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readPrefix(Path path, int len) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(len);
        }
    }

    private static int leShort(byte[] b, int off) {
        if (off + 2 > b.length) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long leUnsignedInt(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }
}

package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unreal Engine PAK（.pak）处理器：识别 PAK 魔数与版本，提取索引偏移与大小。
 *
 * <p>VIEW_ONLY 能力。
 *
 * <p>Unreal Pak 头（FPakInfo）：
 * <pre>
 *   0    4   magic（0x5A6F12E1）
 *   4    4   version（-8 ~ 9，兼容负号 = 旧版格式）
 *   8    8   indexOffset（小端）
 *  16    8   indexSize（小端）
 * </pre>
 * 后续字段随版本变化（Encrypted、Hash 等），这里不展开。
 */
public class PakFileHandler implements FileHandler {

    /** PAK 处理器。 */
    public PakFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("pak");

    /** Unreal Pak 魔数。 */
    private static final int MAGIC = 0x5A6F12E1;

    /** 已知最大 Pak 版本，超过则视为非法。 */
    private static final int MAX_VERSION = 10;

    @Override
    public String getExtension() {
        return "pak";
    }

    @Override
    public String getMimeType() {
        return "application/x-pak";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Unreal Engine PAK archive";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isPak(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("PAK file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, 24);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "pak");
        meta.put("viewOnly", true);

        if (head.length < 16 || leInt(head, 0) != MAGIC) {
            meta.put("valid", false);
            meta.put("error", "Not an Unreal PAK (missing magic 0x5A6F12E1)");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }
        meta.put("valid", true);
        int version = leInt(head, 4);
        if (version > MAX_VERSION) {
            meta.put("versionWarning", "implausible version " + version
                    + " (max known is " + MAX_VERSION + ")");
        }
        meta.put("version", version);
        long indexOffset = leLong(head, 8);
        long indexSize = leLong(head, 16);
        meta.put("indexOffset", indexOffset);
        meta.put("indexSize", indexSize);

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("PAK files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", "pak");
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

    private static boolean isPak(File file) {
        try {
            byte[] head = readHead(file.toPath(), 4);
            return head.length == 4 && leInt(head, 0) == MAGIC;
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
            throw new FileStudioException("Failed reading PAK header: " + path, e);
        }
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long leLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (b[off + i] & 0xFF)) << (8 * i);
        }
        return v;
    }
}
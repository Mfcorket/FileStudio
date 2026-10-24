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
 * Unreal Engine 资源（.uasset）处理器：识别 UE 包魔数并提取版本与标志。
 *
 * <p>VIEW_ONLY 能力。
 *
 * <p>Unreal 4.20+ 的包文件头：
 * <pre>
 *   0    4   包魔数（0x9E2A83C1）
 *   4    4   包源码（未用，通常为 0）
 *   8    4   unversioned 标志：-4 表示未记版本（正式版常见）；否则为包文件版本
 *   ...   引擎版本信息
 * </pre>
 */
public class UnrealAssetFileHandler implements FileHandler {

    /** Unreal Engine 资源处理器。 */
    public UnrealAssetFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("uasset");

    /** Unreal 4.20+ 包魔数（4 字节，字节序为 9E 2A 83 C1）。 */
    private static final int MAGIC = 0x9E2A83C1;

    /** unversioned 包的版本哨兵值。 */
    private static final int UNVERSIONED = -4;

    @Override
    public String getExtension() {
        return "uasset";
    }

    @Override
    public String getMimeType() {
        return "application/x-uasset";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Unreal Engine asset (uasset)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isUasset(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("uasset file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, 32);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "uasset");
        meta.put("viewOnly", true);

        if (head.length < 12 || leInt(head, 0) != MAGIC) {
            meta.put("valid", false);
            meta.put("error", "Not an Unreal package (missing magic 0x9E2A83C1)");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }
        meta.put("valid", true);
        int flag = leInt(head, 8);
        if (flag == UNVERSIONED) {
            meta.put("versioned", false);
            meta.put("note", "Unversioned package (shipping build)");
        } else if (flag > 0) {
            meta.put("versioned", true);
            meta.put("packageVersion", flag);
        } else {
            meta.put("note", "Unexpected version field: " + flag);
        }
        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("uasset files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", "uasset");
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

    private static boolean isUasset(File file) {
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
            throw new FileStudioException("Failed reading uasset header: " + path, e);
        }
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TAR 归档处理器：列出条目清单（名称、大小、类型、权限）供浏览与解压。
 *
 * <p>PARTIAL 能力：可浏览与导出条目，但不作为文本编辑。
 *
 * <p>实现：解析 512 字节块头，提取文件名、大小（八进制）、类型、权限。
 * 不展开压缩数据，避免大文件内存开销。
 */
public class TarArchiveHandler implements FileHandler {

    /**
     * TAR 归档处理器。
     */
    public TarArchiveHandler() {}

    private static final int BLOCK_SIZE = 512;
    private static final int MAX_ENTRIES_TO_LIST = 512;

    @Override
    public String getExtension() {
        return "tar";
    }

    @Override
    public String getMimeType() {
        return "application/x-tar";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "TAR archive";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isTar(file);
        }
        return extensionOf(file.getName()).equals("tar");
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Archive not readable: " + file);
        }
        Path path = file.toPath();
        TarInfo info = readTarInfo(path);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "tar");
        meta.put("valid", info.valid);
        meta.put("entryCount", info.entries.size());
        meta.put("viewOnly", true);
        if (!info.entries.isEmpty()) {
            meta.put("entries", info.entries);
        }
        if (!info.valid) {
            meta.put("error", info.error);
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("TAR archives are view-only; use an extractor");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            TarInfo info = readTarInfo(file.toPath());
            meta.put("entryCount", info.entries.size());
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    /**
     * 条目描述。不可变。
     *
     * @param name 归档内路径，使用 {@code /} 分隔
     * @param size 未压缩大小（字节）
     * @param type 条目类型标志：{@code '0'} 普通文件、{@code '5'} 目录、{@code '2'} 符号链接
     * @param mode 八进制权限字符串，如 {@code "0644"}
     */
    public record TarEntryInfo(String name, long size, char type, String mode) {}

    /** 归档概览。不可变。 */
    record TarInfo(boolean valid, List<TarEntryInfo> entries, String error) {
        static TarInfo invalid(String err) {
            return new TarInfo(false, List.of(), err);
        }
    }

    static TarInfo readTarInfo(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            if (data.length < BLOCK_SIZE) return TarInfo.invalid("File too small");

            List<TarEntryInfo> entries = new ArrayList<>();
            int pos = 0;
            boolean valid = false;

            while (pos + BLOCK_SIZE <= data.length) {
                // 检查是否全零块（结束标记）
                boolean allZero = true;
                for (int i = 0; i < BLOCK_SIZE; i++) {
                    if (data[pos + i] != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    valid = true;
                    break;
                }

                // 解析头部
                String name = readString(data, pos, 100);
                String mode = readString(data, pos + 100, 8);
                String sizeStr = readString(data, pos + 124, 12);
                char type = (char) data[pos + 156];

                if (name.isEmpty()) break;

                long size = parseOctal(sizeStr);
                entries.add(new TarEntryInfo(name, size, type, mode));

                if (entries.size() >= MAX_ENTRIES_TO_LIST) break;

                // 跳过头部 + 数据块
                int dataBlocks = (int) ((size + BLOCK_SIZE - 1) / BLOCK_SIZE);
                pos += BLOCK_SIZE + dataBlocks * BLOCK_SIZE;
            }

            if (!valid && entries.isEmpty()) {
                return TarInfo.invalid("No valid TAR header found");
            }
            return new TarInfo(valid, List.copyOf(entries), null);
        } catch (IOException e) {
            return TarInfo.invalid("Read error: " + e.getMessage());
        }
    }

    private static String readString(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && off + i < data.length; i++) {
            char c = (char) (data[off + i] & 0xFF);
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }

    private static long parseOctal(String s) {
        try {
            return Long.parseLong(s.trim(), 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** 检查文件是否为 TAR 格式（通过 magic number 或结构）。 */
    private static boolean isTar(File file) {
        try {
            byte[] head = new byte[512];
            try (var in = Files.newInputStream(file.toPath())) {
                int n = in.read(head);
                if (n < 512) return false;
                // 检查 ustar magic
                String magic = readString(head, 257, 6);
                if (magic.startsWith("ustar")) return true;
                // 检查 POSIX tar（无 magic，但结构有效）
                String name = readString(head, 0, 100);
                String sizeStr = readString(head, 124, 12);
                if (!name.isEmpty() && !sizeStr.trim().isEmpty()) {
                    try {
                        Long.parseLong(sizeStr.trim(), 8);
                        return true;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}

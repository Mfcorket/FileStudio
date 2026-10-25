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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * TAR 归档处理器：列出条目清单（名称、大小、类型、权限）供浏览与解压。
 *
 * <p>PARTIAL 能力：可浏览与导出条目，但不作为文本编辑。
 *
 * <p>支持格式：
 * <ul>
 *   <li>.tar - 标准 TAR</li>
 *   <li>.tar.gz / .tgz - GZIP 压缩</li>
 *   <li>.tar.bz2 / .tbz2 - BZIP2 压缩</li>
 *   <li>.tar.xz / .txz - XZ (LZMA2) 压缩</li>
 *   <li>.tar.zst - Zstandard 压缩</li>
 *   <li>.tar.lz4 - LZ4 压缩</li>
 * </ul>
 *
 * <p>实现：解析 512 字节块头，提取文件名、大小（八进制）、类型、权限。
 * 对压缩格式先解压再解析，避免大文件内存开销。
 */
public class TarArchiveHandler implements FileHandler {

    /**
     * TAR 归档处理器。
     */
    public TarArchiveHandler() {}

    private static final EditCapability EDIT_CAPABILITY = EditCapability.PARTIAL;
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
        return "TAR archive (with gzip/bzip2/xz/zstd/lz4 compression)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isTar(file) || isCompressedTar(file);
        }
        String ext = extensionOf(file.getName()).toLowerCase();
        return ext.equals("tar") || isCompressedTarExt(ext);
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Archive not readable: " + file);
        }
        Path path = file.toPath();
        String ext = extensionOf(file.getName()).toLowerCase();
        
        // 处理压缩 TAR
        if (isCompressedTarExt(ext)) {
            return parseCompressedTar(file, ext);
        }
        
        // 标准 TAR
        try {
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
        } catch (IOException e) {
            throw new FileStudioException("Failed reading TAR: " + file, e);
        }
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
            if (isCompressedTarExt(extensionOf(file.getName()).toLowerCase())) {
                // 压缩 TAR：读取压缩流获取条目
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                    InputStream decompressed = getDecompressedStream(in, file.getName());
                    if (decompressed != null) {
                        TarInfo info = readTarInfoFromStream(decompressed);
                        meta.put("entryCount", info.entries.size());
                        meta.put("compression", getCompressionName(file.getName()));
                    }
                }
            } else {
                TarInfo info = readTarInfo(file.toPath());
                meta.put("entryCount", info.entries.size());
            }
        } catch (IOException | FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 压缩 TAR 支持 ----

    private static boolean isCompressedTar(File file) {
        String ext = extensionOf(file.getName()).toLowerCase();
        return isCompressedTarExt(ext);
    }

    private static boolean isCompressedTarExt(String ext) {
        return ext.equals("tar.gz") || ext.equals("tgz") ||
               ext.equals("tar.bz2") || ext.equals("tbz2") || ext.equals("tbz") ||
               ext.equals("tar.xz") || ext.equals("txz") ||
               ext.equals("tar.zst") || ext.equals("tzst") ||
               ext.equals("tar.lz4") || ext.equals("tlz4");
    }

    private static String getCompressionName(String fileName) {
        String ext = extensionOf(fileName).toLowerCase();
        if (ext.equals("tar.gz") || ext.equals("tgz")) return "gzip";
        if (ext.equals("tar.bz2") || ext.equals("tbz2") || ext.equals("tbz")) return "bzip2";
        if (ext.equals("tar.xz") || ext.equals("txz")) return "xz";
        if (ext.equals("tar.zst") || ext.equals("tzst")) return "zstd";
        if (ext.equals("tar.lz4") || ext.equals("tlz4")) return "lz4";
        return "unknown";
    }

    private static Document parseCompressedTar(File file, String ext) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            InputStream decompressed = getDecompressedStream(in, file.getName());
            if (decompressed == null) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("size", file.length());
                meta.put("format", ext);
                meta.put("valid", false);
                meta.put("error", "Unsupported compression format: " + ext);
                return new Document(file.toPath(), getMimeTypeForExt(ext), ext, "", meta,
                        file.length(), EDIT_CAPABILITY);
            }
            
            TarInfo info = readTarInfoFromStream(decompressed);
            
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("size", file.length());
            meta.put("format", ext);
            meta.put("valid", info.valid);
            meta.put("entryCount", info.entries.size());
            meta.put("viewOnly", true);
            meta.put("compression", getCompressionName(file.getName()));
            if (!info.entries.isEmpty()) {
                meta.put("entries", info.entries);
            }
            if (!info.valid) {
                meta.put("error", info.error);
            }
            
            return new Document(file.toPath(), getMimeTypeForExt(ext), ext, "", meta,
                    file.length(), EDIT_CAPABILITY);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading compressed TAR: " + file, e);
        }
    }

    private static InputStream getDecompressedStream(InputStream in, String fileName) throws IOException {
        String ext = extensionOf(fileName).toLowerCase();
        if (ext.equals("tar.gz") || ext.equals("tgz")) {
            return new GZIPInputStream(in);
        }
        // BZIP2
        if (ext.equals("tar.bz2") || ext.equals("tbz2") || ext.equals("tbz")) {
            try {
                Class<?> cbzi = Class.forName("org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream");
                return (InputStream) cbzi.getConstructor(InputStream.class).newInstance(in);
            } catch (Exception e) {
                return null;
            }
        }
        // XZ
        if (ext.equals("tar.xz") || ext.equals("txz")) {
            try {
                Class<?> cxz = Class.forName("org.tukaani.xz.XZInputStream");
                return (InputStream) cxz.getConstructor(InputStream.class).newInstance(in);
            } catch (Exception e) {
                return null;
            }
        }
        // ZSTD
        if (ext.equals("tar.zst") || ext.equals("tzst")) {
            try {
                Class<?> czstd = Class.forName("com.github.luben.zstd.ZstdInputStream");
                return (InputStream) czstd.getConstructor(InputStream.class).newInstance(in);
            } catch (Exception e) {
                return null;
            }
        }
        // LZ4
        if (ext.equals("tar.lz4") || ext.equals("tlz4")) {
            try {
                Class<?> clz4 = Class.forName("org.lz4.java.LZ4FrameInputStream");
                return (InputStream) clz4.getConstructor(InputStream.class).newInstance(in);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String getMimeTypeForExt(String ext) {
        return switch (ext) {
            case "tar.gz", "tgz" -> "application/gzip";
            case "tar.bz2", "tbz2", "tbz" -> "application/x-bzip2";
            case "tar.xz", "txz" -> "application/x-xz";
            case "tar.zst", "tzst" -> "application/zstd";
            case "tar.lz4", "tlz4" -> "application/x-lz4";
            default -> "application/x-tar";
        };
    }

    // ---- TAR 解析逻辑 ----

    private static TarInfo readTarInfo(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        return readTarInfoFromBytes(data);
    }

    private static TarInfo readTarInfoFromStream(InputStream in) throws IOException {
        // 读取流前 1MB 用于解析
        byte[] buffer = new byte[1024 * 1024];
        int total = 0;
        byte[] temp = new byte[8192];
        int read;
        while ((read = in.read(temp)) != -1 && total + read <= buffer.length) {
            System.arraycopy(temp, 0, buffer, total, read);
            total += read;
        }
        if (total == buffer.length) {
            // 流更大，扩容
            byte[] newBuffer = new byte[buffer.length * 2];
            System.arraycopy(buffer, 0, newBuffer, 0, total);
            while ((read = in.read(temp)) != -1) {
                if (total + read > newBuffer.length) {
                    byte[] bigger = new byte[newBuffer.length * 2];
                    System.arraycopy(newBuffer, 0, bigger, 0, total);
                    newBuffer = bigger;
                }
                System.arraycopy(temp, 0, newBuffer, total, read);
                total += read;
            }
            buffer = newBuffer;
        }
        byte[] data = new byte[total];
        System.arraycopy(buffer, 0, data, 0, total);
        return readTarInfoFromBytes(data);
    }

    private static TarInfo readTarInfoFromBytes(byte[] data) {
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

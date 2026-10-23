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

/**
 * BZIP2 压缩文件处理器（*.bz2 / *.tbz2）。
 *
 * <p>PARTIAL 能力——解析头信息（块大小、完整性标志），不提供全文编辑。
 *
 * <p>bzip2 是单流压缩格式：文件以 {@code BZh} + 块大小数字（1-9）开头；
 * 数据按 100k×level 的块存储，块头带随机化标志（0x314159265359 / 0x177245385090），
 * 块尾有 32 位 CRC 与前两个字节的 EOS 魔数。这里只解析头与块级信息，
 * 不做完整解压（BWT + 游程编码 + 哈夫曼不在 JDK 内置范围内）。
 */
public class Bzip2FileHandler implements FileHandler {

    private static final byte[] MAGIC = {'B', 'Z', 'h'};

    private static final long MAGIC_BLOCK_RANDOM = 0x314159265359L;
    private static final long MAGIC_BLOCK_ORDERED = 0x177245385090L;

    @Override
    public String getExtension() {
        return "bz2";
    }

    @Override
    public String getMimeType() {
        return "application/x-bzip2";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "BZIP2 compressed file";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            if (ext.equals("bz2") || ext.equals("tbz2")) {
                return true;
            }
        }
        return file.isFile() && isBzip2(file);
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("BZIP2 file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, 12);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "bz2");
        meta.put("viewOnly", true);

        if (head.length < 4 || head[0] != 'B' || head[1] != 'Z' || head[2] != 'h'
                || head[3] < '1' || head[3] > '9') {
            meta.put("valid", false);
            meta.put("error", "Missing or malformed BZh magic header");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        int level = head[3] - '0';
        meta.put("valid", true);
        meta.put("compressionLevel", level);
        meta.put("blockSize100k", level);

        // 第一个数据块：块头魔数占 6 字节（偏移 4..9）
        if (head.length >= 10) {
            long blockMagic = ((long) (head[4] & 0xFF) << 40)
                    | ((long) (head[5] & 0xFF) << 32)
                    | ((long) (head[6] & 0xFF) << 24)
                    | ((long) (head[7] & 0xFF) << 16)
                    | ((long) (head[8] & 0xFF) << 8)
                    | (head[9] & 0xFF);
            if (blockMagic == MAGIC_BLOCK_RANDOM) {
                meta.put("randomized", true);
            } else if (blockMagic == MAGIC_BLOCK_ORDERED) {
                meta.put("randomized", false);
            }
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("BZIP2 files are read-only; decompress to a new file instead");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] head = readHead(file.toPath(), 4);
            if (head.length >= 4 && head[0] == 'B' && head[1] == 'Z' && head[2] == 'h') {
                meta.put("compressionLevel", head[3] - '0');
            }
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static byte[] readHead(Path path, int len) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[len];
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
            throw new FileStudioException("Failed reading BZIP2 header: " + path, e);
        }
    }

    private static boolean isBzip2(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int b0 = in.read();
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            return b0 == 'B' && b1 == 'Z' && b2 == 'h'
                    && b3 >= '1' && b3 <= '9';
        } catch (IOException e) {
            return false;
        }
    }
}
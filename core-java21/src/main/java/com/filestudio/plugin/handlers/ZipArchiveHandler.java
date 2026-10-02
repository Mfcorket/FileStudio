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

/**
 * ZIP / JAR 归档处理器：列出条目清单（名称、压缩/原始大小、目录结构）供浏览与解压。
 *
 * <p>PARTIAL 能力：可浏览与导出条目，但不作为文本编辑。
 *
 * <p>实现：定位 End Of Central Directory (EOCD) 记录，读取总条目数与
 * central directory 偏移，再迭代每条 {@code PK\x01\x02} 中央目录记录。
 * 不展开压缩数据，避免大文件内存开销。
 */
public class ZipArchiveHandler implements FileHandler {

    /**
     * ZIP 归档处理器。
     */
    public ZipArchiveHandler() {}

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int MAX_ENTRIES_TO_LIST = 512;

    @Override
    public String getExtension() {
        return "zip";
    }

    @Override
    public String getMimeType() {
        return "application/zip";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "ZIP archive";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return ext.equals("zip") || ext.equals("jar")
                || isZipSignature(file);
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Archive not readable: " + file);
        }
        Path path = file.toPath();
        ZipInfo info = readZipInfo(path);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("valid", info.valid);
        meta.put("entryCount", info.entries.size());
        meta.put("compressedSize", info.compressedBytes);
        meta.put("uncompressedSize", info.uncompressedBytes);
        meta.put("encrypted", info.encrypted);
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
        throw new FileStudioException("ZIP archives are view-only; use an extractor");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            ZipInfo info = readZipInfo(file.toPath());
            meta.put("entryCount", info.entries.size());
            meta.put("compressedSize", info.compressedBytes);
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    /**
     * 条目描述。不可变。
     *
     * @param name            归档内路径，使用 {@code /} 分隔
     * @param compressedSize  压缩后大小（字节）
     * @param uncompressedSize 原始大小（字节）
     * @param isDirectory     是否为目录条目（以 {@code /} 结尾）
     */
    public record ZipEntryInfo(String name, long compressedSize, long uncompressedSize, boolean isDirectory) {}

    /** 归档概览。不可变。 */
    record ZipInfo(boolean valid, List<ZipEntryInfo> entries, long compressedBytes,
                   long uncompressedBytes, boolean encrypted, String error) {
        static ZipInfo invalid(String err) {
            return new ZipInfo(false, List.of(), 0, 0, false, err);
        }
    }

    static ZipInfo readZipInfo(Path path) {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] tail = readTail(raw, 65557);
            Eocd eocd = findEocd(tail);
            if (eocd == null) return ZipInfo.invalid("No End Of Central Directory record");

            List<ZipEntryInfo> entries = new ArrayList<>();
            long compressed = 0;
            long uncompressed = 0;
            boolean encrypted = false;

            // 重新打开定位到 central directory
            try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                long skipped = skipFully(in, eocd.cdOffset());
                if (skipped < eocd.cdOffset()) return ZipInfo.invalid("Truncated central directory");
                byte[] cenHeader = new byte[46];
                for (int i = 0; i < eocd.totalEntries(); i++) {
                    int n = readFully(in, cenHeader);
                    if (n < 46) break;
                    int sig = leInt(cenHeader, 0);
                    if (sig != CEN_SIG) break;
                    short flag = (short) leShort(cenHeader, 8);
                    int method = leShort(cenHeader, 10);
                    int compSize = leInt(cenHeader, 20);
                    int uncompSize = leInt(cenHeader, 24);
                    int nameLen = leShort(cenHeader, 28);
                    int extraLen = leShort(cenHeader, 30);
                    int commentLen = leShort(cenHeader, 32);

                    if ((flag & 0x0001) != 0) encrypted = true;
                    compressed += compSize;
                    uncompressed += uncompSize;

                    byte[] nameBytes = new byte[nameLen];
                    if (readFully(in, nameBytes) < nameLen) break;
                    String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                    boolean isDir = name.endsWith("/");
                    if (entries.size() < MAX_ENTRIES_TO_LIST) {
                        entries.add(new ZipEntryInfo(name, compSize, uncompSize, isDir));
                    }

                    if (skipFully(in, extraLen + commentLen) < extraLen + commentLen) break;
                }
            }
            return new ZipInfo(true, List.copyOf(entries), compressed, uncompressed, encrypted, null);
        } catch (IOException e) {
            return ZipInfo.invalid("Read error: " + e.getMessage());
        }
    }

    private record Eocd(int totalEntries, long cdOffset) {}

    /** 从文件末尾读取最多 max 字节用于 EOCD 扫描。 */
    private static byte[] readTail(InputStream in, int max) throws IOException {
        byte[] buf = new byte[max];
        // 读满最多 max 字节（若文件更小则读全部）
        long total = 0;
        int r;
        while (total < max && (r = in.read(buf, (int) total, max - (int) total)) > 0) {
            total += r;
        }
        byte[] out = new byte[(int) total];
        System.arraycopy(buf, 0, out, 0, (int) total);
        return out;
    }

    /** 从尾部数据反向扫描 EOCD。 */
    private static Eocd findEocd(byte[] tail) {
        for (int i = tail.length - 22; i >= 0; i--) {
            if (leInt(tail, i) == EOCD_SIG) {
                int entries = leShort(tail, i + 10);
                long cdOffset = leInt(tail, i + 16) & 0xFFFFFFFFL;
                return new Eocd(entries, cdOffset);
            }
        }
        return null;
    }

    private static long skipFully(InputStream in, long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                if (in.read() < 0) break;
                skipped++;
            } else {
                skipped += s;
            }
        }
        return skipped;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int leShort(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** 检查文件是否以 ZIP 局部文件头开始（P K 03 04）。 */
    private static boolean isZipSignature(File file) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            int b0 = in.read();
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            return b0 == 'P' && b1 == 'K' && (b2 == 0x03 || b2 == 0x05 || b2 == 0x07);
        } catch (IOException e) {
            return false;
        }
    }
}

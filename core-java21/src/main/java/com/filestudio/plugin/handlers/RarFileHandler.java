package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * RAR 归档处理器，同时支持 RAR 1.5–4.x 与 RAR 5.x。
 *
 * <p>PARTIAL——读取归档目录（文件名、大小、时间、方法、属性），不解压内容。
 *
 * <p>两个年代的 RAR 头部结构完全不同，因此按 marker 字节分流：
 *
 * <pre>
 * RAR4: 52 61 72 21 1A 07 00                    块头为定长
 *       CRC16(2) TYPE(1) FLAGS(2) SIZE(2) [ADD_SIZE(4)]
 * RAR5: 52 61 72 21 1A 07 01 00                 块头长度为变长整数
 *       CRC32(4) SIZE(vint) TYPE(vint) FLAGS(vint) [EXTRA(vint)] [DATA(vint)]
 * </pre>
 *
 * <p>RAR5 的所有长度都改用了变长整数编码（高位 1 继续、高 0 结束），
 * 与 7z 的 varint 不同——这里没有 zigzag，是直接的原始值。
 */
public class RarFileHandler implements FileHandler {

    /**
     * RAR 归档处理器。
     */
    public RarFileHandler() {}

    /** RAR 签名：{@code Rar!} + 0x1A07 + 版本字节。 */
    private static final byte[] MARKER_PREFIX = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07};

    private static final int RAR4_MARKER = 0x00;
    private static final int RAR5_MARKER = 0x01;

    // RAR4 块类型
    private static final int R4_MAIN = 0x73;
    private static final int R4_FILE = 0x74;
    private static final int R4_END = 0x7B;

    // RAR4 块标志
    private static final int R4_FLAG_ADD_SIZE = 0x8000;
    private static final int R4_FLAG_ENCRYPTED = 0x0004;
    private static final int R4_FLAG_SOLID = 0x0010;
    private static final int R4_FLAG_DICT_CONT = 0x0001;
    private static final int R4_FLAG_COMMENT = 0x0002;

    // RAR5 块类型
    private static final int R5_MAIN = 1;
    private static final int R5_FILE = 2;
    private static final int R5_SERVICE = 3;
    private static final int R5_ENCRYPTION = 4;
    private static final int R5_END = 5;

    // RAR5 块标志
    private static final int R5_FLAG_EXTRA = 0x0001;
    private static final int R5_FLAG_DATA = 0x0002;

    // RAR5 文件标志
    private static final int R5_FILE_DIR = 0x0001;
    private static final int R5_FILE_TIME = 0x0002;
    private static final int R5_FILE_CRC = 0x0004;
    private static final int R5_FILE_UNP_UNKNOWN = 0x0008;

    /** 单个归档内最多读取的条目数，防止异常文件导致无意义遍历。 */
    private static final int MAX_ENTRIES = 200_000;

    @Override
    public String getExtension() {
        return "rar";
    }

    @Override
    public String getMimeType() {
        return "application/vnd.rar";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "RAR archive";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) {
            return false;
        }
        if (file.isFile()) {
            return detectVersion(file) >= 0;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".rar");
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("RAR file not readable: " + file);
        }
        Path path = file.toPath();
        long len = file.length();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", len);
        meta.put("viewOnly", true);

        int version = detectVersion(file);
        if (version < 0) {
            meta.put("valid", false);
            meta.put("error", "Missing RAR marker");
            return doc(path, meta, len);
        }

        meta.put("valid", true);
        meta.put("format", "rar");
        meta.put("rarVersion", version == RAR5_MARKER ? 5 : 4);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (version == RAR5_MARKER) {
                readRar5(raf, len, meta);
            } else {
                readRar4(raf, len, meta);
            }
        } catch (IOException | RuntimeException e) {
            meta.put("error", "Failed to read RAR headers: " + e.getMessage());
        }

        return doc(path, meta, len);
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("RAR archives are not editable");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        int v = detectVersion(file);
        meta.put("valid", v >= 0);
        return meta;
    }

    // ---- RAR 5 ----

    /**
     * 读取 RAR5 归档目录。
     *
     * <p>块头是变长的：CRC32、头部长度、类型、标志，再按标志决定是否有
     * extra / data 长度。文件头里的字段顺序同样由文件标志决定。
     */
    private void readRar5(RandomAccessFile raf, long fileLen, Map<String, Object> meta)
            throws IOException {
        long pos = 8;                      // 7 字节 marker + 1 字节 SFX 标记
        List<Map<String, Object>> entries = new ArrayList<>();
        int dirCount = 0;
        int encryptedCount = 0;
        int serviceCount = 0;
        boolean sawEnd = false;
        String archiveComment = null;
        boolean firstMainBlock = true;

        while (pos < fileLen && entries.size() < MAX_ENTRIES) {
            long blockStart = pos;

            // 块头首 4 字节是该块其余内容的 CRC32
            long crcPos = pos;
            long storedCrc = le32(readRange(raf, crcPos, 4), 0);
            pos += 4;
            long afterCrc = pos;

            long headerSize = readVInt(raf, fileLen);
            if (headerSize <= 0) {
                meta.put("error", "Bad header size at offset " + blockStart);
                break;
            }
            // HEAD_SIZE 不包含长度字段本身，因此要用读完长度字段后的游标计算
            long headerEnd = raf.getFilePointer() + headerSize;
            if (headerEnd > fileLen) {
                meta.put("error", "Header exceeds file at offset " + blockStart);
                break;
            }
            CRC32 crc = new CRC32();
            // readRange 会移动游标，校验后必须恢复，否则后续字段会从错位置读起
            long resume = raf.getFilePointer();
            crc.update(readRange(raf, afterCrc, (int) (headerEnd - afterCrc)));
            raf.seek(resume);
            meta.put("lastBlockCrcOk", crc.getValue() == storedCrc);
            long type = readVInt(raf, fileLen);
            long flags = readVInt(raf, fileLen);

            long extraSize = 0;
            if ((flags & R5_FLAG_EXTRA) != 0) {
                extraSize = readVInt(raf, fileLen);
            }
            long dataSize = 0;
            if ((flags & R5_FLAG_DATA) != 0) {
                dataSize = readVInt(raf, fileLen);
            }

            if (headerEnd > fileLen) {
                meta.put("error", "Header exceeds file at offset " + blockStart);
                break;
            }

            if (headerEnd > fileLen) {
                meta.put("error", "Header exceeds file at offset " + blockStart);
                break;
            }

            switch ((int) type) {
                case R5_MAIN -> {
                    if (firstMainBlock) {
                        firstMainBlock = false;
                        // archive flags：0x0001 volume, 0x0002 首个卷, 0x0004 solid
                        long archiveFlags = readVInt(raf, fileLen);
                        meta.put("isVolume", (archiveFlags & 0x0001) != 0);
                        if ((archiveFlags & 0x0001) != 0 && (archiveFlags & 0x0002) == 0) {
                            meta.put("volumeNumber", readVInt(raf, fileLen));
                        }
                    }
                }
                case R5_FILE, R5_SERVICE -> {
                    boolean isService = type == R5_SERVICE;
                    if (isService) {
                        serviceCount++;
                    }
                    // 本分支内一律顺序读取，pos 只用于跳到下一个块
                    long fileFlags = readVInt(raf, fileLen);
                    long unpSize = readVInt(raf, fileLen);
                    long attributes = readVInt(raf, fileLen);
                    // 规范里的顺序是 mtime 在前、FileCRC 在后
                    Long modified = null;
                    if ((fileFlags & R5_FILE_TIME) != 0) {
                        modified = le32(readExactly(raf, fileLen, 4), 0);
                    }
                    Long crc32 = null;
                    if ((fileFlags & R5_FILE_CRC) != 0) {
                        crc32 = le32(readExactly(raf, fileLen, 4), 0);
                    }
                    long compInfo = readVInt(raf, fileLen);
                    long hostOs = readVInt(raf, fileLen);
                    long nameLen = readVInt(raf, fileLen);
                    if (nameLen < 0 || raf.getFilePointer() + nameLen > headerEnd) {
                        meta.put("error", "Bad file name length at offset " + blockStart);
                        return;
                    }
                    String name = new String(readExactly(raf, fileLen, (int) nameLen),
                            StandardCharsets.UTF_8);

                    if (!isService) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        boolean dir = (fileFlags & R5_FILE_DIR) != 0;
                        e.put("name", name);
                        e.put("directory", dir);
                        e.put("packedSize", dataSize);
                        e.put("unpackedSize", (fileFlags & R5_FILE_UNP_UNKNOWN) != 0
                                ? null : unpSize);
                        e.put("attributes", attributes);
                        if (crc32 != null) {
                            e.put("crc32", formatHex(crc32));
                        }
                        if (modified != null) {
                            e.put("modifiedTime", unixTime(modified));
                        }
                        entries.add(e);
                        if (dir) {
                            dirCount++;
                        }
                    }
                }
                case R5_ENCRYPTION -> {
                    encryptedCount++;
                }
                case R5_END -> {
                    sawEnd = true;
                }
                default -> {
                    // 未知类型：跳过整个块
                }
            }

            // 对齐到下一个块：头部结束 + 数据区长度
            pos = headerEnd + dataSize;
        }

        meta.put("entryCount", entries.size());
        meta.put("directoryCount", dirCount);
        meta.put("serviceBlocks", serviceCount);
        if (encryptedCount > 0) {
            meta.put("encrypted", true);
        }
        if (sawEnd) {
            meta.put("hasEndBlock", true);
        }
        meta.put("complete", sawEnd);
        if (!entries.isEmpty()) {
            meta.put("entries", entries);
        }
    }

    // ---- RAR 4 ----

    /**
     * 读取 RAR4 归档目录。
     *
     * <p>块头定长：CRC16、类型、标志、头长度，以及标志指示的附加长度。
     * 文件头的字段是定宽大端，因此解析更直接。
     */
    private void readRar4(RandomAccessFile raf, long fileLen, Map<String, Object> meta)
            throws IOException {
        long pos = 7;                      // RAR4 marker 只有 7 字节
        List<Map<String, Object>> entries = new ArrayList<>();
        int dirCount = 0;
        int encryptedCount = 0;
        boolean sawEnd = false;

        while (pos + 7 <= fileLen && entries.size() < MAX_ENTRIES) {
            long blockStart = pos;
            byte[] h = readRange(raf, pos, 7);
            int type = h[2] & 0xFF;
            // RAR4 的块头字段是<b>小端</b>：CRC16、FLAGS、HEAD_SIZE
            int flags = (h[3] & 0xFF) | ((h[4] & 0xFF) << 8);
            int headSize = (h[5] & 0xFF) | ((h[6] & 0xFF) << 8);

            if (headSize < 7 || pos + headSize > fileLen) {
                meta.put("error", "Bad block header size at offset " + blockStart);
                break;
            }

            if (type == R4_FILE && pos + 32 <= fileLen) {
                byte[] d = readRange(raf, pos, headSize);
                // 文件头内部的定宽字段同样是小端
                long packSize = le32(d, 7);
                long unpSize = le32(d, 11);
                int hostOs = d[15] & 0xFF;
                long fileCrc = le32(d, 16);
                long fileTime = le32(d, 20);
                int unpVer = d[24] & 0xFF;
                int method = d[25] & 0xFF;
                int nameSize = (d[26] & 0xFF) | ((d[27] & 0xFF) << 8);
                long attr = le32(d, 28);

                long nameOffset = 32;
                if ((flags & 0x0100) != 0) {
                    // 大文件：额外的 64 位高低部分
                    long highPack = le32(d, 32);
                    long highUnp = le32(d, 36);
                    packSize |= highPack << 32;
                    unpSize |= highUnp << 32;
                    nameOffset = 40;
                }
                if (nameSize < 0 || nameOffset + nameSize > headSize) {
                    meta.put("error", "Bad file name size at offset " + blockStart);
                    break;
                }
                String name = new String(d, (int) nameOffset, nameSize,
                        StandardCharsets.UTF_8);

                Map<String, Object> e = new LinkedHashMap<>();
                boolean dir = (flags & 0x00E0) == 0x00E0;
                e.put("name", name);
                e.put("directory", dir);
                e.put("packedSize", packSize);
                e.put("unpackedSize", unpSize);
                e.put("attributes", attr);
                e.put("crc32", formatHex(fileCrc));
                e.put("modifiedTime", dosTime(fileTime));
                e.put("hostOs", hostOsName(hostOs));
                e.put("compressionMethod", method);
                e.put("compressionVersion", unpVer);
                e.put("solid", (flags & R4_FLAG_SOLID) != 0);
                if ((flags & R4_FLAG_ENCRYPTED) != 0) {
                    e.put("encrypted", true);
                    encryptedCount++;
                }
                if ((flags & R4_FLAG_COMMENT) != 0) {
                    e.put("hasComment", true);
                }
                entries.add(e);
                if (dir) {
                    dirCount++;
                }
            } else if (type == R4_MAIN) {
                if (pos + 13 <= fileLen) {
                    byte[] d = readRange(raf, pos, Math.min(headSize, 13));
                    int flags2 = (d[3] & 0xFF) | ((d[4] & 0xFF) << 8);
                    meta.put("isVolume", (flags2 & 0x0001) != 0);
                    meta.put("isSolid", (flags2 & 0x0008) != 0);
                    if (pos + 11 <= fileLen) {
                        meta.put("hasNewNumbering", (d[9] & 0x10) != 0);
                    }
                }
            } else if (type == R4_END) {
                sawEnd = true;
                break;
            }

            long addSize = (flags & R4_FLAG_ADD_SIZE) != 0 ? le32(readRange(raf, pos, 7), 7) : 0;
            pos += headSize + addSize;
        }

        meta.put("entryCount", entries.size());
        meta.put("directoryCount", dirCount);
        if (encryptedCount > 0) {
            meta.put("encrypted", true);
        }
        meta.put("complete", sawEnd);
        if (!entries.isEmpty()) {
            meta.put("entries", entries);
        }
    }

    // ---- varint ----

    /**
     * 读取 RAR5 的变长整数。
     *
     * <p>与 7z 的 varint 不同，这里<b>没有</b> zigzag 编码，读出来的就是原始值。
     *
     * @return 原始值
     * @throws IOException 遇到非法的连续 1 位串或越过文件末尾
     */
    private static long readVInt(RandomAccessFile raf, long fileLen) throws IOException {
        long result = 0;
        long shift = 0;
        while (true) {
            long p = raf.getFilePointer();
            if (p >= fileLen) {
                throw new IOException("varint past end of file");
            }
            int b = raf.readUnsignedByte();
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("varint too long");
            }
        }
    }

    // ---- 低层读取 ----

    /**
     * 返回 4、5 或 {@code -1}（非 RAR）。
     */
    private static int detectVersion(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return detectVersion(raf);
        } catch (IOException e) {
            return -1;
        }
    }

    private static int detectVersion(RandomAccessFile raf) throws IOException {
        if (raf.length() < 8) {
            return -1;
        }
        byte[] head = new byte[8];
        raf.seek(0);
        raf.readFully(head);
        for (int i = 0; i < MARKER_PREFIX.length; i++) {
            if (head[i] != MARKER_PREFIX[i]) {
                return -1;
            }
        }
        int v = head[6] & 0xFF;
        if (v == RAR5_MARKER) {
            return RAR5_MARKER;
        }
        // RAR4 的版本字节为 0
        if (v == RAR4_MARKER) {
            return RAR4_MARKER;
        }
        return -1;
    }

    private static byte[] readRange(RandomAccessFile raf, long off, int len) throws IOException {
        if (len <= 0) {
            return new byte[0];
        }
        if (off < 0 || off + len > raf.length()) {
            throw new IOException("read out of range at " + off + " (+" + len + ")");
        }
        byte[] b = new byte[len];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    /** 从当前游标顺序读取，不改变游标语义。 */
    private static byte[] readExactly(RandomAccessFile raf, long fileLen, int len)
            throws IOException {
        if (len <= 0) {
            return new byte[0];
        }
        long p = raf.getFilePointer();
        if (p + len > fileLen) {
            throw new IOException("sequential read past end at " + p + " (+" + len + ")");
        }
        byte[] b = new byte[len];
        raf.readFully(b);
        return b;
    }

    private static long be32(byte[] b, int off) {
        if (off + 4 > b.length) {
            return 0;
        }
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long le32(byte[] b, int off) {
        if (off + 4 > b.length) {
            return 0;
        }
        return ((long) (b[off] & 0xFF)) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static String formatHex(long v) {
        return String.format("0x%08X", v);
    }

    private static String hostOsName(int os) {
        return switch (os) {
            case 0 -> "MS-DOS";
            case 1 -> "OS/2";
            case 2 -> "Windows";
            case 3 -> "Unix";
            case 4 -> "Mac OS";
            case 5 -> "BeOS";
            default -> "unknown";
        };
    }

    /** RAR4 使用 MS-DOS 时间（2 秒精度，1980 年起）。 */
    private static String dosTime(long dos) {
        if (dos == 0) {
            return "unset";
        }
        try {
            int sec = (int) ((dos & 0x1F) * 2);
            int min = (int) ((dos >> 5) & 0x3F);
            int hour = (int) ((dos >> 11) & 0x1F);
            int day = (int) ((dos >> 16) & 0x1F);
            int month = (int) ((dos >> 21) & 0x0F);
            int year = (int) ((dos >> 25) & 0x7F) + 1980;
            if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || min > 59) {
                return "invalid";
            }
            return String.format("%04d-%02d-%02dT%02d:%02d:%02d", year, month, day, hour, min, sec);
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    private static String unixTime(long unix) {
        if (unix <= 0) {
            return "unset";
        }
        try {
            return java.time.Instant.ofEpochSecond(unix).toString();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    private Document doc(Path path, Map<String, Object> meta, long len) {
        return new Document(path, getMimeType(), getExtension(), "", meta, len,
                getEditCapability());
    }
}
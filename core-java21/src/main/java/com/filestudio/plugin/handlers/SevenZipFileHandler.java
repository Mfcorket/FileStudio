package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 7-Zip 归档处理器：列出归档内条目名、大小与 CRC。
 *
 * <p>PARTIAL 能力——可读取目录结构，但不提供解压。
 *
 * <p>7z 的"头部"并非固定布局，而是一段用变长整数编码的嵌套结构：
 * <pre>
 * 签名头（32 字节）
 *   0   6   '7z' BC AF 27 1C
 *   6   2   格式版本（大端两字节）
 *   8   4   StartHeaderCRC
 *  12   8   NextHeaderOffset（相对偏移 32）
 *  20   8   NextHeaderSize
 *  28   4   NextHeaderCRC
 *
 * 头部（位于 32 + NextHeaderOffset）
 *   NID kHeader
 *   ArchiveProperties   —— 属性 ID 序列，以 kEnd 结束
 *   ...   各 ID 指向的定长属性块，顺序固定：
 *         kPackInfo → kUnPackInfo → kSubStreamsInfo → kFilesInfo
 *         kSize / kCRC
 * </pre>
 *
 * <p>由于头部是一条连续的数值流，要读到最后的 {@code FilesInfo}（文件名列表），
 * 就必须先正确解析前面的 PackInfo 与 UnPackInfo —— 否则流位置会错位。
 */
public class SevenZipFileHandler implements FileHandler {

    /**
     * 7-Zip 归档处理器。
     */
    public SevenZipFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("7z");

    /** 签名头长度。 */
    private static final int SIGNATURE_HEADER_SIZE = 32;

    /** 魔数。 */
    private static final byte[] MAGIC = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    /** 读取上限：头部通常很小，但损坏文件可能声称很大的偏移。 */
    private static final int MAX_READ = 64 * 1024 * 1024;

    /** 最多列出的条目数。 */
    private static final int MAX_ENTRIES = 4096;

    // ---- 属性 ID ----
    private static final int ID_END = 0x00;
    private static final int ID_MAIN_STREAMS_INFO = 0x04;
    private static final int ID_FILES_INFO = 0x05;
    private static final int ID_PACK_INFO = 0x06;
    private static final int ID_UNPACK_INFO = 0x07;
    private static final int ID_SUBSTREAMS_INFO = 0x08;
    private static final int ID_SIZE = 0x09;
    private static final int ID_CRC = 0x0A;
    private static final int ID_FOLDER = 0x0B;
    private static final int ID_CODERS_UNPACK_SIZE = 0x0C;
    private static final int ID_NUM_UNPACK_STREAM = 0x0D;
    private static final int ID_EMPTY_STREAM = 0x0E;
    private static final int ID_EMPTY_FILE = 0x0F;
    private static final int ID_NAME = 0x11;
    private static final int ID_ENCODED_HEADER = 0x17;

    /** 条目描述。 */
    public record Entry(String name, long size, boolean isDirectory, long crc) {}

    @Override
    public String getExtension() {
        return "7z";
    }

    @Override
    public String getMimeType() {
        return "application/x-7z-compressed";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "7-Zip archive";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isSevenZip(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("7z file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "7z");
        meta.put("viewOnly", true);

        byte[] data;
        try {
            data = readPrefix(path, MAX_READ);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading 7z: " + path, e);
        }

        if (data.length < SIGNATURE_HEADER_SIZE || !hasMagic(data)) {
            meta.put("valid", false);
            meta.put("error", "Missing or truncated 7z signature header");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        int major = data[6] & 0xFF;
        int minor = data[7] & 0xFF;
        long nextHeaderOffset = readUInt64(data, 12);
        long nextHeaderSize = readUInt64(data, 20);

        meta.put("valid", true);
        meta.put("formatVersion", major + "." + minor);
        meta.put("nextHeaderOffset", nextHeaderOffset);
        meta.put("nextHeaderSize", nextHeaderSize);

        long headerStart = (long) SIGNATURE_HEADER_SIZE + nextHeaderOffset;
        if (nextHeaderSize == 0 || headerStart < 0 || headerStart + nextHeaderSize > data.length) {
            // 空的归档，或头部超出已读取范围
            meta.put("entryCount", 0);
            if (nextHeaderSize != 0) {
                meta.put("note", "Next header lies beyond the read window");
            }
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        byte[] header = new byte[(int) nextHeaderSize];
        System.arraycopy(data, (int) headerStart, header, 0, header.length);
        // 注意：要从偏移 28 起只取 4 字节作为"已存储的 CRC"，
        // 不能把 data[28..末尾] 整段算进去（那会多算上头部之后的数据）
        long storedCrc = ((long) (data[28] & 0xFF) << 24) | ((data[29] & 0xFF) << 16)
                | ((data[30] & 0xFF) << 8) | (data[31] & 0xFF);
        meta.put("nextHeaderCrcOk", storedCrc == crc32(header, 0, header.length));

        try {
            Reader r = new Reader(header);
            if (r.readByte() != 0x01) {
                // kEncodedHeader：头部本身被压缩，本处理器不解压
                meta.put("encodedHeader", true);
                meta.put("note", "Header is packed (encoded); entry listing requires decompression");
                return new Document(path, getMimeType(), getExtension(), "", meta,
                        file.length(), getEditCapability());
            }

            // ArchiveProperties：属性 ID 序列，直到 kEnd
            List<Integer> properties = new ArrayList<>();
            while (true) {
                int id = (int) r.readNumber();
                if (id == ID_END) {
                    break;
                }
                properties.add(id);
                if (r.eof() || properties.size() > 64) {
                    break;
                }
            }

            boolean havePack = properties.contains(ID_PACK_INFO);
            boolean haveUnpack = properties.contains(ID_UNPACK_INFO);
            boolean haveSubStreams = properties.contains(ID_SUBSTREAMS_INFO);

            FolderInfo folders = new FolderInfo();
            long[] subStreamSizes = new long[0];

            if (havePack) {
                readPackInfo(r, meta);
            }
            if (haveUnpack) {
                readUnpackInfo(r, folders, meta);
            }
            if (haveSubStreams) {
                subStreamSizes = readSubStreamsInfo(r, folders);
            }
            if (properties.contains(ID_FILES_INFO)) {
                readFilesInfo(r, subStreamSizes, meta);
            } else {
                meta.put("entryCount", 0);
                meta.put("fileCount", 0);
                meta.put("directoryCount", 0);
                meta.put("entries", List.of());
                meta.put("note", "No FilesInfo property; entry names unavailable");
            }
        } catch (RuntimeException e) {
            meta.put("note", "Header parse stopped: " + e);
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("7z files are read-only; extract with a dedicated tool");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("entryCount", doc.getMetadata().get("entryCount"));
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

    private static boolean hasMagic(byte[] b) {
        if (b.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (b[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSevenZip(File file) {
        try (var in = Files.newInputStream(file.toPath())) {
            return hasMagic(in.readNBytes(MAGIC.length));
        } catch (Exception e) {
            return false;
        }
    }

    /** 解包文件夹信息：每个输出流的大小与流数量。 */
    private static final class FolderInfo {
        final List<Integer> numUnpackStreams = new ArrayList<>();
        final List<Long> unpackSizes = new ArrayList<>();
        final List<String> coderNames = new ArrayList<>();
        boolean hasNumUnpackStreams;
    }

    /**
     * PackInfo：
     * <pre>
     *   PackPos (uint32) / NumPackStreams (uint32) / PackSize[] / kEnd
     * </pre>
     */
    private static void readPackInfo(Reader r, Map<String, Object> meta) {
        long packPos = r.readUInt32();
        long numPackStreams = r.readUInt32();
        long total = 0;
        List<Long> sizes = new ArrayList<>();
        for (long i = 0; i < numPackStreams && !r.eof(); i++) {
            long s = r.readNumber();
            sizes.add(s);
            total += s;
        }
        r.readByte(); // kEnd
        meta.put("packPos", packPos);
        meta.put("packStreamCount", (int) numPackStreams);
        meta.put("packedBytes", total);
        meta.put("packSizes", sizes);
    }

    /**
     * UnpackInfo：
     * <pre>
     *   kFolder / NumFolders / External / [CodersInfo] / [UnpackSizes] / kEnd
     * </pre>
     */
    private static void readUnpackInfo(Reader r, FolderInfo folders, Map<String, Object> meta) {
        r.readByte();   // kFolder
        long numFolders = r.readUInt32();
        long external = r.readByte();
        if (external != 0) {
            r.readUInt32();   // DataStreamIndex
        }
        meta.put("folderCount", (int) numFolders);

        for (long f = 0; f < numFolders && !r.eof(); f++) {
            long numCoders = r.readUInt32();
            String lastCoder = "";
            for (long c = 0; c < numCoders && !r.eof(); c++) {
                r.readByte();               // coder flags
                long idSize = r.readUInt32();
                byte[] id = r.readBytes((int) idSize);
                lastCoder = coderName(id);

                // 属性：id[0] == 0 时后续字节是属性计数序列
                if (idSize > 0 && id[0] == 0) {
                    for (int i = 1; i < idSize; i++) {
                        if ((id[i] & 0x80) != 0) {
                            r.readNumber();
                        } else {
                            r.readByte();
                        }
                    }
                }
                long numInStreams = r.readNumber();
                long numOutStreams = r.readNumber();
                if (numInStreams != 1 || numOutStreams != 1) {
                    // 复杂绑定：逐项跳过
                    for (long i = 0; i < numInStreams; i++) r.readNumber();
                    for (long i = 0; i < numOutStreams; i++) r.readNumber();
                }
            }
            folders.coderNames.add(lastCoder);
        }

        // UnpackInfo 主体：kNumUnPackStream / kSize / kCRC，直到 kEnd
        while (!r.eof()) {
            int id = r.readByte();
            if (id == ID_END) {
                break;
            }
            if (id == ID_NUM_UNPACK_STREAM) {
                // 每个 folder 一个值：其包含的解包流数量
                for (int f = 0; f < metaFolderCount(meta); f++) {
                    folders.numUnpackStreams.add((int) r.readNumber());
                }
                folders.hasNumUnpackStreams = true;
            } else if (id == ID_CODERS_UNPACK_SIZE) {
                // 每个 folder 的每个输出流一个大小
                for (int i = 0; i < folders.coderNames.size(); i++) {
                    int outs = i < folders.numUnpackStreams.size()
                            ? folders.numUnpackStreams.get(i) : 1;
                    for (int o = 0; o < outs; o++) {
                        folders.unpackSizes.add(r.readNumber());
                    }
                }
            } else if (id == ID_CRC) {
                readDigests(r, countUnpackStreams(folders));
            } else {
                break;
            }
        }

        if (!folders.coderNames.isEmpty()) {
            meta.put("codecs", new ArrayList<>(folders.coderNames));
        }
    }

    private static int metaFolderCount(Map<String, Object> meta) {
        Object v = meta.get("folderCount");
        return v instanceof Integer i ? i : 0;
    }

    private static int countUnpackStreams(FolderInfo f) {
        if (f.hasNumUnpackStreams && !f.numUnpackStreams.isEmpty()) {
            int n = 0;
            for (int v : f.numUnpackStreams) {
                n += v;
            }
            return n;
        }
        return Math.max(1, f.numUnpackStreams.size());
    }

    /**
     * SubStreamsInfo：给出每个解包流的最终大小。
     *
     * <p>若含 {@code kSize} 则显式给出；否则只有"单流文件夹"能从 UnpackInfo 推出大小。
     */
    private static long[] readSubStreamsInfo(Reader r, FolderInfo folders) {
        List<Long> sizes = new ArrayList<>();
        int numFolders = folders.coderNames.size();
        for (int f = 0; f < numFolders; f++) {
            int streamsInFolder = f < folders.numUnpackStreams.size()
                    ? folders.numUnpackStreams.get(f) : 1;
            if (streamsInFolder == 0) {
                continue;
            }
            for (int s = 0; s < streamsInFolder; s++) {
                sizes.add(-1L);   // 稍后由 kSize 覆盖
            }
        }

        boolean sawSize = false;
        while (!r.eof()) {
            int id = r.readByte();
            if (id == ID_END) {
                break;
            }
            if (id == ID_SIZE) {
                for (int i = 0; i < sizes.size(); i++) {
                    sizes.set(i, r.readNumber());
                }
                sawSize = true;
            } else if (id == ID_CRC) {
                readDigests(r, sizes.size());
            } else {
                break;
            }
        }

        if (!sawSize) {
            // 没有显式大小时，用文件夹的输出流大小兜底（仅单流文件夹可靠）
            int idx = 0;
            for (int f = 0; f < numFolders; f++) {
                int outs = f < folders.numUnpackStreams.size()
                        ? folders.numUnpackStreams.get(f) : 1;
                if (outs == 0) {
                    continue;
                }
                if (outs == 1 && idx < folders.unpackSizes.size()) {
                    sizes.set(idx, folders.unpackSizes.get(idx));
                }
                idx += outs;
            }
        }
        return sizes.stream().mapToLong(Long::longValue).toArray();
    }

    /**
     * FilesInfo：条目数、空流位图、名称与 CRC。
     *
     * <pre>
     *   NumFiles (uint32)
     *   循环：PropertyType (number) / Size (number) / 属性数据
     *   以 kEnd 结束
     * </pre>
     */
    private static void readFilesInfo(Reader r, long[] subStreamSizes, Map<String, Object> meta) {
        long numFiles = r.readUInt32();

        boolean[] emptyStream = new boolean[(int) numFiles];
        boolean[] emptyFile = new boolean[(int) numFiles];
        List<String> names = new ArrayList<>();
        long[] crcs = new long[(int) numFiles];
        java.util.Arrays.fill(crcs, -1L);

        boolean sawEmptyStream = false;
        boolean sawEmptyFile = false;

        while (!r.eof()) {
            int type = (int) r.readNumber();
            if (type == ID_END) {
                break;
            }
            long size = r.readNumber();
            int start = r.pos();
            int end = start + (int) size;
            if (end > r.length()) {
                break;
            }

            switch (type) {
                case ID_EMPTY_STREAM -> {
                    boolean[] bits = r.readBitVector((int) numFiles);
                    System.arraycopy(bits, 0, emptyStream, 0, Math.min(bits.length, emptyStream.length));
                    sawEmptyStream = true;
                }
                case ID_EMPTY_FILE -> {
                    // 位图长度等于空流个数
                    int emptyStreams = 0;
                    for (boolean b : emptyStream) {
                        if (b) emptyStreams++;
                    }
                    boolean[] bits = r.readBitVector(emptyStreams);
                    int k = 0;
                    for (int i = 0; i < emptyStream.length; i++) {
                        if (emptyStream[i] && k < bits.length) {
                            emptyFile[i] = bits[k++];
                        }
                    }
                    sawEmptyFile = true;
                }
                case ID_NAME -> {
                    r.readByte();   // External
                    names.addAll(r.readUtf16Names(end));
                }
                case ID_CRC -> {
                    boolean[] defined = r.readBitVector((int) numFiles);
                    for (int i = 0; i < defined.length; i++) {
                        if (defined[i]) {
                            crcs[i] = r.readUInt32();
                        }
                    }
                }
                default -> {
                    // 其他属性（时间戳、属性、大小等）本处理器不解释，直接跳过
                }
            }
            // 无论如何都要跳到属性数据末尾，避免流位置错位
            r.seek(end);
        }

        // 组装条目：空流条目不占用解包流
        List<Entry> entries = new ArrayList<>();
        int streamIndex = 0;
        int dirCount = 0;
        for (int i = 0; i < numFiles && entries.size() < MAX_ENTRIES; i++) {
            String name = i < names.size() ? names.get(i) : ("entry-" + i);
            boolean isDir = sawEmptyStream && i < emptyStream.length && emptyStream[i];
            long size = 0;
            if (!isDir) {
                if (streamIndex < subStreamSizes.length && subStreamSizes[streamIndex] > 0) {
                    size = subStreamSizes[streamIndex];
                }
                streamIndex++;
            } else {
                dirCount++;
            }
            if (sawEmptyStream && sawEmptyFile && i < emptyFile.length && emptyFile[i]) {
                isDir = false;   // 空文件而非空目录
            }
            entries.add(new Entry(name, size, isDir, crcs[i]));
        }

        meta.put("entryCount", entries.size());
        meta.put("directoryCount", dirCount);
        meta.put("fileCount", (int) numFiles - dirCount);
        meta.put("entries", entries);
    }

    private static String coderName(byte[] id) {
        if (id.length >= 3) {
            // LZMA 的传统三字节 id 为 03 01 01
            if ((id[0] & 0xFF) == 0x03 && (id[1] & 0xFF) == 0x01 && (id[2] & 0xFF) == 0x01) {
                return "LZMA";
            }
            if ((id[0] & 0xFF) == 0x03 && (id[1] & 0xFF) == 0x03) {
                return "BCJ x86";
            }
        }
        if (id.length >= 1) {
            // LZMA2 是单字节 id 0x21
            if ((id[0] & 0xFF) == 0x21) {
                return "LZMA2";
            }
            if (id[0] == 0x00) {
                return "Copy";
            }
        }
        StringBuilder sb = new StringBuilder("id:");
        for (byte b : id) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** 跳过 CRC 摘要（allDefined + bitvector + 值）。 */
    private static void readDigests(Reader r, int count) {
        if (count <= 0) {
            return;
        }
        boolean[] defined = r.readBitVector(count);
        for (boolean d : defined) {
            if (d) {
                r.readUInt32();
            }
        }
    }

    // ---- 底层数值读取 ----

    /**
     * 头部字节流读取器，实现 7z 的变长整数编码。
     *
     * <p>{@code readNumber}：首字节最高位为 0 时即为值；否则前导 1 的个数
     * 决定后续读取多少字节，值由后续字节与前导 1 之后的剩余位拼成。
     */
    private static final class Reader {
        private final byte[] b;
        private int p;

        Reader(byte[] b) {
            this.b = b;
            this.p = 0;
        }

        int length() {
            return b.length;
        }

        int pos() {
            return p;
        }

        void seek(int at) {
            p = Math.max(0, Math.min(at, b.length));
        }

        boolean eof() {
            return p >= b.length;
        }

        int readByte() {
            if (p >= b.length) {
                throw new IllegalStateException("unexpected end of 7z header at " + p);
            }
            return b[p++] & 0xFF;
        }

        byte[] readBytes(int n) {
            byte[] out = new byte[n];
            System.arraycopy(b, p, out, 0, Math.min(n, b.length - p));
            p += n;
            return out;
        }

        long readUInt32() {
            return (long) readNumber();
        }

        long readNumber() {
            int first = readByte();
            if ((first & 0x80) == 0) {
                return first;
            }
            long value = 0;
            int mask = 0x80;
            for (int i = 0; i < 8; i++) {
                if ((first & mask) == 0) {
                    long high = first & (mask - 1);
                    value += high << (8 * i);
                    return value;
                }
                value |= ((long) readByte()) << (8 * i);
                mask >>>= 1;
            }
            return value;
        }

        /**
         * READ_VT 位向量。
         *
         * <p>首字节非 0 表示全部为定义；否则读取 bitVector、NumDefined(NUMBER)
         * 与 NumDefined 个比特。
         */
        boolean[] readBitVector(int itemCount) {
            int allDefined = readByte();
            if (allDefined != 0) {
                boolean[] all = new boolean[itemCount];
                java.util.Arrays.fill(all, true);
                return all;
            }
            readByte();               // bitVector（不再细分）
            int numDefined = (int) readNumber();
            boolean[] bits = new boolean[itemCount];
            for (int i = 0; i < numDefined && i < itemCount; i++) {
                bits[i] = true;
            }
            return bits;
        }

        /** 读取 UTF-16LE 名称列表，每个名称前有 2 字节长度（UTF-16 码元数）。 */
        List<String> readUtf16Names(int end) {
            List<String> out = new ArrayList<>();
            while (p + 2 <= end) {
                int lenUnits = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
                p += 2;
                if (lenUnits == 0) {
                    break;
                }
                int byteLen = lenUnits * 2;
                if (p + byteLen > end) {
                    break;
                }
                out.add(new String(b, p, byteLen, StandardCharsets.UTF_16LE));
                p += byteLen;
            }
            return out;
        }
    }

    private static long readUInt64(byte[] b, int off) {
        if (off + 8 > b.length) {
            return 0;
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    private static long crc32(byte[] full, int off) {
        return crc32(full, off, Math.max(0, full.length - off));
    }

    private static long crc32(byte[] full, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(full, off, Math.max(0, Math.min(len, full.length - off)));
        return crc.getValue();
    }

    private static byte[] readPrefix(Path path, int limit) throws IOException {
        long size = Files.size(path);
        int len = (int) Math.min(size, limit);
        try (var in = Files.newInputStream(path)) {
            return in.readNBytes(len);
        }
    }
}

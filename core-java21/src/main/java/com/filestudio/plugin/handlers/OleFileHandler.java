package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OLE 复合文档处理器（Compound File Binary Format，aka CFB）。
 *
 * <p>PARTIAL——读取容器结构与内部流目录，不反序列化 Office 文档内容。
 *
 * <p>这是 {@code .doc} / {@code .xls} / {@code .ppt} / {@code .msi} 的底层容器。
 * 结构分三层：
 * <ol>
 *   <li><b>头部</b>：魔数、扇区大小、FAT/DIFAT/目录流的起始扇区</li>
 *   <li><b>FAT</b>：扇区链数组，把逻辑流串成物理扇区序列</li>
 *   <li><b>目录</b>：128 字节一项的红黑树，记录所有 storage / stream 的名字与大小</li>
 * </ol>
 *
 * <p>识别容器内的特征流（WordDocument、Workbook、PowerPoint Document 等）
 * 可以直接判断这是哪种文档，而不必解析正文——这是元数据查看器最有价值的信息。
 *
 * <p>安全性：所有扇区号在读取前都做越界校验；目录树遍历带已访问集合，
 * 遇到环或坏指针立即停止，不会死循环，也不会越界读盘。
 */
public class OleFileHandler implements FileHandler {

    /**
     * OLE 复合文档处理器。
     */
    public OleFileHandler() {}

    /** CFB 魔数：D0 CF 11 E0 A1 B1 1A E1。 */
    private static final byte[] MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /** 目录项对象类型。 */
    private static final int TYPE_EMPTY = 0;
    private static final int TYPE_STORAGE = 1;
    private static final int TYPE_STREAM = 2;
    private static final int TYPE_ROOT = 5;

    private static final int FREE_SECT = 0xFFFFFFFF;
    private static final int END_OF_CHAIN = 0xFFFFFFFE;

    /** 头部内直接保存的 DIFAT 项数量。 */
    private static final int HEADER_DIFAT_ENTRIES = 109;

    /** 单个目录项的大小。 */
    private static final int DIR_ENTRY_SIZE = 128;

    /** 目录遍历的硬上限，防止异常文件导致无意义的深遍历。 */
    private static final int MAX_ENTRIES = 100_000;

    @Override
    public String getExtension() {
        return "ole";
    }

    @Override
    public String getMimeType() {
        return "application/x-ole-storage";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "OLE compound document (CFB)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) {
            return false;
        }
        if (file.isFile()) {
            return hasMagic(file);
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".doc") || name.endsWith(".xls") || name.endsWith(".ppt")
                || name.endsWith(".msi") || name.endsWith(".msg") || name.endsWith(".ole")
                || name.endsWith(".xlsb");
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("OLE file not readable: " + file);
        }
        Path path = file.toPath();
        Map<String, Object> meta = new LinkedHashMap<>();
        long len = file.length();
        meta.put("size", len);
        meta.put("viewOnly", true);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (len < 512 || !hasMagic(raf)) {
                meta.put("valid", false);
                meta.put("error", "Missing OLE compound file signature");
                return doc(path, meta, len);
            }

            int majorVersion = le16(raf, 0x1A);
            int sectorShift = le16(raf, 0x1E);
            int miniSectorShift = le16(raf, 0x20);
            int sectorSize = 1 << sectorShift;
            int miniSectorSize = 1 << miniSectorShift;

            meta.put("valid", true);
            meta.put("format", "ole");
            meta.put("majorVersion", majorVersion);
            meta.put("minorVersion", le16(raf, 0x18));
            meta.put("sectorSize", sectorSize);
            meta.put("miniSectorSize", miniSectorSize);
            meta.put("miniStreamCutoff", le32(raf, 0x38));
            meta.put("fatSectorCount", le32(raf, 0x2C));
            meta.put("directorySector", le32(raf, 0x30));
            meta.put("miniFatSectorCount", le32(raf, 0x40));
            meta.put("difatSectorCount", le32(raf, 0x44));

            if (sectorSize != 512 && sectorSize != 4096) {
                meta.put("error", "Unexpected sector size: " + sectorSize);
                return doc(path, meta, len);
            }

            // 根目录项的 CLSID 常能说明文档来源程序
            byte[] rootClsid = read(raf, 0x30 + 0, 16);
            int firstDirSector = le32(raf, 0x30);
            int miniCutoff = le32(raf, 0x38);

            List<Integer> fatSectors = collectFatSectors(raf, len, sectorSize);
            List<Integer> fat = readFat(raf, len, sectorSize, fatSectors);

            List<DirEntry> dir = readDirectory(raf, len, sectorSize, fat, firstDirSector);

            List<Map<String, Object>> entries = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            Set<String> storages = new LinkedHashSet<>();
            int streamCount = 0;
            int rootIndex = -1;
            for (int i = 0; i < dir.size(); i++) {
                DirEntry e = dir.get(i);
                if (e.type == TYPE_ROOT) {
                    rootIndex = i;
                    rootClsid = e.clsid;
                }
            }
            if (rootIndex >= 0) {
                // 目录是红黑树，需要从根的 child 指针按左右兄弟遍历
                walk(raf, dir, dir.get(rootIndex).child, "", entries, names, storages,
                        new HashSet<>(), 0);
            }
            for (Map<String, Object> e : entries) {
                if ("stream".equals(e.get("type"))) {
                    streamCount++;
                }
            }

            meta.put("entryCount", entries.size());
            meta.put("streamCount", streamCount);
            meta.put("storageCount", storages.size());
            meta.put("rootClsid", formatClsid(rootClsid));
            if (!entries.isEmpty()) {
                meta.put("entries", entries);
            }
            if (!names.isEmpty()) {
                meta.put("streams", new ArrayList<>(names));
            }

            // 用特征流判断文档类型——比只看扩展名可靠
            String kind = detectKind(names);
            if (kind != null) {
                meta.put("documentKind", kind);
            }
            meta.put("hasSummaryInformation", names.contains("\u0005SummaryInformation"));
            meta.put("hasDocumentSummaryInformation", names.contains("\u0005DocumentSummaryInformation"));
            meta.put("usesMiniStream", miniCutoff > 0 && streamCount > 0);
        } catch (IOException | RuntimeException e) {
            meta.put("valid", false);
            meta.put("error", "Failed to read OLE structure: " + e.getMessage());
        }

        return doc(path, meta, len);
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("OLE documents are not editable");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("valid", hasMagic(file));
        return meta;
    }

    // ---- 目录项 ----

    /** 单个 storage / stream 目录项。 */
    private static final class DirEntry {
        String name = "";
        int type = TYPE_EMPTY;
        int left = -1;
        int right = -1;
        int child = -1;
        byte[] clsid = new byte[16];
        int startSector;
        long size;
    }

    /**
     * 遍历红黑树目录。
     *
     * <p>CFB 的目录是一棵不平衡的红黑树，同层节点靠 left/right 指针串联，
     * 因此必须做中序遍历才能拿到有序列表。用 {@code visited} 防止坏文件造成环。
     */
    private void walk(RandomAccessFile raf, List<DirEntry> dir, int index, String prefix,
                      List<Map<String, Object>> out, Set<String> names, Set<String> storages,
                      Set<Integer> visited, int depth) {
        if (index < 0 || index >= dir.size() || depth > 64 || out.size() >= MAX_ENTRIES) {
            return;
        }
        if (!visited.add(index)) {
            return;
        }
        DirEntry e = dir.get(index);

        walk(raf, dir, e.left, prefix, out, names, storages, visited, depth + 1);

        if (e.type != TYPE_EMPTY && !e.name.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name);
            m.put("path", prefix.isEmpty() ? e.name : prefix + "/" + e.name);
            m.put("type", e.type == TYPE_STREAM ? "stream" : "storage");
            m.put("size", e.size);
            if (!isZero(e.clsid)) {
                m.put("clsid", formatClsid(e.clsid));
            }
            out.add(m);
            if (e.type == TYPE_STREAM) {
                names.add(e.name);
            } else if (e.type == TYPE_STORAGE) {
                storages.add(e.name);
            }
            if (e.type == TYPE_STORAGE) {
                walk(raf, dir, e.child, prefix.isEmpty() ? e.name : prefix + "/" + e.name,
                        out, names, storages, visited, depth + 1);
            }
        }

        walk(raf, dir, e.right, prefix, out, names, storages, visited, depth + 1);
    }

    /**
     * 根据特征流判断文档类型。
     *
     * @return 类型名；无法判断时返回 {@code null}
     */
    private static String detectKind(Set<String> names) {
        if (names.contains("WordDocument")) {
            return "Microsoft Word 97-2003";
        }
        if (names.contains("Workbook") || names.contains("Book")) {
            return "Microsoft Excel 97-2003";
        }
        if (names.contains("PowerPoint Document")) {
            return "Microsoft PowerPoint 97-2003";
        }
        if (names.contains("VisioDocument")) {
            return "Microsoft Visio";
        }
        if (names.contains("Quill")) {
            return "Quill / Scribble";
        }
        if (names.contains("CONTENTS") && names.contains("SPELLING_STATE")) {
            return "Microsoft Works";
        }
        return null;
    }

    // ---- FAT / 目录 ----

    /**
     * 收集 FAT 扇区号：头部内嵌 109 项，其余在 DIFAT 链上。
     */
    private List<Integer> collectFatSectors(RandomAccessFile raf, long fileLen,
                                            int sectorSize) throws IOException {
        List<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < HEADER_DIFAT_ENTRIES; i++) {
            int sect = le32(raf, 0x4C + i * 4);
            if (sect == FREE_SECT || sect == END_OF_CHAIN) {
                continue;
            }
            if (inRange(sect, fileLen, sectorSize)) {
                fatSectors.add(sect);
            }
        }

        int difatStart = le32(raf, 0x44);
        int difatCount = le32(raf, 0x48);
        int entriesPerSector = sectorSize / 4;
        int guard = 0;
        int sect = difatStart;
        Set<Integer> seen = new HashSet<>();

        while (sect != FREE_SECT && sect != END_OF_CHAIN && guard++ < difatCount + 8
                && guard < MAX_ENTRIES && inRange(sect, fileLen, sectorSize) && seen.add(sect)) {
            byte[] data = readSector(raf, fileLen, sectorSize, sect);
            for (int i = 0; i < entriesPerSector - 1; i++) {
                int s = le32(data, i * 4);
                if (s != FREE_SECT && s != END_OF_CHAIN && inRange(s, fileLen, sectorSize)) {
                    fatSectors.add(s);
                }
            }
            sect = le32(data, (entriesPerSector - 1) * 4);
        }
        return fatSectors;
    }

    /** 读取整个 FAT 表。 */
    private List<Integer> readFat(RandomAccessFile raf, long fileLen, int sectorSize,
                                  List<Integer> fatSectors) throws IOException {
        List<Integer> fat = new ArrayList<>();
        for (int s : fatSectors) {
            byte[] data = readSector(raf, fileLen, sectorSize, s);
            for (int i = 0; i < sectorSize / 4; i++) {
                fat.add(le32(data, i * 4));
            }
        }
        return fat;
    }

    /** 沿 FAT 链读取目录流并解析 128 字节一项。 */
    private List<DirEntry> readDirectory(RandomAccessFile raf, long fileLen, int sectorSize,
                                         List<Integer> fat, int startSector) throws IOException {
        List<DirEntry> out = new ArrayList<>();
        int sect = startSector;
        Set<Integer> seen = new HashSet<>();

        while (inRange(sect, fileLen, sectorSize) && seen.add(sect) && seen.size() < MAX_ENTRIES) {
            byte[] data = readSector(raf, fileLen, sectorSize, sect);
            for (int off = 0; off + DIR_ENTRY_SIZE <= sectorSize; off += DIR_ENTRY_SIZE) {
                DirEntry e = parseDirEntry(data, off);
                if (e != null) {
                    out.add(e);
                }
            }
            if (sect >= fat.size()) {
                break;
            }
            int next = fat.get(sect);
            if (next == END_OF_CHAIN || next == FREE_SECT) {
                break;
            }
            sect = next;
        }
        return out;
    }

    /** 解析一个 128 字节目录项；全零项返回 {@code null}。 */
    private DirEntry parseDirEntry(byte[] d, int off) {
        boolean allZero = true;
        for (int i = off; i < off + DIR_ENTRY_SIZE; i++) {
            if (d[i] != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return null;
        }
        DirEntry e = new DirEntry();

        int nameLen = le16(d, off + 0x40) & 0xFFFF;
        // 名称是 UTF-16LE，含结尾的 0 终止符；名字最多 31 个字符
        if (nameLen > 2 && nameLen <= 64) {
            e.name = new String(d, off, nameLen - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        e.type = d[off + 0x42] & 0xFF;
        if (e.type == TYPE_EMPTY) {
            return null;
        }
        e.left = signedLe32(d, off + 0x44);
        e.right = signedLe32(d, off + 0x48);
        e.child = signedLe32(d, off + 0x4C);
        System.arraycopy(d, off + 0x50, e.clsid, 0, 16);
        e.startSector = le32(d, off + 0x74);
        long lo = le32(d, off + 0x78);
        long hi = le32(d, off + 0x7C);
        e.size = lo | (hi << 32);
        return e;
    }

    // ---- 低层读取 ----

    /**
     * 扇区号是否指向文件内的合法扇区。
     */
    private static boolean inRange(int sector, long fileLen, int sectorSize) {
        if (sector < 0) {
            return false;
        }
        // 扇区 N 的偏移是 (N+1) * sectorSize（0 号扇区被头部占用）
        long offset = (long) (sector + 1) * sectorSize;
        return offset + sectorSize <= fileLen;
    }

    private static byte[] readSector(RandomAccessFile raf, long fileLen, int sectorSize, int sector)
            throws IOException {
        byte[] buf = new byte[sectorSize];
        raf.seek((long) (sector + 1) * sectorSize);
        raf.readFully(buf);
        return buf;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int le16(RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        return raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
    }

    private static int le32(RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        return raf.readUnsignedByte()
                | (raf.readUnsignedByte() << 8)
                | (raf.readUnsignedByte() << 16)
                | (raf.readUnsignedByte() << 24);
    }

    /** 目录项里的兄弟指针可能是 -1，符号位要保留。 */
    private static int signedLe32(byte[] b, int off) {
        int v = le32(b, off);
        return v == 0xFFFFFFFF ? -1 : v;
    }

    private static byte[] read(RandomAccessFile raf, long off, int len) throws IOException {
        byte[] buf = new byte[len];
        raf.seek(off);
        raf.readFully(buf);
        return buf;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 格式化 CLSID 为标准 GUID 字符串。
     */
    private static String formatClsid(byte[] b) {
        if (b == null || b.length < 16 || isZero(b)) {
            return null;
        }
        // CFB 用 little-endian 存第一个 3 个分量，其余按 big-endian
        return String.format("%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9],
                b[10], b[11], b[12], b[13], b[14], b[15]);
    }

    private Document doc(Path path, Map<String, Object> meta, long len) {
        return new Document(path, getMimeType(), getExtension(), "", meta, len,
                getEditCapability());
    }

    private static boolean hasMagic(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return hasMagic(raf);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasMagic(RandomAccessFile raf) throws IOException {
        if (raf.length() < 8) {
            return false;
        }
        byte[] head = new byte[8];
        raf.seek(0);
        raf.readFully(head);
        for (int i = 0; i < 8; i++) {
            if (head[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
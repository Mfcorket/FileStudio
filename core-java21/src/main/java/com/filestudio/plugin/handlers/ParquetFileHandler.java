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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apache Parquet 文件处理器。
 *
 * <p>PARTIAL——读取文件尾部的 FileMetaData 与 Schema，但不反序列化行数据。
 *
 * <p>Parquet 的元数据全部位于文件尾部，且使用 <b>Thrift Compact Protocol</b>
 * 编码，因此本处理器实现了一个最小的 Compact 解码器：
 *
 * <pre>
 * 字段头  1 字节：高位 delta（字段 id 相对前一个的增量）+ 低 4 位类型
 * 结束符  1 字节 0x00
 * </pre>
 *
 * 类型编码中 0 表示 BOOLEAN_TRUE，此时字段没有后续负载。
 *
 * <p>结构：{@code PAR1 ... 数据页 ... FileMetaData Thrift} {@code 4 字节元数据长度} {@code PAR1}。
 * {@code FileMetaData} 的字段里，本处理器只取 schema、行列数、createdBy
 * 与压缩方式——也正是这些信息决定了「这份数据能不能被正确读取」。
 */
public class ParquetFileHandler implements FileHandler {

    /**
     * Parquet 文件处理器。
     */
    public ParquetFileHandler() {}

    /** 文件头/尾魔数。 */
    private static final String MAGIC = "PAR1";

    /** 最多读取的尾部字节数；FileMetaData 通常只有几 KB。 */
    private static final int MAX_TAIL = 64 * 1024 * 1024;

    // Thrift Compact 类型
    private static final int T_STOP = 0x00;
    private static final int T_TRUE = 0x01;
    private static final int T_FALSE = 0x02;
    private static final int T_BYTE = 0x03;
    private static final int T_I16 = 0x04;
    private static final int T_I32 = 0x05;
    private static final int T_I64 = 0x06;
    private static final int T_DOUBLE = 0x07;
    private static final int T_BINARY = 0x08;
    private static final int T_LIST = 0x09;
    private static final int T_SET = 0x0A;
    private static final int T_MAP = 0x0B;
    private static final int T_STRUCT = 0x0C;

    /** thrift schema 里 schema 元素的 1-based type 编号到名字。 */
    private static final String[] PHYSICAL_TYPES = {
            "BOOLEAN", "INT32", "INT64", "INT96", "FLOAT", "DOUBLE",
            "BYTE_ARRAY", "FIXED_LEN_BYTE_ARRAY"};

    /** parquet.thrift 中 CompressionCodec 的枚举值。 */
    private static final String[] COMPRESSION = {
            "UNCOMPRESSED", "SNAPPY", "GZIP", "LZO", "BROTLI", "LZ4",
            "ZSTD", "LZ4_RAW"};

    @Override
    public String getExtension() {
        return "parquet";
    }

    @Override
    public String getMimeType() {
        return "application/vnd.apache.parquet";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "Apache Parquet columnar file";
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
        return name.endsWith(".parquet") || name.endsWith(".parq");
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Parquet file not readable: " + file);
        }
        Path path = file.toPath();
        Map<String, Object> meta = new LinkedHashMap<>();
        long len = file.length();
        meta.put("size", len);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            meta.put("valid", hasMagic(raf, len));

            if (!Boolean.TRUE.equals(meta.get("valid"))) {
                meta.put("error", "Missing PAR1 magic");
                return doc(path, meta, len);
            }

            // 尾部：4 字节元数据长度 + PAR1
            byte[] footer = new byte[8];
            raf.seek(len - 8);
            raf.readFully(footer);
            String tailMagic = new String(footer, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!MAGIC.equals(tailMagic)) {
                meta.put("valid", false);
                meta.put("error", "Missing trailing PAR1 magic");
                return doc(path, meta, len);
            }

            int metaLen = le32(footer, 0);
            meta.put("metadataLength", (long) metaLen);
            if (metaLen <= 0 || metaLen > len - 8 || metaLen > MAX_TAIL) {
                meta.put("error", "Implausible metadata length: " + metaLen);
                return doc(path, meta, len);
            }

            byte[] buf = new byte[metaLen];
            raf.seek(len - 8 - metaLen);
            raf.readFully(buf);

            Compact c = new Compact(buf);
            FileMetaData fm = readFileMetaData(c);
            if (fm == null) {
                meta.put("error", "FileMetaData is empty or truncated");
                return doc(path, meta, len);
            }

            meta.put("createdBy", fm.createdBy);
            meta.put("version", fm.version);
            meta.put("numRows", fm.numRows);
            meta.put("columnCount", fm.schema.size());
            meta.put("schema", fm.schema);
            meta.put("compression", fm.compression);

            meta.put("firstDataPageOffset", fm.firstDataPageOffset);
            meta.put("dictionaryPageOffset", fm.dictionaryPageOffset);
            meta.put("parsed", true);
        } catch (IOException | RuntimeException e) {
            meta.put("valid", false);
            meta.put("error", "Failed to read Parquet metadata: " + e.getMessage());
        }

        return doc(path, meta, len);
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Parquet files are not editable");
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

    // ---- FileMetaData ----

    /** FileMetaData 中本处理器关心的部分。 */
    private static final class FileMetaData {
        int version = -1;
        long numRows;
        String createdBy;
        Long firstDataPageOffset;
        Long dictionaryPageOffset;
        String compression = "unknown";
        final List<String> schema = new ArrayList<>();
    }

    /**
     * 解析 FileMetaData 的顶层字段。
     *
     * <p>字段编号来自 parquet.thrift：
     * 1=version, 2=schema, 3=num_rows, 4=row_groups, 5=key_value_metadata,
     * 6=created_by, 7=column_orders, 8=encryption_algorithm,
     * 9=footer_signing_key_metadata
     */
    private FileMetaData readFileMetaData(Compact c) throws IOException {
        if (c.eof()) {
            return null;
        }
        FileMetaData fm = new FileMetaData();
        int lastId = 0;

        while (!c.eof()) {
            int hdr = c.u8();
            if (hdr == T_STOP) {
                break;
            }
            int type = hdr & 0x0F;
            int delta = (hdr & 0xF0) >>> 4;
            int id = delta == 0 ? c.readVarint32() : lastId + delta;
            lastId = id;

            switch (id) {
                case 1 -> fm.version = c.readI32AsInt();
                case 2 -> fm.schema.addAll(readSchemaList(c));
                case 3 -> fm.numRows = c.readI64();
                case 4 -> fm.compression = readRowGroupCompression(c);
                case 5 -> c.skipStruct();
                case 6 -> fm.createdBy = c.readString();
                case 7 -> c.skipStruct();
                case 8 -> c.skipStruct();
                case 9 -> c.skipStruct();
                default -> {
                    if (!c.skip(type)) {
                        // 无法跳过的未知字段直接放弃整个元数据，避免误读
                        return fm.schema.isEmpty() && fm.createdBy == null ? null : fm;
                    }
                }
            }
        }
        return fm;
    }

    /**
     * 从第一个 row group 的第一个 column chunk 读取压缩方式。
     *
     * <p>嵌套路径：row_groups(4) → columns(1) → meta_data(3) → codec(4)。
     *
     * <p>注意：即使已经拿到答案，也必须把剩余的 row group 全部消费掉，
     * 否则游标会停在嵌套结构内部，导致后面的 created_by 等字段被错位读取。
     *
     * @return 压缩方式名称；无法确定时返回 {@code "unknown"}
     */
    private String readRowGroupCompression(Compact c) throws IOException {
        int sizeAndType = c.u8();
        int elemType = sizeAndType & 0x0F;
        int groups = sizeAndType >>> 4;
        if (groups == 15) {
            groups = c.readVarint32();
        }
        if (elemType != T_STRUCT) {
            c.skip(elemType);
            return "unknown";
        }
        String found = null;
        for (int g = 0; g < groups; g++) {
            String codec = readRowGroup(c);
            if (found == null && codec != null) {
                found = codec;
            }
        }
        return found == null ? "unknown" : found;
    }

    /** 消费完一个 RowGroup 结构。 */
    private String readRowGroup(Compact c) throws IOException {
        String found = null;
        int lastId = 0;
        while (!c.eof()) {
            int hdr = c.u8();
            if (hdr == T_STOP) {
                break;
            }
            int type = hdr & 0x0F;
            int delta = (hdr & 0xF0) >>> 4;
            int id = delta == 0 ? c.readVarint32() : lastId + delta;
            lastId = id;

            if (id == 1 && found == null) {     // columns: list<ColumnChunk>
                String codec = readColumns(c);
                if (codec != null) {
                    found = codec;
                }
            } else if (!c.skip(type)) {
                return found;
            }
        }
        return found;
    }

    /** 消费完 columns 列表。 */
    private String readColumns(Compact c) throws IOException {
        int sizeAndType = c.u8();
        int elemType = sizeAndType & 0x0F;
        int columns = sizeAndType >>> 4;
        if (columns == 15) {
            columns = c.readVarint32();
        }
        if (elemType != T_STRUCT) {
            c.skip(elemType);
            return null;
        }

        String found = null;
        for (int i = 0; i < columns; i++) {
            String codec = readColumnChunk(c);
            if (found == null && codec != null) {
                found = codec;
            }
        }
        return found;
    }

    /** 消费完一个 ColumnChunk，取出 meta_data.codec。 */
    private String readColumnChunk(Compact c) throws IOException {
        String found = null;
        int lastId = 0;
        while (!c.eof()) {
            int hdr = c.u8();
            if (hdr == T_STOP) {
                break;
            }
            int type = hdr & 0x0F;
            int delta = (hdr & 0xF0) >>> 4;
            int id = delta == 0 ? c.readVarint32() : lastId + delta;
            lastId = id;

            if (id == 3 && found == null) {     // meta_data: ColumnMetaData
                String codec = readColumnMetaCodec(c);
                if (codec != null) {
                    found = codec;
                }
            } else if (!c.skip(type)) {
                return found;
            }
        }
        return found;
    }

    /**
     * 消费完 ColumnMetaData 结构并取出第 4 个字段 codec。
     *
     * <p>必须读到 STOP 为止：提前返回会把 STOP 字节留给上层，
     * 使嵌套结构的结束标记整体上移一层，之后的字段全部错位。
     */
    private String readColumnMetaCodec(Compact c) throws IOException {
        String found = null;
        int lastId = 0;
        while (!c.eof()) {
            int hdr = c.u8();
            if (hdr == T_STOP) {
                break;
            }
            int type = hdr & 0x0F;
            int delta = (hdr & 0xF0) >>> 4;
            int id = delta == 0 ? c.readVarint32() : lastId + delta;
            lastId = id;

            if (id == 4 && found == null && type == T_I32) {
                found = compressionName(c.readI32AsInt());
            } else if (!c.skip(type)) {
                return found;
            }
        }
        return found;
    }

    /**
     * 解析 schema 列表（thrift.thrift 中 list 字段的 element type 存在元素个数里）。
     *
     * <p>每个元素是 parquet.thrift 的 SchemaElement，只取 name(4)、
     * type(5)、 repetition_type(3)、num_children(6)。
     */
    private List<String> readSchemaList(Compact c) throws IOException {
        List<String> out = new ArrayList<>();
        int sizeAndType = c.u8();
        int elemType = sizeAndType & 0x0F;
        int size = sizeAndType >>> 4;
        if (size == 15) {
            size = c.readVarint32();
        }
        if (elemType != T_STRUCT) {
            c.skip(elemType);
            return out;
        }

        int lastId = 0;
        String currentName = null;
        int currentType = -1;
        int currentRepetition = -1;
        int currentChildren = -1;
        for (int i = 0; i < size; i++) {
            currentName = null;
            currentType = -1;
            currentRepetition = -1;
            currentChildren = -1;
            lastId = 0;

            while (!c.eof()) {
                int hdr = c.u8();
                if (hdr == T_STOP) {
                    break;
                }
                int type = hdr & 0x0F;
                int delta = (hdr & 0xF0) >>> 4;
                int id = delta == 0 ? c.readVarint32() : lastId + delta;
                lastId = id;

                switch (id) {
                    case 1 -> currentType = c.readI32AsInt();          // type
                    case 2 -> c.skip(type);                           // type_length
                    case 3 -> currentRepetition = c.readI32AsInt();   // repetition_type
                    case 4 -> currentName = c.readString();            // name
                    case 5 -> currentChildren = c.readI32AsInt();      // num_children
                    case 6 -> c.skip(type);                           // converted_type
                    case 7 -> c.skip(type);                           // scale
                    case 8 -> c.skip(type);                           // precision
                    case 9 -> c.skip(type);                           // field_id
                    case 10 -> c.skip(type);                          // logicalType
                    default -> {
                        if (!c.skip(type)) {
                            break;
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            // parquet.thrift: REQUIRED=0, OPTIONAL=1, REPEATED=2
            sb.append(switch (currentRepetition) {
                case 0 -> "required ";
                case 1 -> "optional ";
                case 2 -> "repeated ";
                default -> "? ";
            });
            if (currentName != null) {
                sb.append(currentName);
            }
            // 根节点没有 name/type，是 schema 的分组节点
            if (currentType >= 0 && currentType < PHYSICAL_TYPES.length) {
                sb.append(": ").append(PHYSICAL_TYPES[currentType]);
            } else if (currentType == -1 && currentName == null) {
                sb.append("root");
            }
            if (currentChildren > 0) {
                sb.append(" (").append(currentChildren).append(" children)");
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ---- 最小 Thrift Compact 解码器 ----

    /**
     * Thrift Compact Protocol 读取器，只实现 Parquet 元数据需要的部分。
     *
     * <p>所有读取都基于字节数组下标，越界抛 {@link IOException}，
     * 由上层降级为「元数据解析失败」而不是让整个解析崩掉。
     */
    private static final class Compact {
        private final byte[] b;
        private int pos;

        Compact(byte[] buf) {
            this.b = buf;
        }

        boolean eof() {
            return pos >= b.length;
        }

        int u8() throws IOException {
            if (pos >= b.length) {
                throw new IOException("compact read past end at " + pos);
            }
            return b[pos++] & 0xFF;
        }

        /** 无符号 varint（zigzag 编码的有符号整数用 {@link #readI64()} 等）。 */
        int readVarint32() throws IOException {
            int result = 0;
            int shift = 0;
            while (true) {
                int cur = u8();
                result |= (cur & 0x7F) << shift;
                if ((cur & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("varint32 too long");
                }
            }
        }

        /** zigzag 编码的 int32。 */
        int readI32AsInt() throws IOException {
            int n = readVarint32();
            return (n >>> 1) ^ -(n & 1);
        }

        /** zigzag 编码的 int64。 */
        long readI64() throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                int cur = u8();
                result |= ((long) (cur & 0x7F)) << shift;
                if ((cur & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IOException("varint64 too long");
                }
            }
            return (result >>> 1) ^ -(result & 1);
        }

        /** thrift string/binary 的长度前缀是 varint32（不是 zigzag）。 */
        int readLength() throws IOException {
            return readVarint32();
        }

        String readString() throws IOException {
            int len = readLength();
            if (len < 0 || pos + len > b.length) {
                throw new IOException("string length out of range: " + len);
            }
            String s = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        /**
         * 跳过当前字段的负载。
         *
         * @return 能否安全跳过
         */
        boolean skip(int type) throws IOException {
            switch (type) {
                case T_TRUE, T_FALSE -> {
                    return true;                      // 布尔值编码在字段头里
                }
                case T_BYTE -> {
                    u8();
                    return true;
                }
                case T_I16, T_I32, T_I64 -> {
                    readVarint32();
                    return true;
                }
                case T_DOUBLE -> {
                    skipBytes(8);
                    return true;
                }
                case T_BINARY -> {
                    int len = readLength();
                    skipBytes(len);
                    return true;
                }
                case T_LIST, T_SET -> {
                    int sizeAndType = u8();
                    int elemType = sizeAndType & 0x0F;
                    int size = sizeAndType >>> 4;
                    if (size == 15) {
                        size = readVarint32();
                    }
                    for (int i = 0; i < size; i++) {
                        if (!skip(elemType)) {
                            return false;
                        }
                    }
                    return true;
                }
                case T_MAP -> {
                    int size = readVarint32();
                    if (size == 0) {
                        return true;
                    }
                    int types = u8();
                    int keyType = (types & 0xF0) >>> 4;
                    int valType = types & 0x0F;
                    for (int i = 0; i < size; i++) {
                        if (!skip(keyType) || !skip(valType)) {
                            return false;
                        }
                    }
                    return true;
                }
                case T_STRUCT -> {
                    return skipStruct();
                }
                default -> {
                    return false;
                }
            }
        }

        /** 跳过嵌套 struct 直到其 STOP 字节。 */
        boolean skipStruct() throws IOException {
            while (!eof()) {
                int hdr = u8();
                if (hdr == T_STOP) {
                    return true;
                }
                int type = hdr & 0x0F;
                int delta = (hdr & 0xF0) >>> 4;
                if (delta == 0) {
                    readVarint32();               // 字段 id
                }
                if (!skip(type)) {
                    return false;
                }
            }
            return false;
        }

        private void skipBytes(int n) throws IOException {
            if (n < 0 || pos + n > b.length) {
                throw new IOException("skip past end: " + n);
            }
            pos += n;
        }
    }

    // ---- helpers ----

    private Document doc(Path path, Map<String, Object> meta, long len) {
        return new Document(path, getMimeType(), getExtension(), "", meta, len,
                getEditCapability());
    }

    private static boolean hasMagic(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return hasMagic(raf, raf.length());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasMagic(RandomAccessFile raf, long len) throws IOException {
        if (len < 8) {
            return false;
        }
        byte[] head = new byte[4];
        raf.seek(0);
        raf.readFully(head);
        return MAGIC.equals(new String(head, java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * 解码压缩方式枚举值。
     *
     * @return 名称；未知值返回带数字的原文
     */
    static String compressionName(int codec) {
        if (codec >= 0 && codec < COMPRESSION.length) {
            return COMPRESSION[codec];
        }
        return "codec#" + codec;
    }
}
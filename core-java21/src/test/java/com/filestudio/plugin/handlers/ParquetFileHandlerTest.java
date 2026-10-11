package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParquetFileHandlerTest {

    private final ParquetFileHandler handler = new ParquetFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("parquet", handler.getExtension());
        assertEquals("application/vnd.apache.parquet", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void readsFooterMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("users.parquet");
        Files.write(p, buildParquet(buildFileMetaData(2, 1000L, "parquet-mr version 1.13.1",
                snappyCodec())));

        assertTrue(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals(2, m.get("version"));
        assertEquals(1000L, m.get("numRows"));
        assertEquals("parquet-mr version 1.13.1", m.get("createdBy"));
        assertEquals(3, m.get("columnCount"));
        assertEquals("SNAPPY", m.get("compression"));
        assertEquals(true, m.get("parsed"));
        // 文件 = PAR1(4) + 数据页(8) + 元数据 + 长度(4) + PAR1(4)
        assertEquals(p.toFile().length() - 20, m.get("metadataLength"));
    }

    @Test
    void decodesSchemaElements(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("s.parquet");
        Files.write(p, buildParquet(buildFileMetaData(2, 7L, "test", null)));

        @SuppressWarnings("unchecked")
        List<String> schema = (List<String>) handler.parse(p.toFile()).getMetadata().get("schema");
        assertNotNull(schema);
        assertEquals(3, schema.size());

        // 根节点是分组节点，只有名字与子节点数
        assertTrue(schema.get(0).contains("schema"));
        assertTrue(schema.get(0).contains("2 children"));
        // required + int32
        assertEquals("required id: INT32", schema.get(1));
        // optional + byte_array
        assertEquals("optional name: BYTE_ARRAY", schema.get(2));
    }

    @Test
    void decodesLargeRowCount(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.parquet");
        // 超过 int 范围，验证 zigzag varint 的高位处理
        long rows = 5_000_000_000L;
        Files.write(p, buildParquet(buildFileMetaData(1, rows, "big", null)));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(rows, m.get("numRows"));
    }

    @Test
    void handlesFileWithoutCreatedBy(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("minimal.parquet");
        Thrift t = new Thrift();
        t.field(1, T_I32, 2);                 // version
        t.field(3, T_I64, 42L);               // num_rows
        t.stop();
        Files.write(p, buildParquet(t.toByteArray()));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals(42L, m.get("numRows"));
        assertEquals(0, m.get("columnCount"));
        assertNull(m.get("createdBy"));
        assertEquals("unknown", m.get("compression"));
    }

    @Test
    void handlesUnknownCompressionCodec(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("weird.parquet");
        Files.write(p, buildParquet(buildFileMetaData(1, 1L, "x", codec(99))));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals("codec#99", m.get("compression"));
    }

    @Test
    void rejectsMissingMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.parquet");
        byte[] data = buildParquet(buildFileMetaData(1, 1L, "x", null));
        data[0] = 'X';
        Files.write(p, data);

        assertFalse(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("PAR1"));
    }

    @Test
    void rejectsMissingTrailingMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("tail.parquet");
        byte[] data = buildParquet(buildFileMetaData(1, 1L, "x", null));
        data[data.length - 1] = 'X';
        Files.write(p, data);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("trailing"));
    }

    @Test
    void rejectsImplausibleMetadataLength(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("len.parquet");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        o.write(new byte[16]);
        o.write(0x7F); o.write(0xFF); o.write(0xFF); o.write(0xFF);   // 超大长度
        o.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        Files.write(p, o.toByteArray());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertTrue(String.valueOf(m.get("error")).contains("Implausible"));
    }

    @Test
    void handlesTruncatedFooter(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("trunc.parquet");
        // 声明的元数据长度大于实际可用字节
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        o.write(new byte[4]);
        o.write(0x40); o.write(0); o.write(0); o.write(0);            // 声明 64 字节
        o.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        Files.write(p, o.toByteArray());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertTrue(String.valueOf(m.get("error")).contains("Implausible"));
    }

    @Test
    void rejectsEmptyFile(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.parquet");
        Files.write(p, new byte[0]);

        assertFalse(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.parquet");
        Files.write(p, buildParquet(buildFileMetaData(1, 1L, "x", null)));

        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.parquet").toFile()));
    }

    @Test
    void getMetadataReportsValidity(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("good.parquet");
        Files.write(good, buildParquet(buildFileMetaData(1, 1L, "x", null)));
        assertEquals(true, handler.getMetadata(good.toFile()).get("valid"));

        Path bad = dir.resolve("bad.parquet");
        Files.write(bad, new byte[64]);
        assertEquals(false, handler.getMetadata(bad.toFile()).get("valid"));

        assertTrue(handler.getMetadata(dir.resolve("nope.parquet").toFile()).isEmpty());
    }

    // ---- Thrift Compact 编码辅助 ----

    private static final int T_I32 = 0x05;
    private static final int T_I64 = 0x06;
    private static final int T_BINARY = 0x08;
    private static final int T_LIST = 0x09;
    private static final int T_STRUCT = 0x0C;

    /** Thrift Compact Protocol 写入器，只覆盖测试需要的类型。 */
    private static final class Thrift {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        private int lastId;

        void field(int id, int type) {
            int delta = id - lastId;
            if (delta > 0 && delta <= 15) {
                o.write((delta << 4) | type);
            } else {
                o.write(type);                 // delta = 0 → 后面跟 varint 字段 id
                writeUnsignedVarint(id);
            }
            lastId = id;
        }

        void field(int id, int type, long v) {
            field(id, type);
            writeZigZag(v);
        }

        void fieldString(int id, String s) {
            field(id, T_BINARY);
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeUnsignedVarint(bytes.length);
            o.write(bytes, 0, bytes.length);
        }

        void structField(int id) {
            field(id, T_STRUCT);
        }

        void enumField(int id, int value) {
            field(id, T_I32, value);
        }

        /** 列表头：元素类型与「数量 & 15」打包在同一字节。 */
        void listHeader(int elemType, int count) {
            if (count < 15) {
                o.write((count << 4) | elemType);
            } else {
                o.write((15 << 4) | elemType);
                writeUnsignedVarint(count);
            }
        }

        void stop() {
            o.write(0x00);
            lastId = 0;
        }

        void writeZigZag(long v) {
            writeUnsignedVarint((v << 1) ^ (v >> 63));
        }

        void writeUnsignedVarint(long v) {
            while (true) {
                if ((v & ~0x7FL) == 0) {
                    o.write((int) v);
                    return;
                }
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
        }

        byte[] toByteArray() {
            return o.toByteArray();
        }
    }

    /** ColumnMetaData 中的 codec 字段（第 4 个）。 */
    private static byte[] codec(int value) {
        Thrift meta = new Thrift();
        meta.enumField(1, 1);          // type
        meta.enumField(4, value);      // codec
        meta.stop();
        return meta.toByteArray();
    }

    /** RowGroup.columns 中第一个 ColumnChunk 的 meta_data。 */
    private static byte[] snappyCodec() {
        return codec(1);                // SNAPPY
    }

    /**
     * 组装 FileMetaData：version、schema、num_rows、row_groups、created_by。
     *
     * <p>SchemaElement 的字段号：1=type, 3=repetition_type, 4=name, 5=num_children。
     */
    private static byte[] buildFileMetaData(int version, long numRows, String createdBy,
                                            byte[] columnMetaData) throws IOException {
        Thrift t = new Thrift();
        t.field(1, T_I32, version);

        // schema: list<SchemaElement>
        // 列表字段必须先写字段头（含相对上一字段的 delta），再写集合头
        t.field(2, T_LIST);
        t.listHeader(T_STRUCT, 3);

        // 根节点：name=schema, num_children=2
        Thrift root = new Thrift();
        root.fieldString(4, "schema");
        root.field(5, T_I32, 2);
        root.stop();
        t.o.write(root.toByteArray());

        // required id: INT32
        Thrift c1 = new Thrift();
        c1.field(3, T_I32, 0);          // repetition_type = REQUIRED(0)
        c1.fieldString(4, "id");
        c1.field(1, T_I32, 1);          // type = INT32
        c1.stop();
        t.o.write(c1.toByteArray());

        // optional name: BYTE_ARRAY
        Thrift c2 = new Thrift();
        c2.field(3, T_I32, 1);          // repetition_type = OPTIONAL(1)
        c2.fieldString(4, "name");
        c2.field(1, T_I32, 6);          // type = BYTE_ARRAY
        c2.stop();
        t.o.write(c2.toByteArray());

        t.field(3, T_I64, numRows);

        if (columnMetaData != null) {
            // row_groups: list<RowGroup>
            t.field(4, T_LIST);
            t.listHeader(T_STRUCT, 1);
            Thrift rg = new Thrift();
            // columns: list<ColumnChunk>
            rg.field(1, T_LIST);
            rg.listHeader(T_STRUCT, 1);
            Thrift chunk = new Thrift();
            chunk.field(3, T_STRUCT);        // meta_data
            chunk.o.write(columnMetaData);
            chunk.stop();
            rg.o.write(chunk.toByteArray());
            rg.stop();
            t.o.write(rg.toByteArray());
        }

        if (createdBy != null) {
            t.fieldString(6, createdBy);
        }
        t.stop();
        return t.toByteArray();
    }

    /**
     * 包成完整 Parquet 文件：PAR1 + 数据页 + FileMetaData + 长度 + PAR1。
     */
    private static byte[] buildParquet(byte[] fileMetaData) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        o.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});      // 模拟数据页
        o.write(fileMetaData);
        int n = fileMetaData.length;
        o.write(n & 0xFF);
        o.write((n >>> 8) & 0xFF);
        o.write((n >>> 16) & 0xFF);
        o.write((n >>> 24) & 0xFF);
        o.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        return o.toByteArray();
    }
}
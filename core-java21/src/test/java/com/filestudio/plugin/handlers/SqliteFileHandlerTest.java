package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SqliteFileHandlerTest {

    private final SqliteFileHandler handler = new SqliteFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("sqlite", handler.getExtension());
        assertEquals("application/vnd.sqlite3", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    @Test
    void parsesHeaderFields(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("test.sqlite");
        // pageSize=4096, pageCount=12, textEncoding=3 (UTF-16BE), sqliteVersion=3045001
        Files.write(p, buildSqlite(4096, 12, 3, 3045001));

        Document doc = handler.parse(p.toFile());
        assertEquals("application/vnd.sqlite3", doc.getMimeType());
        assertEquals("sqlite", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(4096, doc.getMetadata().get("pageSize"));
        assertEquals(12, doc.getMetadata().get("pageCount"));
        assertEquals(1, doc.getMetadata().get("writeVersion"));
        assertEquals(1, doc.getMetadata().get("readVersion"));
        assertEquals("UTF-16BE", doc.getMetadata().get("textEncoding"));
        assertEquals("3.45.1", doc.getMetadata().get("sqliteVersion"));
        assertEquals(7, doc.getMetadata().get("userVersion"));
    }

    /** 页大小字段存 1 时代表 65536。 */
    @Test
    void decodesMaxPageSize(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("big.sqlite");
        Files.write(p, buildSqliteWithPageSizeField(1));
        Document doc = handler.parse(p.toFile());
        assertEquals(65536, doc.getMetadata().get("pageSize"));
    }

    @Test
    void countsSchemaObjects(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("schema.sqlite");
        Files.write(p, buildSqliteWithSchema(
                "CREATE TABLE users(id INTEGER, name TEXT)",
                "CREATE TABLE orders(id INTEGER)",
                "CREATE INDEX idx_users ON users(name)",
                "CREATE UNIQUE INDEX idx_orders ON orders(id)",
                "CREATE VIEW active AS SELECT * FROM users",
                "CREATE TRIGGER trg AFTER INSERT ON users BEGIN SELECT 1; END"));

        Document doc = handler.parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("tableCount"));
        assertEquals(2, doc.getMetadata().get("indexCount"));
        assertEquals(1, doc.getMetadata().get("viewCount"));
        assertEquals(1, doc.getMetadata().get("triggerCount"));
        assertEquals(6, doc.getMetadata().get("objectCount"));
    }

    /** CREATE 后的限定词（UNIQUE / TEMP / IF NOT EXISTS）不应影响对象类型判定。 */
    @Test
    void handlesCreateQualifiers(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("qual.sqlite");
        Files.write(p, buildSqliteWithSchema(
                "CREATE UNIQUE INDEX idx_a ON t(a)",
                "CREATE TEMP TABLE tmp1(x)",
                "CREATE TABLE IF NOT EXISTS t2(y)",
                "CREATE VIRTUAL TABLE ft USING fts5(body)"));

        Document doc = handler.parse(p.toFile());
        // TEMP TABLE、TABLE IF NOT EXISTS、VIRTUAL TABLE 都是表
        assertEquals(3, doc.getMetadata().get("tableCount"));
        assertEquals(1, doc.getMetadata().get("indexCount"), "UNIQUE INDEX 仍算索引");
    }

    @Test
    void reportsEmptySchema(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.sqlite");
        Files.write(p, buildSqlite(4096, 1, 1, 1));

        Document doc = handler.parse(p.toFile());
        assertEquals(0, doc.getMetadata().get("objectCount"));
        assertNotNull(doc.getMetadata().get("note"));
    }

    @Test
    void rejectsNonSqliteContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.sqlite");
        Files.writeString(p, "this is not a database", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void rejectsTruncatedHeader(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("short.sqlite");
        Files.write(p, "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII));
        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.sqlite")));
        assertTrue(handler.canHandle(new java.io.File("b.db")));
        assertTrue(handler.canHandle(new java.io.File("c.db3")));
        assertFalse(handler.canHandle(new java.io.File("d.txt")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.sqlite");
        Files.write(p, buildSqlite(4096, 1, 1, 1));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.sqlite").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v.sqlite");
        Files.write(p, buildSqlite(4096, 1, 1, 1));
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }

    // ---- 辅助 ----

    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * 构造最小 SQLite 文件：100 字节头 + 一个 4096 字节页。
     *
     * @param pageSizeField 头部偏移 16 处的原始值（1 表示 65536）
     * @param pageCount     偏移 28 处的页数
     * @param encoding      偏移 56 处的文本编码
     * @param sqliteVersion 偏移 96 处的版本号，如 3045001
     */
    private static byte[] buildSqlite(int pageSizeField, int pageCount, int encoding, int sqliteVersion) {
        byte[] page = new byte[Math.max(4096, 512)];
        System.arraycopy(MAGIC, 0, page, 0, MAGIC.length);
        putBe16(page, 16, pageSizeField);
        page[18] = 1; // write version
        page[19] = 1; // read version
        putBe32(page, 28, pageCount);
        putBe32(page, 44, 4);          // schema format
        putBe32(page, 56, encoding);
        putBe32(page, 60, 7);          // user version
        putBe32(page, 68, 0x46435354); // application id "FCST"
        putBe32(page, 92, 1);          // version-valid-for
        putBe32(page, 96, sqliteVersion);
        return page;
    }

    private static byte[] buildSqliteWithPageSizeField(int pageSizeField) {
        return buildSqlite(pageSizeField, 1, 1, 3045001);
    }

    /** 在首页尾部写入若干 CREATE 语句，供 schema 统计使用。 */
    private static byte[] buildSqliteWithSchema(String... statements) {
        byte[] page = buildSqlite(4096, 1, 1, 3045001);
        StringBuilder sb = new StringBuilder();
        for (String s : statements) {
            sb.append(s).append(';');
        }
        byte[] text = sb.toString().getBytes(StandardCharsets.UTF_8);
        int offset = 100;
        System.arraycopy(text, 0, page, offset, Math.min(text.length, page.length - offset));
        return page;
    }

    private static void putBe16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }
}

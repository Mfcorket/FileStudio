package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SQLite 数据库处理器：提取页大小、文本编码、表数量与库版本等元数据。
 *
 * <p>VIEW_ONLY 能力——数据库不可作为文本编辑，仅供信息面板展示。
 *
 * <p>实现依据 SQLite 文件格式官方文档的头 100 字节：
 * <pre>
 *   偏移  长度  含义
 *    0     16   魔数 "SQLite format 3\0"
 *   16      2   页大小（大端；值为 1 表示 65536）
 *   18      1   文件格式写版本
 *   19      1   文件格式读版本
 *   28      4   数据库页数
 *   44      4   schema 格式号
 *   56      4   文本编码（1=UTF-8, 2=UTF-16LE, 3=UTF-16BE）
 *   60      4   user_version（应用自定义版本号）
 *   68      4   application_id
 *   96      4   写入该文件的 SQLite 版本号
 * </pre>
 */
public class SqliteFileHandler implements FileHandler {

    /**
     * SQLite 数据库处理器。
     */
    public SqliteFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("sqlite", "sqlite3", "db", "db3");

    /** 文件头魔数，长度 16。 */
    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /** 头部固定长度。 */
    private static final int HEADER_SIZE = 100;

    /** 扫描 sqlite_master 时的最大字节数，避免超大库卡顿。 */
    private static final long MAX_SCAN_BYTES = 1024 * 1024;

    @Override
    public String getExtension() {
        return "sqlite";
    }

    @Override
    public String getMimeType() {
        return "application/vnd.sqlite3";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "SQLite database";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isSqlite(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("SQLite file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, HEADER_SIZE);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "sqlite");
        meta.put("viewOnly", true);

        if (head.length < HEADER_SIZE || !hasMagic(head)) {
            meta.put("valid", false);
            meta.put("error", "Missing or truncated SQLite header");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        meta.put("valid", true);
        meta.put("pageSize", pageSize(head));
        meta.put("pageCount", beInt(head, 28));
        meta.put("writeVersion", beUnsignedByte(head, 18));
        meta.put("readVersion", beUnsignedByte(head, 19));
        meta.put("schemaFormat", beInt(head, 44));
        meta.put("textEncoding", textEncoding(beInt(head, 56)));
        meta.put("userVersion", beInt(head, 60));
        meta.put("applicationId", beInt(head, 68));
        meta.put("sqliteVersion", formatSqliteVersion(beInt(head, 96)));

        // sqlite_master 的第 1 页是 B-tree 内部/叶子页，扫描其中 CREATE 语句估算对象数量
        SchemaCounts counts = countSchemaObjects(path, head);
        meta.put("tableCount", counts.tables());
        meta.put("indexCount", counts.indexes());
        meta.put("viewCount", counts.views());
        meta.put("triggerCount", counts.triggers());
        int total = counts.tables() + counts.indexes() + counts.views() + counts.triggers();
        meta.put("objectCount", total);
        if (total == 0) {
            meta.put("note", "No schema objects found in first page (WAL or empty database?)");
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("SQLite databases are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] head = readHead(file.toPath(), HEADER_SIZE);
            if (head.length >= HEADER_SIZE && hasMagic(head)) {
                meta.put("pageSize", pageSize(head));
                meta.put("pageCount", beInt(head, 28));
                meta.put("textEncoding", textEncoding(beInt(head, 56)));
            }
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    /** schema 对象计数。 */
    private record SchemaCounts(int tables, int indexes, int views, int triggers) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isSqlite(File file) {
        try {
            return hasMagic(readHead(file.toPath(), MAGIC.length));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasMagic(byte[] h) {
        if (h.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (h[i] != MAGIC[i]) return false;
        }
        return true;
    }

    private static byte[] readHead(Path path, int len) {
        try (var in = Files.newInputStream(path)) {
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
            throw new FileStudioException("Failed reading SQLite header: " + path, e);
        }
    }

    /** 页大小；头部存 1 时代表 65536。 */
    private static int pageSize(byte[] h) {
        int raw = ((h[16] & 0xFF) << 8) | (h[17] & 0xFF);
        return raw == 1 ? 65536 : raw;
    }

    private static String textEncoding(int code) {
        return switch (code) {
            case 1 -> "UTF-8";
            case 2 -> "UTF-16LE";
            case 3 -> "UTF-16BE";
            default -> "unknown(" + code + ")";
        };
    }

    /** 把 SQLite 版本号格式化为 X.Y.Z。 */
    private static String formatSqliteVersion(int n) {
        if (n <= 0) return "unknown";
        int major = n / 1000000;
        int minor = (n / 1000) % 1000;
        int patch = n % 1000;
        return major + "." + minor + "." + patch;
    }

    /** CREATE 之后可能出现、且不表示对象类型的限定词。 */
    private static final Set<String> QUALIFIERS =
            Set.of("UNIQUE", "TEMP", "TEMPORARY", "VIRTUAL", "IF", "NOT", "EXISTS");

    /**
     * 取出 {@code CREATE} 之后的对象类型。
     *
     * <p>需跳过限定词：{@code CREATE UNIQUE INDEX}、{@code CREATE TEMP TABLE}、
     * {@code CREATE TABLE IF NOT EXISTS} 等。
     *
     * @param s 已去除 {@code CREATE} 的语句片段
     * @return 大写的对象类型；无法识别时为空串
     */
    private static String objectTypeOf(String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            int start = i;
            while (i < n && Character.isLetter(s.charAt(i))) i++;
            if (i == start) break;
            String word = s.substring(start, i);
            if (!QUALIFIERS.contains(word.toUpperCase(java.util.Locale.ROOT))) {
                return word.toUpperCase(java.util.Locale.ROOT);
            }
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        }
        return "";
    }

    /**
     * 扫描首页中的 {@code sqlite_master} 建表语句，统计各类 schema 对象。
     *
     * <p>只做文本匹配，不解析 B-tree 结构——对统计而言足够，且避免引入 SQLite 依赖。
     */
    private static SchemaCounts countSchemaObjects(Path path, byte[] head) {
        int tables = 0;
        int indexes = 0;
        int views = 0;
        int triggers = 0;
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            int pageSize = pageSize(head);
            long fileSize = raf.length();
            if (pageSize <= HEADER_SIZE) {
                return new SchemaCounts(0, 0, 0, 0);
            }
            // 读取第 1 页（偏移 0）以及紧随其后的若干页，覆盖常见的小型库
            int pagesToRead = (int) Math.min(8, fileSize / pageSize);
            byte[] buf = new byte[pageSize * pagesToRead];
            raf.seek(0);
            raf.readFully(buf, 0, Math.min(buf.length, (int) fileSize));

            String text = new String(buf, StandardCharsets.UTF_8);
            for (String stmt : text.split("(?i)\\bCREATE\\b")) {
                switch (objectTypeOf(stmt.stripLeading())) {
                    case "TABLE" -> tables++;
                    case "INDEX" -> indexes++;
                    case "VIEW" -> views++;
                    case "TRIGGER" -> triggers++;
                    default -> {
                        // 非 CREATE DDL（如 INSERT），忽略
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 尽力而为：读不到就返回 0
        }
        return new SchemaCounts(tables, indexes, views, triggers);
    }

    private static int beInt(byte[] h, int off) {
        if (off + 4 > h.length) return 0;
        return ((h[off] & 0xFF) << 24) | ((h[off + 1] & 0xFF) << 16)
                | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
    }

    private static int beUnsignedByte(byte[] h, int off) {
        return off < h.length ? (h[off] & 0xFF) : 0;
    }
}

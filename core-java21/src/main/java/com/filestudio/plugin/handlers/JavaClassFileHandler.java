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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 字节码（.class）处理器：提取编译版本、类名、修饰符与成员数量。
 *
 * <p>VIEW_ONLY 能力——字节码不可作为文本编辑。
 *
 * <p>实现依据 JVMS 第 4 章 ClassFile 结构：
 * <pre>
 *   偏移    长度  含义
 *    0       4   魔数 0xCAFEBABE
 *    4       2   minor_version
 *    6       2   major_version
 *    8       2   constant_pool_count
 *   10      ...  constant_pool[constant_pool_count-1]
 *   +pool     2   access_flags
 *   +pool+2   2   this_class（常量池索引）
 *   +pool+4   2   super_class（常量池索引）
 *   +pool+6   2   interfaces_count
 *   +pool+8   2   fields_count
 *   +pool+10  2   methods_count
 *   +pool+12  2   attributes_count
 * </pre>
 *
 * <p>注意：{@code access_flags} 等字段位于常量池<b>之后</b>，因此必须先遍历常量池
 * 才能定位它们。
 */
public class JavaClassFileHandler implements FileHandler {

    /**
     * Java 字节码处理器。
     */
    public JavaClassFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("class");

    private static final int MAGIC = 0xCAFEBABE;

    /** 读取前若干字节用于解析类名等需要越过常量池的字段。 */
    private static final int MAX_READ_BYTES = 256 * 1024;

    @Override
    public String getExtension() {
        return "class";
    }

    @Override
    public String getMimeType() {
        return "application/java-vm";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Java class file (bytecode)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isClassFile(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Class file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] buf = readPrefix(path, MAX_READ_BYTES);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "class");
        meta.put("viewOnly", true);

        if (buf.length < 10 || beInt(buf, 0) != MAGIC) {
            meta.put("valid", false);
            meta.put("error", "Missing or truncated class file magic");
            return new Document(path, getMimeType(), getExtension(), "", meta,
                    file.length(), getEditCapability());
        }

        meta.put("valid", true);
        int minor = beUnsignedShort(buf, 4);
        int major = beUnsignedShort(buf, 6);
        meta.put("minorVersion", minor);
        meta.put("majorVersion", major);
        meta.put("javaVersion", javaVersionName(major));
        meta.put("classFormatVersion", major + "." + minor);

        int constantPoolCount = beUnsignedShort(buf, 8);
        meta.put("constantPoolCount", constantPoolCount);

        // 遍历常量池得到其结束位置——access_flags 等固定字段紧随常量池之后
        ConstantPool cp = ConstantPool.parse(buf, constantPoolCount);
        int fixed = cp.endOffset();

        if (fixed + 14 <= buf.length) {
            int accessFlags = beUnsignedShort(buf, fixed);
            meta.put("accessFlags", accessFlags);
            meta.put("modifiers", describeAccessFlags(accessFlags));
            meta.put("isInterface", (accessFlags & 0x0200) != 0);
            meta.put("isAbstract", (accessFlags & 0x0400) != 0);
            meta.put("isEnum", (accessFlags & 0x4000) != 0);
            meta.put("isAnnotation", (accessFlags & 0x2000) != 0);
            meta.put("interfaceCount", beUnsignedShort(buf, fixed + 6));
            meta.put("fieldCount", beUnsignedShort(buf, fixed + 8));
            meta.put("methodCount", beUnsignedShort(buf, fixed + 10));
            meta.put("attributeCount", beUnsignedShort(buf, fixed + 12));

            // this_class / super_class 指向常量池中的 Class 项
            String thisName = cp.className(beUnsignedShort(buf, fixed + 2));
            if (thisName != null) {
                meta.put("className", thisName);
            }
            String superName = cp.className(beUnsignedShort(buf, fixed + 4));
            if (superName != null) {
                meta.put("superClassName", superName);
            }
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Class files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] buf = readPrefix(file.toPath(), 64);
            if (buf.length >= 8 && beInt(buf, 0) == MAGIC) {
                int major = beUnsignedShort(buf, 6);
                meta.put("javaVersion", javaVersionName(major));
            }
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    /**
     * 常量池的最小解析：只记录 CONSTANT_Class 项指向的 name_index，
     * 以及 CONSTANT_Utf8 项的文本，用于还原类名。
     */
    private static final class ConstantPool {

        private final List<Integer> classNameIndex = new ArrayList<>();
        private final List<String> utf8 = new ArrayList<>();
        private int end;

        /**
         * 遍历常量池。
         *
         * <p>每个常量都必须在两个列表中各占一槽，否则索引会错位；
         * {@code Long}/{@code Double} 额外占用一个不可用槽位。
         *
         * @param b     类文件字节
         * @param count constant_pool_count
         * @return 解析结果，{@link #endOffset()} 给出常量池结束位置
         */
        static ConstantPool parse(byte[] b, int count) {
            ConstantPool cp = new ConstantPool();
            // 常量池索引从 1 开始，第 0 项为占位
            cp.classNameIndex.add(-1);
            cp.utf8.add(null);

            int pos = 10;
            for (int i = 1; i < count; i++) {
                if (pos >= b.length) break;
                int tag = b[pos] & 0xFF;
                cp.classNameIndex.add(-1);
                cp.utf8.add(null);

                switch (tag) {
                    case 1 -> { // Utf8
                        if (pos + 3 > b.length) return cp.done(pos);
                        int len = beUnsignedShort(b, pos + 1);
                        if (pos + 3 + len > b.length) return cp.done(pos);
                        cp.utf8.set(i, new String(b, pos + 3, len, java.nio.charset.StandardCharsets.UTF_8));
                        pos += 3 + len;
                    }
                    case 7, 8, 16, 19, 20 -> { // Class / String / MethodType / Module / Package
                        if (pos + 3 > b.length) return cp.done(pos);
                        cp.classNameIndex.set(i, beUnsignedShort(b, pos + 1));
                        pos += 3;
                    }
                    case 15 -> { // MethodHandle
                        if (pos + 4 > b.length) return cp.done(pos);
                        pos += 4;
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> { // Integer / Float / *ref / NameAndType / Dynamic
                        if (pos + 5 > b.length) return cp.done(pos);
                        pos += 5;
                    }
                    case 5, 6 -> { // Long / Double：占两个常量池槽位
                        if (pos + 9 > b.length) return cp.done(pos);
                        pos += 9;
                        i++;
                        if (i < count) {
                            cp.classNameIndex.add(-1);
                            cp.utf8.add(null);
                        }
                    }
                    default -> {
                        return cp.done(pos); // 未知 tag，提前结束
                    }
                }
            }
            return cp.done(pos);
        }

        private ConstantPool done(int endOffset) {
            this.end = endOffset;
            return this;
        }

        /** 常量池结束位置，其后即为 access_flags。 */
        int endOffset() {
            return end;
        }

        /** 取常量池索引对应的类名；索引不是 Class 项时返回 {@code null}。 */
        String className(int index) {
            if (index <= 0 || index >= classNameIndex.size()) return null;
            int nameIndex = classNameIndex.get(index);
            if (nameIndex <= 0 || nameIndex >= utf8.size()) return null;
            return utf8.get(nameIndex);
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isClassFile(File file) {
        try {
            byte[] head = readPrefix(file.toPath(), 4);
            return head.length == 4 && beInt(head, 0) == MAGIC;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readPrefix(Path path, int len) {
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
            throw new FileStudioException("Failed reading class file: " + path, e);
        }
    }

    /** 将 major_version 映射为对应的 Java 发行版名称。 */
    static String javaVersionName(int major) {
        return switch (major) {
            case 45 -> "1.1";
            case 46 -> "1.2";
            case 47 -> "1.3";
            case 48 -> "1.4";
            case 49 -> "5";
            case 50 -> "6";
            case 51 -> "7";
            case 52 -> "8";
            case 53 -> "9";
            case 54 -> "10";
            case 55 -> "11";
            case 56 -> "12";
            case 57 -> "13";
            case 58 -> "14";
            case 59 -> "15";
            case 60 -> "16";
            case 61 -> "17";
            case 62 -> "18";
            case 63 -> "19";
            case 64 -> "20";
            case 65 -> "21";
            case 66 -> "22";
            case 67 -> "23";
            case 68 -> "24";
            case 69 -> "25";
            case 70 -> "26";
            default -> "unknown(major=" + major + ")";
        };
    }

    private static String describeAccessFlags(int flags) {
        List<String> names = new ArrayList<>();
        if ((flags & 0x0001) != 0) names.add("public");
        if ((flags & 0x0010) != 0) names.add("final");
        if ((flags & 0x0020) != 0) names.add("super");
        if ((flags & 0x0200) != 0) names.add("interface");
        if ((flags & 0x0400) != 0) names.add("abstract");
        if ((flags & 0x1000) != 0) names.add("synthetic");
        if ((flags & 0x2000) != 0) names.add("annotation");
        if ((flags & 0x4000) != 0) names.add("enum");
        return names.isEmpty() ? "package-private" : String.join(" ", names);
    }

    private static int beInt(byte[] b, int off) {
        if (off + 4 > b.length) return 0;
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int beUnsignedShort(byte[] b, int off) {
        if (off + 2 > b.length) return 0;
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}

package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FBX（Autodesk Filmbox）处理器：识别二进制与 ASCII 两种编码，
 * 提取版本号、文件头扩展信息与顶层节点名。
 *
 * <p>VIEW_ONLY 能力——模型文件不作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>二进制 FBX：文件头固定为 {@code Kaydara FBX Binary  \x00\x1a\x00}，
 *       随后 4 字节版本号（如 7400 = 7.4.0）；节点用递归记录编码。</li>
 *   <li>ASCII FBX：以分号注释的 {@code FBXHeaderExtension:} 节点开头，
 *       后续节点形如 {@code NodeName:  {  ...  }}。</li>
 * </ul>
 */
public class FbxFileHandler implements FileHandler {

    private static final Set<String> SUPPORTED = Set.of("fbx");

    /** 二进制 FBX 的魔数前缀（含尾部空格与三字节控制符）。 */
    private static final byte[] BINARY_MAGIC =
            "Kaydara FBX Binary  ".getBytes(StandardCharsets.US_ASCII);

    /** ASCII FBX 的版本行（第三个分号注释行是版本）。 */
    private static final Pattern ASCII_VERSION = Pattern.compile("FBX\\s+([0-9.]+)");

    /** 顶层节点：行首 `Name:  {`（FBX 顶层节点顶格，无缩进）。 */
    private static final Pattern ASCII_TOP_NODE = Pattern.compile(
            "(?m)^[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*\\{");

    private static final int MAX_HEADER_BYTES = 4096;

    @Override
    public String getExtension() {
        return "fbx";
    }

    @Override
    public String getMimeType() {
        return "model/fbx";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Autodesk FBX model (binary or ASCII)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isFbx(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("FBX file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "fbx");
        meta.put("viewOnly", true);

        byte[] head = readHead(path, MAX_HEADER_BYTES);
        if (isBinaryMagic(head)) {
            meta.put("encoding", "binary");
            meta.put("version", binaryVersion(head));
            meta.put("magic", "Kaydara FBX Binary");
        } else {
            String ascii = new String(head, StandardCharsets.UTF_8);
            meta.put("encoding", "ascii");
            Matcher vm = ASCII_VERSION.matcher(ascii);
            if (vm.find()) {
                meta.put("version", vm.group(1));
            }
            int nodes = 0;
            Matcher nm = ASCII_TOP_NODE.matcher(ascii);
            while (nm.find()) {
                nodes++;
            }
            meta.put("topLevelNodeCount", nodes);
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("FBX files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", "fbx");
            meta.put("encoding", doc.getMetadata().get("encoding"));
            meta.put("version", doc.getMetadata().get("version"));
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

    static boolean isFbx(File file) {
        byte[] head = readHead(file.toPath(), MAX_HEADER_BYTES);
        if (isBinaryMagic(head)) {
            return true;
        }
        // ASCII FBX：前几行分号注释后是 FBXHeaderExtension:，头部应含 "FBX"。
        String ascii = new String(head, StandardCharsets.UTF_8);
        return ascii.contains("FBXHeaderExtension:")
                || ASCII_VERSION.matcher(ascii).find();
    }

    private static boolean isBinaryMagic(byte[] head) {
        if (head.length < BINARY_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < BINARY_MAGIC.length; i++) {
            if (head[i] != BINARY_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** 二进制版本是魔数后的 4 字节有符号整数，如 7400 → 7.4.0。 */
    private static String binaryVersion(byte[] head) {
        int off = BINARY_MAGIC.length;
        if (off + 4 > head.length) {
            return null;
        }
        int v = ((head[off] & 0xFF) << 24) | ((head[off + 1] & 0xFF) << 16)
                | ((head[off + 2] & 0xFF) << 8) | (head[off + 3] & 0xFF);
        if (v <= 0) {
            return null;
        }
        int major = v / 1000;
        int minor = (v % 1000) / 100;
        int revision = v % 100;
        return major + "." + minor + "." + revision;
    }

    private static byte[] readHead(Path path, int len) {
        byte[] buf = new byte[len];
        try (InputStream in = Files.newInputStream(path)) {
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
            throw new FileStudioException("Failed reading FBX header: " + path, e);
        }
    }
}
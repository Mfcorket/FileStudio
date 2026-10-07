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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB 电子书处理器：提取书名、作者、语言、出版社与章节数等元数据。
 *
 * <p>PARTIAL 能力——可解析元数据与目录结构，但不做正文渲染。
 *
 * <p>EPUB 本质是 ZIP，关键文件：
 * <ul>
 *   <li>{@code mimetype} —— 内容须为 {@code application/epub+zip}</li>
 *   <li>{@code META-INF/container.xml} —— 指向 OPF 包的 {@code full-path}</li>
 *   <li>{@code *.opf} —— 包文档，含 {@code metadata / manifest / spine}</li>
 * </ul>
 *
 * <p>规范要求 OPF 使用 XML；本实现不引入 XML 解析器，改用受限的文本提取
 * （只取元素文本内容并做实体解码），足以覆盖各生成器的常见输出。
 */
public class EpubFileHandler implements FileHandler {

    /**
     * EPUB 电子书处理器。
     */
    public EpubFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("epub");

    private static final String MIMETYPE_CONTENT = "application/epub+zip";

    /** container.xml 中 rootfile 的路径属性。 */
    private static final Pattern ROOTFILE = Pattern.compile(
            "full-path\\s*=\\s*[\"']([^\"']+)[\"']");

    /** 读取单个条目的大小上限，避免恶意压缩包。 */
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;

    /** 最多列出的章节文件名数。 */
    private static final int MAX_CHAPTERS = 40;

    @Override
    public String getExtension() {
        return "epub";
    }

    @Override
    public String getMimeType() {
        return "application/epub+zip";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "EPUB electronic book";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isEpub(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("EPUB file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "epub");
        meta.put("viewOnly", true);

        try (ZipFile zip = new ZipFile(file)) {
            meta.put("valid", true);
            meta.put("entryCount", zip.size());

            String mimetype = readText(zip, "mimetype", 64);
            if (mimetype != null) {
                meta.put("mimetype", mimetype.strip());
            }

            // 1) container.xml → OPF 路径
            String container = readText(zip, "META-INF/container.xml", MAX_ENTRY_BYTES);
            String opfPath = null;
            if (container != null) {
                Matcher m = ROOTFILE.matcher(container);
                if (m.find()) {
                    opfPath = m.group(1);
                }
            }
            meta.put("opfPath", opfPath);
            if (opfPath == null) {
                meta.put("error", "META-INF/container.xml has no rootfile full-path");
                return new Document(path, getMimeType(), getExtension(), "", meta,
                        file.length(), getEditCapability());
            }

            // 2) 解析 OPF
            String opf = readText(zip, opfPath, MAX_ENTRY_BYTES);
            if (opf == null) {
                meta.put("error", "Cannot read OPF package document: " + opfPath);
                return new Document(path, getMimeType(), getExtension(), "", meta,
                        file.length(), getEditCapability());
            }

            putIfPresent(meta, "title", elementText(opf, "title"));
            putIfPresent(meta, "creator", elementText(opf, "creator"));
            putIfPresent(meta, "language", elementText(opf, "language"));
            putIfPresent(meta, "publisher", elementText(opf, "publisher"));
            putIfPresent(meta, "date", elementText(opf, "date"));
            putIfPresent(meta, "identifier", elementText(opf, "identifier"));
            putIfPresent(meta, "description", elementText(opf, "description"));
            putIfPresent(meta, "rights", elementText(opf, "rights"));

            meta.put("opfVersion", attribute(opf, "package", "version"));
            meta.put("manifestItemCount", countElements(opf, "item"));
            int spine = countElements(opf, "itemref");
            meta.put("spineItemCount", spine);
            meta.put("chapterCount", spine);
            meta.put("hasToc", opf.contains("toc=\"ncx\"") || opf.contains("properties=\"nav\""));

            List<String> chapters = listChapterFiles(opf);
            if (!chapters.isEmpty()) {
                meta.put("chapters", chapters);
            }

            // 图片资源数量，便于判断是否为图文书
            int images = 0;
            for (var e : java.util.Collections.list(zip.entries())) {
                if (e.getName().matches("(?i).*\\.(jpg|jpeg|png|gif|svg|webp)$")) {
                    images++;
                }
            }
            meta.put("imageCount", images);

        } catch (IOException e) {
            meta.put("valid", false);
            meta.put("error", "Failed to read EPUB: " + e.getMessage());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("EPUB files are read-only; use a dedicated reader");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("title", doc.getMetadata().get("title"));
            meta.put("creator", doc.getMetadata().get("creator"));
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

    private static void putIfPresent(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.isEmpty()) {
            meta.put(key, value);
        }
    }

    /**
     * 判断是否为 EPUB。
     *
     * <p>以 ZIP 内 {@code mimetype} 条目为准。文件确实存在但不是 EPUB 时直接拒绝，
     * 不退回扩展名——否则任何名为 *.epub 的文本文件都会被误判。
     */
    private static boolean isEpub(File file) {
        if (!isZipSignature(file)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry e = zip.getEntry("mimetype");
            if (e == null) {
                return false;
            }
            String s = readText(zip, "mimetype", 64);
            return s != null && s.strip().equals(MIMETYPE_CONTENT);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isZipSignature(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = new byte[4];
            int n = in.read(head);
            if (n < 4) return false;
            return head[0] == 'P' && head[1] == 'K'
                    && (head[2] == 0x03 || head[2] == 0x05 || head[2] == 0x07);
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取条目为 UTF-8 文本；不存在或过大时返回 {@code null}。 */
    private static String readText(ZipFile zip, String name, int limit) {
        ZipEntry e = zip.getEntry(name);
        if (e == null || e.isDirectory()) {
            return null;
        }
        if (e.getSize() > limit && e.getSize() > 0) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            byte[] buf = in.readNBytes(limit);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 去掉 XML 注释，避免注释里的同名元素被误当作真实内容。 */
    static String stripComments(String xml) {
        if (!xml.contains("<!--")) {
            return xml;
        }
        StringBuilder sb = new StringBuilder(xml.length());
        int i = 0;
        while (i < xml.length()) {
            int start = xml.indexOf("<!--", i);
            if (start < 0) {
                sb.append(xml, i, xml.length());
                break;
            }
            sb.append(xml, i, start);
            int end = xml.indexOf("-->", start);
            if (end < 0) {
                // 未闭合的注释，其后内容整体丢弃
                break;
            }
            i = end + 3;
        }
        return sb.toString();
    }

    /**
     * 提取元素的文本内容，兼容带命名空间前缀的形式（如 {@code <dc:title>}）。
     *
     * @return 实体解码后的文本；元素不存在或自闭合时返回 {@code null}
     */
    static String elementText(String xml, String localName) {
        xml = stripComments(xml);
        int open = findTagStart(xml, localName);
        if (open < 0) {
            return null;
        }
        int gt = xml.indexOf('>', open);
        if (gt < 0) {
            return null;
        }
        if (xml.charAt(gt - 1) == '/') {
            return null; // 自闭合元素无内容
        }
        String name = tagNameAt(xml, open);
        int close = indexOfCloseTag(xml, name, gt + 1);
        if (close < 0) {
            return null;
        }
        return decodeEntities(xml.substring(gt + 1, close)).strip();
    }

    /** 读取元素上的某个属性值，如 {@code <package version="3.0">}。 */
    static String attribute(String xml, String localName, String attr) {
        xml = stripComments(xml);
        int open = findTagStart(xml, localName);
        if (open < 0) {
            return null;
        }
        int gt = xml.indexOf('>', open);
        if (gt < 0) {
            return null;
        }
        String tag = xml.substring(open, gt);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(attr) + "\\s*=\\s*[\"']([^\"']*)[\"']")
                .matcher(tag);
        return m.find() ? decodeEntities(m.group(1)) : null;
    }

    /** 统计某类元素出现次数，需排除同前缀的其他元素（如 {@code item} 不计 {@code itemref}）。 */
    static int countElements(String xml, String localName) {
        Matcher m = Pattern.compile("<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "(?=[\\s/>])")
                .matcher(xml);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 提取 manifest 中 XHTML/HTML 文档的 href。 */
    private static List<String> listChapterFiles(String opf) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "<(?:[\\w.-]+:)?item\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"']").matcher(opf);
        while (m.find() && out.size() < MAX_CHAPTERS) {
            String href = m.group(1);
            if (href.matches("(?i).*\\.(xhtml|html|htm)$")) {
                out.add(decodeEntities(href));
            }
        }
        return out;
    }

    /** 找到 {@code <name} 或 {@code <prefix:name} 的起始位置。 */
    private static int findTagStart(String xml, String localName) {
        Matcher m = Pattern.compile("<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "(?=[\\s/>])")
                .matcher(xml);
        return m.find() ? m.start() : -1;
    }

    /** 取起始标签的名字（含前缀）。 */
    private static String tagNameAt(String xml, int open) {
        int i = open + 1;
        int end = i;
        while (end < xml.length() && (Character.isLetterOrDigit(xml.charAt(end))
                || xml.charAt(end) == ':' || xml.charAt(end) == '-' || xml.charAt(end) == '_')) {
            end++;
        }
        return xml.substring(i, end);
    }

    /** 查找匹配的结束标签位置（内容已在调用前去除注释）。 */
    private static int indexOfCloseTag(String xml, String name, int from) {
        int candidate = xml.indexOf("</" + name, from);
        if (candidate < 0) {
            return -1;
        }
        if (xml.indexOf('>', candidate) < 0) {
            return -1;
        }
        return candidate;
    }

    /** 解码 XML 常用实体。 */
    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");   // 必须最后解码
    }
}

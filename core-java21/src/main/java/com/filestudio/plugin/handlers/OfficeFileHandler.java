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
 * Office 文档处理器：docx / xlsx / pptx / odt。
 *
 * <p>PARTIAL 能力——解析文档属性与结构概览，不做正文渲染。
 *
 * <p>现代 Office 文档均是 ZIP 容器：
 * <ul>
 *   <li>{@code docx} 以 {@code word/document.xml} 为内容主干，属性在 {@code docProps/core.xml} 与
 *       {@code docProps/app.xml}（页数/字数均可从 app.xml 直接读取）</li>
 *   <li>{@code xlsx} 以 {@code xl/workbook.xml} 为工作簿（含 sheet 名称），属性同上</li>
 *   <li>{@code pptx} 以 {@code ppt/presentation.xml} 为演示区（含 slideId 数量），属性同上</li>
 *   <li>{@code odt} 是 OASIS OpenDocument，元数据集中在 {@code meta.xml}，正文在 {@code content.xml}</li>
 * </ul>
 *
 * <p>与 EPUB 类似，本实现不引入 XML 解析器，复用 {@link EpubFileHandler} 受限的文本提取
 * （元素文本 + 属性 + 实体解码），足以覆盖常见生成器的输出。
 */
public class OfficeFileHandler implements FileHandler {

    /** 本处理器覆盖的扩展名。getExtension() 返回主扩展名 docx。 */
    private static final Set<String> SUPPORTED = Set.of("docx", "xlsx", "pptx", "odt");

    /** 读取单个条目的大小上限，避免恶意压缩包。 */
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;

    private static final String ODT_MIMETYPE_PREFIX = "application/vnd.oasis.opendocument";

    @Override
    public String getExtension() {
        return "docx";
    }

    @Override
    public String getMimeType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "Office document (Word/Excel/PowerPoint/OpenDocument)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (!SUPPORTED.contains(extensionOf(file.getName()))) {
            return false;
        }
        if (file.isFile()) {
            // 已有的文件必须真的带 OOXML/ODT 部件，防止任意 ZIP 改名被误收
            return isZipSignature(file) && containsOfficePart(file);
        }
        // 文件不存在时按路径 / 新建对话框判断
        return true;
    }

    /** 检查 ZIP 内是否有明确的 OOXML/ODT 内容标记。 */
    private static boolean containsOfficePart(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("word/document.xml") != null) return true;
            if (zip.getEntry("xl/workbook.xml") != null) return true;
            if (zip.getEntry("ppt/presentation.xml") != null) return true;
            ZipEntry mimetype = zip.getEntry("mimetype");
            if (mimetype != null) {
                String s = readText(zip, "mimetype", 64);
                return s != null && s.startsWith(ODT_MIMETYPE_PREFIX);
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Office file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        try (ZipFile zip = new ZipFile(file)) {
            meta.put("valid", true);
            meta.put("entryCount", zip.size());

            String type = detectType(zip);
            String format = effectiveFormat(type, file.getName());
            meta.put("format", format);
            meta.put("officeType", type);

            if (!SUPPORTED.contains(format)) {
                meta.put("error", "Not an OOXML/ODT package: " + fileName(file));
                return new Document(path, mimeFor(format), format, "", meta,
                        file.length(), getEditCapability());
            }

            if ("odt".equals(format)) {
                readOdtMeta(zip, meta);
            } else {
                readOoxmlProps(zip, meta);
            }
            readStructureStats(zip, meta, format);

            int images = 0;
            for (var e : java.util.Collections.list(zip.entries())) {
                if (e.getName().matches("(?i).*\\.(jpg|jpeg|png|gif|svg|webp|emf|wmf|avif)$")) {
                    images++;
                }
            }
            meta.put("imageCount", images);

        } catch (IOException e) {
            meta.put("valid", false);
            meta.put("error", "Failed to read office file: " + e.getMessage());
        }

        return new Document(path, mimeFor(String.valueOf(meta.getOrDefault("format", "docx"))),
                String.valueOf(meta.getOrDefault("format", "docx")), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Office files are read-only; use a dedicated reader");
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
            meta.put("officeType", doc.getMetadata().get("officeType"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static String fileName(File file) {
        return file == null ? "" : file.getName();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
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

    /**
     * 按 ZIP 内的内容判别文档类型，而不是只看扩展名——改名伪装的文件会现形。
     * 返回 docx / xlsx / pptx / odt，识别不出时回退到扩展名。
     */
    private static String detectType(ZipFile zip) {
        if (zip.getEntry("word/document.xml") != null) return "docx";
        if (zip.getEntry("xl/workbook.xml") != null) return "xlsx";
        if (zip.getEntry("ppt/presentation.xml") != null) return "pptx";
        ZipEntry mimetype = zip.getEntry("mimetype");
        if (mimetype != null) {
            String s = readText(zip, "mimetype", 64);
            if (s != null && s.startsWith(ODT_MIMETYPE_PREFIX)) return "odt";
        }
        return "";
    }

    /** 内容判别 > 扩展名回退：没有内容标记但扩展名属于支持集时依然按扩展名处理。 */
    private static String effectiveFormat(String detected, String fileName) {
        if (SUPPORTED.contains(detected)) return detected;
        String ext = extensionOf(fileName);
        return SUPPORTED.contains(ext) ? ext : "";
    }

    private static String mimeFor(String format) {
        return switch (format) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odt" -> "application/vnd.oasis.opendocument.text";
            default -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
    }

    /** OOXML 的 docProps/core.xml + docProps/app.xml 属性。 */
    private static void readOoxmlProps(ZipFile zip, Map<String, Object> meta) {
        String core = readText(zip, "docProps/core.xml", MAX_ENTRY_BYTES);
        if (core != null) {
            put(meta, "title", EpubFileHandler.elementText(core, "title"));
            put(meta, "creator", EpubFileHandler.elementText(core, "creator"));
            put(meta, "lastModifiedBy", EpubFileHandler.elementText(core, "lastModifiedBy"));
            put(meta, "subject", EpubFileHandler.elementText(core, "subject"));
            put(meta, "description", EpubFileHandler.elementText(core, "description"));
            put(meta, "keywords", EpubFileHandler.elementText(core, "keywords"));
            put(meta, "category", EpubFileHandler.elementText(core, "category"));
            put(meta, "revision", EpubFileHandler.elementText(core, "revision"));
            put(meta, "created", EpubFileHandler.elementText(core, "created"));
            put(meta, "modified", EpubFileHandler.elementText(core, "modified"));
        }
        String app = readText(zip, "docProps/app.xml", MAX_ENTRY_BYTES);
        if (app != null) {
            put(meta, "application", EpubFileHandler.elementText(app, "Application"));
            put(meta, "company", EpubFileHandler.elementText(app, "Company"));
            put(meta, "appVersion", EpubFileHandler.elementText(app, "AppVersion"));
            put(meta, "totalEditTime", EpubFileHandler.elementText(app, "TotalTime"));
            String format = String.valueOf(meta.get("format"));
            switch (format) {
                case "docx" -> {
                    put(meta, "pages", EpubFileHandler.elementText(app, "Pages"));
                    put(meta, "words", EpubFileHandler.elementText(app, "Words"));
                    put(meta, "characters", EpubFileHandler.elementText(app, "Characters"));
                    put(meta, "paragraphs", EpubFileHandler.elementText(app, "Paragraphs"));
                    put(meta, "lines", EpubFileHandler.elementText(app, "Lines"));
                }
                case "xlsx" -> put(meta, "sheetCount", EpubFileHandler.elementText(app, "Sheets"));
                case "pptx" -> put(meta, "slideCount", EpubFileHandler.elementText(app, "Slides"));
                default -> { }
            }
        }
    }

    /** OpenDocument 的元数据集中在 meta.xml。 */
    private static void readOdtMeta(ZipFile zip, Map<String, Object> meta) {
        String m = readText(zip, "meta.xml", MAX_ENTRY_BYTES);
        if (m == null) {
            meta.put("error", "meta.xml not readable");
            return;
        }
        put(meta, "title", EpubFileHandler.elementText(m, "title"));
        put(meta, "creator", EpubFileHandler.elementText(m, "creator"));
        put(meta, "initialCreator", EpubFileHandler.elementText(m, "initial-creator"));
        put(meta, "subject", EpubFileHandler.elementText(m, "subject"));
        put(meta, "description", EpubFileHandler.elementText(m, "description"));
        put(meta, "keywords", EpubFileHandler.elementText(m, "keywords"));
        put(meta, "created", EpubFileHandler.elementText(m, "creation-date"));
        put(meta, "modified", EpubFileHandler.elementText(m, "date"));
        put(meta, "generator", EpubFileHandler.elementText(m, "generator"));
        put(meta, "pageCount", EpubFileHandler.elementText(m, "page-count"));
    }

    /** 读取与格式相关的正文结构统计（段落/表格/幻灯片/工作表名）。 */
    private static void readStructureStats(ZipFile zip, Map<String, Object> meta, String format) {
        switch (format) {
            case "docx" -> {
                String doc = readText(zip, "word/document.xml", MAX_ENTRY_BYTES);
                if (doc != null) {
                    meta.put("paragraphCount", EpubFileHandler.countElements(doc, "p"));
                    meta.put("tableCount", EpubFileHandler.countElements(doc, "tbl"));
                }
            }
            case "xlsx" -> {
                String wb = readText(zip, "xl/workbook.xml", MAX_ENTRY_BYTES);
                if (wb != null) {
                    List<String> sheets = allAttributeValues(wb, "sheet", "name");
                    if (!sheets.isEmpty()) {
                        meta.put("sheetNames", sheets);
                        meta.put("sheetCount", sheets.size());
                    }
                }
            }
            case "pptx" -> {
                String pres = readText(zip, "ppt/presentation.xml", MAX_ENTRY_BYTES);
                if (pres != null) {
                    int slides = EpubFileHandler.countElements(pres, "sldId");
                    if (slides > 0) {
                        meta.put("slideCount", slides);
                    }
                }
            }
            default -> { }
        }
    }

    /** 提取某类元素上指定属性的全部值，如 workbook.xml 里的 sheet name。 */
    private static List<String> allAttributeValues(String xml, String localName, String attr) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "<(?:[\\w.-]+:)?\\b" + Pattern.quote(localName) + "\\b[^>]*\\b"
                        + Pattern.quote(attr) + "\\s*=\\s*[\"']([^\"']*)[\"']")
                .matcher(xml);
        while (m.find()) {
            out.add(EpubFileHandler.decodeEntities(m.group(1)));
        }
        return out;
    }

    private static void put(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.isEmpty()) {
            meta.put(key, value);
        }
    }

    /** 读取条目为 UTF-8 文本；不存在、目录或过大时返回 {@code null}。 */
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
}
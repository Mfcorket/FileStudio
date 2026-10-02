package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 文档处理器：提取页数、标题、作者、页面尺寸等元数据。
 *
 * <p>PARTIAL 能力——可浏览元数据，但不作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>页数：统计 {@code /Type /Page} 出现次数（排除 {@code /Pages}）</li>
 *   <li>元数据：从 Info 字典提取标题、作者、创建日期、生产者</li>
 *   <li>页面尺寸：提取第一个 {@code /MediaBox}</li>
 * </ul>
 */
public class PdfFileHandler implements FileHandler {

    /**
     * PDF 文档处理器。
     */
    public PdfFileHandler() {}

    private static final int MAX_SCAN_BYTES = 1024 * 1024; // 扫描前 1MB

    private static final Pattern PAGE_PATTERN = Pattern.compile("/Type\\s*/Page(?!s)");
    private static final Pattern MEDIA_BOX_PATTERN = Pattern.compile("/MediaBox\\s*\\[([^\\]]+)\\]");
    private static final Pattern TITLE_PATTERN = Pattern.compile("/Title\\s*\\(([^)]+)\\)");
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("/Author\\s*\\(([^)]+)\\)");
    private static final Pattern CREATION_DATE_PATTERN = Pattern.compile("/CreationDate\\s*\\(([^)]+)\\)");
    private static final Pattern PRODUCER_PATTERN = Pattern.compile("/Producer\\s*\\(([^)]+)\\)");

    @Override
    public String getExtension() {
        return "pdf";
    }

    @Override
    public String getMimeType() {
        return "application/pdf";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "PDF document";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            // 文件存在：按魔数校验内容
            return isPdf(file);
        }
        // 文件不存在（假设路径）：按扩展名
        return extensionOf(file.getName()).equals("pdf");
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("PDF file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] content = readContent(path);
        String text = new String(content, StandardCharsets.ISO_8859_1);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", "pdf");
        meta.put("viewOnly", true);

        // 页数
        int pageCount = countPages(text);
        if (pageCount > 0) {
            meta.put("pageCount", pageCount);
        }

        // 元数据
        String title = extractField(text, TITLE_PATTERN);
        if (title != null) meta.put("title", title);
        String author = extractField(text, AUTHOR_PATTERN);
        if (author != null) meta.put("author", author);
        String creationDate = extractField(text, CREATION_DATE_PATTERN);
        if (creationDate != null) meta.put("creationDate", creationDate);
        String producer = extractField(text, PRODUCER_PATTERN);
        if (producer != null) meta.put("producer", producer);

        // 页面尺寸
        String[] mediaBox = extractMediaBox(text);
        if (mediaBox != null) {
            meta.put("pageWidth", Double.parseDouble(mediaBox[2].trim()) - Double.parseDouble(mediaBox[0].trim()));
            meta.put("pageHeight", Double.parseDouble(mediaBox[3].trim()) - Double.parseDouble(mediaBox[1].trim()));
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("PDF files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] content = readContent(file.toPath());
            String text = new String(content, StandardCharsets.ISO_8859_1);
            int pageCount = countPages(text);
            if (pageCount > 0) meta.put("pageCount", pageCount);
            String title = extractField(text, TITLE_PATTERN);
            if (title != null) meta.put("title", title);
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

    private static boolean isPdf(File file) {
        try {
            byte[] head = new byte[5];
            try (var in = Files.newInputStream(file.toPath())) {
                int n = in.read(head);
                return n >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readContent(Path path) {
        try {
            long size = Files.size(path);
            int len = (int) Math.min(size, MAX_SCAN_BYTES);
            byte[] buf = new byte[len];
            try (var in = Files.newInputStream(path)) {
                int off = 0;
                while (off < len) {
                    int n = in.read(buf, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off < len) {
                    byte[] trimmed = new byte[off];
                    System.arraycopy(buf, 0, trimmed, 0, off);
                    return trimmed;
                }
            }
            return buf;
        } catch (Exception e) {
            throw new FileStudioException("Failed reading PDF: " + path, e);
        }
    }

    private static int countPages(String text) {
        int count = 0;
        Matcher m = PAGE_PATTERN.matcher(text);
        while (m.find()) count++;
        return count;
    }

    private static String extractField(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static String[] extractMediaBox(String text) {
        Matcher m = MEDIA_BOX_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).split("\\s+");
        }
        return null;
    }
}

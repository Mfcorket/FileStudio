package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 文档处理器：提取页数、标题、作者、页面尺寸与文本内容。
 *
 * <p>PARTIAL 能力——可浏览元数据与提取文本，但不作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>页数：统计 {@code /Type /Page} 出现次数（排除 {@code /Pages}）</li>
 *   <li>元数据：从 Info 字典提取标题、作者、创建日期、生产者</li>
 *   <li>页面尺寸：提取第一个 {@code /MediaBox}</li>
 *   <li>文本提取：解析内容流中的 Tj/TJ/'/" 等文本显示操作符</li>
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
    private static final Pattern SUBJECT_PATTERN = Pattern.compile("/Subject\\s*\\(([^)]+)\\)");
    private static final Pattern KEYWORDS_PATTERN = Pattern.compile("/Keywords\\s*\\(([^)]+)\\)");
    private static final Pattern MOD_DATE_PATTERN = Pattern.compile("/ModDate\\s*\\(([^)]+)\\)");
    private static final Pattern TRAPPED_PATTERN = Pattern.compile("/Trapped\\s*/(True|False|Unknown)");

    // 内容流相关模式
    private static final Pattern STREAM_PATTERN = Pattern.compile("stream\\s*([\\s\\S]*?)\\s*endstream");
    private static final Pattern CONTENTS_PATTERN = Pattern.compile("/Contents\\s+(\\d+\\s+\\d+\\s+R|\\d+\\s+\\d+\\s+obj|\\\\[[^\\]]+\\\\]|\\d+\\s+\\d+\\s+R)");

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
            return isPdf(file);
        }
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
        String subject = extractField(text, SUBJECT_PATTERN);
        if (subject != null) meta.put("subject", subject);
        String keywords = extractField(text, KEYWORDS_PATTERN);
        if (keywords != null) meta.put("keywords", keywords);
        String modDate = extractField(text, MOD_DATE_PATTERN);
        if (modDate != null) meta.put("modDate", modDate);
        String trapped = extractField(text, TRAPPED_PATTERN);
        if (trapped != null) meta.put("trapped", trapped);

        // 页面尺寸
        String[] mediaBox = extractMediaBox(text);
        if (mediaBox != null) {
            meta.put("pageWidth", Double.parseDouble(mediaBox[2].trim()) - Double.parseDouble(mediaBox[0].trim()));
            meta.put("pageHeight", Double.parseDouble(mediaBox[3].trim()) - Double.parseDouble(mediaBox[1].trim()));
        }

        // 提取文本内容
        String extractedText = extractText(text);
        if (extractedText != null && !extractedText.isEmpty()) {
            meta.put("hasText", true);
            // 截断过长的文本用于元数据，完整文本可通过单独方法获取
            meta.put("textPreview", extractedText.length() > 500 ? extractedText.substring(0, 500) + "..." : extractedText);
        }

        return new Document(path, getMimeType(), getExtension(), extractedText != null ? extractedText : "", meta,
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

    /**
     * 提取 PDF 文本内容（基础实现）。
     *
     * @param file PDF 文件
     * @return 提取的文本，失败返回空字符串
     */
    public static String extractTextContent(File file) {
        if (file == null || !file.isFile()) return "";
        try {
            Path path = file.toPath();
            byte[] content = readContent(path);
            String text = new String(content, StandardCharsets.ISO_8859_1);
            return extractText(text);
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractText(String pdfText) {
        StringBuilder result = new StringBuilder();

        // 1. 找到所有 stream...endstream 块
        Matcher streamMatcher = STREAM_PATTERN.matcher(pdfText);
        List<String> streams = new ArrayList<>();
        while (streamMatcher.find()) {
            streams.add(streamMatcher.group(1));
        }

        // 2. 从每个流中提取文本
        for (String stream : streams) {
            String streamText = extractTextFromStream(stream);
            if (streamText != null && !streamText.isEmpty()) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(streamText);
            }
        }

        return result.toString().trim();
    }

    /** 从内容流中提取文本（处理 Tj, TJ, ', " 操作符）。 */
    private static String extractTextFromStream(String stream) {
        StringBuilder text = new StringBuilder();

        // 处理字符串：括号字符串 ( ... ) 和 十六进制字符串 < ... >
        // PDF 字符串转义：\( \) \\ \n \r \t \b \f \xxx (八进制)
        // 简单处理：匹配括号字符串
        Pattern stringPattern = Pattern.compile("\\(([^\\\\]*(?:\\\\.[^\\\\]*)*)\\)");
        Matcher m = stringPattern.matcher(stream);
        while (m.find()) {
            String str = m.group(1);
            // 处理转义
            str = unescapePdfString(str);
            if (!str.isEmpty()) {
                if (text.length() > 0) text.append(' ');
                text.append(str);
            }
        }

        // 处理十六进制字符串 < ... >
        Pattern hexPattern = Pattern.compile("<([0-9A-Fa-f\\s]+)>");
        m = hexPattern.matcher(stream);
        while (m.find()) {
            String hex = m.group(1).replaceAll("\\s+", "");
            if (hex.length() % 2 == 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < hex.length(); i += 2) {
                    int code = Integer.parseInt(hex.substring(i, i + 2), 16);
                    if (code >= 32 && code < 127) {
                        sb.append((char) code);
                    } else if (code == 10 || code == 13 || code == 9) {
                        sb.append(' ');
                    }
                }
                String str = sb.toString().trim();
                if (!str.isEmpty()) {
                    if (text.length() > 0) text.append(' ');
                    text.append(str);
                }
            }
        }

        return text.toString().trim();
    }

    /** 解码 PDF 字符串转义序列。 */
    private static String unescapePdfString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'b' -> { sb.append('\b'); i++; }
                    case 'f' -> { sb.append('\f'); i++; }
                    case '(' -> { sb.append('('); i++; }
                    case ')' -> { sb.append(')'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> {
                        // 八进制：\ddd
                        if (next >= '0' && next <= '7') {
                            int val = next - '0';
                            int j = i + 2;
                            for (int k = 0; k < 2 && j < s.length(); k++, j++) {
                                char d = s.charAt(j);
                                if (d >= '0' && d <= '7') {
                                    val = val * 8 + (d - '0');
                                } else {
                                    break;
                                }
                            }
                            sb.append((char) val);
                            i = j - 1;
                        } else {
                            sb.append(next);
                            i++;
                        }
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
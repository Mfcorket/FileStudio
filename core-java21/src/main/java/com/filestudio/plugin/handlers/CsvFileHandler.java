package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;

/**
 * CSV / TSV 表格数据处理器。
 *
 * <p>元数据：行数、列数（取自表头）、推断的分隔符、首行字段名（最多 64 个）。
 * 分隔符推断基于表头中最常见的 {@code ,} {@code \t} {@code ;} 出现次数。
 * 不构建二维数组，仅统计，避免大文件内存开销。
 */
public class CsvFileHandler extends AbstractTextHandler {

    /**
     * CSV / TSV 分隔符表格处理器。
     */
    public CsvFileHandler() {}

    private static final int MAX_HEADER_FIELDS = 64;

    @Override
    public String getExtension() {
        return "csv";
    }

    @Override
    public String getMimeType() {
        return "text/csv";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "CSV table data";
    }

    @Override
    public boolean canHandle(java.io.File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        return ext.equals("csv") || ext.equals("tsv");
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;
        String[] lines = content.split("\n", -1);
        // 末尾换行符会产生一个空片段，不计为行
        int rowCount = lines.length;
        if (rowCount > 0 && lines[rowCount - 1].isEmpty()) {
            rowCount--;
        }
        String header = lines.length > 0 ? lines[0] : "";
        char delim = detectDelimiter(header);
        String[] fields = splitCsvLine(header, delim);
        meta.put("rowCount", rowCount);
        meta.put("columnCount", fields.length);
        meta.put("delimiter", delimiterName(delim));
        if (fields.length > 0 && fields.length <= MAX_HEADER_FIELDS) {
            meta.put("header", java.util.Arrays.asList(fields));
        }
    }

    /** 分隔符的可读名称，便于 UI 展示。 */
    static String delimiterName(char delim) {
        return switch (delim) {
            case '\t' -> "tab";
            case ';' -> "semicolon";
            case '|' -> "pipe";
            case ',' -> "comma";
            default -> String.valueOf(delim);
        };
    }

    /** 基于首行推断分隔符：取出现次数最多者；无匹配时用逗号。 */
    static char detectDelimiter(String line) {
        int comma = 0, tab = 0, semi = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ',') comma++;
            else if (c == '\t') tab++;
            else if (c == ';') semi++;
        }
        if (tab >= comma && tab >= semi && tab > 0) return '\t';
        if (semi > comma && semi > 0) return ';';
        return ',';
    }

    /** 简单 CSV 行切分：支持双引号包裹字段内的分隔符。 */
    static String[] splitCsvLine(String line, char delim) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delim) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }
}

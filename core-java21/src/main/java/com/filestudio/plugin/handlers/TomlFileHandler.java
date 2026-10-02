package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TOML 格式处理器。
 *
 * <p>元数据：表数量（{@code [section]}）、表数组数量（{@code [[section]]}）、键值对数量、
 * 已识别的表名（最多 32 个）。
 * 采用 TOML 的行级规则（{@code #} 注释、{@code [table]} 表头、{@code key = value} 条目），
 * 不处理多行字符串与内联表，仅用于结构概览。
 */
public class TomlFileHandler extends AbstractTextHandler {

    private static final int MAX_TABLE_NAMES = 32;
    private static final Pattern ARRAY_TABLE = Pattern.compile("^\\s*\\[\\[([^\\]]+)\\]\\]\\s*(?:#.*)?$");
    private static final Pattern TABLE = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*(?:#.*)?$");
    private static final Pattern KEY_VALUE = Pattern.compile("^[^#\\s=][^=]*=\\s*.*$");

    @Override
    public String getExtension() {
        return "toml";
    }

    @Override
    public String getMimeType() {
        return "application/toml";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "TOML configuration";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        int tables = 0;
        int arrayTables = 0;
        int keyCount = 0;
        List<String> tableNames = new ArrayList<>();

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            Matcher at = ARRAY_TABLE.matcher(line);
            if (at.matches()) {
                arrayTables++;
                if (tableNames.size() < MAX_TABLE_NAMES) {
                    tableNames.add(at.group(1).trim());
                }
                continue;
            }
            Matcher t = TABLE.matcher(line);
            if (t.matches()) {
                tables++;
                if (tableNames.size() < MAX_TABLE_NAMES) {
                    tableNames.add(t.group(1).trim());
                }
                continue;
            }
            if (KEY_VALUE.matcher(line).matches()) {
                keyCount++;
            }
        }

        meta.put("tableCount", tables);
        meta.put("arrayTableCount", arrayTables);
        meta.put("keyCount", keyCount);
        if (!tableNames.isEmpty()) {
            meta.put("tables", List.copyOf(tableNames));
        }
    }
}

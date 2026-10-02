package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;

/**
 * Java {@code .properties} 文件处理器。
 *
 * <p>元数据：键数量、注释行数、空行数。
 * 采用 Java 属性文件的行级规则（{@code #} / {@code !} 为注释，{@code key=value} 为条目），
 * 不处理续行与转义，仅用于元数据展示。
 */
public class PropertiesFileHandler extends AbstractTextHandler {

    @Override
    public String getExtension() {
        return "properties";
    }

    @Override
    public String getMimeType() {
        return "application/x-java-properties";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "Java properties file";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;
        int keyCount = 0;
        int commentLines = 0;
        int blankLines = 0;
        for (String line : content.split("\n", -1)) {
            String t = line.stripLeading();
            if (t.isEmpty()) {
                blankLines++;
            } else if (t.charAt(0) == '#' || t.charAt(0) == '!') {
                commentLines++;
            } else {
                if (t.indexOf('=') > 0) keyCount++;
            }
        }
        meta.put("keyCount", keyCount);
        meta.put("commentLines", commentLines);
        meta.put("blankLines", blankLines);
    }
}

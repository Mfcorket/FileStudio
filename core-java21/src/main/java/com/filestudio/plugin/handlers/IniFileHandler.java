package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * INI 配置文件处理器。
 *
 * <p>元数据：section 数量、键数量、已识别 section 名列表（最多 32 个）。
 * 识别 {@code [section]} 作为 section 头，{@code key=value} 作为条目；
 * {@code ;} / {@code #} 为注释。
 */
public class IniFileHandler extends AbstractTextHandler {

    private static final int MAX_SECTION_NAMES = 32;

    @Override
    public String getExtension() {
        return "ini";
    }

    @Override
    public String getMimeType() {
        return "text/plain";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "INI configuration file";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;
        int sectionCount = 0;
        int keyCount = 0;
        List<String> sections = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.charAt(0) == ';' || t.charAt(0) == '#') continue;
            if (t.startsWith("[") && t.endsWith("]")) {
                sectionCount++;
                if (sections.size() < MAX_SECTION_NAMES) {
                    sections.add(t.substring(1, t.length() - 1).trim());
                }
            } else if (t.indexOf('=') > 0) {
                keyCount++;
            }
        }
        meta.put("sectionCount", sectionCount);
        meta.put("keyCount", keyCount);
        if (!sections.isEmpty()) {
            meta.put("sections", List.copyOf(sections));
        }
    }
}

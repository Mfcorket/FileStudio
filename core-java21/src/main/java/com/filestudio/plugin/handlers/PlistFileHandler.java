package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple 属性列表（Property List）处理器。
 *
 * <p>支持 XML 编码的 plist（{@code <?xml ...?><plist><dict>...）与 OpenStep/NeXT
 * 文本格式。元数据：键数量、值类型统计、顶层元素类型与 plist 版本。
 *
 * <p>FULL 编辑能力——内容原样回写。<b>不支持二进制 plist（bplist）</b>，
 * 遇到时 {@code canHandle} 返回 false。
 */
public class PlistFileHandler extends AbstractTextHandler {

    private static final Pattern DICT_KEY = Pattern.compile("<key>([^<]+)</key>");
    private static final Pattern ELEMENT_REAL = Pattern.compile("<real>([^<]+)</real>");
    private static final Pattern ELEMENT_INTEGER = Pattern.compile("<integer>([^<]+)</integer>");
    private static final Pattern ELEMENT_STRING = Pattern.compile("<string>([^<]+)</string>");
    private static final Pattern ELEMENT_DATE = Pattern.compile("<date>([^<]+)</date>");
    private static final Pattern ELEMENT_TRUE = Pattern.compile("<true\\s*/>");
    private static final Pattern ELEMENT_FALSE = Pattern.compile("<false\\s*/>");
    private static final Pattern ARRAY_ITEM = Pattern.compile("<array>|<dict>");

    @Override
    public String getExtension() {
        return "plist";
    }

    @Override
    public String getMimeType() {
        return "application/x-plist";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "Apple Property List (XML plist)";
    }

    @Override
    public boolean canHandle(java.io.File file) {
        if (file == null) return false;
        String ext = getExtensionFromName(file.getName());
        if (!ext.equals("plist")) return false;
        // 排除二进制 plist：文件以 "bplist00" 开头
        if (file.isFile()) {
            try {
                byte[] head = new byte[8];
                int n;
                try (var in = java.nio.file.Files.newInputStream(file.toPath())) {
                    n = in.read(head);
                }
                if (n >= 8) {
                    String sig = new String(head, 0, 8, java.nio.charset.StandardCharsets.US_ASCII);
                    if (sig.startsWith("bplist")) return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        // OpenStep 文本格式是键值对，不匹配 XML 标签时直接标注格式
        boolean xml = content.stripLeading().startsWith("<?xml");
        meta.put("encodingFormat", xml ? "xml" : "openstep");

        if (!xml) {
            return;
        }
        int keys = count(DICT_KEY, content);
        meta.put("keyCount", keys);
        meta.put("stringValueCount", count(ELEMENT_STRING, content));
        meta.put("integerValueCount", count(ELEMENT_INTEGER, content));
        meta.put("realValueCount", count(ELEMENT_REAL, content));
        meta.put("dateValueCount", count(ELEMENT_DATE, content));
        meta.put("trueCount", count(ELEMENT_TRUE, content));
        meta.put("falseCount", count(ELEMENT_FALSE, content));

        // 顶层容器类型：第一个 <dict> / <array> 之前的类型
        String trimmed = content.stripLeading();
        int firstDict = trimmed.indexOf("<dict>");
        int firstArray = trimmed.indexOf("<array>");
        if (firstDict >= 0 && (firstArray < 0 || firstDict < firstArray)) {
            meta.put("rootElement", "dict");
        } else if (firstArray >= 0) {
            meta.put("rootElement", "array");
        }
    }

    private static int count(Pattern p, String s) {
        int n = 0;
        Matcher m = p.matcher(s);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
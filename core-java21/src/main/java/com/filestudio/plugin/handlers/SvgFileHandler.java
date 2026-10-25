package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG（可缩放矢量图形）处理器。
 *
 * <p>SVG 本质是带命名空间的 XML，因此 FULL 编辑能力（内容原样回写），
 * 并在通用文本元数据之上追加图形层面的信息：文档尺寸、viewBox、
 * 标题、命名空间与各种绘图元素的数量、路径数据解析、CSS 样式提取。
 *
 * <p>沿用 {@link XmlFileHandler} 的文本基类路径，不构建 DOM，
 * 用受限的文本提取解析根元素属性与元素计数。
 */
public class SvgFileHandler extends AbstractTextHandler {

    /** SVG 路径命令正则。 */
    private static final Pattern PATH_CMD = Pattern.compile("([MmZzLlHhVvCcSsQqTtAa])([^MmZzLlHhVvCcSsQqTtAa]*)");

    /** CSS 样式属性正则。 */
    private static final Pattern STYLE_ATTR = Pattern.compile("([\\w-]+)\\s*:\\s*([^;]+)");

    /**
     * SVG 处理器。
     */
    public SvgFileHandler() {}

    @Override
    public String getExtension() {
        return "svg";
    }

    @Override
    public String getMimeType() {
        return "image/svg+xml";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "Scalable Vector Graphics (SVG)";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        meta.put("format", "svg");
        meta.put("image", true);

        // 根元素属性
        String width = EpubFileHandler.attribute(content, "svg", "width");
        String height = EpubFileHandler.attribute(content, "svg", "height");
        if (width != null) {
            meta.put("width", width);
        }
        if (height != null) {
            meta.put("height", height);
        }
        String viewBox = EpubFileHandler.attribute(content, "svg", "viewBox");
        if (viewBox != null) {
            meta.put("viewBox", viewBox);
        }
        String version = EpubFileHandler.attribute(content, "svg", "version");
        if (version != null) {
            meta.put("svgVersion", version);
        }

        // 命名空间与标题
        meta.put("hasSvgNamespace", content.contains("xmlns=\"http://www.w3.org/2000/svg\"")
                || content.contains("xmlns='http://www.w3.org/2000/svg\"")
                || content.contains("xmlns=\"http://www.w3.org/2000/svg\""));
        String title = EpubFileHandler.elementText(content, "title");
        if (title != null && !title.isEmpty()) {
            meta.put("title", title);
        }

        // 各类绘图元素计数
        int shapes = 0;
        shapes += EpubFileHandler.countElements(content, "path");
        shapes += EpubFileHandler.countElements(content, "rect");
        shapes += EpubFileHandler.countElements(content, "circle");
        shapes += EpubFileHandler.countElements(content, "ellipse");
        shapes += EpubFileHandler.countElements(content, "line");
        shapes += EpubFileHandler.countElements(content, "polyline");
        shapes += EpubFileHandler.countElements(content, "polygon");
        meta.put("shapeCount", shapes);
        int groups = EpubFileHandler.countElements(content, "g");
        if (groups > 0) {
            meta.put("groupCount", groups);
        }

        // --- 新增：路径数据解析 ---
        parsePathData(content, meta);

        // --- 新增：CSS 样式解析 ---
        parseStyles(content, meta);

        // --- 新增：变换属性统计 ---
        parseTransforms(content, meta);

        // --- 新增：填充/描边统计 ---
        parseFillStroke(content, meta);
    }

    /** 解析所有 path 元素的 d 属性，统计命令类型与数量。 */
    private void parsePathData(String content, Map<String, Object> meta) {
        List<String> pathDataList = extractAttributes(content, "path", "d");
        if (pathDataList.isEmpty()) {
            return;
        }

        int totalCommands = 0;
        Map<String, Integer> cmdCounts = new LinkedHashMap<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean hasCoords = false;

        for (String d : pathDataList) {
            Matcher m = PATH_CMD.matcher(d);
            while (m.find()) {
                String cmd = m.group(1);
                String args = m.group(2).trim();
                totalCommands++;
                cmdCounts.merge(cmd, 1, Integer::sum);

                // 简单坐标提取（用于估算边界框）
                if (!args.isEmpty()) {
                    String[] parts = args.split("[,\\s]+");
                    for (int i = 0; i + 1 < parts.length; i += 2) {
                        try {
                            double x = Double.parseDouble(parts[i]);
                            double y = Double.parseDouble(parts[i + 1]);
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                            hasCoords = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        if (totalCommands > 0) {
            meta.put("pathCommandCount", totalCommands);
            meta.put("pathCommandTypes", cmdCounts);
            if (hasCoords) {
                meta.put("pathBounds", Map.of("minX", minX, "minY", minY, "maxX", maxX, "maxY", maxY));
            }
        }
    }

    /** 提取所有指定元素的指定属性值。 */
    private List<String> extractAttributes(String content, String element, String attr) {
        List<String> results = new ArrayList<>();
        String openTag = "<" + element;
        int pos = 0;
        while (true) {
            int start = content.indexOf(openTag, pos);
            if (start < 0) break;
            int end = content.indexOf('>', start);
            if (end < 0) break;
            String tag = content.substring(start, end);
            String val = EpubFileHandler.attribute(tag, element, attr);
            if (val != null) {
                results.add(val);
            }
            pos = end + 1;
        }
        return results;
    }

    /** 解析 style 属性与内联样式。 */
    private void parseStyles(String content, Map<String, Object> meta) {
        // style="..."
        List<String> styleAttrs = extractAttributes(content, "", "style");
        if (!styleAttrs.isEmpty()) {
            Map<String, String> styleMap = new LinkedHashMap<>();
            for (String s : styleAttrs) {
                parseStyleString(s, styleMap);
            }
            meta.put("inlineStyles", styleMap);
        }

        // <style>...</style> 内容
        String styleContent = EpubFileHandler.elementText(content, "style");
        if (styleContent != null && !styleContent.isBlank()) {
            meta.put("styleElement", styleContent.trim());
        }
    }

    private void parseStyleString(String style, Map<String, String> out) {
        Matcher m = STYLE_ATTR.matcher(style);
        while (m.find()) {
            out.put(m.group(1).trim(), m.group(2).trim());
        }
    }

    /** 统计 transform 属性。 */
    private void parseTransforms(String content, Map<String, Object> meta) {
        List<String> transforms = extractAttributes(content, "", "transform");
        if (!transforms.isEmpty()) {
            Map<String, Integer> transformTypes = new LinkedHashMap<>();
            for (String t : transforms) {
                if (t.startsWith("translate")) transformTypes.merge("translate", 1, Integer::sum);
                else if (t.startsWith("scale")) transformTypes.merge("scale", 1, Integer::sum);
                else if (t.startsWith("rotate")) transformTypes.merge("rotate", 1, Integer::sum);
                else if (t.startsWith("skewX")) transformTypes.merge("skewX", 1, Integer::sum);
                else if (t.startsWith("skewY")) transformTypes.merge("skewY", 1, Integer::sum);
                else if (t.startsWith("matrix")) transformTypes.merge("matrix", 1, Integer::sum);
            }
            meta.put("transformCount", transforms.size());
            meta.put("transformTypes", transformTypes);
        }
    }

    /** 统计 fill/stroke 属性。 */
    private void parseFillStroke(String content, Map<String, Object> meta) {
        List<String> fills = extractAttributes(content, "", "fill");
        List<String> strokes = extractAttributes(content, "", "stroke");
        List<String> strokeWidths = extractAttributes(content, "", "stroke-width");

        if (!fills.isEmpty()) {
            Map<String, Integer> fillCounts = new LinkedHashMap<>();
            for (String f : fills) {
                fillCounts.merge(f, 1, Integer::sum);
            }
            meta.put("fillTypes", fillCounts);
        }
        if (!strokes.isEmpty()) {
            Map<String, Integer> strokeCounts = new LinkedHashMap<>();
            for (String s : strokes) {
                strokeCounts.merge(s, 1, Integer::sum);
            }
            meta.put("strokeTypes", strokeCounts);
        }
        if (!strokeWidths.isEmpty()) {
            meta.put("strokeWidthValues", strokeWidths);
        }
    }
}
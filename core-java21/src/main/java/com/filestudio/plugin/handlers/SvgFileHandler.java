package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;

/**
 * SVG（可缩放矢量图形）处理器。
 *
 * <p>SVG 本质是带命名空间的 XML，因此 FULL 编辑能力（内容原样回写），
 * 并在通用文本元数据之上追加图形层面的信息：文档尺寸、viewBox、
 * 标题、命名空间与各种绘图元素的数量。
 *
 * <p>沿用 {@link XmlFileHandler} 的文本基类路径，不构建 DOM，
 * 用受限的文本提取解析根元素属性与元素计数。
 */
public class SvgFileHandler extends AbstractTextHandler {

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
    }
}
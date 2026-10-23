package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LaTeX 文档处理器。
 *
 * <p>元数据：section/subsection/paragraph 计数、usepackage/begin{env} 计数、
 * 数学模式段数（\( \) 与 $$）等。轻量正则，不解析完整语法树。
 */
public class LatexFileHandler extends AbstractTextHandler {

    /**
     * LaTeX 处理器。
     */
    public LatexFileHandler() {}

    private static final Pattern SECTION = Pattern.compile(
            "\\\\part\\*?\\s*\\{|\\\\chapter\\*?\\s*\\{|\\\\section\\*?\\s*\\{|"
                    + "\\\\subsection\\*?\\s*\\{|\\\\subsubsection\\*?\\s*\\{|"
                    + "\\\\paragraph\\*?\\s*\\{|\\\\subparagraph\\*?\\s*\\{");

    private static final Pattern USEPACKAGE = Pattern.compile("\\\\usepackage(?:\\[[^]]*\\])?\\{[^}]*\\}");

    private static final Pattern BEGIN_ENV = Pattern.compile("\\\\begin\\{([a-zA-Z*]+)\\}");

    private static final Pattern MATH_INLINE = Pattern.compile("\\\\\\(|\\\\\\)");

    private static final Pattern MATH_DISPLAY = Pattern.compile("\\$\\$");

    @Override
    public String getExtension() {
        return "tex";
    }

    @Override
    public String getMimeType() {
        return "application/x-tex";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "LaTeX document";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isEmpty()) return;

        Matcher sm = SECTION.matcher(content);
        int sections = 0;
        while (sm.find()) {
            sections++;
        }
        meta.put("sectionCount", sections);

        Matcher um = USEPACKAGE.matcher(content);
        int packages = 0;
        while (um.find()) {
            packages++;
        }
        meta.put("packageCount", packages);

        Matcher bm = BEGIN_ENV.matcher(content);
        int envs = 0;
        while (bm.find()) {
            envs++;
        }
        meta.put("environmentCount", envs);

        Matcher mi = MATH_INLINE.matcher(content);
        int inlineMath = 0;
        while (mi.find()) {
            inlineMath++;
        }
        meta.put("inlineMathPairs", inlineMath / 2);

        Matcher md = MATH_DISPLAY.matcher(content);
        int displayMath = 0;
        while (md.find()) {
            displayMath++;
        }
        meta.put("displayMathBlocks", displayMath / 2);

        meta.put("wordCount", content.trim().isEmpty()
                ? 0 : content.trim().split("\\s+").length);
    }
}
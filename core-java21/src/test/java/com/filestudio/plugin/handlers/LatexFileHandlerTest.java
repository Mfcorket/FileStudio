package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LatexFileHandlerTest {

    private final LatexFileHandler handler = new LatexFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("tex", handler.getExtension());
        assertEquals("application/x-tex", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void countsLatexStructure(@TempDir Path dir) throws IOException {
        String tex = """
                \\documentclass{article}
                \\usepackage{amsmath}
                \\usepackage{graphicx}

                \\begin{document}
                \\section{Intro}
                Inline math \\( a + b \\) here.

                \\subsection{Method}
                $$ x^2 $$

                \\begin{equation}
                y = f(x)
                \\end{equation}
                \\end{document}
                """;
        Path p = dir.resolve("paper.tex");
        Files.writeString(p, tex, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());
        assertEquals(2, doc.getMetadata().get("sectionCount"),
                "section + subsection 各计一次");
        assertEquals(2, doc.getMetadata().get("packageCount"), "amsmath + graphicx");
        assertTrue((int) doc.getMetadata().get("environmentCount") >= 2,
                "document + equation 至少 2 个 begin");
        assertEquals(1, doc.getMetadata().get("inlineMathPairs"));
        assertEquals(1, doc.getMetadata().get("displayMathBlocks"));
    }

    @Test
    void rendersTextBack(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("a.tex");
        Files.writeString(p, "\\alpha", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());
        assertEquals("\\alpha", doc.getContent());
        assertTrue(doc.isEditable());
    }
}
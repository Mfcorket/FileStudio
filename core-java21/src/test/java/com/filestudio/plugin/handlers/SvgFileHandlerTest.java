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

class SvgFileHandlerTest {

    private final SvgFileHandler handler = new SvgFileHandler();

    private static final String BASIC_SVG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"
                 viewBox="0 0 800 600" version="1.1">
              <title>Figure One</title>
              <g id="shapes">
                <path d="M10 10 L100 100"/>
                <rect x="1" y="2" width="3" height="4"/>
                <circle cx="5" cy="5" r="5"/>
                <ellipse cx="1" cy="2" rx="3" ry="4"/>
                <line x1="1" y1="2" x2="3" y2="4"/>
              </g>
            </svg>
            """;

    @Test
    void declaresFormat() {
        assertEquals("svg", handler.getExtension());
        assertEquals("image/svg+xml", handler.getMimeType());
        assertEquals(EditCapability.FULL, handler.getEditCapability());
    }

    @Test
    void extractsSvgMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fig.svg");
        Files.writeString(p, BASIC_SVG, StandardCharsets.UTF_8);

        Document doc = handler.parse(p.toFile());

        assertEquals("image/svg+xml", doc.getMimeType());
        assertEquals("800", doc.getMetadata().get("width"));
        assertEquals("600", doc.getMetadata().get("height"));
        assertEquals("0 0 800 600", doc.getMetadata().get("viewBox"));
        assertEquals("1.1", doc.getMetadata().get("svgVersion"));
        assertEquals("Figure One", doc.getMetadata().get("title"));
        assertEquals(true, doc.getMetadata().get("hasSvgNamespace"));
        assertEquals(5, doc.getMetadata().get("shapeCount"), "path+rect+circle+ellipse+line");
        assertEquals(1, doc.getMetadata().get("groupCount"));
        assertEquals(true, doc.getMetadata().get("image"));
    }

    @Test
    void rendersTextBack(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("out.svg");
        Files.writeString(p, "<svg xmlns=\"http://www.w3.org/2000/svg\"><g/></svg>", StandardCharsets.UTF_8);
        Document doc = handler.parse(p.toFile());

        Document edited = doc.withContent("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\"/></svg>");
        Path out = dir.resolve("edited.svg");
        handler.render(edited, out.toFile());

        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\"/></svg>",
                Files.readString(out, StandardCharsets.UTF_8));
    }
}
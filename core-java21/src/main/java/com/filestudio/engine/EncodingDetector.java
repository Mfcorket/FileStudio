package com.filestudio.engine;

import com.filestudio.core.FileStudioException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 字节流编码探测工具：识别 BOM 并在无 BOM 时回退到 UTF-8（ASCII 兼容）。
 *
 * <p>当前策略简洁可控，后续可扩展为基于统计的字节频率嗅探。
 */
public final class EncodingDetector {

    private EncodingDetector() {}

    /** 返回 [charset, consumeBom]。consumeBom 为 true 时调用方应跳过对应 BOM 字节数。 */
    public static CharsetAndBom detect(Path path) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] bom = new byte[4];
            int n = in.read(bom);
            return detect(bom, n);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading for encoding detection: " + path, e);
        }
    }

    public static CharsetAndBom detect(byte[] head, int len) {
        if (len >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new CharsetAndBom(StandardCharsets.UTF_8, 3);
        }
        if (len >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new CharsetAndBom(StandardCharsets.UTF_16LE, 2);
        }
        if (len >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new CharsetAndBom(StandardCharsets.UTF_16BE, 2);
        }
        return new CharsetAndBom(StandardCharsets.UTF_8, 0);
    }

    public record CharsetAndBom(Charset charset, int bomBytes) {}
}

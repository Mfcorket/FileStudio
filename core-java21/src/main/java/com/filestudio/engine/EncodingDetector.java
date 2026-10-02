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

    /**
     * 读取文件头部字节并检测编码。
     *
     * @param path 目标文件
     * @return 编码与 BOM 长度
     * @throws FileStudioException 读取失败时抛出
     */
    public static CharsetAndBom detect(Path path) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] bom = new byte[4];
            int n = in.read(bom);
            return detect(bom, n);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading for encoding detection: " + path, e);
        }
    }

    /**
     * 从字节前缀检测编码。
     *
     * <p>识别 UTF-8 / UTF-16LE / UTF-16BE 的 BOM；无 BOM 时回退 UTF-8。
     *
     * @param head 文件头字节
     * @param len  有效字节数
     * @return 编码与 BOM 长度
     */
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

    /**
     * 编码与 BOM 长度。不可变。
     *
     * @param charset  检测出的字符集
     * @param bomBytes BOM 字节数，调用方解码时应跳过该数量；无 BOM 时为 0
     */
    public record CharsetAndBom(Charset charset, int bomBytes) {}
}

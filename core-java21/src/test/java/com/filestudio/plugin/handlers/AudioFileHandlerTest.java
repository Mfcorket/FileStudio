package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AudioFileHandlerTest {

    private final AudioFileHandler handler = new AudioFileHandler();

    @Test
    void declaresAudioFormat() {
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
        assertEquals("audio/wav", handler.getMimeType());
    }

    @Test
    void canHandleKnownExtensions() {
        assertTrue(handler.canHandle(new java.io.File("a.wav")));
        assertTrue(handler.canHandle(new java.io.File("b.flac")));
        assertTrue(handler.canHandle(new java.io.File("c.mp3")));
        assertTrue(handler.canHandle(new java.io.File("d.ogg")));
        assertFalse(handler.canHandle(new java.io.File("e.txt")));
    }

    @Test
    void parsesWavMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("sound.wav");
        // RIFF 头 + fmt 块（PCM, 44100Hz, 16bit, 立体声）+ data 块
        byte[] wav = new byte[44];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 0, 4);
        wav[4] = (byte) 0x24; wav[5] = (byte) 0x08; wav[6] = 0; wav[7] = 0; // 文件大小-8
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 8, 4);
        System.arraycopy("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 12, 4);
        wav[16] = 16; wav[17] = 0; wav[18] = 0; wav[19] = 0; // fmt 块大小
        wav[20] = 1; wav[21] = 0; // PCM
        wav[22] = 2; wav[23] = 0; // 声道=2
        wav[24] = 0x44; wav[25] = (byte) 0xAC; wav[26] = 0; wav[27] = 0; // 采样率=44100
        wav[28] = 0x10; wav[29] = (byte) 0xB1; wav[30] = 2; wav[31] = 0; // 字节率=176400
        wav[32] = 4; wav[33] = 0; // 块对齐
        wav[34] = 16; wav[35] = 0; // 位深=16
        System.arraycopy("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 36, 4);
        wav[40] = 0; wav[41] = 0; wav[42] = 0; wav[43] = 0; // data 大小
        Files.write(p, wav);

        Document doc = handler.parse(p.toFile());
        assertEquals("audio/wav", doc.getMimeType());
        assertEquals("wav", doc.getMetadata().get("format"));
        assertEquals(44100, doc.getMetadata().get("sampleRate"));
        assertEquals(2, doc.getMetadata().get("channels"));
        assertEquals(16, doc.getMetadata().get("bitsPerSample"));
        assertEquals(true, doc.getMetadata().get("viewOnly"));
    }

    @Test
    void parsesFlacMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("music.flac");
        // fLaC + STREAMINFO 元数据块
        byte[] flac = new byte[42];
        System.arraycopy("fLaC".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, flac, 0, 4);
        flac[4] = 0x00; // STREAMINFO, 非最后
        flac[5] = 0x00; flac[6] = 0x00; flac[7] = 0x22; // 块大小=34
        // STREAMINFO 内容（34 字节）
        flac[8] = 0x00; flac[9] = 0x00; // 最小块大小
        flac[10] = 0x00; flac[11] = 0x00; // 最大块大小
        flac[12] = 0x00; flac[13] = 0x00; flac[14] = 0x00; // 最小帧大小
        flac[15] = 0x00; flac[16] = 0x00; flac[17] = 0x00; // 最大帧大小
        // 采样率(20bit) + 声道(3bit) + 位深(5bit) + 总采样数(36bit)
        flac[18] = 0x0A; flac[19] = (byte) 0xC4; flac[20] = 0x42; // 采样率=44100
        flac[21] = (byte) 0xF3; // 声道-1=1, 位深-1=15
        flac[22] = 0x00; flac[23] = 0x00; flac[24] = 0x00; // 总采样数高 4 位
        flac[25] = 0x00; flac[26] = 0x00; flac[27] = 0x00; flac[28] = 0x00; // 总采样数低 32 位
        Files.write(p, flac);

        Document doc = handler.parse(p.toFile());
        assertEquals("audio/flac", doc.getMimeType());
        assertEquals("flac", doc.getMetadata().get("format"));
        assertEquals(44100, doc.getMetadata().get("sampleRate"));
        assertEquals(2, doc.getMetadata().get("channels"));
        assertEquals(16, doc.getMetadata().get("bitsPerSample"));
    }

    @Test
    void parsesMp3Metadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("song.mp3");
        // MPEG1 Layer3 帧头：FF FB 90 64
        // 版本=11(MPEG1), 层=01(Layer3), 保护=1
        // 比特率索引=1001(128kbps), 采样率索引=00(44100)
        byte[] mp3 = new byte[4];
        mp3[0] = (byte) 0xFF;
        mp3[1] = (byte) 0xFB; // 1111 1011
        mp3[2] = (byte) 0x90; // 1001 0000
        mp3[3] = 0x64;
        Files.write(p, mp3);

        Document doc = handler.parse(p.toFile());
        assertEquals("audio/mpeg", doc.getMimeType());
        assertEquals("mp3", doc.getMetadata().get("format"));
        assertEquals(44100, doc.getMetadata().get("sampleRate"));
        assertEquals(128, doc.getMetadata().get("bitrate"));
    }

    @Test
    void parsesOggMetadata(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("audio.ogg");
        // OggS + Vorbis 标识头
        byte[] ogg = new byte[32];
        System.arraycopy("OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, ogg, 0, 4);
        ogg[4] = 0; // 版本
        ogg[5] = 2; // 头类型
        ogg[6] = 0; ogg[7] = 0; ogg[8] = 0; ogg[9] = 0; // 粒度位置
        ogg[10] = 0; ogg[11] = 0; ogg[12] = 0; ogg[13] = 0;
        ogg[14] = 0; ogg[15] = 0; ogg[16] = 0; ogg[17] = 0;
        ogg[18] = 1; ogg[19] = 0; ogg[20] = 0; ogg[21] = 0; // 序列号
        ogg[22] = 0; ogg[23] = 0; ogg[24] = 0; ogg[25] = 0;
        ogg[26] = 0; ogg[27] = 0; ogg[28] = 0; ogg[29] = 0;
        ogg[30] = 1; // 页校验和
        ogg[31] = 0;
        // Vorbis 标识头在字节 28+ 处有采样率
        // 简化：直接在偏移 28 写入采样率
        ogg[28] = 0x44; ogg[29] = (byte) 0xAC; ogg[30] = 0; ogg[31] = 0; // 44100 LE
        Files.write(p, ogg);

        Document doc = handler.parse(p.toFile());
        assertEquals("audio/ogg", doc.getMimeType());
        assertEquals("ogg", doc.getMetadata().get("format"));
        assertEquals(44100, doc.getMetadata().get("sampleRate"));
    }

    @Test
    void unknownAudioContentCannotBeParsed(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("broken.wav");
        byte[] junk = "this is not audio at all".getBytes();
        Files.write(p, junk);
        assertFalse(handler.canHandle(p.toFile()));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.wav");
        byte[] wav = new byte[44];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 8, 4);
        Files.write(p, wav);
        Document doc = handler.parse(p.toFile());
        assertThrows(com.filestudio.core.FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.wav").toFile()));
    }

    @Test
    void viewOnlyDocumentHasEmptyContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("img.wav");
        byte[] wav = new byte[44];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, wav, 8, 4);
        Files.write(p, wav);
        Document doc = handler.parse(p.toFile());
        assertEquals("", doc.getContent());
        assertFalse(doc.isEditable());
    }
}

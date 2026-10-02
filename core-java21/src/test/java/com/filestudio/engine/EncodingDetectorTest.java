package com.filestudio.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EncodingDetectorTest {

    @Test
    void detectsUtf8Bom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        var r = EncodingDetector.detect(bom, 4);
        assertEquals(StandardCharsets.UTF_8, r.charset());
        assertEquals(3, r.bomBytes());
    }

    @Test
    void detectsUtf16LeBom() {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE, 'a', 0};
        var r = EncodingDetector.detect(bom, 4);
        assertEquals(StandardCharsets.UTF_16LE, r.charset());
        assertEquals(2, r.bomBytes());
    }

    @Test
    void detectsUtf16BeBom() {
        byte[] bom = {(byte) 0xFE, (byte) 0xFF, 0, 'a'};
        var r = EncodingDetector.detect(bom, 4);
        assertEquals(StandardCharsets.UTF_16BE, r.charset());
        assertEquals(2, r.bomBytes());
    }

    @Test
    void noBomDefaultsToUtf8() {
        var r = EncodingDetector.detect(new byte[]{'h','i'}, 2);
        assertEquals(StandardCharsets.UTF_8, r.charset());
        assertEquals(0, r.bomBytes());
    }
}

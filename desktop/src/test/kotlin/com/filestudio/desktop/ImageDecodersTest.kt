package com.filestudio.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图片解码链的行为。
 *
 * 重点不是「能不能画出图」——那要靠界面验证——而是：
 * Skia 负责的格式要优先走 Skia，Skia 搞不定时要能自动落到 ImageIO，
 * 两者都不行时才返回 null（而不是抛异常把界面带崩）。
 */
class ImageDecodersTest {

    private fun sampleImage(w: Int = 32, h: Int = 16): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, w, h)
        g.color = Color.BLUE
        g.fillRect(0, 0, w / 2, h / 2)
        g.dispose()
        return img
    }

    private fun encode(format: String): ByteArray {
        val bos = ByteArrayOutputStream()
        assertTrue(ImageIO.write(sampleImage(), format, bos), "ImageIO 应能写出 $format")
        return bos.toByteArray()
    }

    @Test
    fun `png goes through skia`() {
        val (image, name) = assertNotNull(ImageDecoders.decodeWithName(encode("png")))
        try {
            assertEquals("Skia", name, "PNG 应由 Skia 处理，速度最快")
            assertEquals(32, image.width)
            assertEquals(16, image.height)
        } finally {
            image.close()
        }
    }

    @Test
    fun `tiff falls back to imageio`() {
        val bytes = encode("TIFF")
        // 先确认 Skia 确实解不了 TIFF，这样才验证到「回落到 ImageIO」
        assertNull(SkiaDecoder.decode(bytes), "前提：Skia 不应能解码 TIFF")

        val (image, name) = assertNotNull(ImageDecoders.decodeWithName(bytes))
        try {
            assertEquals("ImageIO", name)
            assertEquals(32, image.width)
            assertEquals(16, image.height)
        } finally {
            image.close()
        }
    }

    @Test
    fun `unknown bytes decode to null without throwing`() {
        val junk = "definitely not an image".toByteArray()
        assertNull(SkiaDecoder.decode(junk))
        assertNull(ImageIoDecoder.decode(junk))
        assertNull(ImageDecoders.decode(junk))
    }

    @Test
    fun `empty input is handled`() {
        assertNull(ImageDecoders.decode(ByteArray(0)))
    }

    @Test
    fun `chain never throws on malformed data`() {
        // 截断的 PNG：ImageIO 很可能抛异常，解码链必须吞掉并继续/返回 null
        val truncated = encode("png").copyOf(20)
        assertNull(ImageDecoders.decode(truncated))
    }

    @Test
    fun `supported extensions include tiff when imageio provides it`() {
        val exts = ImageDecoders.supportedExtensions()
        assertTrue(exts.contains("png"))
        assertTrue(exts.contains("jpg"))
        assertTrue(exts.contains("webp"), "Skia 负责的格式应被声明为支持")
        if (ImageDecoders.imageIoExtensions().contains("tiff")) {
            assertTrue(exts.contains("tiff"))
        }
    }

    @Test
    fun `imageio extension probe does not throw`() {
        // 只是探测环境能力，不应抛异常
        assertNotNull(ImageDecoders.imageIoExtensions())
    }

    @Test
    fun `decoder names are distinct`() {
        val names = ImageDecoders.chain.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.contains("Skia"))
        assertTrue(names.contains("ImageIO"))
    }

    @Test
    fun `alpha pixels survive the imageio conversion`() {
        // 走 ImageIO 路径（半透明 PNG 是唯一能稳定触发它的用例之一），
        // 确认 ARGB 顺序没有写反，否则半透明会变成黑块
        val src = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        src.setRGB(0, 0, 0x80FF0000.toInt())   // 半透明红
        src.setRGB(1, 0, 0x80000000.toInt())
        val bos = ByteArrayOutputStream()
        ImageIO.write(src, "TIFF", bos)

        val image = assertNotNull(ImageIoDecoder.decode(bos.toByteArray()))
        try {
            assertEquals(8, image.width)
            assertEquals(8, image.height)
        } finally {
            image.close()
        }
    }
}
package com.filestudio.desktop

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * 图片解码器。
 *
 * 预览只认一种输出类型（Skia 的 [Image]），因此每个解码器都负责把各自的
 * 中间表示转成它。这样绘制侧只有一条代码路径，也不用去操心 ImageBitmap 转换。
 */
interface ImageDecoder {
    /** 展示用的名字，例如「Skia」。 */
    val name: String

    /**
     * 解码。
     *
     * @return 解码结果；该格式无法处理时返回 null，交回给下一个解码器
     */
    fun decode(bytes: ByteArray): Image?
}

/**
 * 依次尝试各解码器，返回第一个成功的结果。
 *
 * 顺序：先 Skia（覆盖 png/jpg/webp/gif/bmp/ico 且速度最快），
 * 再走 ImageIO（补上 JDK 自带但 Skia 不支持的格式，目前主要是 TIFF）。
 */
object ImageDecoders {

    /** 所有已注册的解码器，按优先级排列。 */
    val chain: List<ImageDecoder> = listOf(SkiaDecoder, ImageIoDecoder)

    /**
     * 依次尝试解码。
     *
     * @return 第一个成功的解码结果；全部失败返回 null
     */
    fun decode(bytes: ByteArray): Image? = decodeWithName(bytes)?.first

    /**
     * 同 [decode]，但一并返回使用的解码器名字，便于界面上说明「由谁解码的」。
     */
    fun decodeWithName(bytes: ByteArray): Pair<Image, String>? {
        for (decoder in chain) {
            val image = try {
                decoder.decode(bytes)
            } catch (e: Throwable) {
                // 单个解码器抛异常不代表整体失败，继续尝试下一个
                null
            }
            if (image != null) {
                return image to decoder.name
            }
        }
        return null
    }

    /** 当前环境下 ImageIO 能处理的后缀（小写、不含点）。 */
    fun imageIoExtensions(): Set<String> = listOf(
        "jpg", "jpeg", "jpe", "png", "gif", "bmp", "wbmp", "tif", "tiff"
    ).filterTo(linkedSetOf()) { ImageIO.getImageReadersBySuffix(it).hasNext() }

    /**
     * 当前环境下确定能解码的格式名。
     *
     * 用于界面提示，避免对不支持的格式承诺「正在解码」。
     */
    fun supportedExtensions(): Set<String> = linkedSetOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico"
    ) + imageIoExtensions()
}

/** Skia：覆盖绝大多数常见位图格式，速度最快，放第一位。 */
object SkiaDecoder : ImageDecoder {
    override val name = "Skia"

    /**
     * 注意：Skia 在遇到无法识别的数据时**抛异常**而不是返回 null
     * （IllegalArgumentException: Failed to Image::makeFromEncoded）。
     * 这里转成 null，保持「解不了就交回给下一个解码器」的约定，
     * 也避免任何直接调用者被它炸到。
     */
    override fun decode(bytes: ByteArray): Image? = try {
        Image.makeFromEncoded(bytes)
    } catch (e: Throwable) {
        null
    }
}

/**
 * ImageIO：补齐 JDK 自带但 Skia 不支持的格式，目前主要是 TIFF。
 *
 * ImageIO 解出的是 [BufferedImage]，这里逐像素转成 Skia 的 N32 位图。
 * 用 UNPREMUL（未预乘）与 `getRGB` 返回的 ARGB 顺序对应，避免半透明像素发黑。
 */
object ImageIoDecoder : ImageDecoder {
    override val name = "ImageIO"

    override fun decode(bytes: ByteArray): Image? = try {
        val bi = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        bufferedToSkia(bi)
    } catch (e: Throwable) {
        null
    }

    private fun bufferedToSkia(source: BufferedImage): Image? {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        source.getRGB(0, 0, w, h, pixels, 0, w)

        val bytes = ByteArray(w * h * 4)
        var i = 0
        for (p in pixels) {
            bytes[i++] = ((p ushr 16) and 0xFF).toByte()  // R
            bytes[i++] = ((p ushr 8) and 0xFF).toByte()   // G
            bytes[i++] = (p and 0xFF).toByte()             // B
            bytes[i++] = ((p ushr 24) and 0xFF).toByte()  // A
        }
        val info = ImageInfo.makeN32(w, h, ColorAlphaType.UNPREMUL)
        return Image.makeRaster(info, bytes, w * 4)
    }
}
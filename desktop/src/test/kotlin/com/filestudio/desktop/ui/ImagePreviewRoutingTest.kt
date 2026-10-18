package com.filestudio.desktop.ui

import com.filestudio.desktop.AppState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 图片预览的接入判定。
 *
 * 预览本身是 Composable，不好直接单测；真正容易出错的是「什么时候走预览、
 * 什么时候走文本」这条分流，以及「图片文件是否真的被识别成 image 类型的
 * MIME」这里锁定分流所依赖的事实。
 */
class ImagePreviewRoutingTest {

    private val state = AppState()

    @AfterTest
    fun cleanup() {
        state.shutdown()
    }

    // ---- 最小 PNG（1x1 透明方块）----
    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
        0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
    )

    @Test
    fun `png opens as image mime so the editor can route to preview`() {
        val f = Files.createTempFile("shot", ".png")
        Files.write(f, pngBytes())

        assertTrue(state.openFile(f.toFile()))
        val tab = assertNotNull(state.activeTab)

        assertTrue(
            tab.session.document.mimeType.startsWith("image/"),
            "png 应识别为 image/*，实际=${tab.session.document.mimeType}"
        )
        assertTrue(tab.readOnly, "图片应当只读")
    }

    @Test
    fun `image tabs expose no text content`() {
        val f = Files.createTempFile("shot2", ".png")
        Files.write(f, pngBytes())
        state.openFile(f.toFile())

        // 这正是预览必须按路径重新读字节的原因
        assertEquals("", state.activeTab!!.content)
        assertNotNull(state.activeMetadata()["width"])
    }

    @Test
    fun `text files are not routed to preview`() {
        val f = Files.createTempFile("note", ".txt")
        Files.writeString(f, "hello")

        assertTrue(state.openFile(f.toFile()))
        val tab = assertNotNull(state.activeTab)

        assertFalse(tab.session.document.mimeType.startsWith("image/"))
        assertFalse(tab.readOnly, "文本应当可编辑")
    }

    @Test
    fun `image metadata carries dimensions for the caption`() {
        val f = Files.createTempFile("shot3", ".png")
        Files.write(f, pngBytes())
        state.openFile(f.toFile())

        val meta = state.activeMetadata()
        assertEquals(1, meta["width"])
        assertEquals(1, meta["height"])
        // format 是短名（png），完整 MIME 在 Document.mimeType 上
        assertEquals("png", meta["format"])
        assertEquals("image/png", state.activeTab!!.session.document.mimeType)
    }

    @Test
    fun `non image binary formats are not routed to preview`() {
        val f = Files.createTempFile("arch", ".zip")
        Files.write(f, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        state.openFile(f.toFile())

        val tab = assertNotNull(state.activeTab)
        assertFalse(tab.session.document.mimeType.startsWith("image/"))
    }

    @Test
    fun `undecodable image files still report an image mime`() {
        // 内容不是合法 JPEG，但扩展名是 jpg。
        // 关键点：mime 必须由扩展名兜底推导，否则会退化成
        // application/octet-stream，界面就不会走图片预览。
        val f = Files.createTempFile("broken", ".jpg")
        Files.writeString(f, "this is definitely not a jpeg")

        assertTrue(state.openFile(f.toFile()))
        assertTrue(state.activeTab!!.readOnly)
        assertEquals("image/jpeg", state.activeTab!!.session.document.mimeType)
        // 头部解析不出来时只影响尺寸，不影响打开
        assertEquals("unavailable", state.activeMetadata()["dimensions"])
    }
}
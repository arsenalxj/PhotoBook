package com.mantou.photobook.archive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPipelineMetadataTest {
    @Test
    fun `quarter turn swaps encoded video dimensions`() {
        assertEquals(
            Pair(1080, 1920),
            MediaPipeline.displayVideoDimensions(1920, 1080, 90),
        )
        assertEquals(
            Pair(1080, 1920),
            MediaPipeline.displayVideoDimensions(1920, 1080, 270),
        )
        assertEquals(
            Pair(1080, 1920),
            MediaPipeline.displayVideoDimensions(1920, 1080, -90),
        )
    }

    @Test
    fun `zero and half turn keep encoded video dimensions`() {
        assertEquals(
            Pair(1920, 1080),
            MediaPipeline.displayVideoDimensions(1920, 1080, 0),
        )
        assertEquals(
            Pair(1920, 1080),
            MediaPipeline.displayVideoDimensions(1920, 1080, 180),
        )
    }

    @Test
    fun `detects native gif by file signature`() {
        val gif = File.createTempFile("photobook", ".bin").apply {
            writeBytes("GIF89a-content".toByteArray())
            deleteOnExit()
        }
        val jpeg = File.createTempFile("photobook", ".bin").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            deleteOnExit()
        }

        assertEquals("image/gif", MediaPipeline.imageMimeTypeFromHeader(gif))
        assertNull(MediaPipeline.imageMimeTypeFromHeader(jpeg))
    }

    @Test
    fun `rejects media redirect outside platform domains`() {
        try {
            MediaPipeline.resolveMediaRedirect(
                "https://sns-video-hw.xhscdn.com/source.mp4",
                "https://example.com/private.mp4",
                SOURCE_PLATFORM_XIAOHONGSHU,
            )
            throw AssertionError("应拒绝跨平台域名跳转")
        } catch (error: ArchiveException) {
            assertEquals("INVALID_RESPONSE", error.code)
        }
    }
}

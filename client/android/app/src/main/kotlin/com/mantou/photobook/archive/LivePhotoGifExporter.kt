package com.mantou.photobook.archive

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal class LivePhotoGifExporter(context: Context) {
    private val exportDirectory = File(context.cacheDir, "share_exports").apply { mkdirs() }

    init {
        cleanupStale()
    }

    fun export(video: File, stem: String): File {
        val part = File(exportDirectory, "$stem-${System.currentTimeMillis()}.gif.part")
        val target = File(exportDirectory, part.name.removeSuffix(".part"))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw ArchiveException("GIF_EXPORT_FAILED", "无法读取 Live Photo 动态时长")
            val frameCount = minOf(MAX_FRAMES, max(1, ceil(durationMs / FRAME_INTERVAL_MS.toDouble()).toInt()))
            val delayMs = max(1L, durationMs / frameCount)
            val first = frameAt(retriever, 0, durationMs)
                ?: throw ArchiveException("GIF_EXPORT_FAILED", "无法读取 Live Photo 动态画面")
            val scaledFirst = scale(first)
            if (scaledFirst !== first) first.recycle()
            FileOutputStream(part, false).use { output ->
                val encoder = GifEncoder(output, scaledFirst.width, scaledFirst.height, 0)
                val options = ImageOptions().setDelay(delayMs, TimeUnit.MILLISECONDS)
                encoder.addImage(scaledFirst.toRgbArray(), options)
                scaledFirst.recycle()
                for (index in 1 until frameCount) {
                    val timeMs = (durationMs * index) / frameCount
                    val frame = frameAt(retriever, timeMs, durationMs) ?: continue
                    val scaled = scale(frame)
                    if (scaled !== frame) frame.recycle()
                    encoder.addImage(scaled.toRgbArray(), options)
                    scaled.recycle()
                }
                encoder.finishEncoding()
                output.fd.sync()
            }
            if (part.length() <= 0 || part.length() > MAX_BYTES) {
                throw ArchiveException("GIF_EXPORT_TOO_LARGE", "GIF 超过 50 MB，请改为保存静态图或视频")
            }
            if (!part.renameTo(target)) throw ArchiveException("GIF_EXPORT_FAILED", "无法完成 GIF 导出")
            return target
        } catch (error: ArchiveException) {
            part.delete()
            target.delete()
            throw error
        } catch (error: Exception) {
            part.delete()
            target.delete()
            throw ArchiveException("GIF_EXPORT_FAILED", "Live Photo 转 GIF 失败", error)
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeMs: Long, durationMs: Long): Bitmap? =
        retriever.getFrameAtTime(
            timeMs.coerceIn(0, max(0L, durationMs - 1)) * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST,
        )

    private fun scale(bitmap: Bitmap): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toDouble() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun Bitmap.toRgbArray(): Array<IntArray> {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return Array(height) { row ->
            IntArray(width) { column -> pixels[row * width + column] and 0x00ffffff }
        }
    }

    private fun cleanupStale(now: Long = System.currentTimeMillis()) {
        val cutoff = now - STALE_AGE_MS
        exportDirectory.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        private const val MAX_EDGE = 720
        private const val TARGET_FPS = 20
        private const val FRAME_INTERVAL_MS = 1000 / TARGET_FPS
        private const val MAX_FRAMES = 72
        private const val MAX_BYTES = 50L * 1024L * 1024L
        private const val STALE_AGE_MS = 24L * 60L * 60L * 1000L
    }
}

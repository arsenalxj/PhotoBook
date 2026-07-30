package com.mantou.photobook.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

class MediaPipeline(
    context: Context,
    private val isShaReferenced: (String) -> Boolean = { false },
) {
    private val root = File(context.filesDir, "archive")
    private val jobs = File(root, "jobs")
    private val avatars = File(root, "avatars")
    private val thumbnails = File(root, "thumbnails")
    private val originals = File(root, "originals")

    init {
        listOf(jobs, avatars, thumbnails, originals).forEach { directory ->
            check(directory.exists() || directory.mkdirs()) { "无法创建媒体目录" }
        }
    }

    fun preparePost(
        job: CaptureJob,
        remote: RemotePost,
        isAttemptActive: () -> Boolean,
        onProgress: (current: Int, total: Int) -> Unit,
    ): PreparedPost {
        val jobDirectory = File(jobs, job.id).apply { mkdirs() }
        val createdFiles = linkedSetOf<File>()
        return try {
            ensureAttemptActive(isAttemptActive)
            val avatar =
                prepareAvatar(
                    remote.authorAvatarUrl,
                    jobDirectory,
                    createdFiles,
                    isAttemptActive,
                )
            val preparedMedia =
                remote.media.mapIndexed { index, item ->
                    ensureAttemptActive(isAttemptActive)
                    val prepared =
                        prepareMedia(item, jobDirectory, createdFiles, isAttemptActive)
                    ensureAttemptActive(isAttemptActive)
                    onProgress(index + 1, remote.media.size)
                    prepared
                }
            PreparedPost(
                sourcePostId = remote.sourcePostId,
                sourceUrl = remote.sourceUrl,
                authorUsername = remote.authorUsername,
                authorDisplayName = remote.authorDisplayName,
                authorProfileUrl = remote.authorProfileUrl,
                authorAvatarSha256 = avatar?.sha256,
                localAvatarPath = avatar?.file?.absolutePath,
                caption = remote.caption,
                publishedAt = remote.publishedAt,
                locationName = remote.locationName,
                media = preparedMedia,
                createdFilePaths = createdFiles.map(File::getAbsolutePath),
            )
        } catch (error: Exception) {
            rollbackFiles(createdFiles.map(File::getAbsolutePath))
            throw error
        }
    }

    fun rollbackPrepared(post: PreparedPost) {
        rollbackFiles(post.createdFilePaths)
    }

    fun cleanupJob(jobId: String) {
        val directory = File(jobs, jobId)
        if (directory.exists()) directory.deleteRecursively()
    }

    fun cleanupStaleParts(now: Long = System.currentTimeMillis()) {
        val cutoff = now - STALE_FILE_AGE_MS
        root.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".part") && file.lastModified() < cutoff) {
                file.delete()
            }
        }
        jobs.listFiles()?.forEach { directory ->
            if (directory.isDirectory && directory.lastModified() < cutoff) {
                directory.deleteRecursively()
            }
        }
    }

    private fun prepareAvatar(
        url: String?,
        jobDirectory: File,
        createdFiles: MutableSet<File>,
        isAttemptActive: () -> Boolean,
    ): StoredFile? {
        if (url.isNullOrBlank()) return null
        return try {
            val downloaded =
                download(
                    url,
                    File(jobDirectory, "avatar.part"),
                    "image",
                    isAttemptActive,
                )
            ensureAttemptActive(isAttemptActive)
            val bitmap = decodeScaledImage(downloaded.file, AVATAR_MAX_EDGE)
                ?: throw ArchiveException("INVALID_RESPONSE", "博主头像无法解析")
            val normalized = File(jobDirectory, "avatar.jpg.part")
            writeJpeg(bitmap, normalized, 90)
            bitmap.recycle()
            storeContentAddressed(normalized, avatars, ".jpg", createdFiles = createdFiles)
        } catch (error: ArchiveAttemptStoppedException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareMedia(
        remote: RemoteMedia,
        jobDirectory: File,
        createdFiles: MutableSet<File>,
        isAttemptActive: () -> Boolean,
    ): PreparedMedia {
        val downloaded =
            download(
                remote.url,
                File(jobDirectory, "media-${remote.sortIndex}.part"),
                remote.mediaType,
                isAttemptActive,
            )
        ensureAttemptActive(isAttemptActive)
        val extension = extensionForMime(downloaded.mimeType, remote.mediaType)
        val original =
            storeContentAddressed(
                downloaded.file,
                originals,
                extension,
                downloaded.sha256,
                createdFiles,
            )
        ensureAttemptActive(isAttemptActive)

        val metadata =
            if (remote.mediaType == "video") {
                videoMetadata(original.file, remote)
            } else {
                imageMetadata(original.file, remote)
            }
        val thumbnailBitmap =
            if (remote.mediaType == "video") {
                videoFrame(original.file)
            } else {
                decodeScaledImage(original.file, THUMBNAIL_MAX_EDGE)
            } ?: throw ArchiveException("THUMBNAIL_FAILED", "媒体缩略图生成失败")
        val scaledThumbnail = scaleBitmap(thumbnailBitmap, THUMBNAIL_MAX_EDGE)
        if (scaledThumbnail !== thumbnailBitmap) thumbnailBitmap.recycle()
        val thumbnailPart = File(jobDirectory, "thumbnail-${remote.sortIndex}.jpg.part")
        writeJpeg(scaledThumbnail, thumbnailPart, 85)
        scaledThumbnail.recycle()
        val thumbnail =
            storeContentAddressed(
                thumbnailPart,
                thumbnails,
                ".jpg",
                createdFiles = createdFiles,
            )
        ensureAttemptActive(isAttemptActive)

        return PreparedMedia(
            sortIndex = remote.sortIndex,
            mediaType = remote.mediaType,
            mimeType = downloaded.mimeType,
            width = metadata.width,
            height = metadata.height,
            durationMs = metadata.durationMs,
            originalSize = original.file.length(),
            originalSha256 = original.sha256,
            thumbnailSha256 = thumbnail.sha256,
            localOriginalPath = original.file.absolutePath,
            localThumbnailPath = thumbnail.file.absolutePath,
        )
    }

    private fun download(
        url: String,
        target: File,
        mediaType: String,
        isAttemptActive: () -> Boolean,
    ): DownloadedFile {
        ensureAttemptActive(isAttemptActive)
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val code =
                    when {
                        status == 429 -> "RATE_LIMITED"
                        status == 408 || status == 425 || status in 500..599 -> "NETWORK_ERROR"
                        else -> "MEDIA_DOWNLOAD_FAILED"
                    }
                throw ArchiveException(
                    code,
                    "Instagram 媒体下载失败（HTTP $status）",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(target, false).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var nextCancellationCheck =
                        System.nanoTime() + CANCELLATION_CHECK_INTERVAL_NANOS
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now >= nextCancellationCheck) {
                            ensureAttemptActive(isAttemptActive)
                            nextCancellationCheck = now + CANCELLATION_CHECK_INTERVAL_NANOS
                        }
                    }
                    ensureAttemptActive(isAttemptActive)
                    output.fd.sync()
                }
            }
            if (target.length() <= 0) {
                throw ArchiveException("MEDIA_DOWNLOAD_FAILED", "Instagram 返回了空媒体文件")
            }
            val mimeType = normalizeMimeType(connection.contentType, mediaType)
            return DownloadedFile(target, digest.digest().toHex(), mimeType)
        } catch (error: ArchiveException) {
            target.delete()
            throw error
        } catch (error: ArchiveAttemptStoppedException) {
            target.delete()
            throw error
        } catch (error: Exception) {
            target.delete()
            throw ArchiveException("NETWORK_ERROR", "媒体下载失败，请检查系统网络或 VPN", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureAttemptActive(isAttemptActive: () -> Boolean) {
        if (!isAttemptActive()) throw ArchiveAttemptStoppedException()
    }

    private fun imageMetadata(file: File, remote: RemoteMedia): MediaMetadata {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return MediaMetadata(
            width = options.outWidth.takeIf { it > 0 } ?: remote.width ?: 1,
            height = options.outHeight.takeIf { it > 0 } ?: remote.height ?: 1,
            durationMs = null,
        )
    }

    private fun videoMetadata(file: File, remote: RemoteMedia): MediaMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val encodedWidth =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.takeIf { it > 0 }
            val encodedHeight =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.takeIf { it > 0 }
            val rotationDegrees =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            val (width, height) =
                if (encodedWidth != null && encodedHeight != null) {
                    displayVideoDimensions(encodedWidth, encodedHeight, rotationDegrees)
                } else {
                    Pair(remote.width ?: 1, remote.height ?: 1)
                }
            MediaMetadata(
                width = width,
                height = height,
                durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0 } ?: remote.durationMs,
            )
        } catch (error: Exception) {
            MediaMetadata(remote.width ?: 1, remote.height ?: 1, remote.durationMs)
        } finally {
            retriever.release()
        }
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (error: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun decodeScaledImage(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = scaleBitmap(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun scaleBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxEdge) return bitmap
        val scale = maxEdge.toDouble() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun writeJpeg(bitmap: Bitmap, target: File, quality: Int) {
        FileOutputStream(target, false).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw ArchiveException("THUMBNAIL_FAILED", "缩略图编码失败")
            }
            output.fd.sync()
        }
    }

    private fun storeContentAddressed(
        part: File,
        directory: File,
        extension: String,
        knownSha256: String? = null,
        createdFiles: MutableSet<File>,
    ): StoredFile {
        val digest = knownSha256 ?: sha256(part)
        val target = File(directory, "$digest$extension")
        if (target.isFile && sha256(target).equals(digest, ignoreCase = true)) {
            part.delete()
            return StoredFile(target, digest)
        }
        if (target.exists() && !target.delete()) {
            throw ArchiveException("MEDIA_STORE_FAILED", "无法替换损坏的本地媒体文件")
        }
        if (!part.renameTo(target)) {
            try {
                FileInputStream(part).use { input ->
                    FileOutputStream(target, false).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                part.delete()
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }
        createdFiles += target
        return StoredFile(target, digest)
    }

    private fun rollbackFiles(paths: Iterable<String>) {
        val managedDirectories =
            setOf(avatars.canonicalFile, thumbnails.canonicalFile, originals.canonicalFile)
        paths.toSet().forEach { path ->
            val file = File(path)
            val sha256 = file.name.substringBefore('.').lowercase()
            if (!SHA256_PATTERN.matches(sha256)) return@forEach
            val parent =
                runCatching { file.parentFile?.canonicalFile }.getOrNull()
                    ?: return@forEach
            if (parent !in managedDirectories) return@forEach
            val referenced = runCatching { isShaReferenced(sha256) }.getOrDefault(true)
            if (!referenced && file.isFile) file.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun normalizeMimeType(contentType: String?, mediaType: String): String {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (normalized.startsWith("image/") || normalized.startsWith("video/")) return normalized
        return if (mediaType == "video") "video/mp4" else "image/jpeg"
    }

    private fun extensionForMime(mimeType: String, mediaType: String): String =
        when (mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/quicktime" -> ".mov"
            "video/webm" -> ".webm"
            "video/mp4" -> ".mp4"
            else -> if (mediaType == "video") ".mp4" else ".jpg"
        }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private data class DownloadedFile(val file: File, val sha256: String, val mimeType: String)

    private data class StoredFile(val file: File, val sha256: String)

    private data class MediaMetadata(val width: Int, val height: Int, val durationMs: Long?)

    companion object {
        private const val THUMBNAIL_MAX_EDGE = 800
        private const val AVATAR_MAX_EDGE = 256
        private const val STALE_FILE_AGE_MS = 24L * 60L * 60L * 1000L
        private const val CANCELLATION_CHECK_INTERVAL_NANOS = 250_000_000L
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

        internal fun displayVideoDimensions(
            width: Int,
            height: Int,
            rotationDegrees: Int,
        ): Pair<Int, Int> {
            require(width > 0 && height > 0) { "视频尺寸必须为正数" }
            val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
            return if (normalizedRotation == 90 || normalizedRotation == 270) {
                Pair(height, width)
            } else {
                Pair(width, height)
            }
        }
    }
}

package com.mantou.photobook.archive

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

class ArchiveMediaActions(
    private val activity: Activity,
    private val database: ArchiveDatabase,
    private val copyOriginal: (File, OutputStream) -> Unit = { file, output ->
        FileInputStream(file).use { input -> input.copyTo(output) }
    },
) {
    private val applicationContext = activity.applicationContext

    fun share(mediaIds: List<String>, onComplete: (Exception?) -> Unit) {
        if (mediaIds.isEmpty()) {
            throw ArchiveException("SHARE_SELECTION_EMPTY", "请至少选择一个媒体")
        }
        val originals = mediaIds.distinct().map(::resolveOriginal)
        val videoCount = originals.count { it.descriptor.mediaType == "video" }
        if (videoCount != 0 && (videoCount != 1 || originals.size != 1)) {
            throw ArchiveException(
                "SHARE_SELECTION_INVALID",
                "图片可以多选，视频每次只能分享一个",
            )
        }

        val uris =
            originals.map { original ->
                try {
                    FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        original.file,
                    )
                } catch (error: IllegalArgumentException) {
                    throw ArchiveException("SHARE_PREPARE_FAILED", "无法准备原媒体分享文件", error)
                }
            }
        val shareIntent =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = originals.single().descriptor.mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.single())
                    clipData = ClipData.newUri(activity.contentResolver, "PhotoBook", uris.single())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    clipData =
                        ClipData.newUri(activity.contentResolver, "PhotoBook", uris.first()).also {
                            clipData -> uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
                        }
                }
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.runOnUiThread {
            try {
                startChooser(Intent.createChooser(shareIntent, "分享原媒体"))
                onComplete(null)
            } catch (error: Exception) {
                onComplete(error)
            }
        }
    }

    fun save(mediaId: String): String {
        val original = resolveOriginal(mediaId)
        val displayName =
            "PhotoBook_${original.descriptor.sourcePostId}_${original.descriptor.sortIndex + 1}" +
                extensionForMime(original.descriptor.mimeType)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(original, displayName)
            } else {
                saveLegacy(original, displayName)
            }
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("GALLERY_SAVE_FAILED", "保存到系统相册失败", error)
        }
    }

    private fun resolveOriginal(mediaId: String): ResolvedOriginal {
        val descriptor =
            database.originalDescriptor(mediaId)
                ?: throw ArchiveException("MEDIA_NOT_FOUND", "媒体不存在或已被删除")
        val file = R2SyncEngine(applicationContext, database).ensureOriginal(mediaId)
        return ResolvedOriginal(descriptor, file)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(original: ResolvedOriginal, displayName: String): String {
        val isVideo = original.descriptor.mediaType == "video"
        val collection =
            if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        val relativeDirectory =
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, original.descriptor.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDirectory/PhotoBook")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = activity.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw ArchiveException("GALLERY_SAVE_FAILED", "无法创建系统相册文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                copyOriginal(original.file, output)
            } ?: throw ArchiveException("GALLERY_SAVE_FAILED", "无法写入系统相册文件")
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (published <= 0) {
                throw ArchiveException("GALLERY_SAVE_FAILED", "无法发布系统相册文件")
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return displayName
    }

    private fun startChooser(chooser: Intent) {
        try {
            activity.startActivity(chooser)
        } catch (error: ActivityNotFoundException) {
            throw ArchiveException("SHARE_UNAVAILABLE", "没有可用的分享应用", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(original: ResolvedOriginal, displayName: String): String {
        val publicDirectory =
            Environment.getExternalStoragePublicDirectory(
                if (original.descriptor.mediaType == "video") {
                    Environment.DIRECTORY_MOVIES
                } else {
                    Environment.DIRECTORY_PICTURES
                },
            )
        val directory = File(publicDirectory, "PhotoBook")
        if (!directory.exists() && !directory.mkdirs()) {
            throw ArchiveException("GALLERY_SAVE_FAILED", "无法创建 PhotoBook 相册目录")
        }
        val target = uniqueTarget(directory, displayName)
        try {
            FileOutputStream(target, false).use { output ->
                copyOriginal(original.file, output)
                output.fd.sync()
            }
            MediaScannerConnection.scanFile(
                activity,
                arrayOf(target.absolutePath),
                arrayOf(original.descriptor.mimeType),
                null,
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        return target.name
    }

    private fun uniqueTarget(directory: File, displayName: String): File {
        val preferred = File(directory, displayName)
        if (!preferred.exists()) return preferred
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        val stem = if (extension.isEmpty()) displayName else displayName.removeSuffix(".$extension")
        var suffix = 2
        while (true) {
            val candidateName =
                if (extension.isEmpty()) "${stem}_$suffix" else "${stem}_$suffix.$extension"
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) return candidate
            suffix += 1
        }
    }

    private fun extensionForMime(mimeType: String): String =
        when (mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/quicktime" -> ".mov"
            "video/webm" -> ".webm"
            "video/mp4" -> ".mp4"
            else -> if (mimeType.startsWith("video/", ignoreCase = true)) ".mp4" else ".jpg"
        }

    private data class ResolvedOriginal(
        val descriptor: OriginalMediaDescriptor,
        val file: File,
    )

    companion object {
        fun requiresLegacyStoragePermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }
}

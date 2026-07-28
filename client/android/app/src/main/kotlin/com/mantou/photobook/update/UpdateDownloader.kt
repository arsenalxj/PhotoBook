package com.mantou.photobook.update

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class UpdateDownloader(context: Context) {
    private val updateDirectory = File(context.cacheDir, UPDATE_DIRECTORY)
    private val downloading = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    init {
        cleanupStaleFiles()
    }

    fun download(spec: UpdateDownloadSpec): File {
        if (!downloading.compareAndSet(false, true)) {
            throw UpdateException("UPDATE_BUSY", "已有更新正在下载")
        }
        cancelled.set(false)
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            downloading.set(false)
            throw UpdateException("UPDATE_STORAGE", "无法创建更新缓存目录")
        }
        val finalFile = resolveFile(spec.fileName)
        val partFile = resolveFile("${spec.fileName}.part")
        try {
            if (UpdateFileIntegrity.verify(finalFile, spec.size, spec.sha256)) {
                UpdateEventBus.emitDownloadProgress(spec.size, spec.size)
                return finalFile
            }
            finalFile.delete()
            partFile.delete()
            streamToPart(spec, partFile)
            publish(partFile, finalFile)
            return finalFile
        } catch (error: Exception) {
            partFile.delete()
            if (error is UpdateException) throw error
            throw UpdateException("UPDATE_DOWNLOAD", "更新下载失败，请稍后重试", error)
        } finally {
            activeConnection?.disconnect()
            activeConnection = null
            cancelled.set(false)
            downloading.set(false)
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeConnection?.disconnect()
    }

    private fun streamToPart(spec: UpdateDownloadSpec, partFile: File) {
        val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.android.package-archive")
        }
        activeConnection = connection
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw UpdateException("UPDATE_HTTP", "更新下载失败：HTTP $responseCode")
        }
        val contentLength = connection.contentLengthLong
        if (contentLength > 0L && contentLength != spec.size) {
            throw UpdateException("UPDATE_SIZE", "更新文件大小与清单不一致")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var lastProgressAt = 0L
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled.get()) throw UpdateException("UPDATE_CANCELLED", "更新下载已取消")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    received += read
                    if (received > spec.size) {
                        throw UpdateException("UPDATE_SIZE", "更新文件大小与清单不一致")
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        UpdateEventBus.emitDownloadProgress(received, spec.size)
                        lastProgressAt = now
                    }
                }
                output.flush()
                output.fd.sync()
            }
        }
        if (cancelled.get()) throw UpdateException("UPDATE_CANCELLED", "更新下载已取消")
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (received != spec.size) throw UpdateException("UPDATE_SIZE", "更新文件大小与清单不一致")
        if (actualSha256 != spec.sha256) throw UpdateException("UPDATE_HASH", "更新文件校验失败")
        UpdateEventBus.emitDownloadProgress(received, spec.size)
    }

    private fun publish(partFile: File, finalFile: File) {
        if (finalFile.exists() && !finalFile.delete()) {
            throw UpdateException("UPDATE_STORAGE", "无法替换旧更新文件")
        }
        if (!partFile.renameTo(finalFile)) {
            throw UpdateException("UPDATE_STORAGE", "无法发布已校验的更新文件")
        }
    }

    private fun resolveFile(name: String): File {
        val file = File(updateDirectory, name)
        if (file.canonicalFile.parentFile != updateDirectory.canonicalFile) {
            throw UpdateException("UPDATE_STORAGE", "更新文件路径无效")
        }
        return file
    }

    private fun cleanupStaleFiles() {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
        updateDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    companion object {
        const val UPDATE_DIRECTORY = "updates"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val STALE_FILE_AGE_MS = 24L * 60L * 60L * 1000L
    }
}

package com.mantou.photobook.update

import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest

internal class UpdateException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    constructor(message: String) : this("UPDATE_INVALID", message)
}

internal data class UpdateDownloadSpec(
    val downloadUrl: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val versionCode: Long,
) {
    companion object {
        private val assetPattern =
            Regex("^photobook-(v[0-9]+\\.[0-9]+\\.[0-9]+\\+([1-9][0-9]*))-arm64-v8a\\.apk$")

        fun fromMap(arguments: Map<*, *>): UpdateDownloadSpec {
            val downloadUrl = arguments.requiredString("downloadUrl")
            val fileName = arguments.requiredString("fileName")
            val size = arguments.requiredLong("size")
            val sha256 = arguments.requiredString("sha256")
            val versionCode = arguments.requiredLong("versionCode")
            val match = assetPattern.matchEntire(fileName)
                ?: throw UpdateException("更新文件名无效")
            val tag = match.groupValues[1]
            val tagVersionCode = match.groupValues[2].toLongOrNull()
            if (size <= 0L || versionCode <= 0L || tagVersionCode != versionCode) {
                throw UpdateException("更新版本或文件大小无效")
            }
            if (!sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                throw UpdateException("更新文件 SHA-256 无效")
            }
            validateDownloadUrl(downloadUrl, tag, fileName)
            return UpdateDownloadSpec(downloadUrl, fileName, size, sha256, versionCode)
        }

        private fun validateDownloadUrl(
            value: String,
            tag: String,
            fileName: String,
        ) {
            val uri = runCatching { URI(value) }.getOrNull()
                ?: throw UpdateException("更新下载地址无效")
            if (uri.scheme != "https" ||
                uri.host != "github.com" ||
                uri.port != -1 ||
                uri.userInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null
            ) {
                throw UpdateException("更新下载地址不可信")
            }
            val expected =
                listOf("arsenalxj", "PhotoBook", "releases", "download", tag, fileName)
            val segments = uri.path.split('/').filter(String::isNotEmpty)
            if (segments != expected) throw UpdateException("更新下载地址不可信")
        }
    }
}

internal data class UpdateInstallSpec(
    val path: String,
    val size: Long,
    val sha256: String,
    val versionCode: Long,
) {
    companion object {
        fun fromMap(arguments: Map<*, *>): UpdateInstallSpec {
            val path = arguments.requiredString("path")
            val size = arguments.requiredLong("size")
            val sha256 = arguments.requiredString("sha256")
            val versionCode = arguments.requiredLong("versionCode")
            if (size <= 0L || versionCode <= 0L) {
                throw UpdateException("更新安装参数无效")
            }
            if (!sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                throw UpdateException("更新文件 SHA-256 无效")
            }
            return UpdateInstallSpec(path, size, sha256, versionCode)
        }
    }
}

internal object UpdateFileIntegrity {
    fun verify(file: File, expectedSize: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        return sha256(file) == expectedSha256
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private fun Map<*, *>.requiredString(key: String): String {
    val value = this[key] as? String
    if (value.isNullOrBlank()) throw UpdateException("更新参数 $key 无效")
    return value
}

private fun Map<*, *>.requiredLong(key: String): Long {
    val value = (this[key] as? Number)?.toLong()
        ?: throw UpdateException("更新参数 $key 无效")
    return value
}

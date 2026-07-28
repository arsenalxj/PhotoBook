package com.mantou.photobook.archive

import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.UploadObjectArgs
import io.minio.errors.ErrorResponseException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

interface R2Store {
    fun testConnection()

    fun putJson(objectKey: String, json: String)

    fun putImmutableJson(objectKey: String, json: String)

    fun readJson(objectKey: String): String

    fun uploadFileIfMissing(objectKey: String, file: File, contentType: String)

    fun downloadTo(objectKey: String, target: File)

    fun exists(objectKey: String): Boolean

    fun listDeviceIds(): List<String>

    fun remove(objectKey: String)

    fun key(relative: String): String
}

class R2ObjectStore(private val config: R2Config) : R2Store {
    private val client =
        MinioClient.builder()
            .endpoint(config.endpoint)
            .region("auto")
            .credentials(config.accessKeyId, config.secretAccessKey)
            .build()

    override fun testConnection() {
        client.listObjects(
            ListObjectsArgs.builder()
                .bucket(config.bucket)
                .prefix("${config.basePrefix}/")
                .maxKeys(1)
                .build(),
        ).iterator().let { iterator -> if (iterator.hasNext()) iterator.next().get() }

        val key = key("system/probe/${UUID.randomUUID()}.txt")
        val body = "photobook-r2-probe".toByteArray(Charsets.UTF_8)
        var uploaded = false
        try {
            putBytes(key, body, "text/plain")
            uploaded = true
            val downloaded = readBytes(key)
            check(body.contentEquals(downloaded)) { "R2 测试对象内容不一致" }
        } finally {
            if (uploaded) remove(key)
        }
    }

    override fun putJson(objectKey: String, json: String) {
        putBytes(objectKey, json.toByteArray(Charsets.UTF_8), "application/json")
    }

    override fun putImmutableJson(objectKey: String, json: String) {
        if (exists(objectKey)) {
            if (readJson(objectKey) != json) {
                throw ArchiveException("SYNC_CONFLICT", "R2 中存在内容不同的同序号操作")
            }
            return
        }
        putJson(objectKey, json)
    }

    override fun readJson(objectKey: String): String = readBytes(objectKey).toString(Charsets.UTF_8)

    override fun uploadFileIfMissing(objectKey: String, file: File, contentType: String) {
        if (exists(objectKey)) return
        require(file.isFile) { "待上传媒体不存在" }
        client.uploadObject(
            UploadObjectArgs.builder()
                .bucket(config.bucket)
                .`object`(validateKey(objectKey))
                .filename(file.absolutePath)
                .contentType(contentType)
                .build(),
        )
    }

    override fun downloadTo(objectKey: String, target: File) {
        target.parentFile?.mkdirs()
        client.getObject(
            GetObjectArgs.builder()
                .bucket(config.bucket)
                .`object`(validateKey(objectKey))
                .build(),
        ).use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    override fun exists(objectKey: String): Boolean =
        try {
            client.statObject(
                StatObjectArgs.builder()
                    .bucket(config.bucket)
                    .`object`(validateKey(objectKey))
                    .build(),
            )
            true
        } catch (error: ErrorResponseException) {
            if (error.errorResponse().code() in NOT_FOUND_CODES) false else throw error
        }

    override fun listDeviceIds(): List<String> {
        val prefix = key("devices/index/")
        return client.listObjects(
            ListObjectsArgs.builder()
                .bucket(config.bucket)
                .prefix(prefix)
                .recursive(true)
                .build(),
        ).mapNotNull { result ->
            val objectName = result.get().objectName()
            if (!objectName.startsWith(prefix) || !objectName.endsWith(".json")) return@mapNotNull null
            objectName.removePrefix(prefix).removeSuffix(".json")
                .takeIf { DEVICE_ID_PATTERN.matches(it) }
        }.distinct().sorted()
    }

    override fun remove(objectKey: String) {
        client.removeObject(
            RemoveObjectArgs.builder()
                .bucket(config.bucket)
                .`object`(validateKey(objectKey))
                .build(),
        )
    }

    override fun key(relative: String): String {
        val normalized = relative.trimStart('/')
        require(normalized.isNotEmpty() && !normalized.split('/').any { it == ".." }) {
            "R2 object key 无效"
        }
        return "${config.basePrefix}/$normalized"
    }

    private fun putBytes(objectKey: String, body: ByteArray, contentType: String) {
        ByteArrayInputStream(body).use { input ->
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(config.bucket)
                    .`object`(validateKey(objectKey))
                    .stream(input, body.size.toLong(), -1)
                    .contentType(contentType)
                    .build(),
            )
        }
    }

    private fun readBytes(objectKey: String): ByteArray =
        client.getObject(
            GetObjectArgs.builder()
                .bucket(config.bucket)
                .`object`(validateKey(objectKey))
                .build(),
        ).use { it.readBytes() }

    private fun validateKey(objectKey: String): String {
        require(
            objectKey.startsWith("${config.basePrefix}/") &&
                !objectKey.startsWith('/') &&
                !objectKey.split('/').any { it == ".." },
        ) {
            "R2 object key 超出当前资料库"
        }
        return objectKey
    }

    companion object {
        private val NOT_FOUND_CODES = setOf("NoSuchKey", "NoSuchObject", "NotFound", "404")
        private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}

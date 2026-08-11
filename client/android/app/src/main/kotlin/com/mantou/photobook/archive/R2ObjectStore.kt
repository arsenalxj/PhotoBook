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
    fun putJson(objectKey: String, json: String)

    fun putImmutableJson(objectKey: String, json: String)

    fun uploadFileIfMissing(
        objectKey: String,
        file: File,
        contentType: String,
        expectedSha256: String,
    )

    fun downloadTo(objectKey: String, target: File)

    fun key(relative: String): String
}

class R2ObjectStore(private val config: R2Config) : R2Store {
    private val client =
        MinioClient.builder()
            .endpoint(config.endpoint)
            .region("auto")
            .credentials(config.accessKeyId, config.secretAccessKey)
            .build()

    fun testConnection() {
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
            if (uploaded) removeObject(key)
        }
    }

    override fun putJson(objectKey: String, json: String) {
        putBytes(objectKey, json.toByteArray(Charsets.UTF_8), "application/json")
    }

    override fun putImmutableJson(objectKey: String, json: String) {
        if (exists(objectKey)) {
            if (readJson(objectKey) != json) {
                throw ArchiveException("BACKUP_CONFLICT", "R2 中存在内容不同的同序号备份")
            }
            return
        }
        putJson(objectKey, json)
    }

    override fun uploadFileIfMissing(
        objectKey: String,
        file: File,
        contentType: String,
        expectedSha256: String,
    ) {
        require(file.isFile) { "待上传媒体不存在" }
        val validatedKey = validateKey(objectKey)
        objectMetadata(validatedKey)?.let { existing ->
            validateExistingBackupMediaObject(
                existingSize = existing.size,
                existingSha256 = existing.sha256,
                expectedSize = file.length(),
                expectedSha256 = expectedSha256,
            )
            return
        }
        client.uploadObject(
            UploadObjectArgs.builder()
                .bucket(config.bucket)
                .`object`(validatedKey)
                .filename(file.absolutePath)
                .contentType(contentType)
                .userMetadata(mapOf(MEDIA_SHA256_METADATA_KEY to expectedSha256))
                .build(),
        )
        val uploaded =
            objectMetadata(validatedKey)
                ?: throw ArchiveException("BACKUP_UPLOAD_INCOMPLETE", "R2 媒体上传后无法确认")
        validateExistingBackupMediaObject(
            existingSize = uploaded.size,
            existingSha256 = uploaded.sha256,
            expectedSize = file.length(),
            expectedSha256 = expectedSha256,
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

    private fun exists(objectKey: String): Boolean = objectMetadata(objectKey) != null

    private fun objectMetadata(objectKey: String): R2ObjectMetadata? =
        try {
            val response = client.statObject(
                StatObjectArgs.builder()
                    .bucket(config.bucket)
                    .`object`(validateKey(objectKey))
                    .build(),
            )
            R2ObjectMetadata(
                size = response.size(),
                sha256 = response.userMetadata().getFirst(MEDIA_SHA256_METADATA_KEY),
            )
        } catch (error: ErrorResponseException) {
            if (error.errorResponse().code() in NOT_FOUND_CODES) null else throw error
        }

    private fun removeObject(objectKey: String) {
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

    private fun readJson(objectKey: String): String = readBytes(objectKey).toString(Charsets.UTF_8)

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
        private const val MEDIA_SHA256_METADATA_KEY = "photobook-sha256"
        private val NOT_FOUND_CODES = setOf("NoSuchKey", "NoSuchObject", "NotFound", "404")
    }
}

private data class R2ObjectMetadata(
    val size: Long,
    val sha256: String?,
)

internal fun validateExistingBackupMediaObject(
    existingSize: Long,
    existingSha256: String?,
    expectedSize: Long,
    expectedSha256: String,
) {
    if (existingSize != expectedSize || existingSha256 != expectedSha256) {
        throw ArchiveException("BACKUP_CONFLICT", "R2 中存在内容或校验信息不一致的同哈希媒体")
    }
}

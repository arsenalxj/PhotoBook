package com.mantou.photobook.archive

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

data class R2SyncResult(
    val error: String? = null,
    val hasRemainingWork: Boolean = false,
    val shouldRetry: Boolean = false,
    val retryDelay: Long? = null,
)

class R2SyncEngine(
    context: Context,
    private val database: ArchiveDatabase,
    configProvider: (() -> R2Config?)? = null,
    migrationSourceProvider: (() -> R2Config?)? = null,
    private val storeFactory: (R2Config) -> R2Store = { R2ObjectStore(it) },
    deviceId: String? = null,
    private val maxLocalOpsPerBatch: Int = DEFAULT_MAX_LOCAL_OPS_PER_BATCH,
    private val maxRemoteOpsPerPeer: Int = DEFAULT_MAX_REMOTE_OPS_PER_PEER,
    private val previewBatchSize: Int = DEFAULT_PREVIEW_BATCH_SIZE,
    migrationSourceClearer: (() -> Unit)? = null,
    private val archiveChangedEmitter: () -> Unit = { ArchiveEventBus.emitArchiveChanged() },
) {
    private val applicationContext = context.applicationContext
    private val configStore = R2ConfigStore(applicationContext)
    private val configProvider = configProvider ?: configStore::read
    private val migrationSourceProvider =
        migrationSourceProvider ?: configStore::readMigrationSource
    private val migrationSourceClearer =
        migrationSourceClearer ?: configStore::clearMigrationSource
    private val deviceId = deviceId ?: DeviceIdentity(applicationContext).getOrCreate()
    private val archiveRoot = File(applicationContext.filesDir, "archive")

    init {
        require(maxLocalOpsPerBatch > 0) { "R2 单批上传操作数必须大于 0" }
        require(maxRemoteOpsPerPeer > 0) { "R2 单批操作数必须大于 0" }
        require(previewBatchSize > 0) { "R2 单批预览数必须大于 0" }
    }

    fun syncIfConfigured(): R2SyncResult {
        val config = configProvider() ?: return R2SyncResult()
        return try {
            database.seedRepositoryIfNeeded(config.repositoryId, deviceId)
            val store = storeFactory(config)
            val migrationConfig =
                migrationSourceProvider()?.takeIf { it.repositoryId != config.repositoryId }
            val migrationStore = migrationConfig?.let(storeFactory)
            uploadLocalOperations(config, store, migrationStore)
            val remoteRemaining = pullRemoteOperations(config, store)
            val previewRemaining = downloadMissingPreviews(store)
            val uploadRemaining =
                database.hasPendingSyncOperations(config.repositoryId, deviceId)
            val hasRemainingWork = uploadRemaining || remoteRemaining || previewRemaining
            if (!hasRemainingWork && migrationConfig != null) migrationSourceClearer()
            database.writeSyncResult(null)
            R2SyncResult(
                hasRemainingWork = hasRemainingWork,
                shouldRetry = hasRemainingWork,
                retryDelay = if (hasRemainingWork) CONTINUATION_DELAY_MS else null,
            )
        } catch (error: Exception) {
            val message = syncErrorMessage(error)
            database.writeSyncResult(message)
            R2SyncResult(
                error = message,
                hasRemainingWork = true,
                shouldRetry = true,
                retryDelay = ERROR_RETRY_DELAY_MS,
            )
        }
    }

    fun ensureOriginal(mediaId: String): File {
        val descriptor =
            database.originalDescriptor(mediaId)
                ?: throw ArchiveException("MEDIA_NOT_FOUND", "媒体记录不存在")
        descriptor.localPath?.let { path ->
            val existing = File(path)
            if (isValid(existing, descriptor.sha256, descriptor.expectedSize)) return existing
        }
        val config = configProvider()
            ?: throw ArchiveException("R2_NOT_CONFIGURED", "尚未配置 R2 同步")
        val relativeKey =
            "media/originals/${descriptor.sha256}${extensionForMime(descriptor.mimeType)}"
        val target = File(archiveRoot, "originals/${descriptor.sha256}${extensionForMime(descriptor.mimeType)}")
        val store = storeFactory(config)
        try {
            downloadValidated(store, relativeKey, target, descriptor.sha256, descriptor.expectedSize)
        } catch (currentError: Exception) {
            val migrationConfig =
                migrationSourceProvider()?.takeIf { it.repositoryId != config.repositoryId }
                    ?: throw currentError
            val migrationStore = storeFactory(migrationConfig)
            downloadValidated(
                migrationStore,
                relativeKey,
                target,
                descriptor.sha256,
                descriptor.expectedSize,
            )
            store.uploadFileIfMissing(store.key(relativeKey), target, descriptor.mimeType)
        }
        database.updateOriginalPath(mediaId, descriptor.sha256, target.absolutePath)
        return target
    }

    private fun uploadLocalOperations(
        config: R2Config,
        store: R2Store,
        migrationStore: R2Store?,
    ) {
        val operations =
            database.listPendingSyncOperations(
                config.repositoryId,
                deviceId,
                maxLocalOpsPerBatch,
            )
        for (operation in operations) {
            try {
                when (operation.operation) {
                    "upsert_post" -> uploadPostFiles(store, migrationStore, operation.payloadJson)
                    "delete_post", "delete_media" -> Unit
                    else -> throw IllegalArgumentException("未知本地同步操作")
                }
                val sequence = operation.seq.toString().padStart(20, '0')
                val operationKey = store.key("devices/$deviceId/ops/$sequence.json")
                store.putImmutableJson(operationKey, operation.payloadJson)
                val now = System.currentTimeMillis()
                store.putJson(
                    store.key("devices/index/$deviceId.json"),
                    JSONObject()
                        .put("deviceId", deviceId)
                        .put("updatedAt", now)
                        .toString(),
                )
                store.putJson(
                    store.key("devices/$deviceId/manifest.json"),
                    JSONObject()
                        .put("deviceId", deviceId)
                        .put("lastSeq", operation.seq)
                        .put("updatedAt", now)
                        .toString(),
                )
                database.markSyncOperationUploaded(config.repositoryId, deviceId, operation.seq)
            } catch (error: Exception) {
                database.markSyncOperationError(
                    config.repositoryId,
                    deviceId,
                    operation.seq,
                    syncErrorMessage(error),
                )
                throw error
            }
        }
    }

    private fun uploadPostFiles(
        store: R2Store,
        migrationStore: R2Store?,
        payloadJson: String,
    ) {
        val payload = JSONObject(payloadJson).getJSONObject("payload")
        val post = payload.getJSONObject("post")
        if (!post.isNull("authorAvatarSha256")) {
            val sha256 = post.getString("authorAvatarSha256")
            uploadContent(
                store = store,
                migrationStore = migrationStore,
                relativeKey = "media/avatars/$sha256.jpg",
                localFile = File(archiveRoot, "avatars/$sha256.jpg"),
                sha256 = sha256,
                expectedSize = null,
                contentType = "image/jpeg",
            )
        }
        val media = payload.getJSONArray("media")
        for (index in 0 until media.length()) {
            val item = media.getJSONObject(index)
            val mimeType = item.getString("mimeType")
            val originalSha = item.getString("originalSha256")
            val extension = extensionForMime(mimeType)
            uploadContent(
                store = store,
                migrationStore = migrationStore,
                relativeKey = "media/originals/$originalSha$extension",
                localFile = File(archiveRoot, "originals/$originalSha$extension"),
                sha256 = originalSha,
                expectedSize = item.getLong("originalSize"),
                contentType = mimeType,
            )
            val thumbnailSha = item.getString("thumbnailSha256")
            uploadContent(
                store = store,
                migrationStore = migrationStore,
                relativeKey = "media/thumbnails/$thumbnailSha.jpg",
                localFile = File(archiveRoot, "thumbnails/$thumbnailSha.jpg"),
                sha256 = thumbnailSha,
                expectedSize = null,
                contentType = "image/jpeg",
            )
        }
    }

    private fun uploadContent(
        store: R2Store,
        migrationStore: R2Store?,
        relativeKey: String,
        localFile: File,
        sha256: String,
        expectedSize: Long?,
        contentType: String,
    ) {
        val objectKey = store.key(relativeKey)
        if (store.exists(objectKey)) return
        if (!isValid(localFile, sha256, expectedSize)) {
            if (migrationStore == null) {
                throw ArchiveException("SYNC_MEDIA_MISSING", "本地媒体文件缺失，无法上传 R2")
            }
            downloadValidated(migrationStore, relativeKey, localFile, sha256, expectedSize)
        }
        store.uploadFileIfMissing(objectKey, localFile, contentType)
    }

    private fun pullRemoteOperations(config: R2Config, store: R2Store): Boolean {
        var hasRemainingWork = false
        var appliedAny = false
        for (peerDeviceId in store.listDeviceIds()) {
            if (peerDeviceId == deviceId) continue
            val manifestKey = store.key("devices/$peerDeviceId/manifest.json")
            if (!store.exists(manifestKey)) continue
            val manifest = JSONObject(store.readJson(manifestKey))
            require(manifest.getString("deviceId") == peerDeviceId) { "设备 manifest 标识不匹配" }
            val lastSeq = manifest.getLong("lastSeq")
            require(lastSeq >= 0) { "设备 manifest 序号无效" }

            var highWater = database.peerHighWater(config.repositoryId, peerDeviceId)
            var applied = 0
            while (highWater < lastSeq && applied < maxRemoteOpsPerPeer) {
                val expected = highWater + 1
                val sequence = expected.toString().padStart(20, '0')
                val operationKey = store.key("devices/$peerDeviceId/ops/$sequence.json")
                if (!store.exists(operationKey)) {
                    throw ArchiveException(
                        "SYNC_GAP",
                        "设备 $peerDeviceId 缺少同步序号 $expected，已停止推进",
                    )
                }
                database.applyRemoteOperation(
                    config.repositoryId,
                    peerDeviceId,
                    expected,
                    store.readJson(operationKey),
                )
                highWater = expected
                applied += 1
                appliedAny = true
            }
            if (highWater < lastSeq) hasRemainingWork = true
        }
        if (appliedAny) archiveChangedEmitter()
        return hasRemainingWork
    }

    private fun downloadMissingPreviews(store: R2Store): Boolean {
        val pending = database.listMissingPreviews(previewBatchSize + 1)
        var firstError: Exception? = null
        for (preview in pending.take(previewBatchSize)) {
            val relativeKey =
                if (preview.kind == "avatar") {
                    "media/avatars/${preview.sha256}.jpg"
                } else {
                    "media/thumbnails/${preview.sha256}.jpg"
                }
            val directory = if (preview.kind == "avatar") "avatars" else "thumbnails"
            val target = File(archiveRoot, "$directory/${preview.sha256}.jpg")
            try {
                if (!isValid(target, preview.sha256, null)) {
                    downloadValidated(store, relativeKey, target, preview.sha256, null)
                }
                database.updatePreviewPath(preview, target.absolutePath)
            } catch (error: Exception) {
                if (firstError == null) firstError = error
            }
        }
        firstError?.let {
            throw ArchiveException("SYNC_PREVIEW_FAILED", "R2 预览图补齐失败，请稍后重试", it)
        }
        return pending.size > previewBatchSize || database.listMissingPreviews(1).isNotEmpty()
    }

    private fun downloadValidated(
        store: R2Store,
        relativeKey: String,
        target: File,
        sha256: String,
        expectedSize: Long?,
    ) {
        val part = File("${target.absolutePath}.part")
        try {
            store.downloadTo(store.key(relativeKey), part)
            if (!isValid(part, sha256, expectedSize)) {
                throw ArchiveException("MEDIA_CHECKSUM_MISMATCH", "R2 媒体大小或校验值不匹配")
            }
            publish(part, target)
        } catch (error: Exception) {
            part.delete()
            throw error
        }
    }

    private fun isValid(file: File, expectedSha256: String, expectedSize: Long?): Boolean {
        if (!file.isFile) return false
        if (expectedSize != null && file.length() != expectedSize) return false
        return sha256(file).equals(expectedSha256, ignoreCase = true)
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publish(part: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            FileInputStream(part).use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output) }
            }
            part.delete()
        }
    }

    private fun extensionForMime(mimeType: String): String =
        when (mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "video/quicktime" -> ".mov"
            "video/webm" -> ".webm"
            "video/mp4" -> ".mp4"
            else -> if (mimeType.startsWith("video/", ignoreCase = true)) ".mp4" else ".jpg"
        }

    private fun syncErrorMessage(error: Exception): String =
        when (error) {
            is ArchiveException -> error.message
            is IllegalArgumentException -> error.message ?: "R2 同步数据无效"
            else -> "R2 同步失败，请检查配置、网络和 bucket 权限"
        }

    companion object {
        private const val DEFAULT_MAX_LOCAL_OPS_PER_BATCH = 25
        private const val DEFAULT_MAX_REMOTE_OPS_PER_PEER = 1000
        private const val DEFAULT_PREVIEW_BATCH_SIZE = 100
        private const val CONTINUATION_DELAY_MS = 0L
        private const val ERROR_RETRY_DELAY_MS = 15L * 60L * 1000L
    }
}

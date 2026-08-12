package com.mantou.photobook.archive

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

data class R2BackupResult(
    val error: String? = null,
    val hasRemainingWork: Boolean = false,
    val shouldRetry: Boolean = false,
    val retryDelay: Long? = null,
)

class R2BackupEngine(
    context: Context,
    private val database: ArchiveDatabase,
    settingsProvider: (() -> R2Settings)? = null,
    private val storeFactory: (R2Config) -> R2Store = { R2ObjectStore(it) },
    deviceInfo: DeviceInfo? = null,
    private val maxJobsPerBatch: Int = DEFAULT_MAX_JOBS_PER_BATCH,
    private val archiveChangedEmitter: () -> Unit = { ArchiveEventBus.emitArchiveChanged() },
) {
    private val applicationContext = context.applicationContext
    private val configStore = R2ConfigStore(applicationContext)
    private val settingsProvider = settingsProvider ?: configStore::read
    private val deviceInfo = deviceInfo ?: DeviceIdentity(applicationContext).getOrCreateInfo()
    private val archiveRoot = File(applicationContext.filesDir, "archive")

    init {
        require(maxJobsPerBatch > 0) { "R2 单批备份任务数必须大于 0" }
    }

    fun backupPending(): R2BackupResult {
        val targetIds = database.listPendingBackupTargetIds(deviceInfo.deviceId)
        if (targetIds.isEmpty()) {
            database.writeBackupResult(null)
            return R2BackupResult()
        }
        val settings =
            try {
                settingsProvider()
            } catch (error: Exception) {
                val message = backupErrorMessage(error)
                database.markPendingBackupErrors(deviceInfo.deviceId, message)
                database.writeBackupResult(message)
                return R2BackupResult(
                    error = message,
                    hasRemainingWork = true,
                    shouldRetry = true,
                    retryDelay = ERROR_RETRY_DELAY_MS,
                )
            }
        var remainingCapacity = maxJobsPerBatch
        var completedAny = false
        var firstError: String? = null
        for (targetId in targetIds) {
            if (remainingCapacity <= 0) break
            val config = settings.resolve(targetId)
            if (config == null) {
                val message = "R2 备份位置配置不可用，请重新配置或删除该位置"
                database.markPendingBackupErrors(targetId, deviceInfo.deviceId, message)
                if (firstError == null) firstError = message
                continue
            }
            val jobs =
                database.listPendingBackupJobs(
                    targetId,
                    deviceInfo.deviceId,
                    remainingCapacity,
                )
            if (jobs.isEmpty()) continue
            val store = storeFactory(config)
            if (database.clearBackupError(jobs.first().backupSeq)) {
                archiveChangedEmitter()
            }
            try {
                uploadDeviceDescriptor(store)
                for ((index, job) in jobs.withIndex()) {
                    try {
                        if (index > 0 && database.clearBackupError(job.backupSeq)) {
                            archiveChangedEmitter()
                        }
                        uploadJob(store, job)
                        database.markBackupCompleted(job.backupSeq)
                        completedAny = true
                        remainingCapacity -= 1
                    } catch (error: Exception) {
                        val message = backupErrorMessage(error)
                        database.markBackupError(job.backupSeq, message)
                        if (firstError == null) firstError = message
                        break
                    }
                }
            } catch (error: Exception) {
                val message = backupErrorMessage(error)
                database.markBackupError(jobs.first().backupSeq, message)
                if (firstError == null) firstError = message
            }
        }
        val hasRemainingWork = database.hasPendingBackupJobs(deviceInfo.deviceId)
        database.writeBackupResult(firstError)
        if (completedAny) archiveChangedEmitter()
        return R2BackupResult(
            error = firstError,
            hasRemainingWork = hasRemainingWork,
            shouldRetry = hasRemainingWork,
            retryDelay = when {
                !hasRemainingWork -> null
                firstError != null -> ERROR_RETRY_DELAY_MS
                else -> CONTINUATION_DELAY_MS
            },
        )
    }

    fun ensureOriginal(mediaId: String): File {
        val descriptor =
            database.originalDescriptor(mediaId)
                ?: throw ArchiveException("MEDIA_NOT_FOUND", "媒体记录不存在")
        descriptor.localPath?.let { path ->
            val existing = File(path)
            if (isValid(existing, descriptor.sha256, descriptor.expectedSize)) return existing
        }
        val extension = extensionForMime(descriptor.mimeType)
        val relativeKey =
            "devices/${deviceInfo.deviceId}/media/originals/${descriptor.sha256}$extension"
        val target = File(archiveRoot, "originals/${descriptor.sha256}$extension")
        val settings = settingsProvider()
        val configs =
            database.completedBackupTargetIds(descriptor.postId, deviceInfo.deviceId)
                .mapNotNull(settings::resolve)
        if (configs.isEmpty()) {
            throw ArchiveException("R2_NOT_CONFIGURED", "没有可用于恢复该媒体的 R2 备份位置")
        }
        var lastError: Exception? = null
        for (config in configs) {
            try {
                downloadValidated(
                    storeFactory(config),
                    relativeKey,
                    target,
                    descriptor.sha256,
                    descriptor.expectedSize,
                )
                database.updateOriginalPath(mediaId, descriptor.sha256, target.absolutePath)
                return target
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw ArchiveException(
            "MEDIA_RESTORE_FAILED",
            "无法从已完成的 R2 备份恢复原媒体",
            lastError,
        )
    }

    private fun uploadDeviceDescriptor(store: R2Store) {
        val now = System.currentTimeMillis()
        store.putJson(
            store.key("devices/${deviceInfo.deviceId}/device.json"),
            JSONObject()
                .put("deviceId", deviceInfo.deviceId)
                .put("createdAt", deviceInfo.createdAt)
                .put("updatedAt", now)
                .toString(),
        )
    }

    private fun uploadJob(store: R2Store, job: PendingR2BackupJob) {
        val snapshot = validateSnapshot(job)
        uploadSnapshotMedia(store, snapshot)
        val post = snapshot.getJSONObject("post")
        val sourcePostId = post.getString("sourcePostId")
        val postRoot =
            "devices/${job.deviceId}/posts/${job.sourcePlatform}/$sourcePostId"
        val snapshotKey =
            store.key(
                "$postRoot/snapshots/${job.backupSeq.toString().padStart(20, '0')}.json",
            )
        store.putImmutableJson(snapshotKey, job.snapshotJson)
        store.putJson(
            store.key("$postRoot/latest.json"),
            JSONObject()
                .put("deviceId", job.deviceId)
                .put("postId", job.postId)
                .put("generation", job.generation)
                .put("backupSeq", job.backupSeq)
                .put("snapshotKey", snapshotKey)
                .put("updatedAt", System.currentTimeMillis())
                .toString(),
        )
    }

    private fun validateSnapshot(job: PendingR2BackupJob): JSONObject {
        require(job.backupSeq > 0 && job.generation > 0) { "R2 备份序号无效" }
        require(DEVICE_ID_PATTERN.matches(job.deviceId)) { "R2 设备标识无效" }
        require(job.sourcePlatform in SUPPORTED_SOURCE_PLATFORMS) { "R2 备份来源平台无效" }
        val snapshot = JSONObject(job.snapshotJson)
        require(snapshot.getString("deviceId") == job.deviceId) { "R2 快照设备标识不匹配" }
        require(snapshot.getLong("backupSeq") == job.backupSeq) { "R2 快照序号不匹配" }
        require(snapshot.getLong("generation") == job.generation) { "R2 快照 generation 不匹配" }
        require(snapshot.getLong("createdAt") > 0) { "R2 快照时间无效" }
        val post = snapshot.getJSONObject("post")
        val sourcePostId = post.getString("sourcePostId")
        require(SOURCE_POST_ID_PATTERN.matches(sourcePostId)) { "R2 帖子来源编号无效" }
        require(post.getString("sourcePlatform") == job.sourcePlatform) { "R2 帖子来源平台不匹配" }
        require(post.getString("id") == job.postId) { "R2 帖子 ID 不匹配" }
        require(job.postId == "${job.sourcePlatform}:$sourcePostId") { "R2 帖子来源编号不匹配" }
        requireCanonicalSourceUrl(job.sourcePlatform, sourcePostId, post.getString("sourceUrl"))
        if (!post.isNull("authorAvatarSha256")) {
            require(SHA256_PATTERN.matches(post.getString("authorAvatarSha256"))) {
                "R2 头像校验值无效"
            }
        }
        val media = snapshot.getJSONArray("media")
        require(media.length() > 0 && media.length() == post.getInt("mediaCount")) {
            "R2 帖子媒体数量无效"
        }
        val mediaIds = mutableSetOf<String>()
        val sortIndexes = mutableSetOf<Int>()
        for (index in 0 until media.length()) {
            val item = media.getJSONObject(index)
            val sortIndex = item.getInt("sortIndex")
            val logicalIndex = item.getInt("logicalIndex")
            val mediaType = item.getString("mediaType")
            val mediaRole = item.getString("mediaRole")
            require(sortIndex >= 0 && logicalIndex >= 0 && sortIndexes.add(sortIndex)) {
                "R2 媒体编号无效"
            }
            require(item.getString("id") == "${job.postId}:$sortIndex") { "R2 媒体 ID 无效" }
            require(item.getString("postId") == job.postId && mediaIds.add(item.getString("id"))) {
                "R2 媒体所属帖子无效"
            }
            require(mediaType == "image" || mediaType == "video") { "R2 媒体类型无效" }
            require(mediaRole in SUPPORTED_MEDIA_ROLES) { "R2 媒体角色无效" }
            require(mediaRole != MEDIA_ROLE_LIVE_STILL || mediaType == "image") {
                "R2 Live Photo 静态媒体无效"
            }
            require(mediaRole != MEDIA_ROLE_LIVE_MOTION || mediaType == "video") {
                "R2 Live Photo 动态媒体无效"
            }
            require(item.getLong("originalSize") > 0) { "R2 原媒体大小无效" }
            require(SHA256_PATTERN.matches(item.getString("originalSha256"))) {
                "R2 原媒体校验值无效"
            }
            require(SHA256_PATTERN.matches(item.getString("thumbnailSha256"))) {
                "R2 缩略图校验值无效"
            }
        }
        require(post.getString("coverMediaId") in mediaIds) { "R2 帖子封面媒体无效" }
        return snapshot
    }

    private fun uploadSnapshotMedia(store: R2Store, snapshot: JSONObject) {
        val deviceRoot = "devices/${snapshot.getString("deviceId")}/media"
        val post = snapshot.getJSONObject("post")
        if (!post.isNull("authorAvatarSha256")) {
            val sha256 = post.getString("authorAvatarSha256")
            uploadContent(
                store,
                "$deviceRoot/avatars/$sha256.jpg",
                File(archiveRoot, "avatars/$sha256.jpg"),
                sha256,
                null,
                "image/jpeg",
            )
        }
        val media = snapshot.getJSONArray("media")
        for (index in 0 until media.length()) {
            val item = media.getJSONObject(index)
            val mimeType = item.getString("mimeType")
            val originalSha = item.getString("originalSha256")
            uploadContent(
                store,
                "$deviceRoot/originals/$originalSha${extensionForMime(mimeType)}",
                File(archiveRoot, "originals/$originalSha${extensionForMime(mimeType)}"),
                originalSha,
                item.getLong("originalSize"),
                mimeType,
            )
            val thumbnailSha = item.getString("thumbnailSha256")
            uploadContent(
                store,
                "$deviceRoot/thumbnails/$thumbnailSha.jpg",
                File(archiveRoot, "thumbnails/$thumbnailSha.jpg"),
                thumbnailSha,
                null,
                "image/jpeg",
            )
        }
    }

    private fun uploadContent(
        store: R2Store,
        relativeKey: String,
        localFile: File,
        sha256: String,
        expectedSize: Long?,
        contentType: String,
    ) {
        if (!isValid(localFile, sha256, expectedSize)) {
            throw ArchiveException("BACKUP_MEDIA_MISSING", "本地媒体文件缺失，无法备份到 R2")
        }
        store.uploadFileIfMissing(
            store.key(relativeKey),
            localFile,
            contentType,
            sha256,
        )
    }

    private fun downloadValidated(
        store: R2Store,
        relativeKey: String,
        target: File,
        sha256: String,
        expectedSize: Long,
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
        return sha256(file) == expectedSha256
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

    private fun backupErrorMessage(error: Exception): String =
        when (error) {
            is ArchiveException -> error.message
            is IllegalArgumentException -> error.message ?: "R2 备份数据无效"
            else -> "R2 备份失败，请检查配置、网络和 bucket 权限"
        }

    companion object {
        private const val DEFAULT_MAX_JOBS_PER_BATCH = 25
        private const val CONTINUATION_DELAY_MS = 0L
        private const val ERROR_RETRY_DELAY_MS = 15L * 60L * 1000L
        private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

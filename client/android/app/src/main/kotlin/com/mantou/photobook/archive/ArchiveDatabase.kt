package com.mantou.photobook.archive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ArchiveDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBaseSchema(db)
        createRuntimeSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("开发阶段不支持数据库从 $oldVersion 升级到 $newVersion，请清除 App 数据后重新安装")
    }

    fun recoverInterruptedJobs(): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            val requeued =
                db.update(
                    "capture_jobs",
                    ContentValues().apply {
                        put("status", "queued")
                        put("updated_at", now)
                    },
                    "status IN ('fetching', 'downloading', 'committing')",
                    null,
                )
            val cancelled =
                db.update(
                    "capture_jobs",
                    cancelledJobValues(now),
                    "status = 'cancelling'",
                    null,
                )
            db.setTransactionSuccessful()
            requeued > 0 || cancelled > 0
        } finally {
            db.endTransaction()
        }
    }

    fun enqueue(sourceUrl: String, sourcePostId: String): CaptureJob =
        enqueue(
            sourceUrl = sourceUrl,
            sourcePlatform = SOURCE_PLATFORM_INSTAGRAM,
            requestKey = sourcePostId,
            sourcePostId = sourcePostId,
        )

    fun enqueue(
        sourceUrl: String,
        sourcePlatform: String,
        requestKey: String,
        sourcePostId: String? = null,
    ): CaptureJob {
        require(sourcePlatform in SUPPORTED_SOURCE_PLATFORMS) { "不支持的来源平台" }
        require(requestKey.isNotBlank()) { "任务去重键不能为空" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            findJobByRequest(db, sourcePlatform, requestKey)?.let { existing ->
                if (existing.status == "failed") {
                    val values =
                        ContentValues().apply {
                            put("status", "queued")
                            putNull("error_code")
                            putNull("error_message")
                            putNull("next_attempt_at")
                            put("updated_at", now)
                        }
                    db.update("capture_jobs", values, "id = ?", arrayOf(existing.id))
                    db.setTransactionSuccessful()
                    return existing.copy(status = "queued")
                }
                db.setTransactionSuccessful()
                return existing
            }

            val alreadyArchived = sourcePostId != null &&
                db.rawQuery(
                    """
                    SELECT 1 FROM posts
                    WHERE source_platform = ? AND source_post_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(sourcePlatform, sourcePostId),
                ).use(Cursor::moveToFirst)
            val id = UUID.randomUUID().toString()
            val status = if (alreadyArchived) "completed" else "queued"
            db.insertOrThrow(
                "capture_jobs",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("source_url", sourceUrl)
                    put("source_platform", sourcePlatform)
                    put("request_key", requestKey)
                    put("source_post_id", sourcePostId)
                    put("status", status)
                    put("progress_current", 0)
                    put("progress_total", 0)
                    put("attempt_count", 0)
                    putNull("next_attempt_at")
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            db.setTransactionSuccessful()
            return CaptureJob(id, sourcePostId, status, 0, sourcePlatform, requestKey)
        } finally {
            db.endTransaction()
        }
    }

    fun claimNextJob(): CaptureJob? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val job =
                db.rawQuery(
                    """
                    SELECT * FROM capture_jobs
                    WHERE status = 'queued'
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                    ORDER BY created_at, id
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(System.currentTimeMillis().toString()),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.toCaptureJob() else null }
                    ?: return null
            val values =
                ContentValues().apply {
                    put("status", "fetching")
                    put("attempt_count", job.attemptCount + 1)
                    putNull("error_code")
                    putNull("error_message")
                    put("updated_at", System.currentTimeMillis())
                }
            val updated =
                db.update(
                    "capture_jobs",
                    values,
                    "id = ? AND status = 'queued'",
                    arrayOf(job.id),
                )
            if (updated != 1) return null
            db.setTransactionSuccessful()
            return job.copy(status = "fetching", attemptCount = job.attemptCount + 1)
        } finally {
            db.endTransaction()
        }
    }

    fun jobSourceUrl(jobId: String): String =
        readableDatabase.rawQuery(
            "SELECT source_url FROM capture_jobs WHERE id = ?",
            arrayOf(jobId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw ArchiveException("JOB_NOT_FOUND", "任务不存在或已被处理")
            }
            cursor.getString(0)
        }

    fun updateJobProgress(
        jobId: String,
        attemptCount: Int,
        expectedStatus: String,
        status: String,
        current: Int,
        total: Int,
    ): Boolean =
        writableDatabase.update(
            "capture_jobs",
            ContentValues().apply {
                put("status", status)
                put("progress_current", current)
                put("progress_total", total)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND attempt_count = ? AND status = ?",
            arrayOf(jobId, attemptCount.toString(), expectedStatus),
        ) == 1

    fun recordJobError(jobId: String, attemptCount: Int, error: ArchiveException): Boolean {
        val retryable = error.code == "NETWORK_ERROR" || error.code == "RATE_LIMITED"
        val now = System.currentTimeMillis()
        val retryDelay =
            if (error.code == "RATE_LIMITED") RATE_LIMIT_MS else retryDelayMs(attemptCount)
        return writableDatabase.update(
            "capture_jobs",
            ContentValues().apply {
                put("status", if (retryable) "queued" else "failed")
                put("error_code", error.code)
                put("error_message", error.message)
                if (retryable) put("next_attempt_at", now + retryDelay)
                else putNull("next_attempt_at")
                put("updated_at", now)
            },
            """
            id = ? AND attempt_count = ?
            AND status IN ('fetching', 'downloading', 'committing')
            """.trimIndent(),
            arrayOf(jobId, attemptCount.toString()),
        ) == 1
    }

    fun isJobAttemptActive(jobId: String, attemptCount: Int, expectedStatus: String): Boolean =
        isJobAttemptActive(readableDatabase, jobId, attemptCount, expectedStatus)

    private fun isJobAttemptActive(
        db: SQLiteDatabase,
        jobId: String,
        attemptCount: Int,
        expectedStatus: String,
    ): Boolean =
        db.rawQuery(
            """
            SELECT 1 FROM capture_jobs
            WHERE id = ? AND attempt_count = ? AND status = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(jobId, attemptCount.toString(), expectedStatus),
        ).use(Cursor::moveToFirst)

    fun cancelJob(jobId: String): JobCancellationResult? {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            val queued =
                db.update(
                    "capture_jobs",
                    cancelledJobValues(now),
                    "id = ? AND status = 'queued'",
                    arrayOf(jobId),
                )
            if (queued == 1) {
                db.setTransactionSuccessful()
                return JobCancellationResult.QUEUED
            }
            val running =
                db.update(
                    "capture_jobs",
                    ContentValues().apply {
                        put("status", "cancelling")
                        putNull("next_attempt_at")
                        put("updated_at", now)
                    },
                    "id = ? AND status IN ('fetching', 'downloading', 'committing')",
                    arrayOf(jobId),
                )
            db.setTransactionSuccessful()
            if (running == 1) JobCancellationResult.RUNNING else null
        } finally {
            db.endTransaction()
        }
    }

    fun completeJobCancellation(jobId: String, attemptCount: Int): Boolean =
        writableDatabase.update(
            "capture_jobs",
            cancelledJobValues(System.currentTimeMillis()),
            "id = ? AND attempt_count = ? AND status = 'cancelling'",
            arrayOf(jobId, attemptCount.toString()),
        ) == 1

    fun deleteJob(jobId: String): Boolean =
        writableDatabase.delete(
            "capture_jobs",
            "id = ? AND status = 'failed'",
            arrayOf(jobId),
        ) == 1

    fun retryJob(jobId: String): Boolean =
        writableDatabase.update(
            "capture_jobs",
            ContentValues().apply {
                put("status", "queued")
                put("progress_current", 0)
                put("progress_total", 0)
                putNull("error_code")
                putNull("error_message")
                putNull("next_attempt_at")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND status = 'failed'",
            arrayOf(jobId),
        ) == 1

    fun activeJobCount(): Int =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM capture_jobs
            WHERE status IN ('queued', 'fetching', 'downloading', 'committing', 'cancelling')
            """.trimIndent(),
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun hasRunningCaptureJob(): Boolean =
        readableDatabase.rawQuery(
            """
            SELECT 1 FROM capture_jobs
            WHERE status IN ('fetching', 'downloading', 'committing', 'cancelling')
            LIMIT 1
            """.trimIndent(),
            null,
        ).use(Cursor::moveToFirst)

    fun failedJobCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM capture_jobs WHERE status = 'failed'",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun nextQueuedDelayMs(now: Long = System.currentTimeMillis()): Long? =
        readableDatabase.rawQuery(
            """
            SELECT MIN(COALESCE(next_attempt_at, 0))
            FROM capture_jobs
            WHERE status = 'queued'
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            (cursor.getLong(0) - now).coerceAtLeast(0)
        }

    fun commitCompletedJob(
        jobId: String,
        attemptCount: Int,
        post: PreparedPost,
    ): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (!isJobAttemptActive(db, jobId, attemptCount, "committing")) {
                db.setTransactionSuccessful()
                return false
            }
            val jobIdentity =
                db.rawQuery(
                    "SELECT source_platform, source_post_id FROM capture_jobs WHERE id = ?",
                    arrayOf(jobId),
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "归档任务不存在" }
                    cursor.getString(0) to cursor.getNullableString("source_post_id")
                }
            require(jobIdentity.first == post.sourcePlatform) { "任务来源平台与解析结果不一致" }
            require(jobIdentity.second == null || jobIdentity.second == post.sourcePostId) {
                "任务帖子编号与解析结果不一致"
            }
            require(SOURCE_POST_ID_PATTERN.matches(post.sourcePostId)) { "帖子来源编号无效" }
            requireCanonicalSourceUrl(post.sourcePlatform, post.sourcePostId, post.sourceUrl)

            val previousGeneration =
                db.rawQuery(
                    "SELECT generation FROM post_backup_generations WHERE post_id = ?",
                    arrayOf(post.id),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            val generation = nextPositiveSequence(previousGeneration, "BACKUP_GENERATION_EXHAUSTED")
            db.insertWithOnConflict(
                "posts",
                null,
                ContentValues().apply {
                    put("id", post.id)
                    put("source_platform", post.sourcePlatform)
                    put("source_post_id", post.sourcePostId)
                    put("source_url", post.sourceUrl)
                    put("author_username", post.authorUsername)
                    put("author_display_name", post.authorDisplayName)
                    put("author_profile_url", post.authorProfileUrl)
                    put("has_author_avatar", if (post.authorAvatarSha256 == null) 0 else 1)
                    put("author_avatar_sha256", post.authorAvatarSha256)
                    put("caption", post.caption)
                    put("published_at", post.publishedAt)
                    put("location_name", post.locationName)
                    put("cover_media_id", post.media.first().id(post.id))
                    put("media_count", post.media.size)
                    put("saved_at", now)
                    put("updated_at", now)
                    put("local_avatar_path", post.localAvatarPath)
                    put("backup_generation", generation)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            post.media.forEach { media ->
                db.insertOrThrow(
                    "post_media",
                    null,
                    ContentValues().apply {
                        put("id", media.id(post.id))
                        put("post_id", post.id)
                        put("sort_index", media.sortIndex)
                        put("logical_index", media.logicalIndex)
                        put("media_role", media.mediaRole)
                        put("media_type", media.mediaType)
                        put("mime_type", media.mimeType)
                        put("width", media.width)
                        put("height", media.height)
                        put("duration_ms", media.durationMs)
                        put("original_size", media.originalSize)
                        put("original_sha256", media.originalSha256)
                        put("thumbnail_sha256", media.thumbnailSha256)
                        put("local_thumbnail_path", media.localThumbnailPath)
                        put("local_original_path", media.localOriginalPath)
                        put("original_download_status", "cached")
                        putNull("original_download_error")
                    },
                )
            }
            db.insertWithOnConflict(
                "post_backup_generations",
                null,
                ContentValues().apply {
                    put("post_id", post.id)
                    put("generation", generation)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.update(
                "capture_jobs",
                ContentValues().apply {
                    put("source_post_id", post.sourcePostId)
                    put("status", "completed")
                    put("progress_current", post.media.size)
                    put("progress_total", post.media.size)
                    putNull("error_code")
                    putNull("error_message")
                    put("updated_at", now)
                },
                "id = ?",
                arrayOf(jobId),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun deletePost(postId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sourceIdentity = sourceIdentity(db, postId)
                ?: throw ArchiveException("POST_NOT_FOUND", "帖子不存在或已被删除")
            deletePostInTransaction(db, postId, sourceIdentity.first, sourceIdentity.second)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteMediaSelection(
        postId: String,
        mediaIds: List<String>,
    ): DeleteMediaSelectionResult {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (mediaIds.isEmpty() || mediaIds.toSet().size != mediaIds.size) {
                throw ArchiveException("MEDIA_NOT_FOUND", "请选择有效媒体")
            }
            val sourceIdentity = sourceIdentity(db, postId)
                ?: throw ArchiveException("POST_NOT_FOUND", "帖子不存在或已被删除")
            val physicalMedia =
                db.rawQuery(
                    """
                    SELECT id, logical_index, media_role
                    FROM post_media
                    WHERE post_id = ?
                    ORDER BY logical_index,
                             CASE WHEN media_role = 'live_motion' THEN 0 ELSE 1 END,
                             sort_index
                    """.trimIndent(),
                    arrayOf(postId),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(LogicalMediaRow(cursor.getString(0), cursor.getInt(1), cursor.getString(2)))
                        }
                    }
                }
            if (physicalMedia.isEmpty()) {
                throw ArchiveException("MEDIA_NOT_FOUND", "帖子没有可删除的媒体")
            }
            val logicalIndexes = physicalMedia.mapTo(linkedSetOf()) { it.logicalIndex }
            val representativeIndexes =
                physicalMedia
                    .filter { it.mediaRole == MEDIA_ROLE_PRIMARY || it.mediaRole == MEDIA_ROLE_LIVE_STILL }
                    .associate { it.mediaId to it.logicalIndex }
            if (representativeIndexes.size != logicalIndexes.size ||
                representativeIndexes.values.toSet() != logicalIndexes
            ) {
                throw ArchiveException("MEDIA_NOT_FOUND", "帖子媒体结构无效")
            }
            val selectedLogicalIndexes =
                mediaIds.map { mediaId ->
                    representativeIndexes[mediaId]
                        ?: throw ArchiveException("MEDIA_NOT_FOUND", "选择包含无效媒体")
                }.toSet()
            if (selectedLogicalIndexes == logicalIndexes) {
                deletePostInTransaction(db, postId, sourceIdentity.first, sourceIdentity.second)
                db.setTransactionSuccessful()
                return DeleteMediaSelectionResult(postId, postDeleted = true)
            }
            physicalMedia
                .filter { it.logicalIndex in selectedLogicalIndexes }
                .forEach { db.delete("post_media", "id = ?", arrayOf(it.mediaId)) }
            updatePostMediaSummary(db, postId, now)
            db.delete(
                "capture_jobs",
                "source_platform = ? AND source_post_id = ? AND status = 'completed'",
                arrayOf(sourceIdentity.first, sourceIdentity.second),
            )
            db.setTransactionSuccessful()
            return DeleteMediaSelectionResult(postId, postDeleted = false)
        } finally {
            db.endTransaction()
        }
    }

    private fun deletePostInTransaction(
        db: SQLiteDatabase,
        postId: String,
        sourcePlatform: String,
        sourcePostId: String,
    ) {
        check(db.delete("posts", "id = ?", arrayOf(postId)) == 1) { "帖子删除失败" }
        db.delete(
            "capture_jobs",
            "source_platform = ? AND source_post_id = ? AND status = 'completed'",
            arrayOf(sourcePlatform, sourcePostId),
        )
    }

    fun enqueueManualBackup(
        postId: String,
        backupTargetId: String,
        deviceId: String,
    ): ManualBackupEnqueueStatus {
        require(postId.isNotBlank()) { "帖子标识无效" }
        require(backupTargetId.isNotBlank()) { "R2 备份目标标识无效" }
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "R2 设备标识无效" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val post =
                db.rawQuery(
                    """
                    SELECT source_platform, backup_generation
                    FROM posts
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf(postId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        throw ArchiveException("POST_NOT_FOUND", "帖子不存在或已被删除")
                    }
                    cursor.getString(0) to cursor.getLong(1)
                }
            val sourcePlatform = post.first
            val generation = post.second
            require(generation > 0) { "帖子备份 generation 无效" }
            val existing =
                db.rawQuery(
                    """
                    SELECT status FROM r2_backup_jobs
                    WHERE backup_target_id = ? AND device_id = ?
                      AND post_id = ? AND generation = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(backupTargetId, deviceId, postId, generation.toString()),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existing != null) {
                if (existing != "completed") {
                    db.update(
                        "r2_backup_jobs",
                        ContentValues().apply { putNull("last_error") },
                        "backup_target_id = ? AND device_id = ? AND post_id = ? AND generation = ?",
                        arrayOf(backupTargetId, deviceId, postId, generation.toString()),
                    )
                }
                db.setTransactionSuccessful()
                return if (existing == "completed") {
                    ManualBackupEnqueueStatus.COMPLETED
                } else {
                    ManualBackupEnqueueStatus.PENDING
                }
            }
            val backupSeq = nextBackupSeq(db)
            val now = System.currentTimeMillis()
            insertBackupJob(
                db = db,
                backupSeq = backupSeq,
                backupTargetId = backupTargetId,
                deviceId = deviceId,
                postId = postId,
                sourcePlatform = sourcePlatform,
                generation = generation,
                snapshotJson = buildBackupSnapshot(db, postId, deviceId, backupSeq, generation, now),
                createdAt = now,
            )
            writeMeta(db, "local_backup_seq", backupSeq.toString())
            db.setTransactionSuccessful()
            return ManualBackupEnqueueStatus.QUEUED
        } finally {
            db.endTransaction()
        }
    }

    fun discardPendingBackupJobs(backupTargetId: String): Int =
        writableDatabase.delete(
            "r2_backup_jobs",
            "status = 'pending' AND backup_target_id = ?",
            arrayOf(backupTargetId),
        )

    fun migrateToManualBackupMode(): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val migrated =
                db.rawQuery(
                    "SELECT value FROM app_meta WHERE key = ?",
                    arrayOf(MANUAL_BACKUP_MIGRATION_KEY),
                ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "1" }
            if (migrated) {
                db.setTransactionSuccessful()
                return false
            }
            db.delete("r2_backup_jobs", "status = 'pending'", null)
            db.delete("app_meta", "key = 'last_backup_error'", null)
            writeMeta(db, MANUAL_BACKUP_MIGRATION_KEY, "1")
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun listPendingBackupTargetIds(deviceId: String): List<String> =
        readableDatabase.rawQuery(
            """
            SELECT backup_target_id, MIN(backup_seq) AS first_seq
            FROM r2_backup_jobs
            WHERE device_id = ? AND status = 'pending'
            GROUP BY backup_target_id
            ORDER BY first_seq
            """.trimIndent(),
            arrayOf(deviceId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    fun hasPendingBackupJobs(deviceId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM r2_backup_jobs WHERE device_id = ? AND status = 'pending' LIMIT 1",
            arrayOf(deviceId),
        ).use(Cursor::moveToFirst)

    fun completedBackupTargetIds(postId: String, deviceId: String): List<String> =
        readableDatabase.rawQuery(
            """
            SELECT j.backup_target_id
            FROM r2_backup_jobs j
            JOIN posts p ON p.id = j.post_id AND p.backup_generation = j.generation
            WHERE j.post_id = ? AND j.device_id = ? AND j.status = 'completed'
            ORDER BY j.completed_at DESC, j.backup_seq DESC
            """.trimIndent(),
            arrayOf(postId, deviceId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    fun listPendingBackupJobs(
        backupTargetId: String,
        deviceId: String,
        limit: Int = Int.MAX_VALUE,
    ): List<PendingR2BackupJob> {
        require(limit > 0) { "R2 备份批次必须大于 0" }
        return readableDatabase.rawQuery(
            """
            SELECT backup_seq, backup_target_id, device_id, post_id,
                   source_platform, generation, snapshot_json
            FROM r2_backup_jobs
            WHERE backup_target_id = ? AND device_id = ? AND status = 'pending'
            ORDER BY backup_seq
            LIMIT ?
            """.trimIndent(),
            arrayOf(backupTargetId, deviceId, limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingR2BackupJob(
                            backupSeq = cursor.getLong(0),
                            backupTargetId = cursor.getString(1),
                            deviceId = cursor.getString(2),
                            postId = cursor.getString(3),
                            sourcePlatform = cursor.getString(4),
                            generation = cursor.getLong(5),
                            snapshotJson = cursor.getString(6),
                        ),
                    )
                }
            }
        }
    }

    fun markBackupCompleted(backupSeq: Long) {
        val updated =
            writableDatabase.update(
                "r2_backup_jobs",
                ContentValues().apply {
                    put("status", "completed")
                    put("completed_at", System.currentTimeMillis())
                    putNull("last_error")
                },
                "backup_seq = ? AND status = 'pending'",
                arrayOf(backupSeq.toString()),
            )
        check(updated == 1) { "待确认 R2 备份任务不存在" }
    }

    fun clearBackupError(backupSeq: Long): Boolean =
        writableDatabase.update(
            "r2_backup_jobs",
            ContentValues().apply { putNull("last_error") },
            "backup_seq = ? AND status = 'pending' AND last_error IS NOT NULL AND TRIM(last_error) != ''",
            arrayOf(backupSeq.toString()),
        ) == 1

    fun markBackupError(backupSeq: Long, message: String) {
        writableDatabase.update(
            "r2_backup_jobs",
            ContentValues().apply { put("last_error", message.take(300)) },
            "backup_seq = ? AND status = 'pending'",
            arrayOf(backupSeq.toString()),
        )
    }

    fun markPendingBackupErrors(
        backupTargetId: String,
        deviceId: String,
        message: String,
    ): Int =
        writableDatabase.update(
            "r2_backup_jobs",
            ContentValues().apply { put("last_error", message.take(300)) },
            "backup_target_id = ? AND device_id = ? AND status = 'pending'",
            arrayOf(backupTargetId, deviceId),
        )

    fun markPendingBackupErrors(deviceId: String, message: String): Int =
        writableDatabase.update(
            "r2_backup_jobs",
            ContentValues().apply { put("last_error", message.take(300)) },
            "device_id = ? AND status = 'pending'",
            arrayOf(deviceId),
        )

    fun hasPendingBackupJobs(backupTargetId: String, deviceId: String): Boolean =
        readableDatabase.rawQuery(
            """
            SELECT 1 FROM r2_backup_jobs
            WHERE backup_target_id = ? AND device_id = ? AND status = 'pending'
            LIMIT 1
            """.trimIndent(),
            arrayOf(backupTargetId, deviceId),
        ).use(Cursor::moveToFirst)

    fun originalDescriptor(mediaId: String): OriginalMediaDescriptor? =
        readableDatabase.rawQuery(
            """
            SELECT m.id, m.post_id, p.source_post_id,
                   m.logical_index, m.media_role, m.media_type,
                   m.mime_type, m.original_sha256, m.original_size, m.local_original_path
            FROM post_media m
            JOIN posts p ON p.id = m.post_id
            WHERE m.id = ?
            """.trimIndent(),
            arrayOf(mediaId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            OriginalMediaDescriptor(
                mediaId = cursor.getString(0),
                postId = cursor.getString(1),
                sourcePostId = cursor.getString(2),
                logicalIndex = cursor.getInt(3),
                mediaRole = cursor.getString(4),
                mediaType = cursor.getString(5),
                mimeType = cursor.getString(6),
                sha256 = cursor.getString(7),
                expectedSize = cursor.getLong(8),
                localPath = cursor.getNullableString(9),
            )
        }

    fun liveMotionDescriptor(mediaId: String): OriginalMediaDescriptor? {
        val still = originalDescriptor(mediaId) ?: return null
        if (still.mediaRole != MEDIA_ROLE_LIVE_STILL) return null
        val motionId =
            readableDatabase.rawQuery(
                """
                SELECT id FROM post_media
                WHERE post_id = ? AND logical_index = ? AND media_role = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(still.postId, still.logicalIndex.toString(), MEDIA_ROLE_LIVE_MOTION),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return motionId?.let(::originalDescriptor)
    }

    fun updateOriginalPath(mediaId: String, sha256: String, localPath: String) {
        writableDatabase.update(
            "post_media",
            ContentValues().apply {
                put("local_original_path", localPath)
                put("original_download_status", "cached")
                putNull("original_download_error")
            },
            "id = ? AND original_sha256 = ?",
            arrayOf(mediaId, sha256),
        )
    }

    fun writeBackupResult(error: String?) {
        val db = writableDatabase
        if (error == null) {
            db.delete("app_meta", "key = 'last_backup_error'", null)
        } else {
            writeMeta(db, "last_backup_error", error.take(300))
        }
    }

    fun clearBackupResult() {
        writableDatabase.delete("app_meta", "key = 'last_backup_error'", null)
    }

    fun isMediaShaReferenced(sha256: String): Boolean {
        val db = readableDatabase
        val current =
            db.rawQuery(
                """
                SELECT 1 FROM posts WHERE author_avatar_sha256 = ?
                UNION ALL
                SELECT 1 FROM post_media
                WHERE original_sha256 = ? OR thumbnail_sha256 = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(sha256, sha256, sha256),
            ).use(Cursor::moveToFirst)
        if (current) return true
        return db.rawQuery(
            """
            SELECT snapshot_json FROM r2_backup_jobs
            WHERE status = 'pending' AND snapshot_json LIKE ?
            """.trimIndent(),
            arrayOf("%$sha256%"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (snapshotReferencesSha(cursor.getString(0), sha256)) return@use true
            }
            false
        }
    }

    private fun sourceIdentity(db: SQLiteDatabase, postId: String): Pair<String, String>? =
        db.rawQuery(
            "SELECT source_platform, source_post_id FROM posts WHERE id = ?",
            arrayOf(postId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }

    private fun updatePostMediaSummary(db: SQLiteDatabase, postId: String, now: Long) {
        val mediaIds =
            db.rawQuery(
                "SELECT id FROM post_media WHERE post_id = ? ORDER BY sort_index",
                arrayOf(postId),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        check(mediaIds.isNotEmpty()) { "帖子删除后没有剩余媒体" }
        db.update(
            "posts",
            ContentValues().apply {
                put("cover_media_id", mediaIds.first())
                put("media_count", mediaIds.size)
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(postId),
        )
    }

    private fun insertBackupJob(
        db: SQLiteDatabase,
        backupSeq: Long,
        backupTargetId: String,
        deviceId: String,
        postId: String,
        sourcePlatform: String,
        generation: Long,
        snapshotJson: String,
        createdAt: Long,
    ) {
        db.insertOrThrow(
            "r2_backup_jobs",
            null,
            ContentValues().apply {
                put("backup_seq", backupSeq)
                put("backup_target_id", backupTargetId)
                put("device_id", deviceId)
                put("post_id", postId)
                put("source_platform", sourcePlatform)
                put("generation", generation)
                put("snapshot_json", snapshotJson)
                put("status", "pending")
                put("created_at", createdAt)
            },
        )
    }

    private fun buildBackupSnapshot(
        db: SQLiteDatabase,
        postId: String,
        deviceId: String,
        backupSeq: Long,
        generation: Long,
        createdAt: Long,
    ): String {
        val post =
            db.rawQuery(
                """
                SELECT id, source_platform, source_post_id, source_url,
                       author_username, author_display_name, author_profile_url,
                       has_author_avatar, author_avatar_sha256, caption, published_at,
                       location_name, cover_media_id, media_count, saved_at, updated_at
                FROM posts WHERE id = ?
                """.trimIndent(),
                arrayOf(postId),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "待备份帖子不存在" }
                JSONObject()
                    .put("id", cursor.getString(0))
                    .put("sourcePlatform", cursor.getString(1))
                    .put("sourcePostId", cursor.getString(2))
                    .put("sourceUrl", cursor.getString(3))
                    .put("authorUsername", cursor.getString(4))
                    .put("authorDisplayName", cursor.getString(5))
                    .put("authorProfileUrl", cursor.getString(6))
                    .put("hasAuthorAvatar", cursor.getInt(7) == 1)
                    .put("authorAvatarSha256", cursor.getNullableString(8))
                    .put("caption", cursor.getString(9))
                    .put("publishedAt", cursor.getLong(10))
                    .put("locationName", cursor.getNullableString(11))
                    .put("coverMediaId", cursor.getString(12))
                    .put("mediaCount", cursor.getInt(13))
                    .put("savedAt", cursor.getLong(14))
                    .put("updatedAt", cursor.getLong(15))
            }
        val media = JSONArray()
        db.rawQuery(
            """
            SELECT id, post_id, sort_index, logical_index, media_role, media_type,
                   mime_type, width, height, duration_ms, original_size,
                   original_sha256, thumbnail_sha256
            FROM post_media WHERE post_id = ? ORDER BY sort_index
            """.trimIndent(),
            arrayOf(postId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                media.put(
                    JSONObject()
                        .put("id", cursor.getString(0))
                        .put("postId", cursor.getString(1))
                        .put("sortIndex", cursor.getInt(2))
                        .put("logicalIndex", cursor.getInt(3))
                        .put("mediaRole", cursor.getString(4))
                        .put("mediaType", cursor.getString(5))
                        .put("mimeType", cursor.getString(6))
                        .put("width", cursor.getInt(7))
                        .put("height", cursor.getInt(8))
                        .put("durationMs", cursor.getNullableLong(9))
                        .put("originalSize", cursor.getLong(10))
                        .put("originalSha256", cursor.getString(11))
                        .put("thumbnailSha256", cursor.getString(12)),
                )
            }
        }
        check(media.length() == post.getInt("mediaCount") && media.length() > 0) {
            "待备份帖子媒体清单无效"
        }
        return JSONObject()
            .put("deviceId", deviceId)
            .put("backupSeq", backupSeq)
            .put("generation", generation)
            .put("createdAt", createdAt)
            .put("post", post)
            .put("media", media)
            .toString()
    }

    private fun nextBackupSeq(db: SQLiteDatabase): Long =
        nextPositiveSequence(readMetaLong(db, "local_backup_seq"), "BACKUP_SEQUENCE_EXHAUSTED")

    private fun nextPositiveSequence(current: Long, errorCode: String): Long {
        if (current >= Long.MAX_VALUE - 1) {
            throw ArchiveException(errorCode, "R2 备份序号已耗尽，请清空数据后重试")
        }
        return current + 1
    }

    private fun readMetaLong(db: SQLiteDatabase, key: String): Long =
        db.rawQuery("SELECT value FROM app_meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
        }

    private fun snapshotReferencesSha(rawJson: String, sha256: String): Boolean =
        runCatching {
            val snapshot = JSONObject(rawJson)
            val post = snapshot.getJSONObject("post")
            if (post.optString("authorAvatarSha256") == sha256) return@runCatching true
            val media = snapshot.getJSONArray("media")
            (0 until media.length()).any { index ->
                val item = media.getJSONObject(index)
                item.getString("originalSha256") == sha256 ||
                    item.getString("thumbnailSha256") == sha256
            }
        }.getOrDefault(false)

    private fun writeMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "app_meta",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun findJobByRequest(
        db: SQLiteDatabase,
        sourcePlatform: String,
        requestKey: String,
    ): CaptureJob? =
        db.rawQuery(
            "SELECT * FROM capture_jobs WHERE source_platform = ? AND request_key = ? LIMIT 1",
            arrayOf(sourcePlatform, requestKey),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCaptureJob() else null }

    private fun createBaseSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE posts (
                id TEXT PRIMARY KEY,
                source_platform TEXT NOT NULL,
                source_post_id TEXT NOT NULL,
                source_url TEXT NOT NULL,
                author_username TEXT NOT NULL,
                author_display_name TEXT NOT NULL,
                author_profile_url TEXT NOT NULL,
                has_author_avatar INTEGER NOT NULL,
                author_avatar_sha256 TEXT,
                caption TEXT NOT NULL,
                published_at INTEGER NOT NULL,
                location_name TEXT,
                cover_media_id TEXT NOT NULL,
                media_count INTEGER NOT NULL,
                saved_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                local_avatar_path TEXT,
                backup_generation INTEGER NOT NULL,
                UNIQUE(source_platform, source_post_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX posts_saved_at ON posts(saved_at DESC, id DESC)")
        db.execSQL(
            """
            CREATE TABLE post_media (
                id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                sort_index INTEGER NOT NULL,
                logical_index INTEGER NOT NULL,
                media_role TEXT NOT NULL,
                media_type TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                duration_ms INTEGER,
                original_size INTEGER NOT NULL,
                original_sha256 TEXT NOT NULL,
                thumbnail_sha256 TEXT NOT NULL,
                local_thumbnail_path TEXT,
                local_original_path TEXT,
                original_download_status TEXT NOT NULL DEFAULT 'cached',
                original_download_error TEXT,
                UNIQUE(post_id, sort_index)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX post_media_post_id ON post_media(post_id, sort_index)")
        db.execSQL(
            """
            CREATE TABLE post_backup_generations (
                post_id TEXT PRIMARY KEY,
                generation INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createRuntimeSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE capture_jobs (
                id TEXT PRIMARY KEY,
                source_url TEXT NOT NULL,
                source_platform TEXT NOT NULL,
                request_key TEXT NOT NULL,
                source_post_id TEXT,
                status TEXT NOT NULL,
                progress_current INTEGER NOT NULL DEFAULT 0,
                progress_total INTEGER NOT NULL DEFAULT 0,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER,
                error_code TEXT,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(source_platform, request_key)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX capture_jobs_status ON capture_jobs(status, created_at)")
        db.execSQL(
            """
            CREATE TABLE r2_backup_jobs (
                backup_seq INTEGER PRIMARY KEY,
                backup_target_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                post_id TEXT NOT NULL,
                source_platform TEXT NOT NULL,
                generation INTEGER NOT NULL,
                snapshot_json TEXT NOT NULL,
                status TEXT NOT NULL,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                completed_at INTEGER,
                UNIQUE(backup_target_id, device_id, post_id, generation)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX r2_backup_jobs_pending
            ON r2_backup_jobs(backup_target_id, device_id, status, backup_seq)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX r2_backup_jobs_post_generation
            ON r2_backup_jobs(backup_target_id, post_id, generation, status)
            """.trimIndent(),
        )
    }

    private fun Cursor.toCaptureJob(): CaptureJob =
        CaptureJob(
            id = getString(getColumnIndexOrThrow("id")),
            sourcePostId = getNullableString("source_post_id"),
            status = getString(getColumnIndexOrThrow("status")),
            attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
            sourcePlatform = getString(getColumnIndexOrThrow("source_platform")),
            requestKey = getString(getColumnIndexOrThrow("request_key")),
        )

    private fun Cursor.getNullableString(column: String): String? =
        getNullableString(getColumnIndexOrThrow(column))

    private fun Cursor.getNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getNullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun cancelledJobValues(now: Long): ContentValues =
        ContentValues().apply {
            put("status", "failed")
            put("error_code", "CANCELLED")
            put("error_message", "任务已由用户取消")
            putNull("next_attempt_at")
            put("updated_at", now)
        }

    private data class LogicalMediaRow(
        val mediaId: String,
        val logicalIndex: Int,
        val mediaRole: String,
    )

    companion object {
        const val DATABASE_NAME = "photobook.db"
        const val DATABASE_VERSION = 4
        private const val MANUAL_BACKUP_MIGRATION_KEY = "manual_backup_mode_v1"
        private const val RATE_LIMIT_MS = 30L * 60L * 1000L
        private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

        private fun retryDelayMs(attemptCount: Int): Long {
            val exponent = (attemptCount - 1).coerceIn(0, 5)
            return (60_000L shl exponent).coerceAtMost(30L * 60L * 1000L)
        }
    }
}

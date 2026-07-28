package com.mantou.photobook.archive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
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

    fun recoverInterruptedJobs() {
        writableDatabase.execSQL(
            """
            UPDATE capture_jobs
            SET status = 'queued', updated_at = ?
            WHERE status IN ('fetching', 'downloading', 'committing')
            """.trimIndent(),
            arrayOf(System.currentTimeMillis()),
        )
    }

    fun enqueue(sourceUrl: String, sourcePostId: String): CaptureJob {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            findJobBySource(db, sourcePostId)?.let { existing ->
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

            val alreadyArchived =
                db.rawQuery(
                    """
                    SELECT 1 FROM posts p
                    WHERE p.source_post_id = ?
                      AND NOT EXISTS (
                        SELECT 1 FROM sync_entity_states s
                        WHERE s.entity_type = 'media'
                          AND s.state = 'deleted'
                          AND s.entity_id LIKE p.id || ':%'
                      )
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(sourcePostId),
                ).use { it.moveToFirst() }
            val id = UUID.randomUUID().toString()
            val status = if (alreadyArchived) "completed" else "queued"
            val values =
                ContentValues().apply {
                    put("id", id)
                    put("source_url", sourceUrl)
                    put("source_post_id", sourcePostId)
                    put("status", status)
                    put("progress_current", 0)
                    put("progress_total", 0)
                    put("attempt_count", 0)
                    putNull("next_attempt_at")
                    put("created_at", now)
                    put("updated_at", now)
                }
            db.insertOrThrow("capture_jobs", null, values)
            db.setTransactionSuccessful()
            return CaptureJob(id, sourcePostId, status, 0)
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
            val now = System.currentTimeMillis()
            val values =
                ContentValues().apply {
                    put("status", "fetching")
                    put("attempt_count", job.attemptCount + 1)
                    putNull("error_code")
                    putNull("error_message")
                    put("updated_at", now)
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
            return job.copy(
                status = "fetching",
                attemptCount = job.attemptCount + 1,
            )
        } finally {
            db.endTransaction()
        }
    }

    fun updateJobProgress(jobId: String, status: String, current: Int, total: Int) {
        val values =
            ContentValues().apply {
                put("status", status)
                put("progress_current", current)
                put("progress_total", total)
                put("updated_at", System.currentTimeMillis())
            }
        writableDatabase.update("capture_jobs", values, "id = ?", arrayOf(jobId))
    }

    fun recordJobError(jobId: String, error: ArchiveException) {
        val retryable = error.code == "NETWORK_ERROR" || error.code == "RATE_LIMITED"
        val now = System.currentTimeMillis()
        val attemptCount =
            readableDatabase.rawQuery(
                "SELECT attempt_count FROM capture_jobs WHERE id = ?",
                arrayOf(jobId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 1 }
        val retryDelay =
            if (error.code == "RATE_LIMITED") RATE_LIMIT_MS else retryDelayMs(attemptCount)
        val values =
            ContentValues().apply {
                put("status", if (retryable) "queued" else "failed")
                put("error_code", error.code)
                put("error_message", error.message)
                if (retryable) put("next_attempt_at", now + retryDelay)
                else putNull("next_attempt_at")
                put("updated_at", now)
            }
        writableDatabase.update("capture_jobs", values, "id = ?", arrayOf(jobId))
    }

    fun retryJob(jobId: String): Boolean {
        val values =
            ContentValues().apply {
                put("status", "queued")
                put("progress_current", 0)
                put("progress_total", 0)
                putNull("error_code")
                putNull("error_message")
                putNull("next_attempt_at")
                put("updated_at", System.currentTimeMillis())
            }
        return writableDatabase.update(
            "capture_jobs",
            values,
            "id = ? AND status = 'failed'",
            arrayOf(jobId),
        ) == 1
    }

    fun activeJobCount(): Int =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM capture_jobs
            WHERE status IN ('queued', 'fetching', 'downloading', 'committing')
            """.trimIndent(),
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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

    fun commitCompletedJob(jobId: String, post: PreparedPost, deviceId: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val seq = nextLocalSeq(db)
            val version = nextLogicalVersion(db)
            val previousMediaIds = mediaIdsForPost(db, post.id).toSet()
            val postValues =
                ContentValues().apply {
                    put("id", post.id)
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
                    put("sync_device_id", deviceId)
                    put("sync_seq", seq)
                }
            db.insertWithOnConflict("posts", null, postValues, SQLiteDatabase.CONFLICT_REPLACE)

            writeEntityState(
                db = db,
                entityType = ENTITY_POST,
                entityId = post.id,
                state = STATE_ACTIVE,
                version = version,
                deviceId = deviceId,
                seq = seq,
                changedAt = now,
            )

            val currentMediaIds = mutableSetOf<String>()
            post.media.forEach { media ->
                val mediaId = media.id(post.id)
                currentMediaIds += mediaId
                val mediaValues =
                    ContentValues().apply {
                        put("id", mediaId)
                        put("post_id", post.id)
                        put("sort_index", media.sortIndex)
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
                    }
                db.insertOrThrow("post_media", null, mediaValues)
                writeEntityState(
                    db = db,
                    entityType = ENTITY_MEDIA,
                    entityId = mediaId,
                    state = STATE_ACTIVE,
                    version = version,
                    deviceId = deviceId,
                    seq = seq,
                    changedAt = now,
                )
            }
            (previousMediaIds - currentMediaIds).forEach { mediaId ->
                writeEntityState(
                    db = db,
                    entityType = ENTITY_MEDIA,
                    entityId = mediaId,
                    state = STATE_DELETED,
                    version = version,
                    deviceId = deviceId,
                    seq = seq,
                    changedAt = now,
                )
            }

            val operation =
                ContentValues().apply {
                    put("device_id", deviceId)
                    put("seq", seq)
                    put("operation", "upsert_post")
                    put("entity_id", post.id)
                    put("payload_json", post.operationJson(deviceId, seq, now, version))
                    put("created_at", now)
                }
            db.insertOrThrow("sync_ops", null, operation)

            val jobValues =
                ContentValues().apply {
                    put("status", "completed")
                    put("progress_current", post.media.size)
                    put("progress_total", post.media.size)
                    putNull("error_code")
                    putNull("error_message")
                    put("updated_at", now)
                }
            db.update("capture_jobs", jobValues, "id = ?", arrayOf(jobId))
            writeMeta(db, "local_sync_seq", seq.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deletePost(postId: String, deviceId: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val sourcePostId = sourcePostId(db, postId)
                ?: throw ArchiveException("POST_NOT_FOUND", "帖子不存在或已被删除")
            deletePostInTransaction(db, postId, sourcePostId, deviceId, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteMedia(mediaId: String, deviceId: String): DeleteMediaResult {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val media =
                db.rawQuery(
                    """
                    SELECT m.post_id, p.source_post_id,
                           (SELECT COUNT(*) FROM post_media WHERE post_id = m.post_id)
                    FROM post_media m
                    JOIN posts p ON p.id = m.post_id
                    WHERE m.id = ?
                    """.trimIndent(),
                    arrayOf(mediaId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
                } ?: throw ArchiveException("MEDIA_NOT_FOUND", "媒体不存在或已被删除")

            val (postId, sourcePostId, mediaCount) = media
            if (mediaCount <= 1) {
                db.setTransactionSuccessful()
                return DeleteMediaResult(postId = postId, postDeleteRequired = true)
            }

            val seq = nextLocalSeq(db)
            val version = nextLogicalVersion(db)
            writeEntityState(
                db,
                ENTITY_MEDIA,
                mediaId,
                STATE_DELETED,
                version,
                deviceId,
                seq,
                now,
            )
            db.delete("post_media", "id = ?", arrayOf(mediaId))
            updatePostMediaSummary(db, postId, deviceId, seq, now)
            db.delete(
                "capture_jobs",
                "source_post_id = ? AND status = 'completed'",
                arrayOf(sourcePostId),
            )
            insertSyncOperation(
                db = db,
                deviceId = deviceId,
                seq = seq,
                operation = "delete_media",
                entityId = mediaId,
                payloadJson = deleteMediaOperationJson(
                    deviceId,
                    seq,
                    EntityVersion(version, deviceId, seq),
                    now,
                    now,
                    postId,
                    mediaId,
                ),
                createdAt = now,
            )
            writeMeta(db, "local_sync_seq", seq.toString())
            db.setTransactionSuccessful()
            return DeleteMediaResult(postId = postId, postDeleteRequired = false)
        } finally {
            db.endTransaction()
        }
    }

    private fun deletePostInTransaction(
        db: SQLiteDatabase,
        postId: String,
        sourcePostId: String,
        deviceId: String,
        now: Long,
    ) {
        val seq = nextLocalSeq(db)
        val version = nextLogicalVersion(db)
        writeEntityState(
            db,
            ENTITY_POST,
            postId,
            STATE_DELETED,
            version,
            deviceId,
            seq,
            now,
        )
        db.delete("posts", "id = ?", arrayOf(postId))
        db.delete(
            "capture_jobs",
            "source_post_id = ? AND status = 'completed'",
            arrayOf(sourcePostId),
        )
        insertSyncOperation(
            db = db,
            deviceId = deviceId,
            seq = seq,
            operation = "delete_post",
            entityId = postId,
            payloadJson = deletePostOperationJson(
                deviceId,
                seq,
                EntityVersion(version, deviceId, seq),
                now,
                now,
                postId,
            ),
            createdAt = now,
        )
        writeMeta(db, "local_sync_seq", seq.toString())
    }

    fun seedRepositoryIfNeeded(repositoryId: String, deviceId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seedKey = "repository_seed:$repositoryId"
            val alreadySeeded =
                db.rawQuery("SELECT 1 FROM app_meta WHERE key = ? LIMIT 1", arrayOf(seedKey))
                    .use { it.moveToFirst() }
            if (alreadySeeded) {
                db.setTransactionSuccessful()
                return
            }
            val activePosts =
                db.rawQuery(
                    """
                    SELECT p.id, s.version, s.device_id, s.seq, s.changed_at
                    FROM posts p
                    JOIN sync_entity_states s
                      ON s.entity_type = 'post'
                     AND s.entity_id = p.id
                     AND s.state = 'active'
                    ORDER BY p.saved_at, p.id
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                EntityStateSnapshot(
                                    entityType = ENTITY_POST,
                                    entityId = cursor.getString(0),
                                    state = STATE_ACTIVE,
                                    entityVersion = EntityVersion(
                                        cursor.getLong(1),
                                        cursor.getString(2),
                                        cursor.getLong(3),
                                    ),
                                    changedAt = cursor.getLong(4),
                                ),
                            )
                        }
                    }
                }
            val tombstones =
                db.rawQuery(
                    """
                    SELECT entity_type, entity_id, version, device_id, seq, changed_at
                    FROM sync_entity_states
                    WHERE state = 'deleted'
                    ORDER BY CASE entity_type WHEN 'post' THEN 0 ELSE 1 END, entity_id
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                EntityStateSnapshot(
                                    entityType = cursor.getString(0),
                                    entityId = cursor.getString(1),
                                    state = STATE_DELETED,
                                    entityVersion = EntityVersion(
                                        cursor.getLong(2),
                                        cursor.getString(3),
                                        cursor.getLong(4),
                                    ),
                                    changedAt = cursor.getLong(5),
                                ),
                            )
                        }
                    }
                }
            var seq = readMetaLong(db, "local_sync_seq")
            for (snapshot in activePosts + tombstones) {
                seq = nextSequence(seq)
                val now = System.currentTimeMillis()
                val operation =
                    when {
                        snapshot.state == STATE_ACTIVE -> "upsert_post"
                        snapshot.entityType == ENTITY_POST -> "delete_post"
                        snapshot.entityType == ENTITY_MEDIA -> "delete_media"
                        else -> error("未知同步实体类型")
                    }
                val operationJson =
                    when (operation) {
                        "upsert_post" -> buildOperationJson(
                            db,
                            snapshot.entityId,
                            deviceId,
                            seq,
                            now,
                            snapshot.entityVersion,
                        )
                        "delete_post" -> deletePostOperationJson(
                            deviceId,
                            seq,
                            snapshot.entityVersion,
                            now,
                            snapshot.changedAt,
                            snapshot.entityId,
                        )
                        else -> {
                            val postId = snapshot.entityId.substringBeforeLast(':')
                            require(postId != snapshot.entityId) { "媒体墓碑 ID 无效" }
                            deleteMediaOperationJson(
                                deviceId,
                                seq,
                                snapshot.entityVersion,
                                now,
                                snapshot.changedAt,
                                postId,
                                snapshot.entityId,
                            )
                        }
                    }
                insertSyncOperation(
                    db,
                    deviceId,
                    seq,
                    operation,
                    snapshot.entityId,
                    operationJson,
                    now,
                )
            }
            if (activePosts.isNotEmpty() || tombstones.isNotEmpty()) {
                writeMeta(db, "local_sync_seq", seq.toString())
            }
            writeMeta(db, seedKey, System.currentTimeMillis().toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listPendingSyncOperations(
        repositoryId: String,
        deviceId: String,
        limit: Int = Int.MAX_VALUE,
    ): List<PendingSyncOperation> {
        require(limit > 0) { "同步操作批次必须大于 0" }
        return readableDatabase.rawQuery(
            """
            SELECT o.seq, o.operation, o.payload_json
            FROM sync_ops o
            LEFT JOIN sync_uploads u
              ON u.repository_id = ?
             AND u.device_id = o.device_id
             AND u.seq = o.seq
            WHERE o.device_id = ? AND u.uploaded_at IS NULL
            ORDER BY o.seq
            LIMIT ?
            """.trimIndent(),
            arrayOf(repositoryId, deviceId, limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingSyncOperation(
                            seq = cursor.getLong(0),
                            operation = cursor.getString(1),
                            payloadJson = cursor.getString(2),
                        ),
                    )
                }
            }
        }
    }

    fun markSyncOperationUploaded(repositoryId: String, deviceId: String, seq: Long) {
        val values =
            ContentValues().apply {
                put("repository_id", repositoryId)
                put("device_id", deviceId)
                put("seq", seq)
                put("uploaded_at", System.currentTimeMillis())
                putNull("last_error")
            }
        writableDatabase.insertWithOnConflict(
            "sync_uploads",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun markSyncOperationError(
        repositoryId: String,
        deviceId: String,
        seq: Long,
        message: String,
    ) {
        val values =
            ContentValues().apply {
                put("repository_id", repositoryId)
                put("device_id", deviceId)
                put("seq", seq)
                put("last_error", message.take(300))
            }
        writableDatabase.insertWithOnConflict(
            "sync_uploads",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun hasPendingSyncOperations(repositoryId: String, deviceId: String): Boolean =
        readableDatabase.rawQuery(
            """
            SELECT 1
            FROM sync_ops o
            LEFT JOIN sync_uploads u
              ON u.repository_id = ?
             AND u.device_id = o.device_id
             AND u.seq = o.seq
            WHERE o.device_id = ? AND u.uploaded_at IS NULL
            LIMIT 1
            """.trimIndent(),
            arrayOf(repositoryId, deviceId),
        ).use { it.moveToFirst() }

    fun peerHighWater(repositoryId: String, peerDeviceId: String): Long =
        readableDatabase.rawQuery(
            """
            SELECT high_water_seq FROM sync_peers
            WHERE repository_id = ? AND peer_device_id = ?
            """.trimIndent(),
            arrayOf(repositoryId, peerDeviceId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0 }

    fun applyRemoteOperation(
        repositoryId: String,
        peerDeviceId: String,
        expectedSeq: Long,
        rawJson: String,
    ) {
        val operation = JSONObject(rawJson)
        require(operation.getString("deviceId") == peerDeviceId) { "同步设备标识不匹配" }
        require(operation.getLong("seq") == expectedSeq) { "同步序号与路径不匹配" }
        require(operation.getLong("createdAt") > 0) { "同步操作时间无效" }
        val entityVersion = EntityVersion.fromJson(operation.getJSONObject("entityVersion"))
        val entityId = operation.getString("entityId")
        val db = writableDatabase
        db.beginTransaction()
        try {
            when (operation.getString("operation")) {
                "upsert_post" ->
                    applyRemotePost(
                        db,
                        operation,
                        entityId,
                        entityVersion,
                    )
                "delete_post" ->
                    applyRemoteDeletePost(
                        db,
                        operation,
                        entityId,
                        entityVersion,
                    )
                "delete_media" ->
                    applyRemoteDeleteMedia(
                        db,
                        operation,
                        entityId,
                        entityVersion,
                    )
                else -> throw IllegalArgumentException("未知 R2 同步操作")
            }
            observeLogicalVersion(db, entityVersion.version)
            updatePeerHighWater(db, repositoryId, peerDeviceId, expectedSeq)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listMissingPreviews(limit: Int = 100): List<MissingPreview> {
        val db = readableDatabase
        val avatars =
            db.rawQuery(
                """
                SELECT id, author_avatar_sha256
                FROM posts
                WHERE has_author_avatar = 1
                  AND author_avatar_sha256 IS NOT NULL
                  AND local_avatar_path IS NULL
                ORDER BY saved_at DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(MissingPreview(cursor.getString(0), null, "avatar", cursor.getString(1)))
                    }
                }
            }
        if (avatars.size >= limit) return avatars
        val media =
            db.rawQuery(
                """
                SELECT post_id, id, thumbnail_sha256
                FROM post_media
                WHERE local_thumbnail_path IS NULL
                ORDER BY post_id, sort_index
                LIMIT ?
                """.trimIndent(),
                arrayOf((limit - avatars.size).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            MissingPreview(
                                cursor.getString(0),
                                cursor.getString(1),
                                "thumbnail",
                                cursor.getString(2),
                            ),
                        )
                    }
                }
            }
        return avatars + media
    }

    fun updatePreviewPath(preview: MissingPreview, localPath: String) {
        if (preview.kind == "avatar") {
            writableDatabase.update(
                "posts",
                ContentValues().apply { put("local_avatar_path", localPath) },
                "id = ? AND author_avatar_sha256 = ?",
                arrayOf(preview.postId, preview.sha256),
            )
        } else {
            writableDatabase.update(
                "post_media",
                ContentValues().apply { put("local_thumbnail_path", localPath) },
                "id = ? AND thumbnail_sha256 = ?",
                arrayOf(preview.mediaId, preview.sha256),
            )
        }
    }

    fun originalDescriptor(mediaId: String): OriginalMediaDescriptor? =
        readableDatabase.rawQuery(
            """
            SELECT m.id, m.post_id, p.source_post_id, m.sort_index, m.media_type,
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
                sortIndex = cursor.getInt(3),
                mediaType = cursor.getString(4),
                mimeType = cursor.getString(5),
                sha256 = cursor.getString(6),
                expectedSize = cursor.getLong(7),
                localPath = cursor.getNullableString(8),
            )
        }

    fun updateOriginalPath(mediaId: String, sha256: String, localPath: String) {
        val values =
            ContentValues().apply {
                put("local_original_path", localPath)
                put("original_download_status", "cached")
                putNull("original_download_error")
            }
        writableDatabase.update(
            "post_media",
            values,
            "id = ? AND original_sha256 = ?",
            arrayOf(mediaId, sha256),
        )
    }

    fun writeSyncResult(error: String?) {
        val db = writableDatabase
        if (error == null) {
            db.delete("app_meta", "key = 'last_sync_error'", null)
        } else {
            writeMeta(db, "last_sync_error", error.take(300))
        }
    }

    fun clearSyncResult() {
        writableDatabase.delete("app_meta", "key = 'last_sync_error'", null)
    }

    fun isMediaShaReferenced(sha256: String): Boolean {
        val db = readableDatabase
        val referencedByCurrentState =
            db.rawQuery(
                """
                SELECT 1 FROM posts WHERE author_avatar_sha256 = ?
                UNION ALL
                SELECT 1 FROM post_media
                WHERE original_sha256 = ? OR thumbnail_sha256 = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(sha256, sha256, sha256),
            ).use { it.moveToFirst() }
        if (referencedByCurrentState) return true

        return db.rawQuery(
            "SELECT payload_json FROM sync_ops WHERE payload_json LIKE ?",
            arrayOf("%$sha256%"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (operationReferencesSha(cursor.getString(0), sha256)) return@use true
            }
            false
        }
    }

    private fun applyRemotePost(
        db: SQLiteDatabase,
        operation: JSONObject,
        entityId: String,
        entityVersion: EntityVersion,
    ) {
        val payload = operation.getJSONObject("payload")
        val post = payload.getJSONObject("post")
        require(post.getString("id") == entityId) { "同步帖子 ID 不匹配" }
        val sourcePostId = post.getString("sourcePostId")
        require(SHORTCODE_PATTERN.matches(sourcePostId)) { "同步帖子来源编号无效" }
        require(entityId == "instagram:$sourcePostId") {
            "同步帖子来源编号与 ID 不匹配"
        }
        val avatarSha = post.optionalString("authorAvatarSha256")
        require(avatarSha == null || SHA256_PATTERN.matches(avatarSha)) {
            "同步头像校验值无效"
        }
        require(post.getBoolean("hasAuthorAvatar") == (avatarSha != null)) {
            "同步头像状态与校验值不匹配"
        }
        val mediaArray = payload.getJSONArray("media")
        require(mediaArray.length() > 0) { "同步帖子没有媒体" }
        require(post.getInt("mediaCount") == mediaArray.length()) {
            "同步帖子媒体数量不匹配"
        }
        val mediaIds = linkedSetOf<String>()
        val sortIndexes = mutableSetOf<Int>()
        for (index in 0 until mediaArray.length()) {
            val media = mediaArray.getJSONObject(index)
            require(media.getString("postId") == entityId) { "同步媒体所属帖子不匹配" }
            val sortIndex = media.getInt("sortIndex")
            require(sortIndex >= 0 && sortIndexes.add(sortIndex)) { "同步媒体索引无效或重复" }
            val mediaId = media.getString("id")
            require(mediaId == "$entityId:$sortIndex" && mediaIds.add(mediaId)) {
                "同步媒体 ID 无效或重复"
            }
            val mediaType = media.getString("mediaType")
            require(mediaType in SUPPORTED_MEDIA_TYPES) {
                "同步媒体类型无效"
            }
            require(media.getString("mimeType").lowercase().startsWith("$mediaType/")) {
                "同步媒体 MIME 类型无效"
            }
            require(media.getInt("width") > 0 && media.getInt("height") > 0) {
                "同步媒体尺寸无效"
            }
            if (!media.isNull("durationMs")) {
                require(media.getLong("durationMs") > 0) { "同步媒体时长无效" }
            }
            require(media.getLong("originalSize") > 0) { "同步媒体大小无效" }
            require(SHA256_PATTERN.matches(media.getString("originalSha256"))) {
                "同步原媒体校验值无效"
            }
            require(SHA256_PATTERN.matches(media.getString("thumbnailSha256"))) {
                "同步缩略图校验值无效"
            }
        }
        require(post.getString("coverMediaId") in mediaIds) { "同步封面媒体不存在" }
        val incomingUpdatedAt = post.getLong("updatedAt")
        require(
            post.getLong("publishedAt") > 0 &&
                post.getLong("savedAt") > 0 &&
                incomingUpdatedAt > 0,
        ) {
            "同步帖子时间无效"
        }
        if (!shouldApplyEntity(db, ENTITY_POST, entityId, entityVersion)) {
            return
        }
        val previousMediaIds = mediaIdsForPost(db, entityId).toSet()
        val existing =
            db.rawQuery(
                "SELECT updated_at, sync_device_id, sync_seq, author_avatar_sha256, local_avatar_path " +
                    "FROM posts WHERE id = ?",
                arrayOf(entityId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else ExistingPost(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getLong(2),
                    cursor.getNullableString(3),
                    cursor.getNullableString(4),
                )
            }
        val avatarPath =
            existing?.avatarPath?.takeIf {
                existing.avatarSha256 == avatarSha && File(it).isFile
            }
        val values =
            ContentValues().apply {
                put("id", entityId)
                put("source_post_id", post.getString("sourcePostId"))
                put("source_url", post.getString("sourceUrl"))
                put("author_username", post.getString("authorUsername"))
                put("author_display_name", post.getString("authorDisplayName"))
                put("author_profile_url", post.getString("authorProfileUrl"))
                put("has_author_avatar", if (post.optBoolean("hasAuthorAvatar")) 1 else 0)
                put("author_avatar_sha256", avatarSha)
                put("caption", post.optString("caption"))
                put("published_at", post.getLong("publishedAt"))
                put("location_name", post.optionalString("locationName"))
                put("cover_media_id", post.getString("coverMediaId"))
                put("media_count", post.getInt("mediaCount"))
                put("saved_at", post.getLong("savedAt"))
                put("updated_at", incomingUpdatedAt)
                put("local_avatar_path", avatarPath)
                put("sync_device_id", entityVersion.deviceId)
                put("sync_seq", entityVersion.seq)
            }
        if (existing == null) db.insertOrThrow("posts", null, values)
        else db.update("posts", values.apply { remove("id") }, "id = ?", arrayOf(entityId))

        writeEntityState(
            db,
            ENTITY_POST,
            entityId,
            STATE_ACTIVE,
            entityVersion.version,
            entityVersion.deviceId,
            entityVersion.seq,
            incomingUpdatedAt,
        )

        for (index in 0 until mediaArray.length()) {
            val media = mediaArray.getJSONObject(index)
            val mediaId = media.getString("id")
            if (shouldApplyEntity(db, ENTITY_MEDIA, mediaId, entityVersion)) {
                upsertRemoteMedia(db, entityId, media)
                writeEntityState(
                    db,
                    ENTITY_MEDIA,
                    mediaId,
                    STATE_ACTIVE,
                    entityVersion.version,
                    entityVersion.deviceId,
                    entityVersion.seq,
                    incomingUpdatedAt,
                )
            }
        }
        (previousMediaIds - mediaIds).forEach { mediaId ->
            if (shouldApplyEntity(db, ENTITY_MEDIA, mediaId, entityVersion)) {
                db.delete("post_media", "id = ?", arrayOf(mediaId))
                writeEntityState(
                    db,
                    ENTITY_MEDIA,
                    mediaId,
                    STATE_DELETED,
                    entityVersion.version,
                    entityVersion.deviceId,
                    entityVersion.seq,
                    incomingUpdatedAt,
                )
            }
        }
        updatePostMediaSummary(
            db,
            entityId,
            entityVersion.deviceId,
            entityVersion.seq,
            incomingUpdatedAt,
        )
    }

    private fun applyRemoteDeletePost(
        db: SQLiteDatabase,
        operation: JSONObject,
        entityId: String,
        entityVersion: EntityVersion,
    ) {
        val sourcePostId = sourcePostIdFromEntityId(entityId)
        val payload = operation.getJSONObject("payload")
        val deletedAt = payload.getLong("deletedAt")
        require(deletedAt > 0) { "同步帖子删除时间无效" }
        if (!shouldApplyEntity(db, ENTITY_POST, entityId, entityVersion)) return

        writeEntityState(
            db,
            ENTITY_POST,
            entityId,
            STATE_DELETED,
            entityVersion.version,
            entityVersion.deviceId,
            entityVersion.seq,
            deletedAt,
        )
        db.delete("posts", "id = ?", arrayOf(entityId))
        db.delete(
            "capture_jobs",
            "source_post_id = ? AND status = 'completed'",
            arrayOf(sourcePostId),
        )
    }

    private fun applyRemoteDeleteMedia(
        db: SQLiteDatabase,
        operation: JSONObject,
        entityId: String,
        entityVersion: EntityVersion,
    ) {
        require(MEDIA_ID_PATTERN.matches(entityId)) { "同步媒体删除 ID 无效" }
        val payload = operation.getJSONObject("payload")
        val postId = payload.getString("postId")
        require(payload.getString("mediaId") == entityId) { "同步媒体删除 ID 不匹配" }
        require(entityId.startsWith("$postId:")) { "同步媒体删除所属帖子不匹配" }
        val deletedAt = payload.getLong("deletedAt")
        require(deletedAt > 0) { "同步媒体删除时间无效" }
        val sourcePostId = sourcePostIdFromEntityId(postId)
        if (!shouldApplyEntity(db, ENTITY_MEDIA, entityId, entityVersion)) return

        writeEntityState(
            db,
            ENTITY_MEDIA,
            entityId,
            STATE_DELETED,
            entityVersion.version,
            entityVersion.deviceId,
            entityVersion.seq,
            deletedAt,
        )
        db.delete("post_media", "id = ?", arrayOf(entityId))
        updatePostMediaSummary(
            db,
            postId,
            entityVersion.deviceId,
            entityVersion.seq,
            deletedAt,
        )
        db.delete(
            "capture_jobs",
            "source_post_id = ? AND status = 'completed'",
            arrayOf(sourcePostId),
        )
    }

    private fun upsertRemoteMedia(db: SQLiteDatabase, postId: String, media: JSONObject) {
        val mediaId = media.getString("id")
        val old =
            db.rawQuery(
                """
                SELECT original_sha256, local_original_path,
                       thumbnail_sha256, local_thumbnail_path
                FROM post_media WHERE id = ?
                """.trimIndent(),
                arrayOf(mediaId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else ExistingMedia(
                    cursor.getString(0),
                    cursor.getNullableString(1),
                    cursor.getString(2),
                    cursor.getNullableString(3),
                )
            }
        val originalSha = media.getString("originalSha256")
        val thumbnailSha = media.getString("thumbnailSha256")
        val originalPath = old?.originalPath?.takeIf { old.originalSha256 == originalSha && File(it).isFile }
        val thumbnailPath = old?.thumbnailPath?.takeIf { old.thumbnailSha256 == thumbnailSha && File(it).isFile }
        val values =
            ContentValues().apply {
                put("post_id", postId)
                put("sort_index", media.getInt("sortIndex"))
                put("media_type", media.getString("mediaType"))
                put("mime_type", media.getString("mimeType"))
                put("width", media.getInt("width"))
                put("height", media.getInt("height"))
                if (media.isNull("durationMs")) putNull("duration_ms")
                else put("duration_ms", media.getLong("durationMs"))
                put("original_size", media.getLong("originalSize"))
                put("original_sha256", originalSha)
                put("thumbnail_sha256", thumbnailSha)
                put("local_original_path", originalPath)
                put("local_thumbnail_path", thumbnailPath)
                put("original_download_status", if (originalPath == null) "remote" else "cached")
                putNull("original_download_error")
            }
        if (old == null) db.insertOrThrow("post_media", null, ContentValues(values).apply { put("id", mediaId) })
        else db.update("post_media", values, "id = ?", arrayOf(mediaId))
    }

    private fun updatePeerHighWater(
        db: SQLiteDatabase,
        repositoryId: String,
        peerDeviceId: String,
        seq: Long,
    ) {
        val values =
            ContentValues().apply {
                put("high_water_seq", seq)
                put("updated_at", System.currentTimeMillis())
            }
        val updated =
            db.update(
                "sync_peers",
                values,
                "repository_id = ? AND peer_device_id = ?",
                arrayOf(repositoryId, peerDeviceId),
            )
        if (updated == 0) {
            values.put("repository_id", repositoryId)
            values.put("peer_device_id", peerDeviceId)
            db.insertOrThrow("sync_peers", null, values)
        }
    }

    private fun sourcePostId(db: SQLiteDatabase, postId: String): String? =
        db.rawQuery(
            "SELECT source_post_id FROM posts WHERE id = ?",
            arrayOf(postId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun sourcePostIdFromEntityId(postId: String): String {
        require(postId.startsWith("instagram:")) { "同步帖子 ID 无效" }
        val sourcePostId = postId.removePrefix("instagram:")
        require(SHORTCODE_PATTERN.matches(sourcePostId)) { "同步帖子来源编号无效" }
        return sourcePostId
    }

    private fun mediaIdsForPost(db: SQLiteDatabase, postId: String): List<String> =
        db.rawQuery(
            "SELECT id FROM post_media WHERE post_id = ? ORDER BY sort_index",
            arrayOf(postId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun updatePostMediaSummary(
        db: SQLiteDatabase,
        postId: String,
        deviceId: String,
        seq: Long,
        updatedAt: Long,
    ) {
        val mediaIds = mediaIdsForPost(db, postId)
        if (mediaIds.isEmpty()) {
            db.delete("posts", "id = ?", arrayOf(postId))
            return
        }
        val currentCover =
            db.rawQuery(
                "SELECT cover_media_id FROM posts WHERE id = ?",
                arrayOf(postId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val values =
            ContentValues().apply {
                put("cover_media_id", currentCover?.takeIf(mediaIds::contains) ?: mediaIds.first())
                put("media_count", mediaIds.size)
                put("updated_at", updatedAt)
                put("sync_device_id", deviceId)
                put("sync_seq", seq)
            }
        db.update("posts", values, "id = ?", arrayOf(postId))
    }

    private fun insertSyncOperation(
        db: SQLiteDatabase,
        deviceId: String,
        seq: Long,
        operation: String,
        entityId: String,
        payloadJson: String,
        createdAt: Long,
    ) {
        val values =
            ContentValues().apply {
                put("device_id", deviceId)
                put("seq", seq)
                put("operation", operation)
                put("entity_id", entityId)
                put("payload_json", payloadJson)
                put("created_at", createdAt)
            }
        db.insertOrThrow("sync_ops", null, values)
    }

    private fun deletePostOperationJson(
        deviceId: String,
        seq: Long,
        entityVersion: EntityVersion,
        createdAt: Long,
        deletedAt: Long,
        postId: String,
    ): String =
        JSONObject()
            .put("deviceId", deviceId)
            .put("seq", seq)
            .put("entityVersion", entityVersion.toJson())
            .put("operation", "delete_post")
            .put("entityId", postId)
            .put("createdAt", createdAt)
            .put("payload", JSONObject().put("deletedAt", deletedAt))
            .toString()

    private fun deleteMediaOperationJson(
        deviceId: String,
        seq: Long,
        entityVersion: EntityVersion,
        createdAt: Long,
        deletedAt: Long,
        postId: String,
        mediaId: String,
    ): String =
        JSONObject()
            .put("deviceId", deviceId)
            .put("seq", seq)
            .put("entityVersion", entityVersion.toJson())
            .put("operation", "delete_media")
            .put("entityId", mediaId)
            .put("createdAt", createdAt)
            .put(
                "payload",
                JSONObject()
                    .put("postId", postId)
                    .put("mediaId", mediaId)
                    .put("deletedAt", deletedAt),
            )
            .toString()

    private fun shouldApplyEntity(
        db: SQLiteDatabase,
        entityType: String,
        entityId: String,
        entityVersion: EntityVersion,
    ): Boolean =
        db.rawQuery(
            """
            SELECT version, device_id, seq FROM sync_entity_states
            WHERE entity_type = ? AND entity_id = ?
            """.trimIndent(),
            arrayOf(entityType, entityId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use true
            compareVersion(
                entityVersion.version,
                entityVersion.deviceId,
                entityVersion.seq,
                cursor.getLong(0),
                cursor.getString(1),
                cursor.getLong(2),
            ) > 0
        }

    private fun writeEntityState(
        db: SQLiteDatabase,
        entityType: String,
        entityId: String,
        state: String,
        version: Long,
        deviceId: String,
        seq: Long,
        changedAt: Long,
    ) {
        val values =
            ContentValues().apply {
                put("entity_type", entityType)
                put("entity_id", entityId)
                put("state", state)
                put("version", version)
                put("device_id", deviceId)
                put("seq", seq)
                put("changed_at", changedAt)
            }
        db.insertWithOnConflict(
            "sync_entity_states",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun nextLogicalVersion(db: SQLiteDatabase): Long {
        val current = readMetaLong(db, "local_sync_version")
        if (current >= MAX_LOGICAL_VERSION) {
            throw ArchiveException("SYNC_VERSION_EXHAUSTED", "同步逻辑版本已耗尽，请清空数据后重试")
        }
        val next = current + 1
        writeMeta(db, "local_sync_version", next.toString())
        return next
    }

    private fun observeLogicalVersion(db: SQLiteDatabase, version: Long) {
        if (version > readMetaLong(db, "local_sync_version")) {
            writeMeta(db, "local_sync_version", version.toString())
        }
    }

    private fun readMetaLong(db: SQLiteDatabase, key: String): Long =
        db.rawQuery("SELECT value FROM app_meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0 else 0
        }

    private fun buildOperationJson(
        db: SQLiteDatabase,
        postId: String,
        deviceId: String,
        seq: Long,
        createdAt: Long,
        entityVersion: EntityVersion,
    ): String {
        val post =
            db.rawQuery("SELECT * FROM posts WHERE id = ?", arrayOf(postId)).use { cursor ->
                require(cursor.moveToFirst()) { "待同步帖子不存在" }
                JSONObject()
                    .put("id", postId)
                    .put("sourcePostId", cursor.getString(cursor.getColumnIndexOrThrow("source_post_id")))
                    .put("sourceUrl", cursor.getString(cursor.getColumnIndexOrThrow("source_url")))
                    .put("authorUsername", cursor.getString(cursor.getColumnIndexOrThrow("author_username")))
                    .put("authorDisplayName", cursor.getString(cursor.getColumnIndexOrThrow("author_display_name")))
                    .put("authorProfileUrl", cursor.getString(cursor.getColumnIndexOrThrow("author_profile_url")))
                    .put("hasAuthorAvatar", cursor.getInt(cursor.getColumnIndexOrThrow("has_author_avatar")) == 1)
                    .put("authorAvatarSha256", cursor.getNullableString("author_avatar_sha256"))
                    .put("caption", cursor.getString(cursor.getColumnIndexOrThrow("caption")))
                    .put("publishedAt", cursor.getLong(cursor.getColumnIndexOrThrow("published_at")))
                    .put("locationName", cursor.getNullableString("location_name"))
                    .put("coverMediaId", cursor.getString(cursor.getColumnIndexOrThrow("cover_media_id")))
                    .put("mediaCount", cursor.getInt(cursor.getColumnIndexOrThrow("media_count")))
                    .put("savedAt", cursor.getLong(cursor.getColumnIndexOrThrow("saved_at")))
                    .put("updatedAt", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }
        val mediaArray = JSONArray()
        db.rawQuery("SELECT * FROM post_media WHERE post_id = ? ORDER BY sort_index", arrayOf(postId)).use { cursor ->
            while (cursor.moveToNext()) {
                mediaArray.put(
                    JSONObject()
                        .put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                        .put("postId", postId)
                        .put("sortIndex", cursor.getInt(cursor.getColumnIndexOrThrow("sort_index")))
                        .put("mediaType", cursor.getString(cursor.getColumnIndexOrThrow("media_type")))
                        .put("mimeType", cursor.getString(cursor.getColumnIndexOrThrow("mime_type")))
                        .put("width", cursor.getInt(cursor.getColumnIndexOrThrow("width")))
                        .put("height", cursor.getInt(cursor.getColumnIndexOrThrow("height")))
                        .put("durationMs", cursor.getNullableLong("duration_ms"))
                        .put("originalSize", cursor.getLong(cursor.getColumnIndexOrThrow("original_size")))
                        .put("originalSha256", cursor.getString(cursor.getColumnIndexOrThrow("original_sha256")))
                        .put("thumbnailSha256", cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_sha256"))),
                )
            }
        }
        require(mediaArray.length() > 0) { "待同步帖子没有媒体" }
        return JSONObject()
            .put("deviceId", deviceId)
            .put("seq", seq)
            .put("entityVersion", entityVersion.toJson())
            .put("operation", "upsert_post")
            .put("entityId", postId)
            .put("createdAt", createdAt)
            .put("payload", JSONObject().put("post", post).put("media", mediaArray))
            .toString()
    }

    private fun compareVersion(
        leftTime: Long,
        leftDevice: String,
        leftSeq: Long,
        rightTime: Long,
        rightDevice: String,
        rightSeq: Long,
    ): Int {
        if (leftTime != rightTime) return leftTime.compareTo(rightTime)
        val deviceComparison = leftDevice.compareTo(rightDevice)
        return if (deviceComparison != 0) deviceComparison else leftSeq.compareTo(rightSeq)
    }

    private fun Cursor.getNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getNullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun operationReferencesSha(rawJson: String, sha256: String): Boolean =
        runCatching {
            val payload = JSONObject(rawJson).optJSONObject("payload") ?: return@runCatching false
            val post = payload.optJSONObject("post")
            if (post != null &&
                !post.isNull("authorAvatarSha256") &&
                post.optString("authorAvatarSha256").equals(sha256, ignoreCase = true)
            ) {
                return@runCatching true
            }
            val media = payload.optJSONArray("media") ?: return@runCatching false
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index) ?: continue
                if (item.optString("originalSha256").equals(sha256, ignoreCase = true) ||
                    item.optString("thumbnailSha256").equals(sha256, ignoreCase = true)
                ) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)

    private data class ExistingPost(
        val updatedAt: Long,
        val deviceId: String,
        val seq: Long,
        val avatarSha256: String?,
        val avatarPath: String?,
    )

    private data class ExistingMedia(
        val originalSha256: String,
        val originalPath: String?,
        val thumbnailSha256: String,
        val thumbnailPath: String?,
    )

    private data class EntityStateSnapshot(
        val entityType: String,
        val entityId: String,
        val state: String,
        val entityVersion: EntityVersion,
        val changedAt: Long,
    )

    private fun nextLocalSeq(db: SQLiteDatabase): Long {
        val current =
            db.rawQuery("SELECT value FROM app_meta WHERE key = 'local_sync_seq'", null).use {
                if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0 else 0
            }
        return nextSequence(current)
    }

    private fun nextSequence(current: Long): Long {
        if (current >= Long.MAX_VALUE) {
            throw ArchiveException("SYNC_SEQUENCE_EXHAUSTED", "同步操作序号已耗尽，请清空数据后重试")
        }
        return current + 1
    }

    private fun writeMeta(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply { put("key", key); put("value", value) }
        db.insertWithOnConflict("app_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun findJobBySource(db: SQLiteDatabase, sourcePostId: String): CaptureJob? =
        db.rawQuery(
            "SELECT * FROM capture_jobs WHERE source_post_id = ? LIMIT 1",
            arrayOf(sourcePostId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCaptureJob() else null }

    private fun createBaseSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE posts (
                id TEXT PRIMARY KEY,
                source_post_id TEXT NOT NULL UNIQUE,
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
                sync_device_id TEXT NOT NULL DEFAULT '',
                sync_seq INTEGER NOT NULL DEFAULT 0
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
                original_download_status TEXT NOT NULL DEFAULT 'remote',
                original_download_error TEXT,
                UNIQUE(post_id, sort_index)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX post_media_post_id ON post_media(post_id, sort_index)")
    }

    private fun createRuntimeSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS capture_jobs (
                id TEXT PRIMARY KEY,
                source_url TEXT NOT NULL,
                source_post_id TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                progress_current INTEGER NOT NULL DEFAULT 0,
                progress_total INTEGER NOT NULL DEFAULT 0,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER,
                error_code TEXT,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS capture_jobs_status ON capture_jobs(status, created_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_ops (
                device_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                operation TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(device_id, seq)
            )
            """.trimIndent(),
        )
        createSyncUploadsSchema(db)
        createEntityStateSchema(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_peers (
                repository_id TEXT NOT NULL,
                peer_device_id TEXT NOT NULL,
                high_water_seq INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(repository_id, peer_device_id)
            )
            """.trimIndent(),
        )
    }

    private fun createSyncUploadsSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_uploads (
                repository_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                uploaded_at INTEGER,
                last_error TEXT,
                PRIMARY KEY(repository_id, device_id, seq),
                FOREIGN KEY(device_id, seq) REFERENCES sync_ops(device_id, seq) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS sync_uploads_pending " +
                "ON sync_uploads(repository_id, uploaded_at, seq)",
        )
    }

    private fun createEntityStateSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_entity_states (
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                state TEXT NOT NULL,
                version INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                changed_at INTEGER NOT NULL,
                PRIMARY KEY(entity_type, entity_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS sync_entity_states_state " +
                "ON sync_entity_states(entity_type, state, entity_id)",
        )
    }

    private fun Cursor.toCaptureJob(): CaptureJob =
        CaptureJob(
            id = getString(getColumnIndexOrThrow("id")),
            sourcePostId = getString(getColumnIndexOrThrow("source_post_id")),
            status = getString(getColumnIndexOrThrow("status")),
            attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
        )

    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        const val DATABASE_NAME = "photobook.db"
        const val DATABASE_VERSION = 1
        private const val RATE_LIMIT_MS = 30L * 60L * 1000L
        private const val ENTITY_POST = "post"
        private const val ENTITY_MEDIA = "media"
        private const val STATE_ACTIVE = "active"
        private const val STATE_DELETED = "deleted"
        private const val MAX_LOGICAL_VERSION = Long.MAX_VALUE - 1
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val SHORTCODE_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private val MEDIA_ID_PATTERN = Regex("^instagram:[A-Za-z0-9_-]+:[0-9]+$")
        private val SUPPORTED_MEDIA_TYPES = setOf("image", "video")

        private fun retryDelayMs(attemptCount: Int): Long {
            val exponent = (attemptCount - 1).coerceIn(0, 5)
            return (60_000L shl exponent).coerceAtMost(30L * 60L * 1000L)
        }
    }
}

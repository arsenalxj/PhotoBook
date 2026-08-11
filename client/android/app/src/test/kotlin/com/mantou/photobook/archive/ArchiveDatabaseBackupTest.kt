package com.mantou.photobook.archive

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchiveDatabaseBackupTest {
    private lateinit var context: Context
    private lateinit var database: ArchiveDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        database = ArchiveDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
    }

    @Test
    fun `archive without R2 remains local and creates no backup job`() {
        val sourcePostId = "LocalOnly1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)

        database.commitCompletedJob(job.id, preparedPost(sourcePostId), LOCAL_DEVICE)

        assertNotNull(database.originalDescriptor("instagram:$sourcePostId:0"))
        assertFalse(database.hasPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE))
    }

    @Test
    fun `archive transaction stores generation snapshot and backup job`() {
        val sourcePostId = "Backup1"
        val postId = "instagram:$sourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)

        database.commitCompletedJob(job.id, preparedPost(sourcePostId), LOCAL_DEVICE, BACKUP_TARGET)

        val backup = database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).single()
        assertEquals(postId, backup.postId)
        assertEquals(1L, backup.generation)
        assertEquals(1L, backup.backupSeq)
        val snapshot = JSONObject(backup.snapshotJson)
        assertEquals(LOCAL_DEVICE, snapshot.getString("deviceId"))
        assertEquals(1L, snapshot.getLong("generation"))
        assertEquals(postId, snapshot.getJSONObject("post").getString("id"))
    }

    @Test
    fun `backup job insert failure rolls back archive completion`() {
        database.writableDatabase.insertOrThrow(
            "app_meta",
            null,
            ContentValues().apply {
                put("key", "local_backup_seq")
                put("value", (Long.MAX_VALUE - 1).toString())
            },
        )
        val sourcePostId = "Rollback1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        val claimed = database.claimNextJob()!!
        assertTrue(
            database.updateJobProgress(
                job.id,
                claimed.attemptCount,
                "fetching",
                "committing",
                1,
                1,
            ),
        )

        assertThrows(ArchiveException::class.java) {
            database.commitCompletedJob(
                job.id,
                claimed.attemptCount,
                preparedPost(sourcePostId),
                BackupDestination(BACKUP_TARGET, LOCAL_DEVICE),
            )
        }

        assertNull(database.originalDescriptor("instagram:$sourcePostId:0"))
        assertEquals("committing", captureStatus(job.id))
    }

    @Test
    fun `partial local deletion keeps generation and pending snapshot unchanged`() {
        val sourcePostId = "PartialDelete1"
        val postId = "instagram:$sourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, mediaCount = 2),
            LOCAL_DEVICE,
            BACKUP_TARGET,
        )
        val before = database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).single()

        val result = database.deleteMediaSelection(postId, listOf("$postId:0"))

        assertFalse(result.postDeleted)
        assertNull(database.originalDescriptor("$postId:0"))
        assertNotNull(database.originalDescriptor("$postId:1"))
        val after = database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).single()
        assertEquals(before, after)
        assertEquals(1L, postGeneration(postId))
    }

    @Test
    fun `deleting whole post keeps pending backup and generation history`() {
        val sourcePostId = "DeletePost1"
        val postId = "instagram:$sourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(job.id, preparedPost(sourcePostId), LOCAL_DEVICE, BACKUP_TARGET)

        database.deletePost(postId)

        assertNull(database.originalDescriptor("$postId:0"))
        assertEquals(postId, database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).single().postId)
        assertEquals(1L, generationHighWater(postId))
    }

    @Test
    fun `rearchive after deletion increments generation without delete events`() {
        val sourcePostId = "Rearchive1"
        val postId = "instagram:$sourcePostId"
        val first = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(first.id, preparedPost(sourcePostId), LOCAL_DEVICE, BACKUP_TARGET)
        database.deletePost(postId)
        val second = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)

        database.commitCompletedJob(second.id, preparedPost(sourcePostId), LOCAL_DEVICE, BACKUP_TARGET)

        assertEquals(2L, postGeneration(postId))
        assertEquals(
            listOf(1L, 2L),
            database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).map { it.generation },
        )
    }

    @Test
    fun `target activation seeds active posts and excludes deleted posts`() {
        val activeId = archive("SeedActive1")
        val deletedId = archive("SeedDelete1")
        database.deletePost(deletedId)

        database.activateBackupTarget(BACKUP_TARGET, LOCAL_DEVICE)

        assertEquals(
            listOf(activeId),
            database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).map { it.postId },
        )
    }

    @Test
    fun `target activation after partial deletion snapshots remaining physical media`() {
        val sourcePostId = "SeedPartial1"
        val postId = archive(sourcePostId, mediaCount = 3)
        database.deleteMediaSelection(postId, listOf("$postId:1"))

        database.activateBackupTarget(BACKUP_TARGET, LOCAL_DEVICE)

        val snapshot =
            JSONObject(
                database.listPendingBackupJobs(BACKUP_TARGET, LOCAL_DEVICE).single().snapshotJson,
            )
        val media = snapshot.getJSONArray("media")
        assertEquals(listOf(0, 2), List(media.length()) { media.getJSONObject(it).getInt("sortIndex") })
        assertEquals(1L, snapshot.getLong("generation"))
    }

    @Test
    fun `invalid media selection rolls back without partial deletion`() {
        val firstPost = archive("Selection1", mediaCount = 2)
        val otherPost = archive("Selection2")

        assertThrows(ArchiveException::class.java) {
            database.deleteMediaSelection(
                firstPost,
                listOf("$firstPost:0", "$otherPost:0"),
            )
        }

        assertNotNull(database.originalDescriptor("$firstPost:0"))
        assertNotNull(database.originalDescriptor("$firstPost:1"))
    }

    @Test
    fun `live photo selection removes still and motion atomically`() {
        val sourcePostId = "LiveDelete1"
        val postId = "instagram:$sourcePostId"
        val base = preparedPost(sourcePostId, mediaCount = 3)
        val livePost =
            base.copy(
                media =
                    listOf(
                        base.media[0].copy(logicalIndex = 0, mediaRole = MEDIA_ROLE_LIVE_STILL),
                        base.media[1].copy(
                            logicalIndex = 0,
                            mediaRole = MEDIA_ROLE_LIVE_MOTION,
                            mediaType = "video",
                            mimeType = "video/mp4",
                        ),
                        base.media[2].copy(logicalIndex = 1),
                    ),
            )
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(job.id, livePost, LOCAL_DEVICE)

        assertThrows(ArchiveException::class.java) {
            database.deleteMediaSelection(postId, listOf("$postId:1"))
        }
        val result = database.deleteMediaSelection(postId, listOf("$postId:0"))

        assertFalse(result.postDeleted)
        assertNull(database.originalDescriptor("$postId:0"))
        assertNull(database.originalDescriptor("$postId:1"))
        assertNotNull(database.originalDescriptor("$postId:2"))
    }

    private fun archive(sourcePostId: String, mediaCount: Int = 1): String {
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(job.id, preparedPost(sourcePostId, mediaCount), LOCAL_DEVICE)
        return "instagram:$sourcePostId"
    }

    private fun preparedPost(sourcePostId: String, mediaCount: Int = 1): PreparedPost =
        PreparedPost(
            sourcePostId = sourcePostId,
            sourceUrl = "https://www.instagram.com/p/$sourcePostId/",
            authorUsername = "author",
            authorDisplayName = "Author",
            authorProfileUrl = "https://www.instagram.com/author/",
            authorAvatarSha256 = null,
            localAvatarPath = null,
            caption = sourcePostId,
            publishedAt = 1_750_000_000_000,
            locationName = null,
            media =
                List(mediaCount) { index ->
                    val original = File(context.cacheDir, "$sourcePostId-$index.jpg")
                        .apply { writeText("original-$index") }
                    val thumbnail = File(context.cacheDir, "$sourcePostId-$index-thumb.jpg")
                        .apply { writeText("thumbnail-$index") }
                    val hash = index.toString().repeat(64)
                    PreparedMedia(
                        sortIndex = index,
                        mediaType = "image",
                        mimeType = "image/jpeg",
                        width = 10,
                        height = 10,
                        durationMs = null,
                        originalSize = original.length(),
                        originalSha256 = hash,
                        thumbnailSha256 = hash,
                        localOriginalPath = original.absolutePath,
                        localThumbnailPath = thumbnail.absolutePath,
                    )
                },
        )

    private fun captureStatus(jobId: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM capture_jobs WHERE id = ?",
            arrayOf(jobId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun postGeneration(postId: String): Long =
        database.readableDatabase.rawQuery(
            "SELECT backup_generation FROM posts WHERE id = ?",
            arrayOf(postId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun generationHighWater(postId: String): Long =
        database.readableDatabase.rawQuery(
            "SELECT generation FROM post_backup_generations WHERE post_id = ?",
            arrayOf(postId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    companion object {
        private const val LOCAL_DEVICE = "local-device-0001"
        private const val BACKUP_TARGET = "target-a"
    }
}

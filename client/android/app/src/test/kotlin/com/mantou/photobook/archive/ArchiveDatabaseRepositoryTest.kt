package com.mantou.photobook.archive

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
class ArchiveDatabaseRepositoryTest {
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
    fun `running cancellation waits for cleanup before retry`() {
        val sourcePostId = "CancelRetry1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        val firstAttempt = database.claimNextJob()!!

        assertEquals(JobCancellationResult.RUNNING, database.cancelJob(job.id))
        assertEquals("cancelling", jobState(job.id).first)
        assertFalse(database.retryJob(job.id))
        assertFalse(
            database.updateJobProgress(
                job.id,
                firstAttempt.attemptCount,
                "fetching",
                "downloading",
                0,
                1,
            ),
        )
        assertTrue(database.completeJobCancellation(job.id, firstAttempt.attemptCount))
        assertEquals(Pair("failed", "CANCELLED"), jobState(job.id))
        assertTrue(database.retryJob(job.id))

        val secondAttempt = database.claimNextJob()!!
        assertEquals(firstAttempt.attemptCount + 1, secondAttempt.attemptCount)
        assertFalse(
            database.recordJobError(
                job.id,
                firstAttempt.attemptCount,
                ArchiveException("INTERNAL_ERROR", "旧 attempt"),
            ),
        )
        assertEquals(1, database.activeJobCount())
        assertEquals(0, database.failedJobCount())
    }

    @Test
    fun `cancelled or deleted job rejects old attempt commit`() {
        val sourcePostId = "CancelCommit1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        val attempt = database.claimNextJob()!!
        assertTrue(
            database.updateJobProgress(
                job.id,
                attempt.attemptCount,
                "fetching",
                "committing",
                1,
                1,
            ),
        )
        assertEquals(JobCancellationResult.RUNNING, database.cancelJob(job.id))
        assertFalse(
            database.commitCompletedJob(
                job.id,
                attempt.attemptCount,
                preparedPost(sourcePostId, "不应提交", '0'),
                LOCAL_DEVICE,
            ),
        )
        assertFalse(database.deleteJob(job.id))
        assertTrue(database.completeJobCancellation(job.id, attempt.attemptCount))
        assertTrue(database.deleteJob(job.id))
        assertFalse(
            database.commitCompletedJob(
                job.id,
                attempt.attemptCount,
                preparedPost(sourcePostId, "删除后也不应提交", '0'),
                LOCAL_DEVICE,
            ),
        )
        assertNull(database.originalDescriptor("instagram:$sourcePostId:0"))
    }

    @Test
    fun `only active jobs can cancel and only failed jobs can delete`() {
        val sourcePostId = "CancelDelete1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)

        assertFalse(database.deleteJob(job.id))
        assertEquals(JobCancellationResult.QUEUED, database.cancelJob(job.id))
        assertNull(database.cancelJob(job.id))
        assertEquals(1, database.failedJobCount())
        assertTrue(database.deleteJob(job.id))
        assertFalse(database.deleteJob(job.id))
        assertEquals(0, database.failedJobCount())
    }

    @Test
    fun `job source URL returns stored value and missing job uses job not found`() {
        val sourceUrl = "https://www.instagram.com/p/CopySource1/"
        val job = database.enqueue(sourceUrl, "CopySource1")

        assertEquals(sourceUrl, database.jobSourceUrl(job.id))
        val error = assertThrows(ArchiveException::class.java) {
            database.jobSourceUrl("missing-job")
        }
        assertEquals("JOB_NOT_FOUND", error.code)
    }

    @Test
    fun `recovery finalizes cancellation but requeues interrupted work`() {
        val cancellingId = "CancelRecover1"
        val cancelling =
            database.enqueue("https://www.instagram.com/p/$cancellingId/", cancellingId)
        val cancellingAttempt = database.claimNextJob()!!
        assertEquals(JobCancellationResult.RUNNING, database.cancelJob(cancelling.id))

        val interruptedId = "InterruptedRecover1"
        val interrupted =
            database.enqueue("https://www.instagram.com/p/$interruptedId/", interruptedId)
        assertEquals(interrupted.id, database.claimNextJob()!!.id)

        assertTrue(database.recoverInterruptedJobs())

        assertEquals(Pair("failed", "CANCELLED"), jobState(cancelling.id))
        assertEquals(Pair("queued", null), jobState(interrupted.id))
        assertFalse(database.completeJobCancellation(cancelling.id, cancellingAttempt.attemptCount))
    }

    @Test
    fun `new repository appends snapshot of current remote winner`() {
        val sourcePostId = "RepoSwitch1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "本机旧正文", '1'),
            LOCAL_DEVICE,
        )

        val remoteTime = System.currentTimeMillis() + 60_000
        database.applyRemoteOperation(
            "old-repository",
            PEER_DEVICE,
            1,
            preparedPost(sourcePostId, "远端当前正文", '2')
                .operationJson(PEER_DEVICE, 1, remoteTime),
        )

        database.seedRepositoryIfNeeded("new-repository", LOCAL_DEVICE)

        val operations = database.listPendingSyncOperations("new-repository", LOCAL_DEVICE)
        assertEquals(2, operations.size)
        val latestPost =
            JSONObject(operations.last().payloadJson)
                .getJSONObject("payload")
                .getJSONObject("post")
        assertEquals("远端当前正文", latestPost.getString("caption"))
    }

    @Test
    fun `repository snapshot carries remote post tombstone without changing its winner`() {
        val sourcePostId = "RemotePostTombstone1"
        val postId = "instagram:$sourcePostId"
        database.applyRemoteOperation(
            "old-repository",
            DELETE_DEVICE,
            1,
            preparedPost(sourcePostId, "远端帖子", 'c')
                .operationJson(DELETE_DEVICE, 1, 10, version = 1),
        )
        database.applyRemoteOperation(
            "old-repository",
            DELETE_DEVICE,
            2,
            deletePostOperation(DELETE_DEVICE, 2, 2, postId),
        )

        database.seedRepositoryIfNeeded("new-repository", LOCAL_DEVICE)

        val snapshot =
            database.listPendingSyncOperations("new-repository", LOCAL_DEVICE).single()
        assertEquals("delete_post", snapshot.operation)
        val entityVersion = JSONObject(snapshot.payloadJson).getJSONObject("entityVersion")
        assertEquals(2, entityVersion.getLong("version"))
        assertEquals(DELETE_DEVICE, entityVersion.getString("deviceId"))
        assertEquals(2, entityVersion.getLong("seq"))

        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        database = ArchiveDatabase(context)
        database.applyRemoteOperation(
            "new-repository",
            LOCAL_DEVICE,
            snapshot.seq,
            snapshot.payloadJson,
        )
        database.applyRemoteOperation(
            "new-repository",
            STALE_DEVICE,
            1,
            preparedPost(sourcePostId, "旧设备帖子", 'd')
                .operationJson(STALE_DEVICE, 1, 20, version = 1),
        )

        assertNull(database.originalDescriptor("$postId:0"))
    }

    @Test
    fun `repository snapshot carries remote media tombstone`() {
        val sourcePostId = "RemoteMediaTombstone1"
        val postId = "instagram:$sourcePostId"
        val deletedMediaId = "$postId:0"
        database.applyRemoteOperation(
            "old-repository",
            DELETE_DEVICE,
            1,
            preparedPost(sourcePostId, "两张图", 'e', mediaCount = 2)
                .operationJson(DELETE_DEVICE, 1, 10, version = 1),
        )
        database.applyRemoteOperation(
            "old-repository",
            DELETE_DEVICE,
            2,
            deleteMediaOperation(DELETE_DEVICE, 2, 2, postId, deletedMediaId),
        )

        database.seedRepositoryIfNeeded("new-repository", LOCAL_DEVICE)

        val snapshots = database.listPendingSyncOperations("new-repository", LOCAL_DEVICE)
        assertEquals(listOf("upsert_post", "delete_media"), snapshots.map { it.operation })
        val deleteSnapshot = snapshots.last()
        val entityVersion = JSONObject(deleteSnapshot.payloadJson).getJSONObject("entityVersion")
        assertEquals(2, entityVersion.getLong("version"))
        assertEquals(DELETE_DEVICE, entityVersion.getString("deviceId"))
        assertEquals(2, entityVersion.getLong("seq"))

        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        database = ArchiveDatabase(context)
        snapshots.forEach { snapshot ->
            database.applyRemoteOperation(
                "new-repository",
                LOCAL_DEVICE,
                snapshot.seq,
                snapshot.payloadJson,
            )
        }
        database.applyRemoteOperation(
            "new-repository",
            STALE_DEVICE,
            1,
            preparedPost(sourcePostId, "旧设备完整帖子", 'f', mediaCount = 2)
                .operationJson(STALE_DEVICE, 1, 20, version = 1),
        )

        assertNull(database.originalDescriptor(deletedMediaId))
        assertNotNull(database.originalDescriptor("$postId:1"))
    }

    @Test
    fun `maximum remote logical version is rejected before clock corruption`() {
        val invalidOperation =
            JSONObject(
                preparedPost("ClockRemote1", "极限版本", 'a')
                    .operationJson(PEER_DEVICE, 1, 10, version = 1),
            ).apply {
                getJSONObject("entityVersion").put("version", Long.MAX_VALUE)
            }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            database.applyRemoteOperation(
                "repository",
                PEER_DEVICE,
                1,
                invalidOperation,
            )
        }
        assertEquals(0, database.peerHighWater("repository", PEER_DEVICE))

        val sourcePostId = "ClockLocal1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "本机帖子", 'b'),
            LOCAL_DEVICE,
        )
        val operation =
            JSONObject(
                database.listPendingSyncOperations("repository", LOCAL_DEVICE).single().payloadJson,
            )
        assertEquals(1, operation.getJSONObject("entityVersion").getLong("version"))
    }

    @Test
    fun `invalid remote operations do not advance high water`() {
        val sourcePostId = "Validation1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "本机正文", '1'),
            LOCAL_DEVICE,
        )
        val valid =
            preparedPost(sourcePostId, "远端旧正文", '2')
                .operationJson(PEER_DEVICE, 1, 1)

        fun mutated(change: (JSONObject) -> Unit): String =
            JSONObject(valid).also(change).toString()

        val invalidOperations =
            listOf(
                mutated { operation ->
                    operation.getJSONObject("payload").getJSONObject("post").put("mediaCount", 2)
                },
                mutated { operation ->
                    operation.getJSONObject("payload").getJSONObject("post")
                        .put("coverMediaId", "instagram:$sourcePostId:99")
                },
                mutated { operation ->
                    operation.getJSONObject("payload").getJSONArray("media").getJSONObject(0)
                        .put("id", "instagram:$sourcePostId:99")
                },
                mutated { operation ->
                    operation.getJSONObject("payload").getJSONArray("media").getJSONObject(0)
                        .put("originalSha256", "../../outside")
                },
                mutated { operation ->
                    operation.getJSONObject("payload").getJSONObject("post")
                        .put("sourceUrl", "https://www.instagram.com/p/AnotherPost/")
                },
                mutated { operation -> operation.put("operation", "unsupported_operation") },
            )

        invalidOperations.forEach { rawJson ->
            assertThrows(IllegalArgumentException::class.java) {
                database.applyRemoteOperation(
                    "repository",
                    PEER_DEVICE,
                    1,
                    rawJson,
                )
            }
            assertEquals(0, database.peerHighWater("repository", PEER_DEVICE))
        }
    }

    @Test
    fun `deleting a media selection writes tombstones and keeps the post`() {
        val sourcePostId = "DeleteMedia1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "两张图", '3', mediaCount = 2),
            LOCAL_DEVICE,
        )

        val postId = "instagram:$sourcePostId"
        val result =
            database.deleteMediaSelection(
                postId,
                listOf("$postId:0"),
                LOCAL_DEVICE,
            )

        assertFalse(result.postDeleted)
        assertNull(database.originalDescriptor("instagram:$sourcePostId:0"))
        assertNotNull(database.originalDescriptor("instagram:$sourcePostId:1"))
        assertEquals(
            listOf("upsert_post", "delete_media"),
            database.listPendingSyncOperations("repository", LOCAL_DEVICE).map { it.operation },
        )
        assertEquals(
            "queued",
            database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId).status,
        )
    }

    @Test
    fun `selecting every media deletes the post in one transaction`() {
        val sourcePostId = "DeleteLast1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "单张图", '4'),
            LOCAL_DEVICE,
        )

        val postId = "instagram:$sourcePostId"
        val result =
            database.deleteMediaSelection(
                postId,
                listOf("$postId:0"),
                LOCAL_DEVICE,
            )

        assertTrue(result.postDeleted)
        assertNull(database.originalDescriptor("$postId:0"))
        assertEquals(
            listOf("upsert_post", "delete_post"),
            database.listPendingSyncOperations("repository", LOCAL_DEVICE).map { it.operation },
        )
        assertEquals(
            "queued",
            database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId).status,
        )
    }

    @Test
    fun `invalid batch media selection rolls back without partial deletion`() {
        val sourcePostId = "DeleteRollback1"
        val postId = "instagram:$sourcePostId"
        val otherSourcePostId = "DeleteRollback2"
        val otherPostId = "instagram:$otherSourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "两张图", 'd', mediaCount = 2),
            LOCAL_DEVICE,
        )
        val otherJob =
            database.enqueue(
                "https://www.instagram.com/p/$otherSourcePostId/",
                otherSourcePostId,
            )
        database.commitCompletedJob(
            otherJob.id,
            preparedPost(otherSourcePostId, "另一张图", 'e'),
            LOCAL_DEVICE,
        )

        assertThrows(ArchiveException::class.java) {
            database.deleteMediaSelection(
                postId,
                listOf("$postId:0", "$otherPostId:0"),
                LOCAL_DEVICE,
            )
        }

        assertNotNull(database.originalDescriptor("$postId:0"))
        assertNotNull(database.originalDescriptor("$postId:1"))
        assertEquals(
            listOf("upsert_post", "upsert_post"),
            database.listPendingSyncOperations("repository", LOCAL_DEVICE).map { it.operation },
        )
    }

    @Test
    fun `selecting every sparse logical media deletes the post`() {
        val sourcePostId = "DeleteSparse1"
        val postId = "instagram:$sourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        val base = preparedPost(sourcePostId, "稀疏索引", 'f', mediaCount = 2)
        database.commitCompletedJob(
            job.id,
            base.copy(
                media =
                    listOf(
                        base.media[0].copy(logicalIndex = 2),
                        base.media[1].copy(logicalIndex = 7),
                    ),
            ),
            LOCAL_DEVICE,
        )

        val result =
            database.deleteMediaSelection(
                postId,
                listOf("$postId:0", "$postId:1"),
                LOCAL_DEVICE,
            )

        assertTrue(result.postDeleted)
        assertNull(database.originalDescriptor("$postId:0"))
        assertNull(database.originalDescriptor("$postId:1"))
        assertEquals(
            listOf("upsert_post", "delete_post"),
            database.listPendingSyncOperations("repository", LOCAL_DEVICE).map { it.operation },
        )
    }

    @Test
    fun `remote media deletion clears completed job and allows rearchive`() {
        val sourcePostId = "RemoteDelete1"
        val postId = "instagram:$sourcePostId"
        val mediaId = "$postId:0"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "两张图", 'a', mediaCount = 2),
            LOCAL_DEVICE,
        )

        database.applyRemoteOperation(
            "repository",
            DELETE_DEVICE,
            1,
            deleteMediaOperation(DELETE_DEVICE, 1, 2, postId, mediaId),
        )

        assertNull(database.originalDescriptor(mediaId))
        assertEquals(
            "queued",
            database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId).status,
        )
    }

    @Test
    fun `repository snapshot with sparse media indexes can be received`() {
        val sourcePostId = "SparseSeed1"
        val postId = "instagram:$sourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "三张图", 'b', mediaCount = 3),
            LOCAL_DEVICE,
        )
        database.deleteMediaSelection(postId, listOf("$postId:1"), LOCAL_DEVICE)
        database.seedRepositoryIfNeeded("new-repository", LOCAL_DEVICE)
        val snapshot =
            database.listPendingSyncOperations("new-repository", LOCAL_DEVICE)
                .last { it.operation == "upsert_post" }
        val media = JSONObject(snapshot.payloadJson).getJSONObject("payload").getJSONArray("media")
        assertEquals(listOf(0, 2), List(media.length()) { media.getJSONObject(it).getInt("sortIndex") })

        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        database = ArchiveDatabase(context)
        database.applyRemoteOperation(
            "new-repository",
            LOCAL_DEVICE,
            snapshot.seq,
            snapshot.payloadJson,
        )

        assertNotNull(database.originalDescriptor("$postId:0"))
        assertNull(database.originalDescriptor("$postId:1"))
        assertNotNull(database.originalDescriptor("$postId:2"))
    }

    @Test
    fun `post tombstone rejects stale upsert and newer rearchive restores`() {
        val sourcePostId = "Restore1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "本机正文", '5'),
            LOCAL_DEVICE,
        )
        database.deletePost("instagram:$sourcePostId", LOCAL_DEVICE)

        database.applyRemoteOperation(
            "repository",
            PEER_DEVICE,
            1,
            preparedPost(sourcePostId, "远端旧正文", '6')
                .operationJson(PEER_DEVICE, 1, 10, version = 1),
        )
        assertNull(database.originalDescriptor("instagram:$sourcePostId:0"))

        database.applyRemoteOperation(
            "repository",
            PEER_DEVICE,
            2,
            preparedPost(sourcePostId, "重新归档", '7')
                .operationJson(PEER_DEVICE, 2, 20, version = 3),
        )
        assertNotNull(database.originalDescriptor("instagram:$sourcePostId:0"))
    }

    @Test
    fun `media tombstone can arrive before the post and blocks stale media`() {
        val sourcePostId = "DeleteFirst1"
        val postId = "instagram:$sourcePostId"
        val mediaId = "$postId:0"
        database.applyRemoteOperation(
            "repository",
            DELETE_DEVICE,
            1,
            deleteMediaOperation(DELETE_DEVICE, 1, 2, postId, mediaId),
        )

        database.applyRemoteOperation(
            "repository",
            PEER_DEVICE,
            1,
            preparedPost(sourcePostId, "迟到的旧帖子", '8')
                .operationJson(PEER_DEVICE, 1, 10, version = 1),
        )

        assertNull(database.originalDescriptor(mediaId))
    }

    @Test
    fun `concurrent media tombstones from different devices both converge`() {
        val sourcePostId = "ConcurrentDelete1"
        val postId = "instagram:$sourcePostId"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "三张图", '9', mediaCount = 3),
            LOCAL_DEVICE,
        )

        database.applyRemoteOperation(
            "repository",
            DELETE_DEVICE,
            1,
            deleteMediaOperation(DELETE_DEVICE, 1, 2, postId, "$postId:0"),
        )
        database.applyRemoteOperation(
            "repository",
            SECOND_DELETE_DEVICE,
            1,
            deleteMediaOperation(SECOND_DELETE_DEVICE, 1, 2, postId, "$postId:1"),
        )

        assertNull(database.originalDescriptor("$postId:0"))
        assertNull(database.originalDescriptor("$postId:1"))
        assertNotNull(database.originalDescriptor("$postId:2"))
        assertEquals(1, database.peerHighWater("repository", DELETE_DEVICE))
        assertEquals(1, database.peerHighWater("repository", SECOND_DELETE_DEVICE))
    }

    @Test
    fun `resolved short-link identity is bound before completion`() {
        val sourcePostId = "64abc"
        val shareUrl = "https://xhslink.com/a/test"
        val job =
            database.enqueue(
                sourceUrl = shareUrl,
                sourcePlatform = SOURCE_PLATFORM_XIAOHONGSHU,
                requestKey = shareUrl,
            )
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

        assertTrue(
            database.commitCompletedJob(
                job.id,
                claimed.attemptCount,
                preparedPost(
                    sourcePostId,
                    "小红书短链",
                    '8',
                    sourcePlatform = SOURCE_PLATFORM_XIAOHONGSHU,
                ),
                LOCAL_DEVICE,
            ),
        )
        val boundSourcePostId =
            database.readableDatabase.rawQuery(
                "SELECT source_post_id FROM capture_jobs WHERE id = ?",
                arrayOf(job.id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        assertEquals(sourcePostId, boundSourcePostId)

        database.deletePost("xiaohongshu:$sourcePostId", LOCAL_DEVICE)
        assertEquals(
            "queued",
            database.enqueue(
                sourceUrl = shareUrl,
                sourcePlatform = SOURCE_PLATFORM_XIAOHONGSHU,
                requestKey = shareUrl,
            ).status,
        )
    }

    @Test
    fun `deleting live photo removes still and motion atomically`() {
        val sourcePostId = "LiveDelete1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        val base = preparedPost(sourcePostId, "Live Photo", '7', mediaCount = 3)
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
                        base.media[2].copy(logicalIndex = 1, mediaRole = MEDIA_ROLE_PRIMARY),
                    ),
            )
        database.commitCompletedJob(job.id, livePost, LOCAL_DEVICE)

        val postId = "instagram:$sourcePostId"
        assertThrows(ArchiveException::class.java) {
            database.deleteMediaSelection(postId, listOf("$postId:1"), LOCAL_DEVICE)
        }
        assertNotNull(database.originalDescriptor("$postId:0"))
        assertNotNull(database.originalDescriptor("$postId:1"))

        val result = database.deleteMediaSelection(postId, listOf("$postId:0"), LOCAL_DEVICE)

        assertFalse(result.postDeleted)
        assertNull(database.originalDescriptor("instagram:$sourcePostId:0"))
        assertNull(database.originalDescriptor("instagram:$sourcePostId:1"))
        assertNotNull(database.originalDescriptor("instagram:$sourcePostId:2"))
        val deletedIds =
            database.readableDatabase.rawQuery(
                "SELECT entity_id FROM sync_ops WHERE operation = 'delete_media' ORDER BY seq",
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertEquals(
            listOf("instagram:$sourcePostId:1", "instagram:$sourcePostId:0"),
            deletedIds,
        )
    }

    private fun preparedPost(
        sourcePostId: String,
        caption: String,
        hashChar: Char,
        mediaCount: Int = 1,
        sourcePlatform: String = SOURCE_PLATFORM_INSTAGRAM,
    ): PreparedPost {
        return PreparedPost(
            sourcePostId = sourcePostId,
            sourceUrl =
                if (sourcePlatform == SOURCE_PLATFORM_XIAOHONGSHU) {
                    "https://www.xiaohongshu.com/explore/$sourcePostId"
                } else {
                    "https://www.instagram.com/p/$sourcePostId/"
                },
            authorUsername = "author",
            authorDisplayName = "Author",
            authorProfileUrl = "https://www.instagram.com/author/",
            authorAvatarSha256 = null,
            localAvatarPath = null,
            caption = caption,
            publishedAt = 1_750_000_000_000,
            locationName = null,
            sourcePlatform = sourcePlatform,
            media =
                List(mediaCount) { index ->
                    val original =
                        File(context.cacheDir, "$sourcePostId-$hashChar-$index.jpg")
                            .apply { writeText("$hashChar$index") }
                    val thumbnail =
                        File(context.cacheDir, "$sourcePostId-$hashChar-$index-thumb.jpg")
                            .apply { writeText("thumb-$hashChar$index") }
                    val hash = hashChar.toString().repeat(63) + index.toString()
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
    }

    private fun jobState(jobId: String): Pair<String, String?> =
        database.readableDatabase.rawQuery(
            "SELECT status, error_code FROM capture_jobs WHERE id = ?",
            arrayOf(jobId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "测试任务不存在" }
            Pair(
                cursor.getString(0),
                if (cursor.isNull(1)) null else cursor.getString(1),
            )
        }

    private fun deleteMediaOperation(
        deviceId: String,
        seq: Long,
        version: Long,
        postId: String,
        mediaId: String,
    ): String =
        JSONObject()
            .put("deviceId", deviceId)
            .put("seq", seq)
            .put("entityVersion", EntityVersion(version, deviceId, seq).toJson())
            .put("operation", "delete_media")
            .put("entityId", mediaId)
            .put("createdAt", 10)
            .put(
                "payload",
                JSONObject()
                    .put("postId", postId)
                    .put("mediaId", mediaId)
                    .put("deletedAt", 10),
            )
            .toString()

    private fun deletePostOperation(
        deviceId: String,
        seq: Long,
        version: Long,
        postId: String,
    ): String =
        JSONObject()
            .put("deviceId", deviceId)
            .put("seq", seq)
            .put("entityVersion", EntityVersion(version, deviceId, seq).toJson())
            .put("operation", "delete_post")
            .put("entityId", postId)
            .put("createdAt", 10)
            .put("payload", JSONObject().put("deletedAt", 10))
            .toString()

    companion object {
        private const val LOCAL_DEVICE = "local-device-0001"
        private const val PEER_DEVICE = "peer-device-0001"
        private const val DELETE_DEVICE = "delete-device-0001"
        private const val SECOND_DELETE_DEVICE = "delete-device-0002"
        private const val STALE_DEVICE = "stale-device-0001"
    }
}

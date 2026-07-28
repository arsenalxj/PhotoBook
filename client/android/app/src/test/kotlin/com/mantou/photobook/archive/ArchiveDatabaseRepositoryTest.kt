package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `deleting one media writes tombstone and keeps the post`() {
        val sourcePostId = "DeleteMedia1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "两张图", '3', mediaCount = 2),
            LOCAL_DEVICE,
        )

        val result = database.deleteMedia("instagram:$sourcePostId:0", LOCAL_DEVICE)

        assertEquals(false, result.postDeleteRequired)
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
    fun `deleting the last media requests post confirmation without mutation`() {
        val sourcePostId = "DeleteLast1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, "单张图", '4'),
            LOCAL_DEVICE,
        )

        val result = database.deleteMedia("instagram:$sourcePostId:0", LOCAL_DEVICE)

        assertEquals(true, result.postDeleteRequired)
        assertNotNull(database.originalDescriptor("instagram:$sourcePostId:0"))
        assertEquals(
            listOf("upsert_post"),
            database.listPendingSyncOperations("repository", LOCAL_DEVICE).map { it.operation },
        )
        assertEquals(
            "completed",
            database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId).status,
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
        database.deleteMedia("$postId:1", LOCAL_DEVICE)
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

    private fun preparedPost(
        sourcePostId: String,
        caption: String,
        hashChar: Char,
        mediaCount: Int = 1,
    ): PreparedPost {
        return PreparedPost(
            sourcePostId = sourcePostId,
            sourceUrl = "https://www.instagram.com/p/$sourcePostId/",
            authorUsername = "author",
            authorDisplayName = "Author",
            authorProfileUrl = "https://www.instagram.com/author/",
            authorAvatarSha256 = null,
            localAvatarPath = null,
            caption = caption,
            publishedAt = 1_750_000_000_000,
            locationName = null,
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

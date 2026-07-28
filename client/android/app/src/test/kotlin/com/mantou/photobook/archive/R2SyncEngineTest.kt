package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class R2SyncEngineTest {
    private lateinit var context: Context
    private lateinit var database: ArchiveDatabase
    private lateinit var config: R2Config

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
        database = ArchiveDatabase(context)
        config =
            R2Config.fromMap(
                mapOf(
                    "endpoint" to "https://example.r2.cloudflarestorage.com",
                    "bucket" to "photobook-test",
                    "prefix" to "photobook",
                    "accessKeyId" to "key",
                    "secretAccessKey" to "secret",
                ),
            )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
    }

    @Test
    fun `partial remote batch requests continuation until high water catches up`() {
        val store = FakeR2Store(config)
        store.addRemotePost(PEER_DEVICE, 1, "Remote1")
        store.addRemotePost(PEER_DEVICE, 2, "Remote2")
        val engine = engine(store, maxRemoteOpsPerPeer = 1)

        val first = engine.syncIfConfigured()
        assertTrue(first.hasRemainingWork)
        assertTrue(first.shouldRetry)
        assertEquals(1, database.peerHighWater(config.repositoryId, PEER_DEVICE))

        val second = engine.syncIfConfigured()
        assertFalse(second.hasRemainingWork)
        assertFalse(second.shouldRetry)
        assertEquals(2, database.peerHighWater(config.repositoryId, PEER_DEVICE))
    }

    @Test
    fun `remote read failure is returned as retryable result`() {
        val store = FakeR2Store(config, listError = IOException("offline"))

        val result = engine(store).syncIfConfigured()

        assertNotNull(result.error)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun `remote sequence gap does not advance high water`() {
        val store = FakeR2Store(config)
        store.addRemotePost(PEER_DEVICE, 2, "Remote2")

        val result = engine(store).syncIfConfigured()

        assertTrue(result.error.orEmpty().contains("缺少同步序号 1"))
        assertTrue(result.shouldRetry)
        assertEquals(0, database.peerHighWater(config.repositoryId, PEER_DEVICE))
    }

    @Test
    fun `remote batch emits one archive change event`() {
        val store = FakeR2Store(config)
        store.addRemotePost(PEER_DEVICE, 1, "Remote1")
        store.addRemotePost(PEER_DEVICE, 2, "Remote2")
        var eventCount = 0

        val result =
            engine(
                store = store,
                archiveChangedEmitter = { eventCount += 1 },
            ).syncIfConfigured()

        assertNull(result.error)
        assertEquals(2, database.peerHighWater(config.repositoryId, PEER_DEVICE))
        assertEquals(1, eventCount)
    }

    @Test
    fun `local upload batch requests continuation until every operation is uploaded`() {
        database.seedRepositoryIfNeeded(config.repositoryId, LOCAL_DEVICE)
        repeat(2) { index ->
            val sourcePostId = "LocalBatch${index + 1}"
            val original =
                writeManagedFile(
                    "originals",
                    "local-original-$index".toByteArray(),
                    ".jpg",
                )
            val thumbnail =
                writeManagedFile(
                    "thumbnails",
                    "local-thumbnail-$index".toByteArray(),
                    ".jpg",
                )
            val job =
                database.enqueue(
                    "https://www.instagram.com/p/$sourcePostId/",
                    sourcePostId,
                )
            database.commitCompletedJob(
                job.id,
                preparedPost(sourcePostId, original, thumbnail),
                LOCAL_DEVICE,
            )
        }
        val store = FakeR2Store(config)
        val engine = engine(store, maxLocalOpsPerBatch = 1)

        val first = engine.syncIfConfigured()
        assertTrue(first.hasRemainingWork)
        assertTrue(first.shouldRetry)
        assertEquals(1, store.immutableKeys.size)

        val second = engine.syncIfConfigured()
        assertFalse(second.hasRemainingWork)
        assertFalse(second.shouldRetry)
        assertEquals(2, store.immutableKeys.size)
    }

    @Test
    fun `delete operation uploads json without deleting content addressed media`() {
        database.seedRepositoryIfNeeded(config.repositoryId, LOCAL_DEVICE)
        val original = writeManagedFile("originals", "delete-original".toByteArray(), ".jpg")
        val thumbnail = writeManagedFile("thumbnails", "delete-thumbnail".toByteArray(), ".jpg")
        val sourcePostId = "DeleteSync1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, original, thumbnail),
            LOCAL_DEVICE,
        )
        val store = FakeR2Store(config)
        assertNull(engine(store).syncIfConfigured().error)
        val uploadedBeforeDelete = store.uploadedKeys.toSet()

        database.deletePost("instagram:$sourcePostId", LOCAL_DEVICE)
        val result = engine(store).syncIfConfigured()

        assertNull(result.error)
        assertEquals(uploadedBeforeDelete, store.uploadedKeys)
        assertTrue(store.removedKeys.isEmpty())
        assertEquals(2, store.immutableKeys.size)
        assertTrue(store.immutableKeys.all { it.startsWith("${config.basePrefix}/devices/") })
        val deleteOperation = JSONObject(store.readStoredJson(store.immutableKeys.last()))
        assertEquals("delete_post", deleteOperation.getString("operation"))
    }

    @Test
    fun `unknown video mime uses video fallback extension`() {
        database.seedRepositoryIfNeeded(config.repositoryId, LOCAL_DEVICE)
        val original = writeManagedFile("originals", "video".toByteArray(), ".mp4")
        val thumbnail = writeManagedFile("thumbnails", "thumbnail".toByteArray(), ".jpg")
        val sourcePostId = "VideoMime1"
        val job = database.enqueue("https://www.instagram.com/reel/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(
                sourcePostId,
                original,
                thumbnail,
                mediaType = "video",
                mimeType = "video/x-m4v",
            ),
            LOCAL_DEVICE,
        )
        val store = FakeR2Store(config)

        val result = engine(store).syncIfConfigured()

        assertNull(result.error)
        assertTrue(
            store.uploadedKeys.contains(
                store.key("media/originals/${original.nameWithoutExtension}.mp4"),
            ),
        )
    }

    @Test
    fun `historical operation uploads hashes from its own payload`() {
        database.seedRepositoryIfNeeded(config.repositoryId, LOCAL_DEVICE)
        val oldOriginal = writeManagedFile("originals", "old-original".toByteArray(), ".jpg")
        val oldThumbnail = writeManagedFile("thumbnails", "old-thumbnail".toByteArray(), ".jpg")
        val sourcePostId = "History1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, oldOriginal, oldThumbnail),
            LOCAL_DEVICE,
        )
        val store = FakeR2Store(config)
        val currentOriginal = File("/unused/${"c".repeat(64)}.jpg")
        val currentThumbnail = File("/unused/${store.previewSha}.jpg")
        database.applyRemoteOperation(
            config.repositoryId,
            PEER_DEVICE,
            1,
            preparedPost(sourcePostId, currentOriginal, currentThumbnail)
                .operationJson(PEER_DEVICE, 1, System.currentTimeMillis() + 60_000),
        )

        val result = engine(store).syncIfConfigured()

        assertNull(result.error)
        assertTrue(
            store.uploadedKeys.contains(
                store.key("media/originals/${oldOriginal.nameWithoutExtension}.jpg"),
            ),
        )
        assertTrue(
            store.uploadedKeys.contains(
                store.key("media/thumbnails/${oldThumbnail.nameWithoutExtension}.jpg"),
            ),
        )
        assertFalse(
            store.uploadedKeys.contains(
                store.key("media/originals/${currentOriginal.nameWithoutExtension}.jpg"),
            ),
        )
    }

    @Test
    fun `repository migration restores missing media before clearing source`() {
        val oldConfig = config.copy(prefix = "photobook-old")
        val oldStore = FakeR2Store(oldConfig)
        val newStore = FakeR2Store(config)
        val originalBytes = "migration-original".toByteArray()
        val thumbnailBytes = "migration-thumbnail".toByteArray()
        val original = writeManagedFile("originals", originalBytes, ".jpg")
        val thumbnail = writeManagedFile("thumbnails", thumbnailBytes, ".jpg")
        val sourcePostId = "Migration1"
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, original, thumbnail),
            LOCAL_DEVICE,
        )
        oldStore.addBinary(
            "media/originals/${original.nameWithoutExtension}.jpg",
            originalBytes,
        )
        oldStore.addBinary(
            "media/thumbnails/${thumbnail.nameWithoutExtension}.jpg",
            thumbnailBytes,
        )
        assertTrue(original.delete())
        assertTrue(thumbnail.delete())
        var migrationCleared = false
        val engine =
            R2SyncEngine(
                context = context,
                database = database,
                configProvider = { config },
                migrationSourceProvider = { oldConfig },
                storeFactory = { candidate ->
                    if (candidate.repositoryId == oldConfig.repositoryId) oldStore else newStore
                },
                deviceId = LOCAL_DEVICE,
                previewBatchSize = 10,
                migrationSourceClearer = { migrationCleared = true },
            )

        val result = engine.syncIfConfigured()

        assertNull(result.error)
        assertFalse(result.hasRemainingWork)
        assertTrue(original.isFile)
        assertTrue(thumbnail.isFile)
        assertTrue(migrationCleared)
        assertTrue(
            newStore.exists(
                newStore.key("media/originals/${original.nameWithoutExtension}.jpg"),
            ),
        )
    }

    private fun writeManagedFile(directory: String, bytes: ByteArray, extension: String): File {
        val sha256 = sha256(bytes)
        return File(context.filesDir, "archive/$directory/$sha256$extension").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun preparedPost(
        sourcePostId: String,
        original: File,
        thumbnail: File,
        mediaType: String = "image",
        mimeType: String = "image/jpeg",
    ): PreparedPost =
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
                listOf(
                    PreparedMedia(
                        sortIndex = 0,
                        mediaType = mediaType,
                        mimeType = mimeType,
                        width = 10,
                        height = 10,
                        durationMs = null,
                        originalSize = original.length().takeIf { it > 0 } ?: 8,
                        originalSha256 = original.nameWithoutExtension,
                        thumbnailSha256 = thumbnail.nameWithoutExtension,
                        localOriginalPath = original.absolutePath,
                        localThumbnailPath = thumbnail.absolutePath,
                    ),
                ),
        )

    private fun engine(
        store: R2Store,
        maxLocalOpsPerBatch: Int = 100,
        maxRemoteOpsPerPeer: Int = 1000,
        archiveChangedEmitter: () -> Unit = {},
    ): R2SyncEngine =
        R2SyncEngine(
            context = context,
            database = database,
            configProvider = { config },
            migrationSourceProvider = { null },
            storeFactory = { store },
            deviceId = LOCAL_DEVICE,
            maxLocalOpsPerBatch = maxLocalOpsPerBatch,
            maxRemoteOpsPerPeer = maxRemoteOpsPerPeer,
            previewBatchSize = 10,
            archiveChangedEmitter = archiveChangedEmitter,
        )

    private class FakeR2Store(
        private val config: R2Config,
        private val listError: Exception? = null,
    ) : R2Store {
        private val json = mutableMapOf<String, String>()
        private val binary = mutableMapOf<String, ByteArray>()
        val uploadedKeys = mutableSetOf<String>()
        val immutableKeys = mutableSetOf<String>()
        val removedKeys = mutableSetOf<String>()
        private val preview = "preview".toByteArray()
        val previewSha = sha256(preview)

        init {
            addBinary("media/thumbnails/$previewSha.jpg", preview)
        }

        fun addBinary(relativeKey: String, bytes: ByteArray) {
            binary[key(relativeKey)] = bytes
        }

        fun readStoredJson(objectKey: String): String = json.getValue(objectKey)

        fun addRemotePost(deviceId: String, seq: Long, shortcode: String) {
            val post =
                PreparedPost(
                    sourcePostId = shortcode,
                    sourceUrl = "https://www.instagram.com/p/$shortcode/",
                    authorUsername = "author",
                    authorDisplayName = "Author",
                    authorProfileUrl = "https://www.instagram.com/author/",
                    authorAvatarSha256 = null,
                    localAvatarPath = null,
                    caption = shortcode,
                    publishedAt = 1_750_000_000_000,
                    locationName = null,
                    media =
                        listOf(
                            PreparedMedia(
                                sortIndex = 0,
                                mediaType = "image",
                                mimeType = "image/jpeg",
                                width = 10,
                                height = 10,
                                durationMs = null,
                                originalSize = 8,
                                originalSha256 = "a".repeat(64),
                                thumbnailSha256 = previewSha,
                                localOriginalPath = "/unused/original.jpg",
                                localThumbnailPath = "/unused/thumbnail.jpg",
                            ),
                        ),
                )
            json[key("devices/index/$deviceId.json")] =
                JSONObject().put("deviceId", deviceId).toString()
            json[key("devices/$deviceId/manifest.json")] =
                JSONObject()
                    .put("deviceId", deviceId)
                    .put("lastSeq", seq)
                    .toString()
            json[key("devices/$deviceId/ops/${seq.toString().padStart(20, '0')}.json")] =
                post.operationJson(deviceId, seq, System.currentTimeMillis() + seq)
        }

        override fun testConnection() = Unit

        override fun putJson(objectKey: String, json: String) {
            this.json[objectKey] = json
        }

        override fun putImmutableJson(objectKey: String, json: String) {
            val existing = this.json[objectKey]
            check(existing == null || existing == json)
            this.json[objectKey] = json
            immutableKeys += objectKey
        }

        override fun readJson(objectKey: String): String = json.getValue(objectKey)

        override fun uploadFileIfMissing(objectKey: String, file: File, contentType: String) {
            if (binary.containsKey(objectKey)) return
            binary[objectKey] = file.readBytes()
            uploadedKeys += objectKey
        }

        override fun downloadTo(objectKey: String, target: File) {
            target.parentFile?.mkdirs()
            target.writeBytes(binary.getValue(objectKey))
        }

        override fun exists(objectKey: String): Boolean =
            json.containsKey(objectKey) || binary.containsKey(objectKey)

        override fun listDeviceIds(): List<String> {
            listError?.let { throw it }
            return json.keys
                .filter { it.contains("/devices/index/") }
                .map { it.substringAfterLast('/').removeSuffix(".json") }
        }

        override fun remove(objectKey: String) {
            removedKeys += objectKey
            json.remove(objectKey)
            binary.remove(objectKey)
        }

        override fun key(relative: String): String = "${config.basePrefix}/${relative.trimStart('/')}"

    }

    companion object {
        private const val LOCAL_DEVICE = "local-device-0001"
        private const val PEER_DEVICE = "peer-device-0001"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

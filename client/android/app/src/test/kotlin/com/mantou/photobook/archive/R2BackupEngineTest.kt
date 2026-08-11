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
class R2BackupEngineTest {
    private lateinit var context: Context
    private lateinit var database: ArchiveDatabase
    private lateinit var config: R2Config

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
        database = ArchiveDatabase(context)
        config = config("photobook-test")
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
    }

    @Test
    fun `uploads media then immutable snapshot then latest`() {
        archive("Order1", config.backupTargetId)
        val store = FakeR2Store(config)

        val result = engine(store).backupIfConfigured()

        assertNull(result.error)
        assertFalse(result.hasRemainingWork)
        val jobEvents = store.events.filterNot { it.endsWith("/device.json") }
        assertTrue(jobEvents[0].contains("/devices/$LOCAL_DEVICE/media/originals/"))
        assertTrue(jobEvents[1].contains("/devices/$LOCAL_DEVICE/media/thumbnails/"))
        assertTrue(jobEvents[2].contains("/snapshots/00000000000000000001.json"))
        assertTrue(jobEvents[3].endsWith("/latest.json"))
        val latest = JSONObject(store.json.getValue(jobEvents[3]))
        assertEquals(1L, latest.getLong("backupSeq"))
        assertEquals(1L, latest.getLong("generation"))
    }

    @Test
    fun `batch continuation uploads jobs in backup sequence`() {
        archive("Batch1", config.backupTargetId)
        archive("Batch2", config.backupTargetId)
        val store = FakeR2Store(config)
        val engine = engine(store, maxJobsPerBatch = 1)

        val first = engine.backupIfConfigured()
        assertTrue(first.hasRemainingWork)
        assertTrue(first.shouldRetry)

        val second = engine.backupIfConfigured()
        assertFalse(second.hasRemainingWork)
        val snapshots = store.events.filter { "/snapshots/" in it }
        assertEquals(2, snapshots.size)
        assertTrue(snapshots[0].endsWith("00000000000000000001.json"))
        assertTrue(snapshots[1].endsWith("00000000000000000002.json"))
    }

    @Test
    fun `first upload failure stays pending and stops later jobs`() {
        archive("Fail1", config.backupTargetId)
        archive("Fail2", config.backupTargetId)
        val store = FakeR2Store(config, uploadError = IOException("offline"))

        val result = engine(store).backupIfConfigured()

        assertTrue(result.shouldRetry)
        assertTrue(result.error.orEmpty().contains("R2 备份失败"))
        assertEquals(
            listOf(1L, 2L),
            database.listPendingBackupJobs(config.backupTargetId, LOCAL_DEVICE).map { it.backupSeq },
        )
    }

    @Test
    fun `deleting post does not cancel an immutable pending backup`() {
        val postId = archive("DeletePending1", config.backupTargetId)
        database.deletePost(postId)
        val store = FakeR2Store(config)

        val result = engine(store).backupIfConfigured()

        assertNull(result.error)
        assertTrue(store.events.any { "/snapshots/" in it })
        assertFalse(database.hasPendingBackupJobs(config.backupTargetId, LOCAL_DEVICE))
    }

    @Test
    fun `deleting backed up post creates no remote request`() {
        val postId = archive("DeleteComplete1", config.backupTargetId)
        val store = FakeR2Store(config)
        assertNull(engine(store).backupIfConfigured().error)
        val eventCount = store.events.size

        database.deletePost(postId)
        val result = engine(store).backupIfConfigured()

        assertNull(result.error)
        assertEquals(eventCount, store.events.size)
    }

    @Test
    fun `same installation restores missing original from its device folder`() {
        val postId = archive("Restore1", config.backupTargetId)
        val store = FakeR2Store(config)
        assertNull(engine(store).backupIfConfigured().error)
        val descriptor = database.originalDescriptor("$postId:0")!!
        assertTrue(File(descriptor.localPath!!).delete())

        val restored = engine(store).ensureOriginal(descriptor.mediaId)

        assertTrue(restored.isFile)
        assertEquals(descriptor.sha256, sha256(restored.readBytes()))
        assertTrue(store.downloadedKeys.single().contains("/devices/$LOCAL_DEVICE/media/originals/"))
    }

    @Test
    fun `another installation cannot restore from the old device folder`() {
        val postId = archive("RestoreOther1", config.backupTargetId)
        val store = FakeR2Store(config)
        assertNull(engine(store).backupIfConfigured().error)
        val descriptor = database.originalDescriptor("$postId:0")!!
        assertTrue(File(descriptor.localPath!!).delete())

        assertThrows(IOException::class.java) {
            engine(store, deviceId = OTHER_DEVICE).ensureOriginal(descriptor.mediaId)
        }
        assertTrue(store.downloadedKeys.last().contains("/devices/$OTHER_DEVICE/media/originals/"))
    }

    @Test
    fun `switching target drops old pending jobs and seeds only active posts`() {
        val keptPost = archive("SwitchKeep1", config.backupTargetId)
        val deletedPost = archive("SwitchDelete1", config.backupTargetId)
        database.deletePost(deletedPost)
        val newConfig = config("photobook-new")

        database.activateBackupTarget(newConfig.backupTargetId, LOCAL_DEVICE)

        assertFalse(database.hasPendingBackupJobs(config.backupTargetId, LOCAL_DEVICE))
        val newJobs = database.listPendingBackupJobs(newConfig.backupTargetId, LOCAL_DEVICE)
        assertEquals(listOf(keptPost), newJobs.map { it.postId })
    }

    @Test
    fun `rearchive after local deletion advances generation and snapshot sequence`() {
        val postId = archive("Rearchive1", config.backupTargetId)
        database.deletePost(postId)
        archive("Rearchive1", config.backupTargetId)

        val jobs = database.listPendingBackupJobs(config.backupTargetId, LOCAL_DEVICE)
        assertEquals(listOf(1L, 2L), jobs.map { it.generation })
        assertEquals(listOf(1L, 2L), jobs.map { it.backupSeq })
    }

    private fun archive(sourcePostId: String, backupTargetId: String): String {
        val original = writeManagedFile("originals", "original-$sourcePostId".toByteArray(), ".jpg")
        val thumbnail = writeManagedFile("thumbnails", "thumbnail-$sourcePostId".toByteArray(), ".jpg")
        val job = database.enqueue("https://www.instagram.com/p/$sourcePostId/", sourcePostId)
        database.commitCompletedJob(
            job.id,
            preparedPost(sourcePostId, original, thumbnail),
            LOCAL_DEVICE,
            backupTargetId,
        )
        return "instagram:$sourcePostId"
    }

    private fun preparedPost(sourcePostId: String, original: File, thumbnail: File): PreparedPost =
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
                        mediaType = "image",
                        mimeType = "image/jpeg",
                        width = 10,
                        height = 10,
                        durationMs = null,
                        originalSize = original.length(),
                        originalSha256 = original.nameWithoutExtension,
                        thumbnailSha256 = thumbnail.nameWithoutExtension,
                        localOriginalPath = original.absolutePath,
                        localThumbnailPath = thumbnail.absolutePath,
                    ),
                ),
        )

    private fun writeManagedFile(directory: String, bytes: ByteArray, extension: String): File {
        val sha256 = sha256(bytes)
        return File(context.filesDir, "archive/$directory/$sha256$extension").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun engine(
        store: R2Store,
        maxJobsPerBatch: Int = 25,
        deviceId: String = LOCAL_DEVICE,
    ): R2BackupEngine =
        R2BackupEngine(
            context = context,
            database = database,
            configProvider = { config },
            storeFactory = { store },
            deviceInfo = DeviceInfo(deviceId, 1_750_000_000_000),
            maxJobsPerBatch = maxJobsPerBatch,
            archiveChangedEmitter = {},
        )

    private fun config(prefix: String): R2Config =
        R2Config.fromMap(
            mapOf(
                "endpoint" to "https://example.r2.cloudflarestorage.com",
                "bucket" to "photobook-test",
                "prefix" to prefix,
                "accessKeyId" to "key",
                "secretAccessKey" to "secret",
            ),
        )

    private class FakeR2Store(
        private val config: R2Config,
        private val uploadError: Exception? = null,
    ) : R2Store {
        val json = mutableMapOf<String, String>()
        private val binary = mutableMapOf<String, ByteArray>()
        val events = mutableListOf<String>()
        val downloadedKeys = mutableListOf<String>()

        override fun putJson(objectKey: String, json: String) {
            this.json[objectKey] = json
            events += objectKey
        }

        override fun putImmutableJson(objectKey: String, json: String) {
            val existing = this.json[objectKey]
            if (existing != null && existing != json) {
                throw ArchiveException("BACKUP_CONFLICT", "不可变对象冲突")
            }
            this.json[objectKey] = json
            events += objectKey
        }

        override fun uploadFileIfMissing(
            objectKey: String,
            file: File,
            contentType: String,
            expectedSha256: String,
        ) {
            uploadError?.let { throw it }
            binary.putIfAbsent(objectKey, file.readBytes())
            events += objectKey
        }

        override fun downloadTo(objectKey: String, target: File) {
            downloadedKeys += objectKey
            val bytes = binary[objectKey] ?: throw IOException("missing")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }

        override fun key(relative: String): String = "${config.basePrefix}/${relative.trimStart('/')}"
    }

    companion object {
        private const val LOCAL_DEVICE = "local-device-0001"
        private const val OTHER_DEVICE = "other-device-0002"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

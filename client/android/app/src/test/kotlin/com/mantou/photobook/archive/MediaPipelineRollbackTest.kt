package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaPipelineRollbackTest {
    private lateinit var context: Context
    private lateinit var database: ArchiveDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
        database = ArchiveDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
    }

    @Test
    fun `discardJob removes new orphan, keeps referenced content and deletes job directory`() {
        val retainedSha = "a".repeat(64)
        val orphanSha = "b".repeat(64)
        val originals = File(context.filesDir, "archive/originals").apply { mkdirs() }
        val retained = File(originals, "$retainedSha.jpg").apply { writeText("retained") }
        val orphan = File(originals, "$orphanSha.jpg").apply { writeText("orphan") }
        val committed = database.enqueue("https://www.instagram.com/p/Retained1/", "Retained1")
        database.commitCompletedJob(
            committed.id,
            preparedPost("Retained1", retainedSha, retained),
            "local-device-0001",
        )
        val job = database.enqueue("https://www.instagram.com/p/Discard1/", "Discard1")
        val jobDirectory = File(context.filesDir, "archive/jobs/${job.id}").apply { mkdirs() }
        File(jobDirectory, "resume.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("remote", remotePost("Discard1").toJson())
                .put("completedMedia", JSONArray())
                .put(
                    "createdFilePaths",
                    JSONArray().put(retained.absolutePath).put(orphan.absolutePath),
                )
                .toString(),
        )
        val pipeline = MediaPipeline(context, database::isMediaShaReferenced)

        pipeline.discardJob(job.id)

        assertTrue(retained.isFile)
        assertFalse(orphan.exists())
        assertFalse(jobDirectory.exists())
    }

    private fun remotePost(sourcePostId: String): RemotePost =
        RemotePost(
            sourcePostId = sourcePostId,
            sourceUrl = "https://www.instagram.com/p/$sourcePostId/",
            authorUsername = "author",
            authorDisplayName = "Author",
            authorProfileUrl = "https://www.instagram.com/author/",
            authorAvatarUrl = null,
            caption = sourcePostId,
            publishedAt = 1_750_000_000_000,
            locationName = null,
            media =
                listOf(
                    RemoteMedia(
                        sortIndex = 0,
                        mediaType = "image",
                        url = "https://scontent.cdninstagram.com/$sourcePostId.jpg",
                        width = 10,
                        height = 10,
                        durationMs = null,
                    ),
                ),
        )

    private fun preparedPost(sourcePostId: String, sha256: String, file: File): PreparedPost =
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
                        originalSize = file.length(),
                        originalSha256 = sha256,
                        thumbnailSha256 = sha256,
                        localOriginalPath = file.absolutePath,
                        localThumbnailPath = file.absolutePath,
                    ),
                ),
        )
}

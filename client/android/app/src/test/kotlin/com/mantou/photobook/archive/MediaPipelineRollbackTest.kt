package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
    fun `rollback removes new orphan and keeps referenced content`() {
        val retainedSha = "a".repeat(64)
        val orphanSha = "b".repeat(64)
        val originals = File(context.filesDir, "archive/originals").apply { mkdirs() }
        val retained = File(originals, "$retainedSha.jpg").apply { writeText("retained") }
        val orphan = File(originals, "$orphanSha.jpg").apply { writeText("orphan") }
        val job = database.enqueue("https://www.instagram.com/p/Retained1/", "Retained1")
        database.commitCompletedJob(
            job.id,
            preparedPost("Retained1", retainedSha, retained),
            "local-device-0001",
        )
        val pipeline = MediaPipeline(context, database::isMediaShaReferenced)

        pipeline.rollbackPrepared(
            preparedPost("Rollback1", orphanSha, orphan).copy(
                createdFilePaths = listOf(retained.absolutePath, orphan.absolutePath),
            ),
        )

        assertTrue(retained.isFile)
        assertFalse(orphan.exists())
    }

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

package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
class ArchiveDatabaseCaptureTest {
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
        assertEquals("cancelling" to null, jobState(job.id))
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
        assertEquals("failed" to "CANCELLED", jobState(job.id))
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
                preparedPost(sourcePostId),
            ),
        )
        assertFalse(database.deleteJob(job.id))
        assertTrue(database.completeJobCancellation(job.id, attempt.attemptCount))
        assertTrue(database.deleteJob(job.id))
        assertFalse(
            database.commitCompletedJob(
                job.id,
                attempt.attemptCount,
                preparedPost(sourcePostId),
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
        val cancellingSourcePostId = "CancelRecover1"
        val cancelling =
            database.enqueue(
                "https://www.instagram.com/p/$cancellingSourcePostId/",
                cancellingSourcePostId,
            )
        val cancellingAttempt = database.claimNextJob()!!
        assertEquals(JobCancellationResult.RUNNING, database.cancelJob(cancelling.id))

        val interruptedSourcePostId = "InterruptedRecover1"
        val interrupted =
            database.enqueue(
                "https://www.instagram.com/p/$interruptedSourcePostId/",
                interruptedSourcePostId,
            )
        assertEquals(interrupted.id, database.claimNextJob()!!.id)

        assertTrue(database.recoverInterruptedJobs())

        assertEquals("failed" to "CANCELLED", jobState(cancelling.id))
        assertEquals("queued" to null, jobState(interrupted.id))
        assertFalse(database.completeJobCancellation(cancelling.id, cancellingAttempt.attemptCount))
    }

    @Test
    fun `resolved short link identity is bound before completion`() {
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
                preparedPost(sourcePostId, SOURCE_PLATFORM_XIAOHONGSHU),
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

        database.deletePost("xiaohongshu:$sourcePostId")
        assertEquals(
            "queued",
            database.enqueue(
                sourceUrl = shareUrl,
                sourcePlatform = SOURCE_PLATFORM_XIAOHONGSHU,
                requestKey = shareUrl,
            ).status,
        )
    }

    private fun preparedPost(
        sourcePostId: String,
        sourcePlatform: String = SOURCE_PLATFORM_INSTAGRAM,
    ): PreparedPost {
        val original = File(context.cacheDir, "$sourcePostId-original.jpg").apply { writeText("original") }
        val thumbnail = File(context.cacheDir, "$sourcePostId-thumbnail.jpg").apply { writeText("thumbnail") }
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
            caption = sourcePostId,
            publishedAt = 1_750_000_000_000,
            locationName = null,
            sourcePlatform = sourcePlatform,
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
                        originalSha256 = "1".repeat(64),
                        thumbnailSha256 = "2".repeat(64),
                        localOriginalPath = original.absolutePath,
                        localThumbnailPath = thumbnail.absolutePath,
                    ),
                ),
        )
    }

    private fun jobState(jobId: String): Pair<String, String?> =
        database.readableDatabase.rawQuery(
            "SELECT status, error_code FROM capture_jobs WHERE id = ?",
            arrayOf(jobId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "测试任务不存在" }
            cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getString(1)
        }
}

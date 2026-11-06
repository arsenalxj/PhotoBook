package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaPipelineResumeTest {
    private lateinit var context: Context
    private lateinit var database: ArchiveDatabase
    private lateinit var pipeline: MediaPipeline

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
        database = ArchiveDatabase(context)
        pipeline = MediaPipeline(context, database::isMediaShaReferenced)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
    }

    @Test
    fun `preparePost reuses completed media from resume without downloading`() {
        val job = database.enqueue("https://www.instagram.com/p/Resume1/", "Resume1")
        val remote = remotePost("Resume1", mediaCount = 1)
        val original = storedFile("archive/originals", "c".repeat(64), ".jpg")
        val thumbnail = storedFile("archive/thumbnails", "d".repeat(64), ".jpg")
        writeResume(job.id, remote, listOf(preparedMedia(0, original, thumbnail)))

        val prepared = pipeline.preparePost(job, remote, { true }) { _, _ -> }

        assertEquals(1, prepared.media.size)
        assertEquals("c".repeat(64), prepared.media[0].originalSha256)
        assertEquals(original.absolutePath, prepared.media[0].localOriginalPath)
    }

    @Test
    fun `preparePost keeps completed media and resume when a later item fails`() {
        val job = database.enqueue("https://www.instagram.com/p/Resume2/", "Resume2")
        val remote = remotePost("Resume2", mediaCount = 2)
        val original = storedFile("archive/originals", "e".repeat(64), ".jpg")
        val thumbnail = storedFile("archive/thumbnails", "f".repeat(64), ".jpg")
        val completed = preparedMedia(0, original, thumbnail)
        writeResume(job.id, remote, listOf(completed))

        try {
            pipeline.preparePost(job, remote, { true }) { _, _ -> }
            fail("第二个媒体地址无效时应当失败")
        } catch (error: ArchiveException) {
            assertEquals("INVALID_RESPONSE", error.code)
        }

        assertTrue(original.isFile)
        assertTrue(thumbnail.isFile)
        val resume = pipeline.loadResume(job.id)
        assertNotNull(resume)
        assertEquals(1, resume!!.completedMedia.size)
        assertEquals(0, resume.completedMedia[0].sortIndex)
    }

    @Test
    fun `preparePost ignores resume entries whose files are missing`() {
        val job = database.enqueue("https://www.instagram.com/p/Resume3/", "Resume3")
        val remote =
            remotePost("Resume3", mediaCount = 1).copy(
                media =
                    listOf(
                        RemoteMedia(
                            sortIndex = 0,
                            mediaType = "image",
                            // 无效域名，重新下载立即失败，不依赖真实网络。
                            url = "https://media.example.com/Resume3-0.jpg",
                            width = 10,
                            height = 10,
                            durationMs = null,
                        ),
                    ),
            )
        val missing =
            preparedMedia(
                0,
                File(context.filesDir, "archive/originals/${"1".repeat(64)}.jpg"),
                File(context.filesDir, "archive/thumbnails/${"2".repeat(64)}.jpg"),
            )
        writeResume(job.id, remote, listOf(missing))

        try {
            pipeline.preparePost(job, remote, { true }) { _, _ -> }
            fail("续传文件缺失时必须重新下载，而不是直接采用")
        } catch (error: ArchiveException) {
            // 文件缺失的续传项被忽略后会重新走下载；关键是不允许静默采用缺失文件。
            assertEquals("INVALID_RESPONSE", error.code)
        }
    }

    @Test
    fun `refreshResumeRemote accepts superset and rejects missing completed keys`() {
        val job = database.enqueue("https://www.instagram.com/p/Resume4/", "Resume4")
        val remote = remotePost("Resume4", mediaCount = 1)
        val original = storedFile("archive/originals", "a".repeat(64), ".jpg")
        val thumbnail = storedFile("archive/thumbnails", "b".repeat(64), ".jpg")
        writeResume(job.id, remote, listOf(preparedMedia(0, original, thumbnail)))

        val superset =
            remote.copy(
                media =
                    remote.media +
                        RemoteMedia(
                            sortIndex = 1,
                            mediaType = "image",
                            url = "https://scontent.cdninstagram.com/Resume4-1.jpg",
                            width = 10,
                            height = 10,
                            durationMs = null,
                        ),
            )
        assertTrue(pipeline.refreshResumeRemote(job.id, superset))
        assertEquals(2, pipeline.loadResume(job.id)!!.remote.media.size)

        val shrunk = remote.copy(media = emptyList())
        assertFalse(pipeline.refreshResumeRemote(job.id, shrunk))
        assertEquals(2, pipeline.loadResume(job.id)!!.remote.media.size)
    }

    @Test
    fun `corrupt resume falls back to null`() {
        val job = database.enqueue("https://www.instagram.com/p/Resume5/", "Resume5")
        val jobDirectory = File(context.filesDir, "archive/jobs/${job.id}").apply { mkdirs() }
        File(jobDirectory, "resume.json").writeText("{ 这不是合法 JSON")

        assertEquals(null, pipeline.loadResume(job.id))
    }

    private fun storedFile(directory: String, sha256: String, extension: String): File {
        val dir = File(context.filesDir, directory).apply { mkdirs() }
        return File(dir, "$sha256$extension").apply { writeText("content-$sha256") }
    }

    private fun writeResume(
        jobId: String,
        remote: RemotePost,
        completedMedia: List<PreparedMedia>,
    ) {
        val jobDirectory = File(context.filesDir, "archive/jobs/$jobId").apply { mkdirs() }
        val mediaJson = JSONArray()
        completedMedia.forEach { mediaJson.put(it.toJson()) }
        val pathsJson = JSONArray()
        completedMedia.forEach {
            pathsJson.put(it.localOriginalPath)
            pathsJson.put(it.localThumbnailPath)
        }
        File(jobDirectory, "resume.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("remote", remote.toJson())
                .put("completedMedia", mediaJson)
                .put("createdFilePaths", pathsJson)
                .toString(),
        )
    }

    private fun remotePost(sourcePostId: String, mediaCount: Int): RemotePost =
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
                (0 until mediaCount).map { index ->
                    RemoteMedia(
                        sortIndex = index,
                        mediaType = "image",
                        url =
                            if (index == 0) {
                                "https://scontent.cdninstagram.com/$sourcePostId-$index.jpg"
                            } else {
                                // 无效域名，保证失败用例不依赖真实网络。
                                "https://media.example.com/$sourcePostId-$index.jpg"
                            },
                        width = 10,
                        height = 10,
                        durationMs = null,
                    )
                },
        )

    private fun preparedMedia(sortIndex: Int, original: File, thumbnail: File): PreparedMedia =
        PreparedMedia(
            sortIndex = sortIndex,
            mediaType = "image",
            mimeType = "image/jpeg",
            width = 10,
            height = 10,
            durationMs = null,
            originalSize = original.length(),
            originalSha256 = original.name.substringBefore('.'),
            thumbnailSha256 = thumbnail.name.substringBefore('.'),
            localOriginalPath = original.absolutePath,
            localThumbnailPath = thumbnail.absolutePath,
        )
}

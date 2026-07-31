package com.mantou.photobook.archive

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentUris
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("DEPRECATION")
class ArchiveMediaActionsTest {
    private lateinit var context: Context
    private lateinit var activityController: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var database: ArchiveDatabase
    private lateinit var actions: ArchiveMediaActions
    private lateinit var media: List<SeededMedia>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
        ShadowContentResolver.reset()
        clearFileProviderCache()
        activityController = Robolectric.buildActivity(Activity::class.java).setup()
        activity = activityController.get()
        database = ArchiveDatabase(context)
        media = seedPost()
        actions = ArchiveMediaActions(activity, database)
    }

    @After
    fun tearDown() {
        clearLegacySavedFiles()
        database.close()
        activityController.pause().stop().destroy()
        context.deleteDatabase(ArchiveDatabase.DATABASE_NAME)
        File(context.filesDir, "archive").deleteRecursively()
    }

    @Test
    fun `single file share uses content uri and temporary read permission`() {
        val error = launchShare(listOf(media[0].id))

        assertNull(error)
        val shareIntent = startedShareIntent()
        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertEquals("image/jpeg", shareIntent.type)
        val uri = shareIntent.parcelableUriExtra(Intent.EXTRA_STREAM)
        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals("${activity.packageName}.fileprovider", uri.authority)
        assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, shareIntent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun `multiple images share with one chooser and video remains exclusive`() {
        val error = launchShare(listOf(media[0].id, media[1].id))

        assertNull(error)
        val shareIntent = startedShareIntent()
        assertEquals(Intent.ACTION_SEND_MULTIPLE, shareIntent.action)
        assertEquals("image/*", shareIntent.type)
        val uris = shareIntent.parcelableUriListExtra(Intent.EXTRA_STREAM)
        assertEquals(2, uris.size)
        assertEquals(2, shareIntent.clipData!!.itemCount)
        assertTrue(uris.all { it.scheme == "content" })
        assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        assertThrows(ArchiveException::class.java) {
            actions.share(listOf(media[0].id, media[2].id)) {}
        }
    }

    @Test
    fun `media store saves original image and video into PhotoBook albums`() {
        val shadowResolver = shadowOf(activity.contentResolver)
        shadowResolver.setNextDatabaseIdForInserts(40)
        val imageUri =
            ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                41,
            )
        val videoUri =
            ContentUris.withAppendedId(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                42,
            )
        val imageOutput = ByteArrayOutputStream()
        val videoOutput = ByteArrayOutputStream()
        shadowResolver.registerOutputStream(imageUri, imageOutput)
        shadowResolver.registerOutputStream(videoUri, videoOutput)

        val imageName = actions.save(media[0].id)
        val videoName = actions.save(media[2].id)

        assertTrue(imageName.endsWith(".jpg"))
        assertTrue(videoName.endsWith(".mp4"))
        assertArrayEquals(media[0].bytes, imageOutput.toByteArray())
        assertArrayEquals(media[2].bytes, videoOutput.toByteArray())

        val inserts = shadowResolver.insertStatements
        assertEquals(2, inserts.size)
        assertEquals(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), inserts[0].uri)
        assertEquals(
            "${Environment.DIRECTORY_PICTURES}/PhotoBook",
            inserts[0].contentValues.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
        )
        assertEquals(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), inserts[1].uri)
        assertEquals(
            "${Environment.DIRECTORY_MOVIES}/PhotoBook",
            inserts[1].contentValues.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
        )
        assertEquals(1, inserts[0].contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(1, inserts[1].contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))

        val updates = shadowResolver.updateStatements
        assertEquals(2, updates.size)
        assertEquals(imageUri, updates[0].uri)
        assertEquals(videoUri, updates[1].uri)
        assertEquals(0, updates[0].contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, updates[1].contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun `media store publish failure deletes pending item and reports failure`() {
        val provider =
            Robolectric.setupContentProvider(
                PublishFailureProvider::class.java,
                MediaStore.AUTHORITY,
            )

        val error = assertThrows(ArchiveException::class.java) {
            actions.save(media[0].id)
        }

        assertEquals("GALLERY_SAVE_FAILED", error.code)
        assertEquals(listOf(provider.insertedUri), provider.deletedUris)
    }

    @Test
    fun `media store returns the actual display name assigned by provider`() {
        Robolectric.setupContentProvider(
            RenamedMediaProvider::class.java,
            MediaStore.AUTHORITY,
        )

        val name = actions.save(media[0].id)

        assertEquals("PhotoBook_renamed.jpg", name)
    }

    @Test
    @Config(sdk = [28])
    fun `legacy save returns the actual unique file name`() {
        clearLegacySavedFiles()

        val firstName = actions.save(media[0].id)
        val secondName = actions.save(media[0].id)

        assertEquals("PhotoBook_${SOURCE_POST_ID}_1.jpg", firstName)
        assertEquals("PhotoBook_${SOURCE_POST_ID}_1_2.jpg", secondName)
        assertArrayEquals(media[0].bytes, File(legacyAlbumDirectory(), firstName).readBytes())
        assertArrayEquals(media[0].bytes, File(legacyAlbumDirectory(), secondName).readBytes())
    }

    @Test
    @Config(sdk = [28])
    fun `live photo video export saves only the motion object`() {
        clearLegacySavedFiles()

        val name = actions.save(media[0].id, ArchiveMediaActions.EXPORT_VIDEO)

        assertEquals("PhotoBook_${SOURCE_POST_ID}_1.mp4", name)
        assertArrayEquals(media[2].bytes, File(legacyVideoAlbumDirectory(), name).readBytes())
    }

    @Test
    @Config(sdk = [28])
    fun `legacy copy failure removes the partial target`() {
        clearLegacySavedFiles()
        actions =
            ArchiveMediaActions(activity, database) { source, output ->
                output.write(source.readBytes().take(3).toByteArray())
                throw IOException("copy failed")
            }

        val error = assertThrows(ArchiveException::class.java) {
            actions.save(media[0].id)
        }

        assertEquals("GALLERY_SAVE_FAILED", error.code)
        assertTrue(
            legacyAlbumDirectory().listFiles()
                .orEmpty()
                .none { it.name.startsWith("PhotoBook_${SOURCE_POST_ID}_1") },
        )
    }

    @Suppress("DEPRECATION")
    private fun startedShareIntent(): Intent {
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriExtra(key: String): Uri? = getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriListExtra(key: String): List<Uri> =
        getParcelableArrayListExtra<Uri>(key).orEmpty()

    private fun launchShare(mediaIds: List<String>): Exception? {
        var failure: Exception? = null
        actions.share(mediaIds) { failure = it }
        shadowOf(Looper.getMainLooper()).idle()
        return failure
    }

    private fun clearFileProviderCache() {
        val cacheField = FileProvider::class.java.getDeclaredField("sCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(null) as MutableMap<String, Any>).clear()
    }

    private fun legacyAlbumDirectory(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "PhotoBook",
        )

    private fun legacyVideoAlbumDirectory(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "PhotoBook",
        )

    private fun clearLegacySavedFiles() {
        listOf(legacyAlbumDirectory(), legacyVideoAlbumDirectory()).forEach { directory ->
            directory.listFiles()
                ?.filter { it.name.startsWith("PhotoBook_${SOURCE_POST_ID}_") }
                ?.forEach(File::delete)
        }
    }

    private fun seedPost(): List<SeededMedia> {
        val specs =
            listOf(
                MediaSpec("image", "image/jpeg", ".jpg", "image-one".toByteArray()),
                MediaSpec("image", "image/png", ".png", "image-two".toByteArray()),
                MediaSpec("video", "video/mp4", ".mp4", "video-one".toByteArray()),
            )
        val seeded =
            specs.mapIndexed { index, spec ->
                val sha = sha256(spec.bytes)
                val original =
                    File(context.filesDir, "archive/originals/$sha${spec.extension}").apply {
                        parentFile?.mkdirs()
                        writeBytes(spec.bytes)
                    }
                val thumbnailBytes = "thumbnail-$index".toByteArray()
                val thumbnailSha = sha256(thumbnailBytes)
                val thumbnail =
                    File(context.filesDir, "archive/thumbnails/$thumbnailSha.jpg").apply {
                        parentFile?.mkdirs()
                        writeBytes(thumbnailBytes)
                    }
                SeededMedia(
                    id = "$POST_ID:$index",
                    bytes = spec.bytes,
                    prepared =
                        PreparedMedia(
                            sortIndex = index,
                            mediaType = spec.mediaType,
                            mimeType = spec.mimeType,
                            width = 10,
                            height = 10,
                            durationMs = if (spec.mediaType == "video") 1_000 else null,
                            originalSize = original.length(),
                            originalSha256 = sha,
                            thumbnailSha256 = thumbnailSha,
                            localOriginalPath = original.absolutePath,
                            localThumbnailPath = thumbnail.absolutePath,
                            logicalIndex = if (index == 2) 0 else index,
                            mediaRole =
                                when (index) {
                                    0 -> MEDIA_ROLE_LIVE_STILL
                                    2 -> MEDIA_ROLE_LIVE_MOTION
                                    else -> MEDIA_ROLE_PRIMARY
                                },
                        ),
                )
            }
        val job = database.enqueue("https://www.instagram.com/p/$SOURCE_POST_ID/", SOURCE_POST_ID)
        database.commitCompletedJob(
            job.id,
            PreparedPost(
                sourcePostId = SOURCE_POST_ID,
                sourceUrl = "https://www.instagram.com/p/$SOURCE_POST_ID/",
                authorUsername = "author",
                authorDisplayName = "Author",
                authorProfileUrl = "https://www.instagram.com/author/",
                authorAvatarSha256 = null,
                localAvatarPath = null,
                caption = "share",
                publishedAt = 1_750_000_000_000,
                locationName = null,
                media = seeded.map(SeededMedia::prepared),
            ),
            DEVICE_ID,
        )
        return seeded
    }

    private data class MediaSpec(
        val mediaType: String,
        val mimeType: String,
        val extension: String,
        val bytes: ByteArray,
    )

    private data class SeededMedia(
        val id: String,
        val bytes: ByteArray,
        val prepared: PreparedMedia,
    )

    class PublishFailureProvider : ContentProvider() {
        var insertedUri: Uri? = null
            private set
        val deletedUris = mutableListOf<Uri>()

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri =
            ContentUris.withAppendedId(uri, 1).also { insertedUri = it }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            deletedUris += uri
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val output = File(requireNotNull(context).cacheDir, "media-store-publish-failure")
            return ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            )
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
    }

    class RenamedMediaProvider : ContentProvider() {
        private lateinit var output: File

        override fun onCreate(): Boolean {
            output = File(requireNotNull(context).cacheDir, "renamed-media-store-output")
            return true
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri =
            ContentUris.withAppendedId(uri, 1)

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 1

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 1

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            )

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf("PhotoBook_renamed.jpg"))
            }

        override fun getType(uri: Uri): String? = null
    }

    companion object {
        private const val SOURCE_POST_ID = "ShareMedia1"
        private const val POST_ID = "instagram:$SOURCE_POST_ID"
        private const val DEVICE_ID = "local-device-0001"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

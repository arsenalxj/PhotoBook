package com.mantou.photobook.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchiveLinkImporterTest {
    @Test
    fun `system share skips only the next automatic clipboard import`() {
        val gate = AutomaticClipboardImportGate()

        gate.markSystemShareReceived()

        assertTrue(gate.consumeSkip())
        assertFalse(gate.consumeSkip())
    }

    @Test
    fun `automatic clipboard accepts only a known timestamp within ten minutes`() {
        val now = 1_000_000L

        assertTrue(isRecentClipboardTimestamp(now - 10 * 60 * 1000L, now))
        assertFalse(isRecentClipboardTimestamp(now - 10 * 60 * 1000L - 1, now))
        assertFalse(isRecentClipboardTimestamp(0, now))
        assertFalse(isRecentClipboardTimestamp(now + 1, now))
    }

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
    fun `mobile xiaohongshu clipboard text enters unified queue with https`() {
        val request =
            ArchiveLinkImporter.parse(
                "吃饱了才有力气减肥 http://xhslink.cn/o/8cuxlUm1O8E " +
                    "小伙伴复制一下，打开【小红书】就能看到内容。",
            )!!

        assertEquals(SOURCE_PLATFORM_XIAOHONGSHU, request.sourcePlatform)
        assertEquals("https://xhslink.cn/o/8cuxlUm1O8E", request.sourceUrl)
        assertEquals(request.sourceUrl, request.requestKey)

        val job = ArchiveLinkImporter.enqueue(database, request)
        assertEquals("queued", job.status)
        assertEquals(SOURCE_PLATFORM_XIAOHONGSHU, job.sourcePlatform)
        assertEquals(request.requestKey, job.requestKey)
    }

    @Test
    fun `unsupported clipboard text is ignored`() {
        assertNull(ArchiveLinkImporter.parse("验证码 123456 https://example.com/private"))
    }
}

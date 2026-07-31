package com.mantou.photobook.archive

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstagramCookieClipboardTest {
    private lateinit var clipboard: ClipboardManager
    private lateinit var scheduler: FakeScheduler
    private lateinit var exporter: InstagramCookieClipboard

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        scheduler = FakeScheduler()
        exporter = InstagramCookieClipboard(clipboard, scheduler)
    }

    @Test
    fun `copy marks cookie sensitive and clears unchanged clip after delay`() {
        exporter.copy("csrftoken=csrf; sessionid=session-secret")

        assertEquals(
            "csrftoken=csrf; sessionid=session-secret",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )
        assertTrue(
            clipboard.primaryClipDescription?.extras?.getBoolean(
                ClipDescription.EXTRA_IS_SENSITIVE,
            ) == true,
        )
        assertEquals(InstagramCookieClipboard.CLEAR_DELAY_MILLIS, scheduler.delayMillis)

        scheduler.action?.invoke()

        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `scheduled clear preserves clipboard content replaced by user`() {
        exporter.copy("csrftoken=csrf; sessionid=session-secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "replacement"))

        scheduler.action?.invoke()

        assertEquals("replacement", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    private class FakeScheduler : CookieClearScheduler {
        var delayMillis: Long? = null
        var action: (() -> Unit)? = null

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            this.delayMillis = delayMillis
            this.action = action
        }
    }
}

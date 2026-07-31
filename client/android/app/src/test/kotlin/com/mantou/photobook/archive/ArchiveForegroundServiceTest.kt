package com.mantou.photobook.archive

import android.app.Notification
import com.mantou.photobook.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveForegroundServiceTest {
    @Test
    fun `foreground notification uses launcher icon alias`() {
        val controller = Robolectric.buildService(ArchiveForegroundService::class.java).create()
        try {
            val method =
                ArchiveForegroundService::class.java
                    .getDeclaredMethod(
                        "buildNotification",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
            val notification = method.invoke(controller.get(), "正在保存帖子", 0, 0) as Notification

            assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `unresolved xiaohongshu job uses a readable notification label`() {
        val job =
            CaptureJob(
                id = "job-1",
                sourcePostId = null,
                status = "downloading",
                attemptCount = 1,
                sourcePlatform = SOURCE_PLATFORM_XIAOHONGSHU,
                requestKey = "https://xhslink.cn/o/example",
            )

        assertEquals("正在保存小红书帖子（1/2）", archiveProgressText(job, 1, 2))
    }
}

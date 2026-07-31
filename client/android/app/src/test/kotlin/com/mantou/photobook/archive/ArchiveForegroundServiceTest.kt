package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveForegroundServiceTest {
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

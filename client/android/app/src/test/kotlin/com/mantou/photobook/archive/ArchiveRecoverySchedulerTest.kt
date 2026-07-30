package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveRecoverySchedulerTest {
    @Test
    fun `queued cancellation replans capture without touching sync`() {
        val capture = ArchiveRecoveryScheduler.captureUpdate(null, hasRunningCapture = false)

        assertEquals(RecoveryWorkKind.CAPTURE, capture?.kind)
        assertNull(capture?.delayMs)
        assertNotEquals(
            ArchiveRecoveryScheduler.CAPTURE_WORK_NAME,
            ArchiveRecoveryScheduler.SYNC_WORK_NAME,
        )
    }

    @Test
    fun `queued cancellation does not replace a running capture worker`() {
        assertNull(
            ArchiveRecoveryScheduler.captureUpdate(
                captureDelayMs = null,
                hasRunningCapture = true,
            ),
        )
    }

    @Test
    fun `sync retry replans only sync work`() {
        val update =
            ArchiveRecoveryScheduler.syncUpdate(
                R2SyncResult(shouldRetry = true, retryDelay = 5_000),
            )

        assertEquals(RecoveryWorkKind.SYNC, update.kind)
        assertEquals(5_000L, update.delayMs)
    }
}

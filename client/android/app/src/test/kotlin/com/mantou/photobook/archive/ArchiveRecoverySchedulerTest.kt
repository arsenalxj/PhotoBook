package com.mantou.photobook.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRecoverySchedulerTest {
    @Test
    fun `queued cancellation replans capture without touching backup`() {
        val capture = ArchiveRecoveryScheduler.captureUpdate(null, hasRunningCapture = false)

        assertEquals(RecoveryWorkKind.CAPTURE, capture?.kind)
        assertNull(capture?.delayMs)
        assertNotEquals(
            ArchiveRecoveryScheduler.CAPTURE_WORK_NAME,
            ArchiveRecoveryScheduler.BACKUP_WORK_NAME,
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
    fun `backup retry replans only backup work`() {
        val update =
            ArchiveRecoveryScheduler.backupUpdate(
                R2BackupResult(shouldRetry = true, retryDelay = 5_000),
            )

        assertEquals(RecoveryWorkKind.BACKUP, update.kind)
        assertEquals(5_000L, update.delayMs)
    }

    @Test
    fun `existing pending backups request a keep-only immediate recovery`() {
        val update = ArchiveRecoveryScheduler.existingBackupUpdate(hasPendingBackups = true)

        assertEquals(RecoveryWorkKind.BACKUP, update?.kind)
        assertEquals(0L, update?.delayMs)
        assertTrue(update?.keepExisting == true)
        assertNull(ArchiveRecoveryScheduler.existingBackupUpdate(hasPendingBackups = false))
    }
}

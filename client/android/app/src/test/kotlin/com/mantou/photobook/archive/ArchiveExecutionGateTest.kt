package com.mantou.photobook.archive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExecutionGateTest {
    @Test
    fun `foreground execution waits for current owner`() {
        assertTrue(ArchiveExecutionGate.tryAcquire())
        val waiting = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val contender =
            thread(start = true) {
                waiting.countDown()
                ArchiveExecutionGate.acquire()
                acquired.countDown()
                ArchiveExecutionGate.release()
            }

        try {
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            assertFalse(acquired.await(100, TimeUnit.MILLISECONDS))
        } finally {
            ArchiveExecutionGate.release()
        }

        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        contender.join(1_000)
        assertFalse(contender.isAlive)
    }
}

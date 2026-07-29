package com.mantou.photobook.archive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramLoginAttemptCoordinatorTest {
    @Test
    fun `cancel while validation is pending prevents session commit`() {
        val coordinator = InstagramLoginAttemptCoordinator()
        val attemptId = coordinator.begin()
        val validationStarted = CountDownLatch(1)
        val finishValidation = CountDownLatch(1)
        val saveCount = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val committed =
                executor.submit<Boolean> {
                    validationStarted.countDown()
                    assertTrue(finishValidation.await(2, TimeUnit.SECONDS))
                    coordinator.commitIfActive(attemptId) { saveCount.incrementAndGet() }
                }

            assertTrue(validationStarted.await(2, TimeUnit.SECONDS))
            coordinator.cancel()
            finishValidation.countDown()

            assertFalse(committed.get(2, TimeUnit.SECONDS))
            assertEquals(0, saveCount.get())
        } finally {
            finishValidation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `new login invalidates older attempt and active attempt commits once`() {
        val coordinator = InstagramLoginAttemptCoordinator()
        val oldAttempt = coordinator.begin()
        val currentAttempt = coordinator.begin()
        var saveCount = 0

        assertFalse(coordinator.commitIfActive(oldAttempt) { saveCount += 1 })
        assertTrue(coordinator.commitIfActive(currentAttempt) { saveCount += 1 })
        assertFalse(coordinator.commitIfActive(currentAttempt) { saveCount += 1 })
        assertEquals(1, saveCount)
    }
}

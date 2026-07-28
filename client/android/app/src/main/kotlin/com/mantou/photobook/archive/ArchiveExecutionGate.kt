package com.mantou.photobook.archive

import java.util.concurrent.Semaphore

object ArchiveExecutionGate {
    private val semaphore = Semaphore(1, true)

    fun tryAcquire(): Boolean = semaphore.tryAcquire()

    @Throws(InterruptedException::class)
    fun acquire() {
        semaphore.acquire()
    }

    fun release() {
        semaphore.release()
    }
}

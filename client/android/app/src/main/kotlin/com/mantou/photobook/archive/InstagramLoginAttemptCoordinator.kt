package com.mantou.photobook.archive

internal class InstagramLoginAttemptCoordinator {
    private var lastAttemptId = 0L
    private var activeAttemptId: Long? = null

    fun begin(): Long =
        synchronized(this) {
            lastAttemptId = if (lastAttemptId == Long.MAX_VALUE) 1L else lastAttemptId + 1L
            lastAttemptId.also { activeAttemptId = it }
        }

    fun currentAttempt(): Long? = synchronized(this) { activeAttemptId }

    fun isActive(attemptId: Long): Boolean =
        synchronized(this) { activeAttemptId == attemptId }

    fun cancel() {
        synchronized(this) { activeAttemptId = null }
    }

    fun cancelIfActive(attemptId: Long) {
        synchronized(this) {
            if (activeAttemptId == attemptId) activeAttemptId = null
        }
    }

    fun commitIfActive(
        attemptId: Long,
        commit: () -> Unit,
    ): Boolean =
        synchronized(this) {
            if (activeAttemptId != attemptId) return@synchronized false
            try {
                commit()
                true
            } finally {
                activeAttemptId = null
            }
        }
}

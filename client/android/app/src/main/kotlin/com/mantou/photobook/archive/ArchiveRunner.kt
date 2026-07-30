package com.mantou.photobook.archive

import android.content.Context

class ArchiveRunner(context: Context, private val database: ArchiveDatabase) {
    private val instagram = InstagramClient(context)
    private val media = MediaPipeline(context, database::isMediaShaReferenced)
    private val deviceId = DeviceIdentity(context).getOrCreate()

    init {
        media.cleanupStaleParts()
    }

    fun runPending(onProgress: (CaptureJob, Int, Int) -> Unit) {
        while (true) {
            val job = database.claimNextJob() ?: return
            ArchiveEventBus.emitJobChanged()
            var prepared: PreparedPost? = null
            var committed = false
            try {
                val remote =
                    instagram.fetchPost(job.sourcePostId) {
                        database.isJobAttemptActive(job.id, job.attemptCount, "fetching")
                    }
                ensureAttemptActive(job, "fetching")
                if (remote.sourcePostId != job.sourcePostId) {
                    throw ArchiveException("SOURCE_MISMATCH", "Instagram 返回了不同的帖子编号")
                }
                updateProgress(job, "fetching", "downloading", 0, remote.media.size)
                val preparedPost =
                    media.preparePost(
                        job = job,
                        remote = remote,
                        isAttemptActive = {
                            database.isJobAttemptActive(
                                job.id,
                                job.attemptCount,
                                "downloading",
                            )
                        },
                    ) { current, total ->
                        updateProgress(
                            job,
                            "downloading",
                            "downloading",
                            current,
                            total,
                        )
                        onProgress(job, current, total)
                    }
                prepared = preparedPost
                updateProgress(
                    job,
                    "downloading",
                    "committing",
                    remote.media.size,
                    remote.media.size,
                )
                if (!database.commitCompletedJob(
                        job.id,
                        job.attemptCount,
                        preparedPost,
                        deviceId,
                    )
                ) {
                    throw ArchiveAttemptStoppedException()
                }
                committed = true
            } catch (_: ArchiveAttemptStoppedException) {
                // 取消、删除或新 attempt 已接管时，旧 attempt 只做文件回滚。
            } catch (error: ArchiveException) {
                database.recordJobError(job.id, job.attemptCount, error)
            } catch (error: Exception) {
                database.recordJobError(
                    job.id,
                    job.attemptCount,
                    ArchiveException("INTERNAL_ERROR", "保存帖子时发生内部错误", error),
                )
            } finally {
                if (!committed) prepared?.let(media::rollbackPrepared)
                media.cleanupJob(job.id)
                database.completeJobCancellation(job.id, job.attemptCount)
                if (committed) ArchiveEventBus.emitArchiveChanged()
                ArchiveEventBus.emitJobChanged()
            }
        }
    }

    private fun ensureAttemptActive(job: CaptureJob, expectedStatus: String) {
        if (!database.isJobAttemptActive(job.id, job.attemptCount, expectedStatus)) {
            throw ArchiveAttemptStoppedException()
        }
    }

    private fun updateProgress(
        job: CaptureJob,
        expectedStatus: String,
        status: String,
        current: Int,
        total: Int,
    ) {
        if (!database.updateJobProgress(
                job.id,
                job.attemptCount,
                expectedStatus,
                status,
                current,
                total,
            )
        ) {
            throw ArchiveAttemptStoppedException()
        }
        ArchiveEventBus.emitJobChanged()
    }
}

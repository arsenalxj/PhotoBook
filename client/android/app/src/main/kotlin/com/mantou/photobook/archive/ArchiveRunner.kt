package com.mantou.photobook.archive

import android.content.Context

class ArchiveRunner(context: Context, private val database: ArchiveDatabase) {
    private val instagram = InstagramClient(context)
    private val xiaohongshu = XiaohongshuClient(context)
    private val media = MediaPipeline(context, database::isMediaShaReferenced)

    init {
        media.cleanupStaleParts()
    }

    fun runPending(onProgress: (CaptureJob, Int, Int) -> Unit) {
        while (true) {
            val job = database.claimNextJob() ?: return
            ArchiveEventBus.emitJobChanged()
            var committed = false
            var keepResume = false
            try {
                var resumedFromDisk = true
                var remote = media.loadResume(job.id)?.remote?.takeIf { matchesJob(job, it) }
                if (remote == null) {
                    resumedFromDisk = false
                    media.discardJob(job.id)
                    remote = fetchRemote(job, "fetching")
                    media.saveResume(job.id, remote)
                }
                updateProgress(job, "fetching", "downloading", 0, remote.media.size)
                val preparedPost =
                    try {
                        prepareMedia(job, remote, onProgress)
                    } catch (error: ArchiveException) {
                        if (!resumedFromDisk || error.code != "MEDIA_URL_EXPIRED") throw error
                        // 续传地址已过期：重新解析一次刷新地址后继续补缺失媒体。
                        val fresh = fetchRemote(job, "downloading")
                        if (!media.refreshResumeRemote(job.id, fresh)) {
                            media.discardJob(job.id)
                            media.saveResume(job.id, fresh)
                        }
                        remote = fresh
                        prepareMedia(job, fresh, onProgress)
                    }
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
                    )
                ) {
                    throw ArchiveAttemptStoppedException()
                }
                committed = true
            } catch (_: ArchiveAttemptStoppedException) {
                // 取消、删除或新 attempt 已接管时，旧 attempt 只做文件回滚。
            } catch (error: ArchiveException) {
                keepResume =
                    database.recordJobError(job.id, job.attemptCount, error) &&
                        (error.code == "NETWORK_ERROR" || error.code == "RATE_LIMITED")
            } catch (error: Exception) {
                database.recordJobError(
                    job.id,
                    job.attemptCount,
                    ArchiveException("INTERNAL_ERROR", "保存帖子时发生内部错误", error),
                )
            } finally {
                if (!committed && !keepResume) media.discardJob(job.id)
                if (committed) media.cleanupJob(job.id)
                database.completeJobCancellation(job.id, job.attemptCount)
                if (committed) ArchiveEventBus.emitArchiveChanged()
                ArchiveEventBus.emitJobChanged()
            }
        }
    }

    private fun matchesJob(job: CaptureJob, remote: RemotePost): Boolean =
        remote.sourcePlatform == job.sourcePlatform &&
            (job.sourcePostId == null || remote.sourcePostId == job.sourcePostId)

    private fun fetchRemote(job: CaptureJob, expectedStatus: String): RemotePost {
        val remote =
            when (job.sourcePlatform) {
                SOURCE_PLATFORM_INSTAGRAM -> {
                    val sourcePostId = job.sourcePostId
                        ?: throw ArchiveException("INVALID_RESPONSE", "Instagram 帖子编号尚未解析")
                    instagram.fetchPost(sourcePostId) {
                        database.isJobAttemptActive(job.id, job.attemptCount, expectedStatus)
                    }
                }
                SOURCE_PLATFORM_XIAOHONGSHU ->
                    xiaohongshu.fetchPost(database.jobSourceUrl(job.id)) {
                        database.isJobAttemptActive(job.id, job.attemptCount, expectedStatus)
                    }
                else -> throw ArchiveException("INVALID_URL", "不支持的帖子来源")
            }
        ensureAttemptActive(job, expectedStatus)
        if (!matchesJob(job, remote)) {
            throw ArchiveException("SOURCE_MISMATCH", "解析结果与分享任务不一致")
        }
        return remote
    }

    private fun prepareMedia(
        job: CaptureJob,
        remote: RemotePost,
        onProgress: (CaptureJob, Int, Int) -> Unit,
    ): PreparedPost =
        media.preparePost(
            job = job,
            remote = remote,
            isAttemptActive = {
                database.isJobAttemptActive(job.id, job.attemptCount, "downloading")
            },
        ) { current, total ->
            updateProgress(job, "downloading", "downloading", current, total)
            onProgress(job, current, total)
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

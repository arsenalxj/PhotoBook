package com.mantou.photobook.archive

import android.content.Context

class ArchiveRunner(context: Context, private val database: ArchiveDatabase) {
    private val instagram = InstagramClient(context)
    private val xiaohongshu = XiaohongshuClient(context)
    private val media = MediaPipeline(context, database::isMediaShaReferenced)
    private val deviceId = DeviceIdentity(context).getOrCreate()
    private val r2ConfigStore = R2ConfigStore(context)

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
                    when (job.sourcePlatform) {
                        SOURCE_PLATFORM_INSTAGRAM -> {
                            val sourcePostId = job.sourcePostId
                                ?: throw ArchiveException("INVALID_RESPONSE", "Instagram 帖子编号尚未解析")
                            instagram.fetchPost(sourcePostId) {
                                database.isJobAttemptActive(job.id, job.attemptCount, "fetching")
                            }
                        }
                        SOURCE_PLATFORM_XIAOHONGSHU ->
                            xiaohongshu.fetchPost(database.jobSourceUrl(job.id)) {
                                database.isJobAttemptActive(job.id, job.attemptCount, "fetching")
                            }
                        else -> throw ArchiveException("INVALID_URL", "不支持的帖子来源")
                    }
                ensureAttemptActive(job, "fetching")
                if (remote.sourcePlatform != job.sourcePlatform ||
                    (job.sourcePostId != null && remote.sourcePostId != job.sourcePostId)
                ) {
                    throw ArchiveException("SOURCE_MISMATCH", "解析结果与分享任务不一致")
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
                        r2ConfigStore.read()?.let { config ->
                            BackupDestination(config.backupTargetId, deviceId)
                        },
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

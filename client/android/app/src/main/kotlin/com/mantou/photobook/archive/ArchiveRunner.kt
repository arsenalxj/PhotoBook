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
            var prepared: PreparedPost? = null
            var committed = false
            try {
                val remote = instagram.fetchPost(job.sourcePostId)
                if (remote.sourcePostId != job.sourcePostId) {
                    throw ArchiveException("SOURCE_MISMATCH", "Instagram 返回了不同的帖子编号")
                }
                database.updateJobProgress(job.id, "downloading", 0, remote.media.size)
                val preparedPost =
                    media.preparePost(job, remote) { current, total ->
                        database.updateJobProgress(job.id, "downloading", current, total)
                        onProgress(job, current, total)
                    }
                prepared = preparedPost
                database.updateJobProgress(
                    job.id,
                    "committing",
                    remote.media.size,
                    remote.media.size,
                )
                database.commitCompletedJob(job.id, preparedPost, deviceId)
                committed = true
            } catch (error: ArchiveException) {
                database.recordJobError(job.id, error)
            } catch (error: Exception) {
                database.recordJobError(
                    job.id,
                    ArchiveException("INTERNAL_ERROR", "保存帖子时发生内部错误", error),
                )
            } finally {
                if (!committed) prepared?.let(media::rollbackPrepared)
                media.cleanupJob(job.id)
                ArchiveEventBus.emitArchiveChanged()
            }
        }
    }
}

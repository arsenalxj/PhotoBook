package com.mantou.photobook.archive

internal fun ArchiveDatabase.commitCompletedJob(
    jobId: String,
    post: PreparedPost,
    deviceId: String,
    backupTargetId: String? = null,
) {
    val claimed = checkNotNull(claimNextJob()) { "测试任务未进入执行队列" }
    check(claimed.id == jobId) { "测试领取了非预期任务" }
    check(
        updateJobProgress(
            jobId,
            claimed.attemptCount,
            "fetching",
            "committing",
            post.media.size,
            post.media.size,
        ),
    ) { "测试任务无法进入提交阶段" }
    check(
        commitCompletedJob(
            jobId,
            claimed.attemptCount,
            post,
            backupTargetId?.let { BackupDestination(it, deviceId) },
        ),
    ) {
        "测试任务提交被拒绝"
    }
}

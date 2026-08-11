package com.mantou.photobook.archive

data class BackupDestination(
    val backupTargetId: String,
    val deviceId: String,
)

data class PendingR2BackupJob(
    val backupSeq: Long,
    val backupTargetId: String,
    val deviceId: String,
    val postId: String,
    val sourcePlatform: String,
    val generation: Long,
    val snapshotJson: String,
)

data class OriginalMediaDescriptor(
    val mediaId: String,
    val postId: String,
    val sourcePostId: String,
    val mediaType: String,
    val mimeType: String,
    val sha256: String,
    val expectedSize: Long,
    val localPath: String?,
    val logicalIndex: Int,
    val mediaRole: String = MEDIA_ROLE_PRIMARY,
)

data class DeleteMediaSelectionResult(
    val postId: String,
    val postDeleted: Boolean,
)

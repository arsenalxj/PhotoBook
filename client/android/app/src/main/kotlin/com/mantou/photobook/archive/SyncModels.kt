package com.mantou.photobook.archive

import org.json.JSONObject

data class EntityVersion(
    val version: Long,
    val deviceId: String,
    val seq: Long,
) {
    init {
        require(version > 0 && version < Long.MAX_VALUE) { "同步操作版本无效" }
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "同步实体版本设备标识无效" }
        require(seq > 0) { "同步实体版本序号无效" }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put("version", version)
            .put("deviceId", deviceId)
            .put("seq", seq)

    companion object {
        private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

        fun fromJson(json: JSONObject): EntityVersion =
            EntityVersion(
                version = json.getLong("version"),
                deviceId = json.getString("deviceId"),
                seq = json.getLong("seq"),
            )
    }
}

data class PendingSyncOperation(
    val seq: Long,
    val operation: String,
    val payloadJson: String,
)

data class MissingPreview(
    val postId: String,
    val mediaId: String?,
    val kind: String,
    val sha256: String,
)

data class OriginalMediaDescriptor(
    val mediaId: String,
    val postId: String,
    val sourcePostId: String,
    val sortIndex: Int,
    val mediaType: String,
    val mimeType: String,
    val sha256: String,
    val expectedSize: Long,
    val localPath: String?,
    val sourcePlatform: String = SOURCE_PLATFORM_INSTAGRAM,
    val logicalIndex: Int = sortIndex,
    val mediaRole: String = MEDIA_ROLE_PRIMARY,
)

data class DeleteMediaSelectionResult(
    val postId: String,
    val postDeleted: Boolean,
)
